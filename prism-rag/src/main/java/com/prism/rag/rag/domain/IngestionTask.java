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
 * 入库任务。
 *
 * <p>一个文档一次入库 = 一个任务。任务记录了三类信息：</p>
 * <ol>
 *   <li><b>当前状态</b>（{@link #state}）：由状态机管控，非法流转会被拒绝；</li>
 *   <li><b>当前节点</b>（{@link #currentNode}）：走到流水线哪一步了；</li>
 *   <li><b>节点日志</b>（{@link #nodeLog}）：每个节点耗时、入参摘要、异常信息，
 *       排障时不用去翻日志文件，直接看任务详情。</li>
 * </ol>
 *
 * <p><b>为什么要持久化任务？</b>因为入库是异步的，HTTP 请求早就返回了。
 * 进程重启后，内存里的队列就没了，但数据库里的任务还在——
 * 这正是引入 RocketMQ 的起点（见 {@code docs/06-接入RocketMQ.md}）。</p>
 */
@Entity
@Table(name = "ingestion_task")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IngestionTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 文档 ID */
    @Column(name = "doc_id", nullable = false)
    private Long docId;

    /** 知识库 ID */
    @Column(name = "kb_id", nullable = false)
    private Long kbId;

    /** 文档标题（冗余，列表展示用） */
    @Column(name = "doc_title", length = 256)
    private String docTitle;

    /**
     * 任务状态：PENDING / PARSING / CHUNKING / EMBEDDING / PERSISTING / COMPLETED / FAILED / CANCELLED
     */
    @Column(length = 32)
    private String state;

    /** 当前执行到的节点名 */
    @Column(name = "current_node", length = 64)
    private String currentNode;

    /** 已完成节点数 / 总节点数 */
    @Column(name = "progress")
    private Integer progress;

    /** 失败原因 */
    @Column(name = "error_message", length = 2048)
    private String errorMessage;

    /**
     * 节点日志（JSON 数组字符串）。
     *
     * <p>示例：[{"node":"parse","status":"SUCCESS","costMs":12,"at":"..."}]</p>
     */
    @Lob
    @Column(name = "node_log")
    private String nodeLog;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    /** 耗时（毫秒） */
    @Column(name = "cost_ms")
    private Long costMillis;
}
