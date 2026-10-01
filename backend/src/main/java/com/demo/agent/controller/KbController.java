package com.demo.agent.controller;

import com.demo.agent.common.Result;
import com.demo.agent.entity.KbDocument;
import com.demo.agent.rag.ChunkHit;
import com.demo.agent.rag.RetrievalService;
import com.demo.agent.service.KbService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库接口。属于管理端，已在 WebConfig 的拦截器范围内，需要 JWT。
 */
@RestController
@RequestMapping("/api/kb")
@RequiredArgsConstructor
public class KbController {

    private final KbService kbService;
    private final RetrievalService retrievalService;

    /** 文档列表 */
    @GetMapping("/documents")
    public Result<List<KbDocument>> list() {
        return Result.ok(kbService.list());
    }

    /** 上传文档：解析 → 切片 → 向量化 → 入库，同步返回结果 */
    @PostMapping("/documents")
    public Result<KbDocument> upload(@RequestParam("file") MultipartFile file) {
        return Result.ok(kbService.upload(file));
    }

    /** 删除文档及其全部切片 */
    @DeleteMapping("/documents/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        kbService.delete(id);
        return Result.ok();
    }

    /**
     * 召回测试：只跑检索、不进模型。
     * <p>
     * 调 Prompt 之前先看这一步，能把「检索没召回」和「模型没用好」两类问题分开，
     * 也是调 chunk-size / top-k / 阈值时唯一可靠的观测手段。
     * <p>
     * {@code mode} 用来切换检索通道（vector / keyword），默认 vector，
     * 与改动之前的行为完全一致；评测脚本用它做多通道对比。
     */
    @GetMapping("/search")
    public Result<Map<String, Object>> search(@RequestParam("q") String question,
                                              @RequestParam(value = "topK", required = false) Integer topK,
                                              @RequestParam(value = "mode", required = false) String mode) {
        RetrievalService.Mode channel = parseMode(mode);
        long start = System.currentTimeMillis();
        List<ChunkHit> hits = retrievalService.search(question, topK, channel);
        long cost = System.currentTimeMillis() - start;

        double threshold = retrievalService.getScoreThreshold();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("query", question);
        data.put("mode", channel.name().toLowerCase());
        data.put("topK", topK == null ? retrievalService.getDefaultTopK() : topK);
        data.put("threshold", threshold);
        data.put("retrieveMs", cost);
        data.put("hits", hits.stream().map(h -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("index", h.index());
            item.put("docName", h.docName());
            item.put("chunkIndex", h.chunkIndex());
            item.put("score", round(h.score()));
            // 阈值是按余弦相似度的量纲定的，关键词通道的量纲不同，
            // 对它就只给分数、不给「是否采用」的判断，避免误导
            item.put("used", channel == RetrievalService.Mode.VECTOR && h.score() >= threshold);
            item.put("snippet", h.snippet(120));
            return item;
        }).toList());
        return Result.ok(data);
    }

    private static RetrievalService.Mode parseMode(String raw) {
        if (raw == null || raw.isBlank()) {
            return RetrievalService.Mode.VECTOR;
        }
        return switch (raw.trim().toLowerCase()) {
            case "keyword" -> RetrievalService.Mode.KEYWORD;
            default -> RetrievalService.Mode.VECTOR;
        };
    }

    private static double round(double value) {
        return Math.round(value * 10000d) / 10000d;
    }
}
