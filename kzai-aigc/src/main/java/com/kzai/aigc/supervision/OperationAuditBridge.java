package com.kzai.aigc.supervision;

import com.kzai.aigc.client.OperationLogClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * M13：aigc 侧统一审计桥（复用 sys_operation_log 既有审计机制）。
 * 审计失败不阻断监管主流程，仅 WARN 留痕（与 learning OperationAuditLogger 同语义）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OperationAuditBridge {

    private final OperationLogClient client;

    public boolean write(long operatorId, String operatorName, String module, String operation,
                         String targetType, Long targetId, String detail) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("operatorId", operatorId);
            body.put("operatorName", operatorName);
            body.put("module", module);
            body.put("operation", operation);
            body.put("targetType", targetType);
            body.put("targetId", targetId);
            body.put("detail", detail);
            var r = client.writeOperationLog(body);
            return r != null && Boolean.TRUE.equals(r.getData());
        } catch (RuntimeException ex) {
            log.warn("[aigc.audit] 审计写入失败（不阻断业务）: operation={} target={} err={}",
                    operation, targetId, ex.getMessage());
            return false;
        }
    }
}
