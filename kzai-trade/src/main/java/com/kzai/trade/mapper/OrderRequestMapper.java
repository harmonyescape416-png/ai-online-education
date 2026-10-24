package com.kzai.trade.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kzai.trade.entity.OrderRequest;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface OrderRequestMapper extends BaseMapper<OrderRequest> {
}
