package com.prism.rag.rag.pipeline;

import com.prism.rag.rag.pipeline.chunk.ChunkDraft;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 入库上下文：一次入库任务在流水线中流转的数据载体。
 *
 * <p>四个节点之间通过它传递数据：</p>
 * <pre>
 *   ParseNode   ──写入──→ parsed
 *   ChunkNode   ──写入──→ chunks
 *   EmbeddingNode ──写入──→ vectors
 *   PersistNode ──读取──→ chunks + vectors
 * </pre>
 *
 * <p><b>为什么不用 Spring Bean 传递？</b>因为任务是并发的——
 * 同一个应用实例会同时跑几十个入库任务，状态必须<b>每个任务一份</b>，
 * 绝不能放在单例 Service 的字段里（那是经典的多线程 bug 源头）。</p>
 */
@Getter
@Setter
public class IngestionContext {

    private final Long taskId;
    private final Long docId;
    private final Long kbId;
    private final String title;
    private final String fileName;
    private final String sourceType;

    /** 原始内容 */
    private String rawContent;

    /** 解析结果 */
    private ParsedDocument parsed;

    /** 分块结果 */
    private List<ChunkDraft> chunks = new ArrayList<>();

    /** 向量结果，与 chunks 一一对应 */
    private List<float[]> vectors = new ArrayList<>();

    /** 当前状态 */
    private IngestionState state = IngestionState.PENDING;

    /** 当前节点名 */
    private String currentNode;

    /** 失败原因 */
    private String error;

    /** 节点日志 */
    private final List<NodeLog> logs = new ArrayList<>();

    public IngestionContext(Long taskId, Long docId, Long kbId, String title,
                             String fileName, String sourceType, String rawContent) {
        this.taskId = taskId;
        this.docId = docId;
        this.kbId = kbId;
        this.title = title;
        this.fileName = fileName;
        this.sourceType = sourceType;
        this.rawContent = rawContent;
    }

    /**
     * 状态流转（带合法性校验）。
     */
    public void transitionTo(IngestionState target) {
        state.assertTransitionTo(target);
        this.state = target;
    }

    /**
     * 标记失败。
     */
    public void fail(Throwable throwable) {
        this.error = throwable == null ? "未知错误" : throwable.getMessage();
        // 失败可能发生在任意状态，统一允许从当前状态走到 FAILED
        if (state != IngestionState.FAILED) {
            this.state = IngestionState.FAILED;
        }
    }

    /**
     * 记录一条节点日志。
     */
    public void log(String node, String status, long costMillis, String detail) {
        logs.add(new NodeLog(node, status, costMillis, detail, LocalDateTime.now()));
    }

    /**
     * 节点日志条目。
     *
     * @param node      节点名
     * @param status    SUCCESS / FAILED
     * @param costMillis 耗时
     * @param detail    补充信息（异常信息、产物数量等）
     * @param at        时间
     */
    public record NodeLog(String node, String status, long costMillis, String detail, LocalDateTime at) {
    }
}
