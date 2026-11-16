package com.prism.rag.rag.pipeline.chunk;

import com.prism.rag.rag.pipeline.ParsedDocument;

import java.util.List;

/**
 * 分块策略（SPI）。
 *
 * <p><b>分块策略是 RAG 里最影响效果的参数，没有之一。</b></p>
 * <p>本项目提供两种经典策略：</p>
 * <ul>
 *   <li>{@link TokenWindowChunker}：固定粒度滑动窗口，通用、稳；</li>
 *   <li>{@link ParagraphChunker}：尊重语义边界（段落/章节），准、但块大小不均。</li>
 * </ul>
 * <p>真实项目里通常"两者混用"：先按语义边界切，块太大再按窗口细切。</p>
 */
public interface ChunkStrategy {

    /**
     * 策略名。
     */
    String name();

    /**
     * 执行分块。
     *
     * @param document 已解析的文档
     * @param options  分块参数
     * @return 分块列表
     */
    List<ChunkDraft> split(ParsedDocument document, ChunkOptions options);
}
