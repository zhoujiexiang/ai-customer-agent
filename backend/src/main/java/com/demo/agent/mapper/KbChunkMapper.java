package com.demo.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.demo.agent.entity.KbChunk;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface KbChunkMapper extends BaseMapper<KbChunk> {

    /**
     * 向量相似度检索
     * <p>
     * 用 pgvector 的余弦距离运算符 {@code <=>} 排序，
     * 返回 1 - 距离 作为相似度分数。
     *
     * @param vectorLiteral 查询向量，格式 "[0.1,0.2,...]"
     * @param topK          返回条数
     */
    @Select("""
            SELECT c.id,
                   c.doc_id,
                   c.content,
                   c.chunk_index,
                   d.name AS doc_name,
                   1 - (c.embedding <=> CAST(#{vectorLiteral} AS vector)) AS score
            FROM kb_chunk c
            JOIN kb_document d ON d.id = c.doc_id
            WHERE c.embedding IS NOT NULL
            ORDER BY c.embedding <=> CAST(#{vectorLiteral} AS vector)
            LIMIT #{topK}
            """)
    List<Map<String, Object>> searchByVector(@Param("vectorLiteral") String vectorLiteral,
                                             @Param("topK") int topK);

    /**
     * 关键词检索（三元组相似度），不依赖向量化服务。
     * <p>
     * 用 {@code word_similarity} 而不是 {@code similarity}：前者算的是
     * 「查询的三元组集合在正文中的最佳局部匹配度」，正适合「短查询 vs 长正文」；
     * 后者的分母是整个正文的三元组集合，正文一长分数就被稀释得没有区分度。
     * <p>
     * 之所以不用 PG 全文检索（tsvector），是因为中文要外挂分词器，
     * 而三元组是纯字符切分，对中文开箱即用。
     */
    @Select("""
            SELECT c.id,
                   c.doc_id,
                   c.content,
                   c.chunk_index,
                   d.name AS doc_name,
                   word_similarity(#{query}, c.content) AS score
            FROM kb_chunk c
            JOIN kb_document d ON d.id = c.doc_id
            ORDER BY word_similarity(#{query}, c.content) DESC
            LIMIT #{topK}
            """)
    List<Map<String, Object>> searchByKeyword(@Param("query") String query,
                                              @Param("topK") int topK);
}
