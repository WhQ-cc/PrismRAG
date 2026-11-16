package com.prism.rag.rag.retrieval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RRF 融合测试。
 */
class RrfFusionTest {

    private RetrievalHit hit(long chunkId, double score) {
        return new RetrievalHit(chunkId, 1L, 1L, "doc", "content", score, Map.of(), Map.of(), List.of());
    }

    @Test
    @DisplayName("两路都认可的文档排在只有一路认可的前面")
    void shouldPreferDocsConfirmedByBothChannels() {
        Map<String, List<RetrievalHit>> channels = new LinkedHashMap<>();
        // A：向量第1、BM25 没进榜
        channels.put("vector", List.of(hit(1, 0.9), hit(2, 0.8)));
        // B：BM25 第1、向量第2
        channels.put("bm25", List.of(hit(2, 12.0), hit(3, 5.0)));

        List<RetrievalHit> fused = RrfFusion.fuse(channels, 5);

        assertEquals(2L, fused.get(0).chunkId(), "两路都排第2的 chunk 应排第一");
        assertTrue(fused.get(0).score() > fused.get(1).score(), "首位得分应更高");
    }

    @Test
    @DisplayName("融合得分等于各路 1/(k+rank) 之和")
    void shouldComputeExpectedScore() {
        Map<String, List<RetrievalHit>> channels = new LinkedHashMap<>();
        channels.put("vector", List.of(hit(1, 0.9)));
        channels.put("bm25", List.of(hit(1, 10.0)));

        List<RetrievalHit> fused = RrfFusion.fuse(channels, 5);
        double expected = 1.0 / (60 + 1) + 1.0 / (60 + 1);
        assertEquals(expected, fused.get(0).score(), 1e-9);
    }

    @Test
    @DisplayName("topK 截断生效")
    void shouldRespectTopK() {
        Map<String, List<RetrievalHit>> channels = new LinkedHashMap<>();
        channels.put("vector", List.of(hit(1, 0.9), hit(2, 0.8), hit(3, 0.7), hit(4, 0.6)));
        assertEquals(2, RrfFusion.fuse(channels, 2).size());
    }

    @Test
    @DisplayName("记录了每路的排名与命中通道")
    void shouldKeepChannelRanks() {
        Map<String, List<RetrievalHit>> channels = new LinkedHashMap<>();
        channels.put("vector", List.of(hit(7, 0.9)));
        channels.put("bm25", List.of(hit(7, 5.0)));

        RetrievalHit fused = RrfFusion.fuse(channels, 5).get(0);
        assertEquals(1, fused.channelRanks().get("vector"));
        assertEquals(1, fused.channelRanks().get("bm25"));
        assertTrue(fused.channels().contains("vector"));
        assertTrue(fused.channels().contains("bm25"));
    }
}
