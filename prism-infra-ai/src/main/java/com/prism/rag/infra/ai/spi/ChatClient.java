package com.prism.rag.infra.ai.spi;

import com.prism.rag.infra.ai.model.ChatRequest;
import com.prism.rag.infra.ai.model.ChatResponse;

/**
 * 对话模型客户端（SPI）。
 *
 * <p><b>这是"可插拔模型"的全部秘密。</b></p>
 * <p>整个问答主流程只依赖这个接口，从来不直接写死"调 OpenAI"。
 * 因此：</p>
 * <ul>
 *   <li>想换成通义千问 / DeepSeek / 本地 Ollama → 新写一个实现类即可，<b>问答主流程一行不改</b>；</li>
 *   <li>想加"多候选模型 + 熔断降级" → 在这个接口外面包一层路由，业务层依然只认它；</li>
 *   <li>想做 A/B 实验 → 按配置决定注入哪个实现。</li>
 * </ul>
 *
 * <p>对照 ragent 的设计：它的 {@code ChatClient} / {@code EmbeddingClient} / {@code RerankClient}
 * 就是同一层意思。本项目只实现前两个，保持教学聚焦。</p>
 */
public interface ChatClient {

    /**
     * 提供者标识（mock / openai / qwen ...），会展示在前端的状态徽标上。
     */
    String provider();

    /**
     * 模型标识。
     */
    String model();

    /**
     * 同步对话。
     *
     * @param request 请求
     * @return 完整回答
     */
    ChatResponse complete(ChatRequest request);

    /**
     * 流式对话。
     *
     * @param request  请求
     * @param listener 流式回调，收到增量文本时回调 onDelta
     */
    void stream(ChatRequest request, ChatStreamListener listener);

    /**
     * 流式回调。
     *
     * <p>为什么不用 {@code Consumer<String>}？因为流式场景需要区分
     * "正常结束"和"异常中断"，还需要在结束时拿到 token 统计。
     * 三个方法分开表达，比在 Consumer 里塞魔数清晰得多。</p>
     */
    interface ChatStreamListener {
        /**
         * 收到一段增量文本。
         */
        void onDelta(String delta);

        /**
         * 正常结束。
         */
        default void onComplete(ChatResponse response) {
        }

        /**
         * 异常中断。
         */
        default void onError(Throwable error) {
        }
    }
}
