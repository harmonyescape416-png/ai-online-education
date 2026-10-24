// M15.21：WAV 分片解析单元测试（纯逻辑：extractWavHeader/consumeData 等价逻辑）
// 覆盖：头拆分/单字节分片/奇数边界/多声道/截断/样本一致性
import assert from 'assert'

// 复刻 speech.js 的解析与帧消费逻辑（纯函数版本——用于测试）
function parseWavHeader(u8) {
  const view = new DataView(u8.buffer, u8.byteOffset, u8.byteLength)
  if (u8.length < 12 || String.fromCharCode(u8[0], u8[1], u8[2], u8[3]) !== 'RIFF') return null
  let off = 12
  let sampleRate = 16000, channels = 1, bits = 16, dataStart = -1, dataTotal = -1
  while (off + 8 <= u8.length) {
    const id = String.fromCharCode(u8[off], u8[off+1], u8[off+2], u8[off+3])
    const size = view.getUint32(off + 4, true)
    if (id === 'fmt ') {
      if (off + 8 + 16 > u8.length) return null
      channels = view.getUint16(off + 10, true)
      sampleRate = view.getUint32(off + 12, true)
      bits = view.getUint16(off + 22, true)
    } else if (id === 'data') {
      dataStart = off + 8; dataTotal = size; break
    }
    off += 8 + size
  }
  return dataStart < 0 ? null : { sampleRate, channels, bits, dataStart, dataTotal }
}

function buildWav(sampleRate, channels, bits, pcmBytes) {
  const frame = bits / 8 * channels
  const dataLen = pcmBytes.length
  const buf = new Uint8Array(44 + dataLen)
  const v = new DataView(buf.buffer)
  buf.set([0x52,0x49,0x46,0x46], 0) // RIFF
  v.setUint32(4, 36 + dataLen, true)
  buf.set([0x57,0x41,0x56,0x45], 8) // WAVE
  buf.set([0x66,0x6d,0x74,0x20], 12) // fmt 
  v.setUint32(16, 16, true)
  v.setUint16(20, 1, true)
  v.setUint16(22, channels, true)
  v.setUint32(24, sampleRate, true)
  v.setUint32(28, sampleRate * frame, true)
  v.setUint16(32, frame, true)
  v.setUint16(34, bits, true)
  buf.set([0x64,0x61,0x74,0x61], 36) // data
  v.setUint32(40, dataLen, true)
  buf.set(pcmBytes, 44)
  return buf
}

// 模拟分片消费（speech.js 等价逻辑）：返回消费的 data 字节数组列表
function consumeChunks(chunks) {
  let pBuf = new Uint8Array(0), header = null, consumed = [], consumedBytes = 0
  const out = []
  for (const c of chunks) {
    const merged = new Uint8Array(pBuf.length + c.length)
    merged.set(pBuf, 0); merged.set(c, pBuf.length)
    if (!header) {
      header = parseWavHeader(merged)
      if (!header) { pBuf = merged; continue }
      pBuf = merged.slice(header.dataStart)
    } else pBuf = merged
    const frame = (header.bits / 8) * header.channels
    const usable = Math.floor(pBuf.length / frame) * frame
    if (usable > 0) {
      out.push(pBuf.slice(0, usable))
      consumedBytes += usable
      pBuf = pBuf.slice(usable)
    }
    if (header.dataTotal > 0 && consumedBytes >= header.dataTotal) break
  }
  // 尾部残留
  if (header && pBuf.length > 0) {
    const frame = (header.bits / 8) * header.channels
    const usable = Math.floor(pBuf.length / frame) * frame
    if (usable > 0) out.push(pBuf.slice(0, usable))
  }
  return { header, out, consumedBytes }
}

function concat(u8s) {
  const total = u8s.reduce((a, b) => a + b.length, 0)
  const r = new Uint8Array(total); let o = 0
  for (const u of u8s) { r.set(u, o); o += u.length }
  return r
}

let pass = 0
function t(name, fn) {
  try { fn(); pass++; console.log('  ✓', name) }
  catch (e) { console.log('  ✗', name, e.message) }
}

const pcm = new Uint8Array(6000)
for (let i = 0; i < pcm.length; i++) pcm[i] = i % 256
const wav = buildWav(16000, 1, 16, pcm)

console.log('WAV 分片解析测试：')
t('完整单分片：data 区全部消费', () => {
  const { out, consumedBytes } = consumeChunks([wav])
  assert.equal(consumedBytes, 6000)
  const all = concat(out)
  assert.deepEqual(all, pcm)
})

t('头拆分：44 字节头逐字节分片', () => {
  const chunks = []
  for (let i = 0; i < 44; i++) chunks.push(wav.slice(i, i + 1))
  chunks.push(wav.slice(44))
  const { out, consumedBytes } = consumeChunks(chunks)
  assert.equal(consumedBytes, 6000)
  assert.deepEqual(concat(out), pcm)
})

t('单字节分片（含 PCM 区逐字节）', () => {
  const chunks = []
  for (let i = 0; i < wav.length; i += 1) chunks.push(wav.slice(i, i + 1))
  const { out, consumedBytes } = consumeChunks(chunks)
  assert.equal(consumedBytes, 6000)
  assert.deepEqual(concat(out), pcm)
})

t('奇数字节边界：3 字节分片（帧跨分片）', () => {
  const chunks = []
  for (let i = 0; i < wav.length; i += 3) chunks.push(wav.slice(i, i + 3))
  const { out, consumedBytes } = consumeChunks(chunks)
  assert.equal(consumedBytes, 6000) // 帧对齐无丢失
  assert.deepEqual(concat(out), pcm)
})

t('多声道 2ch/8bit：帧跨分片', () => {
  const pcm2 = new Uint8Array(4000)
  for (let i = 0; i < pcm2.length; i++) pcm2[i] = i % 256
  const wav2 = buildWav(8000, 2, 8, pcm2)
  const chunks = []
  for (let i = 0; i < wav2.length; i += 7) chunks.push(wav2.slice(i, i + 7))
  const { out, consumedBytes } = consumeChunks(chunks)
  assert.equal(consumedBytes, 4000)
  assert.deepEqual(concat(out), pcm2)
})

t('截断 WAV：consumedBytes 按 data 总长截断', () => {
  const truncated = wav.slice(0, 44 + 3000) // data 只到一半
  const { consumedBytes } = consumeChunks([truncated])
  assert.equal(consumedBytes, Math.floor(3000 / 2) * 2) // 16bit 帧对齐
})

t('分片解码样本与完整解码一致（首 100 样本）', () => {
  const whole = consumeChunks([wav]).out
  const pieced = consumeChunks([
    wav.slice(0, 100), wav.slice(100, 1000), wav.slice(1000, 2000), wav.slice(2000)
  ]).out
  const a = concat(whole), b = concat(pieced)
  assert.equal(a.length, b.length)
  for (let i = 0; i < Math.min(a.length, 100); i++) assert.equal(a[i], b[i])
})

console.log(`结果: ${pass}/7 通过`)
process.exit(pass === 7 ? 0 : 1)
