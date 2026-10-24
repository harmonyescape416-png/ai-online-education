package com.kzai.auth.controller;

import com.kzai.auth.dto.LoginRequest;
import com.kzai.auth.dto.LoginVO;
import com.kzai.auth.service.AuthService;
import com.kzai.common.result.R;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口（系统设计说明书 5.2.3）：POST /auth/login、POST /auth/logout。
 */
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private static final String BEARER = "Bearer ";

    private final AuthService authService;
    private final com.kzai.auth.service.RegisterService registerService;

    @PostMapping("/login")
    public R<LoginVO> login(@Valid @RequestBody LoginRequest request) {
        return R.ok(authService.login(request));
    }

    /**
     * M15.17：发送注册短信验证码。
     * 请求：{"phone":"13800138000","scene":"REGISTER"}；响应不返回验证码明文。
     * REAL 未配置时 503 安全拒绝；限流/有效期/失败次数由 SmsCodeService 服务端权威控制。
     */
    @PostMapping("/sms/send")
    public R<Map<String, Object>> sendSms(@RequestBody Map<String, Object> body,
                                          jakarta.servlet.http.HttpServletRequest request) {
        String phone = String.valueOf(body.getOrDefault("phone", ""));
        String scene = String.valueOf(body.getOrDefault("scene", ""));
        String ip = clientIp(request);
        // M15.17：CHANGE_PHONE 为登录态场景（换绑自己账号）
        Long loginUserId = "CHANGE_PHONE".equals(scene)
                ? authService.requireLoginUserId(request.getHeader(org.springframework.http.HttpHeaders.AUTHORIZATION))
                : null;
        Map<String, Object> sendResult = registerService.sendRegisterCode(phone, scene, ip, loginUserId);
        return R.ok(sendResult);
    }

    /**
     * M15.17：手机号短信验证码注册（仅学员）。
     * 请求：{"phone","smsCode","password","confirmPassword"}
     * 响应：{"username","phone","message"}——引导登录，不发放 JWT。
     */
    @PostMapping("/register")
    public R<Map<String, Object>> register(@RequestBody Map<String, Object> body,
                                           jakarta.servlet.http.HttpServletRequest request) {
        clientIp(request); // 保持 IP 提取路径一致（注册频率风控后续扩展点）
        String phone = String.valueOf(body.getOrDefault("phone", ""));
        String smsCode = String.valueOf(body.getOrDefault("smsCode", ""));
        String password = String.valueOf(body.getOrDefault("password", ""));
        String confirmPassword = String.valueOf(body.getOrDefault("confirmPassword", ""));
        return R.ok(registerService.register(phone, smsCode, password, confirmPassword));
    }

    /** M15.17：修改手机号（登录态；短信验证码确认新号归属；占用拒绝「该号码已注册」） */
    @PostMapping("/change-phone")
    public R<Map<String, Object>> changePhone(@RequestBody Map<String, Object> body,
                                              jakarta.servlet.http.HttpServletRequest request) {
        Long userId = authService.requireLoginUserId(
                request.getHeader(org.springframework.http.HttpHeaders.AUTHORIZATION));
        String newPhone = String.valueOf(body.getOrDefault("newPhone", ""));
        String smsCode = String.valueOf(body.getOrDefault("smsCode", ""));
        return R.ok(registerService.changePhone(userId, newPhone, smsCode));
    }

    private String clientIp(jakarta.servlet.http.HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        return (xff == null || xff.isBlank()) ? request.getRemoteAddr() : xff.split(",")[0].trim();
    }

    @PostMapping("/logout")
    public R<Void> logout(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        String token = header != null && header.startsWith(BEARER)
                ? header.substring(BEARER.length())
                : null;
        authService.logout(token);
        return R.ok();
    }
}
