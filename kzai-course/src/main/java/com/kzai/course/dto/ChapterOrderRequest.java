package com.kzai.course.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/** 章节排序入参：按传入顺序重排 chapter_order。 */
@Data
public class ChapterOrderRequest {

    @NotEmpty(message = "章节ID列表不能为空")
    private List<Long> chapterIds;
}
