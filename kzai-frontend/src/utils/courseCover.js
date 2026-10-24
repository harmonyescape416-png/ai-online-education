/**
 * M15.13：课程封面统一映射（课程浏览/详情/我的学习/AI 课程卡片共用）。
 * - 课程真实已上传封面（course.cover_url 非占位外链）优先展示；
 * - placehold.co 外部占位或无封面时，按课程主题映射本地 SVG 封面（assets: public/covers/*.svg）；
 * - 主题由课程名称关键词推断（与课程实际内容方向对应，不编造额外信息）；
 * - 图片加载失败降级为 generic 主题封面。
 */

const THEME_RULES = [
  { theme: 'spring', kw: ['spring', '微服务', 'springboot', 'spring boot', 'cloud'] },
  { theme: 'vue', kw: ['vue', '前端', 'javascript', 'js', 'html', 'css'] },
  { theme: 'db', kw: ['mysql', 'redis', '数据库', 'sql', '缓存', 'mongo'] },
  { theme: 'ai', kw: ['ai', '大模型', '智能', 'llm', 'agent', '机器学习', '深度学习', '人工智能'] },
  { theme: 'rag', kw: ['rag', '检索', '知识库', 'embedding', '向量'] },
  { theme: 'ds', kw: ['算法', '数据结构', 'leetcode', '刷题'] },
  { theme: 'python', kw: ['python', '爬虫', '数据分析', 'pandas'] },
  { theme: 'java', kw: ['java', 'jdk', 'jvm', '多线程'] }
]

function isPlaceholder(url) {
  return !url || /placehold\.co|placeholder/i.test(String(url))
}

export function coverTheme(course) {
  const name = String((course && (course.name || course.courseName)) || '').toLowerCase()
  for (const rule of THEME_RULES) {
    if (rule.kw.some(k => name.includes(k))) return rule.theme
  }
  return 'generic'
}

/** 统一封面地址：真实封面优先，否则本地主题 SVG（16:9） */
/** 分类中性封面：按课程 ID 稳定派生色相 + 课程名首字（不虚构技术主题，消除整页同图） */
function genericVariant(course) {
  const id = String((course && (course.id || course.courseId)) || '0')
  let h = 0
  for (let i = 0; i < id.length; i++) h = (h * 31 + id.charCodeAt(i)) % 360
  const name = String((course && (course.name || course.courseName)) || '课').trim()
  const first = name.slice(0, 1).toUpperCase()
  const sub = name.length > 10 ? name.slice(0, 10) + '…' : name
  const dark = `hsl(${h}, 32%, 24%)`
  const main = `hsl(${h}, 42%, 38%)`
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="640" height="360" viewBox="0 0 640 360">` +
    `<defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1">` +
    `<stop offset="0" stop-color="${dark}"/><stop offset="1" stop-color="${main}"/></linearGradient></defs>` +
    `<rect width="640" height="360" fill="url(#g)"/>` +
    `<circle cx="560" cy="60" r="120" fill="#ffffff" opacity="0.06"/>` +
    `<circle cx="80" cy="320" r="90" fill="#ffffff" opacity="0.05"/>` +
    `<rect x="48" y="132" width="76" height="76" rx="16" fill="#ffffff" opacity="0.16"/>` +
    `<text x="86" y="186" font-size="44" text-anchor="middle" fill="#ffffff" font-weight="600" font-family="PingFang SC, Microsoft YaHei, sans-serif">${first}</text>` +
    `<text x="148" y="172" font-size="32" font-weight="600" fill="#ffffff" font-family="PingFang SC, Microsoft YaHei, sans-serif">${sub}</text>` +
    `<text x="150" y="212" font-size="16" fill="#ffffff" opacity="0.72" font-family="PingFang SC, Microsoft YaHei, sans-serif">课栈智伴 · 在线课程</text>` +
    `</svg>`
  return 'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(svg)
}

export function coverUrlOf(course) {
  const raw = course && (course.coverUrl || course.cover_url)
  if (!isPlaceholder(raw)) return raw
  const theme = coverTheme(course)
  // 主题不明确（generic）时改用分类中性渐变变体（按课程 ID 稳定派生，刷新不变），消除整页同图
  if (theme === 'generic') return genericVariant(course)
  return '/covers/' + theme + '.svg'
}

/** Vue 模板 @error 处理：加载失败降级为 generic 封面 */
export function onCoverError(e) {
  const el = e && e.target
  if (el && !String(el.src).endsWith('/covers/generic.svg')) {
    el.src = '/covers/generic.svg'
  }
}
