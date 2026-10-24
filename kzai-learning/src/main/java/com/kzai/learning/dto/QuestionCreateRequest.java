package com.kzai.learning.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** M10：学员提问请求（userId 一律取服务端登录身份，不接受前端传入） */
@Data
public class QuestionCreateRequest {

    @NotBlank(message = "标题不能为空")
    @Size(max = 100, message = "标题最长 100 字")
    private String title;

    @NotBlank(message = "问题描述不能为空")
    @Size(max = 2000, message = "问题描述最长 2000 字")
    private String description;

    private Long courseId;

    private Long chapterId;

    private Long sectionId;

    /** 匿名仅影响展示 */
    private Integer anonymity;
}
