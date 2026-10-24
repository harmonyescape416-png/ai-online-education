package com.kzai.aigc.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 课程服务 AI 视图（M7，镜像 kzai-course CourseAiVO 内部契约）。
 * 课程 ID 按字符串传输；金额按分返回。
 * M15 REAL：新增可选 chapters（[{name,order}]，真实 DB 章节标题）——
 * 模型章节目录的唯一合法数据源（防编造修复）。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class CourseAiItem {

    private String id;
    private String name;
    private String coverUrl;
    /** 价格，单位分（数据库事实） */
    private Integer price;
    /** 元展示值，如 "199.00" */
    private String priceYuan;
    private String introduce;
    private String usePeople;
    private String categoryId;
    private Long totalDurationSeconds;
    /** 章节数 */
    private Long chapterCount;
    /** M15 REAL：真实章节标题列表（[{name, order}]，工具返回时模型才可展示逐章标题） */
    private List<Map<String, Object>> chapters;
    /** 1直播，2录播 */
    private Integer courseType;
    /** 0付费，1免费 */
    private Integer free;
    private Integer status;
    /** 有效期，月 */
    private Integer validDuration;
    private Boolean purchasable;
    private String reason;
}
