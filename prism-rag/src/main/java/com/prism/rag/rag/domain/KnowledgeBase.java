package com.prism.rag.rag.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 知识库。
 *
 * <p>知识库是文档的容器，也是<b>检索隔离的边界</b>：检索时按 kbId 过滤，
 * 这样"财务部知识库"的 chunk 永远不会被"人事部知识库"的查询召回。</p>
 *
 * <p>多租户场景下，还要在这里加 tenant_id，并在每次查询强制带上租户条件——
 * 数据隔离必须是<b>默认行为</b>而不是"记得加 where"，否则一次疏忽就是越权事故。</p>
 */
@Entity
@Table(name = "knowledge_base")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 知识库名称 */
    @Column(nullable = false, length = 128)
    private String name;

    /** 描述 */
    @Column(length = 512)
    private String description;

    /** 文档数量（冗余字段，由入库流水线维护，避免列表页每次 count） */
    @Column(name = "document_count")
    private Integer documentCount;

    /** chunk 数量 */
    @Column(name = "chunk_count")
    private Integer chunkCount;

    /** 归属用户（简易归属校验用；接入 Sa-Token 后换成从上下文取） */
    @Column(name = "owner_id", length = 64)
    private String ownerId;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
