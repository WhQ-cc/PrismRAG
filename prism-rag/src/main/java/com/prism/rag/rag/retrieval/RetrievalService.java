package com.prism.rag.rag.retrieval;

import com.prism.rag.common.math.VectorUtils;
import com.prism.rag.infra.ai.spi.EmbeddingClient;
import com.prism.rag.rag.domain.KnowledgeChunk;
import com.prism.rag.rag.repository.KnowledgeChunkRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * 双路召回服务：向量（余弦） + BM25（倒排） → RRF 融合。
 *
 * <h3>整体流程</h3>
 * <pre>
 *                      ┌─ 向量通道 ─→ 内存向量库暴力余弦检索 ─┐
 *   用户问题 ──分词/向量化─┤                                    ├─→ RRF 融合 ─→ TopK
 *                      └─ BM25 通道 ─→ 倒排索引打分 ─────────┘
 * </pre>
 *
 * <h3>三个工程要点</h3>
 * <ol>
 *   <li><b>两路并行</b>：用 {@code CompletableFuture} 同时发起，
 *       总耗时 = max(两路) 而不是 sum(两路)。两路跑在<b>独立的检索线程池</b>上，
 *       不占用问答线程。</li>
 *   <li><b>单路失败不影响整体</b>：向量通道维度不匹配、BM25 索引为空……
 *       任何一路抛异常，都降级为"只用另一路"，而不是让整个问答 500。
 *       <b>可用性优先于完美性</b>，这是线上系统的基本要求。</li>
 *   <li><b>融合后再查库</b>：索引里只存 ID 和分数，正文最后统一从数据库批量取一次，
 *       避免每路各查一次数据库。</li>
 * </ol>
 */
@Service
public class RetrievalService {

    private static final Logger log = LoggerFactory.getLogger(RetrievalService.class);

    /** 每路召回的候选数 = topK 的倍数，给 RRF 留出融合空间 */
    private static final int CANDIDATE_MULTIPLIER = 3;

    private final VectorStore vectorStore;
    private final Bm25Index bm25Index;
    private final EmbeddingClient embeddingClient;
    private final KnowledgeChunkRepository chunkRepository;
    private final Executor retrievalExecutor;

    public RetrievalService(VectorStore vectorStore,
                            Bm25Index bm25Index,
                            EmbeddingClient embeddingClient,
                            KnowledgeChunkRepository chunkRepository,
                            @Qualifier("retrievalExecutor") Executor retrievalExecutor) {
        this.vectorStore = vectorStore;
        this.bm25Index = bm25Index;
        this.embeddingClient = embeddingClient;
        this.chunkRepository = chunkRepository;
        this.retrievalExecutor = retrievalExecutor;
    }

