package com.prism.rag.rag.retrieval;

/**
 * 向量检索命中。
 *
 * @param chunkId 分块 ID
 * @param docId   文档 ID
 * @param kbId    知识库 ID
 * @param score   余弦相似度
 */
public record VectorHit(Long chunkId, Long docId, Long kbId, double score) {
}
