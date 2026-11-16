package com.prism.rag.infra.ai.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prism.rag.common.api.ErrorCode;
import com.prism.rag.common.exception.BizException;
import com.prism.rag.infra.ai.config.AiProperties;
import com.prism.rag.infra.ai.spi.EmbeddingClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

/**
 * OpenAI 兼容协议的向量化客户端。
 *
 * <p>调用 {@code POST /v1/embeddings}，传入一批文本，返回一批向量。</p>
 *
 * <p><b>为什么要批量而不是一条条调？</b>网络往返是主要成本。
 * 100 个 chunk 逐个调是 100 次 RTT，批量调可能 1~2 次就完事。
 * 本项目的入库流水线在 {@code EmbeddingNode} 里就是按批次（默认 32 条）切分后调用的。</p>
 *
 * <p><b>维度提醒：</b>不同模型的输出维度不同
 * （text-embedding-3-small 默认 1536，text-embedding-3-large 默认 3072），
 * 而本项目本地 Mock 模型是 256 维。
 * 入库用哪个模型，检索就必须用同一个，否则维度对不上——
 * 这条规则会在检索层做校验并降级，详见 {@code VectorStore}。</p>
 */
public class OpenAiCompatibleEmbeddingClient implements EmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleEmbeddingClient.class);

    private final AiProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    /** 真实维度：以模型第一次返回的向量长度为准 */
    private volatile Integer actualDimension;

    public OpenAiCompatibleEmbeddingClient(AiProperties properties) {
        this(properties, new ObjectMapper());
    }

    public OpenAiCompatibleEmbeddingClient(AiProperties properties, ObjectMapper objectMapper) {
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
        return properties.getEmbeddingModel();
    }

    @Override
    public int dimension() {
        Integer actual = actualDimension;
        return actual != null ? actual : properties.getEmbeddingDimension();
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", properties.getEmbeddingModel());
        body.put("input", texts);
        try {
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint()))
                    .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + properties.getApiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new BizException(ErrorCode.EMBEDDING_FAILED,
                        "向量化接口返回 " + response.statusCode() + ": " + response.body());
            }
            JsonNode root = objectMapper.readTree(response.body());
            List<float[]> result = new ArrayList<>();
            for (JsonNode item : root.path("data")) {
                JsonNode embedding = item.path("embedding");
                float[] vector = new float[embedding.size()];
                for (int i = 0; i < embedding.size(); i++) {
                    vector[i] = (float) embedding.get(i).asDouble();
                }
                result.add(vector);
                if (actualDimension == null) {
                    actualDimension = vector.length;
                }
            }
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException(ErrorCode.EMBEDDING_FAILED, "向量化被中断", e);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("[Embedding] 调用失败: {}", e.getMessage(), e);
            throw new BizException(ErrorCode.EMBEDDING_FAILED, "向量化失败: " + e.getMessage(), e);
        }
    }

    private String endpoint() {
        String base = properties.getBaseUrl();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/v1/embeddings";
    }
}
