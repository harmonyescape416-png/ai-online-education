package com.kzai.course.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 分类新增/修改入参（M3，两级管理）。 */
@Data
public class CategorySaveRequest {

    @NotBlank(message = "分类名称不能为空")
    @Size(max = 30, message = "分类名称不能超过 30 个字符")
    private String name;

    /** 父分类ID，null 或 0 表示一级分类 */
    private Long parentId;

    /** 排序值，越小越靠前 */
    private Integer sort;

    /** 0禁用，1启用 */
    private Integer status;
}
