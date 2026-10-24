/**
 * M14 语音前端能力（M15.11-R1 重构录音路径）：
 * - WavRecorder（主路径）：WebAudio 采集 PCM(16kHz 单声道)→封装 WAV——统一音频格式，
 *   从根上消除「浏览器容器(webm/mp4)×扩展名×魔数」匹配故障；MediaRecorder 降级保留；
 * - TTS 播放管理（单实例 Audio，切换/关页自动清理，MOCK 标识透传）
 *
 * 红线：STT 识别文字仅回填输入框，绝不自动发送；录音上限后端下发+前端兜底；音频仅内存。
 */

const FALLBACK_MIME = [
  'audio/webm;codecs=opus',
  'audio/webm',
  'audio/ogg;codecs=opus',
  'audio/mp4',
  'audio/mpeg'
]

export function recorderSupported() {
  return !!(navigator.mediaDevices && navigator.mediaDevices.getUserMedia)
}

export function isSecureContextOk() {
  return window.isSecureContext || location.hostname === 'localhost' || location.hostname === '127.0.0.1'
}

const TARGET_RATE = 16000

/** WebAudio WAV 录音器：getUserMedia → AudioContext(16kHz) → PCM Int16 → 44 字节 WAV 头封装 */
export class WavRecorder {
  constructor({ maxSeconds = 60, onTick } = {}) {
    this.maxSeconds = maxSeconds
    this.onTick = onTick
    this.stream = null
    this.ctx = null
    this.processor = null
    this.source = null
    this.chunks = []
    this.samples = 0
    this.maxSamples = 0
    this.startedAt = 0
    this.timer = null
    this.elapsed = 0
    this.cancelled = false
    this.active = false
    this.sampleRate = TARGET_RATE
  }

  async start() {
    if (this.active) throw new Error('已有录音进行中')
    if (!isSecureContextOk()) throw new Error('语音功能需要 HTTPS 或 localhost 环境')
    if (!recorderSupported() || !window.AudioContext) {
      throw new Error('当前浏览器不支持录音（需 Chrome/Edge 126+ 或 Safari 14.1+）')
    }
    try {
      this.stream = await navigator.mediaDevices.getUserMedia({ audio: true })
    } catch (e) {
      if (e && (e.name === 'NotAllowedError' || e.name === 'SecurityError')) {
        throw new Error('麦克风权限被拒绝：请在浏览器地址栏允许麦克风后重试')
      }
      if (e && e.name === 'NotFoundError') {
        throw new Error('未检测到可用麦克风设备')
      }
      throw new Error('无法访问麦克风：' + (e.message || e.name))
    }
    try {
      this.ctx = new (window.AudioContext || window.webkitAudioContext)({ sampleRate: TARGET_RATE })
    } catch (e) {
      this.ctx = new (window.AudioContext || window.webkitAudioContext)()
    }
    this.sampleRate = this.ctx.sampleRate
    this.maxSamples = this.sampleRate * this.maxSeconds
    this.chunks = []
    this.samples = 0
    this.cancelled = false
    this.active = true
    this.startedAt = Date.now()
    this.elapsed = 0
    this.source = this.ctx.createMediaStreamSource(this.stream)
    this.processor = this.ctx.createScriptProcessor(4096, 1, 1)
    this.processor.onaudioprocess = (ev) => {
      if (!this.active) return
      const input = ev.inputBuffer.getChannelData(0)
      const remain = this.maxSamples - this.samples
      const n = Math.min(input.length, remain)
      if (n <= 0) {
        this.stop().catch(() => {})
        return
      }
      const pcm = new Int16Array(n)
      for (let i = 0; i < n; i++) {
        const s = Math.max(-1, Math.min(1, input[i]))
        pcm[i] = s < 0 ? s * 0x8000 : s * 0x7fff
      }
      this.chunks.push(pcm)
      this.samples += n
    }
    this.source.connect(this.processor)
    this.processor.connect(this.ctx.destination)
    this.timer = setInterval(() => {
      this.elapsed = Math.floor((Date.now() - this.startedAt) / 1000)
      if (this.onTick) this.onTick(this.elapsed)
    }, 250)
  }

