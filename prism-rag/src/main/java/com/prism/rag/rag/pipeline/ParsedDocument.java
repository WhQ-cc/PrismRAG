package com.prism.rag.rag.pipeline;

import com.prism.rag.rag.pipeline.chunk.ChunkDraft;

import java.util.List;

/**
 * 解析结果。
 *
 * @param title    文档标题
 * @param fullText 全文（用于整体分块）
 * @param sections 章节/段落（结构化分块时优先使用）
 */
public record ParsedDocument(String title, String fullText, List<Section> sections) {

    /**
     * 一个章节。
     *
     * @param heading 标题（可能为空）
     * @param content 内容
     */
    public record Section(String heading, String content) {
    }

    /**
     * 把章节合并为全文。
     */
    public static String joinSections(List<Section> sections) {
        if (sections == null || sections.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Section section : sections) {
            if (section.heading() != null && !section.heading().isBlank()) {
                sb.append(section.heading()).append('\n');
            }
            sb.append(section.content()).append("\n\n");
        }
        return sb.toString().trim();
    }
}
