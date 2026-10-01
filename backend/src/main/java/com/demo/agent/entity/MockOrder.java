package com.demo.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 模拟订单（供工具调用）
 */
@Data
@TableName("mock_order")
public class MockOrder {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String orderNo;

    private String userPhone;

    private String productName;

    private BigDecimal amount;

    /** 待发货 / 已发货 / 已签收 / 已完成 / 已退款 */
    private String status;

    private String receiverAddress;

    private LocalDateTime createdAt;
}
