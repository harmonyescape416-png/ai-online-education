package com.kzai.aigc.tools;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 工具调用审计（M7）。
 * <p>
 * 每次工具调用记录：requestId、sessionId、toolName、执行耗时、成功或失败、
 * 结果数量、受控错误类型。使用独立 logger（TOOL_AUDIT）输出结构化日志，
 * 并维护进程级计数指标。
 * <p>
 * <b>红线</b>：不输出 API Key、完整 JWT、敏感个人信息、用户原文。
 * sys_operation_log 表归属 kzai-user 服务域，跨服务高频写不合适；
 * 本阶段审计落结构化日志 + 指标，不新增数据库表（见 docs/decisions.md D7-x）。
 */
@Slf4j
@Component
public class ToolAudit {

    /** 专用审计 logger，便于按名收集与采样 */
    private static final org.slf4j.Logger AUDIT = org.slf4j.LoggerFactory.getLogger("TOOL_AUDIT");

    private final AtomicLong totalCalls = new AtomicLong();
    private final AtomicLong successCalls = new AtomicLong();
    private final AtomicLong failedCalls = new AtomicLong();
    private final AtomicLong totalCostMs = new AtomicLong();
    /** 按工具名统计的失败次数（受控错误分类） */
    private final Map<String, AtomicLong> failuresByTool = new ConcurrentHashMap<>();

    /** 受控错误类型常量 */
    public static final String ERR_SERVICE_UNAVAILABLE = "SERVICE_UNAVAILABLE";
    public static final String ERR_NOT_FOUND = "NOT_FOUND";
    public static final String ERR_CONFIRM_REQUIRED = "CONFIRM_REQUIRED";
    public static final String ERR_STOP_REQUESTED = "STOP_REQUESTED";
    public static final String ERR_BAD_REQUEST = "BAD_REQUEST";
    public static final String ERR_TRADE_REJECTED = "TRADE_REJECTED";
    public static final String ERR_NO_IDENTITY = "NO_IDENTITY";

    /**
     * 记录一次工具调用。
     *
     * @param toolName    工具名（如 searchCourses）
     * @param requestId   请求 ID
     * @param sessionId   会话 ID
     * @param costMs      执行耗时（毫秒）
     * @param success     是否成功（受控业务分支如 CONFIRM_REQUIRED 属于正常流程，按 success=true、errorKind 标注）
     * @param resultCount 结果数量（卡片数或结果条数）
     * @param errorKind   受控错误类型（无则 null）
     */
    public void record(String toolName, String requestId, String sessionId,
                       long costMs, boolean success, int resultCount, String errorKind) {
        totalCalls.incrementAndGet();
        totalCostMs.addAndGet(Math.max(0, costMs));
        if (success) {
            successCalls.incrementAndGet();
        } else {
            failedCalls.incrementAndGet();
            failuresByTool.computeIfAbsent(String.valueOf(toolName), k -> new AtomicLong()).incrementAndGet();
        }
        // 结构化审计日志：仅含标识与指标，不含用户原文/密钥/令牌
        AUDIT.info("tool={} requestId={} sessionId={} costMs={} success={} resultCount={} errorKind={}",
                toolName, requestId, sessionId, costMs, success, resultCount, errorKind);
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("totalCalls", totalCalls.get());
        m.put("successCalls", successCalls.get());
        m.put("failedCalls", failedCalls.get());
        m.put("avgCostMs", totalCalls.get() == 0 ? 0 : totalCostMs.get() / totalCalls.get());
        Map<String, Long> fails = new java.util.LinkedHashMap<>();
        failuresByTool.forEach((k, v) -> fails.put(k, v.get()));
        m.put("failuresByTool", fails);
        return m;
    }
}
