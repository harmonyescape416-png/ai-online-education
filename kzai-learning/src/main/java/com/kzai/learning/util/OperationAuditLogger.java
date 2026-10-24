package com.kzai.learning.util;

import com.kzai.common.result.R;
import com.kzai.learning.client.UserClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 跨服务统一审计写入（M10，用户确认方案：复用 sys_operation_log 现有审计机制）。
 * <p>
 * 通过内部 Feign 调用 user 服务落 sys_operation_log（learning 不跨服务写他库表）。
 * 审计失败不阻断主业务（与 user 侧 OperationLogService 同语义），仅 WARN 留痕。
 * 限制：detail 列 512 字符，长内容存 JSON 摘要；最近一次完整修正前文存
 * interaction_reply_extra.prev_content——二者结合构成修正历史（报告已说明）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OperationAuditLogger {

    private final UserClient userClient;

    public boolean log(long operatorId, String operatorName, String module, String operation,
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
            R<Boolean> r = userClient.writeOperationLog(body);
            return r != null && Boolean.TRUE.equals(r.getData());
        } catch (RuntimeException ex) {
            log.warn("[qa.audit] 审计写入失败（不阻断业务）: {} operation={} target={}",
                    ex.getMessage(), operation, targetId);
            return false;
        }
    }
}
