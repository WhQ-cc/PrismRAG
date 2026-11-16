package com.prism.rag.rag.pipeline.node;

import com.prism.rag.rag.pipeline.AbstractIngestionNode;
import com.prism.rag.rag.pipeline.IngestionContext;
import com.prism.rag.rag.pipeline.IngestionState;
import com.prism.rag.rag.pipeline.chunk.ChunkOptions;
import com.prism.rag.rag.pipeline.chunk.ChunkStrategyRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 节点 2：分块。
 *
 * <p>把解析后的文本切成 chunk。这是决定 RAG 效果的关键一步，
 * 具体策略由配置 {@code prism.ingestion.chunk-strategy} 决定（window / paragraph）。</p>
 */
@Component
@Order(2)
public class ChunkNode extends AbstractIngestionNode {

    private final ChunkStrategyRegistry strategyRegistry;

    @Value("${prism.ingestion.chunk-strategy:window}")
    private String strategyName;

    @Value("${prism.ingestion.chunk-size:300}")
    private int chunkSize;

    @Value("${prism.ingestion.chunk-overlap:50}")
    private int overlap;

    @Value("${prism.ingestion.prepend-title:true}")
    private boolean prependTitle;

    public ChunkNode(ChunkStrategyRegistry strategyRegistry) {
        this.strategyRegistry = strategyRegistry;
    }

    @Override
    public String name() {
        return "chunk";
    }

    @Override
    public IngestionState targetState() {
        return IngestionState.CHUNKING;
    }

    @Override
    protected void doExecute(IngestionContext context) {
        ChunkOptions options = new ChunkOptions(chunkSize, overlap, prependTitle, strategyName).validate();
        var strategy = strategyRegistry.resolve(strategyName);
        var chunks = strategy.split(context.getParsed(), options);
        if (chunks.isEmpty()) {
            throw new IllegalStateException("分块结果为空，无法继续入库");
        }
        context.setChunks(chunks);
    }

    @Override
    protected String describeResult(IngestionContext context) {
        return "切出 " + context.getChunks().size() + " 个分块（策略=" + strategyName + "）";
    }
}
