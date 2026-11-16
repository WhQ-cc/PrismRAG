package com.prism.rag.rag.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prism.rag.rag.domain.IngestionTask;
import com.prism.rag.rag.domain.KnowledgeDocument;
import com.prism.rag.rag.repository.IngestionTaskRepository;
import com.prism.rag.rag.repository.KnowledgeDocumentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * 入库流水线编排器。
 *
 * <h3>它做什么</h3>
 * <pre>
 *   提交任务 ──→ ingestExecutor ──→ 依次执行 ParseNode → ChunkNode → EmbeddingNode → PersistNode
 *                      │                        │
 *                      │                        ├─ 状态机管控流转（PENDING→PARSING→…→COMPLETED）
 *                      │                        └─ 每个节点记录耗时与结果到任务日志
 *                      └─ 无论成败 finally 都要把最终状态回写数据库
 * </pre>
 *
 * <h3>为什么编排器本身要跑在独立线程池？</h3>
 * <p>入库是<b>慢操作</b>（解析 + 分块 + 向量化 + 写库，可能几秒到几十秒）。
 * 如果直接在 HTTP 线程里做，用户会一直等，而且 Tomcat 工作线程会被占满。
 * 所以 HTTP 接口只负责"建任务 + 提交 + 立刻返回任务 ID"，
 * 后台慢慢跑，前端轮询任务状态。</p>
 *
 * <p>更进一步的演进：把"提交"改成"往 MQ 发消息"，
 * 由消费者集群消费——这样就能横向扩容、进程重启不丢任务。
 * 详见 {@code docs/06-接入RocketMQ.md}。</p>
 */
@Component
public class IngestionPipeline {

    private static final Logger log = LoggerFactory.getLogger(IngestionPipeline.class);

    private final List<IngestionNode> nodes;
    private final Executor ingestExecutor;
    private final IngestionTaskRepository taskRepository;
    private final KnowledgeDocumentRepository documentRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public IngestionPipeline(List<IngestionNode> nodes,
                             @Qualifier("ingestExecutor") Executor ingestExecutor,
                             IngestionTaskRepository taskRepository,
                             KnowledgeDocumentRepository documentRepository) {
        // Spring 会按 @Order 排好序注入
        this.nodes = nodes;
        this.ingestExecutor = ingestExecutor;
        this.taskRepository = taskRepository;
        this.documentRepository = documentRepository;
        log.info("[Pipeline] 装配完成，节点顺序: {}",
                nodes.stream().map(IngestionNode::name).toList());
    }

    /**
     * 提交入库任务（异步）。
     */
    public void submit(IngestionContext context) {
        ingestExecutor.execute(() -> run(context));
    }

    /**
     * 同步执行流水线（测试时可以直接调用）。
     */
    public void run(IngestionContext context) {
        long start = System.currentTimeMillis();
        try {
            for (IngestionNode node : nodes) {
                node.execute(context);
            }
            context.transitionTo(IngestionState.COMPLETED);
            log.info("[Pipeline] 任务 {} 入库完成，共 {} 个分块，耗时 {}ms",
                    context.getTaskId(), context.getChunks().size(),
                    System.currentTimeMillis() - start);
        } catch (Exception e) {
            context.fail(e);
            log.error("[Pipeline] 任务 {} 入库失败: {}", context.getTaskId(), e.getMessage());
        } finally {
            syncTask(context, System.currentTimeMillis() - start);
        }
    }

    /**
     * 把最终状态回写数据库（无论成功失败都要执行）。
     */
    private void syncTask(IngestionContext context, long costMillis) {
        try {
            IngestionTask task = taskRepository.findById(context.getTaskId()).orElse(null);
            if (task != null) {
                task.setState(context.getState().name());
                task.setCurrentNode(context.getCurrentNode());
                task.setProgress(context.getState().progress());
                task.setErrorMessage(context.getError());
                task.setNodeLog(toJson(context.getLogs()));
                // 首次同步时补上开始时间
                if (task.getStartedAt() == null) {
                    task.setStartedAt(LocalDateTime.now());
                }
                task.setFinishedAt(LocalDateTime.now());
                task.setCostMillis(costMillis);
                taskRepository.save(task);
            }

            // 同步文档状态，让"文档列表"页不用 JOIN 任务表
            KnowledgeDocument document = documentRepository.findById(context.getDocId()).orElse(null);
            if (document != null) {
                document.setStatus(context.getState().name());
                if (context.getState() == IngestionState.COMPLETED) {
                    document.setChunkCount(context.getChunks().size());
                    document.setErrorMessage(null);
                } else {
                    document.setErrorMessage(context.getError());
                }
                document.setUpdatedAt(LocalDateTime.now());
                documentRepository.save(document);
            }
        } catch (Exception e) {
            log.error("[Pipeline] 同步任务状态失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 节点日志转 JSON 字符串。
     */
    private String toJson(List<IngestionContext.NodeLog> logs) {
        try {
            List<Map<String, Object>> list = new ArrayList<>();
            for (IngestionContext.NodeLog log : logs) {
                list.add(Map.of(
                        "node", log.node(),
                        "status", log.status(),
                        "costMs", log.costMillis(),
                        "detail", log.detail() == null ? "" : log.detail(),
                        "at", log.at().toString()
                ));
            }
            return objectMapper.writeValueAsString(list);
        } catch (Exception e) {
            return "[]";
        }
    }
}
