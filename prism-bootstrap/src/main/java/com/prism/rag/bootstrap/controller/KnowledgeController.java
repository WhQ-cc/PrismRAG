package com.prism.rag.bootstrap.controller;

import com.prism.rag.common.api.R;
import com.prism.rag.rag.domain.IngestionTask;
import com.prism.rag.rag.domain.KnowledgeBase;
import com.prism.rag.rag.domain.KnowledgeDocument;
import com.prism.rag.rag.service.IngestionService;
import com.prism.rag.rag.service.KnowledgeBaseService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 知识库与入库接口。
 */
@RestController
@RequestMapping("/api")
public class KnowledgeController {

    private final KnowledgeBaseService knowledgeBaseService;
    private final IngestionService ingestionService;

    public KnowledgeController(KnowledgeBaseService knowledgeBaseService,
                               IngestionService ingestionService) {
        this.knowledgeBaseService = knowledgeBaseService;
        this.ingestionService = ingestionService;
    }

    /**
     * 知识库列表。
     */
    @GetMapping("/kb")
    public R<List<KnowledgeBase>> listKb() {
        return R.ok(knowledgeBaseService.list());
    }

    /**
     * 创建知识库。
     */
    @PostMapping("/kb")
    public R<KnowledgeBase> createKb(@RequestBody Map<String, String> body) {
        return R.ok(knowledgeBaseService.create(body.get("name"), body.get("description")));
    }

    /**
     * 知识库统计。
     */
    @GetMapping("/kb/{kbId}/stats")
    public R<Map<String, Object>> stats(@PathVariable Long kbId) {
        return R.ok(knowledgeBaseService.stats(kbId));
    }

    /**
     * 知识库下的文档列表。
     */
    @GetMapping("/kb/{kbId}/documents")
    public R<List<KnowledgeDocument>> documents(@PathVariable Long kbId) {
        return R.ok(knowledgeBaseService.documents(kbId));
    }

    /**
     * 新增文档并触发入库流水线。
     *
     * <p>接口立即返回任务 ID，流水线在后台执行，前端轮询 {@code /api/tasks/{id}} 获取进度。</p>
     */
    @PostMapping("/kb/{kbId}/documents")
    public R<IngestionTask> addDocument(@PathVariable Long kbId, @RequestBody Map<String, String> body) {
        IngestionTask task = ingestionService.submit(
                kbId,
                body.get("title"),
                body.get("fileName"),
                body.getOrDefault("sourceType", "text"),
                body.get("content"));
        return R.ok(task);
    }

    /**
     * 删除文档。
     */
    @DeleteMapping("/documents/{docId}")
    public R<Void> deleteDocument(@PathVariable Long docId) {
        knowledgeBaseService.deleteDocument(docId);
        return R.ok();
    }

    /**
     * 最近的入库任务。
     */
    @GetMapping("/tasks")
    public R<List<IngestionTask>> tasks() {
        return R.ok(ingestionService.recentTasks());
    }

    /**
     * 任务详情（含节点日志）。
     */
    @GetMapping("/tasks/{taskId}")
    public R<IngestionTask> task(@PathVariable Long taskId) {
        return R.ok(ingestionService.getTask(taskId));
    }

    /**
     * 重试失败的任务。
     */
    @PostMapping("/tasks/{taskId}/retry")
    public R<IngestionTask> retry(@PathVariable Long taskId) {
        return R.ok(ingestionService.retry(taskId));
    }
}
