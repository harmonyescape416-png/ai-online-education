package com.kzai.aigc.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SSE 事件编码（系统设计说明书 4.2 / 3.1.4）。
 * <p>
 * 协议：
 *  - 1001 文本增量，data.content = "..."
 *  - 1003 结构化参数（M7 课程/订单卡片用；M6 保留协议但不发业务卡片）
 *  - 1002 结束事件，data.terminal=true，data.code/message 表示终态
 * <p>
 * 写响应时使用「data: ...\n\n」格式。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SseEvent {

    public static final int TYPE_DELTA = 1001;
    public static final int TYPE_PARAM = 1003;
    public static final int TYPE_END = 1002;

    public static final String TERM_OK = "OK";
    public static final String TERM_STOPPED = "STOPPED";
    public static final String TERM_ERROR = "ERROR";
    public static final String TERM_UPSTREAM = "UPSTREAM_ERROR";

    private final ObjectMapper objectMapper;

    /** 写一段 SSE 帧：event: 1001\\ndata: {...}\\n\\n */
    public void write(jakarta.servlet.http.HttpServletResponse response, int type, Object data) throws IOException {
        response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
        response.setCharacterEncoding("UTF-8");
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("code", type);
        envelope.put("data", data);
        String json = objectMapper.writeValueAsString(envelope);
        // 防止 JSON 内部换行破坏 SSE 帧
        json = json.replace("\n", "\\n");
        String frame = "event: " + type + "\ndata: " + json + "\n\n";
        response.getWriter().write(frame);
        response.getWriter().flush();
    }
}
