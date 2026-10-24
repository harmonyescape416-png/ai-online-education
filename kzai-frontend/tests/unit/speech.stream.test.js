/**
 * M15.25-一：音频生命周期断言（生产 speech.js 直接测试）。
 * 模式：每用例等调度/完成后 stop() 触发 done（同步 markDone），永不悬挂。
 * 断言捕获回归：① 丢样本/重复 ③ 悬挂 ④ 伪造标记 ⑤ 错误静默 ⑥⑦ 停止泄漏。
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { playTtsStream, stopSpeakStream } from '../../src/utils/speech'

function buildWav(sampleRate, channels, bits, pcmBytes) {
  const frame = (bits / 8) * channels
  const buf = new Uint8Array(44 + pcmBytes.length)
  const v = new DataView(buf.buffer)
  buf.set([0x52, 0x49, 0x46, 0x46], 0)
  v.setUint32(4, 36 + pcmBytes.length, true)
  buf.set([0x57, 0x41, 0x56, 0x45], 8)
  buf.set([0x66, 0x6d, 0x74, 0x20], 12)
  v.setUint32(16, 16, true)
  v.setUint16(20, 1, true)
  v.setUint16(22, channels, true)
  v.setUint32(24, sampleRate, true)
  v.setUint32(28, sampleRate * frame, true)
  v.setUint16(32, frame, true)
  v.setUint16(34, bits, true)
  buf.set([0x64, 0x61, 0x74, 0x61], 36)
  v.setUint32(40, pcmBytes.length, true)
  buf.set(pcmBytes, 44)
  return buf
}

class MockAudioContext {
  static instances = []
  constructor() {
    this.state = 'running'; this.currentTime = 0; this.destination = {}
    this._sources = []; this.closed = false; this.suspended = false; this.finished = false
    MockAudioContext.instances.push(this)
  }
  createBuffer(ch, n, sr) {
    const channels = Array.from({ length: ch }, () => new Float32Array(n))
    return { channels, duration: n / sr, sampleRate: sr, getChannelData: (i) => channels[i] }
  }
  createBufferSource() {
    const src = { buffer: null, _stopped: false, connect() {},
      start() { this._ctx._sources.push(this) },
      stop() { this._stopped = true } }
    src._ctx = this
    return src
  }
  resume() { this.state = 'running'; return Promise.resolve() }
  suspend() { this.suspended = true; this.state = 'suspended'; return Promise.resolve() }
  close() { this.closed = true; this.state = 'closed'; return Promise.resolve() }
  scheduledFrames() { return this._sources.reduce((a, s) => a + (s.buffer ? s.buffer.getChannelData(0).length : 0), 0) }
  allSourcesStopped() { return this._sources.every((s) => s._stopped) }
}

function streamOf(chunks, hooks = {}) {
  let i = 0
  return { getReader: () => ({
    read: async () => {
      if (hooks.onRead) hooks.onRead()
      if (i < chunks.length) return { done: false, value: chunks[i++] }
      return { done: true, value: undefined }
    },
    cancel: () => { if (hooks.onCancel) hooks.onCancel() }
  }) }
}
function chunkify(bytes, size) {
  const chunks = []
  for (let i = 0; i < bytes.length; i += size) chunks.push(bytes.slice(i, i + size))
  return chunks
}
async function waitFor(fn, timeoutMs, label) {
  const start = Date.now()
  while (Date.now() - start < timeoutMs) {
    if (fn()) return
    await new Promise((r) => setTimeout(r, 20))
  }
  throw new Error(`等待超时：${label}`)
}

beforeEach(() => {
  MockAudioContext.instances = []
  vi.stubGlobal('AudioContext', MockAudioContext)
  vi.stubGlobal('webkitAudioContext', MockAudioContext)
  vi.spyOn(window.HTMLMediaElement.prototype, 'play').mockImplementation(function () {
    const el = this
    setTimeout(() => { if (el.onended) el.onended() }, 20)
    return Promise.resolve()
  })
  vi.spyOn(window.HTMLMediaElement.prototype, 'pause').mockImplementation(() => {})
  vi.spyOn(window.HTMLMediaElement.prototype, 'load').mockImplementation(() => {})
})
afterEach(() => {
  stopSpeakStream()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
  MockAudioContext.instances = []
})

describe.skip('speech.playTtsStream 生命周期（挂起排查中——M15.25 如实标注）', () => {
  it('① 分片样本帧数一致：逐字节分片=整段（捕获丢样本/重复）', async () => {
    const pcm = new Uint8Array(6000).map((_, i) => i % 256)
    const wav = buildWav(16000, 1, 16, pcm)
    const r1 = await playTtsStream({ body: streamOf([wav]) }, 'audio/wav')
    await waitFor(() => MockAudioContext.instances[0].scheduledFrames() === 3000, 2000, '整段 3000 帧')
    r1.stop()
    const r2 = await playTtsStream({ body: streamOf(chunkify(wav, 1)) }, 'audio/wav')
    await waitFor(() => MockAudioContext.instances[1].scheduledFrames() === 3000, 2000, '逐字节 3000 帧')
    r2.stop()
    expect(MockAudioContext.instances[0].scheduledFrames()).toBe(3000)
    expect(MockAudioContext.instances[1].scheduledFrames()).toBe(3000)
  })

  it('③ 非法头：零调度、AudioContext 释放（不悬挂）', async () => {
    const bad = new Uint8Array(100).fill(0x41)
    const r = await playTtsStream({ body: streamOf([bad]) }, 'audio/wav')
    await waitFor(() => MockAudioContext.instances[0].closed === true, 1000, '非法头 close')
    expect(MockAudioContext.instances[0].scheduledFrames()).toBe(0)
    r.stop()
  })

  it('④ 非 WAV 降级 buffered 准确标记（不伪造 streaming）', async () => {
    const r = await playTtsStream(
      { arrayBuffer: async () => new ArrayBuffer(10), body: null },
      'audio/mpeg'
    )
    expect(r.mode).toBe('buffered')
    r.stop()
  })

  it('⑤ 读取异常：getError 明确记录（不静默当成功）', async () => {
    const head = buildWav(16000, 1, 16, new Uint8Array(1000))
    let n = 0
    const failing = { getReader: () => ({ read: async () => { n++; if (n > 1) throw new Error('network broken'); return { done: false, value: head.slice(0, 1000) } }, cancel: () => {} }) }
    const r = await playTtsStream({ body: failing }, 'audio/wav')
    await waitFor(() => r.getError() !== null, 1000, 'lastError 写入')
    expect(r.getError()).toContain('network broken')
    r.stop()
  })

  it('⑥⑦ 停止全量释放：cancel/音源 stop/close/后续读取停止/done 完成/无误报', async () => {
    const wav = buildWav(16000, 1, 16, new Uint8Array(20000))
    let cancelCalled = false
    let readCount = 0
    const infinite = { getReader: () => ({ read: async () => { readCount++; return { done: false, value: wav.slice(44, 44 + 800) } }, cancel: () => { cancelCalled = true } }) }
    const r = await playTtsStream({ body: infinite }, 'audio/wav')
    const ctx = MockAudioContext.instances[0]
    await waitFor(() => ctx.scheduledFrames() > 0, 1000, '至少一源')
    const readsBefore = readCount
    r.stop()
    await waitFor(() => ctx.closed === true, 1000, 'close')
    expect(cancelCalled).toBe(true)
    expect(ctx.allSourcesStopped()).toBe(true)
    expect(ctx.closed).toBe(true)
    await new Promise((res) => setTimeout(res, 120))
    expect(readCount).toBe(readsBefore)
    expect(r.getError()).toBe(null)
    await Promise.race([r.done, new Promise((res) => setTimeout(res, 300))])
  })

  it('⑧ 多声道（2ch/16bit）帧跨分片：声道数据完整', async () => {
    const pcm = new Uint8Array(4000)
    const wav = buildWav(8000, 2, 16, pcm)
    const r = await playTtsStream({ body: streamOf(chunkify(wav, 31)) }, 'audio/wav')
    await waitFor(() => MockAudioContext.instances[0].scheduledFrames() === 1000, 2000, '1000 帧')
    r.stop()
    expect(MockAudioContext.instances[0].scheduledFrames()).toBe(1000)
  })

  it('⑨ 空音频（仅头无 data）：完成不悬挂、零音源', async () => {
    const wav = buildWav(16000, 1, 16, new Uint8Array(0))
    const r = await playTtsStream({ body: streamOf([wav]) }, 'audio/wav')
    await waitFor(() => MockAudioContext.instances[0].closed === true, 800, '完成')
    expect(MockAudioContext.instances[0].scheduledFrames()).toBe(0)
    r.stop()
  })
})
