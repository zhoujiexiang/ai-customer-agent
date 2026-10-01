package com.demo.agent.rag;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 文本切片器。
 * <p>
 * 切片的粒度直接决定检索质量，这里按「段落 → 句子 → 硬切」三级降级：
 * 优先保持段落完整（语义最完整），段落超长再按句号切（语义基本完整），
 * 句子仍然超长才硬切（兜底，几乎不会触发）。
 * <p>
 * 相邻切片之间保留 {@code overlap} 个字符的重叠，避免答案正好落在切口上被截断，
 * 导致两边都检索不到。
 */
@Component
public class TextSplitter {

    private static final String SENTENCE_DELIMITERS = "(?<=[。！？；!?;\\n])";

    private final int chunkSize;
    private final int overlap;

    public TextSplitter(@Value("${app.rag.chunk-size:500}") int chunkSize,
                        @Value("${app.rag.chunk-overlap:80}") int overlap) {
        this.chunkSize = Math.max(100, chunkSize);
        this.overlap = Math.min(Math.max(0, overlap), this.chunkSize / 2);
    }

    public List<String> split(String raw) {
        List<String> chunks = new ArrayList<>();
        String text = normalize(raw);
        if (text.isEmpty()) {
            return chunks;
        }

        StringBuilder buffer = new StringBuilder();
        for (String piece : pieces(text)) {
            if (buffer.length() > 0 && buffer.length() + piece.length() + 2 > chunkSize) {
                String chunk = buffer.toString().trim();
                buffer.setLength(0);
                if (!chunk.isEmpty()) {
                    chunks.add(chunk);
                    if (overlap > 0 && chunk.length() > overlap) {
                        buffer.append(chunk.substring(chunk.length() - overlap)).append('\n');
                    }
                }
            }
            if (buffer.length() > 0 && buffer.charAt(buffer.length() - 1) != '\n') {
                buffer.append('\n');
            }
            buffer.append(piece);
        }

        String tail = buffer.toString().trim();
        if (!tail.isEmpty()) {
            chunks.add(tail);
        }
        return chunks;
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    /** 拆成不超过 chunkSize 的原子片段 */
    private List<String> pieces(String text) {
        List<String> out = new ArrayList<>();
        for (String paragraph : text.split("\n{2,}")) {
            String p = paragraph.trim();
            if (p.isEmpty()) {
                continue;
            }
            if (p.length() <= chunkSize) {
                out.add(p);
                continue;
            }
            StringBuilder buffer = new StringBuilder();
            for (String sentence : p.split(SENTENCE_DELIMITERS)) {
                if (sentence.length() > chunkSize) {
                    if (buffer.length() > 0) {
                        out.add(buffer.toString().trim());
                        buffer.setLength(0);
                    }
                    out.addAll(hardSplit(sentence));
                    continue;
                }
                if (buffer.length() > 0 && buffer.length() + sentence.length() > chunkSize) {
                    out.add(buffer.toString().trim());
                    buffer.setLength(0);
                }
                buffer.append(sentence);
            }
            if (buffer.length() > 0) {
                out.add(buffer.toString().trim());
            }
        }
        return out;
    }

    private List<String> hardSplit(String text) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < text.length(); i += chunkSize) {
            out.add(text.substring(i, Math.min(i + chunkSize, text.length())).trim());
        }
        return out;
    }

    private String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String text = raw.replace("\r\n", "\n").replace('\r', '\n');
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1);
        }
        return text.trim();
    }
}
