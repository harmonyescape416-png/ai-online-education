package com.kzai.aigc.agent;

import lombok.ToString;

/**
 * 路由结果（M8）。
 * <p>
 * agent=null 且 directAnswer=true 表示寒暄/无关意图（按原始要求直接回复）；
 * fallback=true 表示模型输出无法解析或路由调用失败，已按原始要求兜底 ConsultAgent。
 * 不携带用户问题正文（避免隐私进入日志/展示）。
 */
@ToString
public class RouteResult {

    /** 命中的专业 Agent；寒暄/无关为 null */
    private final AgentTypeEnum agent;
    /** 模型原始输出（仅枚举词，用于排查；不含用户正文） */
    private final String rawIntent;
    /** true=寒暄/无关，直接回复 */
    private final boolean directAnswer;
    /** true=解析失败或路由异常，已兜底 CONSULT */
    private final boolean fallback;
    /** 路由耗时（毫秒） */
    private final long costMs;
    /** 失败类型（ROUTE_MODEL_ERROR 等，无则 null） */
    private final String errorKind;

    private RouteResult(AgentTypeEnum agent, String rawIntent, boolean directAnswer,
                        boolean fallback, long costMs, String errorKind) {
        this.agent = agent;
        this.rawIntent = rawIntent;
        this.directAnswer = directAnswer;
        this.fallback = fallback;
        this.costMs = costMs;
        this.errorKind = errorKind;
    }

    public static RouteResult ofAgent(AgentTypeEnum agent, String rawIntent, long costMs) {
        return new RouteResult(agent, rawIntent, false, false, costMs, null);
    }

    public static RouteResult direct(long costMs, String rawIntent) {
        return new RouteResult(null, rawIntent, true, false, costMs, null);
    }

    public static RouteResult fallback(long costMs, String errorKind) {
        return new RouteResult(AgentTypeEnum.CONSULT, null, false, true, costMs, errorKind);
    }

    public AgentTypeEnum getAgent() {
        return agent;
    }

    public String getRawIntent() {
        return rawIntent;
    }

    public boolean isDirectAnswer() {
        return directAnswer;
    }

    public boolean isFallback() {
        return fallback;
    }

    public long getCostMs() {
        return costMs;
    }

    public String getErrorKind() {
        return errorKind;
    }
}
