package com.kzai.course.dto;

import lombok.Data;

/**
 * 课程列表查询参数（需求分析说明书 3.2.3.1）。
 */
@Data
public class CourseQuery {

    /** 页码，从 1 开始 */
    private Integer pageNum = 1;

    private Integer pageSize = 12;

    /** 分类ID，支持一级或二级分类筛选 */
    private Long categoryId;

    /** 关键词：课程名称 / 简介 / 适用人群 */
    private String keyword;

    /** 排序：latest 最新、price 价格、hot 热度 */
    private String sort;

    /** 逗号分隔的课程 ID。为空表示不按 ID 过滤。 */
    private String ids;

    /** 最低价格，单位分。为空表示不限。 */
    private Integer minPrice;
}
