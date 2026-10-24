package com.kzai.aigc.config.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** M13 参数发布记录（只插入；区分 Nacos 发布结果与运行时生效验证）。 */
@Data
@TableName("sys_config_publish")
public class SysConfigPublish {

    public static final int ST_PUBLISHING = 0;
    public static final int ST_SUCCESS = 1;
    public static final int ST_FAILED = 2;

    public static final int RT_UNVERIFIED = 0;
    public static final int RT_VERIFIED = 1;
    public static final int RT_FAILED = 2;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Integer publishVersion;
    private String paramKeys;
    private String configSnapshot;
    private String valuesBefore;
    private String valuesAfter;
    private Integer publishStatus;
    private Integer runtimeVerified;
    private String nacosDataId;
    private String error;
    private Long publishedBy;
    private String publishedByName;
    private LocalDateTime effectiveTime;
    private LocalDateTime createTime;
}
