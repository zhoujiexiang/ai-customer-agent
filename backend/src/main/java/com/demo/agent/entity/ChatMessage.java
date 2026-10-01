package com.demo.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 对话消息
 * <p>
 * trace / citations 是 jsonb 列，用 String 承载 JSON 文本。
 * 依赖 JDBC URL 上的 stringtype=unspecified 参数完成写入转换。
 */
@Data
@TableName("chat_message")
public class ChatMessage {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long sessionId;

    /** USER / ASSISTANT */
    private String role;

    private String content;

    /** Agent 决策轨迹快照 */
    private String trace;

    /** 引用来源列表 */
    private String citations;

    private Integer promptTokens;

    private Integer completionTokens;

    private Integer firstTokenMs;

    private Integer totalMs;

    private LocalDateTime createdAt;
}
