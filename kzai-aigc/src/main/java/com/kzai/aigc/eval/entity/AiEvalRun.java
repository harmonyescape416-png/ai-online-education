package com.kzai.aigc.eval.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 评测批次（M12，用户已批准扩展表）。
 * <ul>
 *   <li>状态机：0 运行中 → 1 成功 / 2 失败 / 3 部分成功 / 4 已终止 / 5 不可执行（REAL 未配置）；</li>
 *   <li>防并发重复：active_flag 生成列（仅运行中=1）+ UNIQUE → 全局至多一个运行中批次；</li>
 *   <li>重启策略（用户批准「明确终止」）：启动补偿将遗留运行中批次置 4，已完成结果保留。</li>
 * </ul>
 */
@Data
@TableName("ai_eval_run")
public class AiEvalRun {

    public static final int MODE_MOCK = 0;
    public static final int MODE_REAL = 1;

    public static final int STATUS_RUNNING = 0;
    public static final int STATUS_SUCCESS = 1;
    public static final int STATUS_FAILED = 2;
    public static final int STATUS_PARTIAL = 3;
    public static final int STATUS_TERMINATED = 4;
    public static final int STATUS_NOT_EXECUTABLE = 5;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String name;
    /** MOCK / REAL */
    private String execMode;
    private Integer status;
    private String provider;
    private String modelCode;
    private String embeddingProvider;
    private String kbPhysicalIndex;
    private String promptSnapshot;
    /** 本批选定用例 ID 快照（JSON 数组） */
    private String caseIds;
    private Integer totalCases;
    private Integer successCount;
    private Integer failedCount;
    private Integer errorCount;
    private Integer skippedCount;
    private Long executorId;
    private String error;
    private LocalDateTime createTime;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private LocalDateTime updateTime;
}
