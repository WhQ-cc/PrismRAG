package com.prism.rag.rag.pipeline.chunk;

import com.prism.rag.common.text.TextUtils;
import com.prism.rag.rag.pipeline.ParsedDocument;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 固定粒度滑动窗口分块器。
 *
 * <h3>原理</h3>
 * <pre>
 *   原文：  A B C D E F G H I J
 *   chunkSize=4, overlap=2
 *   块1:   A B C D
 *   块2:         D E F G      ← 重叠 2 个，保证跨块的句子不被切断
 *   块3:               G H I J
 * </pre>
 *
 * <h3>为什么需要重叠？</h3>
 * <p>假设原文是"差旅报销需要在 OA 系统提交，超过 500 元需要部门总监审批。"，
 * 如果正好在"500 元"中间切开，块1 结尾是"超过"，块2 开头是"500 元需要……"，
 * <b>两个块单独看都不完整</b>，检索到任何一个都会丢失语义。
 * 重叠让每个块都保留一点上下文，缓解（但不能完全消除）这个问题。</p>
 *
 * <h3>chunkSize 怎么选？</h3>
 * <ul>
 *   <li><b>太小</b>（&lt;100）：上下文不足，模型拿到"差旅报销需要"这种残句；</li>
 *   <li><b>太大</b>（&gt;800）：向量被多个主题稀释，检索不精确，还烧 token；</li>
 *   <li><b>经验值</b>：中文 200~500 token 是甜区。本项目默认 300。</li>
 * </ul>
 */
public class TokenWindowChunker implements ChunkStrategy {

    @Override
    public String name() {
        return "window";
    }

    @Override
    public List<ChunkDraft> split(ParsedDocument document, ChunkOptions options) {
        options.validate();
        List<ChunkDraft> result = new ArrayList<>();
        if (document == null || document.fullText() == null || document.fullText().isBlank()) {
            return result;
        }

        List<String> units = TextSegmenter.segment(document.fullText());
        if (units.isEmpty()) {
            return result;
        }

        int chunkSize = options.chunkSize();
        int overlap = options.overlap();
        int step = Math.max(1, chunkSize - overlap);

        int seq = 0;
        int start = 0;
        while (start < units.size()) {
            int end = Math.min(start + chunkSize, units.size());
            StringBuilder sb = new StringBuilder();
            for (int i = start; i < end; i++) {
                sb.append(units.get(i));
            }
            String content = sb.toString().trim();
            if (!content.isEmpty()) {
                result.add(buildChunk(content, seq++, options, document.title(), null));
            }
            if (end >= units.size()) {
                break;
            }
            start += step;
        }
        return result;
    }

    /**
     * 构造一个 chunk（可选地给块补上标题上下文）。
     */
    static ChunkDraft buildChunk(String content, int seq, ChunkOptions options, String docTitle, String heading) {
        Map<String, String> metadata = new LinkedHashMap<>();
        StringBuilder finalContent = new StringBuilder();
        if (options.prependTitle()) {
            String prefix = heading != null && !heading.isBlank() ? heading : docTitle;
            if (prefix != null && !prefix.isBlank()) {
                finalContent.append('《').append(prefix).append("》\n");
                metadata.put("heading", prefix);
            }
        }
        finalContent.append(content);
        metadata.put("docTitle", docTitle == null ? "" : docTitle);
        return new ChunkDraft(finalContent.toString(), seq, TextUtils.estimateTokens(finalContent.toString()), metadata);
    }
}
