package com.kzai.aigc.supervision;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kzai.aigc.memory.ChatMemoryKeys;
import com.kzai.aigc.memory.ChatMessageCodec;
import com.kzai.aigc.memory.RedisChatMemory;
import com.kzai.aigc.session.entity.ChatSession;
import com.kzai.aigc.session.mapper.ChatSessionMapper;
import com.kzai.aigc.supervision.entity.SysSessionReview;
import com.kzai.aigc.supervision.mapper.SysSessionReviewMapper;
import com.kzai.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * M13 AI 会话监管（管理员专用，服务归属 kzai-aigc）：
 * <ul>
 *   <li>分页查询会话：用户/时间区间/关键词筛选（关键词与意图在 Redis 消息级别过滤，
 *       限定扫描窗口防全库扫描）；</li>
 *   <li>会话详情：元数据 + Redis 消息（TTL 过期 → 明确显示「消息内容已过期或不可用」，
 *       绝不伪造消息）+ 工具卡片 + 复核历史；</li>
 *   <li>复核：标记待复核（原因必填）/ 处理（status + 备注，仅 ADMIN），
 *       只插入不覆盖，历史完整；</li>
 *   <li>红线：监管不修改用户原始会话消息（只读 Redis，绝不写/删消息）。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SessionSupervisionService {

    /** 关键词/意图筛选的最大扫描会话窗口（防全库 Redis 扫描；超窗提示缩小条件） */
    private static final int MAX_SCAN = 300;

    private final ChatSessionMapper sessionMapper;
    private final SysSessionReviewMapper reviewMapper;
    private final RedisChatMemory memory;
    private final StringRedisTemplate redis;
    private final ChatMessageCodec codec;
    private final ObjectMapper om;
    private final OperationAuditBridge audit;

    /** 会话分页（userId/时间区间为 SQL 过滤；keyword/agent 为消息级过滤） */
    public Map<String, Object> pageSessions(Long userId, LocalDateTime startTime, LocalDateTime endTime,
                                            String keyword, String agent, long page, long size) {
        Page<ChatSession> p = sessionMapper.selectPage(new Page<>(page, Math.min(size, 50)),
                Wrappers.<ChatSession>lambdaQuery()
                        .eq(userId != null, ChatSession::getUserId, userId)
                        .ge(startTime != null, ChatSession::getCreateTime, startTime)
                        .le(endTime != null, ChatSession::getCreateTime, endTime)
                        .orderByDesc(ChatSession::getUpdateTime));
        boolean needMessageScan = (keyword != null && !keyword.isBlank())
                || (agent != null && !agent.isBlank());
        List<Map<String, Object>> rows = new ArrayList<>();
        long total = p.getTotal();
        if (!needMessageScan) {
            for (ChatSession s : p.getRecords()) {
                rows.add(toRow(s, null, null));
            }
        } else {
            // 消息级筛选：按更新时间倒序扫描至多 MAX_SCAN 条会话
            List<ChatSession> scan = sessionMapper.selectList(Wrappers.<ChatSession>lambdaQuery()
                    .eq(userId != null, ChatSession::getUserId, userId)
                    .ge(startTime != null, ChatSession::getCreateTime, startTime)
                    .le(endTime != null, ChatSession::getCreateTime, endTime)
                    .orderByDesc(ChatSession::getUpdateTime)
                    .last("LIMIT " + MAX_SCAN));
            String kw = keyword == null ? null : keyword.toLowerCase();
            for (ChatSession s : scan) {
                MsgSummary sum = summarize(s);
                if (kw != null && (sum.allText == null || !sum.allText.contains(kw))) {
                    continue;
                }
                if (agent != null && !agent.isBlank() && !agent.equals(sum.lastAgent)) {
                    continue;
                }
                rows.add(toRow(s, sum.lastAgent, sum.messageCount));
            }
            total = rows.size(); // 消息级过滤的命中数（窗口内）
            rows = rows.stream().skip((page - 1) * Math.min(size, 50))
                    .limit(Math.min(size, 50)).toList();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", total);
        out.put("scanLimit", needMessageScan ? MAX_SCAN : null);
        out.put("records", rows);
        return out;
    }

    /** 会话详情：元数据 + 消息（过期如实标注）+ 复核历史 */
    public Map<String, Object> detail(String sessionId) {
        ChatSession s = sessionMapper.selectOne(new LambdaQueryWrapper<ChatSession>()
                .eq(ChatSession::getSessionId, sessionId));
        if (s == null) {
            throw new BizException("会话不存在");
        }
        Map<String, Object> out = toRow(s, null, null);
        out.put("messages", messagesOf(s));
        List<SysSessionReview> reviews = reviewMapper.selectList(
                Wrappers.<SysSessionReview>lambdaQuery()
                        .eq(SysSessionReview::getSessionId, sessionId)
                        .orderByDesc(SysSessionReview::getMarkedAt));
        out.put("reviews", reviews);
        return out;
    }

    /** 标记待复核（原因必填；只插入新行，历史保留） */
    public long mark(String sessionId, String reason, long adminId, String adminName) {
        ChatSession s = sessionMapper.selectOne(new LambdaQueryWrapper<ChatSession>()
                .eq(ChatSession::getSessionId, sessionId));
        if (s == null) {
            throw new BizException("会话不存在");
        }
        SysSessionReview r = new SysSessionReview();
        r.setSessionId(sessionId);
        r.setUserId(s.getUserId());
        r.setStatus(SysSessionReview.ST_PENDING);
        r.setReason(reason);
        r.setMarkedBy(adminId);
        r.setMarkedByName(adminName);
        r.setMarkedAt(LocalDateTime.now());
        reviewMapper.insert(r);
        audit.write(adminId, adminName, "SUPERVISION", "SESSION_MARK",
                "CHAT_SESSION", s.getId(),
                "{\"reason\":\"" + esc(reason) + "\",\"sessionId\":\"" + sessionId + "\"}");
        return r.getId();
    }

    /** 处理复核（仅待复核行可处理；条件更新防并发重复处理） */
    public void handle(long reviewId, int status, String handleNote, long adminId, String adminName) {
        if (status != SysSessionReview.ST_HANDLED_OK && status != SysSessionReview.ST_HANDLED_VIOLATION
                && status != SysSessionReview.ST_HANDLED_OTHER) {
            throw new BizException("非法处理结果");
        }
        SysSessionReview upd = new SysSessionReview();
        upd.setStatus(status);
        upd.setHandleNote(handleNote);
        upd.setHandledBy(adminId);
        upd.setHandledByName(adminName);
        upd.setHandledAt(LocalDateTime.now());
        upd.setUpdateTime(LocalDateTime.now());
        int n = reviewMapper.update(upd, Wrappers.<SysSessionReview>lambdaUpdate()
                .eq(SysSessionReview::getId, reviewId)
                .eq(SysSessionReview::getStatus, SysSessionReview.ST_PENDING));
        if (n != 1) {
            throw new BizException("该复核已被处理或不存在");
        }
        audit.write(adminId, adminName, "SUPERVISION", "SESSION_HANDLE",
                "SESSION_REVIEW", reviewId,
                "{\"status\":" + status + ",\"note\":\"" + esc(handleNote == null ? "" : handleNote) + "\"}");
    }

    /** 复核处理历史 */
    public List<SysSessionReview> history(String sessionId) {
        return reviewMapper.selectList(Wrappers.<SysSessionReview>lambdaQuery()
                .eq(SysSessionReview::getSessionId, sessionId)
                .orderByDesc(SysSessionReview::getMarkedAt));
    }

    // ------------------------------------------------------------------

    private record MsgSummary(String lastAgent, int messageCount, String allText) { }

    /** 读取消息摘要（TTL 过期返回空摘要；绝不伪造） */
    private MsgSummary summarize(ChatSession s) {
        String key = ChatMemoryKeys.CHAT_PREFIX + ChatMemoryKeys.conversationId(s.getUserId(), s.getSessionId());
        try {
            Long len = redis.opsForList().size(key);
            if (len == null || len == 0) {
                return new MsgSummary(null, 0, "");
            }
            List<ChatMessageCodec.Dto> entries = memoryEntries(s);
            StringBuilder text = new StringBuilder();
            for (ChatMessageCodec.Dto d : entries) {
                if (d.content != null) {
                    text.append(d.content.toLowerCase());
                }
            }
            String lastAgent = null;
            for (ChatMessageCodec.Dto d : entries) {
                if ("ASSISTANT".equals(d.type) && d.agent != null) {
                    lastAgent = d.agent;
                }
            }
            return new MsgSummary(lastAgent, entries.size(), text.toString());
        } catch (RuntimeException ex) {
            return new MsgSummary(null, 0, "");
        }
    }

    /** 原始消息条目（含 cards/agent 元数据）；过期返回 null 由调用方标注 */
    @SuppressWarnings("unchecked")
    private List<ChatMessageCodec.Dto> memoryEntries(ChatSession s) {
        String key = ChatMemoryKeys.CHAT_PREFIX + ChatMemoryKeys.conversationId(s.getUserId(), s.getSessionId());
        List<String> raw = redis.opsForList().range(key, 0, -1);
        List<ChatMessageCodec.Dto> out = new ArrayList<>();
        if (raw == null) {
            return out;
        }
        for (String line : raw) {
            try {
                ChatMessageCodec.Dto d = codec.parseEntry(line);
                if (d != null) {
                    if (d.agent == null) {
                        d.agent = agentOfCards(d);
                    }
                    out.add(d);
                }
            } catch (Exception ignore) {
                // 单条损坏不阻断监管读取
            }
        }
        return out;
    }

    private String agentOfCards(ChatMessageCodec.Dto d) {
        return null; // agent 由消息写入时的元数据承载（M8 终态），卡片不含 agent
    }

    private List<Map<String, Object>> messagesOf(ChatSession s) {
        List<Map<String, Object>> out = new ArrayList<>();
        String key = ChatMemoryKeys.CHAT_PREFIX + ChatMemoryKeys.conversationId(s.getUserId(), s.getSessionId());
        Long len = null;
        try {
            len = redis.opsForList().size(key);
        } catch (RuntimeException ignore) {
            // Redis 不可用按过期处理
        }
        if (len == null || len == 0) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("expired", true);
            m.put("content", "消息内容已过期或不可用");
            out.add(m);
            return out;
        }
        for (ChatMessageCodec.Dto d : memoryEntries(s)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", d.type);
            m.put("content", d.content == null ? "" : d.content);
            m.put("cards", d.cards);
            out.add(m);
        }
        return out;
    }

    private Map<String, Object> toRow(ChatSession s, String lastAgent, Integer messageCount) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", String.valueOf(s.getId()));
        m.put("sessionId", s.getSessionId());
        m.put("userId", String.valueOf(s.getUserId()));
        m.put("title", s.getTitle());
        m.put("createTime", s.getCreateTime());
        m.put("updateTime", s.getUpdateTime());
        m.put("lastAgent", lastAgent);
        m.put("messageCount", messageCount);
        // 待复核状态（最新一条复核记录）
        SysSessionReview latest = reviewMapper.selectOne(Wrappers.<SysSessionReview>lambdaQuery()
                .eq(SysSessionReview::getSessionId, s.getSessionId())
                .orderByDesc(SysSessionReview::getMarkedAt)
                .last("LIMIT 1"));
        m.put("reviewStatus", latest == null ? null : latest.getStatus());
        return m;
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
    }
}
