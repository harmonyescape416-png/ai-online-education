package com.kzai.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 重要管理操作日志（M2 扩展表 sys_operation_log，见 sql/03_m2_user_manage.sql）。
 */
@Data
@TableName("sys_operation_log")
public class OperationLog {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 操作人ID */
    private Long operatorId;

    /** 操作人账号 */
    private String operatorName;

    /** 模块：USER / COURSE */
    private String module;

    /** 操作：CREATE / UPDATE / ROLE / STATUS / RESET_PWD / DELETE */
    private String operation;

    /** 对象类型：USER */
    private String targetType;

    /** 对象ID */
    private Long targetId;

    /** 操作详情 */
    private String detail;

    private LocalDateTime createTime;
}
