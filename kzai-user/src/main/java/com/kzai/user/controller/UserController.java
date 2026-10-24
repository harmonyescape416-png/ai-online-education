package com.kzai.user.controller;

import com.kzai.common.context.UserContext;
import com.kzai.common.exception.AuthException;
import com.kzai.common.result.R;
import com.kzai.common.security.TokenVersionService;
import com.kzai.user.dto.UserDTO;
import com.kzai.user.service.UserService;
import com.kzai.user.vo.UserVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 用户接口：GET /users/me（系统设计说明书 5.2.3）。
 * /internal/users/** 为服务间调用，网关未配置路由，外部不可达。
 */
@RestController
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final TokenVersionService tokenVersionService;
    private final com.kzai.user.mapper.UserProfileExtMapper extMapper;

    @GetMapping("/users/me")
    public R<UserVO> me() {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            throw new AuthException("未登录或 token 无效");
        }
        // M15.15 方案 A：合并扩展资料（昵称/头像回读）
        com.kzai.user.vo.UserVO vo = userService.getById(userId);
        com.kzai.user.entity.UserProfileExt ext = extMapper.selectById(userId);
        if (ext != null) {
            vo.setNickname(ext.getNickname());
            vo.setAvatarUrl(ext.getAvatarUrl());
        }
        return R.ok(vo);
    }

    @GetMapping("/internal/users/{username}")
    public R<UserDTO> internalFindByUsername(@PathVariable("username") String username) {
        return R.ok(userService.findByUsername(username));
    }

    /**
     * 内部接口：查询用户当前 token 版本号（M2 遗留修复②）。
     * 供 auth 服务签发 token、网关在 Redis 缓存未命中时回源校验。
     */
    @GetMapping("/internal/users/{userId}/token-version")
    public R<Integer> internalTokenVersion(@PathVariable("userId") Long userId) {
        return R.ok(tokenVersionService.current(userId));
    }

    /**
     * 内部接口（M5批量）：按用户ID取账号名，供学习服务学情统计展示学员。
     * 只返回账号名，不返回手机号等敏感字段。
     */
    @GetMapping("/internal/users/names")
    public R<Map<Long, String>> internalNames(@RequestParam("ids") List<Long> ids) {
        return R.ok(userService.namesByIds(ids));
    }
}
