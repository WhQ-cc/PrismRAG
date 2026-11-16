package com.prism.rag.rag.retrieval;

import java.util.List;
import java.util.Map;

/**
 * 融合后的检索命中（一条 chunk 在多路召回中的综合结果）。
 *
 * @param chunkId       分块 ID
 * @param docId         文档 ID
 * @param kbId          知识库 ID
 * @param title         文档标题（引用卡片展示用）
 * @param content       chunk 正文（喂给模型）
 * @param score         RRF 融合得分
 * @param channelScores 各通道原始得分（向量余弦 / BM25）
 * @param channelRanks  各通道内的排名（从 1 开始）
 * @param channels      命中过的通道名列表（前端用来打"向量命中""关键词命中"标签）
 */
public record RetrievalHit(
        Long chunkId,
        Long docId,
        Long kbId,
        String title,
        String content,
        double score,
        Map<String, Double> channelScores,
        Map<String, Integer> channelRanks,
        List<String> channels
) {
}
