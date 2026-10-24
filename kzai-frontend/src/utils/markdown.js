import MarkdownIt from 'markdown-it'
import DOMPurify from 'dompurify'

/**
 * Markdown 渲染（M6 AI 工作台）。
 * <p>
 * 安全要求：模型输出属于不可信内容，必须先渲染再经 DOMPurify 净化，
 * 禁止直接 v-html 渲染原始 HTML。配置上：
 *  - html: false    —— 不解析原始 HTML 标签
 *  - linkify: true  —— 纯文本 URL 自动成链（由 DOMPurify 校验协议）
 *  - 净化后仅允许 http/https/mailto 协议，阻断 javascript: 等伪协议
 */
const md = new MarkdownIt({
    html: false,
    linkify: true,
    breaks: true,
    typographer: false
})

// 代码高亮不做外部依赖，仅保证结构可读
const ALLOWED_TAGS = [
    'p', 'br', 'hr', 'strong', 'em', 'del', 'code', 'pre', 'blockquote',
    'ul', 'ol', 'li', 'h1', 'h2', 'h3', 'h4', 'h5', 'h6', 'table',
    'thead', 'tbody', 'tr', 'th', 'td', 'a', 'span', 'div', 'sup', 'sub'
]
const ALLOWED_ATTR = ['href', 'title', 'target', 'rel', 'class', 'colspan', 'rowspan', 'align']

export function renderMarkdown(source) {
    if (!source) return ''
    const rawHtml = md.render(String(source))
    return DOMPurify.sanitize(rawHtml, {
        ALLOWED_TAGS,
        ALLOWED_ATTR,
        ALLOWED_URI_REGEXP: /^(?:(?:https?|mailto):|[^a-z]|[a-z+.-]+(?:[^a-z+.-:]|$))/i
    })
}
