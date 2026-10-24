package com.kzai.learning.dto;

import lombok.Data;

/** 学习资源（课程服务内部契约）：只给受控访问路径，不暴露服务端绝对路径。 */
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
