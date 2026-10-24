package com.kzai.course.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 章节新增/编辑入参（M3，仅使用原始 course_chapter 表的 8 个字段）。 */
@Data
public class ChapterSaveRequest {

    @NotBlank(message = "章节名称不能为空")
    @Size(max = 100, message = "章节名称不能超过 100 个字符")
    private String name;

    @Size(max = 255, message = "章节描述不能超过 255 个字符")
    private String description;
}
