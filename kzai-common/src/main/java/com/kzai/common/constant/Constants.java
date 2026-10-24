package com.kzai.common.constant;

import java.util.List;

/**
 * 公共常量。
 * 对应《系统设计说明书》5.5.3「Constant：工具名、参数名、事件类型常量」与第 8 章缓存键设计。
 */
public final class Constants {

    private Constants() {
    }

    /** 网关注入的用户上下文请求头 */
    public static final String HEADER_USER_ID = "X-User-Id";
    /** 网关注入的角色请求头（1员工/管理员，2学员，3老师） */
    public static final String HEADER_USER_ROLE = "X-User-Role";
    /** 网关注入的登录账号 */
    public static final String HEADER_USERNAME = "X-Username";
    /** 网关注入的权限角色（M2 扩展，逗号分隔的 role_code） */
    public static final String HEADER_USER_ROLES = "X-User-Roles";
    /** requestId 贯穿网关、业务服务与工具调用日志 */
    public static final String HEADER_REQUEST_ID = "X-Request-Id";

    /** 会话消息 Redis 前缀，conversationId = userId + "_" + sessionId（设计说明书 8.1 / 7.1） */
    public static final String REDIS_KEY_CHAT = "CHAT:";
    /** 停止生成标识（设计说明书 8.1，TTL 5 分钟） */
    public static final String REDIS_KEY_AI_STOP = "AI:STOP:";
    /** 课程热点缓存（设计说明书 8.1，TTL 10 分钟） */
    public static final String REDIS_KEY_COURSE = "course:";
    /** 评测汇总缓存（设计说明书 8.1，TTL 30 分钟） */
    public static final String REDIS_KEY_EVAL_SUMMARY = "eval:summary";
    /** 扩展设计 E1：token 失效黑名单前缀（按 jti 精确失效） */
    public static final String REDIS_KEY_TOKEN_BLACKLIST = "AUTH:BLACKLIST:";
    /** 扩展设计 E1：用户级 token 版本号，禁用/重置密码/改密后自增，使该用户全部旧 token 失效 */
    public static final String REDIS_KEY_TOKEN_VERSION = "AUTH:TOKEN:VER:";

    /** 权限角色编码（M2 扩展表 sys_user_role.role_code） */
    public static final String ROLE_ADMIN = "ADMIN";
    public static final String ROLE_STAFF = "STAFF";
    public static final String ROLE_TEACHER = "TEACHER";
    public static final String ROLE_STUDENT = "STUDENT";

    /** 网关注入的内部请求签名，业务服务校验通过后才信任身份头（M2 遗留修复④） */
    public static final String HEADER_INTERNAL_SIGN = "X-Internal-Sign";

    /** 服务间调用路径前缀，网关拒绝外部访问该前缀（M2 权限约束） */
    public static final String INTERNAL_PATH_PREFIX = "/internal/";

    /** 删除用户前必须校验的关联业务类型（M2 遗留修复③：严格 fail-closed） */
    public static final String ASSOC_COURSE = "COURSE";
    public static final String ASSOC_ORDER = "ORDER";
    public static final String ASSOC_LEARNING = "LEARNING";
    public static final String ASSOC_CHAT_SESSION = "CHAT_SESSION";
    public static final String ASSOC_INTERACTION = "INTERACTION";

    /**
     * 12 张核心表中与 user 存在直接业务关联的类型。
     * 删除用户前每一类都必须有对应的校验实现；缺失即拒绝删除，
     * 不得假设"服务还没建所以没有业务数据"。
     */
    public static final List<String> REQUIRED_DELETION_BINDINGS = List.of(
            ASSOC_COURSE, ASSOC_ORDER, ASSOC_LEARNING, ASSOC_CHAT_SESSION, ASSOC_INTERACTION);

    /** M3：课程审核状态（course_audit.audit_status，独立于 course.status 原文枚举） */
    public static final int AUDIT_PENDING = 1;
    public static final int AUDIT_APPROVED = 2;
    public static final int AUDIT_REJECTED = 3;
    public static final int AUDIT_OFFLINE = 4;

    /** M3：课程资源类型（course_resource.kind） */
    public static final int RES_KIND_COVER = 1;
    public static final int RES_KIND_MATERIAL = 2;
    public static final int RES_KIND_VIDEO = 3;

