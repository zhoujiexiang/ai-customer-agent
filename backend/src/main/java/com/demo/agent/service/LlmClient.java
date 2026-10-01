package com.demo.agent.service;

import com.demo.agent.common.BizException;
import com.demo.agent.dto.LlmMessage;
import com.demo.agent.dto.ToolCall;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * OpenAI 兼容协议的 LLM 客户端。
 * <p>
 * 只用 JDK 自带的 java.net.http.HttpClient，不引 WebFlux / OkHttp：
 * 项目主体是 Spring MVC + SseEmitter，为了发一次 HTTP 请求把整个 Web 栈换成响应式不划算。
 * 流式解析靠 {@code BodyHandlers.ofLines()}，它是惰性的，SSE 一行一行吐，不用自己处理分块粘包。
 * <p>
 * 工具调用（Function Calling）走的是同一条流式请求：模型决定调工具时，
 * 会在 delta 里以「分片」形式吐出 tool_calls，由 {@link ToolCallAccumulator} 按 index 拼回完整调用。
 * 这样「不需要工具」的普通问答不会被多出的一次非流式调用拖慢首 token 延迟。
 */
@Component
public class LlmClient {

    private static final Logger log = LoggerFactory.getLogger(LlmClient.class);
    private static final String STREAM_DONE = "[DONE]";

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http;
    private final String baseUrl;
    private final String apiKey;
    private final String chatModel;
    private final Duration requestTimeout;

    public LlmClient(@Value("${app.llm.base-url}") String baseUrl,
                     @Value("${app.llm.api-key:}") String apiKey,
                     @Value("${app.llm.chat-model}") String chatModel,
                     @Value("${app.llm.timeout-seconds:60}") long timeoutSeconds) {
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.apiKey = apiKey;
        this.chatModel = chatModel;
        this.requestTimeout = Duration.ofSeconds(timeoutSeconds);
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                // 强制 HTTP/1.1：SSE 走 HTTP/2 时部分网关会缓冲，导致「流式」变成一次性返回
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    public String getChatModel() {
        return chatModel;
    }

    /**
     * 非流式对话，返回 assistant 文本。
     */
    public String chat(List<LlmMessage> messages) {
        JsonNode resp = chatRaw(messages, null, null);
        return resp.path("choices").path(0).path("message").path("content").asText("");
    }

    /**
     * 非流式对话，返回完整响应体。
     * 工具调用场景需要读 choices[0].message.tool_calls，所以不能只取 content。
     */
    public JsonNode chatRaw(List<LlmMessage> messages, ArrayNode tools, String toolChoice) {
        ObjectNode body = baseBody(messages);
        body.put("stream", false);
        if (tools != null && !tools.isEmpty()) {
            body.set("tools", tools);
            if (toolChoice != null) {
                body.put("tool_choice", toolChoice);
            }
        }
        return post("/chat/completions", body);
    }

    /** 流式对话（无工具） */
    public void streamChat(List<LlmMessage> messages, Consumer<String> onDelta, Runnable onDone) {
        streamChat(messages, null, onDelta, null, onDone);
    }

    /** 流式对话（带 usage 回调，无工具） */
    public void streamChat(List<LlmMessage> messages,
                           Consumer<String> onDelta,
                           Consumer<JsonNode> onUsage,
                           Runnable onDone) {
        streamChat(messages, null, onDelta, onUsage, onDone);
    }

    /**
     * 流式对话（支持工具调用）。
     * <p>
     * 请求里带上 {@code stream_options.include_usage}，DeepSeek 会在最后一片里回传 token 用量，
     * 这样只发一次请求就能同时拿到正文和计费数据。
     *
     * @param tools   工具定义，为 null 表示本轮不支持工具调用
     * @param onDelta 增量文本回调
     * @param onUsage token 用量回调，可能为 null；并非所有兼容实现都会回传
     * @param onDone  整条流正常结束的回调
     * @return 模型本轮请求的完整工具调用列表；为空表示这是一次普通回答
     */
    public List<ToolCall> streamChat(List<LlmMessage> messages,
                                     ArrayNode tools,
                                     Consumer<String> onDelta,
                                     Consumer<JsonNode> onUsage,
                                     Runnable onDone) {
        ObjectNode body = baseBody(messages);
        body.put("stream", true);
        body.putObject("stream_options").put("include_usage", true);
        if (tools != null && !tools.isEmpty()) {
            body.set("tools", tools);
            body.put("tool_choice", "auto");
        }

        HttpRequest request = newRequest("/chat/completions")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();

        ToolCallAccumulator accumulator = new ToolCallAccumulator();
        try {
            // request timeout 只覆盖到「响应头返回」，正文是后面惰性读的，长回答不会被超时掐断
            HttpResponse<Stream<String>> response = http.send(request, HttpResponse.BodyHandlers.ofLines());
            if (response.statusCode() != 200) {
                String detail = response.body().limit(20).collect(Collectors.joining("\n"));
                throw new BizException("LLM 接口返回 HTTP " + response.statusCode() + "：" + truncate(detail));
            }
            try (Stream<String> lines = response.body()) {
                Iterator<String> it = lines.iterator();
                while (it.hasNext()) {
                    String line = it.next();
                    if (line.isEmpty() || !line.startsWith("data:")) {
                        continue;
                    }
                    String data = line.substring(5).trim();
                    if (data.isEmpty() || STREAM_DONE.equals(data)) {
                        continue;
                    }
                    JsonNode chunk = parseChunk(data);
                    if (chunk == null) {
                        continue;
                    }

                    JsonNode deltaNode = chunk.path("choices").path(0).path("delta");
                    String delta = deltaNode.path("content").asText("");
                    if (!delta.isEmpty()) {
                        onDelta.accept(delta);
                    }
                    // 工具调用分片：只累积、不回调，等整条流结束后再统一交出去
                    JsonNode toolCallsNode = deltaNode.path("tool_calls");
                    if (toolCallsNode.isArray() && !toolCallsNode.isEmpty()) {
                        accumulator.accept(toolCallsNode);
                    }

                    JsonNode usage = chunk.path("usage");
                    if (onUsage != null && !usage.isMissingNode() && !usage.isNull()) {
                        onUsage.accept(usage);
                    }
                }
            }
            onDone.run();
            return accumulator.toList();
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("调用 LLM 失败：" + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    /**
     * 工具调用分片累加器。
     * <p>
     * 流式下模型的 tool_calls 是拆开吐的：第一片给 index/id/name，
     * 后续每一片只给一小段 arguments（可能只有一两个字符）。
     * 必须按 index 归组、把 arguments 顺序拼接，才能还原成合法的 JSON。
     */
    private static final class ToolCallAccumulator {

        private final Map<Integer, String[]> slots = new LinkedHashMap<>();

        void accept(JsonNode toolCallsNode) {
            for (JsonNode node : toolCallsNode) {
                int index = node.path("index").asInt(0);
                String[] slot = slots.computeIfAbsent(index, k -> new String[]{"", "", ""});
                String id = node.path("id").asText("");
                if (!id.isEmpty()) {
                    slot[0] = id;
                }
                String name = node.path("function").path("name").asText("");
                if (!name.isEmpty()) {
                    slot[1] = name;
                }
                String args = node.path("function").path("arguments").asText("");
                if (!args.isEmpty()) {
                    slot[2] = slot[2] + args;
                }
            }
        }

        List<ToolCall> toList() {
            if (slots.isEmpty()) {
                return List.of();
            }
            List<ToolCall> list = new ArrayList<>(slots.size());
            // 用 LinkedHashMap 保留 index 顺序：模型给的顺序就是它期望的执行顺序
            slots.forEach((index, slot) -> list.add(new ToolCall(slot[0], slot[1], slot[2])));
            return list;
        }
    }

    private JsonNode parseChunk(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            log.debug("忽略无法解析的 SSE 分片：{}", truncate(json));
            return null;
        }
    }

    private JsonNode post(String path, ObjectNode body) {
        if (!isConfigured()) {
            throw new BizException("LLM API Key 未配置，请设置环境变量 DEEPSEEK_API_KEY 后重启服务");
        }
        HttpRequest request = newRequest(path)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<String> response =
                    http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                throw new BizException("LLM 接口返回 HTTP " + response.statusCode()
                        + "：" + truncate(response.body()));
            }
            return mapper.readTree(response.body());
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("调用 LLM 失败：" + e.getMessage());
        }
    }

    private ObjectNode baseBody(List<LlmMessage> messages) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", chatModel);
        body.set("messages", messagesNode(messages));
        body.put("temperature", 0.3);
        return body;
    }

