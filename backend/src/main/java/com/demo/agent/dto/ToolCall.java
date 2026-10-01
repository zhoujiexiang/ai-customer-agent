package com.demo.agent.dto;

/**
 * 模型发起的一次工具调用。
 * <p>
 * {@code arguments} 是模型生成的 JSON 字符串，不是对象——它可能不合法、
 * 也可能缺少必填字段，所以执行前必须做一次校验，不能直接信任。
 */
public record ToolCall(String id, String name, String arguments) {
}
