package com.kzai.trade.payment;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("trade_refund")
public class TradeRefund {

    public static final String APPLYING = "APPLYING";
    public static final String REJECTED = "REJECTED";
    public static final String APPROVED = "APPROVED";
    public static final String REFUNDING = "REFUNDING";
    public static final String SUCCESS = "SUCCESS";
    public static final String FAILED = "FAILED";

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String refundNo;
    private String requestId;
    private Long orderId;
    private Long detailId;
    private Long userId;
    private Integer amount;
    private String reason;
    private String status;
    private String rejectReason;
    private Long reviewer;
    private LocalDateTime reviewTime;
    private String channelRefundNo;
    private String failReason;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private Integer openFlag;
}
