/**
 * POST + SSE 流式读取（M6）。
 * <p>
 * 原生 EventSource 只支持 GET，而本系统会话接口是 POST /chat，
 * 因此使用 fetch + ReadableStream + TextDecoder 手动解析 SSE 帧。
 * <p>
 * SSE 帧格式：
 * <pre>
 *   event: 1001
 *   data: {"code":1001,"data":{"content":"..."}}
 *
 *   event: 1002
 *   data: {"code":1002,"data":{...}}
 * </pre>
 * 网络可能把一帧切在任意字节位置（包括 UTF-8 多字节字符中间），
 * 所以必须用 TextDecoder({ stream:true }) 增量解码，并按 "\n\n" 切帧。
 */
export const SSE_DELTA = 1001
export const SSE_PARAM = 1003
export const SSE_END = 1002

/**
 * @param {string} url
 * @param {object} body 请求体（JSON）
 * @param {(evt:{event:number,data:any}) => void} onEvent
 * @param {{signal?: AbortSignal}} [opts]
 */
export async function streamPost(url, body, onEvent, opts = {}) {
    const resp = await fetch(url, {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
            Authorization: `Bearer ${localStorage.getItem('kzai_token') || ''}`
        },
        body: JSON.stringify(body),
        signal: opts.signal
    })
    if (!resp.ok) {
        let msg = `请求失败（HTTP ${resp.status}）`
        try {
            const b = await resp.json()
            if (b && b.msg) msg = b.msg
        } catch {
            // 忽略解析失败，保留默认提示
        }
        throw new Error(msg)
    }
    if (!resp.body) {
        throw new Error('当前浏览器不支持流式响应')
    }

    const reader = resp.body.getReader()
    const decoder = new TextDecoder('utf-8') // stream:true → 增量解码，避免中文跨块截断
    let buffer = ''

    while (true) {
        const { done, value } = await reader.read()
        if (done) break
        buffer += decoder.decode(value, { stream: true })
        let idx
        while ((idx = buffer.indexOf('\n\n')) !== -1) {
            const frame = buffer.slice(0, idx)
            buffer = buffer.slice(idx + 2)
            const parsed = parseFrame(frame)
            if (parsed) onEvent(parsed)
        }
    }
    // 收尾：处理可能没有尾随空行的最后一帧
    buffer += decoder.decode()
    const tail = parseFrame(buffer)
    if (tail) onEvent(tail)
}

function parseFrame(frame) {
    if (!frame || !frame.trim()) return null
    let event = null
    const dataLines = []
    for (const rawLine of frame.split('\n')) {
        const line = rawLine.replace(/\r$/, '')
        if (line.startsWith('event:')) {
            event = Number(line.slice(6).trim())
        } else if (line.startsWith('data:')) {
            dataLines.push(line.slice(5).trim())
        }
    }
    if (!dataLines.length) return null
    const payload = dataLines.join('\n')
    let data
    try {
        data = JSON.parse(payload)
    } catch {
        return { event: event ?? 0, data: payload }
    }
    return { event: event ?? data?.code ?? 0, data: data?.data ?? data }
}
