package com.kzai.aigc.stats.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * M13 模型调用日志（真实调用链路，每业务请求轮次一条）。
 * 口径与红线（用户批准方案）：
 * <ul>
 *   <li>provider 严格区分 REAL(DASHSCOPE)/MOCK；MOCK 不得计入真实模型指标；</li>
 *   <li>first_token_ms 仅真实测量（流式首增量）时记录；token 仅 provider 真实 usage，缺省 NULL；</li>
 *   <li>不存 Prompt/正文/JWT/API Key；写入失败仅告警不阻断对话；</li>
 *   <li>保留 90 天（与操作日志保留策略同批清理）。</li>
 * </ul>
 */
@Data
@TableName("ai_model_call_log")
public class AiModelCallLog {

    public static final String SOURCE_CHAT = "CHAT";
    public static final String SOURCE_ROUTE = "ROUTE";
    public static final String SOURCE_QUESTION = "QUESTION";
    public static final String SOURCE_TITLE = "TITLE";
    // M14 语音调用来源（语音用量独立概念，token 恒 NULL）
    public static final String SOURCE_STT = "STT";
    public static final String SOURCE_TTS = "TTS";

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String requestId;
    private Long userId;
    private String sessionId;
    /** 调用来源：CHAT/ROUTE/QUESTION/TITLE */
    private String source;
    /** 路由命中 Agent（可空） */
    private String agent;
    /** DASHSCOPE(REAL) / MOCK */
    private String provider;
    private String modelCode;
    /** 0失败 1成功 */
    private Integer success;
    private String errorKind;
    private Integer routeLatencyMs;
    private Integer firstTokenMs;
    private Integer totalLatencyMs;
    private Integer promptTokens;
    private Integer completionTokens;
    private Integer totalTokens;
    private LocalDateTime createTime;
}
