package com.kzai.learning.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kzai.learning.entity.LearningActivityFact;
import lombok.Data;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface LearningActivityFactMapper extends BaseMapper<LearningActivityFact> {

    String UPSERT = """
            INSERT INTO learning_activity_fact
            (id, user_id, course_id, chapter_id, biz_date, study_seconds,
             chapter_first_finished, course_first_finished, activity_time, create_time, update_time)
            VALUES (?,?,?,?,?,?,?,?,?,?,?)
            ON DUPLICATE KEY UPDATE
              study_seconds = study_seconds + VALUES(study_seconds),
              chapter_first_finished = GREATEST(chapter_first_finished, VALUES(chapter_first_finished)),
              course_first_finished = GREATEST(course_first_finished, VALUES(course_first_finished)),
              activity_time = CASE WHEN VALUES(activity_time) > activity_time THEN VALUES(activity_time) ELSE activity_time END,
              update_time = CASE WHEN VALUES(activity_time) > activity_time THEN VALUES(update_time) ELSE update_time END
            """;

    /**
     * 同一业务日原子累加。唯一键冲突时在同一条语句里加上增量，
     * 不先读再覆盖，也不依赖当前事务的快照能否看见对方刚插入的行。
     */
    @Insert("""
            INSERT INTO learning_activity_fact
            (id, user_id, course_id, chapter_id, biz_date, study_seconds,
             chapter_first_finished, course_first_finished, activity_time, create_time, update_time)
            VALUES
            (#{id}, #{userId}, #{courseId}, #{chapterId}, #{bizDate}, #{studySeconds},
             #{chapterFirstFinished}, #{courseFirstFinished}, #{activityTime}, #{createTime}, #{updateTime})
            ON DUPLICATE KEY UPDATE
              study_seconds = study_seconds + VALUES(study_seconds),
              chapter_first_finished = GREATEST(chapter_first_finished, VALUES(chapter_first_finished)),
              course_first_finished = GREATEST(course_first_finished, VALUES(course_first_finished)),
              activity_time = CASE WHEN VALUES(activity_time) > activity_time THEN VALUES(activity_time) ELSE activity_time END,
              update_time = CASE WHEN VALUES(activity_time) > activity_time THEN VALUES(update_time) ELSE update_time END
            """)
    int upsert(LearningActivityFact row);

    @Select("SELECT facts_since FROM learning_fact_coverage WHERE id = 1")
    LocalDateTime factsSince();

    @Select("""
            SELECT COUNT(DISTINCT user_id) FROM learning_activity_fact
            WHERE course_id = #{courseId} AND biz_date >= #{from} AND biz_date < #{to}
            """)
    Long countLearners(@Param("courseId") Long courseId,
                       @Param("from") LocalDate from, @Param("to") LocalDate to);

    @Select("""
            SELECT COALESCE(SUM(study_seconds), 0) FROM learning_activity_fact
            WHERE course_id = #{courseId} AND biz_date >= #{from} AND biz_date < #{to}
            """)
    Long sumSeconds(@Param("courseId") Long courseId,
                    @Param("from") LocalDate from, @Param("to") LocalDate to);

    @Select("""
            SELECT COUNT(DISTINCT user_id) FROM learning_activity_fact
            WHERE course_id = #{courseId} AND biz_date >= #{from} AND biz_date < #{to}
              AND course_first_finished = 1
            """)
    Long countCourseFirstFinished(@Param("courseId") Long courseId,
                                  @Param("from") LocalDate from, @Param("to") LocalDate to);

    @Select("""
            SELECT chapter_id AS chapterId,
                   COUNT(DISTINCT user_id) AS learnerCount,
                   COUNT(DISTINCT CASE WHEN chapter_first_finished = 1 THEN user_id END) AS finishedCount,
                   COALESCE(SUM(study_seconds), 0) AS totalStudySeconds
            FROM learning_activity_fact
            WHERE course_id = #{courseId} AND biz_date >= #{from} AND biz_date < #{to}
            GROUP BY chapter_id
            """)
    List<ChapterFactAgg> chapterAgg(@Param("courseId") Long courseId,
                                    @Param("from") LocalDate from, @Param("to") LocalDate to);

    @Select("""
            SELECT user_id AS userId, MAX(activity_time) AS activityTime, COALESCE(SUM(study_seconds), 0) AS studySeconds
            FROM learning_activity_fact
            WHERE course_id = #{courseId} AND biz_date >= #{from} AND biz_date < #{to}
            GROUP BY user_id
            ORDER BY MAX(activity_time) DESC
            LIMIT 10
            """)
    List<RecentFactAgg> recent(@Param("courseId") Long courseId,
                               @Param("from") LocalDate from, @Param("to") LocalDate to);

    @Data
    class ChapterFactAgg {
        private Long chapterId;
        private Long learnerCount;
        private Long finishedCount;
        private Long totalStudySeconds;
    }

    @Data
    class RecentFactAgg {
        private Long userId;
        private LocalDateTime activityTime;
        private Long studySeconds;
    }
}
