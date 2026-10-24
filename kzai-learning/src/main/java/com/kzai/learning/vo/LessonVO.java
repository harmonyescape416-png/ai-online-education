package com.kzai.learning.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/** 我的学习列表项。 */
@Data
@Builder
public class LessonVO {

    private Long id;
    private Long courseId;
    private String courseName;
    private String coverUrl;
    /** 0免费，1付费 */
    private Integer free;
    /** 0未开始，1学习中，2已完成 */
    private Integer status;
    private String statusText;
    /** 课程进度百分比（服务端按已完成章节数计算） */
    private Integer progress;
    private Integer totalChapterCount;
    private Integer finishedChapterCount;
    /** 最近学习的章节 */
    private Long lastChapterId;
    private String lastChapterName;
    /** 断点续学位置（秒） */
    private Integer lastVideoPositionSec;
    private Long studySeconds;
    /** 权益是否仍然有效（已过期/已退款为 false） */
    private Boolean entitlementValid;
    private String entitlementSource;
    private LocalDateTime expireTime;
    private LocalDateTime lastStudyTime;
    private LocalDateTime createTime;
}
