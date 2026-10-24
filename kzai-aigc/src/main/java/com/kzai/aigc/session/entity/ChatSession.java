package com.kzai.aigc.session.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 会话表（原始表 chat_session，8 字段零改动）。
 * 消息正文不落此表，只存 Redis List：CHAT:{userId_sessionId}。
 */
@Data
@TableName("chat_session")
public class ChatSession {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 会话业务 ID（UUID），前端持有 */
    private String sessionId;

    /** 归属用户（服务端上下文注入） */
    private Long userId;

    /** 会话标题（首条问题前 100 字，可被用户主动重命名） */
    private String title;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private Long creater;
    /** M15.20-A：创建时各类型已发布 Prompt 版本号 JSON（NULL=存量会话用当前生效模板） */
    private String promptVersionMap;
    private Long updater;
}
