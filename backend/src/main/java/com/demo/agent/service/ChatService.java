package com.demo.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.demo.agent.confirm.ConfirmRegistry;
import com.demo.agent.confirm.PendingConfirm;
import com.demo.agent.dto.ChatRequest;
import com.demo.agent.dto.LlmMessage;
import com.demo.agent.dto.ToolCall;
import com.demo.agent.entity.ChatMessage;
import com.demo.agent.entity.ChatSession;
import com.demo.agent.mapper.ChatMessageMapper;
import com.demo.agent.mapper.ChatSessionMapper;
import com.demo.agent.rag.ChunkHit;
import com.demo.agent.rag.RetrievalService;
import com.demo.agent.tool.ToolDefinition;
import com.demo.agent.tool.ToolRegistry;
import com.demo.agent.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Agent 编排引擎 —— 整个项目的核心。
 * <p>
 * 一次对话由「检索 → Agent 循环（决策 / 调工具 / 再决策）→ 收口生成 → 落库」组成，
 * 全程通过 SSE 事件把中间过程暴露给前端决策面板。
 * <p>
 * 事件协议：
 * <pre>
 *   session            会话建立
 *   rag                检索结果（含每条命中的分数与是否采用）
 *   tool_call          开始调用某个工具
 *   tool_confirm       写操作需要用户确认（此时流会挂起）
 *   tool_confirm_result 用户回执结果
 *   tool_result        工具返回
 *   delta              正文增量
 *   done / error       结束
 * </pre>
 */
