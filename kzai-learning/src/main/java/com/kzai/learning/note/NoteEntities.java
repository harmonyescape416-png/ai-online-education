package com.kzai.learning.note;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("learning_note")
class LearningNote {
    static final String PRIVATE = "PRIVATE";
    static final String PUBLIC = "PUBLIC";

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long userId;
    private Long courseId;
    private Long chapterId;
    private String title;
    private String content;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Integer videoPositionSec;
    private String visibility;
    private Integer deleted;
    private Integer likeCount;
    private Integer commentCount;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}

@Data
@TableName("learning_note_like")
class LearningNoteLike {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long noteId;
    private Long userId;
    private LocalDateTime createTime;
}

@Data
@TableName("learning_note_comment")
class LearningNoteComment {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long noteId;
    private Long userId;
    private String content;
    private Integer deleted;
    private LocalDateTime createTime;
}
