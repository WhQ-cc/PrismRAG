package com.prism.rag.rag.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 知识分块（Chunk）。
 *
 * <p>一个文档被切成若干 chunk，每个 chunk 是一段"自包含"的文本 + 一个向量。</p>
 *
 * <p><b>分块为什么是 RAG 质量的生死线？</b></p>
 * <ul>
 *   <li>切太大 → 一个 chunk 里混了五个主题，向量被"平均"掉，检索不准；</li>
 *   <li>切太小 → "差旅报销需要在 OA 系统里提交"被切成"差旅报销需要"和"在 OA 系统里提交"，
 *       前者缺主语、后者缺宾语，两半都不完整；</li>
 *   <li>丢上下文 → 引用溯源时用户看到的原文是断的。</li>
 * </ul>
 * <p>本项目的对策见 {@code ChunkStrategy}：滑动窗口 + 重叠窗口，
 * 并把标题拼进 chunk 内容里（给每个 chunk 补上下文）。</p>
 *
 * <p><b>索引：</b></p>
 * <ul>
 *   <li>{@code idx_doc}：按文档查 chunk（重跑入库、删除文档时用）；</li>
 *   <li>{@code idx_kb}：按知识库查 chunk（检索时过滤）。</li>
 * </ul>
 */
@Entity
@Table(name = "knowledge_chunk",
        indexes = {
                @Index(name = "idx_chunk_doc", columnList = "doc_id"),
                @Index(name = "idx_chunk_kb", columnList = "kb_id")
        })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeChunk {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属文档 */
    @Column(name = "doc_id", nullable = false)
    private Long docId;

    /** 所属知识库（冗余，避免检索时多一次 join） */
    @Column(name = "kb_id", nullable = false)
    private Long kbId;

    /** 文档标题（冗余，生成 Prompt 与引用卡片时直接用） */
    @Column(name = "doc_title", length = 256)
    private String docTitle;

    /** 块序号 */
    @Column(name = "seq")
    private Integer seq;

    /** 块正文 */
    @Lob
    @Column(name = "content")
    private String content;

    /** 估算 token 数 */
    @Column(name = "token_count")
    private Integer tokenCount;

    /**
     * 向量（float[] 序列化后的字节）。
     *
     * <p>本项目把它存在 BLOB 里，同时内存中保留一份用于检索。
     * 生产上换 Milvus / PGVector / Elasticsearch 后，这个字段可以废弃。</p>
     */
    @Lob
    @Column(name = "embedding")
    private byte[] embedding;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
