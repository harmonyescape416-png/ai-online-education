package com.kzai.user.service;

import com.kzai.common.security.TokenVersionStore;
import com.kzai.user.entity.SysTokenVersion;
import com.kzai.user.mapper.SysTokenVersionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * token 版本号的权威存储（MySQL）。用户服务是用户数据的属主，版本号随账号变更自增。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SysTokenVersionStore implements TokenVersionStore {

    private final SysTokenVersionMapper tokenVersionMapper;

    @Override
    public int current(Long userId) {
        if (userId == null) {
            return 0;
        }
        SysTokenVersion entity = tokenVersionMapper.selectById(userId);
        return entity == null || entity.getVersion() == null ? 0 : entity.getVersion();
    }

    @Override
    @Transactional
    public int bump(Long userId) {
        if (userId == null) {
            return 0;
        }
        SysTokenVersion entity = tokenVersionMapper.selectById(userId);
        if (entity == null) {
            SysTokenVersion created = new SysTokenVersion();
            created.setUserId(userId);
            created.setVersion(1);
            created.setUpdateTime(LocalDateTime.now());
            tokenVersionMapper.insert(created);
            return 1;
        }
        int next = (entity.getVersion() == null ? 0 : entity.getVersion()) + 1;
        entity.setVersion(next);
        entity.setUpdateTime(LocalDateTime.now());
        tokenVersionMapper.updateById(entity);
        return next;
    }
}
