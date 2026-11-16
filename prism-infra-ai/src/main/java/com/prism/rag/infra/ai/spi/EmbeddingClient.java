package com.prism.rag.infra.ai.spi;

import java.util.List;

/**
 * 向量化客户端（SPI）。
 *
 * <p>负责把一段文本变成定长浮点数组。入库流水线用它给 chunk 生成向量，
 * 检索时用它把用户问题变成向量去做余弦相似度检索。</p>
 *
 * <p><b>注意维度一致性：</b>不同模型的向量维度不同
 * （OpenAI text-embedding-3-small 是 1536 维，本项目本地模型是 256 维）。
 * 用同一个模型入库、必须用同一个模型检索，否则维度对不上，检索结果会是空的。
 * 这是新手最容易踩的坑之一。</p>
 */
public interface EmbeddingClient {

    /**
     * 提供者标识。
     */
    String provider();

    /**
     * 模型标识。
     */
    String model();

    /**
     * 向量维度。
     */
    int dimension();

    /**
     * 批量向量化。
     *
     * @param texts 待向量化的文本
     * @return 与入参一一对应的向量数组
     */
    List<float[]> embed(List<String> texts);
}
