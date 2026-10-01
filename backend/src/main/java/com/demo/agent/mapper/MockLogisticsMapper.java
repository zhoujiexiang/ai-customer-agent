package com.demo.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.demo.agent.entity.MockLogistics;
import org.apache.ibatis.annotations.Mapper;

/**
 * 模拟物流 Mapper（供 query_logistics 工具使用）
 */
@Mapper
public interface MockLogisticsMapper extends BaseMapper<MockLogistics> {
}
