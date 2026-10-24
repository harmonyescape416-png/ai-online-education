package com.kzai.course.service;

import com.kzai.course.client.OperationLogClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** M13：course 侧统一审计桥（审核通过/驳回/下架真实落库；失败不阻断业务，WARN 留痕）。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CourseAuditBridge {

    private final OperationLogClient client;

    public boolean write(long operatorId, String operatorName, String operation,
                         String targetType, Long targetId, String detail) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("operatorId", operatorId);
            body.put("operatorName", operatorName);
            body.put("module", "COURSE");
            body.put("operation", operation);
            body.put("targetType", targetType);
            body.put("targetId", targetId);
            body.put("detail", detail);
            var r = client.writeOperationLog(body);
            return r != null && Boolean.TRUE.equals(r.getData());
        } catch (RuntimeException ex) {
            log.warn("[course.audit] 审计写入失败（不阻断业务）: operation={} target={} err={}",
                    operation, targetId, ex.getMessage());
            return false;
        }
    }
}