@Service
@RequiredArgsConstructor
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    /** 送进 Prompt 的历史轮数（一问一答算一轮，所以取 10 条消息） */
    private static final int HISTORY_LIMIT = 10;

    /** Agent 循环的最大步数。跑满还没收口就强制生成，防止模型陷入「反复调工具」 */
    private static final int MAX_AGENT_STEPS = 5;

    private static final String SYSTEM_PROMPT = """
            你是「云集商城」的售后客服助手，负责解答退换货、退款、运费、发票、优惠券、配送、价保等售后问题。

            你可以使用工具查询真实数据：
            - 用户提到具体订单时，先用 query_order 查订单，用 query_logistics 查物流，不要凭记忆回答。
            - 申请退款（apply_refund）、修改收货地址（update_address）是写操作，会真实改变订单数据，
              系统会自动让用户二次确认，你正常调用即可，不需要在回答里额外询问「是否确认执行」。
            - 缺少订单号时先向用户索要，不要编造订单号。

            回答要求：
            1. 只依据提供的知识库内容作答，不要凭常识编造平台政策。
            2. 如果知识库中没有相关信息，直接说明「这个问题我暂时没有查到准确的政策」，并建议用户联系人工客服，不要猜测。
            3. 涉及金额、时限、比例等数字时必须与知识库原文或工具返回结果一致。
            4. 语气礼貌简洁，用中文回答，必要时用要点罗列，不要输出无关寒暄。
            5. 全程只使用简体中文，包括调用工具前后的说明文字，任何情况下都不要输出英文句子。
            6. 需要调用工具时直接调用，不要在调用前输出「我帮你查一下」这类过渡说明，把解释留到拿到结果之后。
            """;

    /** 检索为空或全部低于阈值时的补充约束 */
    private static final String NO_CONTEXT_HINT = """

            本次没有检索到相关度足够的资料。若用户问的是平台政策，请务必遵守第 2 条：
            明确告知用户你暂时查不到准确政策，并建议联系人工客服。绝不可以凭常识或经验给出具体数字和时限。
            """;

    private final LlmClient llmClient;
    private final ChatSessionMapper sessionMapper;
    private final ChatMessageMapper messageMapper;
    private final RetrievalService retrievalService;
    private final ToolRegistry toolRegistry;
    private final ConfirmRegistry confirmRegistry;
    private final ObjectMapper objectMapper;

    /**
     * 单独的线程池承接流式生成。
     * 一个 SSE 请求会占住一个线程直到回答结束，绝不能直接用 Tomcat 的工作线程。
     * 写操作确认会在这个线程上阻塞等待最多 60 秒，所以池子留了足够余量。
     */
    private final ExecutorService executor = Executors.newFixedThreadPool(16, r -> {
        Thread t = new Thread(r, "chat-stream");
        t.setDaemon(true);
        return t;
    });

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
    }

    /**
     * 异步启动一次流式对话，立即返回，结果通过 emitter 推送。
     */
    public void stream(ChatRequest request, SseEmitter emitter) {
        executor.submit(() -> doStream(request, emitter));
    }

    // ------------------------------------------------------------------
    // 主流程
    // ------------------------------------------------------------------

    private void doStream(ChatRequest request, SseEmitter emitter) {
        long requestAt = System.currentTimeMillis();
        StringBuilder answer = new StringBuilder();
        AtomicReference<Long> firstTokenMs = new AtomicReference<>(0L);

        List<ChunkHit> hits = List.of();
        long retrieveMs = 0L;
        int promptTokens = 0;
        int completionTokens = 0;
        int agentSteps = 0;
        List<Map<String, Object>> toolSteps = new ArrayList<>();

        try {
            String question = request.getQuestion() == null ? "" : request.getQuestion().trim();
            if (question.isEmpty()) {
                send(emitter, "error", Map.of("message", "提问内容不能为空"));
                emitter.complete();
                return;
            }

            Long sessionId = ensureSession(request.getSessionId(), question);
            saveUserMessage(sessionId, question);
            send(emitter, "session", Map.of("sessionId", sessionId));

            // ---------------- 1. 知识库检索 ----------------
            boolean ragEnabled = !Boolean.FALSE.equals(request.getUseRag()) && retrievalService.isEnabled();
            if (ragEnabled) {
                long t = System.currentTimeMillis();
                try {
                    hits = retrievalService.search(question, null);
                } catch (Exception e) {
                    log.warn("检索失败，本次降级为无知识库回答", e);
                    send(emitter, "rag", Map.of("enabled", true, "error",
                            e.getMessage() == null ? "检索失败" : e.getMessage()));
                }
                retrieveMs = System.currentTimeMillis() - t;
                if (!hits.isEmpty()) {
                    send(emitter, "rag", buildRagPayload(question, hits, retrieveMs));
                }
            }

            // ---------------- 2. Agent 循环 ----------------
            List<LlmMessage> messages = buildContext(sessionId, hits);
            boolean toolsEnabled = !Boolean.FALSE.equals(request.getEnableTools());
            ArrayNode tools = toolsEnabled ? toolRegistry.schema() : null;

            long llmStart = System.currentTimeMillis();
            boolean finished = false;

            for (int step = 1; step <= MAX_AGENT_STEPS && !finished; step++) {
                agentSteps = step;
                int[] usage = new int[2];

                List<ToolCall> toolCalls = llmClient.streamChat(
                        messages,
                        tools,
                        delta -> {
                            markFirstToken(firstTokenMs, llmStart);
                            answer.append(delta);
                            if (!send(emitter, "delta", Map.of("text", delta))) {
                                throw new ClientGoneException();
                            }
                        },
                        u -> {
                            usage[0] = u.path("prompt_tokens").asInt();
                            usage[1] = u.path("completion_tokens").asInt();
                        },
                        () -> {
                        });

                promptTokens += usage[0];
                completionTokens += usage[1];

                if (toolCalls.isEmpty()) {
                    // 模型没有要调工具，说明这一轮就是最终回答
                    finished = true;
                    break;
                }

                // 把模型的调用意图记进上下文，再逐条执行
                messages.add(LlmMessage.assistantToolCalls(toolCalls));

                for (ToolCall call : toolCalls) {
                    ToolResult result = executeTool(call, messages, toolSteps, emitter, step);
                    if (result == null) {
                        // 客户端已断开
                        throw new ClientGoneException();
                    }
                }
            }

            // 步数跑满仍在调工具：去掉 tools 再跑一次，强制它给出文字回答，
            // 否则用户会拿到一条「有工具日志但没有回答」的空白消息
            if (!finished && answer.isEmpty()) {
                llmClient.streamChat(messages, null,
                        delta -> {
                            markFirstToken(firstTokenMs, llmStart);
                            answer.append(delta);
                            if (!send(emitter, "delta", Map.of("text", delta))) {
                                throw new ClientGoneException();
                            }
                        },
                        null, () -> {
                        });
            }

            long llmMs = System.currentTimeMillis() - llmStart;
            int totalMs = (int) (System.currentTimeMillis() - requestAt);

            // ---------------- 3. 落库 ----------------
            String citationsJson = buildCitationsJson(hits);
            String traceJson = buildTraceJson(ragEnabled, hits, retrieveMs, llmMs,
                    promptTokens, completionTokens, agentSteps, toolSteps);

            ChatMessage saved = saveAssistantMessage(sessionId, answer.toString(), traceJson, citationsJson,
                    promptTokens, completionTokens, firstTokenMs.get().intValue(), totalMs);

            Map<String, Object> done = new LinkedHashMap<>();
            done.put("messageId", saved.getId());
            done.put("retrieveMs", retrieveMs);
            done.put("firstTokenMs", firstTokenMs.get());
            done.put("llmMs", llmMs);
            done.put("totalMs", totalMs);
            done.put("promptTokens", promptTokens);
            done.put("completionTokens", completionTokens);
            done.put("citationCount", citationCount(hits));
            done.put("toolCallCount", countToolCalls(toolSteps));
            done.put("agentSteps", agentSteps);
            send(emitter, "done", done);
            emitter.complete();

        } catch (ClientGoneException e) {
            log.debug("客户端已断开，终止本次生成");
            emitter.complete();
        } catch (Exception e) {
            log.warn("对话处理失败", e);
            String message = e.getMessage() == null ? "服务异常，请稍后重试" : e.getMessage();
            send(emitter, "error", Map.of("message", message));
            emitter.complete();
        }
    }

    // ------------------------------------------------------------------
    // 工具执行（含写操作二次确认）
    // ------------------------------------------------------------------

    /**
     * 执行一次工具调用，把过程写进 SSE 与 trace，并把结果作为 tool 消息回灌给模型。
     *
     * @return null 表示推送失败（客户端已断开），调用方应终止整个流程
     */
    private ToolResult executeTool(ToolCall call, List<LlmMessage> messages,
                                   List<Map<String, Object>> toolSteps,
                                   SseEmitter emitter, int step) {

        long toolStart = System.currentTimeMillis();
        Optional<ToolDefinition> found = toolRegistry.find(call.name());
        if (found.isEmpty()) {
            // 模型调用了一个不存在的工具：如实告诉它，让它改用别的方式回答
            String error = "不存在名为 " + call.name() + " 的工具，请使用已提供的工具或直接回答";
            messages.add(LlmMessage.tool(call.id(), "{\"success\":false,\"error\":\"" + error + "\"}"));
            toolSteps.add(stepRecord(step, call.name(), call.name(), true, false, error, 0, null));
            return ToolResult.fail(error);
        }

        ToolDefinition def = found.get();
        JsonNode args = parseArguments(call.arguments());

        if (!send(emitter, "tool_call", Map.of(
                "step", step,
                "name", def.name(),
                "label", def.label(),
                "write", def.write(),
                "arguments", args == null ? Map.of() : objectMapper.convertValue(args, Map.class)))) {
            return null;
        }

        // ---- 写操作：挂起等待用户确认 ----
        String confirmStatus = null;
        if (def.write()) {
            PendingConfirm pending = confirmRegistry.register();
            long confirmStart = System.currentTimeMillis();
            if (!send(emitter, "tool_confirm", Map.of(
                    "confirmId", pending.getId(),
                    "step", step,
                    "name", def.name(),
                    "label", def.label(),
                    "arguments", args == null ? Map.of() : objectMapper.convertValue(args, Map.class),
                    "preview", describe(def, args),
                    "timeoutSeconds", ConfirmRegistry.TIMEOUT_SECONDS))) {
                confirmRegistry.remove(pending.getId());
                return null;
            }

            PendingConfirm.Status status;
            try {
                // 关键：这里阻塞的是 chat-stream 线程，SseEmitter 保持打开，
                // 前端点确认走的是另一个 HTTP 请求，由 ConfirmRegistry 唤醒
                status = pending.await(ConfirmRegistry.TIMEOUT_SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                status = PendingConfirm.Status.CANCELLED;
            } finally {
                confirmRegistry.remove(pending.getId());
            }
            confirmStatus = status.name();

            send(emitter, "tool_confirm_result", Map.of(
                    "confirmId", pending.getId(),
                    "status", status.name(),
                    "elapsedMs", System.currentTimeMillis() - confirmStart));

            if (status != PendingConfirm.Status.CONFIRMED) {
                String reason = status == PendingConfirm.Status.TIMEOUT
                        ? "用户未在 " + ConfirmRegistry.TIMEOUT_SECONDS + " 秒内确认，操作已自动取消"
                        : "用户取消了这个操作";
                // 明确告诉模型「不要再重试」，否则它很可能原地再发起一次同样的调用
                messages.add(LlmMessage.tool(call.id(), "{\"success\":false,\"error\":\"" + reason
                        + "。请直接告知用户操作已取消，不要再次尝试调用该工具。\"}"));
                toolSteps.add(stepRecord(step, def.name(), def.label(), true, false, reason,
                        System.currentTimeMillis() - toolStart, confirmStatus));
                return ToolResult.fail(reason);
            }
        }

        // ---- 实际执行 ----
        long execStart = System.currentTimeMillis();
        ToolResult result;
        try {
            result = def.handler().apply(args);
        } catch (Exception e) {
            log.warn("工具 {} 执行异常", def.name(), e);
            result = ToolResult.fail("工具执行异常：" + e.getMessage());
        }
        long execMs = System.currentTimeMillis() - execStart;

        messages.add(LlmMessage.tool(call.id(), result.toModelContent()));
        toolSteps.add(stepRecord(step, def.name(), def.label(), def.write(), result.success(),
                result.summary(), execMs, confirmStatus));

        if (!send(emitter, "tool_result", Map.of(
                "step", step,
                "name", def.name(),
                "label", def.label(),
                "write", def.write(),
                "success", result.success(),
                "summary", result.summary(),
                "ms", execMs,
                "confirm", confirmStatus == null ? "" : confirmStatus,
                "data", result.data()))) {
            return null;
        }
        return result;
    }

    private Map<String, Object> stepRecord(int step, String name, String label, boolean write,
                                           boolean success, String summary, long ms, String confirm) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("step", step);
        record.put("tool", name);
        record.put("label", label);
        record.put("write", write);
        record.put("success", success);
        record.put("summary", summary);
        record.put("ms", ms);
        if (confirm != null) {
            record.put("confirm", confirm);
        }
        return record;
    }

    /** 把工具参数翻译成一句人话，用于二次确认弹窗 —— 用户要能看懂自己正在同意什么 */
    private String describe(ToolDefinition def, JsonNode args) {
        String orderNo = textOf(args, "orderNo");
        return switch (def.name()) {
            case "apply_refund" -> "为订单 " + orderNo + " 提交退款申请，退款原因："
                    + textOf(args, "reason");
            case "update_address" -> "将订单 " + orderNo + " 的收货地址修改为："
                    + textOf(args, "newAddress");
            default -> "执行「" + def.label() + "」";
        };
    }

    private static String textOf(JsonNode args, String field) {
        if (args == null || args.isMissingNode() || args.isNull()) {
            return "";
        }
        return args.path(field).asText("");
    }

    /**
     * 解析模型生成的 arguments。
     * 这一步必须容错：模型偶尔会吐出不合法的 JSON，直接抛异常会让整轮对话失败。
     */
    private JsonNode parseArguments(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            JsonNode node = objectMapper.readTree(arguments);
            return node != null && node.isObject() ? node : objectMapper.createObjectNode();
        } catch (Exception e) {
            log.warn("工具参数不是合法 JSON，按空参数处理：{}", arguments);
            return objectMapper.createObjectNode();
        }
    }

    private static void markFirstToken(AtomicReference<Long> holder, long llmStart) {
        if (holder.get() == 0L) {
            holder.set(System.currentTimeMillis() - llmStart);
        }
    }

    private int countToolCalls(List<Map<String, Object>> toolSteps) {
        return toolSteps.size();
    }

    // ------------------------------------------------------------------
    // Prompt 组装
    // ------------------------------------------------------------------

    /**
     * 组装 Prompt 上下文。
     * <p>
     * 检索到的资料作为 <b>system 消息的一部分</b>注入，位置在历史消息之前：
     * 放在历史之后会被对话内容稀释；而对「事实依据」这种需要贯穿全程的内容，
     * 放在 system 里的约束力比放在末尾更强。
     */
    private List<LlmMessage> buildContext(Long sessionId, List<ChunkHit> hits) {
        List<LlmMessage> messages = new ArrayList<>();
        messages.add(LlmMessage.system(buildSystemPrompt(hits)));

        List<ChatMessage> history = messageMapper.selectList(
                new LambdaQueryWrapper<ChatMessage>()
                        .eq(ChatMessage::getSessionId, sessionId)
                        .orderByDesc(ChatMessage::getId)
                        .last("limit " + HISTORY_LIMIT));
        Collections.reverse(history);

        for (ChatMessage m : history) {
            String content = m.getContent();
            if (content == null || content.isBlank()) {
                continue;
            }
            if ("USER".equals(m.getRole())) {
                messages.add(LlmMessage.user(content));
            } else if ("ASSISTANT".equals(m.getRole())) {
                messages.add(LlmMessage.assistant(content));
            }
        }
        return messages;
    }

    private String buildSystemPrompt(List<ChunkHit> hits) {
        List<ChunkHit> used = usedHits(hits);
        if (used.isEmpty()) {
            return SYSTEM_PROMPT + NO_CONTEXT_HINT;
        }
        StringBuilder sb = new StringBuilder(SYSTEM_PROMPT);
        sb.append("\n以下是本平台知识库中检索到的资料，回答时必须以其为准，不得与资料冲突：\n\n");
        for (ChunkHit hit : used) {
            sb.append("【资料").append(hit.index()).append("】来源：《").append(hit.docName()).append("》\n")
                    .append(hit.content()).append("\n\n");
        }
        sb.append("引用要求：回答中若使用了某条资料，请在对应句子末尾标注来源编号（如 [1]）；")
                .append("没有用到的资料不要标注。");
        return sb.toString();
    }

    /** 低于阈值的片段不进 Prompt —— 宁可不答，也不要拿不相关的资料去凑 */
    private List<ChunkHit> usedHits(List<ChunkHit> hits) {
        double threshold = retrievalService.getScoreThreshold();
        return hits.stream().filter(h -> h.score() >= threshold).toList();
    }

    private int citationCount(List<ChunkHit> hits) {
        return usedHits(hits).size();
    }

    // ------------------------------------------------------------------
    // SSE 事件与落库辅助
    // ------------------------------------------------------------------

    /**
     * 检索事件。**返回全部命中而不是只有达标的**：
     * 决策面板需要让用户看到「检索到了、只是分数不够所以没用」，
     * 否则无法区分「检索没召回」和「召回了但模型没用」。
     */
    private Map<String, Object> buildRagPayload(String query, List<ChunkHit> hits, long retrieveMs) {
        double threshold = retrievalService.getScoreThreshold();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("enabled", true);
        payload.put("query", query);
        payload.put("retrieveMs", retrieveMs);
        payload.put("topK", retrievalService.getDefaultTopK());
        payload.put("threshold", threshold);
        payload.put("hitCount", hits.size());
        payload.put("usedCount", citationCount(hits));

        List<Map<String, Object>> items = new ArrayList<>(hits.size());
        for (ChunkHit hit : hits) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("index", hit.index());
            item.put("docName", hit.docName());
            item.put("chunkIndex", hit.chunkIndex());
            item.put("score", round(hit.score()));
            item.put("used", hit.score() >= threshold);
            item.put("snippet", hit.snippet(140));
            items.add(item);
        }
        payload.put("hits", items);
        return payload;
    }

    private String buildCitationsJson(List<ChunkHit> hits) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (ChunkHit hit : usedHits(hits)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("index", hit.index());
            item.put("docId", hit.docId());
            item.put("docName", hit.docName());
            item.put("chunkIndex", hit.chunkIndex());
            item.put("score", round(hit.score()));
            item.put("snippet", hit.snippet(200));
            items.add(item);
        }
        return toJson(items);
    }

    /** 决策轨迹快照。前端决策面板回放的就是这份数据 */
    private String buildTraceJson(boolean ragEnabled, List<ChunkHit> hits,
                                  long retrieveMs, long llmMs, int promptTokens, int completionTokens,
                                  int agentSteps, List<Map<String, Object>> toolSteps) {
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("useRag", ragEnabled);
        trace.put("retrieveMs", retrieveMs);
        trace.put("topK", retrievalService.getDefaultTopK());
        trace.put("threshold", retrievalService.getScoreThreshold());
        trace.put("hitCount", hits.size());
        trace.put("usedCount", citationCount(hits));
        trace.put("llmMs", llmMs);
        trace.put("promptTokens", promptTokens);
        trace.put("completionTokens", completionTokens);
        trace.put("model", llmClient.getChatModel());
        trace.put("agentSteps", agentSteps);
        trace.put("toolCallCount", toolSteps.size());
        trace.put("tools", toolSteps);
        return toJson(trace);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("序列化决策轨迹失败，该字段置空", e);
            return null;
        }
    }

    private Long ensureSession(Long sessionId, String question) {
        if (sessionId != null) {
            ChatSession existing = sessionMapper.selectById(sessionId);
            if (existing != null) {
                existing.setUpdatedAt(LocalDateTime.now());
                sessionMapper.updateById(existing);
                return existing.getId();
            }
        }
        ChatSession session = new ChatSession();
        session.setTitle(question.length() > 30 ? question.substring(0, 30) : question);
        session.setCreatedAt(LocalDateTime.now());
        session.setUpdatedAt(LocalDateTime.now());
        sessionMapper.insert(session);
        return session.getId();
    }

    private void saveUserMessage(Long sessionId, String question) {
        ChatMessage message = new ChatMessage();
        message.setSessionId(sessionId);
        message.setRole("USER");
        message.setContent(question);
        message.setPromptTokens(0);
        message.setCompletionTokens(0);
        message.setFirstTokenMs(0);
        message.setTotalMs(0);
        message.setCreatedAt(LocalDateTime.now());
        messageMapper.insert(message);
    }

    private ChatMessage saveAssistantMessage(Long sessionId, String content, String trace, String citations,
                                             int promptTokens, int completionTokens,
                                             int firstTokenMs, int totalMs) {
        ChatMessage message = new ChatMessage();
        message.setSessionId(sessionId);
        message.setRole("ASSISTANT");
        message.setContent(content);
        message.setTrace(trace);
        message.setCitations(citations);
        message.setPromptTokens(promptTokens);
        message.setCompletionTokens(completionTokens);
        message.setFirstTokenMs(firstTokenMs);
        message.setTotalMs(totalMs);
        message.setCreatedAt(LocalDateTime.now());
        messageMapper.insert(message);
        return message;
    }

    /**
     * 推一个 SSE 事件。
     *
     * @return false 表示推送失败，通常意味着浏览器已经关掉连接
     */
    private boolean send(SseEmitter emitter, String event, Map<String, Object> payload) {
        try {
            emitter.send(SseEmitter.event().name(event).data(payload, MediaType.APPLICATION_JSON));
            return true;
        } catch (Exception e) {
            log.debug("SSE 推送失败（event={}）：{}", event, e.getMessage());
            return false;
        }
    }

    private static double round(double value) {
        return Math.round(value * 10000d) / 10000d;
    }

    /** 客户端断开的内部信号，用来区分「用户走了」和「真的出错了」 */
    private static class ClientGoneException extends RuntimeException {
    }
}
