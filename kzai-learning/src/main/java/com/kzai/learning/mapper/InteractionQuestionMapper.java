package com.kzai.learning.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kzai.learning.entity.InteractionQuestion;
import org.apache.ibatis.annotations.Mapper;

/** M10：interaction_question Mapper */
@Mapper
public interface InteractionQuestionMapper extends BaseMapper<InteractionQuestion> {
}
