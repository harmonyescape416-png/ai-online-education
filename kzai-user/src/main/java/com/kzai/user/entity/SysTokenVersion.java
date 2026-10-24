package com.kzai.user.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户 token 版本号（sql/04_m2_token_version.sql）。
 * 持久化撤销：Redis 只做缓存，本表为权威来源。
 */
@Data
@TableName("sys_token_version")
public class SysTokenVersion {

    /** 用户ID，与 user.id 一致（非自增） */
    @TableId
    private Long userId;

    /** 版本号 */
    private Integer version;

    private LocalDateTime updateTime;
}
