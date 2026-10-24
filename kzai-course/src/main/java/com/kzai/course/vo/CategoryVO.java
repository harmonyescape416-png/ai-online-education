package com.kzai.course.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/** 分类树节点（M3）。 */
@Data
@Builder
public class CategoryVO {

    private Long id;
    private String name;
    private Long parentId;
    private Integer level;
    private Integer sort;
    /** 0禁用，1启用 */
    private Integer status;

    private List<CategoryVO> children;
}