    /**
     * 组装 messages 数组。
     * <p>
     * 三种形态要区分开，否则模型会报 400：
     * 普通消息给 content；assistant 发起调用时 content 留空但必须带 tool_calls；
     * tool 结果消息必须带 tool_call_id 指回是哪一次调用。
     */
    private ArrayNode messagesNode(List<LlmMessage> messages) {
        ArrayNode arr = mapper.createArrayNode();
        for (LlmMessage m : messages) {
            ObjectNode node = arr.addObject();
            node.put("role", m.role());

            if (m.toolCalls() != null && !m.toolCalls().isEmpty()) {
                node.put("content", "");
                ArrayNode tcArr = node.putArray("tool_calls");
                for (ToolCall tc : m.toolCalls()) {
                    ObjectNode tcNode = tcArr.addObject();
                    tcNode.put("id", tc.id());
                    tcNode.put("type", "function");
                    ObjectNode fn = tcNode.putObject("function");
                    fn.put("name", tc.name());
                    fn.put("arguments", tc.arguments() == null ? "{}" : tc.arguments());
                }
            } else {
                node.put("content", m.content() == null ? "" : m.content());
            }

            if (m.toolCallId() != null && !m.toolCallId().isEmpty()) {
                node.put("tool_call_id", m.toolCallId());
            }
        }
        return arr;
    }

    private HttpRequest.Builder newRequest(String path) {
        return HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(requestTimeout)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey);
    }

    private static String stripTrailingSlash(String url) {
        if (url == null || url.isEmpty()) {
            return "";
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String truncate(String text) {
        if (text == null) {
            return "";
        }
        String oneLine = text.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= 300 ? oneLine : oneLine.substring(0, 300) + "...";
    }
}
