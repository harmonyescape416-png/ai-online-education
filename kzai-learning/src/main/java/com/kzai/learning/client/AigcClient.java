package com.kzai.learning.client;

import com.kzai.common.constant.Constants;
import com.kzai.common.feign.InternalAuthFeignConfig;
import com.kzai.common.result.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.Map;

/**
 * M10：aigc 内部 AI 生成客户端（可信内部签名，见 InternalAuthFeignConfig）。
 * 仅由 QuestionReplyWorker 在服务端调用；学员/教师不可直接触达该接口。
 */
@FeignClient(name = "kzai-aigc", contextId = "aigcInternal",
        configuration = InternalAuthFeignConfig.class)
public interface AigcClient {

    /** 课程问答 AI 生成（以提问学员身份做 RAG 权益过滤；MOCK 显著标识） */
    @PostMapping(Constants.INTERNAL_PATH_PREFIX + "ai/generate")
    R<Map<String, Object>> generate(@RequestBody Map<String, Object> body);
}
