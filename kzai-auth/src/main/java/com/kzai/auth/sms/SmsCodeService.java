package com.kzai.auth.sms;

import com.kzai.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

/**
 * M15.17：短信验证码服务（Redis 安全机制）。
 * <ul>
 *   <li>6 位 SecureRandom 生成；</li>
 *   <li>验证值=HMAC-SHA256(phone+code, 服务端密钥) 摘要存储——不以明文持久化、不可反推；</li>
 *   <li>绑定手机号+场景（REGISTER），场景间不混用；</li>
 *   <li>有效期/发送间隔/失败次数/日限/IP 限全部 Redis 原子 TTL 判定；</li>
 *   <li>验证消费=Lua 原子（校验+DEL 同脚本）——并发验证不重复消费、无 GET+DELETE 窗口；</li>
 *   <li>新验证码发出即覆盖旧值（旧码失效）；注册成功后立即消费删除。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SmsCodeService {

    private final StringRedisTemplate redis;
    private final SmsProperties props;

    private static final String K_CODE = "SMS:CODE:";      // 摘要存储
    private static final String K_FAIL = "SMS:FAIL:";      // 验证失败计数
    private static final String K_INTERVAL = "SMS:IV:";    // 发送间隔
    private static final String K_DAILY = "SMS:DAY:";      // 单手机号日限
    private static final String K_IP = "SMS:IP:";          // 单 IP 小时限

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String PEPPER = "kzai-sms-code-pepper-v1"; // 服务端摘要盐（与 Redis 分离）

    /** Lua：校验摘要并原子消费（-1 不存在/过期；-2 不匹配；1 消费成功，码与失败计数一并删除） */
    private static final DefaultRedisScript<Long> CONSUME_SCRIPT = new DefaultRedisScript<>(
            "local v = redis.call('GET', KEYS[1]) "
            + "if not v then return -1 end "
            + "if v ~= ARGV[1] then return -2 end "
            + "redis.call('DEL', KEYS[1]); redis.call('DEL', KEYS[2]) "
            + "return 1", Long.class);

    /** 生成验证码（6 位数字，SecureRandom） */
    public String generate() {
        return String.format("%06d", RANDOM.nextInt(1_000_000));
    }

    /** 手机号格式校验（现有业务支持范围：大陆手机号） */
    public void requireValidPhone(String phone) {
        if (phone == null || !phone.matches("^1[3-9]\\d{9}$")) {
            throw new BizException("手机号格式不正确");
        }
    }

    /** 场景枚举校验（REGISTER/CHANGE_PHONE；不同场景验证码不混用——KEYS 按 scene 隔离） */
    public void requireValidScene(String scene) {
        if (!"REGISTER".equals(scene) && !"CHANGE_PHONE".equals(scene)) {
            throw new BizException("不支持的验证码场景");
        }
    }

    /** 发送前限流判定+间隔写入（全部 Redis 原子 TTL 操作；不依赖前端倒计时） */
    public void checkAndMarkSend(String phone, String ip) {
        String day = LocalDate.now().toString();
        String intervalKey = K_INTERVAL + phone;
        String dailyKey = K_DAILY + phone + ":" + day;
        String ipKey = K_IP + ip + ":" + java.time.LocalDateTime.now().getHour();

        Boolean intervalHit = redis.opsForValue().setIfAbsent(intervalKey, "1",
                Duration.ofSeconds(props.getSendIntervalSeconds()));
        if (!Boolean.TRUE.equals(intervalHit)) {
            throw new BizException("发送过于频繁，请 " + props.getSendIntervalSeconds() + " 秒后再试");
        }
        Long daily = redis.opsForValue().increment(dailyKey);
        if (daily != null && daily == 1) {
            redis.expire(dailyKey, Duration.ofHours(25));
        }
        if (daily != null && daily > props.getDailySendLimit()) {
            throw new BizException("今日发送次数已达上限，请明日再试");
        }
        Long ipCount = redis.opsForValue().increment(ipKey);
        if (ipCount != null && ipCount == 1) {
            redis.expire(ipKey, Duration.ofHours(1));
        }
        if (ipCount != null && ipCount > props.getIpHourlyLimit()) {
            throw new BizException("请求过于频繁，请稍后再试");
        }
    }

    /** 存储验证码摘要（绑定 phone+scene；新码覆盖旧码=旧码失效） */
    public void store(String scene, String phone, String code) {
        String key = K_CODE + scene + ":" + phone;
        redis.opsForValue().set(key, digest(phone, code), Duration.ofSeconds(props.getCodeTtlSeconds()));
        redis.delete(K_FAIL + scene + ":" + phone); // 新码发出：失败计数清零
    }

    /**
     * 验证并一次性原子消费（Lua 脚本内校验+删除）。
     * 失败时按错误类型抛 BizException（不泄露码值）。
     */
    public boolean verifyAndConsume(String scene, String phone, String code) {
        requireValidPhone(phone);
        String key = K_CODE + scene + ":" + phone;
        String failKey = K_FAIL + scene + ":" + phone;
        String digest = digest(phone, code);
        Long r = redis.execute(CONSUME_SCRIPT, List.of(key, failKey), digest);
        if (r != null && r == 1L) {
            return true; // 一次性消费成功
        }
        if (r != null && r == -1L) {
            throw new BizException("验证码已过期或未发送，请重新获取");
        }
        // 摘要不匹配：失败计数递增；超限作废验证码
        Long fails = redis.opsForValue().increment(failKey);
        if (fails != null && fails == 1L) {
            redis.expire(failKey, Duration.ofSeconds(props.getCodeTtlSeconds()));
        }
        if (fails != null && fails > props.getMaxVerifyFailures()) {
            redis.delete(key);
            throw new BizException("验证码错误次数过多，请重新获取");
        }
        throw new BizException("验证码错误");
    }

    /** HMAC-SHA256 摘要（服务端 pepper+phone 为密钥；不可反推验证码） */
    private String digest(String phone, String code) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            var secret = new javax.crypto.spec.SecretKeySpec(
                    (PEPPER + phone).getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(secret);
            byte[] out = mac.doFinal(code.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : out) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("验证码摘要计算失败", e);
        }
    }

    /** MOCK 测试夹具：仅 MOCK 模式+夹具密钥匹配时读取最近验证码（隔离环境专用；正式环境禁用） */
    public String mockFixture(String phone, String fixtureKey) {
        if (!"mock".equals(props.getMode()) || props.getMockFixtureKey().isBlank()
                || !props.getMockFixtureKey().equals(fixtureKey)) {
            throw new BizException("测试夹具不可用");
        }
        String code = redis.opsForValue().get("SMS:MOCKFIX:" + phone);
        if (code == null) {
            throw new BizException("测试夹具验证码不存在或已过期");
        }
        return code;
    }

    /** MOCK 发送时写入夹具码（仅 MOCK 模式调用；REAL 模式不调用） */
    public void storeMockFixture(String phone, String code) {
        redis.opsForValue().set("SMS:MOCKFIX:" + phone, code, Duration.ofSeconds(props.getCodeTtlSeconds()));
    }
}
