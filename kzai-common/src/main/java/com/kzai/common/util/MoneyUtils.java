package com.kzai.common.util;

import com.kzai.common.exception.BizException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.regex.Pattern;

/**
 * 金额工具：统一「元 → 分」精确换算。
 * <p>
 * 禁止使用 double/float 参与金额运算（如 1.005 * 100 = 100.49999...），
 * 一律走 BigDecimal 字符串构造，超过两位小数或溢出直接拒绝，不做静默舍入。
 */
public final class MoneyUtils {

    /** 只接受非负、最多两位小数的十进制字面量 */
    private static final Pattern YUAN = Pattern.compile("^\\d{1,9}(\\.\\d{1,2})?$");

    private static final int MAX_CENTS = Integer.MAX_VALUE;

    private MoneyUtils() {
    }

    /** 元（字符串）→ 分（int） */
    public static int yuanToCents(String yuan) {
        if (yuan == null) {
            throw new BizException("金额不能为空");
        }
        String trimmed = yuan.trim();
        if (!YUAN.matcher(trimmed).matches()) {
            throw new BizException("金额格式非法：" + yuan + "（需为最多两位小数的非负金额）");
        }
        BigDecimal cents = new BigDecimal(trimmed)
                .setScale(2, RoundingMode.UNNECESSARY)
                .movePointRight(2);
        if (cents.compareTo(BigDecimal.valueOf(MAX_CENTS)) > 0) {
            throw new BizException("金额超出允许范围");
        }
        return cents.intValueExact();
    }

    /** 分 → 元（展示用，保留两位小数） */
    public static BigDecimal centsToYuan(Integer cents) {
        return BigDecimal.valueOf(cents == null ? 0L : cents)
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    }
}
