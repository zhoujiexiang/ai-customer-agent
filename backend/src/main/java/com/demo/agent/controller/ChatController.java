package com.demo.agent.controller;

import com.demo.agent.common.Result;
import com.demo.agent.confirm.ConfirmRegistry;
import com.demo.agent.dto.ChatRequest;
import com.demo.agent.dto.ConfirmRequest;
import com.demo.agent.service.ChatService;
import com.demo.agent.service.EmbeddingClient;
import com.demo.agent.service.LlmClient;
import com.demo.agent.tool.ToolRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 对话接口。C 端免登录，已在 WebConfig 里从拦截器中放行。
 */
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;
    private final LlmClient llmClient;
    private final EmbeddingClient embeddingClient;
    private final ToolRegistry toolRegistry;
    private final ConfirmRegistry confirmRegistry;

    /**
     * 流式对话。
     * <p>
     * 用 POST 而不是 EventSource：EventSource 只支持 GET，参数只能塞进 URL，
     * 多轮上下文和后续的确认回执都不好带。这里前端用 fetch + ReadableStream 读流。
     * <p>
     * 超时设为 0（不超时）：写操作二次确认需要把这条流挂起等用户点击，
     * 默认的超时会把流掐断。
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestBody ChatRequest request) {
        SseEmitter emitter = new SseEmitter(0L);
        chatService.stream(request, emitter);
        return emitter;
    }

    /**
     * 写操作二次确认回执。
     * <p>
     * 这个接口的作用是「唤醒」：被挂起的推流线程正阻塞在 PendingConfirm 上等这一下。
     * 它是一个独立的 HTTP 请求，和 /stream 那条长连接没有任何关系，两者靠 confirmId 关联。
     * <p>
     * 幂等处理在 PendingConfirm 里用 CAS 完成，重复点击只会生效第一次。
     */
    @PostMapping("/confirm")
    public Result<Map<String, Object>> confirm(@RequestBody ConfirmRequest request) {
        boolean accepted = confirmRegistry.resolve(request.getConfirmId(),
                Boolean.TRUE.equals(request.getApproved()));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("confirmId", request.getConfirmId());
        data.put("accepted", accepted);
        data.put("pendingCount", confirmRegistry.pendingCount());
        if (!accepted) {
            return Result.fail("该确认请求已失效（可能已超时或已处理），请重新发起对话");
        }
        return Result.ok(data);
    }

    /**
     * 前端启动时探活：告知 API Key 是否已配置、当前模型名。
     * 缺 Key 时前端可以直接弹提示，而不是等用户提问后才报错。
     */
    @GetMapping("/status")
    public Result<Map<String, Object>> status() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("llmConfigured", llmClient.isConfigured());
        data.put("llmModel", llmClient.getChatModel());
        data.put("embeddingConfigured", embeddingClient.isConfigured());
        data.put("embeddingDimension", embeddingClient.getDimension());
        data.put("ready", llmClient.isConfigured() && embeddingClient.isConfigured());
        return Result.ok(data);
    }

    /** Agent 可用的工具清单，前端「能力」面板直接渲染 */
    @GetMapping("/tools")
    public Result<List<Map<String, Object>>> tools() {
        return Result.ok(toolRegistry.listAll());
    }
}
