package com.kzai.course.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 资料绑定入参：把已上传文件绑定到课程/章节。 */
@Data
public class ResourceBindRequest {

    @NotNull(message = "资源ID不能为空")
    private Long resourceId;

    /** 1封面，2教学资料，3视频 */
    @NotNull(message = "资源类型不能为空")
    private Integer kind;

    /** 章ID，课程级资料不传 */
    private Long chapterId;
}
