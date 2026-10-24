package com.kzai.course.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * AI 工具视角的课程视图（M7，内部契约）。
 * <p>
 * 只暴露允许公开的字段（需求 3.2.3.1 / 设计说明书 4.3）：
 * 不含 creater/updater 等内部字段，不泄露未审核或受保护资料。
 * 金额按「分」返回，同时提供精确的「元」展示值；课程 ID 按字符串传输，
 * 防止 JavaScript Long 精度丢失。
 * <p>
 * M15 REAL：新增可选 {@code chapters}（真实 course_chapter 名称与顺序）——
 * 防编造修复的数据约束来源：工具未返回章节标题时模型不得自行编写章节目录。
 * 旧调用方兼容：字段可选，未填充时为 null。
 */
@Data
@Builder
public class CourseAiVO {

    /** 章节简要视图（真实 course_chapter 记录，按 chapter_order 升序） */
    @Data
    @Builder
    public static class ChapterBrief {
        /** 章节名称（数据库事实） */
        private String name;
        /** 章节顺序（1 起） */
        private Integer order;
    }

    /** 课程 ID（字符串传输） */
    private String id;
    private String name;
    private String coverUrl;
    /** 价格，单位分（数据库事实，不允许模型推断） */
    private Integer price;
    /** 元展示值，两位小数字符串，如 "199.00" */
    private String priceYuan;
    /** 课程简介（course_content.course_introduce，可能为空） */
    private String introduce;
    /** 公开适用人群；推荐理由只能引用真实声明。 */
    private String usePeople;
    private String categoryId;
    /** 全部章节均有时长时才提供总时长，未知不能当作零。 */
    private Long totalDurationSeconds;
    /** 章节数（course_chapter 未删除记录数） */
    private Long chapterCount;
    /** M15 REAL：章节标题列表（真实 DB 记录，按 order 升序；模型编造目录的约束数据源） */
    private List<ChapterBrief> chapters;
    /** 1直播，2录播 */
    private Integer courseType;
    /** 0付费，1免费 */
    private Integer free;
    /** 2已上架，4已完结（AI 只返回可见状态） */
    private Integer status;
    /** 有效期，单位月 */
    private Integer validDuration;
    /** 是否允许购买（上架 + 分类启用 + 购买时间窗内） */
    private Boolean purchasable;
    /** 不可购买原因（purchasable=false 时给出） */
    private String reason;
}
