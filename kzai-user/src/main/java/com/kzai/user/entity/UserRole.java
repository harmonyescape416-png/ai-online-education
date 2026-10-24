package com.kzai.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户权限角色（M2 扩展表 sys_user_role，见 sql/03_m2_user_manage.sql）。
 * <p>
 * 原始 user.type 仅区分 1员工/2学员/3老师，无法区分管理员与普通员工，
 * 故用本表承载权限角色，user 表零改动；无记录时按 user.type 推导，历史账号兼容。
 */
@Data
@TableName("sys_user_role")
public class UserRole {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 用户ID，关联 user.id */
    private Long userId;

    /** 角色编码：ADMIN / STAFF / TEACHER / STUDENT */
    private String roleCode;

    private LocalDateTime createTime;

    private Long creater;
}
