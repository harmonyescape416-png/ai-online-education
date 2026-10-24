package com.kzai.learning.note;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
interface LearningNoteMapper extends BaseMapper<LearningNote> {
}

@Mapper
interface LearningNoteLikeMapper extends BaseMapper<LearningNoteLike> {
}

@Mapper
interface LearningNoteCommentMapper extends BaseMapper<LearningNoteComment> {
}
