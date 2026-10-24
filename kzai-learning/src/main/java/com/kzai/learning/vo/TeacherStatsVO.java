package com.kzai.learning.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 教师课程学情统计。
 * statsScope=CUMULATIVE 时人数和时长来自进度累计值；
 * statsScope=RANGE 时来自学习日事实。learningCoverage=INSUFFICIENT 表示所选区间早于事实记账起点，
 * 学习人数和时长为 null，不能当成 0。
 * aiAdoptRate 为 0～100、保留 1 位小数；无有效 AI 样本时为 null，同时给出 aiSampleCount=0。
 */
@Data
@Builder
public class TeacherStatsVO {

    private Long courseId;
    private String courseName;
    private Integer free;

    /** 学习人数（learning_lesson 中已加入学习的人数） */
    private Long learnerCount;
    /** 已完成人数 */
    private Long finishedLearnerCount;
    /** 课程完成率（已完成人数 / 学习人数，百分比保留 1 位小数） */
    private Double finishRate;
    /** 章节总数 */
    private Integer chapterCount;
    /** 累计有效学习时长（秒） */
    private Long totalStudySeconds;
    /** 平均学习时长（秒/人，基于真实有效学习记录） */
    private Long avgStudySeconds;
    /** 最近一次学习时间 */
    private LocalDateTime lastStudyTime;
    /** 最近学习情况（前 N 条） */
    private List<RecentStudyVO> recentStudies;
    /** 章节完成分布 */
    private List<ChapterStatVO> chapterStats;

    /** 未隐藏提问数。区间模式下只计区间内创建的提问。 */
    private Long questionCount;
    /** 有效 AI 回复数。无样本时采纳率为 null，本字段为 0。统计失败时为 null。 */
    private Long aiSampleCount;
    /** 至少有一条有效「有帮助」记录的 AI 回复数。 */
    private Long aiAdoptedCount;
    /** 采纳率，0～100，1 位小数。无样本为 null。 */
    private Double aiAdoptRate;
    /** 问答数据是否可统计。查询失败时为 false。 */
    private Boolean interactionReady;
    /** CUMULATIVE 或 RANGE */
    private String statsScope;
    /** OK 或 INSUFFICIENT。仅区间学习指标使用。 */
    private String learningCoverage;
    private String coverageMessage;
    private String dateFrom;
    private String dateTo;
}
