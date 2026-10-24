package com.kzai.aigc.controller;

import com.kzai.aigc.chat.ChatService;
import com.kzai.aigc.config.AiProperties;
import com.kzai.aigc.memory.ChatMemoryKeys;
import com.kzai.aigc.memory.RedisChatMemory;
import com.kzai.aigc.provider.ChatProvider;
import com.kzai.aigc.session.ChatSessionService;
import com.kzai.aigc.session.entity.ChatSession;
import com.kzai.common.constant.Constants;
import com.kzai.common.context.UserContext;
import com.kzai.common.exception.AuthException;
import com.kzai.common.result.R;
import com.kzai.common.result.ResultCode;
import com.kzai.common.security.RequireRoles;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 会话管理接口（M6）：新建 / 列表 / 详情 / 重命名 / 删除。
 * <p>
 * GET /session/hot、GET /chat/templates 使用 Nacos/配置文件预置数据，不冒充模型生成。
 */
@RestController
@RequestMapping("/session")
@RequireRoles({Constants.ROLE_STUDENT, Constants.ROLE_TEACHER, Constants.ROLE_STAFF, Constants.ROLE_ADMIN})
@RequiredArgsConstructor
public class SessionController {

    private final ChatSessionService sessionService;
    private final RedisChatMemory memory;
    private final ChatProvider provider;
    private final AiProperties props;
    private final ChatService chatService; // 仅用于占位（hot/templates 仍由 provider 提供标识）

    /** 新建会话：真实写入 MySQL */
    @PostMapping
    public R<Map<String, Object>> create() {
        long userId = requireUserId();
        ChatSession s = sessionService.create(userId);
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("sessionId", s.getSessionId());
        data.put("id", String.valueOf(s.getId()));
        data.put("createTime", s.getCreateTime() == null ? null : s.getCreateTime().toString());
        return R.ok(data).requestId(UserContext.getRequestId());
    }

    /** 历史会话列表：按更新时间倒序，最多 historyLimit（默认 30） */
    @GetMapping("/history")
    public R<List<Map<String, Object>>> history() {
        long userId = requireUserId();
        List<ChatSession> list = sessionService.history(userId);
        List<Map<String, Object>> data = list.stream().map(s -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("sessionId", s.getSessionId());
            m.put("title", s.getTitle());
            m.put("updateTime", s.getUpdateTime() == null ? null : s.getUpdateTime().toString());
            m.put("createTime", s.getCreateTime() == null ? null : s.getCreateTime().toString());
            return m;
        }).toList();
        return R.ok(data).requestId(UserContext.getRequestId());
    }

    /**
     * 单个会话的详情（包含从 Redis 加载的最近消息）。
     * M7：助手消息携带 cards（工具卡片 1003 载荷），前端历史回放据此恢复卡片；
     * 卡片数据来自真实业务服务结果，回放不会重新调用任何工具。
     */
    @GetMapping("/{sessionId}")
    public R<Map<String, Object>> detail(@PathVariable("sessionId") String sessionId) {
        long userId = requireUserId();
        ChatSession s = sessionService.requireOwned(userId, sessionId);
        List<com.kzai.aigc.memory.ChatMessageCodec.Dto> entries =
                memory.entries(ChatMemoryKeys.conversationId(userId, sessionId), 100);
        List<Map<String, Object>> ms = entries.stream().map(dto -> {
            Map<String, Object> mm = new LinkedHashMap<>();
            String type = dto.type == null ? "USER" : dto.type;
            mm.put("role", type.toLowerCase());
            mm.put("content", dto.content == null ? "" : dto.content);
            if (dto.cards != null && !dto.cards.isEmpty()) {
                mm.put("cards", dto.cards);
            }
            return mm;
        }).toList();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sessionId", s.getSessionId());
        data.put("title", s.getTitle());
        data.put("createTime", s.getCreateTime() == null ? null : s.getCreateTime().toString());
        data.put("updateTime", s.getUpdateTime() == null ? null : s.getUpdateTime().toString());
        data.put("messages", ms);
        return R.ok(data).requestId(UserContext.getRequestId());
    }

    /** 重命名：用户主动设置 */
    @PutMapping("/history")
    public R<Map<String, Object>> rename(@RequestBody Map<String, Object> body) {
        long userId = requireUserId();
        String sessionId = strOf(body, "sessionId");
        String title = strOf(body, "title");
        if (sessionId == null || title == null) {
            throw new com.kzai.common.exception.BizException("sessionId 与 title 均必填");
        }
        sessionService.rename(userId, sessionId, title);
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("sessionId", sessionId);
        data.put("title", title);
        return R.ok(data).requestId(UserContext.getRequestId());
    }

    /** 删除：MySQL + Redis 同步清理 */
    @DeleteMapping("/history")
    public R<Map<String, Object>> delete(@RequestBody Map<String, Object> body) {
        long userId = requireUserId();
        String sessionId = strOf(body, "sessionId");
        if (sessionId == null) {
            throw new com.kzai.common.exception.BizException("sessionId 必填");
        }
        sessionService.delete(userId, sessionId);
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("sessionId", sessionId);
        data.put("deleted", true);
        return R.ok(data).requestId(UserContext.getRequestId());
    }

    /** 热门问题：从配置读取的预置列表，不冒充模型生成 */
    @GetMapping("/hot")
    public R<List<String>> hot() {
        return R.ok(List.of(
                "平台有哪些免费入门课程？",
                "如何制定学习计划？",
                "AI 工作台可以帮我做什么？",
                "课程视频支持断点续学吗？",
                "如何查看我的学习进度？"))
                .requestId(UserContext.getRequestId());
    }

    private static String strOf(Map<String, Object> body, String key) {
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
