package com.prism.rag.infra.ai.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 模型响应。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatResponse {

    /** 完整回答文本 */
    private String content;

    /** 提供者 */
    private String provider;

    /** 模型名 */
    private String model;

    /** 输入 token 数 */
    private Integer promptTokens;

    /** 输出 token 数 */
    private Integer completionTokens;

    /** 总 token 数 */
    private Integer totalTokens;

    /** 耗时（毫秒） */
    private Long latencyMillis;

    /**
     * 粗略估算 token 数（离线 Mock 用）。
     *
     * <p>中文约 1 字 ≈ 1 token，英文约 4 字符 ≈ 1 token。
     * 真实模型请用返回的 usage 字段。</p>
     */
    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        long cjk = text.chars().filter(c -> c >= 0x4E00 && c <= 0x9FFF).count();
        long other = text.length() - cjk;
        return (int) (cjk + Math.ceil(other / 4.0));
    }
}
