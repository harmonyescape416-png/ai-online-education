package com.kzai.learning.points;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("point_rule")
class PointRule {
    @TableId(value = "source", type = IdType.INPUT)
    private String source;
    private Integer points;
    private Integer dailyCap;
    private Integer enabled;
    private LocalDateTime updateTime;
}

@Data
@TableName("point_account")
class PointAccount {
    @TableId(value = "user_id", type = IdType.INPUT)
    private Long userId;
    private Integer totalPoints;
    private LocalDateTime updateTime;
}

@Data
@TableName("point_ledger")
class PointLedger {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long userId;
    private String source;
    private String eventKey;
    private Integer points;
    private LocalDate bizDate;
    private String periodKey;
    private String reason;
    private Long operatorId;
    private LocalDateTime createTime;
}

/** 发分失败补偿（outbox）：award 异常时独立事务落库，由定时任务重试，防止积分静默丢失。 */
@Data
@TableName("point_pending_award")
class PointPendingAward {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long userId;
    private String source;
    private String eventKey;
    private LocalDate bizDate;
    private String reason;
    /** PENDING 待重试 / DONE 已入账或确认无需入账 / FAILED 超过重试上限 */
    private String status;
    private Integer retryCount;
    private String lastError;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}

@Data
@TableName("checkin_record")
class CheckinRecord {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long userId;
    private LocalDate bizDate;
    private Integer streak;
    private LocalDateTime createTime;
}

@Data
@TableName("rank_period")
class RankPeriod {
    @TableId(value = "period_key", type = IdType.INPUT)
    private String periodKey;
    private String status;
    private LocalDateTime settleTime;
}

@Data
@TableName("rank_entry")
class RankEntry {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String periodKey;
    private Long userId;
    private Integer points;
    private Integer rankNo;
    private String displayName;
}
