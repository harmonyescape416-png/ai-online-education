package com.kzai.learning.dto;

import lombok.Data;

/** 我的学习查询条件：学习状态筛选 + 关键字 + 分页。 */
@Data
public class LessonQuery {

    /** 0未开始，1学习中，2已完成 */
    private Integer status;
    private String keyword;
    private Integer pageNum = 1;
    private Integer pageSize = 10;
}
