package com.kzai.aigc.agent;

/**
 * 智能体类型（M8，原始需求 3.2.4.3 / 设计说明书 4.5：5 个 Agent 类型枚举）。
 * <p>
 * ROUTE 为路由智能体；其余四个为专业智能体。
 * OTHER 不是智能体——表示寒暄/无关意图，按原始要求直接回复。
 */
public enum AgentTypeEnum {

    /** 路由智能体：非流式意图判断，无业务工具，不写会话记忆 */
    ROUTE("路由智能体"),
    /** 课程推荐智能体：课程只读查询工具 */
    RECOMMEND("课程推荐"),
    /** 课程咨询智能体：课程只读查询工具 */
    CONSULT("课程咨询"),
    /** 购买咨询与预下单智能体：课程查询 + 受确认门禁保护的预下单 */
    BUY("购买助手"),
    /** 知识讲解智能体：M9 前无 RAG/内部知识库，不授业务工具 */
    KNOWLEDGE("知识讲解");

    private final String displayName;

    AgentTypeEnum(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    /** 该 Agent 是否允许持有业务工具 */
    public boolean hasTools() {
        return this == RECOMMEND || this == CONSULT || this == BUY;
    }

    /**
     * 严格枚举解析：模型输出必须命中合法枚举值，否则返回 null（由调用方兜底）。
     * 不使用模糊的关键词语义匹配冒充 AI 路由——只接受精确枚举词。
     */
    public static AgentTypeEnum parseIntent(String raw) {
        if (raw == null) {
            return null;
        }
        String t = raw.trim().toUpperCase();
        // 按长度倒序匹配，避免误命中子串
        AgentTypeEnum[] intents = {AgentTypeEnum.RECOMMEND, AgentTypeEnum.KNOWLEDGE,
                AgentTypeEnum.CONSULT, AgentTypeEnum.BUY};
        for (AgentTypeEnum intent : intents) {
            if (t.contains(intent.name())) {
                return intent;
            }
        }
        if (t.contains("OTHER") || t.contains("GREETING") || t.contains("OUT_OF_SCOPE")) {
            return null; // 寒暄/无关由调用方直接回复，不是专业 Agent
        }
        return null;
    }
}
