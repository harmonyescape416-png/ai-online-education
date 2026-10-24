package com.kzai.auth.client;

import com.kzai.auth.client.dto.UserDTO;
import com.kzai.common.feign.InternalAuthFeignConfig;
import com.kzai.common.result.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import java.util.Map;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * 通过 OpenFeign 调用用户服务获取账号（系统设计说明书 3.3 模块间调用关系）。
 * /internal/** 不会被网关路由，仅服务间可见。
 */
@FeignClient(name = "kzai-user", path = "/internal/users",
        configuration = InternalAuthFeignConfig.class)
public interface UserClient {

    @GetMapping("/{username}")
    R<UserDTO> findByUsername(@PathVariable("username") String username);

    /** M15.17：按手机号查询（手机号+密码登录） */
    @GetMapping("/phone/{phone}")
    R<UserDTO> findByPhone(@PathVariable("phone") String phone);

    /** M15.17：注册创建账号（auth 已完成短信验证与 BCrypt 加密） */
    @PostMapping("/create-for-register")
    R<Map<String, Object>> createForRegister(@RequestBody Map<String, Object> body);

    /** M15.17：修改手机号（验证码已在 auth 校验；占用由 internal 兜底） */
    @PostMapping("/update-phone")
    R<Map<String, Object>> updatePhone(@RequestBody Map<String, Object> body);

    /** 内部接口：用户当前 token 版本号（M2 遗留修复②：持久化撤销） */
    @GetMapping("/{userId}/token-version")
    R<Integer> tokenVersion(@PathVariable("userId") Long userId);
}
