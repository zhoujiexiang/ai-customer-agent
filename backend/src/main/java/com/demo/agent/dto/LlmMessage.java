package com.demo.agent.dto;

import java.util.List;

/**
 * 送给 LLM 的一条对话消息。
 * <p>
 * 与实体 ChatMessage 区分开：这个是「协议层」对象，实体是「持久化层」对象。
 * <p>
 * 支持工具调用所需的三类消息：
 * <ul>
 *     <li>普通 system / user / assistant：只有 role + content</li>
 *     <li>assistant 发起调用：content 为空，带 {@code toolCalls}</li>
 *     <li>工具返回结果：role=tool，必须带 {@code toolCallId} 指回是哪一次调用</li>
 * </ul>
 */
public record LlmMessage(String role, String content, List<ToolCall> toolCalls, String toolCallId) {

    public static LlmMessage system(String content) {
        return new LlmMessage("system", content, null, null);
    }

    public static LlmMessage user(String content) {
        return new LlmMessage("user", content, null, null);
    }

    public static LlmMessage assistant(String content) {
        return new LlmMessage("assistant", content, null, null);
    }

    /** 模型请求调用工具的 assistant 消息 */
    public static LlmMessage assistantToolCalls(List<ToolCall> toolCalls) {
        return new LlmMessage("assistant", "", toolCalls, null);
    }

    /** 工具执行结果消息，toolCallId 必须与发起时一致，否则模型会报错 */
    public static LlmMessage tool(String toolCallId, String content) {
        return new LlmMessage("tool", content, null, toolCallId);
    }
}
