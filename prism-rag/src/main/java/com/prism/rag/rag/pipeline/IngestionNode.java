package com.prism.rag.rag.pipeline;

/**
 * 入库流水线节点（SPI）。
 *
 * <p>四个节点分别对应入库的四个阶段：解析 → 分块 → 向量化 → 持久化。</p>
 *
 * <p><b>为什么要拆成节点？</b>因为入库这件事在不同场景下需要不同的处理：
 * 网页文档要先抓正文，PDF 要先 OCR，表格要单独处理，敏感信息要脱敏……
 * 拆成节点之后，这些"额外处理"就是<b>插入一个新的节点</b>，
 * 而不是往一个 500 行的 {@code ingest()} 方法里塞 if-else。</p>
 *
 * <p>每个节点还负责自己对应的那次状态流转（见 {@link AbstractIngestionNode}），
 * 于是"节点做什么"和"状态怎么变"是绑在一起的，不会出现状态和实际进度对不上的情况。</p>
 */
public interface IngestionNode {

    /**
     * 节点名（会记录到任务日志里）。
     */
    String name();

    /**
     * 该节点执行期间所处的状态。
     */
    IngestionState targetState();

    /**
     * 执行节点逻辑。
     */
    void execute(IngestionContext context);
}
