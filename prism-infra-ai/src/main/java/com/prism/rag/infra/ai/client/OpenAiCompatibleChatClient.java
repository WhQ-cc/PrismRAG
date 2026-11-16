package com.prism.rag.infra.ai.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prism.rag.common.api.ErrorCode;
import com.prism.rag.common.exception.BizException;
import com.prism.rag.infra.ai.config.AiProperties;
import com.prism.rag.infra.ai.model.ChatMessage;
import com.prism.rag.infra.ai.model.ChatRequest;
import com.prism.rag.infra.ai.model.ChatResponse;
import com.prism.rag.infra.ai.spi.ChatClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

/**
 * OpenAI 兼容协议的真实模型客户端。
 *
 * <p><b>为什么要自己写而不用 Spring AI / LangChain4j？</b></p>
 * <ul>
 *   <li><b>看清全貌</b>：框架把请求体、错误处理、流式解析都藏起来了，
 *       初学者看完还是不知道"请求到底发出去是什么样"。这里 100 行代码把全流程摊开。</li>
 *   <li><b>没有版本锁定</b>：AI 框架迭代极快，低版本缺功能、高版本要重写。
 *       自己写一层薄适配，未来换框架只影响这一个类。</li>
 *   <li><b>零依赖</b>：只用 JDK 自带的 {@link HttpClient}，不引入任何 AI SDK。</li>
 * </ul>
 *
 * <p><b>它兼容谁？</b>凡是实现 OpenAI Chat Completions 协议的服务都行：
 * OpenAI、DeepSeek、通义千问、智谱 GLM、百度千帆、vLLM 自建、Ollama…… 换服务只改配置。</p>
 *
 * <p>启用方式：在 {@code application.yml} 里填上 Key——
 * </p>
 * <pre>
 * prism:
 *   ai:
 *     api-key: ${OPENAI_API_KEY:sk-xxxx}
 *     base-url: https://api.deepseek.com
 *     chat-model: deepseek-chat
 * </pre>
 * <p>不用改任何 Java 代码，{@code AiAutoConfiguration} 会自动切过来。</p>
 */
public class OpenAiCompatibleChatClient implements ChatClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleChatClient.class);

    private final AiProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public OpenAiCompatibleChatClient(AiProperties properties) {
        this(properties, new ObjectMapper());
    }

    public OpenAiCompatibleChatClient(AiProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.min(15, properties.getTimeoutSeconds())))
                .build();
    }

    @Override
    public String provider() {
        return "openai-compatible";
    }

    @Override
    public String model() {
        return properties.getChatModel();
    }

    /**
     * 同步调用。
     */
    @Override
    public ChatResponse complete(ChatRequest request) {
        long start = System.currentTimeMillis();
        Map<String, Object> body = buildBody(request, false);
        try {
            HttpResponse<String> response = send(body);
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode message = root.path("choices").path(0).path("message");
            String content = message.path("content").asText("");
            JsonNode usage = root.path("usage");
            return ChatResponse.builder()
                    .content(content)
                    .provider(provider())
                    .model(properties.getChatModel())
                    .promptTokens(usage.path("prompt_tokens").asInt(0))
                    .completionTokens(usage.path("completion_tokens").asInt(0))
                    .totalTokens(usage.path("total_tokens").asInt(0))
                    .latencyMillis(System.currentTimeMillis() - start)
                    .build();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException(ErrorCode.LLM_UNAVAILABLE, "调用大模型被中断", e);
        } catch (Exception e) {
            log.error("[LLM] 同步调用失败: {}", e.getMessage(), e);
            throw new BizException(ErrorCode.LLM_UNAVAILABLE, "调用大模型失败: " + e.getMessage(), e);
        }
    }

    /**
     * 流式调用。
     *
     * <p>服务端返回的是 <b>text/event-stream</b>，每一行形如：</p>
     * <pre>
     * data: {"choices":[{"delta":{"content":"你"}}]}
     * data: [DONE]
     * </pre>
     * <p>我们逐行读取、逐行解析，遇到 {@code [DONE]} 结束。</p>
     */
    @Override
    public void stream(ChatRequest request, ChatStreamListener listener) {
        long start = System.currentTimeMillis();
        Map<String, Object> body = buildBody(request, true);
        StringBuilder full = new StringBuilder();
        try {
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(chatEndpoint()))
                    .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + properties.getApiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();

            HttpResponse<java.io.InputStream> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() / 100 != 2) {
                String errorBody = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
                throw new BizException(ErrorCode.LLM_UNAVAILABLE,
                        "大模型返回 " + response.statusCode() + ": " + errorBody);
            }

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    // 客户端断开时，任务线程会被中断，这里立刻退出
                    if (Thread.currentThread().isInterrupted()) {
                        listener.onError(new CancellationException("客户端已断开或用户主动取消"));
                        return;
                    }
                    if (!line.startsWith("data:")) {
                        continue;
                    }
                    String payload = line.substring(5).trim();
                    if (payload.isEmpty()) {
                        continue;
                    }
                    if ("[DONE]".equals(payload)) {
                        break;
                    }
                    JsonNode root = objectMapper.readTree(payload);
                    String delta = root.path("choices").path(0).path("delta").path("content").asText("");
                    if (!delta.isEmpty()) {
                        full.append(delta);
                        listener.onDelta(delta);
                    }
                }
            }

            listener.onComplete(ChatResponse.builder()
                    .content(full.toString())
                    .provider(provider())
                    .model(properties.getChatModel())
                    .completionTokens(ChatResponse.estimateTokens(full.toString()))
                    .latencyMillis(System.currentTimeMillis() - start)
                    .build());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            listener.onError(new CancellationException("流式输出被中断"));
        } catch (BizException e) {
            listener.onError(e);
        } catch (Exception e) {
            log.error("[LLM] 流式调用失败: {}", e.getMessage(), e);
            listener.onError(new BizException(ErrorCode.LLM_UNAVAILABLE, "调用大模型失败: " + e.getMessage(), e));
        }
    }

    /**
     * 组装请求体。
     */
    private Map<String, Object> buildBody(ChatRequest request, boolean stream) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", properties.getChatModel());
        body.put("stream", stream);

        List<Map<String, String>> messages = new ArrayList<>();
        for (ChatMessage message : request.toMessages()) {
            Map<String, String> item = new LinkedHashMap<>();
            item.put("role", message.getRole());
            item.put("content", message.getContent());
            messages.add(item);
        }
        body.put("messages", messages);
        body.put("temperature", request.getTemperature() == null
                ? properties.getTemperature() : request.getTemperature());
        if (request.getMaxTokens() != null && request.getMaxTokens() > 0) {
            body.put("max_tokens", request.getMaxTokens());
        }
        return body;
    }

    private HttpResponse<String> send(Map<String, Object> body) throws Exception {
        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(chatEndpoint()))
                .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + properties.getApiKey())
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new BizException(ErrorCode.LLM_UNAVAILABLE,
                    "大模型返回 " + response.statusCode() + ": " + response.body());
        }
        return response;
    }

    private String chatEndpoint() {
        String base = properties.getBaseUrl();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/v1/chat/completions";
    }
}
