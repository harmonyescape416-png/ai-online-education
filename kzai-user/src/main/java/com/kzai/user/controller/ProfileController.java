package com.kzai.user.controller;

import com.kzai.common.exception.AuthException;
import com.kzai.common.result.R;
import com.kzai.user.dto.ChangePasswordRequest;
import com.kzai.user.dto.ProfileUpdateRequest;
import com.kzai.user.service.ProfileService;
import com.kzai.user.vo.UserVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.kzai.common.context.UserContext;

/**
 * 个人中心接口（需求 3.2.5.4）。
 * 身份取自 UserContext（网关注入），不接受前端传入 userId，杜绝越权修改他人资料。
 */
@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
public class ProfileController {

    private final ProfileService profileService;

    @PutMapping("/me")
    public R<UserVO> updateProfile(@Valid @RequestBody ProfileUpdateRequest request) {
        requireLogin();
        return R.ok(profileService.updateProfile(request));
    }

    @PutMapping("/me/password")
    public R<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        requireLogin();
        profileService.changePassword(request);
        return R.ok();
    }

    private void requireLogin() {
        if (UserContext.getUserId() == null) {
            throw new AuthException("未登录或 token 无效");
        }
    }
}
