package com.kzai.course.dto;

import lombok.Data;

/** 学习大纲资源（内部契约）：只暴露受控访问路径 /files/{id}/download。 */
@Data
public class LearnResourceDTO {

    private Long id;
    private Long courseId;
    private Long chapterId;
    /** 1封面，2教学资料，3视频 */
    private Integer kind;
    private String fileName;
    private String fileUrl;
    private Long fileSize;
    private String contentType;
}
