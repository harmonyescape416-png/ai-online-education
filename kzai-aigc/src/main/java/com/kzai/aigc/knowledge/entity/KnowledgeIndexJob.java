package com.kzai.aigc.knowledge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 知识库索引任务（M9）。
 * 状态机：0待处理 → 1执行中 → 2成功 / 3失败（可重试，attempt 上限 5）；
 * 服务重启时「执行中」任务由补偿逻辑重置为「待处理」。
 */
@Data
@TableName("ai_knowledge_index_job")
public class KnowledgeIndexJob {

    public static final int STATUS_PENDING = 0;
    public static final int STATUS_RUNNING = 1;
    public static final int STATUS_SUCCESS = 2;
    public static final int STATUS_FAILED = 3;

    public static final String ACTION_REPARSE = "REPARSE";
    public static final String ACTION_REINDEX = "REINDEX";
    public static final String ACTION_DELETE = "DELETE";

    public static final int MAX_ATTEMPT = 5;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long docId;
    private String action;
    private Integer status;
    private Integer attempt;
    private String error;
    private Long operator;
    /** 创建任务时文档的审核轮次。轮次变化后本任务不得再发布。 */
    private Integer publishGeneration;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
