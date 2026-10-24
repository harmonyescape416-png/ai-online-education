package com.kzai.aigc.controller;

import com.kzai.aigc.chat.ChatService;
import com.kzai.aigc.chat.StopRegistry;
import com.kzai.aigc.config.AiProperties;
import com.kzai.aigc.provider.ChatProvider;
import com.kzai.aigc.session.ChatSessionService;
import com.kzai.aigc.session.entity.ChatSession;
import com.kzai.common.constant.Constants;
import com.kzai.common.context.UserContext;
import com.kzai.common.exception.AuthException;
import com.kzai.common.result.R;
import com.kzai.common.result.ResultCode;
import com.kzai.common.security.RequireRoles;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * AI 对话接口（M6）：流式 /chat、非流式 /chat/text、停止生成 /chat/stop、模板/欢迎语 /chat/templates。
 */
@RestController
@RequestMapping("/chat")
@RequireRoles({Constants.ROLE_STUDENT, Constants.ROLE_TEACHER, Constants.ROLE_STAFF, Constants.ROLE_ADMIN})
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;
    private final StopRegistry stopRegistry;
    private final ChatSessionService sessionService;
    private final ChatProvider provider;
    private final AiProperties props;

    /**
     * SSE 流式对话。
     * <p>
     * 事件协议（系统设计说明书 4.2）：1001 文本增量、1003 结构化参数（M7 用，本阶段不发）、
     * 1002 生成结束（每轮仅一个）。
     * <p>
     * body: {"sessionId", "message", "messageId"(可选，幂等键)}
     */
    @PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public void chat(@RequestBody Map<String, Object> body, HttpServletResponse response) throws Exception {
        long userId = requireUserId();
        String sessionId = stringOf(body, "sessionId");
        String text = stringOf(body, "message");
        String messageId = stringOf(body, "messageId");
        if (sessionId == null || sessionId.isBlank() || text == null || text.isBlank()) {
            throw new com.kzai.common.exception.BizException("sessionId 与 message 均必填，且不能为空白内容");
        }
        // M15.15：超长内容后端拒绝（模型上下文与成本防护；前端 maxlength 同步约束）
        if (text.length() > 4000) {
            throw new com.kzai.common.exception.BizException("消息内容过长（上限 4000 字）");
        }
        text = text.strip();
        // 归属校验：避免越权（JWT 由网关注入，服务端再校验所有权，不信任任何客户端传入的 userId）
        sessionService.requireOwned(userId, sessionId);
        // 设置 SSE 头（必须在首次 flush 前完成；之后不能再改状态码）
        response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("X-Accel-Buffering", "no");
        chatService.streamChat(userId, sessionId, text, messageId, response);
    }

    /** 非流式文本对话（M10 课程问答自动回复基础） */
    @PostMapping("/text")
    public R<Map<String, Object>> textChat(@RequestBody Map<String, Object> body) {
        long userId = requireUserId();
        String sessionId = stringOf(body, "sessionId");
        String text = stringOf(body, "message");
        String messageId = stringOf(body, "messageId");
        if (sessionId == null || sessionId.isBlank() || text == null || text.isBlank()) {
            throw new com.kzai.common.exception.BizException("sessionId 与 message 均必填，且不能为空白内容");
        }
        if (text.length() > 4000) {
            throw new com.kzai.common.exception.BizException("消息内容过长（上限 4000 字）");
        }
        text = text.strip();
        String reply = chatService.callChat(userId, sessionId, text, messageId);
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("reply", reply);
        data.put("mock", provider.mock());
        data.put("provider", provider.code());
        return R.ok(data).requestId(UserContext.getRequestId());
    }

    /** 停止生成：写入 Redis 停止标识（5 分钟 TTL），生成循环每段检查 */
    @PostMapping("/stop")
    public R<Map<String, Object>> stop(@RequestBody Map<String, Object> body) {
        long userId = requireUserId();
        String sessionId = stringOf(body, "sessionId");
        if (sessionId == null) {
            throw new com.kzai.common.exception.BizException("sessionId 必填");
        }
        // 归属校验
        sessionService.requireOwned(userId, sessionId);
        stopRegistry.markStop(sessionId);
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("stopped", true);
        data.put("sessionId", sessionId);
        return R.ok(data).requestId(UserContext.getRequestId());
    }

    /** 模板/欢迎语：从配置读取（不冒充模型生成） */
    @PostMapping("/templates")
    public R<Map<String, Object>> templates() {
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("welcome", props.getWelcomeText());
        data.put("mock", provider.mock());
        data.put("provider", provider.code());
        return R.ok(data).requestId(UserContext.getRequestId());
    }

    private static String stringOf(Map<String, Object> body, String key) {
        if (body == null) return null;
        Object v = body.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private long requireUserId() {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            throw new AuthException(ResultCode.UNAUTHORIZED, "未登录或 token 无效");
        }
        return userId;
    }
}
