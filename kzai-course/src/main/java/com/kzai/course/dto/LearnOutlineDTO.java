package com.kzai.course.dto;

import lombok.Data;

import java.util.List;

/** 课程学习大纲（内部契约）：供学习服务组装学习页面，课程服务仍是课程数据唯一来源。 */
@Data
public class LearnOutlineDTO {

    private Long id;
    private String name;
    private String coverUrl;
    /** 0付费，1免费 */
    private Integer free;
    /** 价格，单位分 */
    private Integer price;
    /** 1待上架，2已上架，3下架，4已完结 */
    private Integer status;
    private Integer validDuration;
    private Long creater;
    private String introduce;
    private Boolean categoryEnabled;
    /** 学员可见：已上架 + 分类启用 */
    private Boolean studentVisible;
    private List<LearnChapterDTO> chapters;
    private List<LearnResourceDTO> resources;
}
