/**
 * M15.14-R2：全站统一格式化工具（G4/P1/P2：时间、角色、订单号）。
 * - 时间统一 YYYY-MM-DD HH:mm:ss（处理 ISO T 格式与时间戳，不简单截断字符串）；
 * - 角色/状态枚举映射中文；
 * - 订单号中段省略 + 复制（19 位雪花 ID 字符串处理）。
 */

/** 任意时间输入 → Date；非法返回 null */
function toDate(v) {
  if (v === null || v === undefined || v === '') return null
  if (v instanceof Date) return isNaN(v.getTime()) ? null : v
  if (typeof v === 'number') {
    const d = new Date(v)
    return isNaN(d.getTime()) ? null : d
  }
  let s = String(v).trim()
  // ISO「2026-10-19T09:53:27」→ Safari 兼容的空格形式（按本地时区解析，不改服务端存储语义）
  s = s.replace('T', ' ').replace(/(\.\d+)?Z$/, '')
  const d = new Date(s.replace(/-/g, '/'))
  return isNaN(d.getTime()) ? null : d
}

/** 统一时间：YYYY-MM-DD HH:mm:ss；空/非法返回 fallback（默认 '—'） */
export function fmtTime(v, fallback = '—') {
  const d = toDate(v)
  if (!d) return fallback
  const p = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}

/** 角色枚举 → 中文 */
const ROLE_LABELS = {
  STUDENT: '学员',
  TEACHER: '教师',
  ADMIN: '管理员'
}
export function roleLabel(r) {
  if (!r) return '—'
  return ROLE_LABELS[String(r).toUpperCase()] || String(r)
}

/** 订单状态文案（1待支付 2已支付 3已关闭 4已完成 5已报名 6已申请退款） */
export const ORDER_STATUS_TEXT = { 1: '待支付', 2: '已支付', 3: '已关闭', 4: '已完成', 5: '已报名', 6: '已申请退款' }

/** 订单号中段省略：2112808824740552706 → 211280…2706（≤14 位原样显示） */
export function maskOrderNo(no) {
  const s = String(no || '')
  if (s.length <= 14) return s
  return s.slice(0, 6) + '…' + s.slice(-4)
}

/** 复制文本到剪贴板（成功返回 true；含旧浏览器降级） */
export async function copyText(text) {
  try {
    await navigator.clipboard.writeText(String(text))
    return true
  } catch (e) {
    try {
      const ta = document.createElement('textarea')
      ta.value = String(text)
      ta.style.position = 'fixed'
      ta.style.opacity = '0'
      document.body.appendChild(ta)
      ta.select()
      const ok = document.execCommand('copy')
      document.body.removeChild(ta)
      return ok
    } catch (e2) {
      return false
    }
  }
}

/** 服务端相对路径 → 带 /api 前缀的访问地址（dev 走 vite 代理；prod 同源） */
export function withApiBase(path) {
  if (!path) return ''
  return path.startsWith('/api') ? path : '/api' + path
}

/** 时长秒 → 「x 分 x 秒」 */
export function fmtDuration(seconds) {
  const s = Number(seconds)
  if (!isFinite(s) || s < 0) return '—'
  const m = Math.floor(s / 60)
  const r = Math.round(s % 60)
  if (m <= 0) return `${r} 秒`
  return `${m} 分 ${r} 秒`
}
