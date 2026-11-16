package com.prism.rag.rag.service;

import com.prism.rag.common.api.ErrorCode;
import com.prism.rag.common.exception.BizException;
import com.prism.rag.rag.domain.KnowledgeBase;
import com.prism.rag.rag.domain.KnowledgeChunk;
import com.prism.rag.rag.domain.KnowledgeDocument;
import com.prism.rag.rag.repository.KnowledgeBaseRepository;
import com.prism.rag.rag.repository.KnowledgeChunkRepository;
import com.prism.rag.rag.repository.KnowledgeDocumentRepository;
import com.prism.rag.rag.retrieval.RetrievalService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库管理服务。
 */
@Service
public class KnowledgeBaseService {

    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final KnowledgeDocumentRepository documentRepository;
    private final KnowledgeChunkRepository chunkRepository;
    private final RetrievalService retrievalService;

    public KnowledgeBaseService(KnowledgeBaseRepository knowledgeBaseRepository,
                                KnowledgeDocumentRepository documentRepository,
                                KnowledgeChunkRepository chunkRepository,
                                RetrievalService retrievalService) {
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
        this.retrievalService = retrievalService;
    }

    /**
     * 创建知识库。
     */
    public KnowledgeBase create(String name, String description) {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setName(name);
        kb.setDescription(description);
        kb.setDocumentCount(0);
        kb.setChunkCount(0);
        kb.setCreatedAt(LocalDateTime.now());
        kb.setUpdatedAt(LocalDateTime.now());
        return knowledgeBaseRepository.save(kb);
    }

    public List<KnowledgeBase> list() {
        return knowledgeBaseRepository.findAllByOrderByIdDesc();
    }

    public KnowledgeBase get(Long id) {
        return knowledgeBaseRepository.findById(id)
                .orElseThrow(() -> new BizException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND, "知识库不存在: " + id));
    }

    public List<KnowledgeDocument> documents(Long kbId) {
        return documentRepository.findByKbIdOrderByIdDesc(kbId);
    }

    /**
     * 删除文档（同时清理分块与检索索引）。
     *
     * <p><b>顺序很重要：</b>先删索引再删数据。
     * 如果先删数据、后删索引，中途失败就会留下"索引里有、库里没有"的脏数据，
     * 检索到时会拿到空内容。</p>
     */
    @Transactional
    public void deleteDocument(Long docId) {
        KnowledgeDocument document = documentRepository.findById(docId)
                .orElseThrow(() -> new BizException(ErrorCode.DOCUMENT_NOT_FOUND, "文档不存在: " + docId));
        // ① 先清理检索索引
        retrievalService.removeDocument(docId);
        // ② 再删分块
        chunkRepository.deleteByDocId(docId);
        // ③ 最后删文档
        documentRepository.delete(document);
    }

    /**
     * 知识库统计。
     */
    public Map<String, Object> stats(Long kbId) {
        Map<String, Object> stats = new LinkedHashMap<>();
        long docCount = documentRepository.countByKbId(kbId);
        long chunkCount = chunkRepository.countByKbId(kbId);
        stats.put("knowledgeBaseId", kbId);
        stats.put("documentCount", docCount);
        stats.put("chunkCount", chunkCount);
        return stats;
    }

    /**
     * 某个文档的全部分块（引用溯源时展开原文用）。
     */
    public List<KnowledgeChunk> chunksOfDocument(Long docId) {
        return chunkRepository.findByDocIdOrderBySeqAsc(docId);
    }
}
