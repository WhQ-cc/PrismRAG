package com.prism.rag.rag.pipeline.chunk;

/**
 * 分块参数。
 *
 * @param chunkSize    每个块的估算 token 数
 * @param overlap      相邻块的重叠 token 数
 * @param prependTitle 是否把标题拼在块正文前面（给每个块补上下文）
 * @param strategy     分块策略名
 */
public record ChunkOptions(int chunkSize, int overlap, boolean prependTitle, String strategy) {

    /** 默认参数：300 token / 重叠 50 token */
    public static ChunkOptions defaults(String strategy) {
        return new ChunkOptions(300, 50, true, strategy);
    }

    /**
     * 参数合法性校验，避免出现 overlap >= chunkSize 这种会把窗口卡死的配置。
     */
    public ChunkOptions validate() {
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("chunkSize 必须大于 0，当前: " + chunkSize);
        }
        if (overlap < 0 || overlap >= chunkSize) {
            throw new IllegalArgumentException(
                    "overlap 必须满足 0 <= overlap < chunkSize，当前: overlap=" + overlap + ", chunkSize=" + chunkSize);
        }
        return this;
    }
}
