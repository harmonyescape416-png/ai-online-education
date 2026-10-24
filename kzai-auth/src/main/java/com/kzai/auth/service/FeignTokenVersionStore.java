package com.kzai.auth.service;

import com.kzai.auth.client.UserClient;
import com.kzai.common.result.R;
import com.kzai.common.security.TokenVersionStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * auth 服务不持有用户数据，token 版本号通过内部接口向用户服务查询（不跨服务操作对方 Mapper）。
 */
@Component
@RequiredArgsConstructor
public class FeignTokenVersionStore implements TokenVersionStore {

    private final UserClient userClient;

    @Override
    public int current(Long userId) {
        if (userId == null) {
            return 0;
        }
        R<Integer> result = userClient.tokenVersion(userId);
        Integer version = result == null ? null : result.getData();
        if (version == null) {
            throw new IllegalStateException("token 版本号查询失败，无法签发 token");
        }
        return version;
    }

    @Override
    public int bump(Long userId) {
        throw new UnsupportedOperationException("token 版本号只能由用户服务变更");
    }
}
