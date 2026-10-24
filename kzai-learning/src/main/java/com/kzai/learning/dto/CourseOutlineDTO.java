package com.kzai.learning.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/** 课程学习大纲（课程服务内部契约）：课程快照 + 章节 + 资源。 */
@Data
public class CourseOutlineDTO {

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
    /** 创建人（教师） */
    private Long creater;
    private String introduce;
    /** 分类是否均启用 */
    private Boolean categoryEnabled;
    /** 是否对学员可见（已上架 + 分类启用） */
    private Boolean studentVisible;
    private LocalDateTime createTime;
    private List<LearnChapterDTO> chapters;
    private List<LearnResourceDTO> resources;
}
