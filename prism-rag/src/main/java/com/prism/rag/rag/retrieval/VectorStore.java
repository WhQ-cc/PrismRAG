package com.prism.rag.rag.retrieval;

import java.util.List;

/**
 * 向量存储（检索通道之一）。
 *
 * <p><b>这是一个刻意保留的抽象。</b>本项目用内存实现它，
 * 但换成 Milvus / PGVector / Elasticsearch / 腾讯云向量数据库，
 * 只需要再写一个实现类，检索主流程一行不改。</p>
 *
 * <p>真实向量库相比内存实现多出来的能力：</p>
 * <ul>
 *   <li><b>ANN 索引</b>（HNSW / IVF）：千万级向量用暴力遍历会超时；</li>
 *   <li><b>持久化</b>：进程重启不用重新灌数据；</li>
 *   <li><b>分布式</b>：数据分片、多副本。</li>
 * </ul>
 */
public interface VectorStore {

    /**
     * 写入/覆盖一个分块的向量。
     */
    void upsert(Long chunkId, Long docId, Long kbId, float[] vector);

    /**
     * 删除某个文档的全部分块（重跑入库前必须先删，否则会残留脏数据）。
     */
    void removeByDocument(Long docId);

    /**
     * 余弦相似度检索。
     *
     * @param queryVector 查询向量
     * @param kbId        知识库过滤（数据隔离）
     * @param topK        返回条数
     * @return 命中列表，按相似度倒序
     */
    List<VectorHit> search(float[] queryVector, Long kbId, int topK);

    /**
     * 当前存储的向量维度（用于校验查询向量是否匹配）。
     */
    int dimension();

    /**
     * 存储的向量条数。
     */
    int size();
}
