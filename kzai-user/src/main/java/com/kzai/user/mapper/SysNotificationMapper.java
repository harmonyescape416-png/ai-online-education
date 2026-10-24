package com.kzai.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kzai.user.entity.SysNotification;
import org.apache.ibatis.annotations.Mapper;

/** 站内通知 Mapper（M11：归属条件一律由 Service 层显式传入 recipient_id） */
@Mapper
public interface SysNotificationMapper extends BaseMapper<SysNotification> {
}
