package com.kzai.learning.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** M10：追问/回复请求（targetReplyId 指定被追问的回复） */
@Data
public class QuestionReplyRequest {

    @NotBlank(message = "回复内容不能为空")
    @Size(max = 2000, message = "回复内容最长 2000 字")
    private String content;

    /** 被追问的回复 id（可空=直接回答问题根） */
    private Long targetReplyId;

    private Integer anonymity;
}
