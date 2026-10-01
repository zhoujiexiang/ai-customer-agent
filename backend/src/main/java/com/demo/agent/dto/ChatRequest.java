package com.demo.agent.dto;

import lombok.Data;

/**
 * 对话请求
 */
@Data
public class ChatRequest {

    /** 会话 ID，为空表示新建会话 */
    private Long sessionId;

    /** 用户提问 */
    private String question;

    /** 是否启用知识库检索，默认启用（决策面板上可以关掉做对比演示） */
    private Boolean useRag = true;

    /** 是否允许 Agent 调用工具，默认允许（关掉即可演示「纯 RAG」与「Agent」的差别） */
    private Boolean enableTools = true;
}
