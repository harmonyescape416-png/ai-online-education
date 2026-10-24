package com.kzai.aigc.tools;

/**
 * Spring AI ToolContext 中的键（M7）。
 * <p>
 * 只传必要的<b>可信</b>上下文：userId/roles 来自网关验证后的认证上下文，
 * 由 ChatService 写入，模型无法伪造；工具执行线程据此安全恢复 UserContext。
 * 不传完整 JWT。本轮原文只在服务端 Round 内临时用于推荐参数来源校验，不进入工具 schema 或审计。
 */
public final class ToolContextKeys {

    /** 可信用户 ID（Long，来自网关注入的认证上下文） */
    public static final String USER_ID = "kzaiUserId";
    /** 可信角色列表（List&lt;String&gt;） */
    public static final String ROLES = "kzaiRoles";
    /** 请求 ID（链路追踪） */
    public static final String REQUEST_ID = "kzaiRequestId";
    /** 会话 ID（提案/停止标识绑定） */
    public static final String SESSION_ID = "kzaiSessionId";
    /** 本轮工具结果暂存实例（ToolResultHolder） */
    public static final String ROUND = "kzaiRound";

    private ToolContextKeys() {
    }
}
