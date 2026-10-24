package com.kzai.common.util;

import com.kzai.common.exception.BizException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 金额换算：精确十进制，禁止浮点误差与静默舍入。 */
class MoneyUtilsTest {

    @Test
    void yuanToCents_exact() {
        assertEquals(1999, MoneyUtils.yuanToCents("19.99"));
        assertEquals(100, MoneyUtils.yuanToCents("1"));
        assertEquals(150, MoneyUtils.yuanToCents("1.5"));
        assertEquals(1230, MoneyUtils.yuanToCents(" 12.30 "));
        assertEquals(0, MoneyUtils.yuanToCents("0"));
        assertEquals(1, MoneyUtils.yuanToCents("0.01"));
    }

    @Test
    void yuanToCents_rejectsPreciseLoss() {
        // 浮点写法 1.005 * 100 = 100.49999... → 此处必须明确拒绝而非静默取整
        assertThrows(BizException.class, () -> MoneyUtils.yuanToCents("1.005"));
        assertThrows(BizException.class, () -> MoneyUtils.yuanToCents("19.999"));
    }

    @Test
    void yuanToCents_rejectsInvalidInput() {
        assertThrows(BizException.class, () -> MoneyUtils.yuanToCents(null));
        assertThrows(BizException.class, () -> MoneyUtils.yuanToCents(""));
        assertThrows(BizException.class, () -> MoneyUtils.yuanToCents("abc"));
        assertThrows(BizException.class, () -> MoneyUtils.yuanToCents("-1.00"));
        assertThrows(BizException.class, () -> MoneyUtils.yuanToCents("12."));
        assertThrows(BizException.class, () -> MoneyUtils.yuanToCents("999999999.00"));
    }

    @Test
    void centsToYuan_display() {
        assertEquals(new BigDecimal("19.99"), MoneyUtils.centsToYuan(1999));
        assertEquals(new BigDecimal("0.00"), MoneyUtils.centsToYuan(0));
        assertEquals(new BigDecimal("0.00"), MoneyUtils.centsToYuan(null));
    }
}
