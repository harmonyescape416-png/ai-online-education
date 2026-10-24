package com.kzai.user.service;

import com.kzai.common.constant.Constants;
import com.kzai.common.result.R;
import com.kzai.user.client.AigcClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * AI 会话关联校验（M6）：用户存在 AI 会话时禁止删除账号。
 * <p>
 * 通过 aigc 服务的内部接口统计；服务不可用时返回负数，按 fail-closed 保守拒绝删除。
 * 注意：INTERACTION Guard 仍未实现，会话 + 互动两条关联必须同时存在 Guard 才能解除删除 fail-closed。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatSessionAssociationGuard implements UserDeletionGuard {

    private final AigcClient aigcClient;

    @Override
    public String bindingName() {
        return "AI 会话";
    }

    @Override
    public String bindingType() {
        return Constants.ASSOC_CHAT_SESSION;
    }

    @Override
    public long count(Long userId) {
        try {
            R<Map<String, Object>> resp = aigcClient.countSessionsByUser(userId);
            // 内部接口无响应 / 无 data / 业务码非 200 均视为"校验不可用"：返回负数由删除流程 fail-closed 拒绝
            if (resp == null || !Integer.valueOf(200).equals(resp.getCode())) {
                return -1L;
            }
            Map<String, Object> data = resp.getData();
            if (data == null) {
                return -1L;
            }
            Object c = data.get("count");
            if (c instanceof Number n) {
                return n.longValue();
            }
            if (c != null) {
                return Long.parseLong(String.valueOf(c));
            }
            return 0L;
        } catch (Exception e) {
            log.warn("[user] aigc association check unavailable: {}", e.getMessage());
            return -1L;
        }
    }
}
