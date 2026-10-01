package com.demo.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.demo.agent.common.BizException;
import com.demo.agent.entity.KbChunk;
import com.demo.agent.entity.KbDocument;
import com.demo.agent.mapper.KbChunkMapper;
import com.demo.agent.mapper.KbDocumentMapper;
import com.demo.agent.rag.TextSplitter;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 知识库管理：文档入库（解析 → 切片 → 向量化 → 落库）、列表、删除。
 */
@Service
@RequiredArgsConstructor
public class KbService {

    private static final Logger log = LoggerFactory.getLogger(KbService.class);

    /** 当前支持的纯文本格式。PDF / Word 需要额外的解析库，属于后续扩展点 */
    private static final Set<String> SUPPORTED = Set.of("txt", "md", "markdown", "text");

    private final KbDocumentMapper documentMapper;
    private final KbChunkMapper chunkMapper;
    private final TextSplitter splitter;
    private final EmbeddingClient embeddingClient;

    public List<KbDocument> list() {
        return documentMapper.selectList(
                new LambdaQueryWrapper<KbDocument>().orderByDesc(KbDocument::getId));
    }

    /**
     * 上传并完成向量化入库。
     * <p>
     * 先落一条 PARSING 状态的文档记录，再切片入库；
     * 中途失败会落成 FAILED 并保留失败原因，避免前端看到一篇「永远在解析中」的文档。
     */
    public KbDocument upload(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BizException("请选择要上传的文件");
        }
        String originalName = file.getOriginalFilename() == null ? "未命名" : file.getOriginalFilename();
        String ext = extensionOf(originalName);
        if (!SUPPORTED.contains(ext)) {
            throw new BizException("暂不支持 ." + ext + " 格式，请上传 txt 或 md 文件");
        }
        if (!embeddingClient.isConfigured()) {
            throw new BizException("向量化服务未配置，无法入库。请先配置 Embedding API Key 后重启服务");
        }

        KbDocument document = new KbDocument();
        document.setName(originalName);
        document.setFileType(ext);
        document.setFileSize(file.getSize());
        document.setChunkCount(0);
        document.setStatus("PARSING");
        document.setCreatedAt(LocalDateTime.now());
        documentMapper.insert(document);

        try {
            String text = new String(file.getBytes(), StandardCharsets.UTF_8);
            List<String> chunks = splitter.split(text);
            if (chunks.isEmpty()) {
                throw new BizException("文件内容为空，没有可入库的文本");
            }

            List<float[]> vectors = embeddingClient.embed(chunks);
            if (vectors.size() != chunks.size()) {
                throw new BizException("向量条数与切片数不一致，入库中止");
            }

            KbChunk chunk = new KbChunk();
            for (int i = 0; i < chunks.size(); i++) {
                String content = chunks.get(i);
                chunk.setId(null);
                chunk.setDocId(document.getId());
                chunk.setContent(content);
                chunk.setEmbedding(vectors.get(i));
                chunk.setChunkIndex(i);
                // bge-m3 对中文大致是 1 字 ≈ 1 token，这里存字符数作为近似值，仅用于展示
                chunk.setTokenCount(content.length());
                chunkMapper.insert(chunk);
            }

            document.setChunkCount(chunks.size());
            document.setStatus("READY");
            documentMapper.updateById(document);
            log.info("文档入库完成：{}，切片 {} 条", originalName, chunks.size());
            return document;

        } catch (IOException e) {
            return markFailed(document, "读取文件失败：" + e.getMessage());
        } catch (RuntimeException e) {
            // 已入库的切片要清掉，否则会留下无法被检索到、也删不掉的孤儿数据
            chunkMapper.delete(new LambdaQueryWrapper<KbChunk>().eq(KbChunk::getDocId, document.getId()));
            return markFailed(document, e.getMessage());
        }
    }

    public void delete(Long documentId) {
        if (documentId == null) {
            throw new BizException("文档 ID 不能为空");
        }
        KbDocument document = documentMapper.selectById(documentId);
        if (document == null) {
            throw new BizException("文档不存在或已被删除");
        }
        chunkMapper.delete(new LambdaQueryWrapper<KbChunk>().eq(KbChunk::getDocId, documentId));
        documentMapper.deleteById(documentId);
        log.info("文档已删除：{}（id={}）", document.getName(), documentId);
    }

    // ------------------------------------------------------------------

    private KbDocument markFailed(KbDocument document, String reason) {
        document.setStatus("FAILED");
        documentMapper.updateById(document);
        log.warn("文档入库失败：{} —— {}", document.getName(), reason);
        throw new BizException("文档入库失败：" + reason);
    }

    private static String extensionOf(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return "";
        }
        return filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
