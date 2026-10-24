package com.kzai.user.controller;

import com.kzai.common.result.R;
import com.kzai.user.service.OperationLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * user 内部接口（M10）：供 learning 服务复用统一操作审计（sys_operation_log）。
 * 必须携带网关/Feign 注入的 X-Internal-Sign，否则被 UserContextWebConfig 拒绝。
 */
@RestController
// M11 验收修复：常量 INTERNAL_PATH_PREFIX 自带尾斜杠，拼接 "/users" 会产生
// "/internal//users"（运行期 404/405，审计长期静默失败）。统一使用字面路径。
@RequestMapping("/internal/users")
@RequiredArgsConstructor
public class InternalUserController {

    private final OperationLogService operationLogService;
    private final com.kzai.user.service.RegisterService registerService;
    private final com.kzai.user.service.UserService userService;
    private final com.kzai.user.service.ProfileService profileService;

    /** 跨服务审计写入（learning 的教师修正/AI 回复事件复用统一审计时间线） */
    /**
     * M15.17：注册创建账号（auth 服务经短信验证后调用）。
     * 请求：{"phone": "13800138000", "passwordHash": "<BCrypt>"}
     * 响应：{"userId": "...", "username": "u_xxx"}——username 服务端生成（不公开手机号）。
     */
    @PostMapping("/create-for-register")
    public R<Map<String, Object>> createForRegister(@RequestBody Map<String, Object> body) {
        String phone = String.valueOf(body.get("phone"));
        String passwordHash = String.valueOf(body.get("passwordHash"));
        if (!phone.matches("^1[3-9]\\d{9}$") || passwordHash == null || passwordHash.isBlank()) {
            throw new com.kzai.common.exception.BizException("注册参数不合法");
        }
        var result = registerService.createByRegister(phone, passwordHash);
        return R.ok(Map.of("userId", String.valueOf(result.userId()),
                "username", result.username()));
    }

    /** M15.17：按手机号查询用户（手机号登录支持；不含密码逻辑） */
    @GetMapping("/phone/{phone}")
    public R<com.kzai.user.dto.UserDTO> findByPhone(@PathVariable("phone") String phone) {
        return R.ok(userService.findDtoByPhone(phone));
    }

    /** M15.17：修改手机号（auth 已验证短信码；占用并发由唯一索引兜底） */
    @PostMapping("/update-phone")
    public R<Map<String, Object>> updatePhone(@RequestBody Map<String, Object> body) {
        Long userId = Long.valueOf(String.valueOf(body.get("userId")));
        String newPhone = String.valueOf(body.get("newPhone"));
        if (!newPhone.matches("^1[3-9]\\d{9}$")) {
            throw new com.kzai.common.exception.BizException("手机号格式不正确");
        }
        profileService.updatePhone(userId, newPhone);
        return R.ok(Map.of("userId", String.valueOf(userId), "newPhone", newPhone));
    }

    @PostMapping("/operation-logs")
    public R<Boolean> writeOperationLog(@RequestBody Map<String, Object> body) {
        operationLogService.recordRaw(
                Long.valueOf(String.valueOf(body.get("operatorId"))),
                String.valueOf(body.getOrDefault("operatorName", "")),
                String.valueOf(body.get("module")),
                String.valueOf(body.get("operation")),
                String.valueOf(body.get("targetType")),
                body.get("targetId") == null ? null : Long.valueOf(String.valueOf(body.get("targetId"))),
                String.valueOf(body.getOrDefault("detail", "")));
        return R.ok(Boolean.TRUE);
    }
}
