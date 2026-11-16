package com.prism.rag.rag.retrieval;

/**
 * BM25 检索命中。
 *
 * @param chunkId 分块 ID
 * @param docId   文档 ID
 * @param kbId    知识库 ID
 * @param score   BM25 得分（无上界，越大越相关）
 */
public record Bm25Hit(Long chunkId, Long docId, Long kbId, double score) {
}
