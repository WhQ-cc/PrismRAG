package com.prism.rag.infra.ai.config;

import com.prism.rag.infra.ai.client.LocalHashEmbeddingClient;
import com.prism.rag.infra.ai.client.MockChatClient;
import com.prism.rag.infra.ai.client.OpenAiCompatibleChatClient;
import com.prism.rag.infra.ai.client.OpenAiCompatibleEmbeddingClient;
import com.prism.rag.infra.ai.model.AiModelInfo;
import com.prism.rag.infra.ai.spi.ChatClient;
import com.prism.rag.infra.ai.spi.EmbeddingClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 模型装配。
 *
 * <p><b>这里是"可插拔"落地的地方。</b>整个应用只注入 {@link ChatClient} 和
 * {@link EmbeddingClient} 两个接口，具体用哪个实现由配置决定，业务代码完全无感。</p>
 *
 * <p>将来要加一个新供应商（比如 DeepSeek），改动范围：</p>
 * <ol>
 *   <li>新增 {@code DeepSeekChatClient implements ChatClient}；</li>
 *   <li>在下面加一个 {@code @Bean}（或干脆用 {@code @ConditionalOnProperty} 让 Spring 自动选）；</li>
 *   <li>改一行配置。</li>
 * </ol>
 * <p><b>问答主流程、检索逻辑、入库流水线，一行都不用改。</b></p>
 */
@Configuration
@EnableConfigurationProperties(AiProperties.class)
public class AiAutoConfiguration {

    /**
     * 对话模型客户端。
     */
    @Bean
    public ChatClient chatClient(AiProperties properties) {
        if (properties.useRealModel()) {
            return new OpenAiCompatibleChatClient(properties);
        }
        return new MockChatClient(properties);
    }

    /**
     * 向量模型客户端。
     */
    @Bean
    public EmbeddingClient embeddingClient(AiProperties properties) {
        if (properties.useRealModel()) {
            return new OpenAiCompatibleEmbeddingClient(properties);
        }
        // 离线本地向量：无需任何依赖和模型文件
        return new LocalHashEmbeddingClient(properties.getEmbeddingDimension());
    }

    /**
     * 模型信息（前端状态徽标用）。
     */
    @Bean
    public AiModelInfo aiModelInfo(AiProperties properties, ChatClient chatClient, EmbeddingClient embeddingClient) {
        return new AiModelInfo(
                chatClient.provider(),
                chatClient.model(),
                embeddingClient.provider(),
                embeddingClient.model(),
                embeddingClient.dimension(),
                properties.useRealModel(),
                properties.useRealModel() ? properties.getBaseUrl() : "内置离线引擎"
        );
    }
}
