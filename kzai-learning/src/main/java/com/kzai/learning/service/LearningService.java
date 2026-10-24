package com.kzai.learning.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kzai.common.constant.Constants;
import com.kzai.common.exception.AuthException;
import com.kzai.common.exception.BizException;
import com.kzai.common.result.ResultCode;
import com.kzai.learning.client.CourseClient;
import com.kzai.learning.dto.CourseOutlineDTO;
import com.kzai.learning.dto.EntitlementDecision;
import com.kzai.learning.dto.LearnChapterDTO;
import com.kzai.learning.dto.LearnResourceDTO;
import com.kzai.learning.dto.LessonQuery;
import com.kzai.learning.dto.ProgressReportRequest;
import com.kzai.learning.entity.LearningLesson;
import com.kzai.learning.entity.LearningLessonProgress;
import com.kzai.learning.mapper.LearningLessonMapper;
import com.kzai.learning.mapper.LearningLessonProgressMapper;
import com.kzai.learning.vo.AccessVO;
import com.kzai.learning.vo.ChapterLearnVO;
import com.kzai.learning.vo.LessonDetailVO;
import com.kzai.learning.vo.LessonVO;
import com.kzai.learning.vo.ResourceLearnVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 学习服务（M5）：入课、我的学习、课程学习、进度上报与断点续学。
 * <p>
 * 约束：
 * <ul>
 *   <li>用户身份只来自服务端认证上下文（UserContext），接口不接受 userId 入参；</li>
 *   <li>课程/章节/资源一律经课程服务内部接口获取，学习服务不复制课程业务数据；</li>
 *   <li>学习权益由 {@link EntitlementService} 依据真实订单判定，不采信前端传入的任何权益字段；</li>
 *   <li>课程完成百分比由服务端按"已完成章节数 / 实际章节数"计算。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LearningService {

    private final LearningLessonMapper lessonMapper;
    private final LearningLessonProgressMapper progressMapper;
    private final CourseClient courseClient;
    private final EntitlementService entitlementService;
    private final LearningFactRecorder factRecorder;

    @Value("${kzai.learning.max-study-seconds-per-report:900}")
    private int maxStudySecondsPerReport;

    @Value("${kzai.learning.chapter-finish-threshold:95}")
    private int chapterFinishThreshold;

    /** 有效时长容差（秒）：吸收上报间隔的网络抖动，超过该值的跳跃按未真实观看处理 */
    @Value("${kzai.learning.study-seconds-tolerance:3}")
    private int studySecondsTolerance;

    // ------------------------------------------------------------------ 入课

    /**
     * 加入学习（免费入课 / 已购课程建档）。
     * <p>
     * 免费课程：允许学员加入，写入真实 learning_lesson 记录，绝不生成伪造支付订单。
     * 付费课程：必须已取得真实学习权益后才允许建档，否则 403。
     * 重复入课：按 (user_id, course_id) 唯一返回既有记录，不产生重复行。
     */
    @Transactional
    public LessonVO enroll(Long userId, Long courseId) {
        if (courseId == null || courseId <= 0) {
            throw new BizException("课程ID非法");
        }
        CourseOutlineDTO outline = requireOutline(courseId);
        EntitlementDecision decision = entitlementService.resolve(userId, outline);
        if (!Boolean.TRUE.equals(decision.getAllowed())) {
            throw new AuthException(ResultCode.FORBIDDEN,
                    StringUtils.hasText(decision.getReason()) ? decision.getReason() : "未取得该课程的学习权益");
        }
        LearningLesson existing = findLesson(userId, courseId);
        if (existing == null) {
            LocalDateTime now = LocalDateTime.now();
            LearningLesson lesson = new LearningLesson();
            lesson.setUserId(userId);
            lesson.setCourseId(courseId);
            lesson.setChapterId(firstChapterId(outline));
            lesson.setSectionId(null);
            lesson.setStatus(Constants.LEARN_STATUS_LEARNING);
            lesson.setProgress(0);
            lesson.setLastStudyTime(now);
            lesson.setCreateTime(now);
            lesson.setUpdateTime(now);
            lessonMapper.insert(lesson);
            existing = lesson;
            log.info("[learning] enroll: user={} course={} source={}", userId, courseId, decision.getSource());
        }
        return toLessonVO(existing, outline, decision);
    }

    // ------------------------------------------------------------ 我的学习

    public IPage<LessonVO> page(Long userId, LessonQuery query) {
        int pageNum = query.getPageNum() == null || query.getPageNum() < 1 ? 1 : query.getPageNum();
        int pageSize = query.getPageSize() == null || query.getPageSize() < 1 ? 10 : query.getPageSize();

        var wrapper = Wrappers.<LearningLesson>lambdaQuery().eq(LearningLesson::getUserId, userId);
        if (query.getStatus() != null) {
            wrapper.eq(LearningLesson::getStatus, query.getStatus());
        }
        wrapper.orderByDesc(LearningLesson::getLastStudyTime);
        List<LearningLesson> lessons = lessonMapper.selectList(wrapper);

        List<LessonVO> matched = new ArrayList<>();
        for (LearningLesson lesson : lessons) {
            CourseOutlineDTO outline = loadOutline(lesson.getCourseId());
            String keyword = query.getKeyword();
            if (StringUtils.hasText(keyword) && (outline == null
                    || !contains(outline.getName(), keyword))) {
                continue;
            }
            EntitlementDecision decision = entitlementService.resolve(userId, outline);
            if (refundedCourse(decision)) {
                continue;
            }
            LessonVO vo = toLessonVO(lesson, outline, decision);
            vo.setEntitlementValid(Boolean.TRUE.equals(decision.getAllowed()));
            matched.add(vo);
        }
        int from = Math.min((pageNum - 1) * pageSize, matched.size());
        int to = Math.min(from + pageSize, matched.size());
        Page<LessonVO> result = new Page<>(pageNum, pageSize, matched.size());
        result.setRecords(new ArrayList<>(matched.subList(from, to)));
        return result;
    }

    /** 明细已全额退款的课程不再出现在我的学习。退款申请中、驳回、到期仍保留。 */
    private boolean refundedCourse(EntitlementDecision decision) {
        return decision != null
                && !Boolean.TRUE.equals(decision.getAllowed())
                && Integer.valueOf(Constants.REFUND_SUCCESS).equals(decision.getRefundStatus());
    }

    // -------------------------------------------------------- 课程学习详情

    public LessonDetailVO detail(Long userId, Long courseId) {
        LearningLesson lesson = findLesson(userId, courseId);
        CourseOutlineDTO outline = requireOutline(courseId);
        EntitlementDecision decision = entitlementService.resolve(userId, outline);
        if (lesson == null && !Boolean.TRUE.equals(decision.getAllowed())) {
            throw new AuthException(ResultCode.FORBIDDEN,
                    StringUtils.hasText(decision.getReason()) ? decision.getReason() : "未取得该课程的学习权益");
        }
        List<LearnChapterDTO> chapters = sortedChapters(outline);
        Map<Long, LearningLessonProgress> progressMap = progressMap(userId, courseId);
        List<ChapterLearnVO> chapterVOs = buildChapters(chapters, outline, decision, progressMap);

        long finished = chapterVOs.stream()
                .filter(c -> Integer.valueOf(Constants.LEARN_STATUS_FINISHED).equals(c.getStatus()))
                .count();
        int progress = LearningSupport.percent(finished, chapters.size());

        Long lastChapterId = lesson == null ? null : lesson.getChapterId();
        LearningLessonProgress lastProgress = lastChapterId == null ? null : progressMap.get(lastChapterId);
        Long prev = null;
        Long next = null;
        if (lastChapterId != null) {
            for (int i = 0; i < chapters.size(); i++) {
                if (Objects.equals(chapters.get(i).getId(), lastChapterId)) {
                    if (i > 0) {
                        prev = chapters.get(i - 1).getId();
                    }
                    if (i < chapters.size() - 1) {
                        next = chapters.get(i + 1).getId();
                    }
                    break;
                }
            }
        }
        if (lesson == null) {
            lastChapterId = chapters.isEmpty() ? null : chapters.get(0).getId();
        }

        Long studySeconds = progressMap.values().stream()
                .map(p -> p.getStudySeconds() == null ? 0L : p.getStudySeconds())
                .reduce(0L, Long::sum);

        return LessonDetailVO.builder()
                .courseId(courseId)
                .courseName(outline.getName())
                .coverUrl(outline.getCoverUrl())
                .free(outline.getFree())
                .introduce(outline.getIntroduce())
                .status(lesson == null ? Constants.LEARN_STATUS_NOT_STARTED : lesson.getStatus())
                .statusText(LearningSupport.statusText(lesson == null
                        ? Constants.LEARN_STATUS_NOT_STARTED : lesson.getStatus()))
                .progress(progress)
                .totalChapterCount(chapters.size())
                .finishedChapterCount((int) finished)
                .lastChapterId(lastChapterId)
                .lastChapterName(chapterName(chapters, lastChapterId))
                .lastVideoPositionSec(lastProgress == null ? 0 : nullSafe(lastProgress.getVideoPositionSec()))
                .studySeconds(studySeconds)
                .lastStudyTime(lesson == null ? null : lesson.getLastStudyTime())
                .entitlementValid(Boolean.TRUE.equals(decision.getAllowed()))
                .entitlementSource(decision.getSource())
                .entitlementReason(decision.getReason())
                .expireTime(decision.getExpireTime())
                .prevChapterId(prev)
                .nextChapterId(next)
                .chapters(chapterVOs)
                .build();
    }

    /** 章节学习详情：服务端重新校验权益与章节归属，未授权章节返回 403 */
    public ChapterLearnVO chapterDetail(Long userId, Long courseId, Long chapterId) {
        CourseOutlineDTO outline = requireOutline(courseId);
        EntitlementDecision decision = entitlementService.resolve(userId, outline);
        List<LearnChapterDTO> chapters = sortedChapters(outline);
        LearnChapterDTO chapter = chapters.stream()
                .filter(c -> Objects.equals(c.getId(), chapterId))
                .findFirst()
                .orElseThrow(() -> new BizException("章节不存在或不属于该课程"));
        Map<Long, LearningLessonProgress> progressMap = progressMap(userId, courseId);
        ChapterLearnVO vo = buildChapter(chapter, outline, decision, progressMap.get(chapterId));
        if (!Boolean.TRUE.equals(vo.getAccessible())) {
            throw new AuthException(ResultCode.FORBIDDEN,
                    StringUtils.hasText(vo.getLockReason()) ? vo.getLockReason() : "无权学习该章节，请先购买课程");
        }
        return vo;
    }

    // ------------------------------------------------------------ 权益查询

    public AccessVO access(Long userId, Long courseId) {
        CourseOutlineDTO outline = requireOutline(courseId);
        EntitlementDecision decision = entitlementService.resolve(userId, outline);
        LearningLesson lesson = findLesson(userId, courseId);
        return AccessVO.builder()
                .courseId(courseId)
                .courseName(outline.getName())
                .free(outline.getFree() == null ? null : outline.getFree() != 0)
                .allowed(Boolean.TRUE.equals(decision.getAllowed()))
                .source(decision.getSource())
                .sourceText(LearningSupport.sourceText(decision.getSource()))
                .reason(decision.getReason())
                .expireTime(decision.getExpireTime())
                .enrolled(lesson != null)
                .courseVisible(Boolean.TRUE.equals(outline.getStudentVisible()))
                .build();
    }

    // ------------------------------------------------------------ 进度上报

    /**
     * 学习进度上报：真实写入 MySQL，服务端计算课程进度。
     * <p>
     * 幂等与防倒退：
     * <ul>
     *   <li>断点位置取 max(已存位置, 上报位置)，重复上报不倒退；</li>
     *   <li>有效学习时长只按"位置真实前进量"累计，重复上报同一位置不重复累计；</li>
     *   <li>单次上报的增量受 {@code maxStudySecondsPerReport} 限制，防止伪造时长；</li>
     *   <li><b>拖动进度条造成的跳跃不得计入有效时长</b>：单次增量同时受"距上次上报的真实流逝时间 +
     *       {@code studySecondsTolerance}"约束——位置前进了 10 分钟但两次上报只隔 5 秒，
     *      只认 5 秒（含容差），不把拖动差当作真实观看时长。</li>
     * </ul>
     */
    @Transactional
    public LessonDetailVO reportProgress(Long userId, Long courseId, ProgressReportRequest request) {
        if (request == null || request.getChapterId() == null) {
            throw new BizException("章ID不能为空");
        }
        CourseOutlineDTO outline = requireOutline(courseId);
        EntitlementDecision decision = entitlementService.resolve(userId, outline);
        if (!Boolean.TRUE.equals(decision.getAllowed())) {
            // 仅试看的未购学员不记录学习进度
            throw new AuthException(ResultCode.FORBIDDEN,
                    StringUtils.hasText(decision.getReason()) ? decision.getReason() : "未取得该课程的学习权益");
        }
        List<LearnChapterDTO> chapters = sortedChapters(outline);
        LearnChapterDTO chapter = chapters.stream()
                .filter(c -> Objects.equals(c.getId(), request.getChapterId()))
                .findFirst()
                .orElseThrow(() -> new BizException("章节不存在或不属于该课程"));

        int duration = positive(request.getVideoDurationSec());
        if (duration == 0 && chapter.getVideoDurationSec() != null) {
            duration = Math.max(0, chapter.getVideoDurationSec());
        }
        int reportedPosition = positive(request.getVideoPositionSec());
        if (duration > 0) {
            reportedPosition = Math.min(reportedPosition, duration);
        }

        LocalDateTime now = LocalDateTime.now();
        LearningLessonProgress progress = progressMapper.selectOne(
                Wrappers.<LearningLessonProgress>lambdaQuery()
                        .eq(LearningLessonProgress::getUserId, userId)
                        .eq(LearningLessonProgress::getCourseId, courseId)
                        .eq(LearningLessonProgress::getChapterId, chapter.getId()));

        if (progress == null) {
            progress = new LearningLessonProgress();
            progress.setUserId(userId);
            progress.setCourseId(courseId);
            progress.setChapterId(chapter.getId());
            progress.setStatus(Constants.LEARN_STATUS_LEARNING);
            progress.setProgress(0);
            progress.setVideoPositionSec(0);
            progress.setVideoDurationSec(duration > 0 ? duration : null);
            progress.setStudySeconds(0L);
            progress.setCreateTime(now);
            progress.setUpdateTime(now);
        }

        int storedPosition = nullSafe(progress.getVideoPositionSec());
        boolean chapterWasFinished = progress.getId() != null
                && (Integer.valueOf(Constants.LEARN_STATUS_FINISHED).equals(progress.getStatus())
                || progress.getFinishTime() != null);
        long secondsBefore = progress.getId() == null ? 0L
                : (progress.getStudySeconds() == null ? 0L : progress.getStudySeconds());
        int newPosition = Math.max(storedPosition, reportedPosition);
        long delta = effectiveDelta(progress.getUpdateTime() == null
                ? progress.getCreateTime() : progress.getUpdateTime(), now, newPosition, storedPosition);
        long studySeconds = (progress.getStudySeconds() == null ? 0L : progress.getStudySeconds()) + delta;

        int chapterProgress = duration > 0
                ? (int) Math.min(100, newPosition * 100L / duration)
                : (Boolean.TRUE.equals(request.getCompleted()) ? 100 : nullSafe(progress.getProgress()));

        boolean finished;
        if (duration > 0) {
            // 带视频的章节：以服务端掌握的播放位置为准，前端 completed 不能单独生效
            finished = newPosition * 100L >= (long) chapterFinishThreshold * duration;
        } else {
            finished = Boolean.TRUE.equals(request.getCompleted())
                    || Integer.valueOf(Constants.LEARN_STATUS_FINISHED).equals(progress.getStatus());
        }

        progress.setVideoPositionSec(newPosition);
        if (duration > 0) {
            progress.setVideoDurationSec(duration);
        }
        progress.setStudySeconds(studySeconds);
        progress.setProgress(finished ? 100 : Math.min(99, chapterProgress));
        progress.setStatus(finished ? Constants.LEARN_STATUS_FINISHED : Constants.LEARN_STATUS_LEARNING);
        progress.setFinishTime(finished
                ? (progress.getFinishTime() == null ? now : progress.getFinishTime())
                : null);
        progress.setUpdateTime(now);

        long persistedDelta;
        boolean chapterFirst;
        if (progress.getId() == null) {
            progressMapper.insert(progress);
            persistedDelta = progress.getStudySeconds() == null ? 0L : progress.getStudySeconds();
            chapterFirst = finished;
        } else {
            // 条件更新（乐观并发）：位置已被其他请求推进时按最新值重算。
            // 日事实只记最终写进数据库的秒数差。
            int rows = progressMapper.update(null,
                    Wrappers.<LearningLessonProgress>lambdaUpdate()
                            .set(LearningLessonProgress::getVideoPositionSec, progress.getVideoPositionSec())
                            .set(LearningLessonProgress::getVideoDurationSec, progress.getVideoDurationSec())
                            .set(LearningLessonProgress::getStudySeconds, progress.getStudySeconds())
                            .set(LearningLessonProgress::getProgress, progress.getProgress())
                            .set(LearningLessonProgress::getStatus, progress.getStatus())
                            .set(LearningLessonProgress::getFinishTime, progress.getFinishTime())
                            .set(LearningLessonProgress::getUpdateTime, now)
                            .eq(LearningLessonProgress::getId, progress.getId())
                            .eq(LearningLessonProgress::getVideoPositionSec, storedPosition));
            if (rows == 1) {
                persistedDelta = Math.max(0L, (progress.getStudySeconds() == null ? 0L : progress.getStudySeconds()) - secondsBefore);
                chapterFirst = finished && !chapterWasFinished;
            } else {
                persistedDelta = 0L;
                chapterFirst = false;
                // 行锁读到对方已提交的进度，再按这个位置做条件更新。更新没写上就不记日事实。
                LearningLessonProgress latest = progressMapper.selectOne(
                        Wrappers.<LearningLessonProgress>lambdaQuery()
                                .eq(LearningLessonProgress::getId, progress.getId())
                                .last("FOR UPDATE"));
                if (latest != null) {
                    boolean latestFinished = Integer.valueOf(Constants.LEARN_STATUS_FINISHED).equals(latest.getStatus())
                            || latest.getFinishTime() != null;
                    int lockedPosition = nullSafe(latest.getVideoPositionSec());
                    int mergedPosition = Math.max(lockedPosition, newPosition);
                    long latestSeconds = latest.getStudySeconds() == null ? 0L : latest.getStudySeconds();
                    long mergedDelta = effectiveDelta(latest.getUpdateTime() == null
                            ? latest.getCreateTime() : latest.getUpdateTime(), now,
                            mergedPosition, lockedPosition);
                    long mergedStudy = latestSeconds + mergedDelta;
                    int mergedRows = progressMapper.update(null,
                            Wrappers.<LearningLessonProgress>lambdaUpdate()
                                    .set(LearningLessonProgress::getVideoPositionSec, mergedPosition)
                                    .set(LearningLessonProgress::getStudySeconds, mergedStudy)
                                    .set(LearningLessonProgress::getProgress,
                                            finished ? 100 : Math.min(99, chapterProgress))
                                    .set(LearningLessonProgress::getStatus,
                                            finished ? Constants.LEARN_STATUS_FINISHED
                                                    : Constants.LEARN_STATUS_LEARNING)
                                    .set(LearningLessonProgress::getFinishTime,
                                            finished ? (latest.getFinishTime() == null ? now : latest.getFinishTime()) : latest.getFinishTime())
                                    .set(LearningLessonProgress::getUpdateTime, now)
                                    .eq(LearningLessonProgress::getId, progress.getId())
                                    .eq(LearningLessonProgress::getVideoPositionSec, lockedPosition));
                    if (mergedRows != 1) {
                        throw new BizException("学习进度并发写入失败，请重试");
                    }
                    persistedDelta = mergedDelta;
                    chapterFirst = finished && !latestFinished;
                }
            }
        }

        // 课程级状态：进度百分比一律由服务端按已完成章节数计算
        Map<Long, LearningLessonProgress> progressMap = progressMap(userId, courseId);
        long finishedCount = progressMap.values().stream()
                .filter(p -> Integer.valueOf(Constants.LEARN_STATUS_FINISHED).equals(p.getStatus()))
                .count();
        int courseProgress = LearningSupport.percent(finishedCount, chapters.size());
        int courseStatus = (!chapters.isEmpty() && finishedCount >= chapters.size())
                ? Constants.LEARN_STATUS_FINISHED : Constants.LEARN_STATUS_LEARNING;

        LearningLesson lesson = findLesson(userId, courseId);
        boolean courseWasFinished = lesson != null
                && Integer.valueOf(Constants.LEARN_STATUS_FINISHED).equals(lesson.getStatus());
        if (lesson == null) {
            lesson = new LearningLesson();
            lesson.setUserId(userId);
            lesson.setCourseId(courseId);
            lesson.setSectionId(null);
            lesson.setCreateTime(now);
            lesson.setStatus(courseStatus);
            lesson.setProgress(courseProgress);
            lesson.setChapterId(chapter.getId());
            lesson.setLastStudyTime(now);
            lesson.setUpdateTime(now);
            lessonMapper.insert(lesson);
        } else {
            lessonMapper.update(null, Wrappers.<LearningLesson>lambdaUpdate()
                    .set(LearningLesson::getChapterId, chapter.getId())
                    .set(LearningLesson::getStatus, courseStatus)
                    .set(LearningLesson::getProgress, courseProgress)
                    .set(LearningLesson::getLastStudyTime, now)
                    .set(LearningLesson::getUpdateTime, now)
                    .eq(LearningLesson::getId, lesson.getId()));
        }
        factRecorder.record(userId, courseId, chapter.getId(), persistedDelta, chapterFirst,
                courseStatus == Constants.LEARN_STATUS_FINISHED && !courseWasFinished, now);
        return detail(userId, courseId);
    }

    /** 显式标记章节完成（学员主动操作，仍需服务端校验归属与权益） */
    @Transactional
    public LessonDetailVO completeChapter(Long userId, Long courseId, Long chapterId) {
        CourseOutlineDTO outline = requireOutline(courseId);
        EntitlementDecision decision = entitlementService.resolve(userId, outline);
        if (!Boolean.TRUE.equals(decision.getAllowed())) {
            throw new AuthException(ResultCode.FORBIDDEN,
                    StringUtils.hasText(decision.getReason()) ? decision.getReason() : "未取得该课程的学习权益");
        }
        List<LearnChapterDTO> chapters = sortedChapters(outline);
        LearnChapterDTO chapter = chapters.stream()
                .filter(c -> Objects.equals(c.getId(), chapterId))
                .findFirst()
                .orElseThrow(() -> new BizException("章节不存在或不属于该课程"));
        LearningLessonProgress progress = progressMapper.selectOne(
                Wrappers.<LearningLessonProgress>lambdaQuery()
                        .eq(LearningLessonProgress::getUserId, userId)
                        .eq(LearningLessonProgress::getCourseId, courseId)
                        .eq(LearningLessonProgress::getChapterId, chapterId));
        LocalDateTime now = LocalDateTime.now();
        boolean chapterWasFinished = progress != null
                && (Integer.valueOf(Constants.LEARN_STATUS_FINISHED).equals(progress.getStatus())
                || progress.getFinishTime() != null);
        LearningLesson existingLesson = findLesson(userId, courseId);
        boolean courseWasFinished = existingLesson != null
                && Integer.valueOf(Constants.LEARN_STATUS_FINISHED).equals(existingLesson.getStatus());
        if (progress == null) {
            progress = new LearningLessonProgress();
            progress.setUserId(userId);
            progress.setCourseId(courseId);
            progress.setChapterId(chapterId);
            progress.setStudySeconds(0L);
            progress.setVideoPositionSec(0);
            progress.setCreateTime(now);
        }
        progress.setStatus(Constants.LEARN_STATUS_FINISHED);
        progress.setProgress(100);
        if (progress.getVideoDurationSec() != null && progress.getVideoDurationSec() > 0) {
            progress.setVideoPositionSec(progress.getVideoDurationSec());
        }
        progress.setFinishTime(progress.getFinishTime() == null ? now : progress.getFinishTime());
        progress.setUpdateTime(now);
        if (progress.getId() == null) {
            progressMapper.insert(progress);
        } else {
            progressMapper.updateById(progress);
        }
        LessonDetailVO detail = reportLessonAggregate(userId, courseId, chapters, chapterId, now);
        long finishedCount = progressMap(userId, courseId).values().stream()
                .filter(p -> Integer.valueOf(Constants.LEARN_STATUS_FINISHED).equals(p.getStatus()))
                .count();
        boolean courseNowFinished = !chapters.isEmpty() && finishedCount >= chapters.size();
        factRecorder.record(userId, courseId, chapterId, 0L, !chapterWasFinished,
                courseNowFinished && !courseWasFinished, now);
        return detail;
    }

    private LessonDetailVO reportLessonAggregate(Long userId, Long courseId,
                                                 List<LearnChapterDTO> chapters,
                                                 Long chapterId, LocalDateTime now) {
        Map<Long, LearningLessonProgress> progressMap = progressMap(userId, courseId);
        long finishedCount = progressMap.values().stream()
                .filter(p -> Integer.valueOf(Constants.LEARN_STATUS_FINISHED).equals(p.getStatus()))
                .count();
        int courseProgress = LearningSupport.percent(finishedCount, chapters.size());
        int courseStatus = (!chapters.isEmpty() && finishedCount >= chapters.size())
                ? Constants.LEARN_STATUS_FINISHED : Constants.LEARN_STATUS_LEARNING;
        LearningLesson lesson = findLesson(userId, courseId);
        if (lesson == null) {
            lesson = new LearningLesson();
            lesson.setUserId(userId);
            lesson.setCourseId(courseId);
            lesson.setSectionId(null);
            lesson.setStatus(courseStatus);
            lesson.setProgress(courseProgress);
            lesson.setChapterId(chapterId);
            lesson.setLastStudyTime(now);
            lesson.setCreateTime(now);
            lesson.setUpdateTime(now);
            lessonMapper.insert(lesson);
        } else {
            lessonMapper.update(null, Wrappers.<LearningLesson>lambdaUpdate()
                    .set(LearningLesson::getChapterId, chapterId)
                    .set(LearningLesson::getStatus, courseStatus)
                    .set(LearningLesson::getProgress, courseProgress)
                    .set(LearningLesson::getLastStudyTime, now)
                    .set(LearningLesson::getUpdateTime, now)
                    .eq(LearningLesson::getId, lesson.getId()));
        }
        return detail(userId, courseId);
    }

    // ------------------------------------------------------------ 内部契约

    /** 删除用户前的学习关联记录数 */
    public long countByUser(Long userId) {
        Long count = lessonMapper.selectCount(
                Wrappers.<LearningLesson>lambdaQuery().eq(LearningLesson::getUserId, userId));
        return count == null ? 0L : count;
    }

    // ------------------------------------------------------------ 私有方法

    private LearningLesson findLesson(Long userId, Long courseId) {
        List<LearningLesson> records = lessonMapper.selectList(
                Wrappers.<LearningLesson>lambdaQuery()
                        .eq(LearningLesson::getUserId, userId)
                        .eq(LearningLesson::getCourseId, courseId));
        return records.isEmpty() ? null : records.get(0);
    }

    private Map<Long, LearningLessonProgress> progressMap(Long userId, Long courseId) {
        List<LearningLessonProgress> list = progressMapper.selectList(
                Wrappers.<LearningLessonProgress>lambdaQuery()
                        .eq(LearningLessonProgress::getUserId, userId)
                        .eq(LearningLessonProgress::getCourseId, courseId));
        return list.stream().collect(Collectors.toMap(
                LearningLessonProgress::getChapterId, Function.identity(), (a, b) -> a));
    }

    private CourseOutlineDTO requireOutline(Long courseId) {
        CourseOutlineDTO outline = loadOutline(courseId);
        if (outline == null || outline.getId() == null) {
            throw new BizException("课程不存在");
        }
        return outline;
    }

    private CourseOutlineDTO loadOutline(Long courseId) {
        try {
            var result = courseClient.learnOutline(courseId);
            return result == null ? null : result.getData();
        } catch (Exception e) {
            log.error("[learning] course service unavailable: courseId={} msg={}", courseId, e.getMessage());
            throw new BizException("课程服务暂时不可用，请稍后重试");
        }
    }

    private static List<LearnChapterDTO> sortedChapters(CourseOutlineDTO outline) {
        if (outline == null || outline.getChapters() == null) {
            return List.of();
        }
        return outline.getChapters().stream()
                .sorted(Comparator.comparing(c -> c.getChapterOrder() == null ? 0 : c.getChapterOrder()))
                .toList();
    }

    /** 章节可访问性：已取得权益 → 全部可访问；否则仅"明确标记为可试看"的章节可访问 */
    private List<ChapterLearnVO> buildChapters(List<LearnChapterDTO> chapters,
                                               CourseOutlineDTO outline,
                                               EntitlementDecision decision,
                                               Map<Long, LearningLessonProgress> progressMap) {
        List<ChapterLearnVO> vos = new ArrayList<>();
        for (LearnChapterDTO chapter : chapters) {
            vos.add(buildChapter(chapter, outline, decision, progressMap.get(chapter.getId())));
        }
        return vos;
    }

    private ChapterLearnVO buildChapter(LearnChapterDTO chapter,
                                        CourseOutlineDTO outline,
                                        EntitlementDecision decision,
                                        LearningLessonProgress progress) {
        boolean entitled = Boolean.TRUE.equals(decision.getAllowed());
        boolean previewAllowed = !entitled && Boolean.TRUE.equals(chapter.getFreePreview());
        boolean accessible = entitled || previewAllowed;
        String lockReason = null;
        if (!accessible) {
            lockReason = StringUtils.hasText(decision.getReason())
                    ? decision.getReason() : "该章节为付费内容，请先购买课程";
        }
        List<ResourceLearnVO> resources = new ArrayList<>();
        if (accessible && outline.getResources() != null) {
            for (LearnResourceDTO res : outline.getResources()) {
                // 封面始终可见；其余资料只暴露给可访问章节
                boolean cover = Integer.valueOf(Constants.RES_KIND_COVER).equals(res.getKind());
                if (!cover && !Objects.equals(res.getChapterId(), chapter.getId())) {
                    continue;
                }
                resources.add(ResourceLearnVO.builder()
                        .id(res.getId())
                        .chapterId(res.getChapterId())
                        .kind(res.getKind())
                        .kindText(kindText(res.getKind()))
                        .fileName(res.getFileName())
                        .fileUrl(res.getFileUrl())
                        .fileSize(res.getFileSize())
                        .contentType(res.getContentType())
                        .build());
            }
        }
        Integer status = progress == null ? Constants.LEARN_STATUS_NOT_STARTED : progress.getStatus();
        Integer pos = progress == null ? 0 : nullSafe(progress.getVideoPositionSec());
        return ChapterLearnVO.builder()
                .id(chapter.getId())
                .name(chapter.getName())
                .chapterOrder(chapter.getChapterOrder())
                .description(chapter.getDescription())
                .freePreview(Boolean.TRUE.equals(chapter.getFreePreview()))
                .accessible(accessible)
                .lockReason(lockReason)
                .status(status)
                .statusText(LearningSupport.statusText(status))
                .progress(progress == null ? 0 : progress.getProgress())
                .videoPositionSec(accessible ? pos : 0)
                .videoDurationSec(progress == null
                        ? chapter.getVideoDurationSec() : progress.getVideoDurationSec())
                .studySeconds(progress == null ? 0L : progress.getStudySeconds())
                .finishTime(progress == null ? null : progress.getFinishTime())
                .resources(resources)
                .build();
    }

    private LessonVO toLessonVO(LearningLesson lesson, CourseOutlineDTO outline,
                                EntitlementDecision decision) {
        List<LearnChapterDTO> chapters = sortedChapters(outline);
        Map<Long, LearningLessonProgress> progressMap = progressMap(lesson.getUserId(), lesson.getCourseId());
        long finished = progressMap.values().stream()
                .filter(p -> Integer.valueOf(Constants.LEARN_STATUS_FINISHED).equals(p.getStatus()))
                .count();
        long studySeconds = progressMap.values().stream()
                .map(p -> p.getStudySeconds() == null ? 0L : p.getStudySeconds())
                .reduce(0L, Long::sum);
        LearningLessonProgress lastProgress = lesson.getChapterId() == null
                ? null : progressMap.get(lesson.getChapterId());
        return LessonVO.builder()
                .id(lesson.getId())
                .courseId(lesson.getCourseId())
                .courseName(outline == null ? null : outline.getName())
                .coverUrl(outline == null ? null : outline.getCoverUrl())
                .free(outline == null ? null : outline.getFree())
                .status(lesson.getStatus())
                .statusText(LearningSupport.statusText(lesson.getStatus()))
                .progress(LearningSupport.percent(finished, chapters.size()))
                .totalChapterCount(chapters.size())
                .finishedChapterCount((int) finished)
                .lastChapterId(lesson.getChapterId())
                .lastChapterName(chapterName(chapters, lesson.getChapterId()))
                .lastVideoPositionSec(lastProgress == null ? 0 : nullSafe(lastProgress.getVideoPositionSec()))
                .studySeconds(studySeconds)
                .entitlementValid(Boolean.TRUE.equals(decision.getAllowed()))
                .entitlementSource(decision.getSource())
                .expireTime(decision.getExpireTime())
                .lastStudyTime(lesson.getLastStudyTime())
                .createTime(lesson.getCreateTime())
                .build();
    }

    private static String chapterName(List<LearnChapterDTO> chapters, Long chapterId) {
        if (chapterId == null) {
            return null;
        }
        return chapters.stream()
                .filter(c -> Objects.equals(c.getId(), chapterId))
                .map(LearnChapterDTO::getName)
                .findFirst().orElse(null);
    }

    private static Long firstChapterId(CourseOutlineDTO outline) {
        List<LearnChapterDTO> chapters = sortedChapters(outline);
        return chapters.isEmpty() ? null : chapters.get(0).getId();
    }

    private static boolean contains(String text, String keyword) {
        return text != null && text.contains(keyword.trim());
    }

    private static int nullSafe(Integer value) {
        return value == null ? 0 : value;
    }

    private static int positive(Integer value) {
        return value == null || value < 0 ? 0 : value;
    }

    /**
     * 计算本次上报可计入的有效学习时长（秒）。
     * <p>
     * 取「位置前进量」与「距上次上报的真实流逝时间 + 容差」的较小者，再受单次上限约束。
     * 拖动进度条产生的大幅位置跳跃不会带来对应的有效时长。
     */
    private long effectiveDelta(LocalDateTime lastUpdate, LocalDateTime now, int newPosition, int storedPosition) {
        long advance = (long) newPosition - storedPosition;
        if (advance <= 0) {
            return 0;
        }
        long elapsed = 0;
        if (lastUpdate != null && now != null) {
            elapsed = Math.max(0, java.time.Duration.between(lastUpdate, now).getSeconds());
        }
        long allowed = Math.min(advance, elapsed + Math.max(0, studySecondsTolerance));
        return Math.min(allowed, maxStudySecondsPerReport);
    }

    private static String kindText(Integer kind) {
        if (kind == null) {
            return "未知";
        }
        return switch (kind) {
            case Constants.RES_KIND_COVER -> "封面";
            case Constants.RES_KIND_MATERIAL -> "教学资料";
            case Constants.RES_KIND_VIDEO -> "视频";
            default -> "未知";
        };
    }
}
