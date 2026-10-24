package com.kzai.learning.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kzai.common.exception.AuthException;
import com.kzai.common.exception.BizException;
import com.kzai.common.result.R;
import com.kzai.common.result.ResultCode;
import com.kzai.learning.client.CourseClient;
import com.kzai.learning.dto.CourseOutlineDTO;
import com.kzai.learning.dto.EntitlementDecision;
import com.kzai.learning.entity.LearningPlan;
import com.kzai.learning.entity.LearningPlanGoal;
import com.kzai.learning.entity.LearningPlanPause;
import com.kzai.learning.mapper.LearningActivityFactMapper;
import com.kzai.learning.mapper.LearningPlanGoalMapper;
import com.kzai.learning.mapper.LearningPlanMapper;
import com.kzai.learning.mapper.LearningPlanPauseMapper;
import com.kzai.learning.stats.PlanWeek;
import com.kzai.learning.stats.StatsWindow;
import com.kzai.learning.vo.LearningPlanBoardVO;
import com.kzai.learning.vo.LearningPlanItemVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 学员学习计划。周完成量只数本周、且落在计划有效区间内的章节首次完成，
 * 不从累计进度倒推。修改目标从下周一开始，不改已经生效的周。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LearningPlanService {

    public static final int GOAL_MIN = 1;
    public static final int GOAL_MAX = 20;
    private static final LocalDateTime OPEN_ENDED = LocalDateTime.of(9999, 1, 1, 0, 0);

    private final LearningPlanMapper planMapper;
    private final LearningPlanGoalMapper goalMapper;
    private final LearningPlanPauseMapper pauseMapper;
    private final LearningActivityFactMapper factMapper;
    private final CourseClient courseClient;
    private final EntitlementService entitlementService;
    private Clock clock = Clock.system(StatsWindow.ZONE);

    /** 测试固定当前周。生产使用上海时区时钟。 */
    void useClock(Clock clock) {
        this.clock = clock;
    }

    public LearningPlanBoardVO board(long userId) {
        try {
            return loadBoard(userId);
        } catch (DataAccessException ex) {
            log.error("[learning.plan] 加载失败 userId={}", userId, ex);
            throw new BizException("学习计划暂时无法加载，请稍后重试");
        }
    }

    private LearningPlanBoardVO loadBoard(long userId) {
        LocalDate today = PlanWeek.today(clock);
        LocalDate weekStart = PlanWeek.mondayOf(today);
        LocalDate weekEnd = PlanWeek.nextMonday(weekStart);
        Coverage coverage = coverage(weekStart);
        List<LearningPlan> plans = planMapper.selectList(Wrappers.<LearningPlan>lambdaQuery()
                .eq(LearningPlan::getUserId, userId)
                .in(LearningPlan::getStatus, LearningPlan.ACTIVE, LearningPlan.PAUSED, LearningPlan.COMPLETED)
                .orderByDesc(LearningPlan::getUpdateTime));
        LearningPlanBoardVO board = new LearningPlanBoardVO();
        board.setWeekStart(weekStart.toString());
        board.setWeekEndExclusive(weekEnd.toString());
        board.setTimezone(StatsWindow.ZONE.getId());
        board.setCoverage(coverage.code);
        board.setCoverageText(coverage.text);
        for (LearningPlan plan : plans) {
            board.getPlans().add(toItem(plan, weekStart, weekEnd, coverage, false));
        }
        summarize(board);
        return board;
    }

    @Transactional
    public LearningPlanItemVO join(long userId, long courseId, int weeklyGoal) {
        checkGoal(weeklyGoal);
        CourseOutlineDTO outline = requireOutline(courseId);
        requireEntitled(userId, outline);
        int chapters = chapterCount(outline);
        if (chapters <= 0) {
            throw new BizException("课程还没有可完成的章节，不能加入学习计划");
        }
        if (planMapper.countChapterFinishes(userId, courseId) >= chapters) {
            throw new BizException("课程章节已经全部完成，不能再加入每周计划");
        }
        LearningPlan open = openPlan(userId, courseId);
        if (open != null) {
            return toItem(open, currentMonday(), PlanWeek.nextMonday(currentMonday()),
                    coverage(currentMonday()), true);
        }
        LocalDateTime now = PlanWeek.now(clock);
        LearningPlan plan = new LearningPlan();
        plan.setUserId(userId);
        plan.setCourseId(courseId);
        plan.setStatus(LearningPlan.ACTIVE);
        plan.setJoinedAt(now);
        plan.setCreateTime(now);
        plan.setUpdateTime(now);
        try {
            planMapper.insert(plan);
        } catch (DuplicateKeyException dup) {
            LearningPlan raced = openPlan(userId, courseId);
            if (raced != null) {
                return toItem(raced, currentMonday(), PlanWeek.nextMonday(currentMonday()),
                        coverage(currentMonday()), true);
            }
            throw new BizException("学习计划正在创建，请稍后刷新");
        }
        insertGoal(plan.getId(), weeklyGoal, currentMonday(), now);
        return toItem(plan, currentMonday(), PlanWeek.nextMonday(currentMonday()),
                coverage(currentMonday()), false);
    }

    @Transactional
    public LearningPlanItemVO changeGoal(long userId, long planId, int weeklyGoal) {
        checkGoal(weeklyGoal);
        LearningPlan plan = requireOwn(userId, planId);
        if (plan.getStatus() != LearningPlan.ACTIVE && plan.getStatus() != LearningPlan.PAUSED) {
            throw new BizException("只有进行中或暂停的计划可以修改目标");
        }
        LocalDate next = PlanWeek.nextMonday(currentMonday());
        LocalDateTime now = PlanWeek.now(clock);
        LearningPlanGoal future = goalMapper.selectOne(Wrappers.<LearningPlanGoal>lambdaQuery()
                .eq(LearningPlanGoal::getPlanId, planId)
                .eq(LearningPlanGoal::getEffectiveWeek, next));
        if (future == null) {
            insertGoal(planId, weeklyGoal, next, now);
        } else {
            future.setWeeklyGoal(weeklyGoal);
            goalMapper.updateById(future);
        }
        plan.setUpdateTime(now);
        planMapper.updateById(plan);
        return toItem(plan, currentMonday(), next, coverage(currentMonday()), false);
    }

    @Transactional
    public LearningPlanItemVO pause(long userId, long planId) {
        LearningPlan plan = requireOwn(userId, planId);
        if (plan.getStatus() != LearningPlan.ACTIVE) {
            throw new BizException("只有进行中的计划可以暂停");
        }
        LocalDateTime now = PlanWeek.now(clock);
        LearningPlanPause row = new LearningPlanPause();
        row.setPlanId(planId);
        row.setPausedAt(now);
        row.setCreateTime(now);
        pauseMapper.insert(row);
        plan.setStatus(LearningPlan.PAUSED);
        plan.setUpdateTime(now);
        planMapper.updateById(plan);
        return toItem(plan, currentMonday(), PlanWeek.nextMonday(currentMonday()), coverage(currentMonday()), false);
    }

    @Transactional
    public LearningPlanItemVO resume(long userId, long planId) {
        LearningPlan plan = requireOwn(userId, planId);
        if (plan.getStatus() != LearningPlan.PAUSED) {
            throw new BizException("只有暂停的计划可以恢复");
        }
        LocalDateTime now = PlanWeek.now(clock);
        LearningPlanPause open = openPause(planId);
        if (open != null) {
            open.setResumedAt(now);
            pauseMapper.updateById(open);
        }
        plan.setStatus(LearningPlan.ACTIVE);
        plan.setUpdateTime(now);
        planMapper.updateById(plan);
        return toItem(plan, currentMonday(), PlanWeek.nextMonday(currentMonday()), coverage(currentMonday()), false);
    }

    @Transactional
    public LearningPlanItemVO quit(long userId, long planId) {
        LearningPlan plan = requireOwn(userId, planId);
        if (plan.getStatus() != LearningPlan.ACTIVE && plan.getStatus() != LearningPlan.PAUSED) {
            throw new BizException("这个计划已经结束");
        }
        LocalDateTime now = PlanWeek.now(clock);
        LearningPlanPause open = openPause(planId);
        if (open != null) {
            open.setResumedAt(now);
            pauseMapper.updateById(open);
        }
        plan.setStatus(LearningPlan.QUIT);
        plan.setQuitAt(now);
        plan.setUpdateTime(now);
        planMapper.updateById(plan);
        return toItem(plan, currentMonday(), PlanWeek.nextMonday(currentMonday()), coverage(currentMonday()), false);
    }

    private LearningPlanItemVO toItem(LearningPlan plan, LocalDate weekStart, LocalDate weekEnd,
                                     Coverage coverage, boolean alreadyJoined) {
        CourseOutlineDTO outline = loadOutline(plan.getCourseId());
        EntitlementDecision access = outline == null
                ? EntitlementDecision.deny("课程信息暂时不可用")
                : entitlementService.resolve(plan.getUserId(), outline);
        maybeComplete(plan, outline);
        int goal = goalFor(plan.getId(), weekStart);
        Integer nextGoal = goalValue(plan.getId(), weekEnd);
        LearningPlanItemVO item = new LearningPlanItemVO();
        item.setId(String.valueOf(plan.getId()));
        item.setCourseId(String.valueOf(plan.getCourseId()));
        item.setCourseName(outline == null || !StringUtils.hasText(outline.getName()) ? "课程信息暂时不可用" : outline.getName());
        item.setStatus(statusCode(plan.getStatus()));
        item.setStatusText(statusText(plan.getStatus()));
        item.setWeeklyGoal(goal);
        item.setGoalEffectiveWeek(weekStart.toString());
        item.setNextWeekGoal(nextGoal);
        item.setNextGoalWeek(nextGoal == null ? null : weekEnd.toString());
        item.setJoinedAt(plan.getJoinedAt() == null ? null : plan.getJoinedAt().toString());
        item.setAlreadyJoined(alreadyJoined);
        item.setCoverage(coverage.code);
        item.setCoverageText(coverage.text);
        boolean entitled = Boolean.TRUE.equals(access.getAllowed());
        item.setAccessReason(entitled ? null : access.getReason());
        boolean continuable = entitled && plan.getStatus() == LearningPlan.ACTIVE;
        item.setCanContinue(continuable);
        item.setContinuePath(continuable ? "/learn/" + plan.getCourseId() : null);
        fillWeek(item, plan, weekStart, weekEnd, coverage, goal);
        return item;
    }

    private void fillWeek(LearningPlanItemVO item, LearningPlan plan, LocalDate weekStart, LocalDate weekEnd,
                          Coverage coverage, int goal) {
        if (plan.getStatus() == LearningPlan.COMPLETED) {
            item.setWeekStatus("COURSE_DONE");
            item.setWeekStatusText("课程已完成");
            item.setFinishedThisWeek(null);
            item.setRemaining(null);
            item.setRatio(null);
            return;
        }
        if (plan.getStatus() == LearningPlan.QUIT) {
            item.setWeekStatus("QUIT");
            item.setWeekStatusText("已退出");
            item.setFinishedThisWeek(null);
            item.setRemaining(null);
            item.setRatio(null);
            return;
        }
        if ("INSUFFICIENT".equals(coverage.code)) {
            item.setWeekStatus("INSUFFICIENT");
            item.setWeekStatusText("覆盖不足");
            item.setFinishedThisWeek(null);
            item.setRemaining(null);
            item.setRatio(null);
            return;
        }
        if (plan.getStatus() == LearningPlan.PAUSED) {
            Integer done = (int) countWeek(plan, weekStart, weekEnd);
            item.setFinishedThisWeek(done);
            item.setRemaining(Math.max(0, goal - done));
            item.setRatio(ratio(done, goal));
            item.setWeekStatus("PAUSED");
            item.setWeekStatusText("已暂停");
            return;
        }
        int done = (int) countWeek(plan, weekStart, weekEnd);
        item.setFinishedThisWeek(done);
        item.setRemaining(Math.max(0, goal - done));
        item.setRatio(ratio(done, goal));
        if (done <= 0) {
            item.setWeekStatus("NOT_STARTED");
            item.setWeekStatusText("尚未学习");
        } else if (done < goal) {
            item.setWeekStatus("PARTIAL");
            item.setWeekStatusText("部分完成");
        } else {
            item.setWeekStatus("REACHED");
            item.setWeekStatusText("目标达成");
        }
    }

    private void summarize(LearningPlanBoardVO board) {
        List<LearningPlanItemVO> active = board.getPlans().stream()
                .filter(p -> "ACTIVE".equals(p.getStatus())).toList();
        boolean paused = board.getPlans().stream().anyMatch(p -> "PAUSED".equals(p.getStatus()));
        if (active.isEmpty() && !paused) {
            board.setSummary("NONE");
            board.setSummaryText("没有计划");
            return;
        }
        if (active.isEmpty()) {
            board.setSummary("PAUSED");
            board.setSummaryText("计划已暂停");
            return;
        }
        if (active.stream().anyMatch(p -> "INSUFFICIENT".equals(p.getWeekStatus()))) {
            board.setSummary("INSUFFICIENT");
            board.setSummaryText("本周完成量覆盖不足");
            return;
        }
        boolean anyDone = active.stream().anyMatch(p -> p.getFinishedThisWeek() != null && p.getFinishedThisWeek() > 0);
        boolean allReached = active.stream().allMatch(p -> "REACHED".equals(p.getWeekStatus()));
        if (allReached) {
            board.setSummary("REACHED");
            board.setSummaryText("目标达成");
        } else if (!anyDone) {
            board.setSummary("NOT_STARTED");
            board.setSummaryText("尚未学习");
        } else {
            board.setSummary("PARTIAL");
            board.setSummaryText("部分完成");
        }
    }

    private long countWeek(LearningPlan plan, LocalDate weekStart, LocalDate weekEnd) {
        LocalDateTime until = plan.getQuitAt() == null ? OPEN_ENDED : plan.getQuitAt();
        return planMapper.countWeekFinishes(plan.getUserId(), plan.getCourseId(), plan.getId(),
                weekStart, weekEnd, plan.getJoinedAt(), until);
    }

    private void maybeComplete(LearningPlan plan, CourseOutlineDTO outline) {
        if (plan.getStatus() != LearningPlan.ACTIVE && plan.getStatus() != LearningPlan.PAUSED) {
            return;
        }
        int chapters = chapterCount(outline);
        if (chapters <= 0) {
            return;
        }
        if (planMapper.countChapterFinishes(plan.getUserId(), plan.getCourseId()) < chapters) {
            return;
        }
        LocalDateTime now = PlanWeek.now(clock);
        LearningPlanPause open = openPause(plan.getId());
        if (open != null) {
            open.setResumedAt(now);
            pauseMapper.updateById(open);
        }
        plan.setStatus(LearningPlan.COMPLETED);
        plan.setUpdateTime(now);
        planMapper.updateById(plan);
    }

    private int goalFor(long planId, LocalDate weekStart) {
        LearningPlanGoal goal = goalMapper.selectOne(Wrappers.<LearningPlanGoal>lambdaQuery()
                .eq(LearningPlanGoal::getPlanId, planId)
                .le(LearningPlanGoal::getEffectiveWeek, weekStart)
                .orderByDesc(LearningPlanGoal::getEffectiveWeek)
                .last("LIMIT 1"));
        return goal == null || goal.getWeeklyGoal() == null ? 0 : goal.getWeeklyGoal();
    }

    private Integer goalValue(long planId, LocalDate week) {
        LearningPlanGoal goal = goalMapper.selectOne(Wrappers.<LearningPlanGoal>lambdaQuery()
                .eq(LearningPlanGoal::getPlanId, planId)
                .eq(LearningPlanGoal::getEffectiveWeek, week));
        return goal == null ? null : goal.getWeeklyGoal();
    }

    private void insertGoal(long planId, int weeklyGoal, LocalDate week, LocalDateTime now) {
        LearningPlanGoal goal = new LearningPlanGoal();
        goal.setPlanId(planId);
        goal.setWeeklyGoal(weeklyGoal);
        goal.setEffectiveWeek(week);
        goal.setCreateTime(now);
        goalMapper.insert(goal);
    }

    private LearningPlan openPlan(long userId, long courseId) {
        return planMapper.selectOne(Wrappers.<LearningPlan>lambdaQuery()
                .eq(LearningPlan::getUserId, userId)
                .eq(LearningPlan::getCourseId, courseId)
                .in(LearningPlan::getStatus, LearningPlan.ACTIVE, LearningPlan.PAUSED)
                .last("LIMIT 1"));
    }

    private LearningPlanPause openPause(long planId) {
        return pauseMapper.selectOne(Wrappers.<LearningPlanPause>lambdaQuery()
                .eq(LearningPlanPause::getPlanId, planId)
                .isNull(LearningPlanPause::getResumedAt)
                .orderByDesc(LearningPlanPause::getPausedAt)
                .last("LIMIT 1"));
    }

    private LearningPlan requireOwn(long userId, long planId) {
        LearningPlan plan = planMapper.selectById(planId);
        if (plan == null || plan.getUserId() == null || plan.getUserId() != userId) {
            throw new BizException("学习计划不存在");
        }
        return plan;
    }

    private CourseOutlineDTO requireOutline(long courseId) {
        CourseOutlineDTO outline = loadOutline(courseId);
        if (outline == null || outline.getId() == null) {
            throw new BizException("课程不存在");
        }
        return outline;
    }

    private CourseOutlineDTO loadOutline(long courseId) {
        try {
            R<CourseOutlineDTO> result = courseClient.learnOutline(courseId);
            return result == null ? null : result.getData();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private void requireEntitled(long userId, CourseOutlineDTO outline) {
        EntitlementDecision decision = entitlementService.resolve(userId, outline);
        if (!Boolean.TRUE.equals(decision.getAllowed())) {
            throw new AuthException(ResultCode.FORBIDDEN,
                    StringUtils.hasText(decision.getReason()) ? decision.getReason() : "未取得该课程的学习权益");
        }
    }

    private static int chapterCount(CourseOutlineDTO outline) {
        if (outline == null || outline.getChapters() == null) {
            return 0;
        }
        return (int) outline.getChapters().stream().filter(ch -> ch != null && ch.getId() != null).count();
    }

    private Coverage coverage(LocalDate weekStart) {
        LocalDateTime since = factMapper == null ? null : factMapper.factsSince();
        if (since == null) {
            return new Coverage("INSUFFICIENT", "本周完成量暂不可用");
        }
        LocalDate covered = StatsWindow.businessDate(since);
        if (weekStart.isBefore(covered)) {
            log.info("[learning.plan] 本周早于事实起点 weekStart={} factsSince={}", weekStart, covered);
            return new Coverage("INSUFFICIENT", "本周完成量暂不可用");
        }
        return new Coverage("FULL", "");
    }

    private LocalDate currentMonday() {
        return PlanWeek.mondayOf(PlanWeek.today(clock));
    }

    private static void checkGoal(int weeklyGoal) {
        if (weeklyGoal < GOAL_MIN || weeklyGoal > GOAL_MAX) {
            throw new BizException("每周目标必须是 " + GOAL_MIN + " 到 " + GOAL_MAX + " 节");
        }
    }

    private static double ratio(int done, int goal) {
        if (goal <= 0) {
            return 0;
        }
        double value = Math.min(100.0, done * 100.0 / goal);
        return Math.round(value * 10.0) / 10.0;
    }

    private static String statusCode(Integer status) {
        if (status == null) {
            return "UNKNOWN";
        }
        return switch (status) {
            case LearningPlan.ACTIVE -> "ACTIVE";
            case LearningPlan.PAUSED -> "PAUSED";
            case LearningPlan.QUIT -> "QUIT";
            case LearningPlan.COMPLETED -> "COMPLETED";
            default -> "UNKNOWN";
        };
    }

    private static String statusText(Integer status) {
        if (status == null) {
            return "未知";
        }
        return switch (status) {
            case LearningPlan.ACTIVE -> "进行中";
            case LearningPlan.PAUSED -> "已暂停";
            case LearningPlan.QUIT -> "已退出";
            case LearningPlan.COMPLETED -> "课程已完成";
            default -> "未知";
        };
    }

    private record Coverage(String code, String text) {
    }
}
