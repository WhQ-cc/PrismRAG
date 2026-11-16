package com.prism.rag.infra.ai.model;

/**
 * 模型信息（对外暴露给前端做状态展示）。
 *
 * @param chatProvider        对话模型提供者
 * @param chatModel           对话模型名
 * @param embeddingProvider   向量模型提供者
 * @param embeddingModel      向量模型名
 * @param embeddingDimension  向量维度
 * @param real                是否接了真实大模型
 * @param endpoint            实际使用的服务地址
 */
public record AiModelInfo(
        String chatProvider,
        String chatModel,
        String embeddingProvider,
        String embeddingModel,
        int embeddingDimension,
        boolean real,
        String endpoint
) {
}
