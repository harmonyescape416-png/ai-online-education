package com.kzai.aigc.prompt;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Prompt 操作审计（M8，无持久化表方案）：独立 logger（PROMPT_AUDIT）记录
 * 发布/回读校验/失败等操作元数据（操作人、Agent 类型、版本、结果、错误类型），
 * 不记录 Prompt 全文（防日志膨胀），绝不含密钥。
 */
@Slf4j
@Component
public class PromptAudit {

    private static final org.slf4j.Logger AUDIT = org.slf4j.LoggerFactory.getLogger("PROMPT_AUDIT");

    private final AtomicLong publishTotal = new AtomicLong();
    private final AtomicLong publishSuccess = new AtomicLong();
    private final AtomicLong publishFailed = new AtomicLong();

    public void recordPublish(Long operator, String agentType, String version,
                              boolean success, String errorKind) {
        publishTotal.incrementAndGet();
        if (success) {
            publishSuccess.incrementAndGet();
        } else {
            publishFailed.incrementAndGet();
        }
        AUDIT.info("action=publish operator={} agentType={} version={} success={} errorKind={}",
                operator, agentType, version, success, errorKind == null ? "null" : errorKind);
    }

    public void recordView(Long operator, String agentType) {
        AUDIT.info("action=view operator={} agentType={}", operator, agentType);
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("publishTotal", publishTotal.get());
        m.put("publishSuccess", publishSuccess.get());
        m.put("publishFailed", publishFailed.get());
        return m;
    }
}
