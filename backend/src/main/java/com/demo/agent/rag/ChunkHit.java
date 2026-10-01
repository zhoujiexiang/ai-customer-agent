package com.demo.agent.rag;

/**
 * 一条检索命中结果。
 *
 * @param index      引用编号（1 起，直接对应 Prompt 里的【资料 N】与前端角标）
 * @param chunkId    切片主键
 * @param docId      所属文档
 * @param docName    文档名，用于引用展示
 * @param chunkIndex 切片在文档内的序号
 * @param content    切片正文
 * @param score      余弦相似度（1 - 余弦距离），越大越相关
 */
public record ChunkHit(int index,
                       Long chunkId,
                       Long docId,
                       String docName,
                       int chunkIndex,
                       String content,
                       double score) {

    /** 供前端决策面板展示的摘要 */
    public String snippet(int maxLength) {
        if (content == null) {
            return "";
        }
        String flat = content.replaceAll("\\s+", " ").trim();
        return flat.length() <= maxLength ? flat : flat.substring(0, maxLength) + "…";
    }
}
