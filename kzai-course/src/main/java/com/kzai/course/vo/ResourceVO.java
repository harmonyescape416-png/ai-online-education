package com.kzai.course.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/** 课程文件/资料视图（course_resource）。 */
@Data
@Builder
public class ResourceVO {

    private Long id;
    private Long courseId;
    private Long chapterId;
    /** 1封面，2教学资料，3视频 */
    private Integer kind;
    private String kindText;
    private String fileName;
    /** /files/{id}/download */
    private String fileUrl;
    private Long fileSize;
    private String fileSizeText;
    private String contentType;
    private Long uploader;
    private LocalDateTime createTime;
    private Long kbDocId;
    private Integer kbReviewStatus;
    private Integer kbIndexStatus;
    private String kbFailReason;
    private String kbVersion;
    /** NOT_SUBMITTED / REVIEW_PENDING / INDEXING / SEARCHABLE / FAILED / WITHDRAWN */
    private String kbPhase;
    private Boolean kbRetryable;
}
