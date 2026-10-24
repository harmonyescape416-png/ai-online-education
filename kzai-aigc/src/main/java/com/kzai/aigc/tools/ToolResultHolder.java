package com.kzai.aigc.tools;

import org.springframework.ai.chat.metadata.Usage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 工具结果暂存（M7，原始设计 4.3：ToolResultHolder）。
 * <p>
 * <b>实例级隔离</b>：每个生成轮次由 ChatService new 一个实例，随请求生命周期存活，
 * 通过 Spring AI ToolContext 传递给工具；<b>禁止使用全局静态变量</b>——
 * 不同用户/不同会话/不同消息各自持有独立实例，天然隔离，不会互相覆盖或串流。
 * <p>
 * 职责：
 * <ul>
 *   <li>同一轮多个工具结果按产生顺序有序保存；</li>
 *   <li>按 cardId 去重——同一工具结果不会被重复推送两次 1003；</li>
 *   <li>失败工具只记录审计、不写入任何卡片（失败不能冒充成功结果）；</li>
 *   <li>采集真实模型 token 用量（Usage），仅统计、不含任何用户内容。</li>
 * </ul>
 * 卡片结构（1003 载荷）：{"type":"COURSE_CARD","cardId":"...","payload":{...}}
 */
public class ToolResultHolder {

    private final long userId;
    private final String sessionId;
    private final String requestId;

    private final List<Map<String, Object>> cards = new CopyOnWriteArrayList<>();
    private final Set<String> cardIds = ConcurrentHashMap.newKeySet();
    private volatile String recommendationUserText;
    private volatile Map<String,Object> recommendationResult;
    private volatile boolean recommendationFallbackUsed;

    /** 仅本请求内使用，不写审计或跨会话共享。身份仍来自独立可信上下文。 */
    public void beginRecommendationTurn(String text){recommendationUserText=text;}
    public String recommendationUserText(){return recommendationUserText;}
    public Map<String,Object> recommendationResult(){return recommendationResult;}
    public void recommendationFallbackUsed(){recommendationFallbackUsed=true;}
    public boolean usedRecommendationFallback(){return recommendationFallbackUsed;}
    public synchronized void recommendationResult(Map<String,Object> result){
        // 同轮重试仅保留最后一次实际结果，避免空结果却展示前一次卡片。
        cards.removeIf(c -> "COURSE_CARD".equals(c.get("type")));
        cardIds.removeIf(id -> id.startsWith("course:"));
        recommendationResult=result;
    }

    private volatile Integer promptTokens;
    private volatile Integer completionTokens;
    private volatile Integer totalTokens;

    /** 本轮各工具已执行次数（M8：轮次预算，防模型无限工具循环） */
    private final Map<String, Integer> toolCallCounts = new java.util.concurrent.ConcurrentHashMap<>();
    /** M6 无最大迭代保护（模型内 handleToolCalls 递归无上限）——由工具侧预算兜底 */
    public static final int MAX_TOOL_CALLS_PER_TOOL = 8;

    /**
     * 登记一次工具调用；超过单工具预算返回 false，工具据此返回受控提示，
     * 引导模型基于已有结果作答（阻止无意义的重复调用循环）。
     */
    public boolean tryAcquireToolCall(String toolName) {
        if (toolName == null) {
            return false;
        }
        Integer count = toolCallCounts.merge(toolName, 1, Integer::sum);
        return count != null && count <= MAX_TOOL_CALLS_PER_TOOL;
    }

    public int toolCallCount(String toolName) {
        return toolCallCounts.getOrDefault(toolName, 0);
    }

    public ToolResultHolder(long userId, String sessionId, String requestId) {
        this.userId = userId;
        this.sessionId = sessionId;
        this.requestId = requestId;
    }

    public long getUserId() {
        return userId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getRequestId() {
        return requestId;
    }

    /**
     * 追加一张卡片；cardId 相同的结果只输出一次（每实体每轮至多一张 1003）。
     * 若后续工具产生了同实体的更完整载荷（如 getCourseDetail 补充章节数/简介），
     * 按 cardId <b>就地覆盖</b>，保持首次出现的位置不变。
     * 失败的工具调用不应调用本方法。
     */
    public boolean addCard(String type, String cardId, Map<String, Object> payload) {
        if (type == null || cardId == null || payload == null) {
            return false;
        }
        // 就地覆盖：保持位置，更新载荷（去重语义 = 同实体仅一张卡片）
        for (int i = 0; i < cards.size(); i++) {
            Map<String, Object> existing = cards.get(i);
            if (cardId.equals(existing.get("cardId"))) {
                Map<String, Object> merged = new LinkedHashMap<>();
                @SuppressWarnings("unchecked")
                Map<String, Object> oldPayload = (Map<String, Object>) existing.get("payload");
                if (oldPayload != null) {
                    merged.putAll(oldPayload);
                }
                merged.putAll(payload);
                Map<String, Object> card = new LinkedHashMap<>();
                card.put("type", existing.get("type"));
                card.put("cardId", cardId);
                card.put("payload", merged);
                cards.set(i, card);
                return true;
            }
        }
        if (!cardIds.add(cardId)) {
            return false;
        }
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("type", type);
        card.put("cardId", cardId);
        card.put("payload", payload);
        cards.add(card);
        return true;
    }

    /** 本轮已产生的全部卡片（只读视图，按产生顺序） */
    public List<Map<String, Object>> cards() {
        return Collections.unmodifiableList(new ArrayList<>(cards));
    }

    public boolean hasCards() {
        return !cards.isEmpty();
    }

    /** 采集真实模型 token 用量（空安全；只记录数字，不记录任何内容文本） */
    public void collectUsage(Usage usage) {
        if (usage == null) {
            return;
        }
        try {
            if (usage.getPromptTokens() != null) {
                this.promptTokens = usage.getPromptTokens();
            }
            if (usage.getCompletionTokens() != null) {
                this.completionTokens = usage.getCompletionTokens();
            }
            Integer total = usage.getTotalTokens();
            if (total == null && promptTokens != null && completionTokens != null) {
                total = promptTokens + completionTokens;
            }
            if (total != null) {
                this.totalTokens = total;
            }
        } catch (RuntimeException ignore) {
            // 用量统计失败不影响主流程
        }
    }

    public Integer getPromptTokens() {
        return promptTokens;
    }

    public Integer getCompletionTokens() {
        return completionTokens;
    }

    public Integer getTotalTokens() {
        return totalTokens;
    }
}
