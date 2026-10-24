/**
 * 卡片外链安全校验（M7）。
 * 只允许 http(s) 绝对地址或站内相对路径（/ 开头）；
 * 禁止 javascript:/data:/vbscript: 等伪协议，防止不可信链接跳转与脚本注入。
 */
export function safeUrl(u) {
  if (!u || typeof u !== 'string') return ''
  const t = u.trim()
  if (/^https?:\/\//i.test(t)) return t
  if (/^\//.test(t) && !/^\/\//.test(t)) return t
  return ''
}

/** 卡片字段文本净化：仅保留普通字符串（Vue 插值本身已转义，此处再兜底） */
export function safeText(v, maxLen = 500) {
  if (v === null || v === undefined) return ''
  const s = String(v)
  return s.length > maxLen ? s.slice(0, maxLen) : s
}
