package com.kzai.trade.compensation;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** M15.20-C：开课失败补偿记录（独立扩展表；12 核心表零改动） */
@Data
@TableName("enroll_compensation")
public class EnrollCompensationEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long orderId;
    private Long courseId;
    private Long userId;
    private Integer attempts;
    private String status;
    private String lastError;
    private LocalDateTime nextRetryAt;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

}
