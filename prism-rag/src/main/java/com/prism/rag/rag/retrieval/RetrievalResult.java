package com.prism.rag.rag.retrieval;

import java.util.List;

/**
 * 一次检索的完整结果。
 *
 * @param hits          RRF 融合后的最终命中（喂给模型的就是这些）
 * @param vectorHits    向量通道原始命中（调试/前端可视化用）
 * @param bm25Hits      BM25 通道原始命中
 * @param elapsedMillis 检索总耗时
 * @param degraded      是否发生降级（例如向量维度不匹配导致只剩单路）
 * @param degradeReason 降级原因
 */
public record RetrievalResult(
        List<RetrievalHit> hits,
        List<VectorHit> vectorHits,
        List<Bm25Hit> bm25Hits,
        long elapsedMillis,
        boolean degraded,
        String degradeReason
) {
}
