package com.demo.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 模拟物流轨迹（供工具调用）
 */
@Data
@TableName("mock_logistics")
public class MockLogistics {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String orderNo;

    private LocalDateTime nodeTime;

    private String nodeDesc;

    /** operator 在部分数据库中属保留字，显式指定列名 */
    @TableField("operator")
    private String operator;
}
