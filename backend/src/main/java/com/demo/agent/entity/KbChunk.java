package com.demo.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 知识库切片
 * <p>
 * embedding 对应 PostgreSQL 的 vector(1024) 列，需要配合
 * {@link com.demo.agent.mapper.PgVectorTypeHandler} 完成 float[] 与 vector 的互转。
 */
@Data
@TableName("kb_chunk")
public class KbChunk {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long docId;

    private String content;

    /** 1024 维向量，bge-m3 生成 */
    @TableField(typeHandler = com.demo.agent.mapper.PgVectorTypeHandler.class)
    private float[] embedding;

    private Integer chunkIndex;

    private Integer tokenCount;
}
