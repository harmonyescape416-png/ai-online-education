package com.kzai.course.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 章节试看设置（M5）：教师显式指定某章是否允许未购学员试看。 */
@Data
public class ChapterPreviewRequest {

    @NotNull(message = "试看标识不能为空")
    private Boolean freePreview;

    /** 视频总时长（秒），可空 */
    private Integer videoDurationSec;
}
