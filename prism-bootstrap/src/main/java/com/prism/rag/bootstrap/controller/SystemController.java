package com.prism.rag.bootstrap.controller;

import com.prism.rag.common.api.R;
import com.prism.rag.framework.sse.SseEmitterRegistry;
import com.prism.rag.infra.ai.model.AiModelInfo;
import com.prism.rag.rag.retrieval.Bm25Index;
import com.prism.rag.rag.retrieval.VectorStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 系统状态接口：模型信息、索引规模、活跃连接数。
 *
 * <p>这些数据前端会显示在顶部的"状态徽标"上——
 * 让用户随时知道"当前用的是真模型还是 Mock""索引里有多少内容"。</p>
 */
@RestController
@RequestMapping("/api/system")
public class SystemController {

    private final AiModelInfo modelInfo;
    private final VectorStore vectorStore;
    private final Bm25Index bm25Index;
    private final SseEmitterRegistry sseEmitterRegistry;

    public SystemController(AiModelInfo modelInfo, VectorStore vectorStore,
                            Bm25Index bm25Index, SseEmitterRegistry sseEmitterRegistry) {
        this.modelInfo = modelInfo;
        this.vectorStore = vectorStore;
        this.bm25Index = bm25Index;
        this.sseEmitterRegistry = sseEmitterRegistry;
    }

    /**
     * 系统概览。
     */
    @GetMapping("/info")
    public R<Map<String, Object>> info() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("model", Map.of(
                "chatProvider", modelInfo.chatProvider(),
                "chatModel", modelInfo.chatModel(),
                "embeddingProvider", modelInfo.embeddingProvider(),
                "embeddingModel", modelInfo.embeddingModel(),
                "embeddingDimension", modelInfo.embeddingDimension(),
                "real", modelInfo.real(),
                "endpoint", modelInfo.endpoint()
        ));
        data.put("index", Map.of(
                "vectorCount", vectorStore.size(),
                "vectorDimension", vectorStore.dimension(),
                "bm25ChunkCount", bm25Index.size(),
                "bm25TermCount", bm25Index.termCount()
        ));
        data.put("sseActiveConnections", sseEmitterRegistry.activeCount());
        return R.ok(data);
    }

    /**
     * 健康检查。
     */
    @GetMapping("/health")
    public R<Map<String, String>> health() {
        return R.ok(Map.of("status", "UP"));
    }
}
