package com.kzai.learning.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 学习进度上报（学员端）。
 * <p>
 * 服务端只接受"章ID + 视频位置 + 视频时长 + 是否完成"，
 * 课程完成百分比一律由服务端按已完成章节数计算，不接受前端传入的百分比。
 */
@Data
public class ProgressReportRequest {

    @NotNull(message = "章ID不能为空")
    private Long chapterId;

    /** 当前视频播放位置（秒），断点续学依据；不播放视频时可为 0 */
    private Integer videoPositionSec;

    /** 视频总时长（秒），用于服务端计算章节内进度与结束判定 */
    private Integer videoDurationSec;

    /** 是否标记本章完成；带视频的章节需播放到阈值比例才被服务端采纳 */
    private Boolean completed;
}
