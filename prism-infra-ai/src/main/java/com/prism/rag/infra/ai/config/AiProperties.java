package com.prism.rag.infra.ai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 模型配置（{@code prism.ai.*}）。
 *
 * <p><b>本项目最实用的一条设计：自动切换。</b></p>
 * <p>你看 {@link #useRealModel()}：</p>
 * <ul>
 *   <li>配置了 {@code api-key} → 自动使用真实大模型；</li>
 *   <li>没配置 {@code api-key} → 自动使用内置离线 Mock。</li>
 * </ul>
 * <p>所以新人 clone 下来<b>不需要任何 Key、不需要任何网络</b>，
 * 就能把"入库 → 检索 → 流式问答"整条链路跑通、跑懂；
 * 等要接真实模型了，只要在 application.yml 里填一行 Key 就完成切换。
 * <b>这是"可插拔"最朴素也最有价值的体现。</b></p>
 *
 * <p>要强制指定用 Mock（调试时不想花钱），把 {@code provider} 显式设成 {@code mock} 即可。</p>
 */
@Data
@ConfigurationProperties(prefix = "prism.ai")
public class AiProperties {

    /**
     * 提供者：auto（默认，按 Key 自动判断） / mock（强制离线） / openai（强制真实）
     */
    private String provider = "auto";

    /**
     * OpenAI 兼容接口地址。
     * <p>兼容所有提供 OpenAI 协议的服务：OpenAI / DeepSeek / 通义 / 智谱 / vLLM / Ollama，
     * 换模型只需要改这个地址和模型名，<b>代码一行不动</b>。</p>
     */
    private String baseUrl = "https://api.openai.com";

    /**
     * API Key。为空则使用离线 Mock。
     */
    private String apiKey;

    /** 对话模型 */
    private String chatModel = "gpt-4o-mini";

    /** 向量模型 */
    private String embeddingModel = "text-embedding-3-small";

    /**
     * 本地离线向量模型的维度。
     * <p>注意：这是 Mock/本地实现用的维度；接真实模型时以模型返回的维度为准。</p>
     */
    private int embeddingDimension = 256;

    /** 采样温度 */
    private double temperature = 0.3;

    /** 单次回答最大 token */
    private int maxTokens = 1024;

    /** 调用真实模型的超时时间（秒） */
    private int timeoutSeconds = 60;

    /** Mock 模式下模拟的逐字输出间隔（毫秒），调小可以更快看到流式效果 */
    private int mockStreamDelayMillis = 18;

    /**
     * 是否使用真实模型。
     */
    public boolean useRealModel() {
        if ("mock".equalsIgnoreCase(provider)) {
            return false;
        }
        if ("openai".equalsIgnoreCase(provider)) {
            return true;
        }
        // auto：有 Key 就用真的，没 Key 就用 Mock
        return apiKey != null && !apiKey.isBlank();
    }
}
