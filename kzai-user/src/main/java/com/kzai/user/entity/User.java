package com.kzai.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * user 表映射（数据库设计说明书 3.3.1，10 个字段）。
 * 表名是 MySQL 保留字，需反引号转义。
 */
@Data
@TableName("`user`")
public class User {

    /** 用户ID，主键，雪花算法生成 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String username;

    private String cellPhone;

    /** 密码哈希值（BCrypt），不存明文 */
    private String password;

    /** 状态：0禁用，1正常 */
    private Integer status;

    /** 类型：1员工，2学员，3老师 */
    private Integer type;

    @TableField("create_time")
    private LocalDateTime createTime;

    @TableField("update_time")
    private LocalDateTime updateTime;

    private Long creater;

    private Long updater;
}
