package com.kzai.learning.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.kzai.learning.entity.LearningActivityFact;
import com.kzai.learning.entity.LearningFirstEvent;
import com.kzai.learning.mapper.LearningActivityFactMapper;
import com.kzai.learning.mapper.LearningFirstEventMapper;
import com.kzai.learning.stats.StatsWindow;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 把已经成功写入进度表的增量记到日事实。
 * 调用方必须传入最终落库的秒数差，不能传入乐观锁失败前的旧 delta。
 * 同一次上报的增量全部归入该次成功时刻的上海业务日。
 */
@Service
@RequiredArgsConstructor
public class LearningFactRecorder {

    private final LearningActivityFactMapper factMapper;
    private final LearningFirstEventMapper firstEventMapper;
    private final com.kzai.learning.points.PointService pointService;

    @Transactional
    public void record(long userId, long courseId, long chapterId, long persistedDeltaSeconds,
                       boolean chapterFirstFinish, boolean courseFirstFinish, LocalDateTime activityTime) {
        long delta = Math.max(0L, persistedDeltaSeconds);
        boolean chapterFlag = chapterFirstFinish && claimFirst(
                userId, courseId, chapterId, LearningFirstEvent.CHAPTER_FINISH, activityTime);
        boolean courseFlag = courseFirstFinish && claimFirst(
                userId, courseId, LearningFirstEvent.COURSE_CHAPTER, LearningFirstEvent.COURSE_FINISH, activityTime);
        if (chapterFlag) {
            pointService.award(userId, "LEARN", "LEARN:" + userId + ":" + courseId + ":" + chapterId,
                    StatsWindow.businessDate(activityTime));
        }
        if (delta == 0L && !chapterFlag && !courseFlag) {
            return;
        }
        LearningActivityFact row = new LearningActivityFact();
        row.setId(IdWorker.getId());
        row.setUserId(userId);
        row.setCourseId(courseId);
        row.setChapterId(chapterId);
        row.setBizDate(StatsWindow.businessDate(activityTime));
        row.setStudySeconds(delta);
        row.setChapterFirstFinished(chapterFlag ? 1 : 0);
        row.setCourseFirstFinished(courseFlag ? 1 : 0);
        row.setActivityTime(activityTime);
        row.setCreateTime(activityTime);
        row.setUpdateTime(activityTime);
        // 唯一键冲突由数据库在同一条语句里累加，避免可重复读快照看不见对方新行而丢掉增量。
        factMapper.upsert(row);
    }

    /** @return true 表示本次抢到了首次事件 */
    private boolean claimFirst(long userId, long courseId, long chapterId, String type, LocalDateTime when) {
        LearningFirstEvent event = new LearningFirstEvent();
        event.setUserId(userId);
        event.setCourseId(courseId);
        event.setChapterId(chapterId);
        event.setEventType(type);
        event.setBizDate(StatsWindow.businessDate(when));
        event.setCreateTime(when);
        try {
            firstEventMapper.insert(event);
            return true;
        } catch (DuplicateKeyException dup) {
            return false;
        }
    }
}
