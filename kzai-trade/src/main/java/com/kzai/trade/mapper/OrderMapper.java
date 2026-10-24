package com.kzai.trade.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kzai.trade.entity.Order;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface OrderMapper extends BaseMapper<Order> {
}
