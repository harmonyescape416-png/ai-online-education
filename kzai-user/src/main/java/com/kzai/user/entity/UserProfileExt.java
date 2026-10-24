package com.kzai.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * M15.15 方案 A（用户批准）：用户扩展资料——昵称/头像。
 * 独立扩展表（user.id 1:1 共键），不修改原始 12 张核心表。
 */
@Data
@TableName("user_profile_ext")
public class UserProfileExt {
    /** 用户ID（与 user.id 一致，主键） */
    @TableId(type = IdType.INPUT)
    private Long userId;

    /** 昵称 */
    private String nickname;

    /** 头像访问地址（本地 /users/me/avatar） */
    private String avatarUrl;

    /** M15.15：昵称最近修改时间（7 天一次限制基准；NULL=从未修改） */
    private LocalDateTime nicknameUpdatedAt;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