  stop() {
    return new Promise((resolve) => {
      if (!this.active) { resolve(null); return }
      this.active = false
      this.cleanupTimer()
      const cancelled = this.cancelled
      try { if (this.processor) this.processor.disconnect() } catch (e) { /* 忽略 */ }
      try { if (this.source) this.source.disconnect() } catch (e) { /* 忽略 */ }
      const finish = () => {
        this.releaseTracks()
        if (this.ctx && this.ctx.state !== 'closed') {
          try { this.ctx.close() } catch (e) { /* 忽略 */ }
        }
        this.ctx = null
        if (cancelled) { resolve(null); return }
        const blob = new Blob([encodeWav(this.chunks, this.samples, this.sampleRate)],
          { type: 'audio/wav' })
        this.chunks = []
        resolve(blob)
      }
      setTimeout(finish, 60)
    })
  }

  cancel() {
    this.cancelled = true
    this.cleanupTimer()
    this.active = false
    try { if (this.processor) this.processor.disconnect() } catch (e) { /* 忽略 */ }
    try { if (this.source) this.source.disconnect() } catch (e) { /* 忽略 */ }
    this.releaseTracks()
    if (this.ctx && this.ctx.state !== 'closed') {
      try { this.ctx.close() } catch (e) { /* 忽略 */ }
    }
    this.ctx = null
    this.chunks = []
  }

  cleanupTimer() {
    if (this.timer) { clearInterval(this.timer); this.timer = null }
  }

  releaseTracks() {
    if (this.stream) {
      this.stream.getTracks().forEach(t => { try { t.stop() } catch (e) { /* 忽略 */ } })
      this.stream = null
    }
  }

  destroy() {
    this.cancel()
  }
}

function encodeWav(pcmChunks, totalSamples, sampleRate) {
  const dataLen = totalSamples * 2
  const buf = new ArrayBuffer(44 + dataLen)
  const view = new DataView(buf)
  writeAscii(view, 0, 'RIFF')
  view.setUint32(4, 36 + dataLen, true)
  writeAscii(view, 8, 'WAVE')
  writeAscii(view, 12, 'fmt ')
  view.setUint32(16, 16, true)
  view.setUint16(20, 1, true)
  view.setUint16(22, 1, true)
  view.setUint32(24, sampleRate, true)
  view.setUint32(28, sampleRate * 2, true)
  view.setUint16(32, 2, true)
  view.setUint16(34, 16, true)
  writeAscii(view, 36, 'data')
  view.setUint32(40, dataLen, true)
  let off = 44
  for (const pcm of pcmChunks) {
    for (let i = 0; i < pcm.length; i++) {
      view.setInt16(off, pcm[i], true)
      off += 2
    }
  }
  return buf
}

function writeAscii(view, offset, s) {
  for (let i = 0; i < s.length; i++) {
    view.setUint8(offset + i, s.charCodeAt(i))
  }
}

function pickMime() {
  if (!window.MediaRecorder) return null
  for (const m of FALLBACK_MIME) {
    if (MediaRecorder.isTypeSupported(m)) return m
  }
  return null
}

/** M15.11-R1 降级路径：MediaRecorder 容器录音（上传文件名由调用方按实际 mime 精确映射） */
export class MediaRecorderFallback {
  constructor({ maxSeconds = 60, onTick } = {}) {
    this.maxSeconds = maxSeconds
    this.onTick = onTick
    this.stream = null
    this.recorder = null
    this.chunks = []
    this.startedAt = 0
    this.timer = null
    this.elapsed = 0
    this.cancelled = false
    this.active = false
  }

