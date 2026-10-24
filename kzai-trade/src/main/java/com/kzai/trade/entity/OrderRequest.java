package com.kzai.trade.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 预下单幂等记录（M4 扩展表，原始 order/order_detail 无 requestId 字段）。
 * <p>
 * 唯一索引 uk_user_request(user_id, request_id) 是并发去重的最終保证：
 * 数据库约束 + 同一事务，不依赖 Redis 锁。
 */
@Data
@TableName("order_request_idempotent")
public class OrderRequest {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long userId;

    private String requestId;

    /** 请求内容摘要，用于识别"同一 requestId 不同内容"的冲突 */
    private String requestFingerprint;

    private Long orderId;

    /** 0处理中，1已创建订单 */
    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
