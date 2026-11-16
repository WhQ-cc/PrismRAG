package com.prism.rag.rag.retrieval;

import com.prism.rag.common.text.Tokenizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * BM25 倒排索引（关键词检索通道）。
 *
 * <p><b>为什么必须有这条通道？</b>向量检索擅长"语义相近"，但对<b>精确匹配</b>很弱。</p>
 * <p>典型场景：用户问"订单号 SO20240315001 怎么查"，或者文档里有个专有名词"小电驴项目"——
 * 向量模型根本没见过这个词，算出来的余弦相似度可能很低（词袋里都是噪声），
 * 但 BM25 靠<b>倒排索引</b>精确命中这个 term，排名直接冲到第一。</p>
 * <p>反过来，BM25 完全不懂同义词：用户问"车辆"、文档写"汽车"，一个字都不重合，BM25 零召回。</p>
 * <p><b>所以两路都要有，再用 RRF 融合。</b>这是双路召回的根本原因。</p>
 *
 * <h3>BM25 打分公式</h3>
 * <pre>
 * score(Q, D) = Σ  IDF(q_i) · ( f(q_i, D) · (k1 + 1) )
 *                       ─────────────────────────────────────
 *                       f(q_i, D) + k1 · (1 - b + b · |D|/avgdl)
 * </pre>
 * <ul>
 *   <li><b>IDF</b>：词越稀有，权重越高。稀有词几乎命中 = 强信号。</li>
 *   <li><b>k1</b>（取 1.2）：控制词频饱和速度。词出现 10 次不会比出现 2 次重要 5 倍，
 *       否则一篇文档反复提"报销"就会霸榜。</li>
 *   <li><b>b</b>（取 0.75）：控制文档长度惩罚。长度归一化，防止长文档仅仅因为"长"就命中更多词。</li>
 * </ul>
 *
 * <h3>倒排索引的结构</h3>
 * <pre>
 *   词 → { chunkId: 词频, chunkId: 词频, ... }
 * </pre>
 * <p>查询时不需要扫全文，只需要按词查倒排表取并集，再算分。这是 BM25 快的原因，
 * 也是它和向量检索的另一个本质区别：<b>BM25 是精确的、词级的；向量是模糊的、语义级的。</b></p>
 */
@Component
public class Bm25Index {

    private static final Logger log = LoggerFactory.getLogger(Bm25Index.class);

    /** 词频饱和参数 */
    private static final double K1 = 1.2;

    /** 长度归一化参数 */
    private static final double B = 0.75;

    /** 倒排索引：词 → (chunkId → 词频) */
    private final Map<String, Map<Long, Integer>> invertedIndex = new ConcurrentHashMap<>();

    /** chunk 元信息 */
    private final Map<Long, ChunkMeta> chunkMeta = new ConcurrentHashMap<>();

    /** chunk → 它包含的词（删除文档时用来精确清理倒排表） */
    private final Map<Long, Set<String>> chunkTerms = new ConcurrentHashMap<>();

    /** 总词元数（用于算平均文档长度） */
    private volatile long totalTerms = 0;

    /** 总 chunk 数 */
    private volatile long totalChunks = 0;

    /**
     * 索引一个 chunk。
     */
    public synchronized void add(Long chunkId, Long docId, Long kbId, String content) {
        if (chunkId == null || content == null) {
            return;
        }
        // 重复索引同一个 chunk，先清掉旧的
        if (chunkMeta.containsKey(chunkId)) {
            removeChunk(chunkId);
        }
        List<String> tokens = Tokenizer.tokenize(content);
        if (tokens.isEmpty()) {
            return;
        }
        Map<String, Integer> termFrequencies = new HashMap<>();
        for (String token : tokens) {
            termFrequencies.merge(token, 1, Integer::sum);
        }
        for (Map.Entry<String, Integer> entry : termFrequencies.entrySet()) {
            invertedIndex.computeIfAbsent(entry.getKey(), k -> new ConcurrentHashMap<>())
                    .put(chunkId, entry.getValue());
        }
        chunkTerms.put(chunkId, new HashSet<>(termFrequencies.keySet()));
        chunkMeta.put(chunkId, new ChunkMeta(chunkId, docId, kbId, tokens.size()));
        totalTerms += tokens.size();
        totalChunks++;
    }

