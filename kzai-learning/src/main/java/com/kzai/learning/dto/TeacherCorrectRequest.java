package com.kzai.learning.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** M10：教师人工修正请求 */
@Data
public class TeacherCorrectRequest {

    @NotBlank(message = "修正内容不能为空")
    @Size(max = 2000, message = "修正内容最长 2000 字")
    private String content;
}
