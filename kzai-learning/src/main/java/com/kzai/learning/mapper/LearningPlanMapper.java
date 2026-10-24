package com.kzai.learning.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kzai.learning.entity.LearningPlan;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Mapper
public interface LearningPlanMapper extends BaseMapper<LearningPlan> {

    /**
     * 本周首次完成且落在计划有效区间内的章节数。
     * 周界用业务日，暂停和退出用完成时刻，避免把暂停期间的完成算进目标。
     */
    @Select("""
            SELECT COUNT(*) FROM learning_first_event e
            WHERE e.user_id = #{userId}
              AND e.course_id = #{courseId}
              AND e.event_type = 'CHAPTER_FINISH'
              AND e.biz_date >= #{weekStart}
              AND e.biz_date < #{weekEnd}
              AND e.create_time >= #{activeFrom}
              AND e.create_time < #{activeUntil}
              AND NOT EXISTS (
                SELECT 1 FROM learning_plan_pause p
                WHERE p.plan_id = #{planId}
                  AND e.create_time >= p.paused_at
                  AND (p.resumed_at IS NULL OR e.create_time < p.resumed_at)
              )
            """)
    long countWeekFinishes(@Param("userId") long userId, @Param("courseId") long courseId,
                           @Param("planId") long planId, @Param("weekStart") LocalDate weekStart,
                           @Param("weekEnd") LocalDate weekEnd, @Param("activeFrom") LocalDateTime activeFrom,
                           @Param("activeUntil") LocalDateTime activeUntil);

    @Select("""
            SELECT COUNT(*) FROM learning_first_event
            WHERE user_id = #{userId} AND course_id = #{courseId} AND event_type = 'CHAPTER_FINISH'
            """)
    long countChapterFinishes(@Param("userId") long userId, @Param("courseId") long courseId);
}
