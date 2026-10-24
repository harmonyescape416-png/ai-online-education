package com.kzai.learning.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * M15.21：AI 回复采纳记录（userId+replyId 唯一——联合主键由表 PK 承载；
 * MP 单 @TableId 限制：replyId 为逻辑主键，userId 普通字段，全部操作走条件查询/删除，不用 updateById）。
 */
@Data
@TableName("interaction_reply_helpful")
public class InteractionReplyHelpful {
    @TableId
    private Long replyId;
    private Long userId;
    private LocalDateTime createTime;
}
