package com.prism.rag.rag.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 知识文档。
 *
 * <p>状态字段 {@link #status} 与入库流水线的状态机一一对应：
 * 文档自己的状态 = 最近一次入库任务的状态。分开存的好处是：
 * 用户在页面上看文档列表时，不用 JOIN 任务表就能知道"这个文档入库成功了吗"。</p>
 *
 * <p><b>关于 {@code content} 用 {@code @Lob}：</b>
 * 大文本用 CLOB 而不是 VARCHAR。生产上更推荐把正文放对象存储（OSS/MinIO），
 * 数据库只存 URL——几百 MB 的文档塞进数据库会让备份和迁移痛苦不堪。
 * 这一点在 {@code docs/} 里有专门说明。</p>
 */
@Entity
@Table(name = "knowledge_document")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属知识库 */
    @Column(name = "kb_id", nullable = false)
    private Long kbId;

    /** 文档标题 */
    @Column(nullable = false, length = 256)
    private String title;

    /** 原始文件名 */
    @Column(name = "file_name", length = 256)
    private String fileName;

    /**
     * 来源类型：text / markdown / csv / pdf(扩展点) / url(扩展点)
     */
    @Column(name = "source_type", length = 32)
    private String sourceType;

    /** 文档正文 */
    @Lob
    @Column(name = "content")
    private String content;

    /**
     * 状态：PENDING / PARSING / CHUNKING / EMBEDDING / PERSISTING / COMPLETED / FAILED
     */
    @Column(length = 32)
    private String status;

    /** 分块数量 */
    @Column(name = "chunk_count")
    private Integer chunkCount;

    /** 失败原因 */
    @Column(name = "error_message", length = 1024)
    private String errorMessage;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
