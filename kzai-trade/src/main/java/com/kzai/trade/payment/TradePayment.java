package com.kzai.trade.payment;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("trade_payment")
public class TradePayment {

    public static final String WAIT_PAY = "WAIT_PAY";
    public static final String SUCCESS = "SUCCESS";
    public static final String CLOSED = "CLOSED";
    public static final String IGNORED = "IGNORED";

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String paymentNo;
    private Long orderId;
    private Long userId;
    private String channel;
    private Integer amount;
    private String currency;
    private String status;
    private String merchantId;
    private String channelTradeNo;
    private LocalDateTime expireTime;
    private LocalDateTime successTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private Integer openFlag;
}
