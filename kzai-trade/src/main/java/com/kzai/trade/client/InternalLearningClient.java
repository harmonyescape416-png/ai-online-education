package com.kzai.trade.client;

import com.kzai.common.result.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.Map;

/**
 * M15.16：学习服务内部接口（InternalAuth 鉴权）——支付成功后开课。
 */
@FeignClient(name = "kzai-learning", path = "/internal/learning",
        configuration = com.kzai.common.feign.InternalAuthFeignConfig.class)
public interface InternalLearningClient {

    /** M15.22：核实学习档案是否存在（存量订单回填用） */
    @org.springframework.web.bind.annotation.PostMapping("/exists-lesson")
    R<java.lang.Boolean> existsLesson(@org.springframework.web.bind.annotation.RequestBody java.util.Map<String, Object> body);

    /** 支付成功后开课：为用户创建学习档案（learning_lesson，状态=待学习） */
    @PostMapping("/enroll-for-paid")
    R<Map<String, Object>> enrollForPaid(@RequestBody Map<String, Object> body);
}
