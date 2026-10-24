package com.kzai.user.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kzai.common.exception.BizException;
import com.kzai.user.entity.User;
import com.kzai.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;

/**
 * M15.17：自助注册账号创建（user 服务内部）。
 * 来源标识约定（62 号方案批准）：creater=0 表示用户自主注册（历史数据无 creater=0，无兼容冲突）。
 * username 由服务端生成（不把手机号当作用户名公开展示；随机标识+唯一重试；uk_username 兜底）。
 * type 固定 2（STUDENT）——注册请求不可指定角色/用户ID/创建人。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RegisterService {

    private final UserMapper userMapper;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String USERNAME_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";

    /** 注册结果（userId 与服务端生成的 username） */
    public record RegisterResult(Long userId, String username) {
    }

    /**
     * 手机号注册创建账号（登录凭证=手机号+密码；username 仅作系统标识）。
     * 手机号唯一前置校验（uk_cell_phone 索引兜底并发）；唯一冲突统一友好提示（防账号枚举）。
     */
    @Transactional
    public RegisterResult createByRegister(String phone, String passwordHash) {
        User exist = userMapper.selectOne(Wrappers.<User>lambdaQuery()
                .eq(User::getCellPhone, phone).last("LIMIT 1"));
        if (exist != null) {
            throw new BizException("该手机号已被注册，请直接登录");
        }
        String username = generateUniqueUsername();
        LocalDateTime now = LocalDateTime.now();
        User user = new User();
        user.setUsername(username);
        user.setCellPhone(phone);
        user.setPassword(passwordHash);
        user.setStatus(1);
        user.setType(2); // 固定 STUDENT
        user.setCreateTime(now);
        user.setUpdateTime(now);
        user.setCreater(0L); // M15.17 约定：0=自助注册
        user.setUpdater(0L);
        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException e) {
            throw new BizException("该手机号已被注册，请直接登录");
        }
        log.info("[register] 新学员注册 userId={} username={}（手机号脱敏：{}）",
                user.getId(), username, mask(phone));
        return new RegisterResult(user.getId(), username);
    }

    /** 生成唯一 username（随机 12-16 位小写字母数字；冲突重试≤5 次） */
    private String generateUniqueUsername() {
        for (int attempt = 0; attempt < 5; attempt++) {
            int len = 12 + attempt;
            StringBuilder sb = new StringBuilder("u_");
            for (int i = 0; i < len; i++) {
                sb.append(USERNAME_ALPHABET.charAt(RANDOM.nextInt(USERNAME_ALPHABET.length())));
            }
            String candidate = sb.toString();
            Long count = userMapper.selectCount(Wrappers.<User>lambdaQuery()
                    .eq(User::getUsername, candidate));
            if (count == null || count == 0) {
                return candidate;
            }
        }
        throw new BizException("系统繁忙，请稍后重试");
    }

    private String mask(String phone) {
        return phone.length() >= 7 ? phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4) : phone;
    }
}
