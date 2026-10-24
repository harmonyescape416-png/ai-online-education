package com.kzai.aigc.eval.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 评测用例（M12，原始表 5.2.11：<b>11 字段零改动</b>）。
 * <p>
 * {@code actual_intent/actual_doc_ids/latency_ms/token_usage/passed} 仅作为
 * 「最近一次执行」的兼容性镜像回写；历史事实一律以 {@code ai_eval_result} 为准
 * （一个用例可执行多次，每次对应不同批次/模型/Prompt/知识库版本）。
 */
@Data
@TableName("ai_eval_case")
public class AiEvalCase {

    public static final String CAT_RECOMMEND = "RECOMMEND";
    public static final String CAT_CONSULT = "CONSULT";
    public static final String CAT_BUY = "BUY";
    public static final String CAT_KNOWLEDGE = "KNOWLEDGE";
    public static final String CAT_GREETING = "GREETING";
    public static final String CAT_OUT_OF_SCOPE = "OUT_OF_SCOPE";

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String question;
    private String category;
    private String expectedIntent;
    private String expectedDocId;
    /** 最近一次执行镜像（历史事实以 ai_eval_result 为准） */
    private String actualIntent;
    /** 最近一次执行镜像 */
    private String actualDocIds;
    /** 最近一次执行镜像（端到端耗时 ms） */
    private Integer latencyMs;
    /** 最近一次执行镜像（token 用量，NULL=不可用） */
    private Integer tokenUsage;
    /** 最近一次执行镜像：0否 1是 */
    private Integer passed;
    private LocalDateTime createTime;
}
