package com.kzai.aigc.session.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kzai.aigc.session.entity.ChatSession;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ChatSessionMapper extends BaseMapper<ChatSession> {
}
