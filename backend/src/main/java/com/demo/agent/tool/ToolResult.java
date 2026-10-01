package com.demo.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一次工具调用的执行结果。
 * <p>
 * {@code summary} 是给决策面板显示的一句话结论，{@code data} 是结构化数据，
 * 两者都会写进 trace 便于回放；回灌给模型的内容由 {@link #toModelContent()} 统一裁剪，
 * 避免把整坨原始数据塞进上下文白烧 token。
 */
public record ToolResult(boolean success, String summary, Map<String, Object> data, String error) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static ToolResult ok(String summary, Map<String, Object> data) {
        return new ToolResult(true, summary, data == null ? Map.of() : data, null);
    }

    public static ToolResult fail(String error) {
        return new ToolResult(false, "调用失败", Map.of(), error);
    }

    /**
     * 回灌给模型的内容。失败时也要正常返回文本而不是抛异常——
     * 让模型知道「这个工具失败了」比直接中断对话更有用，它可以换个方式回答或引导用户。
     */
    public String toModelContent() {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("success", success);
        if (!success) {
            node.put("error", error == null ? "未知错误" : error);
            return node.toString();
        }
        node.put("summary", summary);
        if (!data.isEmpty()) {
            node.set("data", MAPPER.valueToTree(data));
        }
        return node.toString();
    }

    /** 决策面板用：把执行过程写进 trace */
    public Map<String, Object> toTrace() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("success", success);
        map.put("summary", summary);
        if (!success) {
            map.put("error", error);
        }
        return map;
    }
}
