package com.prism.rag.rag.pipeline.chunk;

import java.util.Map;

/**
 * 分块草稿（还没入库、还没向量化）。
 *
 * @param content    块正文
 * @param seq        序号
 * @param tokenCount 估算 token 数
 * @param metadata   附加信息（如标题、来源章节）
 */
public record ChunkDraft(String content, int seq, int tokenCount, Map<String, String> metadata) {

    public static ChunkDraft of(String content, int seq, int tokenCount) {
        return new ChunkDraft(content, seq, tokenCount, Map.of());
    }
}
