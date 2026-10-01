package com.demo.agent.rag;

import com.demo.agent.mapper.KbChunkMapper;
import com.demo.agent.mapper.PgVectorTypeHandler;
import com.demo.agent.service.EmbeddingClient;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 检索层：把问题向量化，再去 pgvector 里按余弦距离取 Top-K。
 * <p>
 * 这里是「检索」而不是「搜索」的关键差别：返回的不只是文本，还有相似度分数，
 * 调用方（ChatService）据此决定哪些片段够格进 Prompt、哪些要在决策面板上标灰。
 */
@Service
@RequiredArgsConstructor
public class RetrievalService {

    private static final Logger log = LoggerFactory.getLogger(RetrievalService.class);

    private final EmbeddingClient embeddingClient;
    private final KbChunkMapper chunkMapper;

    @Value("${app.rag.top-k:5}")
    private int defaultTopK;

    @Value("${app.rag.score-threshold:0.35}")
    private double scoreThreshold;

    public double getScoreThreshold() {
        return scoreThreshold;
    }

    public int getDefaultTopK() {
        return defaultTopK;
    }

    /** 向量化服务是否可用。不可用时调用方应跳过检索，而不是等它抛异常 */
    public boolean isEnabled() {
        return embeddingClient.isConfigured();
    }

    /**
     * 检索通道。
     * <p>
     * 保留两路独立通道、把融合放在评测脚本里做，而不是直接在后端做融合排序 ——
     * 因为融合策略（RRF / 加权 / 分数归一化）需要靠数据挑，
     * 而融合后的分数会失去「余弦相似度」的绝对含义，阈值就不好定了。
     * 先分开测出结论，再决定要不要落到主链路。
     */
    public enum Mode {
        /** 向量通道：pgvector 余弦相似度，语义泛化能力强 */
        VECTOR,
        /** 关键词通道：pg_trgm 三元组相似度，字面命中准，且不依赖向量化服务 */
        KEYWORD
    }

    public List<ChunkHit> search(String question, Integer topK) {
        return search(question, topK, Mode.VECTOR);
    }

    /**
     * 检索，返回按相关度降序的命中列表（**不做阈值过滤**）。
     * <p>
     * 阈值过滤交给调用方：低于阈值的片段不该进 Prompt，
     * 但决策面板需要展示「检索到了、只是分数不够」，否则用户看不出是检索失败还是压根没检索。
     */
    public List<ChunkHit> search(String question, Integer topK, Mode mode) {
        if (question == null || question.isBlank()) {
            return List.of();
        }
        int k = (topK == null || topK <= 0) ? defaultTopK : topK;

        List<Map<String, Object>> rows;
        if (mode == Mode.KEYWORD) {
            // 关键词通道不需要调 Embedding 服务 —— 向量服务不可用时它是唯一的退路
            rows = chunkMapper.searchByKeyword(question, k);
        } else {
            float[] vector = embeddingClient.embedOne(question);
            String literal = PgVectorTypeHandler.toVectorLiteral(vector);
            rows = chunkMapper.searchByVector(literal, k);
        }

        List<ChunkHit> hits = new ArrayList<>(rows.size());
        int index = 0;
        for (Map<String, Object> row : rows) {
            index++;
            hits.add(new ChunkHit(
                    index,
                    asLong(row.get("id")),
                    asLong(row.get("doc_id")),
                    asString(row.get("doc_name")),
                    asInt(row.get("chunk_index")),
                    asString(row.get("content")),
                    asDouble(row.get("score"))));
        }
        log.debug("检索完成：mode={} question={} topK={} 命中 {} 条", mode, question, k, hits.size());
        return hits;
    }

    // ------------------------------------------------------------------

    private static Long asLong(Object v) {
        return v == null ? null : ((Number) v).longValue();
    }

    private static int asInt(Object v) {
        return v == null ? 0 : ((Number) v).intValue();
    }

    private static double asDouble(Object v) {
        return v == null ? 0d : ((Number) v).doubleValue();
    }

    private static String asString(Object v) {
        return v == null ? "" : String.valueOf(v);
    }
}
