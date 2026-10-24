package com.kzai.aigc.prompt;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** M15.19-D：Prompt 模板版本记录（草稿/发布/回滚；独立扩展表不改 12 核心表） */
@Data
@TableName("prompt_version")
public class PromptVersionEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String agentType;
    private String content;
    private Integer versionNo;
    /** PUBLISH / ROLLBACK / DRAFT */
    private String action;
    private Long operator;
    /** 1 成功 / 0 失败（失败保留最后有效版本） */
    private Integer resultOk;
    private String resultMsg;
    private LocalDateTime createTime;
}
