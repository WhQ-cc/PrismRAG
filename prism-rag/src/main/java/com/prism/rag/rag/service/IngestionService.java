package com.prism.rag.rag.service;

import com.prism.rag.common.api.ErrorCode;
import com.prism.rag.common.exception.BizException;
import com.prism.rag.rag.domain.IngestionTask;
import com.prism.rag.rag.domain.KnowledgeBase;
import com.prism.rag.rag.domain.KnowledgeDocument;
import com.prism.rag.rag.pipeline.IngestionContext;
import com.prism.rag.rag.pipeline.IngestionPipeline;
import com.prism.rag.rag.pipeline.IngestionState;
import com.prism.rag.rag.repository.IngestionTaskRepository;
import com.prism.rag.rag.repository.KnowledgeBaseRepository;
import com.prism.rag.rag.repository.KnowledgeDocumentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 入库服务：对外的"提交入库"入口。
 *
 * <p>职责边界很清楚：<b>只负责建任务 + 提交，不负责执行</b>。
 * 执行是 {@link IngestionPipeline} 的事。这种"编排与执行分离"的好处是：
 * 将来把执行换成 MQ 消费者，这里一行都不用改。</p>
 */
@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final KnowledgeDocumentRepository documentRepository;
    private final IngestionTaskRepository taskRepository;
    private final IngestionPipeline pipeline;

    public IngestionService(KnowledgeBaseRepository knowledgeBaseRepository,
                            KnowledgeDocumentRepository documentRepository,
                            IngestionTaskRepository taskRepository,
                            IngestionPipeline pipeline) {
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.documentRepository = documentRepository;
        this.taskRepository = taskRepository;
        this.pipeline = pipeline;
    }

    /**
     * 创建一个文档并提交入库。
     *
     * @param kbId       知识库 ID
     * @param title      标题
     * @param fileName   文件名（可选）
     * @param sourceType 来源类型：text / markdown / csv
     * @param content    正文
     * @return 入库任务
     */
    @Transactional
    public IngestionTask submit(Long kbId, String title, String fileName, String sourceType, String content) {
        KnowledgeBase kb = knowledgeBaseRepository.findById(kbId)
                .orElseThrow(() -> new BizException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND, "知识库不存在: " + kbId));
        if (content == null || content.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "文档内容不能为空");
        }

        LocalDateTime now = LocalDateTime.now();

        // ① 建文档
        KnowledgeDocument document = new KnowledgeDocument();
        document.setKbId(kbId);
        document.setTitle(title == null || title.isBlank() ? "未命名文档" : title);
        document.setFileName(fileName);
        document.setSourceType(sourceType == null ? "text" : sourceType);
        document.setContent(content);
        document.setStatus(IngestionState.PENDING.name());
        document.setChunkCount(0);
        document.setCreatedAt(now);
        document.setUpdatedAt(now);
        document = documentRepository.save(document);

        // ② 建任务
        IngestionTask task = new IngestionTask();
        task.setDocId(document.getId());
        task.setKbId(kbId);
        task.setDocTitle(document.getTitle());
        task.setState(IngestionState.PENDING.name());
        task.setProgress(0);
        task.setNodeLog("[]");
        task.setCreatedAt(now);
        task = taskRepository.save(task);

        // ③ 提交后台执行
        IngestionContext context = new IngestionContext(
                task.getId(), document.getId(), kbId, document.getTitle(),
                fileName, document.getSourceType(), content);
        pipeline.submit(context);

        log.info("[Ingestion] 已提交入库任务 task={} doc={} kb={} 《{}》",
                task.getId(), document.getId(), kbId, document.getTitle());
        return task;
    }

    /**
     * 重试一个失败/取消的任务。
     */
    @Transactional
    public IngestionTask retry(Long taskId) {
        IngestionTask task = taskRepository.findById(taskId)
                .orElseThrow(() -> new BizException(ErrorCode.TASK_NOT_FOUND, "任务不存在: " + taskId));
        KnowledgeDocument document = documentRepository.findById(task.getDocId())
                .orElseThrow(() -> new BizException(ErrorCode.DOCUMENT_NOT_FOUND, "文档不存在: " + task.getDocId()));

        // 重试 = 从 PENDING 重新开始（状态机允许 FAILED/CANCELLED → PENDING）
        task.setState(IngestionState.PENDING.name());
        task.setProgress(0);
        task.setErrorMessage(null);
        task.setCreatedAt(LocalDateTime.now());
        taskRepository.save(task);

        IngestionContext context = new IngestionContext(
                task.getId(), document.getId(), task.getKbId(), document.getTitle(),
                document.getFileName(), document.getSourceType(), document.getContent());
        pipeline.submit(context);
        log.info("[Ingestion] 已重试任务 task={}", taskId);
        return task;
    }

    /**
     * 查询任务。
     */
    public IngestionTask getTask(Long taskId) {
        return taskRepository.findById(taskId)
                .orElseThrow(() -> new BizException(ErrorCode.TASK_NOT_FOUND, "任务不存在: " + taskId));
    }

    /**
     * 最近的任务列表。
     */
    public List<IngestionTask> recentTasks() {
        return taskRepository.findTop50ByOrderByIdDesc();
    }

    /**
     * 某个文档的任务历史。
     */
    public List<IngestionTask> tasksOfDocument(Long docId) {
        return taskRepository.findByDocIdOrderByIdDesc(docId);
    }
}
