package com.prism.rag.rag.retrieval;

import com.prism.rag.common.math.VectorUtils;
import com.prism.rag.rag.domain.KnowledgeChunk;
import com.prism.rag.rag.repository.KnowledgeChunkRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 检索索引重建器。
 *
 * <h3>为什么需要它？</h3>
 * <p>本项目的向量索引和 BM25 倒排索引都放在<b>内存</b>里（为了教学直观、零依赖）。
 * 进程一重启，内存索引就没了，而数据库里的 chunk 和向量还在。
 * 如果不重建，用户会发现"文档明明入库了，但问什么都搜不到"。</p>
 *
 * <p>所以在应用启动完成后，从数据库把全部分块重新灌进两个索引。</p>
 *
 * <h3>生产环境怎么做？</h3>
 * <p>真实项目里索引是持久化的（Milvus / ES），不需要重建；
 * 但"启动时检查索引与数据是否一致"这件事仍然要做，
 * 只是变成了"校验 + 增量补齐"而不是"全量重建"。</p>
 */
@Component
public class RetrievalIndexInitializer {

    private static final Logger log = LoggerFactory.getLogger(RetrievalIndexInitializer.class);

    private final KnowledgeChunkRepository chunkRepository;
    private final VectorStore vectorStore;
    private final Bm25Index bm25Index;

    public RetrievalIndexInitializer(KnowledgeChunkRepository chunkRepository,
                                     VectorStore vectorStore,
                                     Bm25Index bm25Index) {
        this.chunkRepository = chunkRepository;
        this.vectorStore = vectorStore;
        this.bm25Index = bm25Index;
    }

    /**
     * 应用启动完成后重建索引。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void rebuild() {
        long start = System.currentTimeMillis();
        List<KnowledgeChunk> chunks = chunkRepository.findAll();
        if (chunks.isEmpty()) {
            log.info("[Index] 没有任何分块，跳过索引重建");
            return;
        }
        List<Bm25Index.ChunkMeta> metas = new ArrayList<>(chunks.size());
        Map<Long, String> contents = new HashMap<>(chunks.size());
        int vectorCount = 0;
        for (KnowledgeChunk chunk : chunks) {
            metas.add(new Bm25Index.ChunkMeta(chunk.getId(), chunk.getDocId(), chunk.getKbId(),
                    chunk.getTokenCount() == null ? 0 : chunk.getTokenCount()));
            contents.put(chunk.getId(), chunk.getContent());
            float[] vector = VectorUtils.fromBytes(chunk.getEmbedding());
            if (vector.length > 0) {
                vectorStore.upsert(chunk.getId(), chunk.getDocId(), chunk.getKbId(), vector);
                vectorCount++;
            }
        }
        bm25Index.addAll(metas, contents);
        log.info("[Index] 索引重建完成：{} 个分块（向量 {} 个），耗时 {}ms",
                chunks.size(), vectorCount, System.currentTimeMillis() - start);
    }
}
