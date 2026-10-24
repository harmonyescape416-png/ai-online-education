package com.kzai.aigc.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "kzai.aigc")
public class AiProperties {
    /** 同一会话同时只允许一个生成任务（应用内锁：单实例生效） */
    private int maxConcurrentSessions = 1;
    /** 会话消息 Redis TTL（小时） */
    private int memoryTtlHours = 168;
    /** 停止标识 Redis TTL（分钟） */
    private int stopTtlMinutes = 5;
    /** 历史会话列表上限 */
    private int historyLimit = 30;
    /** 标题截取长度 */
    private int titleLength = 100;
    /** 是否强制 MOCK（即便有 API Key） */
    private boolean forceMock = true;
    /** MOCK 模式单次增量延迟（毫秒） */
    private int mockDelayMs = 60;
    /**
     * M7：MOCK 工具脚本开关（仅 MOCK Provider 生效）。
     * 按确定性脚本触发与真实路径完全相同的工具实现，用于验证 1003 卡片/提案/幂等协议；
     * 不计入真实模型 Tool Calling 验收。
     */
    private boolean mockToolScript = true;
    /** M7：课程工具单次搜索返回上限（1–10） */
    private int toolSearchLimit = 5;
    /** M7：购买确认提案有效期（分钟），过期自动失效防止复用 */
    private int proposalTtlMinutes = 10;
    /** M8：多智能体路由开关（关闭则保持 M7 单 Agent 行为） */
    private boolean multiAgentEnabled = true;
    /** M8：路由失败详情是否输出 WARN 日志（默认仅 ROUTE_AUDIT 摘要） */
    private boolean routeAuditVerbose = false;
    /** M9：Elasticsearch 地址 */
    private String knowledgeEsUrl = "http://127.0.0.1:9200";
    /** M9：ES 读别名 */
    private String knowledgeIndexAlias = "kzai-knowledge-alias";
    /** M9：ES 物理索引前缀（版本化 kzai-knowledge-v{N}） */
    private String knowledgeIndexPrefix = "kzai-knowledge";
    /** M9：检索 TopK（原始约束：6，最大 20） */
    private int knowledgeTopK = 6;
    /** M9：相似度阈值（cosine 归一化口径 [0,1]，ES _score 直接可比；原始约束：0.6） */
    private double knowledgeMinScore = 0.6;
    /** M9：Embedding 提供方：mock（缺省，显著标识）/ dashscope（真实，需 Key） */
    private String embeddingProvider = "mock";
    /** M9：上传文件大小上限（字节，默认 10MB） */
    private long knowledgeMaxFileSize = 10 * 1024 * 1024;
    /** M9：知识文件受控存储目录（服务端生成路径，绝不拼接用户输入文件名） */
    private String knowledgeStorageDir = "./data/knowledge";
    /** 系统提示词 */
    private String systemPrompt = "你是「课栈智伴」在线教育平台的 AI 助手，请用中文简洁作答，回答支持 Markdown。"
            + "若被问到课程、订单或学习进度等与用户账号相关的问题，请告知用户前往课程详情页、我的订单或我的学习查看。";
    /** 欢迎语（从配置读取，非模型生成） */
    private String welcomeText = "你好，我是「课栈智伴」AI 助手。试试问我：在线教育平台有哪些特色课程？"
            + "或描述一下你正在学习的主题，我会给出学习建议。";
}