    /** M5：学习状态（learning_lesson.status / learning_lesson_progress.status，沿用原文枚举） */
    public static final int LEARN_STATUS_NOT_STARTED = 0; // 未开始
    public static final int LEARN_STATUS_LEARNING = 1;    // 学习中
    public static final int LEARN_STATUS_FINISHED = 2;    // 已完成

    /**
     * M5：退款状态（order_detail.refund_status）。
     * 原始《数据库设计说明书》3.3.7 仅写"退款状态"，未给枚举。
     * 按"未定义即不得默认授予权益"的原则补全，并在 decisions.md D5-3 登记。
     */
    public static final int REFUND_NONE = 0;      // 无退款（NULL 与 0 等价）
    public static final int REFUND_APPLYING = 1;  // 退款申请中：暂停学习权益
    public static final int REFUND_SUCCESS = 2;   // 已退款：永久收回学习权益
    public static final int REFUND_REJECTED = 3;  // 退款驳回：视为正常，保留权益
    public static final int REFUND_FAILED = 4;    // 渠道退款失败：未退成，保留权益

    /** M5：学习权益来源 */
    public static final String ENTITLEMENT_FREE = "FREE";   // 免费课程入课
    public static final String ENTITLEMENT_PAID = "PAID";   // 已支付订单明细
    public static final String ENTITLEMENT_NONE = "NONE";   // 无权益

    /** M5：单次进度上报允许累计的最大有效学习时长（秒），防止伪造时长 */
    public static final int MAX_STUDY_SECONDS_PER_REPORT = 900;

    /** M4：订单状态（order.status 与 order_detail.status，沿用原文枚举） */
    public static final int ORDER_STATUS_PENDING = 1;   // 待支付
    public static final int ORDER_STATUS_PAID = 2;      // 已支付
    public static final int ORDER_STATUS_CLOSED = 3;    // 已关闭
    public static final int ORDER_STATUS_FINISHED = 4;  // 已完成
    public static final int ORDER_STATUS_ENROLLED = 5;  // 已报名
    public static final int ORDER_STATUS_REFUNDING = 6; // 已申请退款

    /** M4：幂等记录状态（order_request_idempotent.status） */
    public static final int IDEMPOTENT_PROCESSING = 0;
    public static final int IDEMPOTENT_DONE = 1;

    /** 操作日志模块（M2 扩展表 sys_operation_log.module） */
    public static final String MODULE_USER = "USER";
    public static final String MODULE_COURSE = "COURSE";
    public static final String MODULE_TRADE = "TRADE";
    /** M5：学习模块 */
    public static final String MODULE_LEARNING = "LEARNING";
    /** 操作日志操作类型 */
    public static final String OP_CREATE = "CREATE";
    public static final String OP_IMPORT = "IMPORT";
    public static final String OP_UPDATE = "UPDATE";
    public static final String OP_ROLE = "ROLE";
    public static final String OP_STATUS = "STATUS";
    public static final String OP_RESET_PWD = "RESET_PWD";
    public static final String OP_DELETE = "DELETE";
    /** M3：课程提交审核 / 审核 / 强制下架 */
    public static final String OP_SUBMIT = "SUBMIT";
    public static final String OP_AUDIT = "AUDIT";
    public static final String OP_OFFLINE = "OFFLINE";

    /** SSE 事件类型（需求分析说明书 3.1.4、系统设计说明书 5.5.3） */
    public static final int EVENT_TEXT = 1001;
    public static final int EVENT_PARAM = 1003;
    public static final int EVENT_FINISH = 1002;

    /** AI 系统账号 id（系统设计说明书 4.6：自动回复落库使用系统用户 9999） */
    public static final Long AI_SYSTEM_USER_ID = 9999L;

    /** 用户类型（数据库设计说明书 3.3.1） */
    public static final int USER_TYPE_STAFF = 1;
    public static final int USER_TYPE_STUDENT = 2;
    public static final int USER_TYPE_TEACHER = 3;

    /** 课程状态（数据库设计说明书 3.3.3）：1待上架，2已上架，3下架，4已完结 */
    public static final int COURSE_STATUS_PENDING = 1;
    public static final int COURSE_STATUS_ONLINE = 2;
    public static final int COURSE_STATUS_OFFLINE = 3;
    public static final int COURSE_STATUS_FINISHED = 4;

}
