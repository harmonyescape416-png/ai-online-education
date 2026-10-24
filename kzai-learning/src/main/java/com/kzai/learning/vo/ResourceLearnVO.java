package com.kzai.learning.vo;

import lombok.Builder;
import lombok.Data;

/** 学习页资料项：只暴露受控访问路径，下载时服务端重新校验权益。 */
@Data
@Builder
public class ResourceLearnVO {

    private Long id;
    private Long chapterId;
    /** 1封面，2教学资料，3视频 */
    private Integer kind;
    private String kindText;
    private String fileName;
    private String fileUrl;
    private Long fileSize;
    private String contentType;
}
