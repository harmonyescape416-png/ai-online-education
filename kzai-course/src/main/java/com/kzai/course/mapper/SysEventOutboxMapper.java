package com.kzai.course.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kzai.course.entity.SysEventOutbox;
import org.apache.ibatis.annotations.Mapper;

/** 通知事件 Outbox Mapper（M11·course） */
@Mapper
public interface SysEventOutboxMapper extends BaseMapper<SysEventOutbox> {
}
