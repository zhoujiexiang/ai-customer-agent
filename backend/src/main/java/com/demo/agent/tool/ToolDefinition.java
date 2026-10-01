package com.demo.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;
import java.util.function.Function;

/**
 * 一个可被模型调用的工具。
 *
 * @param name        工具名，模型按这个名字发起调用，必须与 schema 一致
 * @param label       中文短名，只用于决策面板展示，不进 Prompt
 * @param description 给模型看的说明。**写得越清楚，模型选错工具的概率越低**，
 *                    这是 Function Calling 里最值得打磨的一段「提示词」
 * @param write       是否写操作。写操作会触发二次确认，只读的直接执行
 * @param parameters  JSON Schema 的 properties 部分，由 ToolRegistry 组装成完整 schema
 * @param required    必填参数名
 * @param handler     执行逻辑，入参是模型生成的 arguments（可能不合法，需自行兜底）
 */
public record ToolDefinition(
        String name,
        String label,
        String description,
        boolean write,
        Map<String, Object> parameters,
        java.util.List<String> required,
        Function<JsonNode, ToolResult> handler) {
}
