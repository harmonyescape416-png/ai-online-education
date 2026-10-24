package com.kzai.auth.service;

import com.kzai.auth.client.OperationLogClient;
import com.kzai.common.result.R;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * M13：登录/鉴权事件审计（auth → user sys_operation_log，内部签名）。
 * 登录成功与失败均留痕（不记密码/JWT；失败记录用户名与原因类别）。
 * 审计失败不阻断登录（可用性优先），仅 WARN 留痕可观察。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LoginAuditBridge {

    private final OperationLogClient client;

    public boolean write(Long userId, String username, boolean success, String note) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("operatorId", userId == null ? 0L : userId);
            body.put("operatorName", username == null ? "unknown" : username);
            body.put("module", "AUTH");
            body.put("operation", success ? "LOGIN_SUCCESS" : "LOGIN_FAIL");
            body.put("targetType", "user");
            body.put("targetId", userId);
            body.put("detail", "{\"result\":\"" + (success ? "SUCCESS" : "FAIL")
                    + "\",\"note\":\"" + (note == null ? "" : note) + "\"}");
            R<Boolean> r = client.writeOperationLog(body);
            return r != null && Boolean.TRUE.equals(r.getData());
        } catch (RuntimeException ex) {
            log.warn("[auth.audit] 登录审计写入失败（不阻断登录）: {}", ex.getMessage());
            return false;
        }
    }
}