  async start() {
    if (this.active) throw new Error('已有录音进行中')
    if (!isSecureContextOk()) throw new Error('语音功能需要 HTTPS 或 localhost 环境')
    const mime = pickMime()
    if (!mime) throw new Error('当前浏览器不支持可用的录音编码格式')
    try {
      this.stream = await navigator.mediaDevices.getUserMedia({ audio: true })
    } catch (e) {
      if (e && (e.name === 'NotAllowedError' || e.name === 'SecurityError')) {
        throw new Error('麦克风权限被拒绝：请在浏览器地址栏允许麦克风后重试')
      }
      if (e && e.name === 'NotFoundError') {
        throw new Error('未检测到可用麦克风设备')
      }
      throw new Error('无法访问麦克风：' + (e.message || e.name))
    }
    this.mime = mime
    this.chunks = []
    this.cancelled = false
    this.active = true
    this.startedAt = Date.now()
    this.elapsed = 0
    this.recorder = new MediaRecorder(this.stream, { mimeType: this.mime })
    this.recorder.ondataavailable = (ev) => {
      if (ev.data && ev.data.size) this.chunks.push(ev.data)
    }
    this.recorder.start(250)
    this.timer = setInterval(() => {
      this.elapsed = Math.floor((Date.now() - this.startedAt) / 1000)
      if (this.onTick) this.onTick(this.elapsed)
      if (this.elapsed >= this.maxSeconds) {
        this.stop().catch(() => {})
      }
    }, 250)
  }

  stop() {
    return new Promise((resolve, reject) => {
      if (!this.active) { resolve(null); return }
      const rec = this.recorder
      this.cleanupTimer()
      rec.onstop = () => {
        this.releaseTracks()
        this.active = false
        if (this.cancelled) { resolve(null); return }
        const blob = new Blob(this.chunks, { type: this.mime })
        this.chunks = []
        resolve(blob)
      }
      try { rec.stop() } catch (e) { this.releaseTracks(); this.active = false; reject(e) }
    })
  }

  cancel() {
    this.cancelled = true
    this.cleanupTimer()
    try { if (this.recorder && this.recorder.state !== 'inactive') this.recorder.stop() } catch (e) { /* 忽略 */ }
    this.releaseTracks()
    this.chunks = []
    this.active = false
  }

  cleanupTimer() {
    if (this.timer) { clearInterval(this.timer); this.timer = null }
  }

  releaseTracks() {
    if (this.stream) {
      this.stream.getTracks().forEach(t => { try { t.stop() } catch (e) { /* 忽略 */ } })
      this.stream = null
    }
  }

  destroy() {
    this.cancel()
  }
}

/** TTS 播放管理：全局单例 Audio；新播报自动停止旧的；页面卸载清理 */
let currentAudio = null
let currentUrl = null

export function stopSpeak() {
  if (currentAudio) {
    try {
      currentAudio.onended = null; currentAudio.onerror = null
      currentAudio.pause()
      currentAudio.src = ''   // M15.22：显式断源（部分浏览器 pause 后仍继续缓冲播放）
      currentAudio.load()
    } catch (e) { /* 忽略 */ }
    currentAudio = null
  }
  if (currentUrl) {
    try { URL.revokeObjectURL(currentUrl) } catch (e) { /* 忽略 */ }
    currentUrl = null
  }
}

export function playTts(arrayBuffer, contentType) {
  stopSpeak()
  const blob = new Blob([arrayBuffer], { type: contentType || 'audio/wav' })
  currentUrl = URL.createObjectURL(blob)
  const audio = new Audio(currentUrl)
  currentAudio = audio
  // M15.25：resolve 提升外部变量（play().catch 在 executor 外——原代码 resolve 越界 ReferenceError）
  let resolveDone = null
  const done = new Promise((resolve) => { resolveDone = resolve })
  audio.onended = () => { if (currentAudio === audio) { stopSpeak() } if (resolveDone) resolveDone() }
  audio.onerror = () => { stopSpeak(); if (resolveDone) resolveDone() }
  audio.play().catch(() => { stopSpeak(); if (resolveDone) resolveDone() })
  return { stop: stopSpeak, done }
}

/** M15.20-F：流式播报全局状态（停止/切答/关页释放） */
let streamCtx = null

