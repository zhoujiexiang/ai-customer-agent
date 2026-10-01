package com.demo.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.demo.agent.entity.MockOrder;
import org.apache.ibatis.annotations.Mapper;

/**
 * 模拟订单 Mapper（供 query_order / apply_refund / update_address 工具使用）
 */
@Mapper
public interface MockOrderMapper extends BaseMapper<MockOrder> {
}
