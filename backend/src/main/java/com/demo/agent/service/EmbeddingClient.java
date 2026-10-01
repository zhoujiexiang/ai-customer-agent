package com.demo.agent.service;

import com.demo.agent.common.BizException;
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
import java.util.Arrays;
import java.util.List;

/**
 * 文本向量化客户端（硅基流动 bge-m3，1024 维）。
 * <p>
 * 单次请求最多 16 条文本，超出自动分批：批次太大时服务端容易返回 413 或超时，
 * 而且逐批发送能让大文档入库时的失败粒度更细、便于重试。
 */
@Component
public class EmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingClient.class);
    private static final int BATCH_SIZE = 16;

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http;
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final int dimension;
    private final Duration requestTimeout;

    public EmbeddingClient(@Value("${app.embedding.base-url}") String baseUrl,
                           @Value("${app.embedding.api-key:}") String apiKey,
                           @Value("${app.embedding.model}") String model,
                           @Value("${app.embedding.dimension:1024}") int dimension) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.dimension = dimension;
        this.requestTimeout = Duration.ofSeconds(60);
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    public int getDimension() {
        return dimension;
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    /**
     * 批量向量化，返回顺序与入参一致。
     */
    public List<float[]> embed(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        List<float[]> result = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i += BATCH_SIZE) {
            List<String> batch = texts.subList(i, Math.min(i + BATCH_SIZE, texts.size()));
            result.addAll(embedBatch(batch));
        }
        return result;
    }

    /**
     * 单条向量化。
     */
    public float[] embedOne(String text) {
        List<float[]> list = embed(List.of(text));
        if (list.isEmpty()) {
            throw new BizException("向量化返回为空");
        }
        return list.get(0);
    }

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    private List<float[]> embedBatch(List<String> batch) {
        if (!isConfigured()) {
            throw new BizException("Embedding API Key 未配置，请设置环境变量 SILICONFLOW_API_KEY 后重启服务");
        }
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        ArrayNode input = body.putArray("input");
        batch.forEach(input::add);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/embeddings"))
                .timeout(requestTimeout)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();

        try {
            HttpResponse<String> response =
                    http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                throw new BizException("向量化接口返回 HTTP " + response.statusCode()
                        + "：" + truncate(response.body()));
            }
            return parse(response.body(), batch.size());
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("调用向量化接口失败：" + e.getMessage());
        }
    }

    /**
     * 按 index 字段还原顺序——服务端不保证返回顺序与入参一致，不排序会导致切片和向量错位。
     */
    private List<float[]> parse(String responseBody, int expected) throws Exception {
        JsonNode root = mapper.readTree(responseBody);
        JsonNode data = root.path("data");
        if (!data.isArray() || data.size() != expected) {
            throw new BizException("向量化返回条数不符，期望 " + expected + " 实际 " + data.size());
        }
        float[][] slots = new float[expected][];
        for (JsonNode item : data) {
            int index = item.path("index").asInt();
            if (index < 0 || index >= expected) {
                throw new BizException("向量化返回 index 越界：" + index);
            }
            JsonNode vector = item.path("embedding");
            if (vector.size() != dimension) {
                throw new BizException("向量维度不符，期望 " + dimension + " 实际 " + vector.size()
                        + "，请检查 app.embedding.model 与数据库 vector 列的定义是否一致");
            }
            float[] arr = new float[dimension];
            for (int i = 0; i < dimension; i++) {
                arr[i] = (float) vector.get(i).asDouble();
            }
            slots[index] = arr;
        }
        for (int i = 0; i < expected; i++) {
            if (slots[i] == null) {
                throw new BizException("向量化返回缺少 index=" + i + " 的结果");
            }
        }
        return Arrays.asList(slots);
    }

    private static String truncate(String text) {
        if (text == null) {
            return "";
        }
        String oneLine = text.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= 300 ? oneLine : oneLine.substring(0, 300) + "...";
    }
}
