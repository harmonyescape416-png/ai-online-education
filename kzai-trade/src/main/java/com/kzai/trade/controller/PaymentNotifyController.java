package com.kzai.trade.controller;

import com.kzai.common.result.R;
import com.kzai.trade.payment.PaymentAppService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 支付通知。不读取登录用户，只验签后入账。网关需放行该路径。 */
@RestController
@RequiredArgsConstructor
public class PaymentNotifyController {

    private final PaymentAppService payments;

    @PostMapping("/orders/payments/notify/{channel}")
    public R<String> notify(@PathVariable("channel") String channel, @RequestParam Map<String, String> params) {
        String normalized = "alipay".equalsIgnoreCase(channel) ? "ALIPAY" : "MOCK";
        payments.acceptNotify(normalized, params);
        return R.ok("success");
    }
}
