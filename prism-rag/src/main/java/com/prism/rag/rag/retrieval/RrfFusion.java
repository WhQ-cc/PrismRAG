package com.prism.rag.rag.retrieval;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RRF（Reciprocal Rank Fusion，倒数排名融合）算法。
 *
 * <h3>为什么需要它？</h3>
 * <p>向量通道的得分是余弦相似度（0~1），BM25 的得分是无上界的（可能是 12.7、也可能 300）。
 * <b>两者的量纲完全不同，直接加权求和是没有意义的</b>——BM25 分数大就会把向量结果全压下去。</p>
 *
 * <h3>RRF 的聪明之处：只看排名，不看分数</h3>
 * <pre>
 * RRF(d) = Σ_channels  1 / (k + rank_channel(d))
 * </pre>
 * <ul>
 *   <li>{@code rank} 是该文档在<b>这一路</b>里的排名，从 1 开始；</li>
 *   <li>{@code k} 是平滑常数，取 60（来自 Cormack 等人的原始论文经验值），
 *       作用是<b>削弱头部排名的绝对统治力</b>：排第一和排第二的贡献差距
 *       是 1/61 和 1/62，很小。这样"一路排第一 + 一路排最后"往往比"一路排第一 + 另一路没进榜"更靠前，
 *       符合直觉。</li>
 * </ul>
 *
 * <h3>一个例子</h3>
 * <pre>
 * 文档A：向量第 1 名，BM25 没进榜   → 1/(60+1) = 0.0164
 * 文档B：向量没进榜，BM25 第 1 名 → 1/(60+1) = 0.0164
 * 文档C：向量第 2 名，BM25 第 2 名 → 1/62 + 1/62 = 0.0323  ← 两路都认可，排第一
 * </pre>
 * <p>RRF 的价值就在这里：<b>两路都认可的内容会被顶上来</b>，
 * 这正是我们想要的"稳健召回"——只有一路认可的可能是偶然命中。</p>
 *
 * <h3>它不是什么</h3>
 * <p>RRF 不是"更聪明的融合算法"，它的优点是<b>无需调参、不依赖分数归一化</b>，
 * 在多路异构召回（向量/BM25/图谱/联网）场景下是最佳默认选择。
 * 真要更准可以上 Rerank 模型（Cross-Encoder），那需要额外的模型调用，
 * 本项目在 {@code docs/} 里说明了接入位置。</p>
 */
public final class RrfFusion {

    /** 平滑常数，论文推荐 60 */
    public static final int DEFAULT_K = 60;

    private RrfFusion() {
    }

    /**
     * 融合多路召回结果。
     *
     * @param channels 通道名 → 该通道已排好序的命中列表
     * @param topK     融合后保留的条数
     * @return 按融合得分倒序的命中列表
     */
    public static List<RetrievalHit> fuse(Map<String, List<RetrievalHit>> channels, int topK) {
        return fuse(channels, topK, DEFAULT_K);
    }

    /**
     * 融合多路召回结果（可指定 k）。
     */
    public static List<RetrievalHit> fuse(Map<String, List<RetrievalHit>> channels, int topK, int k) {
        Map<Long, Double> fusedScores = new HashMap<>();
        Map<Long, Map<String, Integer>> ranks = new HashMap<>();
        Map<Long, Map<String, Double>> channelScores = new HashMap<>();
        Map<Long, RetrievalHit> holders = new HashMap<>();

        for (Map.Entry<String, List<RetrievalHit>> entry : channels.entrySet()) {
            String channel = entry.getKey();
            List<RetrievalHit> ranked = entry.getValue();
            for (int i = 0; i < ranked.size(); i++) {
                RetrievalHit hit = ranked.get(i);
                int rank = i + 1;
                fusedScores.merge(hit.chunkId(), 1.0 / (k + rank), Double::sum);
                ranks.computeIfAbsent(hit.chunkId(), id -> new HashMap<>()).put(channel, rank);
                channelScores.computeIfAbsent(hit.chunkId(), id -> new HashMap<>())
                        .put(channel, hit.score());
                holders.putIfAbsent(hit.chunkId(), hit);
            }
        }

        List<RetrievalHit> result = new ArrayList<>();
        for (Map.Entry<Long, Double> entry : fusedScores.entrySet()) {
            RetrievalHit hit = holders.get(entry.getKey());
            Map<String, Integer> hitRanks = ranks.get(entry.getKey());
            Map<String, Double> hitScores = channelScores.get(entry.getKey());
            result.add(new RetrievalHit(
                    hit.chunkId(),
                    hit.docId(),
                    hit.kbId(),
                    hit.title(),
                    hit.content(),
                    entry.getValue(),
                    hitScores,
                    hitRanks,
                    List.copyOf(hitRanks.keySet())
            ));
        }
        result.sort(Comparator.comparingDouble(RetrievalHit::score).reversed());
        return result.size() > topK ? result.subList(0, topK) : result;
    }

    /**
     * 便捷方法：把 {@link VectorHit} / {@link Bm25Hit} 转换成统一结构。
     */
    public static Map<String, List<RetrievalHit>> toChannels(List<VectorHit> vectorHits, List<Bm25Hit> bm25Hits) {
        Map<String, List<RetrievalHit>> channels = new LinkedHashMap<>();
        channels.put("vector", vectorHits.stream()
                .map(hit -> new RetrievalHit(hit.chunkId(), hit.docId(), hit.kbId(), null, null,
                        hit.score(), Map.of(), Map.of(), List.of("vector")))
                .toList());
        channels.put("bm25", bm25Hits.stream()
                .map(hit -> new RetrievalHit(hit.chunkId(), hit.docId(), hit.kbId(), null, null,
                        hit.score(), Map.of(), Map.of(), List.of("bm25")))
                .toList());
        return channels;
    }
}
