package com.prism.rag.rag.pipeline.node;

import com.prism.rag.infra.ai.spi.EmbeddingClient;
import com.prism.rag.rag.pipeline.AbstractIngestionNode;
import com.prism.rag.rag.pipeline.IngestionContext;
import com.prism.rag.rag.pipeline.IngestionState;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * 节点 3：向量化。
 *
 * <h3>这里的线程池隔离</h3>
 * <p>向量化是整个流水线里<b>唯一需要等外部网络</b>的环节（要调 Embedding 模型）。
 * 本节点把分块切成若干批，每批作为一个任务提交到<b>独立的 embeddingExecutor</b>：</p>
 * <pre>
 *   [chunk1..chunk32] → embedding 线程池 → 一次模型调用（32 条）
 *   [chunk33..chunk64] → embedding 线程池 → 一次模型调用（32 条）
 *   ...
 * </pre>
 * <p>好处有三个：</p>
 * <ol>
 *   <li><b>批量调用</b>：模型接口按 batch 计费/限流，32 条一次比 32 次快得多；</li>
 *   <li><b>并行</b>：多个批次同时在飞，总耗时接近"最慢的一批"而不是"所有批次之和"；</li>
 *   <li><b>不阻塞其它业务</b>：embedding 线程池和入库主控池、问答池都隔离，
 *       向量化慢不会拖慢用户提问。</li>
 * </ol>
 */
@Component
@Order(3)
public class EmbeddingNode extends AbstractIngestionNode {

    /** 单批送入模型的 chunk 数量 */
    @Value("${prism.ingestion.embedding-batch-size:32}")
    private int batchSize;

    private final EmbeddingClient embeddingClient;
    private final ExecutorService embeddingExecutor;

    public EmbeddingNode(EmbeddingClient embeddingClient,
                         @Qualifier("embeddingExecutor") ExecutorService embeddingExecutor) {
        this.embeddingClient = embeddingClient;
        this.embeddingExecutor = embeddingExecutor;
    }

    @Override
    public String name() {
        return "embedding";
    }

    @Override
    public IngestionState targetState() {
        return IngestionState.EMBEDDING;
    }

    @Override
    protected void doExecute(IngestionContext context) {
        List<String> contents = context.getChunks().stream()
                .map(chunk -> chunk.content())
                .toList();
        if (contents.isEmpty()) {
            throw new IllegalStateException("没有需要向量化的分块");
        }

        int size = Math.max(1, batchSize);
        List<Future<List<float[]>>> futures = new ArrayList<>();
        List<List<String>> batches = new ArrayList<>();
        for (int i = 0; i < contents.size(); i += size) {
            List<String> batch = contents.subList(i, Math.min(i + size, contents.size()));
            batches.add(batch);
            // 每一批作为一个独立任务提交到向量化线程池
            Callable<List<float[]>> callable = () -> embeddingClient.embed(batch);
            futures.add(embeddingExecutor.submit(callable));
        }

        List<float[]> vectors = new ArrayList<>(contents.size());
        for (Future<List<float[]>> future : futures) {
            try {
                vectors.addAll(future.get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("向量化被中断", e);
            } catch (Exception e) {
                throw new IllegalStateException("向量化失败: " + e.getMessage(), e);
            }
        }

        if (vectors.size() != contents.size()) {
            throw new IllegalStateException("向量化结果数量与分块数量不一致："
                    + vectors.size() + " vs " + contents.size());
        }
        context.setVectors(vectors);
    }

    @Override
    protected String describeResult(IngestionContext context) {
        int dimension = context.getVectors().isEmpty() ? 0 : context.getVectors().get(0).length;
        return "生成 " + context.getVectors().size() + " 个向量（维度 " + dimension + "）";
    }
}