    /**
     * 批量索引（启动时从数据库重建索引用）。
     */
    public void addAll(List<ChunkMeta> chunks, Map<Long, String> contents) {
        for (ChunkMeta meta : chunks) {
            String content = contents.get(meta.chunkId());
            if (content != null) {
                add(meta.chunkId(), meta.docId(), meta.kbId(), content);
            }
        }
        log.info("[BM25] 索引重建完成：{} 个 chunk，{} 个词项，平均长度 {:.1f}",
                totalChunks, invertedIndex.size(), averageDocLength());
    }

    /**
     * 删除某个文档的全部分块。
     */
    public synchronized void removeByDocument(Long docId) {
        List<Long> toRemove = chunkMeta.values().stream()
                .filter(meta -> meta.docId().equals(docId))
                .map(ChunkMeta::chunkId)
                .toList();
        for (Long chunkId : toRemove) {
            removeChunk(chunkId);
        }
    }

    /**
     * 删除单个 chunk 的索引。
     */
    private void removeChunk(Long chunkId) {
        Set<String> terms = chunkTerms.remove(chunkId);
        if (terms != null) {
            for (String term : terms) {
                Map<Long, Integer> postings = invertedIndex.get(term);
                if (postings != null) {
                    postings.remove(chunkId);
                    if (postings.isEmpty()) {
                        invertedIndex.remove(term);
                    }
                }
            }
        }
        ChunkMeta meta = chunkMeta.remove(chunkId);
        if (meta != null) {
            totalTerms -= meta.tokenCount();
            totalChunks--;
        }
    }

    /**
     * BM25 检索。
     *
     * @param query 查询串
     * @param kbId  知识库过滤
     * @param topK  返回条数
     */
    public List<Bm25Hit> search(String query, Long kbId, int topK) {
        if (query == null || query.isBlank() || invertedIndex.isEmpty()) {
            return List.of();
        }
        List<String> queryTokens = Tokenizer.tokenize(query);
        if (queryTokens.isEmpty()) {
            return List.of();
        }
        double avgdl = averageDocLength();
        int n = (int) totalChunks;
        Map<Long, Double> scores = new HashMap<>();

        for (String token : queryTokens) {
            Map<Long, Integer> postings = invertedIndex.get(token);
            if (postings == null || postings.isEmpty()) {
                continue;
            }
            int df = postings.size();
            // IDF：稀有词权重大。这里做平滑，避免 df 接近 N 时出现负数。
            double idf = Math.log(1 + (n - df + 0.5) / (df + 0.5));
            for (Map.Entry<Long, Integer> posting : postings.entrySet()) {
                Long chunkId = posting.getKey();
                ChunkMeta meta = chunkMeta.get(chunkId);
                if (meta == null) {
                    continue;
                }
                if (kbId != null && !kbId.equals(meta.kbId())) {
                    continue;
                }
                double tf = posting.getValue();
                double norm = 1 - B + B * (meta.tokenCount() / avgdl);
                double score = idf * (tf * (K1 + 1)) / (tf + K1 * norm);
                scores.merge(chunkId, score, Double::sum);
            }
        }

        return scores.entrySet().stream()
                .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
                .limit(topK)
                .map(entry -> {
                    ChunkMeta meta = chunkMeta.get(entry.getKey());
                    return new Bm25Hit(meta.chunkId(), meta.docId(), meta.kbId(), entry.getValue());
                })
                .toList();
    }

    /**
     * 平均文档长度。
     */
    private double averageDocLength() {
        if (totalChunks == 0) {
            return 1;
        }
        return (double) totalTerms / totalChunks;
    }

    public int size() {
        return chunkMeta.size();
    }

    public int termCount() {
        return invertedIndex.size();
    }

    public void clear() {
        invertedIndex.clear();
        chunkMeta.clear();
        chunkTerms.clear();
        totalTerms = 0;
        totalChunks = 0;
    }

    /**
     * chunk 元信息。
     *
     * @param chunkId   分块 ID
     * @param docId     文档 ID
     * @param kbId      知识库 ID
     * @param tokenCount 词元数（用于长度归一化）
     */
    public record ChunkMeta(Long chunkId, Long docId, Long kbId, int tokenCount) {
    }
}
