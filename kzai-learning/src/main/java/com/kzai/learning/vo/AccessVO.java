package com.kzai.learning.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/** 课程学习权益查询响应。 */
@Data
@Builder
public class AccessVO {

    private Long courseId;
    private String courseName;
    private Boolean free;
    /** 是否具备学习权益 */
    private Boolean allowed;
    /** FREE / PAID / NONE */
    private String source;
    private String sourceText;
    private String reason;
    private LocalDateTime expireTime;
    /** 是否已加入学习（learning_lesson 中存在记录） */
    private Boolean enrolled;
    /** 课程当前是否对学员可见（已上架 + 分类启用） */
    private Boolean courseVisible;
}