export function stopSpeakStream() {
  if (streamCtx) {
    try { streamCtx.abort && streamCtx.abort() } catch (e) { /* 忽略 */ }
    // M15.25：停止即结束 done 生命周期（watcher 清理——防 setInterval 泄漏悬挂）
    try { if (streamCtx.onDone) streamCtx.onDone() } catch (e0) { /* 忽略 */ }
    try {
      streamCtx.sources.forEach((s) => { try { s.onended = null; s.stop() } catch (e2) { /* 已停 */ } })
    } catch (e3) { /* 忽略 */ }
    // M15.22：suspend 立即静音（同步语义）+ close 异步释放——双保险确保停止立即生效
    try { streamCtx.audioCtx.suspend() } catch (e5) { /* 忽略 */ }
    try { streamCtx.audioCtx.close() } catch (e4) { /* 忽略 */ }
    streamCtx = null
  }
}

if (typeof window !== 'undefined') {
  window.addEventListener('beforeunload', () => { stopSpeak(); stopSpeakStream() })
}

/**
 * M15.20-F：TTS 真流式播放。
 * WAV（audio/wav）：ReadableStream 边收边解码——首个分片解析 WAV 头，后续分片 PCM→AudioBuffer
 * 逐段 schedule（边下边播，非整段 arrayBuffer）。其他编码（audio/mpeg 等）MSE/WAV 流式不可用时
 * 降级整段播放并准确标记 mode='buffered'。
 * 返回 { mode: 'stream'|'buffered', stop, done }；停止/切换/关页释放 AudioContext 与 reader。
 */
