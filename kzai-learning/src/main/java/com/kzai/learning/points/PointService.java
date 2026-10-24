package com.kzai.learning.points;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kzai.common.exception.BizException;
import com.kzai.learning.client.UserClient;
import com.kzai.learning.util.OperationAuditLogger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 签到、积分流水和月榜。业务日为 Asia/Shanghai。
 * 连续签到中断后从 1 重新计算。每满 7 天额外奖励一次。没有补签。
 * 同分按最后得分时间早的在前，再按用户 ID 小的在前。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PointService {

    static final Set<String> SOURCES = Set.of("CHECKIN", "STREAK", "LEARN", "QUESTION", "NOTE_LIKE", "NOTE_COMMENT");

    private final PointRuleMapper ruleMapper;
    private final PointAccountMapper accountMapper;
    private final PointLedgerMapper ledgerMapper;
    private final PointPendingAwardMapper pendingMapper;
    private final CheckinMapper checkinMapper;
    private final RankPeriodMapper periodMapper;
    private final RankEntryMapper entryMapper;
    private final PointClock clock;
    private final UserClient userClient;
    private final OperationAuditLogger auditLogger;
    private final org.springframework.beans.factory.ObjectProvider<StringRedisTemplate> redisProvider;
    private final org.springframework.transaction.support.TransactionTemplate transactionTemplate;

    @Transactional
    public Map<String, Object> checkin(long userId) {
        ensureRules();
        LocalDate today = clock.today();
        CheckinRecord existing = checkinMapper.selectOne(Wrappers.<CheckinRecord>lambdaQuery()
                .eq(CheckinRecord::getUserId, userId)
                .eq(CheckinRecord::getBizDate, today));
        if (existing != null) {
            return checkinView(existing, 0, true);
        }
        CheckinRecord yesterday = checkinMapper.selectOne(Wrappers.<CheckinRecord>lambdaQuery()
                .eq(CheckinRecord::getUserId, userId)
                .eq(CheckinRecord::getBizDate, today.minusDays(1)));
        int streak = yesterday == null ? 1 : yesterday.getStreak() + 1;
        CheckinRecord row = new CheckinRecord();
        row.setUserId(userId);
        row.setBizDate(today);
        row.setStreak(streak);
        row.setCreateTime(clock.now());
        try {
            checkinMapper.insert(row);
        } catch (DuplicateKeyException ex) {
            CheckinRecord again = checkinMapper.selectOne(Wrappers.<CheckinRecord>lambdaQuery()
                    .eq(CheckinRecord::getUserId, userId)
                    .eq(CheckinRecord::getBizDate, today));
            return checkinView(again, 0, true);
        }
        int gained = awardLocked(userId, "CHECKIN", "CHECKIN:" + userId + ":" + today, today, null, null);
        if (streak % 7 == 0) {
            gained += awardLocked(userId, "STREAK", "STREAK:" + userId + ":" + today, today, null, null);
        }
        return checkinView(row, gained, false);
    }

    public Map<String, Object> today(long userId) {
        LocalDate today = clock.today();
        CheckinRecord row = checkinMapper.selectOne(Wrappers.<CheckinRecord>lambdaQuery()
                .eq(CheckinRecord::getUserId, userId)
                .eq(CheckinRecord::getBizDate, today));
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("date", today.toString());
        view.put("signed", row != null);
        view.put("streak", row == null ? currentStreak(userId, today) : row.getStreak());
        view.put("todayPoints", sumDay(userId, today));
        view.put("totalPoints", total(userId));
        return view;
    }

    public Map<String, Object> month(long userId, String month) {
        LocalDate start = month == null || month.isBlank()
                ? clock.today().withDayOfMonth(1)
                : LocalDate.parse(month + "-01");
        LocalDate end = start.plusMonths(1);
        List<String> days = checkinMapper.selectList(Wrappers.<CheckinRecord>lambdaQuery()
                        .eq(CheckinRecord::getUserId, userId)
                        .ge(CheckinRecord::getBizDate, start)
                        .lt(CheckinRecord::getBizDate, end)
                        .orderByAsc(CheckinRecord::getBizDate))
                .stream().map(row -> row.getBizDate().toString()).toList();
        return Map.of("month", start.toString().substring(0, 7), "days", days);
    }

    @Transactional
    public int award(long userId, String source, String eventKey, LocalDate bizDate) {
        try {
            return awardLocked(userId, source, eventKey, bizDate, null, null);
        } catch (RuntimeException ex) {
            // 修复：发分失败不再静默丢弃。异常大概率源于发分瞬间（如服务重启窗口）的瞬时故障，
            // 章节完成主事务照常提交，若不落补偿记录积分将永久丢失且无从追踪。
            log.warn("[points] 发分失败，转入补偿队列 event={} user={} {}",
                    eventKey, userId, ex.toString(), ex);
            quarantine(userId, source, eventKey, bizDate, ex);
            return 0;
        }
    }

    /** 不吞异常的发分入口，供补偿重试使用：返回实发积分，异常直接抛出由调用方计数。 */
    public int awardStrict(long userId, String source, String eventKey, LocalDate bizDate) {
        return awardLocked(userId, source, eventKey, bizDate, null, null);
    }

    /** 用独立事务（REQUIRES_NEW）把失败事件写入补偿表，保证外层事务回滚也留痕。 */
    private void quarantine(long userId, String source, String eventKey, LocalDate bizDate, RuntimeException ex) {
        try {
            transactionTemplate.executeWithoutResult(status -> {
                PointPendingAward pending = new PointPendingAward();
                pending.setUserId(userId);
                pending.setSource(source);
                pending.setEventKey(eventKey);
                pending.setBizDate(bizDate);
                pending.setStatus("PENDING");
                pending.setRetryCount(0);
                pending.setLastError(trimError(ex));
                pending.setCreateTime(clock.now());
                pending.setUpdateTime(clock.now());
                try {
                    pendingMapper.insert(pending);
                } catch (DuplicateKeyException dup) {
                    // 同一事件已在补偿队列，等待重试即可
                }
            });
        } catch (RuntimeException quarantineEx) {
            // 补偿写入也失败（如库不可用），只保留错误日志，避免影响主流程
            log.error("[points] 补偿队列写入失败 event={} user={} {}",
                    eventKey, userId, quarantineEx.toString());
        }
    }

    /** 供定时任务取待重试事件。 */
    public List<PointPendingAward> loadPending(int limit) {
        return pendingMapper.selectList(Wrappers.<PointPendingAward>lambdaQuery()
                .eq(PointPendingAward::getStatus, "PENDING")
                .orderByAsc(PointPendingAward::getId)
                .last("LIMIT " + Math.max(1, Math.min(limit, 200))));
    }

    public void markPendingDone(long id, String note) {
        pendingMapper.update(null, Wrappers.<PointPendingAward>lambdaUpdate()
                .set(PointPendingAward::getStatus, "DONE")
                .set(PointPendingAward::getLastError, trimText(note))
                .set(PointPendingAward::getUpdateTime, clock.now())
                .eq(PointPendingAward::getId, id));
    }

    public void markPendingFailed(long id, int retryCount, String error) {
        boolean fatal = retryCount >= 5;
        pendingMapper.update(null, Wrappers.<PointPendingAward>lambdaUpdate()
                .set(PointPendingAward::getStatus, fatal ? "FAILED" : "PENDING")
                .set(PointPendingAward::getRetryCount, retryCount)
                .set(PointPendingAward::getLastError, trimError(new RuntimeException(error)))
                .set(PointPendingAward::getUpdateTime, clock.now())
                .eq(PointPendingAward::getId, id));
        if (fatal) {
            log.error("[points] 补偿重试超过上限，转人工处理 pendingId={}", id);
        }
    }

    private String trimError(RuntimeException ex) {
        String text = ex.toString();
        return trimText(text);
    }

    private String trimText(String text) {
        if (text == null) {
            return null;
        }
        return text.length() > 190 ? text.substring(0, 190) : text;
    }

    public Map<String, Object> summary(long userId) {
        LocalDate today = clock.today();
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("date", today.toString());
        view.put("todayPoints", sumDay(userId, today));
        view.put("totalPoints", total(userId));
        view.put("period", clock.period(today));
        view.put("periodPoints", periodPoints(userId, clock.period(today)));
        return view;
    }

    public Map<String, Object> ledger(long userId, long page, long size) {
        Page<PointLedger> result = ledgerMapper.selectPage(new Page<>(Math.max(page, 1), Math.min(Math.max(size, 1), 50)),
                Wrappers.<PointLedger>lambdaQuery()
                        .eq(PointLedger::getUserId, userId)
                        .orderByDesc(PointLedger::getId));
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("total", result.getTotal());
        view.put("records", result.getRecords().stream().map(this::ledgerView).toList());
        return view;
    }

    public List<Map<String, Object>> rules() {
        ensureRules();
        return ruleMapper.selectList(Wrappers.<PointRule>lambdaQuery()).stream().map(rule -> {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("source", rule.getSource());
            view.put("points", rule.getPoints());
            view.put("dailyCap", rule.getDailyCap());
            view.put("enabled", rule.getEnabled());
            return view;
        }).toList();
    }

    @Transactional
    public Map<String, Object> updateRule(long operatorId, String source, int points, int dailyCap, boolean enabled) {
        if (!SOURCES.contains(source)) {
            throw new BizException("未知积分来源");
        }
        if (points < 0 || dailyCap < 0) {
            throw new BizException("积分和每日上限不能为负");
        }
        ensureRules();
        ruleMapper.update(null, Wrappers.<PointRule>lambdaUpdate()
                .set(PointRule::getPoints, points)
                .set(PointRule::getDailyCap, dailyCap)
                .set(PointRule::getEnabled, enabled ? 1 : 0)
                .set(PointRule::getUpdateTime, clock.now())
                .eq(PointRule::getSource, source));
        auditLogger.log(operatorId, "", "points", "UPDATE_RULE", "point_rule", null,
                source + " points=" + points + " cap=" + dailyCap);
        return Map.of("source", source, "points", points, "dailyCap", dailyCap, "enabled", enabled);
    }

    @Transactional
    public Map<String, Object> adjust(long operatorId, long userId, int points, String reason, String requestId) {
        if (reason == null || reason.isBlank()) {
            throw new BizException("请填写调整原因");
        }
        if (requestId == null || requestId.isBlank()) {
            throw new BizException("缺少请求号");
        }
        if (points == 0) {
            throw new BizException("调整分值不能为 0");
        }
        String eventKey = "ADJUST:" + operatorId + ":" + userId + ":" + requestId.trim();
        PointLedger existing = ledgerMapper.selectOne(Wrappers.<PointLedger>lambdaQuery()
                .eq(PointLedger::getEventKey, eventKey));
        if (existing != null) {
            return ledgerView(existing);
        }
        lockAccount(userId);
        insertLedger(userId, "ADJUST", eventKey, points, clock.today(), reason.trim(), operatorId);
        addTotal(userId, points);
        pushRank(userId, clock.period(clock.today()), periodPoints(userId, clock.period(clock.today())), clock.now());
        auditLogger.log(operatorId, "", "points", "ADJUST", "point_ledger", userId,
                "points=" + points + " reason=" + reason.trim());
        PointLedger saved = ledgerMapper.selectOne(Wrappers.<PointLedger>lambdaQuery()
                .eq(PointLedger::getEventKey, eventKey));
        return ledgerView(saved);
    }

    public Map<String, Object> rank(String period, long page, long size, Long viewerId) {
        String key = period == null || period.isBlank() || "current".equals(period)
                ? clock.period(clock.today()) : period;
        RankPeriod stored = periodMapper.selectById(key);
        boolean settled = stored != null && "SETTLED".equals(stored.getStatus());
        long pageNo = Math.max(page, 1);
        long pageSize = Math.min(Math.max(size, 1), 50);
        List<Map<String, Object>> records;
        long total;
        if (settled) {
            Page<RankEntry> result = entryMapper.selectPage(new Page<>(pageNo, pageSize),
                    Wrappers.<RankEntry>lambdaQuery()
                            .eq(RankEntry::getPeriodKey, key)
                            .orderByAsc(RankEntry::getRankNo));
            total = result.getTotal();
            records = result.getRecords().stream().map(entry -> row(entry.getRankNo(), entry.getUserId(),
                    entry.getDisplayName(), entry.getPoints())).toList();
        } else {
            List<Agg> all = aggregate(key);
            total = all.size();
            int from = (int) Math.min((pageNo - 1) * pageSize, all.size());
            int to = (int) Math.min(from + pageSize, all.size());
            records = new ArrayList<>();
            Map<Long, String> names = names(all.subList(from, to).stream().map(Agg::userId).toList());
            for (int i = from; i < to; i++) {
                Agg agg = all.get(i);
                records.add(row(i + 1, agg.userId(), publicName(names.get(agg.userId()), agg.userId()), agg.points()));
            }
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("period", key);
        view.put("settled", settled);
        view.put("total", total);
        view.put("records", records);
        if (viewerId != null) {
            view.put("me", myRank(key, viewerId, settled));
        }
        view.put("source", rankSource());
        return view;
    }

    @Transactional
    public Map<String, Object> settle(String period) {
        String key = period == null || period.isBlank() ? previousPeriod() : period;
        RankPeriod current = periodMapper.selectById(key);
        if (current != null && "SETTLED".equals(current.getStatus())) {
            return Map.of("period", key, "settled", true, "repeated", true);
        }
        if (current == null) {
            RankPeriod created = new RankPeriod();
            created.setPeriodKey(key);
            created.setStatus("OPEN");
            try {
                periodMapper.insert(created);
            } catch (DuplicateKeyException ignored) {
                // 并发结算由后面的条件更新收口
            }
        }
        int claimed = periodMapper.update(null, Wrappers.<RankPeriod>lambdaUpdate()
                .set(RankPeriod::getStatus, "SETTLING")
                .eq(RankPeriod::getPeriodKey, key)
                .in(RankPeriod::getStatus, "OPEN", "SETTLING"));
        if (claimed != 1) {
            RankPeriod latest = periodMapper.selectById(key);
            if (latest != null && "SETTLED".equals(latest.getStatus())) {
                return Map.of("period", key, "settled", true, "repeated", true);
            }
            throw new BizException("榜单结算冲突，请重试");
        }
        entryMapper.delete(Wrappers.<RankEntry>lambdaQuery().eq(RankEntry::getPeriodKey, key));
        List<Agg> all = aggregate(key);
        Map<Long, String> names = names(all.stream().map(Agg::userId).toList());
        int rank = 1;
        for (Agg agg : all) {
            RankEntry entry = new RankEntry();
            entry.setPeriodKey(key);
            entry.setUserId(agg.userId());
            entry.setPoints(agg.points());
            entry.setRankNo(rank++);
            entry.setDisplayName(publicName(names.get(agg.userId()), agg.userId()));
            entryMapper.insert(entry);
        }
        periodMapper.update(null, Wrappers.<RankPeriod>lambdaUpdate()
                .set(RankPeriod::getStatus, "SETTLED")
                .set(RankPeriod::getSettleTime, clock.now())
                .eq(RankPeriod::getPeriodKey, key)
                .eq(RankPeriod::getStatus, "SETTLING"));
        return Map.of("period", key, "settled", true, "count", all.size(), "repeated", false);
    }

    private int awardLocked(long userId, String source, String eventKey, LocalDate bizDate, String reason, Long operatorId) {
        ensureRules();
        if (ledgerMapper.selectCount(Wrappers.<PointLedger>lambdaQuery().eq(PointLedger::getEventKey, eventKey)) > 0) {
            return 0;
        }
        PointRule rule = ruleMapper.selectById(source);
        if (rule == null || rule.getEnabled() == null || rule.getEnabled() != 1 || rule.getPoints() == null || rule.getPoints() <= 0) {
            return 0;
        }
        lockAccount(userId);
        int today = sumSourceDay(userId, source, bizDate);
        if (today + rule.getPoints() > rule.getDailyCap()) {
            return 0;
        }
        try {
            insertLedger(userId, source, eventKey, rule.getPoints(), bizDate, reason, operatorId);
        } catch (DuplicateKeyException ex) {
            return 0;
        }
        addTotal(userId, rule.getPoints());
        pushRank(userId, clock.period(bizDate), periodPoints(userId, clock.period(bizDate)), clock.now());
        return rule.getPoints();
    }

    private void lockAccount(long userId) {
        PointAccount account = accountMapper.selectById(userId);
        if (account == null) {
            PointAccount created = new PointAccount();
            created.setUserId(userId);
            created.setTotalPoints(0);
            created.setUpdateTime(clock.now());
            try {
                accountMapper.insert(created);
            } catch (DuplicateKeyException ignored) {
                // 并发建户后继续更新
            }
        }
        accountMapper.lockById(userId);
    }

    private void insertLedger(long userId, String source, String eventKey, int points, LocalDate bizDate,
                              String reason, Long operatorId) {
        PointLedger ledger = new PointLedger();
        ledger.setUserId(userId);
        ledger.setSource(source);
        ledger.setEventKey(eventKey);
        ledger.setPoints(points);
        ledger.setBizDate(bizDate);
        ledger.setPeriodKey(clock.period(bizDate));
        ledger.setReason(reason);
        ledger.setOperatorId(operatorId);
        ledger.setCreateTime(clock.now());
        ledgerMapper.insert(ledger);
    }

    private void addTotal(long userId, int points) {
        PointAccount account = accountMapper.selectById(userId);
        int next = (account == null || account.getTotalPoints() == null ? 0 : account.getTotalPoints()) + points;
        if (account == null) {
            PointAccount created = new PointAccount();
            created.setUserId(userId);
            created.setTotalPoints(next);
            created.setUpdateTime(clock.now());
            accountMapper.insert(created);
            return;
        }
        accountMapper.update(null, Wrappers.<PointAccount>lambdaUpdate()
                .set(PointAccount::getTotalPoints, next)
                .set(PointAccount::getUpdateTime, clock.now())
                .eq(PointAccount::getUserId, userId));
    }

    private int sumSourceDay(long userId, String source, LocalDate day) {
        return ledgerMapper.selectList(Wrappers.<PointLedger>lambdaQuery()
                        .eq(PointLedger::getUserId, userId)
                        .eq(PointLedger::getSource, source)
                        .eq(PointLedger::getBizDate, day))
                .stream().mapToInt(row -> Math.max(row.getPoints(), 0)).sum();
    }

    private int sumDay(long userId, LocalDate day) {
        return ledgerMapper.selectList(Wrappers.<PointLedger>lambdaQuery()
                        .eq(PointLedger::getUserId, userId)
                        .eq(PointLedger::getBizDate, day))
                .stream().mapToInt(row -> row.getPoints() == null ? 0 : row.getPoints()).sum();
    }

    private int total(long userId) {
        PointAccount account = accountMapper.selectById(userId);
        return account == null || account.getTotalPoints() == null ? 0 : account.getTotalPoints();
    }

    private int periodPoints(long userId, String period) {
        return ledgerMapper.selectList(Wrappers.<PointLedger>lambdaQuery()
                        .eq(PointLedger::getUserId, userId)
                        .eq(PointLedger::getPeriodKey, period))
                .stream().mapToInt(row -> row.getPoints() == null ? 0 : row.getPoints()).sum();
    }

    private int currentStreak(long userId, LocalDate today) {
        CheckinRecord yesterday = checkinMapper.selectOne(Wrappers.<CheckinRecord>lambdaQuery()
                .eq(CheckinRecord::getUserId, userId)
                .eq(CheckinRecord::getBizDate, today.minusDays(1)));
        return yesterday == null ? 0 : yesterday.getStreak();
    }

    private Map<String, Object> checkinView(CheckinRecord row, int gained, boolean already) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("signed", true);
        view.put("already", already);
        view.put("date", row.getBizDate().toString());
        view.put("streak", row.getStreak());
        view.put("gained", gained);
        view.put("totalPoints", total(row.getUserId()));
        return view;
    }

    private Map<String, Object> ledgerView(PointLedger row) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", String.valueOf(row.getId()));
        view.put("source", row.getSource());
        view.put("points", row.getPoints());
        view.put("bizDate", row.getBizDate() == null ? null : row.getBizDate().toString());
        view.put("period", row.getPeriodKey());
        view.put("reason", row.getReason());
        view.put("createTime", row.getCreateTime());
        return view;
    }

    private void ensureRules() {
        LocalDateTime now = clock.now();
        seedIfAbsent("CHECKIN", 5, 5, now);
        seedIfAbsent("STREAK", 10, 10, now);
        seedIfAbsent("LEARN", 2, 10, now);
        seedIfAbsent("QUESTION", 3, 9, now);
        seedIfAbsent("NOTE_LIKE", 1, 5, now);
        seedIfAbsent("NOTE_COMMENT", 1, 3, now);
    }

    private void seedIfAbsent(String source, int points, int cap, LocalDateTime now) {
        if (ruleMapper.selectById(source) != null) {
            return;
        }
        seed(source, points, cap, now);
    }

    private void seed(String source, int points, int cap, LocalDateTime now) {
        PointRule rule = new PointRule();
        rule.setSource(source);
        rule.setPoints(points);
        rule.setDailyCap(cap);
        rule.setEnabled(1);
        rule.setUpdateTime(now);
        try {
            ruleMapper.insert(rule);
        } catch (DuplicateKeyException ignored) {
            // 并发初始化
        }
    }

    private List<Agg> aggregate(String period) {
        Map<Long, int[]> sums = new LinkedHashMap<>();
        Map<Long, LocalDateTime> last = new LinkedHashMap<>();
        for (PointLedger row : ledgerMapper.selectList(Wrappers.<PointLedger>lambdaQuery()
                .eq(PointLedger::getPeriodKey, period))) {
            sums.computeIfAbsent(row.getUserId(), key -> new int[1])[0] += row.getPoints() == null ? 0 : row.getPoints();
            LocalDateTime time = row.getCreateTime();
            if (time != null && (last.get(row.getUserId()) == null || time.isAfter(last.get(row.getUserId())))) {
                last.put(row.getUserId(), time);
            }
        }
        List<Agg> all = new ArrayList<>();
        sums.forEach((userId, value) -> {
            if (value[0] > 0) {
                all.add(new Agg(userId, value[0], last.get(userId)));
            }
        });
        all.sort((a, b) -> {
            int byPoints = Integer.compare(b.points(), a.points());
            if (byPoints != 0) {
                return byPoints;
            }
            LocalDateTime at = a.last() == null ? LocalDateTime.MAX : a.last();
            LocalDateTime bt = b.last() == null ? LocalDateTime.MAX : b.last();
            int byTime = at.compareTo(bt);
            return byTime != 0 ? byTime : Long.compare(a.userId(), b.userId());
        });
        return all;
    }

    private Map<String, Object> myRank(String period, long userId, boolean settled) {
        if (settled) {
            RankEntry entry = entryMapper.selectOne(Wrappers.<RankEntry>lambdaQuery()
                    .eq(RankEntry::getPeriodKey, period)
                    .eq(RankEntry::getUserId, userId));
            if (entry == null) {
                return Map.of("ranked", false, "points", 0);
            }
            return Map.of("ranked", true, "rank", entry.getRankNo(), "points", entry.getPoints());
        }
        List<Agg> all = aggregate(period);
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).userId() == userId) {
                return Map.of("ranked", true, "rank", i + 1, "points", all.get(i).points());
            }
        }
        return Map.of("ranked", false, "points", periodPoints(userId, period));
    }

    private void pushRank(long userId, String period, int points, LocalDateTime when) {
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null || points <= 0) {
            return;
        }
        try {
            double score = points * 1_000_000_000d - when.atZone(com.kzai.learning.stats.StatsWindow.ZONE).toEpochSecond();
            redis.opsForZSet().add("rank:" + period, String.valueOf(userId), score);
        } catch (RuntimeException ex) {
            log.warn("[points] Redis 排行不可用，改由数据库计算 {}", ex.getMessage());
        }
    }

    private String rankSource() {
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null) {
            return "DATABASE";
        }
        try {
            redis.opsForValue().get("rank:ping");
            return "REDIS";
        } catch (RuntimeException ex) {
            return "DATABASE";
        }
    }

    private Map<Long, String> names(List<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        try {
            var result = userClient.names(ids);
            return result == null || result.getData() == null ? Map.of() : result.getData();
        } catch (RuntimeException ex) {
            return Map.of();
        }
    }

    private static String publicName(String name, long userId) {
        if (name == null || name.isBlank()) {
            return "学员" + String.valueOf(userId).substring(Math.max(0, String.valueOf(userId).length() - 4));
        }
        if (name.matches("1\\d{10}")) {
            return name.substring(0, 3) + "****" + name.substring(7);
        }
        return name;
    }

    private Map<String, Object> row(int rank, long userId, String name, int points) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("rank", rank);
        view.put("userId", String.valueOf(userId));
        view.put("name", name);
        view.put("points", points);
        return view;
    }

    private String previousPeriod() {
        LocalDate day = clock.today().withDayOfMonth(1).minusMonths(1);
        return clock.period(day);
    }

    private record Agg(long userId, int points, LocalDateTime last) {
    }
}
