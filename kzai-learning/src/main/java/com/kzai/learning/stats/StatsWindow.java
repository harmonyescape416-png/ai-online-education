package com.kzai.learning.stats;

import com.kzai.common.exception.BizException;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 学情日期窗口。业务日按 Asia/Shanghai。
 * 区间为 [开始日 00:00:00, 结束日次日 00:00:00)。两端必须同时有或同时没有。
 */
public final class StatsWindow {

    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    public static final DateTimeFormatter DAY = DateTimeFormatter.ISO_LOCAL_DATE;

    public enum Mode { CUMULATIVE, RANGE }

    private final Mode mode;
    private final LocalDate from;
    private final LocalDate to;

    private StatsWindow(Mode mode, LocalDate from, LocalDate to) {
        this.mode = mode;
        this.from = from;
        this.to = to;
    }

    public static StatsWindow parse(String dateFrom, String dateTo) {
        boolean blankFrom = dateFrom == null || dateFrom.isBlank();
        boolean blankTo = dateTo == null || dateTo.isBlank();
        if (blankFrom && blankTo) {
            return new StatsWindow(Mode.CUMULATIVE, null, null);
        }
        if (blankFrom || blankTo) {
            throw new BizException("开始日期和结束日期必须同时填写");
        }
        LocalDate from;
        LocalDate to;
        try {
            from = LocalDate.parse(dateFrom.trim(), DAY);
            to = LocalDate.parse(dateTo.trim(), DAY);
        } catch (DateTimeException ex) {
            throw new BizException("日期格式必须为 YYYY-MM-DD");
        }
        if (from.isAfter(to)) {
            throw new BizException("开始日期不能晚于结束日期");
        }
        return new StatsWindow(Mode.RANGE, from, to);
    }

    public Mode mode() {
        return mode;
    }

    public LocalDate from() {
        return from;
    }

    public LocalDate to() {
        return to;
    }

    /** 含起始日。 */
    public LocalDateTime startInclusive() {
        return from.atStartOfDay();
    }

    /** 结束日的次日 00:00，不含。 */
    public LocalDateTime endExclusive() {
        return to.plusDays(1).atStartOfDay();
    }

    /**
     * 一次上报的增量全部归入「成功持久化时刻」换算到上海时区后的业务日，不按自然日拆分。
     * activityTime 是 JVM 默认时区下的 LocalDateTime。
     */
    public static LocalDate businessDate(LocalDateTime activityTime) {
        return activityTime.atZone(ZoneId.systemDefault()).withZoneSameInstant(ZONE).toLocalDate();
    }
}
