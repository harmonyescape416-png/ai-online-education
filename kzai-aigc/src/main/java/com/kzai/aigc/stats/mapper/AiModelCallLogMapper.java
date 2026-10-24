package com.kzai.aigc.stats.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kzai.aigc.stats.entity.AiModelCallLog;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AiModelCallLogMapper extends BaseMapper<AiModelCallLog> {
}
