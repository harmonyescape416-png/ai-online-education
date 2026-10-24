package com.kzai.learning.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kzai.learning.entity.SysEventOutbox;
import org.apache.ibatis.annotations.Mapper;

/** 通知事件 Outbox Mapper（M11） */
@Mapper
public interface SysEventOutboxMapper extends BaseMapper<SysEventOutbox> {
}
