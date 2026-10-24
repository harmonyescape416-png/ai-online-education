package com.kzai.aigc.config.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** M13 系统参数（生效值+草稿+乐观锁版本；sql/14_m13_management.sql，用户批准）。 */
@Data
@TableName("sys_config_param")
public class SysConfigParam {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String paramKey;
    private String paramName;
    private String description;
    private String effectiveValue;
    private String draftValue;
    private String valueType;
    private String minValue;
    private String maxValue;
    private Integer version;
    private Long draftUpdatedBy;
    private LocalDateTime draftUpdateTime;
    private Long lastPublishedBy;
    private LocalDateTime publishTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
