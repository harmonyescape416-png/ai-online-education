package com.kzai.aigc.eval.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 评测单条结果（M12）。
 * <ul>
 *   <li>每行 = 某批次下某用例的某次尝试；UNIQUE(run_id, case_id, attempt)；</li>
 *   <li>重试 = 新 attempt 新行，历史永不覆盖；</li>
 *   <li>status：0 待执行 → 1 完成（判定有效）/ 2 执行失败 / 3 超时 / 4 跳过；
 *       执行失败与模型判断错误由此天然分离；</li>
 *   <li>token 只接受真实 provider usage（token_source=provider），否则 NULL（unavailable）；
 *       MOCK 为 mock_na。</li>
 * </ul>
 */
@Data
@TableName("ai_eval_result")
public class AiEvalResult {

    public static final int ST_PENDING = 0;
    public static final int ST_FINISHED = 1;
    public static final int ST_EXEC_FAILED = 2;
    public static final int ST_TIMEOUT = 3;
    public static final int ST_SKIPPED = 4;

    public static final String TOKEN_PROVIDER = "provider";
    public static final String TOKEN_UNAVAILABLE = "unavailable";
    public static final String TOKEN_MOCK_NA = "mock_na";

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long runId;
    private Long caseId;
    private Integer attempt;
    private Integer status;
    private String category;
    private String expectedIntent;
    private String expectedDocId;

    private String actualIntent;
    private Integer routeLatencyMs;
    private String actualDocIds;
    private String actualDocVersions;
    private Integer ragHit;
    private Integer ragEmptyRecall;
    private Integer ragLatencyMs;

    private String answer;
    private Integer answerLatencyMs;
    private Integer firstTokenMs;
    private Integer latencyMs;

    private Integer promptTokens;
    private Integer completionTokens;
    private Integer totalTokens;
    private String tokenSource;

    private Integer refusal;
    private String refusalRule;
    private Integer hasCitation;
    private Integer leakSuspected;
    private Integer writeToolAttempt;

    private Integer passed;
    private String verdictRule;
    private String failReason;
    private String provider;
    private String modelCode;
    private String error;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
