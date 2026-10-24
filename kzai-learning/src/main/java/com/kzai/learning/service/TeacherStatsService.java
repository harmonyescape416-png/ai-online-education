package com.kzai.learning.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kzai.common.constant.Constants;
import com.kzai.common.exception.AuthException;
import com.kzai.common.exception.BizException;
import com.kzai.common.result.ResultCode;
import com.kzai.learning.client.CourseClient;
import com.kzai.learning.client.UserClient;
import com.kzai.learning.dto.CourseOutlineDTO;
import com.kzai.learning.dto.LearnChapterDTO;
import com.kzai.learning.entity.InteractionQuestion;
import com.kzai.learning.entity.InteractionReply;
import com.kzai.learning.entity.InteractionReplyExtra;
import com.kzai.learning.entity.InteractionReplyHelpful;
import com.kzai.learning.entity.LearningLesson;
import com.kzai.learning.entity.LearningLessonProgress;
import com.kzai.learning.mapper.InteractionReplyHelpfulMapper;
import com.kzai.learning.mapper.LearningActivityFactMapper;
import com.kzai.learning.stats.StatsWindow;
import com.kzai.learning.mapper.InteractionQuestionMapper;
import com.kzai.learning.mapper.LearningLessonMapper;
import com.kzai.learning.mapper.LearningLessonProgressMapper;
import com.kzai.common.util.CsvUtils;
import com.kzai.learning.vo.ChapterStatVO;
import com.kzai.learning.vo.RecentStudyVO;
import com.kzai.learning.vo.TeacherStatsVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 教师课程学情统计（M5）。
 * <p>
 * 数据全部来自 MySQL 真实学习记录：
 * <ul>
 *   <li>学习人数：learning_lesson 中该课程的记录数；</li>
 *   <li>有效学习时长：learning_lesson_progress.study_seconds（按视频位置真实前进量累计，
 *       不是登录时间减退出时间）；</li>
 *   <li>完成率：已完成人数 / 学习人数，与课程进度（已完成章节数 / 章节数）分别统计。</li>
 * </ul>
 * 教师只能查看本人创建的课程；课程问答数与 AI 回复采纳率依赖 M10，本阶段标记未就绪。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TeacherStatsService {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final LearningLessonMapper lessonMapper;
    private final com.kzai.learning.mapper.InteractionQuestionMapper questionMapper;
    private final com.kzai.learning.mapper.InteractionReplyMapper replyMapper;
    private final com.kzai.learning.mapper.InteractionReplyExtraMapper replyExtraMapper;
    private final LearningLessonProgressMapper progressMapper;
    private final LearningActivityFactMapper factMapper;
    private final InteractionReplyHelpfulMapper helpfulMapper;
    private final CourseClient courseClient;
    private final UserClient userClient;
    private final InteractionQuestionMapper interactionQuestionMapper;

    /** 教师学情统计（分页，按课程维度） */
    public IPage<TeacherStatsVO> page(Long userId, Long courseId, Integer pageNum,
                                      Integer pageSize, boolean backstageAdmin, String dateFrom, String dateTo) {
        StatsWindow window = StatsWindow.parse(dateFrom, dateTo);
        int pn = pageNum == null || pageNum < 1 ? 1 : pageNum;
        int ps = pageSize == null || pageSize < 1 ? 10 : pageSize;
        List<Long> courseIds = resolveCourseIds(userId, courseId, backstageAdmin);
        if (courseIds.isEmpty()) {
            Page<TeacherStatsVO> empty = new Page<>(pn, ps, 0);
            empty.setRecords(List.of());
            return empty;
        }
        int from = Math.min((pn - 1) * ps, courseIds.size());
        int to = Math.min(from + ps, courseIds.size());
        List<TeacherStatsVO> vos = new ArrayList<>();
        for (int i = from; i < to; i++) {
            TeacherStatsVO vo = statsOf(courseIds.get(i), window);
            if (vo != null) {
                vos.add(vo);
            }
        }
        Page<TeacherStatsVO> page = new Page<>(pn, ps, courseIds.size());
        page.setRecords(vos);
        return page;
    }

    public IPage<TeacherStatsVO> page(Long userId, Long courseId, Integer pageNum,
                                      Integer pageSize, boolean backstageAdmin) {
        return page(userId, courseId, pageNum, pageSize, backstageAdmin, null, null);
    }

    /** CSV 导出（章节维度明细，UTF-8 BOM + 公式注入防护） */
    /**
     * M15.20-B：教师工作台摘要（本人课程口径；空集合早退；学习人数去重；区分 AI/人工回复）。
     * 待回复=无任何回复的提问（status 语义红线不触碰）；AI 待检查=AI 回复且 reply_extra.ai_checked=0。
     */
    public java.util.Map<String, Object> workbenchSummary(Long userId, boolean admin) {
        List<Long> courseIds = resolveCourseIds(userId, null, admin);
        long courseCount = courseIds.size();
        long learners = 0, pendingQuestions = 0, aiReplies = 0, aiUnchecked = 0,
                humanReplies = 0, weeklyQuestions = 0;
        if (courseCount > 0) {
            learners = lessonMapper.selectObjs(com.baomidou.mybatisplus.core.toolkit.Wrappers
                            .<com.kzai.learning.entity.LearningLesson>query()
                            .select("DISTINCT user_id")
                            .in("course_id", courseIds)).size();
            var qs = questionMapper.selectList(com.baomidou.mybatisplus.core.toolkit.Wrappers
                    .<com.kzai.learning.entity.InteractionQuestion>lambdaQuery()
                    .in(com.kzai.learning.entity.InteractionQuestion::getCourseId, courseIds));
            weeklyQuestions = qs.stream().filter(x -> x.getCreateTime() != null
                    && x.getCreateTime().isAfter(java.time.LocalDateTime.now().minusDays(7))).count();
            if (!qs.isEmpty()) {
                List<Long> qIds = qs.stream().map(com.kzai.learning.entity.InteractionQuestion::getId).toList();
                var replies = replyMapper.selectList(com.baomidou.mybatisplus.core.toolkit.Wrappers
                        .<com.kzai.learning.entity.InteractionReply>lambdaQuery()
                        .in(com.kzai.learning.entity.InteractionReply::getQuestionId, qIds));
                var repliedIds = replies.stream()
                        .map(com.kzai.learning.entity.InteractionReply::getQuestionId).distinct().toList();
                pendingQuestions = qs.stream()
                        .filter(x -> !repliedIds.contains(x.getId())).count();
                // extra 按 replyId 建映射（source=AI/TEACHER；ai_checked=教师检查状态）
                var extras = replyExtraMapper.selectList(com.baomidou.mybatisplus.core.toolkit.Wrappers
                        .<com.kzai.learning.entity.InteractionReplyExtra>lambdaQuery()
                        .in(com.kzai.learning.entity.InteractionReplyExtra::getReplyId,
                                replies.stream().map(com.kzai.learning.entity.InteractionReply::getId).toList()));
                java.util.Map<Long, com.kzai.learning.entity.InteractionReplyExtra> extraByReply =
                        extras.stream().collect(java.util.stream.Collectors.toMap(
                                com.kzai.learning.entity.InteractionReplyExtra::getReplyId,
                                x -> x, (a, b) -> a));
                var visibleQuestions = qs.stream()
                        .filter(q -> q.getHidden() == null || q.getHidden() == 0)
                        .map(InteractionQuestion::getId)
                        .collect(Collectors.toSet());
                var aiList = replies.stream()
                        .filter(r -> visibleQuestions.contains(r.getQuestionId()))
                        .filter(r -> r.getHidden() == null || r.getHidden() == 0)
                        .filter(r -> {
                            var ex = extraByReply.get(r.getId());
                            return ex != null && InteractionReplyExtra.SOURCE_SYSTEM_AI.equals(ex.getSource());
                        }).toList();
                aiReplies = aiList.size();
                aiUnchecked = aiList.stream().filter(r -> {
                    var ex = extraByReply.get(r.getId());
                    boolean corrected = ex != null && ex.getCorrected() != null && ex.getCorrected() == 1;
                    boolean checked = ex != null && ex.getAiChecked() != null && ex.getAiChecked() == 1;
                    return !corrected && !checked;
                }).count();
                humanReplies = replies.size() - aiList.size();
            }
        }
        return java.util.Map.of(
                "courseCount", courseCount, "learners", learners,
                "pendingQuestions", pendingQuestions, "aiReplies", aiReplies,
                "aiUnchecked", aiUnchecked, "humanReplies", humanReplies,
                "weeklyQuestions", weeklyQuestions);
    }


    public String exportCsv(Long userId, Long courseId, boolean backstageAdmin, String dateFrom, String dateTo) {
        StatsWindow window = StatsWindow.parse(dateFrom, dateTo);
        List<Long> courseIds = resolveCourseIds(userId, courseId, backstageAdmin);
        return renderCsv(courseIds, window);
    }

    public String exportCsv(Long userId, Long courseId, boolean backstageAdmin) {
        return exportCsv(userId, courseId, backstageAdmin, null, null);
    }

    private String renderCsv(List<Long> courseIds, StatsWindow window) {
        boolean range = window.mode() == StatsWindow.Mode.RANGE;
        String scope = range ? "所选区间" : "累计";
        StringBuilder sb = new StringBuilder(CsvUtils.UTF8_BOM);
        sb.append(CsvUtils.line(
                CsvUtils.escape("口径"),
                CsvUtils.escape("开始日期"),
                CsvUtils.escape("结束日期"),
                CsvUtils.escape("学习数据覆盖"),
                CsvUtils.escape("课程ID"),
                CsvUtils.escape("课程名称"),
                CsvUtils.escape("章节序号"),
                CsvUtils.escape("章节名称"),
                CsvUtils.escape(scope + "学习人数"),
                CsvUtils.escape(scope + "完课人数"),
                CsvUtils.escape(scope + "完课率(%)"),
                CsvUtils.escape(scope + "章节学习人数"),
                CsvUtils.escape(scope + "章节完成人数"),
                CsvUtils.escape(scope + "章节完成率(%)"),
                CsvUtils.escape(scope + "章节有效学习时长(秒)"),
                CsvUtils.escape(scope + "平均有效学习时长(秒/人)"),
                CsvUtils.escape("最近学习时间"),
                CsvUtils.escape(scope + "提问数"),
                CsvUtils.escape("AI样本数"),
                CsvUtils.escape("被采纳AI回复数"),
                CsvUtils.escape("AI回复采纳率(%)")));
        for (Long id : courseIds) {
            TeacherStatsVO stats = statsOf(id, window);
            if (stats == null) {
                continue;
            }
            appendCourseRows(sb, stats);
        }
        return sb.toString();
    }

    private void appendCourseRows(StringBuilder sb, TeacherStatsVO stats) {
        String courseName = stats.getCourseName();
        String avg = stats.getAvgStudySeconds() == null ? "" : String.valueOf(stats.getAvgStudySeconds());
        String last = stats.getLastStudyTime() == null ? "" : FMT.format(stats.getLastStudyTime());
        String learners = numOrCoverage(stats, stats.getLearnerCount());
        String finished = numOrCoverage(stats, stats.getFinishedLearnerCount());
        String rate = stats.getFinishRate() == null ? coverageCell(stats) : String.valueOf(stats.getFinishRate());
        String questions = stats.getQuestionCount() == null ? "" : String.valueOf(stats.getQuestionCount());
        String samples = stats.getAiSampleCount() == null ? "" : String.valueOf(stats.getAiSampleCount());
        String adopted = stats.getAiAdoptedCount() == null ? "" : String.valueOf(stats.getAiAdoptedCount());
        String adoptRate = stats.getAiAdoptRate() == null
                ? (stats.getAiSampleCount() != null && stats.getAiSampleCount() == 0 ? "暂无AI回复样本" : "")
                : String.valueOf(stats.getAiAdoptRate());
        List<ChapterStatVO> chapters = stats.getChapterStats() == null || stats.getChapterStats().isEmpty()
                ? List.of() : stats.getChapterStats();
        if (chapters.isEmpty()) {
            sb.append(csvLine(stats, courseName, "", "（无章节）", learners, finished, rate,
                    coverageCell(stats), coverageCell(stats), coverageCell(stats), coverageCell(stats),
                    avg, last, questions, samples, adopted, adoptRate));
            return;
        }
        for (ChapterStatVO chapter : chapters) {
            sb.append(csvLine(stats, courseName,
                    chapter.getChapterOrder() == null ? "" : String.valueOf(chapter.getChapterOrder()),
                    chapter.getChapterName(),
                    learners, finished, rate,
                    numOrCoverage(stats, chapter.getLearnerCount()),
                    numOrCoverage(stats, chapter.getFinishedCount()),
                    chapter.getFinishRate() == null ? coverageCell(stats) : String.valueOf(chapter.getFinishRate()),
                    numOrCoverage(stats, chapter.getTotalStudySeconds()),
                    avg, last, questions, samples, adopted, adoptRate));
        }
    }

    private String csvLine(TeacherStatsVO stats, String courseName, String order, String chapter,
                           String learners, String finished, String rate,
                           String chapterLearners, String chapterFinished, String chapterRate, String chapterSeconds,
                           String avg, String last, String questions, String samples, String adopted, String adoptRate) {
        return CsvUtils.line(
                CsvUtils.escape("CUMULATIVE".equals(stats.getStatsScope()) ? "累计" : "所选区间"),
                CsvUtils.escape(stats.getDateFrom()),
                CsvUtils.escape(stats.getDateTo()),
                CsvUtils.escape(stats.getCoverageMessage() == null ? "OK" : stats.getCoverageMessage()),
                CsvUtils.number(stats.getCourseId()),
                CsvUtils.escape(courseName),
                CsvUtils.number(order),
                CsvUtils.escape(chapter),
                CsvUtils.number(learners),
                CsvUtils.number(finished),
                CsvUtils.number(rate),
                CsvUtils.number(chapterLearners),
                CsvUtils.number(chapterFinished),
                CsvUtils.number(chapterRate),
                CsvUtils.number(chapterSeconds),
                CsvUtils.number(avg),
                CsvUtils.escape(last),
                CsvUtils.number(questions),
                CsvUtils.number(samples),
                CsvUtils.number(adopted),
                CsvUtils.escape(adoptRate));
    }

    private static String coverageCell(TeacherStatsVO stats) {
        return "INSUFFICIENT".equals(stats.getLearningCoverage()) ? "数据覆盖不足" : "0";
    }

    private static String numOrCoverage(TeacherStatsVO stats, Number value) {
        if ("INSUFFICIENT".equals(stats.getLearningCoverage())) {
            return "数据覆盖不足";
        }
        return value == null ? "" : String.valueOf(value);
    }

    // ------------------------------------------------------------ 内部实现

    /** 课程范围：教师只能看本人创建的课程；后台管理员可按 courseId 查看任意课程 */
    public List<Long> resolveCourseIds(Long userId, Long courseId, boolean backstageAdmin) {
        if (courseId != null) {
            if (!backstageAdmin && !isOwner(userId, courseId)) {
                throw new AuthException(ResultCode.FORBIDDEN, "无权查看该课程的学情数据");
            }
            return List.of(courseId);
        }
        try {
            var result = courseClient.byCreator(userId);
            List<CourseOutlineDTO> courses = result == null ? null : result.getData();
            if (courses == null || courses.isEmpty()) {
                return List.of();
            }
            return courses.stream()
                    .map(CourseOutlineDTO::getId)
                    .filter(Objects::nonNull)
                    .sorted(Comparator.reverseOrder())
                    .toList();
        } catch (Exception e) {
            log.error("[learning] course service unavailable: {}", e.getMessage());
            throw new BizException("课程服务暂时不可用，请稍后重试");
        }
    }

    private boolean isOwner(Long userId, Long courseId) {
        try {
            var result = courseClient.ownership(userId, List.of(courseId));
            Map<Long, Boolean> map = result == null ? null : result.getData();
            return map != null && Boolean.TRUE.equals(map.get(courseId));
        } catch (Exception e) {
            log.error("[learning] ownership check unavailable: {}", e.getMessage());
            throw new BizException("课程服务暂时不可用，请稍后重试");
        }
    }

    private TeacherStatsVO statsOf(Long courseId, StatsWindow window) {
        if (window.mode() == StatsWindow.Mode.RANGE) {
            return statsOfRange(courseId, window);
        }
        TeacherStatsVO vo = statsOfCumulative(courseId);
        return vo;
    }

    private TeacherStatsVO statsOfCumulative(Long courseId) {
        CourseOutlineDTO outline;
        try {
            var result = courseClient.learnOutline(courseId);
            outline = result == null ? null : result.getData();
        } catch (Exception e) {
            log.error("[learning] course service unavailable: {}", e.getMessage());
            throw new BizException("课程服务暂时不可用，请稍后重试");
        }
        if (outline == null || outline.getId() == null) {
            return null;
        }
        List<LearningLesson> lessons = lessonMapper.selectList(
                Wrappers.<LearningLesson>lambdaQuery().eq(LearningLesson::getCourseId, courseId));
        List<LearningLessonProgress> progresses = progressMapper.selectList(
                Wrappers.<LearningLessonProgress>lambdaQuery()
                        .eq(LearningLessonProgress::getCourseId, courseId));

        long learnerCount = lessons.size();
        long finishedLearnerCount = lessons.stream()
                .filter(l -> Integer.valueOf(Constants.LEARN_STATUS_FINISHED).equals(l.getStatus()))
                .count();
        double finishRate = round1(learnerCount == 0 ? 0.0 : finishedLearnerCount * 100.0 / learnerCount);

        long totalStudySeconds = progresses.stream()
                .map(p -> p.getStudySeconds() == null ? 0L : p.getStudySeconds())
                .reduce(0L, Long::sum);
        long avgStudySeconds = learnerCount == 0 ? 0L : totalStudySeconds / learnerCount;

        LocalDateTime lastStudyTime = lessons.stream()
                .map(LearningLesson::getLastStudyTime)
                .filter(Objects::nonNull)
                .max(LocalDateTime::compareTo)
                .orElse(null);

        List<LearnChapterDTO> chapters = outline.getChapters() == null
                ? List.of()
                : outline.getChapters().stream()
                        .sorted(Comparator.comparing(
                                c -> c.getChapterOrder() == null ? 0 : c.getChapterOrder()))
                        .toList();
        Map<Long, String> chapterNames = chapters.stream()
                .collect(Collectors.toMap(LearnChapterDTO::getId, LearnChapterDTO::getName, (a, b) -> a));

        Map<Long, List<LearningLessonProgress>> byChapter = new HashMap<>();
        for (LearningLessonProgress p : progresses) {
            byChapter.computeIfAbsent(p.getChapterId(), k -> new ArrayList<>()).add(p);
        }
        List<ChapterStatVO> chapterStats = new ArrayList<>();
        for (LearnChapterDTO chapter : chapters) {
            List<LearningLessonProgress> rows = byChapter.getOrDefault(chapter.getId(), List.of());
            long finished = rows.stream()
                    .filter(p -> Integer.valueOf(Constants.LEARN_STATUS_FINISHED).equals(p.getStatus()))
                    .count();
            long seconds = rows.stream()
                    .map(p -> p.getStudySeconds() == null ? 0L : p.getStudySeconds())
                    .reduce(0L, Long::sum);
            chapterStats.add(ChapterStatVO.builder()
                    .chapterId(chapter.getId())
                    .chapterName(chapter.getName())
                    .chapterOrder(chapter.getChapterOrder())
                    .learnerCount((long) rows.size())
                    .finishedCount(finished)
                    .finishRate(round1(rows.isEmpty() ? 0.0 : finished * 100.0 / rows.size()))
                    .totalStudySeconds(seconds)
                    .build());
        }

        List<LearningLesson> recent = lessons.stream()
                .filter(l -> l.getLastStudyTime() != null)
                .sorted(Comparator.comparing(LearningLesson::getLastStudyTime).reversed())
                .limit(10)
                .toList();
        Map<Long, String> usernames = loadUsernames(recent.stream()
                .map(LearningLesson::getUserId).distinct().toList());
        List<RecentStudyVO> recentStudies = new ArrayList<>();
        for (LearningLesson lesson : recent) {
            LearningLessonProgress p = progresses.stream()
                    .filter(x -> Objects.equals(x.getUserId(), lesson.getUserId())
                            && Objects.equals(x.getChapterId(), lesson.getChapterId()))
                    .findFirst().orElse(null);
            recentStudies.add(RecentStudyVO.builder()
                    .userId(lesson.getUserId())
                    .username(usernames.get(lesson.getUserId()))
                    .courseId(courseId)
                    .courseName(outline.getName())
                    .chapterId(lesson.getChapterId())
                    .chapterName(lesson.getChapterId() == null
                            ? null : chapterNames.get(lesson.getChapterId()))
                    .status(lesson.getStatus())
                    .statusText(LearningSupport.statusText(lesson.getStatus()))
                    .progress(lesson.getProgress())
                    .studySeconds(p == null ? 0L : p.getStudySeconds())
                    .lastStudyTime(lesson.getLastStudyTime())
                    .build());
        }

        TeacherStatsVO vo = TeacherStatsVO.builder()
                .courseId(courseId)
                .courseName(outline.getName())
                .free(outline.getFree())
                .learnerCount(learnerCount)
                .finishedLearnerCount(finishedLearnerCount)
                .finishRate(finishRate)
                .chapterCount(chapters.size())
                .totalStudySeconds(totalStudySeconds)
                .avgStudySeconds(avgStudySeconds)
                .lastStudyTime(lastStudyTime)
                .recentStudies(recentStudies)
                .chapterStats(chapterStats)
                .questionCount(countCourseQuestions(courseId, null, null))
                .statsScope("CUMULATIVE")
                .learningCoverage("OK")
                .coverageMessage("累计口径：人数与时长来自学习进度累计值，不是按日增量回放")
                .build();
        applyAdoption(vo, courseId, null, null);
        return vo;
    }

    private TeacherStatsVO statsOfRange(Long courseId, StatsWindow window) {
        CourseOutlineDTO outline = loadOutline(courseId);
        if (outline == null || outline.getId() == null) {
            return null;
        }
        LocalDateTime since = factMapper == null ? null : factMapper.factsSince();
        LocalDate coveredFrom = since == null ? null : StatsWindow.businessDate(since);
        boolean insufficient = coveredFrom == null || window.from().isBefore(coveredFrom);
        List<LearnChapterDTO> chapters = outline.getChapters() == null ? List.of()
                : outline.getChapters().stream()
                .sorted(Comparator.comparing(c -> c.getChapterOrder() == null ? 0 : c.getChapterOrder()))
                .toList();
        TeacherStatsVO.TeacherStatsVOBuilder builder = TeacherStatsVO.builder()
                .courseId(courseId)
                .courseName(outline.getName())
                .free(outline.getFree())
                .chapterCount(chapters.size())
                .statsScope("RANGE")
                .dateFrom(window.from().toString())
                .dateTo(window.to().toString())
                .questionCount(countCourseQuestions(courseId, window.startInclusive(), window.endExclusive()));
        if (insufficient) {
            String sinceText = coveredFrom == null ? "尚未建立学习事实" : coveredFrom.toString();
            builder.learningCoverage("INSUFFICIENT")
                    .coverageMessage("所选区间早于学习事实开始记录日 " + sinceText + "，不能还原历史增量，未把缺失写成 0")
                    .chapterStats(List.of())
                    .recentStudies(List.of());
            TeacherStatsVO vo = builder.build();
            applyAdoption(vo, courseId, window.startInclusive(), window.endExclusive());
            return vo;
        }
        LocalDate from = window.from();
        LocalDate toExclusive = window.to().plusDays(1);
        long learners = nz(factMapper.countLearners(courseId, from, toExclusive));
        long seconds = nz(factMapper.sumSeconds(courseId, from, toExclusive));
        long finished = nz(factMapper.countCourseFirstFinished(courseId, from, toExclusive));
        Map<Long, LearningActivityFactMapper.ChapterFactAgg> byChapter = new HashMap<>();
        for (LearningActivityFactMapper.ChapterFactAgg row : factMapper.chapterAgg(courseId, from, toExclusive)) {
            byChapter.put(row.getChapterId(), row);
        }
        List<ChapterStatVO> chapterStats = new ArrayList<>();
        for (LearnChapterDTO chapter : chapters) {
            LearningActivityFactMapper.ChapterFactAgg row = byChapter.get(chapter.getId());
            long chapterLearners = row == null || row.getLearnerCount() == null ? 0L : row.getLearnerCount();
            long chapterFinished = row == null || row.getFinishedCount() == null ? 0L : row.getFinishedCount();
            long chapterSeconds = row == null || row.getTotalStudySeconds() == null ? 0L : row.getTotalStudySeconds();
            chapterStats.add(ChapterStatVO.builder()
                    .chapterId(chapter.getId())
                    .chapterName(chapter.getName())
                    .chapterOrder(chapter.getChapterOrder())
                    .learnerCount(chapterLearners)
                    .finishedCount(chapterFinished)
                    .finishRate(round1(chapterLearners == 0 ? 0.0 : chapterFinished * 100.0 / chapterLearners))
                    .totalStudySeconds(chapterSeconds)
                    .build());
        }
        List<LearningActivityFactMapper.RecentFactAgg> recent = factMapper.recent(courseId, from, toExclusive);
        Map<Long, String> names = loadUsernames(recent.stream().map(LearningActivityFactMapper.RecentFactAgg::getUserId).toList());
        List<LearningLesson> lessons = lessonMapper.selectList(
                Wrappers.<LearningLesson>lambdaQuery().eq(LearningLesson::getCourseId, courseId));
        Map<Long, LearningLesson> lessonByUser = lessons.stream()
                .collect(Collectors.toMap(LearningLesson::getUserId, l -> l, (a, b) -> a));
        Map<Long, String> chapterNames = chapters.stream()
                .collect(Collectors.toMap(LearnChapterDTO::getId, LearnChapterDTO::getName, (a, b) -> a));
        List<RecentStudyVO> recentStudies = new ArrayList<>();
        for (LearningActivityFactMapper.RecentFactAgg row : recent) {
            LearningLesson lesson = lessonByUser.get(row.getUserId());
            recentStudies.add(RecentStudyVO.builder()
                    .userId(row.getUserId())
                    .username(names.get(row.getUserId()))
                    .courseId(courseId)
                    .courseName(outline.getName())
                    .chapterId(lesson == null ? null : lesson.getChapterId())
                    .chapterName(lesson == null || lesson.getChapterId() == null ? null : chapterNames.get(lesson.getChapterId()))
                    .status(lesson == null ? null : lesson.getStatus())
                    .statusText(lesson == null ? null : LearningSupport.statusText(lesson.getStatus()))
                    .progress(lesson == null ? null : lesson.getProgress())
                    .studySeconds(row.getStudySeconds())
                    .lastStudyTime(row.getActivityTime())
                    .progressIsCurrentSnapshot(Boolean.TRUE)
                    .build());
        }
        LocalDateTime last = recent.stream().map(LearningActivityFactMapper.RecentFactAgg::getActivityTime)
                .filter(Objects::nonNull).max(LocalDateTime::compareTo).orElse(null);
        TeacherStatsVO vo = builder
                .learningCoverage("OK")
                .coverageMessage("区间口径：学习人数、时长和首次完课来自日事实；当前进度列不是历史快照")
                .learnerCount(learners)
                .finishedLearnerCount(finished)
                .finishRate(round1(learners == 0 ? 0.0 : finished * 100.0 / learners))
                .totalStudySeconds(seconds)
                .avgStudySeconds(learners == 0 ? 0L : seconds / learners)
                .lastStudyTime(last)
                .chapterStats(chapterStats)
                .recentStudies(recentStudies)
                .build();
        applyAdoption(vo, courseId, window.startInclusive(), window.endExclusive());
        return vo;
    }

    private CourseOutlineDTO loadOutline(Long courseId) {
        try {
            var result = courseClient.learnOutline(courseId);
            return result == null ? null : result.getData();
        } catch (Exception e) {
            log.error("[learning] course service unavailable: {}", e.getMessage());
            throw new BizException("课程服务暂时不可用，请稍后重试");
        }
    }

    /** 未隐藏提问。传入时间时只计 [start, end) 内创建的提问。失败返回 null。 */
    private Long countCourseQuestions(Long courseId, LocalDateTime start, LocalDateTime end) {
        try {
            var query = new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<InteractionQuestion>()
                    .eq(InteractionQuestion::getCourseId, courseId)
                    .and(w -> w.isNull(InteractionQuestion::getHidden).or().eq(InteractionQuestion::getHidden, 0));
            if (start != null) {
                query.ge(InteractionQuestion::getCreateTime, start);
            }
            if (end != null) {
                query.lt(InteractionQuestion::getCreateTime, end);
            }
            Long n = interactionQuestionMapper.selectCount(query);
            return n == null ? 0L : n;
        } catch (Exception e) {
            log.warn("[learning.stats] questionCount 统计失败（返回 null 而非假数据）: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 采纳率 = 至少有一条有效「有帮助」的 AI 回复数 / 有效 AI 回复数 × 100，保留 1 位小数。
     * 有效：source=SYSTEM_AI，问题与回复均未隐藏，且未被教师修正。
     * 区间模式按回复创建时间筛选，反馈取当前有效记录，不还原历史快照。
     */
    private void applyAdoption(TeacherStatsVO vo, Long courseId, LocalDateTime start, LocalDateTime end) {
        try {
            List<InteractionQuestion> questions = interactionQuestionMapper.selectList(
                    Wrappers.<InteractionQuestion>lambdaQuery()
                            .eq(InteractionQuestion::getCourseId, courseId)
                            .and(w -> w.isNull(InteractionQuestion::getHidden).or().eq(InteractionQuestion::getHidden, 0)));
            List<Long> questionIds = questions.stream().map(InteractionQuestion::getId).toList();
            if (questionIds.isEmpty()) {
                vo.setAiSampleCount(0L);
                vo.setAiAdoptedCount(0L);
                vo.setAiAdoptRate(null);
                vo.setInteractionReady(Boolean.TRUE);
                return;
            }
            var replyQuery = Wrappers.<InteractionReply>lambdaQuery()
                    .in(InteractionReply::getQuestionId, questionIds)
                    .and(w -> w.isNull(InteractionReply::getHidden).or().eq(InteractionReply::getHidden, 0));
            if (start != null) {
                replyQuery.ge(InteractionReply::getCreateTime, start);
            }
            if (end != null) {
                replyQuery.lt(InteractionReply::getCreateTime, end);
            }
            List<InteractionReply> replies = replyMapper.selectList(replyQuery);
            if (replies.isEmpty()) {
                vo.setAiSampleCount(0L);
                vo.setAiAdoptedCount(0L);
                vo.setAiAdoptRate(null);
                vo.setInteractionReady(Boolean.TRUE);
                return;
            }
            List<Long> replyIds = replies.stream().map(InteractionReply::getId).toList();
            List<InteractionReplyExtra> extras = replyExtraMapper.selectList(
                    Wrappers.<InteractionReplyExtra>lambdaQuery().in(InteractionReplyExtra::getReplyId, replyIds));
            List<Long> aiIds = extras.stream()
                    .filter(ex -> InteractionReplyExtra.SOURCE_SYSTEM_AI.equals(ex.getSource()))
                    .filter(ex -> ex.getCorrected() == null || ex.getCorrected() != 1)
                    .map(InteractionReplyExtra::getReplyId)
                    .distinct()
                    .toList();
            if (aiIds.isEmpty()) {
                vo.setAiSampleCount(0L);
                vo.setAiAdoptedCount(0L);
                vo.setAiAdoptRate(null);
                vo.setInteractionReady(Boolean.TRUE);
                return;
            }
            List<InteractionReplyHelpful> marks = helpfulMapper.selectList(
                    Wrappers.<InteractionReplyHelpful>lambdaQuery().in(InteractionReplyHelpful::getReplyId, aiIds));
            long adopted = marks.stream().map(InteractionReplyHelpful::getReplyId).distinct().count();
            vo.setAiSampleCount((long) aiIds.size());
            vo.setAiAdoptedCount(adopted);
            vo.setAiAdoptRate(round1(adopted * 100.0 / aiIds.size()));
            vo.setInteractionReady(Boolean.TRUE);
        } catch (Exception e) {
            log.warn("[learning.stats] 采纳率统计失败（不写成 0）: {}", e.getMessage());
            vo.setAiSampleCount(null);
            vo.setAiAdoptedCount(null);
            vo.setAiAdoptRate(null);
            vo.setInteractionReady(Boolean.FALSE);
        }
    }

    private static long nz(Long value) {
        return value == null ? 0L : value;
    }

    private Map<Long, String> loadUsernames(List<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        try {
            var result = userClient.names(ids);
            Map<Long, String> names = result == null ? null : result.getData();
            return names == null ? Map.of() : names;
        } catch (Exception e) {
            log.warn("[learning] user service unavailable for usernames: {}", e.getMessage());
            return Map.of();
        }
    }

    private static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}
