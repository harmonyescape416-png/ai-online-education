package com.kzai.user.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kzai.common.constant.Constants;
import com.kzai.common.context.UserContext;
import com.kzai.common.exception.BizException;
import com.kzai.common.security.TokenVersionService;
import com.kzai.user.dto.ChangePasswordRequest;
import com.kzai.user.dto.ProfileUpdateRequest;
import com.kzai.user.entity.User;
import com.kzai.user.entity.UserProfileExt;
import com.kzai.user.mapper.UserProfileExtMapper;
import com.kzai.user.mapper.UserMapper;
import com.kzai.user.vo.UserVO;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 个人中心（需求 3.2.5.4）：资料查看与修改、修改密码。
 * <p>
 * 身份一律取 UserContext（网关注入），不接受前端传入的 userId。
 */
@Service
@RequiredArgsConstructor
public class ProfileService {

    private final UserMapper userMapper;
    private final com.kzai.user.mapper.UserProfileExtMapper extMapper;
    private final UserRoleService userRoleService;
    private final OperationLogService operationLogService;
    private final TokenVersionService tokenVersionService;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    /** 修改个人资料：原始 user 表仅手机号可编辑 */
    public UserVO updateProfile(ProfileUpdateRequest request) {
        Long userId = UserContext.getUserId();
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException("用户不存在");
        }
        if (request.getCellPhone() != null) {
            if (StringUtils.hasText(request.getCellPhone())) {
                User exist = userMapper.selectOne(Wrappers.<User>lambdaQuery()
                        .eq(User::getCellPhone, request.getCellPhone()).last("LIMIT 1"));
                if (exist != null && !exist.getId().equals(userId)) {
                    throw new BizException("手机号已被占用");
                }
            }
            user.setCellPhone(request.getCellPhone());
        }
        user.setUpdater(userId);
        userMapper.updateById(user);
        // M15.15 方案 A：扩展资料（昵称/头像）保存到 user_profile_ext（1:1 共键，不改核心表）
        // 昵称 7 天一次修改限制（服务端权威校验；仅当本次提交了昵称且与现有值不同时计为一次修改）
        UserProfileExt curExt = extMapper.selectById(userId);
        if (request.getNickname() != null && curExt != null
                && request.getNickname().equals(curExt.getNickname())) {
            // 昵称未变化：直接按未修改处理（不触发限制，也不刷新时间戳）
            saveExt(userId, null, request.getAvatarUrl());
        } else {
            if (curExt != null && curExt.getNicknameUpdatedAt() != null) {
                long days = java.time.Duration.between(
                        curExt.getNicknameUpdatedAt(), java.time.LocalDateTime.now()).toDays();
                if (days < 7) {
                    long remain = 7 - days;
                    throw new BizException("昵称 7 天内仅可修改一次，还需 " + remain + " 天后才能再次修改");
                }
            }
            saveExt(userId, request.getNickname(), request.getAvatarUrl());
            // 修改成功：记录时间戳（首次设置同样起算）
            markNicknameUpdated(userId);
        }
        operationLogService.recordUser(Constants.OP_UPDATE, userId, "修改个人资料");
        UserVO vo = UserService.toVO(user, userRoleService.resolve(userId, user.getType()));
        UserProfileExt ext = extMapper.selectById(userId);
        if (ext != null) {
            vo.setNickname(ext.getNickname());
            vo.setAvatarUrl(ext.getAvatarUrl());
        }
        return vo;
    }
    /** M15.15：记录昵称修改时间戳（upsert） */
    private void markNicknameUpdated(Long userId) {
        com.kzai.user.entity.UserProfileExt ext = extMapper.selectById(userId);
        if (ext == null) {
            ext = new com.kzai.user.entity.UserProfileExt();
            ext.setUserId(userId);
            ext.setNicknameUpdatedAt(java.time.LocalDateTime.now());
            extMapper.insert(ext);
        } else {
            ext.setNicknameUpdatedAt(java.time.LocalDateTime.now());
            extMapper.updateById(ext);
        }
    }

    /** M15.15 方案 A：扩展资料保存（upsert 1:1 共键；null 字段不覆盖） */
    /** M15.17：修改手机号（占用检查+更新；各角色登录态一致） */
    public void updatePhone(Long userId, String newPhone) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException("用户不存在");
        }
        if (newPhone.equals(user.getCellPhone())) {
            return; // 与当前一致：幂等
        }
        User exist = userMapper.selectOne(Wrappers.<User>lambdaQuery()
                .eq(User::getCellPhone, newPhone).last("LIMIT 1"));
        if (exist != null) {
            throw new BizException("该号码已注册");
        }
        user.setCellPhone(newPhone);
        user.setUpdater(userId);
        try {
            userMapper.updateById(user);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new BizException("该号码已注册");
        }
        operationLogService.recordUser(Constants.OP_UPDATE, userId, "修改手机号（短信验证通过）");
    }

    private void saveExt(Long userId, String nickname, String avatarUrl) {
        com.kzai.user.entity.UserProfileExt ext = extMapper.selectById(userId);
        if (ext == null) {
            if (nickname == null && avatarUrl == null) return;
            ext = new com.kzai.user.entity.UserProfileExt();
            ext.setUserId(userId);
            ext.setNickname(nickname);
            ext.setAvatarUrl(avatarUrl);
            extMapper.insert(ext);
        } else {
            if (nickname != null) ext.setNickname(nickname);
            if (avatarUrl != null) ext.setAvatarUrl(avatarUrl);
            extMapper.updateById(ext);
        }
    }

    /** 修改密码：校验旧密码 → BCrypt 加密 → 旧 token 全部失效 */
    public void changePassword(ChangePasswordRequest request) {
        Long userId = UserContext.getUserId();
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException("用户不存在");
        }
        if (!passwordEncoder.matches(request.getOldPassword(), user.getPassword())) {
            throw new BizException("原密码不正确");
        }
        if (request.getOldPassword().equals(request.getNewPassword())) {
            throw new BizException("新密码不能与原密码相同");
        }
        user.setPassword(passwordEncoder.encode(request.getNewPassword()));
        user.setUpdater(userId);
        userMapper.updateById(user);

        // 需求：密码修改成功后原有 token 失效
        tokenVersionService.bump(userId);
        operationLogService.recordUser(Constants.OP_RESET_PWD, userId, "自助修改密码");
    }
}
