package com.kzai.auth.service;

import com.kzai.auth.client.UserClient;
import com.kzai.auth.client.dto.UserDTO;
import com.kzai.auth.sms.SmsCodeService;
import com.kzai.auth.sms.SmsSender;
import com.kzai.common.exception.BizException;
import com.kzai.common.result.R;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * M15.17：手机号短信验证码注册（auth 侧编排）。
 * 流程：短信验证码一次性消费（Lua 原子）→ BCrypt 加密 → user 服务内部创建（creater=0/type=2/username 生成）
 * → 返回登录引导。并发一致性：先消费验证码再创建，重复注册由唯一索引+前置校验兜底，统一友好提示防账号枚举。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RegisterService {

    private final SmsCodeService smsCodeService;
    private final SmsSender smsSender;
    private final UserClient userClient;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    /**
     * 发送注册验证码。返回：sent/message/ttlSeconds；MOCK 模式额外返回 devCode（开发/测试环境
     * 获取验证码的受控通道——每次随机非固定码，REAL 模式绝不返回）。
     */
    public Map<String, Object> sendRegisterCode(String phone, String scene, String ip, Long loginUserId) {
        smsCodeService.requireValidPhone(phone);
        smsCodeService.requireValidScene(scene);
        smsSender.requireSendable();
        if ("CHANGE_PHONE".equals(scene)) {
            // M15.17：换绑手机号——新号已绑定任意用户（含教师/管理员）则拒绝，不发送
            R<UserDTO> existed = userClient.findByPhone(phone);
            if (existed != null && existed.getData() != null) {
                throw new BizException("该号码已注册");
            }
        } else {
            // REGISTER：已注册手机号引导直接登录
            R<UserDTO> existed = userClient.findByPhone(phone);
            if (existed != null && existed.getData() != null) {
                throw new BizException("该手机号已注册，请直接登录");
            }
        }
        // 已注册手机号：引导直接登录（注册场景明确语义；限流阈值独立生效，枚举面受限）
        R<UserDTO> existed = userClient.findByPhone(phone);
        if (existed != null && existed.getData() != null) {
            throw new BizException("该手机号已注册，请直接登录");
        }
        smsCodeService.checkAndMarkSend(phone, ip);
        String code = smsCodeService.generate();
        boolean sent = smsSender.send(phone, code);
        if (!sent) {
            // REAL 服务商失败：不得伪装成功（限流间隔已占用——提示稍后再试）
            throw new BizException("短信发送失败，请稍后再试");
        }
        smsCodeService.store(scene, phone, code);
        if (smsSender.isMock()) {
            // MOCK 测试夹具（隔离环境专用；REAL 模式不写夹具键）
            smsCodeService.storeMockFixture(phone, code);
        }
        log.info("[sms] REGISTER 验证码已发送至脱敏手机号 {}***", phone.substring(0, 3));
        // MOCK 模式：响应携带开发验证码（每次随机、仅 mock 环境；REAL 模式该字段为 null）
        Map<String, Object> result = new java.util.HashMap<>();
        result.put("sent", true);
        result.put("message", "验证码已发送，请在有效期内输入");
        result.put("ttlSeconds", 300);
        if (smsSender.isMock()) {
            result.put("devCode", code);
        }
        return result;
    }

    /** 注册：验证码消费 → 创建账号 → 返回登录引导信息 */
    public Map<String, Object> register(String phone, String smsCode, String password, String confirmPassword) {
        smsCodeService.requireValidPhone(phone);
        if (password == null || password.isBlank() || password.length() < 6 || password.length() > 32) {
            throw new BizException("密码长度需为 6-32 位");
        }
        if (!password.equals(confirmPassword)) {
            throw new BizException("两次输入的密码不一致");
        }
        // 验证码一次性原子消费（并发注册同一码只有一个成功）
        smsCodeService.verifyAndConsume("REGISTER", phone, smsCode);
        // 创建账号（user 服务内部：username 生成/type=2/creater=0；唯一冲突统一友好提示）
        R<Map<String, Object>> created = userClient.createForRegister(Map.of(
                "phone", phone,
                "passwordHash", passwordEncoder.encode(password)));
        if (created == null || !Integer.valueOf(200).equals(created.getCode()) || created.getData() == null) {
            throw new BizException("注册失败，请稍后重试");
        }
        String username = String.valueOf(created.getData().get("username"));
        log.info("[register] 注册成功 username={}（手机号脱敏 {}****）", username, phone.substring(0, 3));
        return Map.of("username", username, "phone", phone,
                "message", "注册成功，请使用手机号和密码登录");
    }


    /** M15.17：修改手机号（验证码校验+占用检查→user 服务更新；各角色登录态一致） */
    public Map<String, Object> changePhone(Long userId, String newPhone, String smsCode) {
        smsCodeService.requireValidPhone(newPhone);
        // 新号占用检查（任意角色已绑定即拒绝）
        R<UserDTO> existed = userClient.findByPhone(newPhone);
        if (existed != null && existed.getData() != null) {
            throw new BizException("该号码已注册");
        }
        // 验证码一次性消费（scene=CHANGE_PHONE 绑定新手机号）
        smsCodeService.verifyAndConsume("CHANGE_PHONE", newPhone, smsCode);
        // user 服务更新（DuplicateKey 兜底并发）
        R<Map<String, Object>> updated = userClient.updatePhone(Map.of(
                "userId", String.valueOf(userId), "newPhone", newPhone));
        if (updated == null || !Integer.valueOf(200).equals(updated.getCode())) {
            throw new BizException((updated != null && updated.getMsg() != null) ? updated.getMsg() : "手机号修改失败");
        }
        log.info("[change-phone] userId={} 手机号已更新（脱敏 {}****）", userId, newPhone.substring(0, 3));
        return Map.of("newPhone", newPhone, "message", "手机号已更新");
    }
}