    /**
     * 执行一次双路召回。
     *
     * @param query 用户问题
     * @param kbId  知识库过滤（null 表示全库）
     * @param topK  最终返回条数
     */
    public RetrievalResult retrieve(String query, Long kbId, int topK) {
        long start = System.currentTimeMillis();
        int candidates = Math.max(topK * CANDIDATE_MULTIPLIER, 10);
        boolean degraded = false;
        String degradeReason = null;

        // ① 查询向量化（放在两路之前，因为它只是一次模型调用）
        float[] queryVector = null;
        try {
            List<float[]> vectors = embeddingClient.embed(List.of(query));
            if (!vectors.isEmpty()) {
                queryVector = VectorUtils.normalize(vectors.get(0));
            }
        } catch (Exception e) {
            log.warn("[Retrieval] 查询向量化失败，降级为仅 BM25 检索: {}", e.getMessage());
            degraded = true;
            degradeReason = "查询向量化失败: " + e.getMessage();
        }

        // ② 两路并行召回
        // 注意：lambda 只能捕获"有效 final"变量，这里用 final 引用把结果固定下来
        final float[] queryVectorFinal = queryVector;
        CompletableFuture<List<VectorHit>> vectorFuture = CompletableFuture.supplyAsync(() -> {
            if (queryVectorFinal == null) {
                return List.<VectorHit>of();
            }
            try {
                return vectorStore.search(queryVectorFinal, kbId, candidates);
            } catch (Exception e) {
                log.warn("[Retrieval] 向量通道异常，降级: {}", e.getMessage());
                return List.<VectorHit>of();
            }
        }, retrievalExecutor);

        CompletableFuture<List<Bm25Hit>> bm25Future = CompletableFuture.supplyAsync(() -> {
            try {
                return bm25Index.search(query, kbId, candidates);
            } catch (Exception e) {
                log.warn("[Retrieval] BM25 通道异常，降级: {}", e.getMessage());
                return List.<Bm25Hit>of();
            }
        }, retrievalExecutor);

        List<VectorHit> vectorHits = join(vectorFuture);
        List<Bm25Hit> bm25Hits = join(bm25Future);

        if (vectorHits.isEmpty() && !bm25Hits.isEmpty()) {
            degraded = true;
            degradeReason = "向量通道无结果，仅使用 BM25";
        } else if (bm25Hits.isEmpty() && !vectorHits.isEmpty()) {
            degraded = true;
            degradeReason = "BM25 通道无结果，仅使用向量";
        }

        // ③ RRF 融合
        Map<String, List<RetrievalHit>> channels = RrfFusion.toChannels(vectorHits, bm25Hits);
        List<RetrievalHit> fused = RrfFusion.fuse(channels, topK);

        // ④ 批量取正文与标题
        List<Long> chunkIds = fused.stream().map(RetrievalHit::chunkId).toList();
        Map<Long, KnowledgeChunk> chunkMap = new HashMap<>();
        if (!chunkIds.isEmpty()) {
            for (KnowledgeChunk chunk : chunkRepository.findByIdIn(chunkIds)) {
                chunkMap.put(chunk.getId(), chunk);
            }
        }

        List<RetrievalHit> enriched = new ArrayList<>();
        for (RetrievalHit hit : fused) {
            KnowledgeChunk chunk = chunkMap.get(hit.chunkId());
            if (chunk == null) {
                // 索引里有但数据库没有：说明数据不一致（删过文档但索引没同步），跳过
                continue;
            }
            enriched.add(new RetrievalHit(
                    hit.chunkId(), hit.docId(), hit.kbId(),
                    chunk.getDocTitle(), chunk.getContent(),
                    hit.score(), hit.channelScores(), hit.channelRanks(), hit.channels()
            ));
        }

        long elapsed = System.currentTimeMillis() - start;
        log.info("[Retrieval] query={} kb={} topK={} 命中 向量{} 条 / BM25{} 条 / 融合{} 条，耗时 {}ms{}",
                query, kbId, topK, vectorHits.size(), bm25Hits.size(), enriched.size(), elapsed,
                degraded ? "（降级：" + degradeReason + "）" : "");

        return new RetrievalResult(enriched, vectorHits, bm25Hits, elapsed, degraded, degradeReason);
    }

    /**
     * 把一个 chunk 同时写入两个检索通道。
     *
     * <p>这就是 ragent 里提到的"装饰器"思路的简化版：
     * 主流程只管"存一个 chunk"，至于要同步更新几个索引，由这里统一处理。</p>
     */
    public void indexChunk(KnowledgeChunk chunk, float[] vector) {
        if (chunk.getId() == null) {
            return;
        }
        vectorStore.upsert(chunk.getId(), chunk.getDocId(), chunk.getKbId(), vector);
        bm25Index.add(chunk.getId(), chunk.getDocId(), chunk.getKbId(), chunk.getContent());
    }

    /**
     * 删除某个文档在所有检索通道中的索引。
     */
    public void removeDocument(Long docId) {
        vectorStore.removeByDocument(docId);
        bm25Index.removeByDocument(docId);
    }

    /**
     * 等待异步结果，任何异常都降级成空列表。
     */
    private <T> List<T> join(CompletableFuture<List<T>> future) {
        try {
            return future.join();
        } catch (Exception e) {
            log.warn("[Retrieval] 异步召回失败: {}", e.getMessage());
            return List.of();
        }
    }
}