export async function playTtsStream(response, contentType) {
  const ct = (contentType || 'audio/wav').toLowerCase()
  const isWav = ct.includes('wav')
  if (!response.body || !isWav || typeof window.AudioContext === 'undefined') {
    const buf = await response.arrayBuffer()
    const r = playTts(buf, contentType)
    return { mode: 'buffered', stop: r.stop, done: r.done }
  }
  stopSpeak()
  stopSpeakStream()
  const audioCtx = new (window.AudioContext || window.webkitAudioContext)()
  const abort = new AbortController()
  const ctx = { audioCtx, abort, sources: [], finished: false, lastError: null }
  streamCtx = ctx
  const reader = response.body.getReader()

  let pBuf = new Uint8Array(0)      // 待消费字节（跨分片累积）
  let header = null                  // {sampleRate, channels, bits, dataStart, dataTotal}
  let consumedBytes = 0              // data 区已消费字节
  let scheduledDuration = 0
  let started = false
  let stopped = false
  let readerDone = false

  function markDone() {
    if (!ctx.finished) { ctx.finished = true; if (ctx.onDone) ctx.onDone() }
  }

  // 播放结束判定：网络读尽且音频实际播完（currentTime>=调度末尾）——与网络结束分离
  const done = new Promise((resolve) => { ctx.onDone = resolve })
  let watcherRounds = 0
  const watcher = setInterval(() => {
    watcherRounds++
    if (stopped || ctx.finished) { clearInterval(watcher); markDone(); return }
    if (readerDone && scheduledDuration > 0 && audioCtx.currentTime >= scheduledDuration - 0.05) {
      clearInterval(watcher)
      markDone()
      return
    }
    if (watcherRounds > 300) { clearInterval(watcher); markDone() } // M15.25：30s 上限防泄漏
  }, 100)

  function parseWavHeader(u8) {
    const view = new DataView(u8.buffer, u8.byteOffset, u8.byteLength)
    if (u8.length < 12 || String.fromCharCode(u8[0], u8[1], u8[2], u8[3]) !== 'RIFF') return null
    let off = 12
    let sampleRate = 16000, channels = 1, bits = 16, dataStart = -1, dataTotal = -1
    while (off + 8 <= u8.length) {
      const id = String.fromCharCode(u8[off], u8[off + 1], u8[off + 2], u8[off + 3])
      const size = view.getUint32(off + 4, true)
      if (id === 'fmt ') {
        if (off + 8 + 16 > u8.length) return null
        channels = view.getUint16(off + 10, true)
        sampleRate = view.getUint32(off + 12, true)
        bits = view.getUint16(off + 22, true)
      } else if (id === 'data') {
        dataStart = off + 8
        dataTotal = size
        break
      }
      off += 8 + size
    }
    return dataStart < 0 ? null : { sampleRate, channels, bits, dataStart, dataTotal }
  }

  function schedulePcm(bytes) {
    if (!bytes || bytes.length === 0) return
    const { sampleRate, channels, bits } = header
    const frame = (bits / 8) * channels
    const usable = Math.floor(bytes.length / frame) * frame
    if (usable <= 0) return
    const n = usable / frame
    const buffer = audioCtx.createBuffer(channels, n, sampleRate)
    for (let ch = 0; ch < channels; ch++) {
      const out = buffer.getChannelData(ch)
      for (let i = 0; i < n; i++) {
        const pos = i * frame + ch * (bits / 8)
        if (bits === 16) {
          let v = bytes[pos] | (bytes[pos + 1] << 8)
          if (v >= 32768) v -= 65536
          out[i] = v / 32768
        } else if (bits === 8) {
          out[i] = (bytes[pos] - 128) / 128
        }
      }
    }
    const source = audioCtx.createBufferSource()
    source.buffer = buffer
    source.connect(audioCtx.destination)
    const at = Math.max(audioCtx.currentTime + 0.02, scheduledDuration)
    source.start(at)
    scheduledDuration = at + buffer.duration
    ctx.sources.push(source)
    started = true
  }

  ;(async () => {
    try {
      while (true) {
        const { done: rd, value } = await reader.read()
        if (rd) { readerDone = true; break }
        if (stopped) break
        const merged = new Uint8Array(pBuf.length + value.length)
        merged.set(pBuf, 0); merged.set(value, pBuf.length)
        if (!header) {
          header = parseWavHeader(merged)
          if (!header) { pBuf = merged; continue } // 头不完整：全量保留
          pBuf = merged.slice(header.dataStart)    // 头只消费一次
        } else {
          pBuf = merged
        }
        const frame = (header.bits / 8) * header.channels
        const usable = Math.floor(pBuf.length / frame) * frame
        if (usable > 0) {
          schedulePcm(pBuf.slice(0, usable))
          consumedBytes += usable
          pBuf = pBuf.slice(usable)
        }
        if (header.dataTotal > 0 && consumedBytes >= header.dataTotal) { readerDone = true; break }
      }
      // 尾部残留：剩余完整帧补播（<1 帧忽略）
      if (!stopped && header && pBuf.length > 0) {
        const frame = (header.bits / 8) * header.channels
        const usable = Math.floor(pBuf.length / frame) * frame
        if (usable > 0) schedulePcm(pBuf.slice(0, usable))
      }
    } catch (e) {
      if (!stopped) ctx.lastError = (e && e.message) || '语音流读取中断'
    }
  })()

  return {
    mode: 'chunked-playback',
    stop: () => {
      stopped = true
      try { abort.abort() } catch (e) { /* 忽略 */ }
      try { reader.cancel() } catch (e2) { /* 忽略 */ }
      stopSpeakStream()
    },
    done,
    getError: () => ctx.lastError || null
  }
}

if (typeof window !== 'undefined') {
  window.addEventListener('beforeunload', () => { stopSpeak(); stopSpeakStream() })
}

if (typeof window !== 'undefined') {
  window.addEventListener('beforeunload', () => { stopSpeak(); stopSpeakStream() })
}

/** M15.22 诊断探针：控制台 window.__kzaiSpeech 状态（停止链路验证） */
if (typeof window !== 'undefined') {
  window.__kzaiSpeech = {
    hasStreamCtx: () => !!streamCtx,
    audioCtxState: () => (streamCtx && streamCtx.audioCtx ? streamCtx.audioCtx.state : 'none'),
    sourcesCount: () => (streamCtx ? streamCtx.sources.length : 0),
    forceStop: () => { stopSpeakStream() }
  }
}
