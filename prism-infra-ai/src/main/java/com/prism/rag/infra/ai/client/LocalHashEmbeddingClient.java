package com.prism.rag.infra.ai.client;

import com.prism.rag.common.math.VectorUtils;
import com.prism.rag.common.text.Tokenizer;
import com.prism.rag.infra.ai.spi.EmbeddingClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 离线本地向量模型（Hashing Trick / 随机投影）。
 *
 * <p><b>它是什么？</b>一个不需要任何模型文件、不需要网络、不需要 GPU 的"伪向量模型"。</p>
 *
 * <p><b>它怎么工作？</b></p>
 * <ol>
 *   <li>用 {@link Tokenizer} 把文本切成词元（中文单字 + 二字组，英文单词）；</li>
 *   <li>每个词元用哈希函数映射到固定维度向量的一维上（哈希技巧 Hashing Trick）；</li>
 *   <li>用带符号哈希（signed hashing）抑制碰撞偏差：不同词可能映射到同一维，
 *       用正负号抵消一部分噪声；</li>
 *   <li>词频取对数抑制（1+log(tf)），长文档不会因为词多而"霸占"某一维；</li>
 *   <li>L2 归一化，之后余弦相似度 = 点积。</li>
 * </ol>
 *
 * <p><b>它准吗？</b>说句实话——它<b>不是</b>语义向量，它只是"字面相似度"的一个高效近似。
 * "汽车"和"轿车"它分不开，"番茄"和"西红柿"也分不开。
 * 但它的价值在于：<b>让整条链路（入库 → 向量化 → 索引 → 余弦召回 → RRF）真实跑通</b>，
 * 你可以在没有任何 Key 的情况下把每一行代码都调试明白。
 * 等换成真正的 embedding 模型，业务代码一行都不用改——
 * 因为上层只依赖 {@code EmbeddingClient} 接口。</p>
 *
 * <p><b>换成真实模型：</b>只要在 {@code application.yml} 填上 Key，
 * {@code AiAutoConfiguration} 会自动换成 {@link OpenAiCompatibleEmbeddingClient}。</p>
 */
public class LocalHashEmbeddingClient implements EmbeddingClient {

    private final int dimension;

    public LocalHashEmbeddingClient(int dimension) {
        if (dimension <= 0) {
            throw new IllegalArgumentException("向量维度必须大于 0，当前: " + dimension);
        }
        this.dimension = dimension;
    }

    @Override
    public String provider() {
        return "local-hash";
    }

    @Override
    public String model() {
        return "local-hashing-v1";
    }

    @Override
    public int dimension() {
        return dimension;
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        List<float[]> result = new ArrayList<>();
        if (texts == null || texts.isEmpty()) {
            return result;
        }
        for (String text : texts) {
            result.add(embedOne(text));
        }
        return result;
    }

    /**
     * 单条文本向量化。
     */
    private float[] embedOne(String text) {
        float[] vector = new float[dimension];
        if (text == null || text.isBlank()) {
            return vector;
        }

        // 统计每个维度上的"带符号词频"
        Map<Integer, Double> accumulator = new HashMap<>();
        for (String token : Tokenizer.tokenize(text)) {
            int hash = fnv1a(token);
            int index = Math.floorMod(hash, dimension);
            // 第二个哈希决定符号，用于抵消哈希碰撞带来的系统性偏差
            double sign = (fnv1a(token + "#prism") & 1) == 0 ? 1.0 : -1.0;
            accumulator.merge(index, sign, Double::sum);
        }

        for (Map.Entry<Integer, Double> entry : accumulator.entrySet()) {
            double value = entry.getValue();
            if (value == 0) {
                continue;
            }
            // 对数抑制：出现 100 次的词不会比出现 10 次的词重要 10 倍
            vector[entry.getKey()] = (float) (Math.signum(value) * (1.0 + Math.log(Math.abs(value))));
        }

        return VectorUtils.normalize(vector);
    }

    /**
     * FNV-1a 哈希：分布均匀、实现简单，比 {@code String.hashCode()} 更适合做哈希技巧。
     */
    private int fnv1a(String value) {
        final int prime = 16777619;
        int hash = (int) 2166136261L;
        for (int i = 0; i < value.length(); i++) {
            hash ^= value.charAt(i);
            hash *= prime;
        }
        return hash;
    }
}
