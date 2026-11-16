package com.prism.rag.rag.retrieval;

import com.prism.rag.common.math.VectorUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存向量库（暴力余弦检索）。
 *
 * <p><b>优点：</b>零依赖、零配置、重启后能从数据库重建（见 {@code VectorIndexInitializer}），
 * 非常适合教学和中小规模数据（十万级 chunk 以内，毫秒级响应）。</p>
 *
 * <p><b>局限：</b>暴力遍历是 O(N)。10 万个 chunk、256 维，一次检索要算 2500 万次乘加，
 * 大约几十毫秒——还能接受。但如果上到百万级就必须换 ANN 索引。</p>
 *
 * <p><b>换成向量库时要改什么？</b>只改这一个类：
 * 实现 {@link VectorStore} 接口，把 {@code upsert / search} 换成 Milvus 的
 * {@code upsert / search} 调用即可。其他代码完全无感。</p>
 *
 * <p><b>维度校验很重要：</b>如果入库时用的是 1536 维模型、检索时用的是 256 维模型，
 * 维度对不上会直接抛异常或返回空结果。这里做了显式校验并优雅降级
 * （返回空列表 → 检索链路自动退化为"只用 BM25"），而不是让整个请求 500。</p>
 */
@Component
public class InMemoryVectorStore implements VectorStore {

    private static final Logger log = LoggerFactory.getLogger(InMemoryVectorStore.class);

    /** chunkId → 向量记录 */
    private final Map<Long, VectorRecord> records = new ConcurrentHashMap<>();

    /** 向量维度（以第一条写入的向量为准） */
    private volatile int dimension;

    @Override
    public void upsert(Long chunkId, Long docId, Long kbId, float[] vector) {
        if (chunkId == null || vector == null || vector.length == 0) {
            return;
        }
        if (dimension == 0) {
            dimension = vector.length;
        } else if (dimension != vector.length) {
            // 同一个索引里混入不同维度的向量，说明配置变了或模型换过，必须显式拒绝
            log.warn("[VectorStore] 向量维度不一致（已有 {}，新写入 {}），忽略该条 chunk={}",
                    dimension, vector.length, chunkId);
            return;
        }
        records.put(chunkId, new VectorRecord(chunkId, docId, kbId, vector));
    }

    @Override
    public void removeByDocument(Long docId) {
        records.entrySet().removeIf(entry -> entry.getValue().docId().equals(docId));
    }

    @Override
    public List<VectorHit> search(float[] queryVector, Long kbId, int topK) {
        if (queryVector == null || queryVector.length == 0 || records.isEmpty()) {
            return List.of();
        }
        if (dimension > 0 && queryVector.length != dimension) {
            // 优雅降级：维度不匹配说明"入库模型"与"检索模型"不一致，
            // 此时向量通道直接放弃，检索链路退化为 BM25 单路，而不是整体报错。
            log.warn("[VectorStore] 查询向量维度({}) 与索引维度({}) 不一致，跳过向量通道", queryVector.length, dimension);
            return List.of();
        }

        // 小顶堆保留 topK，避免每次都全量排序
        PriorityQueue<VectorHit> heap = new PriorityQueue<>(Comparator.comparingDouble(VectorHit::score));
        for (VectorRecord record : records.values()) {
            if (kbId != null && !kbId.equals(record.kbId())) {
                continue;
            }
            double score = VectorUtils.cosine(queryVector, record.vector());
            VectorHit hit = new VectorHit(record.chunkId(), record.docId(), record.kbId(), score);
            if (heap.size() < topK) {
                heap.offer(hit);
            } else if (heap.peek() != null && score > heap.peek().score()) {
                heap.poll();
                heap.offer(hit);
            }
        }
        List<VectorHit> result = new ArrayList<>(heap);
        // 倒序输出（堆里是倒着排的）
        result.sort(Comparator.comparingDouble(VectorHit::score).reversed());
        return result;
    }

    @Override
    public int dimension() {
        return dimension;
    }

    @Override
    public int size() {
        return records.size();
    }

    /**
     * 清空索引（重建时用）。
     */
    public void clear() {
        records.clear();
        dimension = 0;
    }

    /**
     * 内部记录。
     */
    private record VectorRecord(Long chunkId, Long docId, Long kbId, float[] vector) {
    }
}
