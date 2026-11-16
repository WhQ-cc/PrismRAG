package com.prism.rag.rag.pipeline.node;

import com.prism.rag.common.math.VectorUtils;
import com.prism.rag.rag.domain.KnowledgeBase;
import com.prism.rag.rag.domain.KnowledgeChunk;
import com.prism.rag.rag.domain.KnowledgeDocument;
import com.prism.rag.rag.pipeline.AbstractIngestionNode;
import com.prism.rag.rag.pipeline.IngestionContext;
import com.prism.rag.rag.pipeline.IngestionState;
import com.prism.rag.rag.repository.KnowledgeBaseRepository;
import com.prism.rag.rag.repository.KnowledgeChunkRepository;
import com.prism.rag.rag.repository.KnowledgeDocumentRepository;
import com.prism.rag.rag.retrieval.RetrievalService;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 节点 4：持久化。
 *
 * <p>把 chunk 和向量真正写下去，并且<b>同步更新两个检索索引</b>。</p>
 *
 * <h3>顺序很关键：先删旧数据，再写新数据</h3>
 * <p>重跑入库时，如果只追加不删除，文档改版后旧 chunk 会残留，
 * 检索到旧内容就答错了。所以这里第一步就是清理该文档的历史 chunk 与索引。</p>
 *
 * <h3>为什么"写数据库"和"更新索引"放在一起？</h3>
 * <p>因为这两件事必须<b>保持一致</b>：数据库里有、索引里没有 → 检索不到；
 * 索引里有、数据库里没有 → 检索到但取不到正文（前端会看到空白引用）。
 * 把它们放在同一个节点、同一个事务边界里，语义才完整。</p>
 */
@Component
@Order(4)
public class PersistNode extends AbstractIngestionNode {

    private final KnowledgeChunkRepository chunkRepository;
    private final KnowledgeDocumentRepository documentRepository;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final RetrievalService retrievalService;

    public PersistNode(KnowledgeChunkRepository chunkRepository,
                       KnowledgeDocumentRepository documentRepository,
                       KnowledgeBaseRepository knowledgeBaseRepository,
                       RetrievalService retrievalService) {
        this.chunkRepository = chunkRepository;
        this.documentRepository = documentRepository;
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.retrievalService = retrievalService;
    }

    @Override
    public String name() {
        return "persist";
    }

    @Override
    public IngestionState targetState() {
        return IngestionState.PERSISTING;
    }

    @Override
    @Transactional
    protected void doExecute(IngestionContext context) {
        // ① 清理旧数据（重跑幂等的关键）
        chunkRepository.deleteByDocId(context.getDocId());
        retrievalService.removeDocument(context.getDocId());

        // ② 写入新 chunk
        List<KnowledgeChunk> chunks = new ArrayList<>();
        List<float[]> vectors = context.getVectors();
        for (int i = 0; i < context.getChunks().size(); i++) {
            var draft = context.getChunks().get(i);
            KnowledgeChunk chunk = new KnowledgeChunk();
            chunk.setDocId(context.getDocId());
            chunk.setKbId(context.getKbId());
            chunk.setDocTitle(context.getTitle());
            chunk.setSeq(draft.seq());
            chunk.setContent(draft.content());
            chunk.setTokenCount(draft.tokenCount());
            chunk.setEmbedding(VectorUtils.toBytes(vectors.get(i)));
            chunk.setCreatedAt(LocalDateTime.now());
            chunks.add(chunk);
        }
        List<KnowledgeChunk> saved = chunkRepository.saveAll(chunks);

        // ③ 同步两个检索索引（向量库 + BM25 倒排）
        for (int i = 0; i < saved.size(); i++) {
            retrievalService.indexChunk(saved.get(i), vectors.get(i));
        }

        // ④ 更新文档状态
        KnowledgeDocument document = documentRepository.findById(context.getDocId()).orElse(null);
        if (document != null) {
            document.setStatus(IngestionState.PERSISTING.getLabel());
            document.setChunkCount(saved.size());
            document.setUpdatedAt(LocalDateTime.now());
            documentRepository.save(document);
        }

        // ⑤ 同步知识库的统计数字（列表页直接展示，避免每次 count）
        KnowledgeBase kb = knowledgeBaseRepository.findById(context.getKbId()).orElse(null);
        if (kb != null) {
            kb.setDocumentCount((int) documentRepository.countByKbId(context.getKbId()));
            kb.setChunkCount((int) chunkRepository.countByKbId(context.getKbId()));
            kb.setUpdatedAt(LocalDateTime.now());
            knowledgeBaseRepository.save(kb);
        }
    }

    @Override
    protected String describeResult(IngestionContext context) {
        return "已写入 " + context.getChunks().size() + " 个分块并更新检索索引";
    }
}
