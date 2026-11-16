package com.prism.rag.rag.pipeline.chunk;

import com.prism.rag.common.text.TextUtils;
import com.prism.rag.rag.pipeline.ParsedDocument;

import java.util.ArrayList;
import java.util.List;

/**
 * 语义边界分块器（按段落/章节切）。
 *
 * <h3>和滑窗的区别</h3>
 * <p>滑窗是"无视语义、只按字数硬切"，块的大小永远一样，但可能切在句子中间；
 * 段落分块是"尊重原文的天然边界"，块的大小不均，但每一块都是完整的一段话。</p>
 *
 * <h3>混合策略（本实现采用）</h3>
 * <ol>
 *   <li>段落太短 → 和后面的段落<b>合并</b>，直到接近 chunkSize；</li>
 *   <li>段落太长 → 交给滑窗策略<b>二次拆分</b>，并保留重叠。</li>
 * </ol>
 * <p>这样既避免了碎片块，也避免了超大块。</p>
 */
public class ParagraphChunker implements ChunkStrategy {

    private final TokenWindowChunker fallback = new TokenWindowChunker();

    @Override
    public String name() {
        return "paragraph";
    }

    @Override
    public List<ChunkDraft> split(ParsedDocument document, ChunkOptions options) {
        options.validate();
        List<ChunkDraft> result = new ArrayList<>();
        if (document == null || document.sections() == null || document.sections().isEmpty()) {
            return result;
        }

        int seq = 0;
        StringBuilder buffer = new StringBuilder();
        String bufferHeading = null;
        int bufferTokens = 0;

        for (ParsedDocument.Section section : document.sections()) {
            String content = section.content() == null ? "" : section.content().trim();
            if (content.isEmpty()) {
                continue;
            }
            int tokens = TextUtils.estimateTokens(content);

            // ① 段落过大：先把缓冲区落盘，再单独用滑窗拆这一段
            if (tokens > options.chunkSize()) {
                if (buffer.length() > 0) {
                    result.add(TokenWindowChunker.buildChunk(buffer.toString(), seq++, options,
                            document.title(), bufferHeading));
                    buffer.setLength(0);
                    bufferTokens = 0;
                }
                ParsedDocument single = new ParsedDocument(document.title(), content,
                        List.of(new ParsedDocument.Section(section.heading(), content)));
                for (ChunkDraft draft : fallback.split(single, options)) {
                    result.add(new ChunkDraft(draft.content(), seq++, draft.tokenCount(), draft.metadata()));
                }
                continue;
            }

            // ② 合并后仍然超限，则先落盘当前缓冲，再开新缓冲
            if (bufferTokens + tokens > options.chunkSize() && buffer.length() > 0) {
                result.add(TokenWindowChunker.buildChunk(buffer.toString(), seq++, options,
                        document.title(), bufferHeading));
                buffer.setLength(0);
                bufferTokens = 0;
                bufferHeading = null;
            }

            if (buffer.length() > 0) {
                buffer.append('\n');
            }
            buffer.append(content);
            bufferTokens += tokens;
            if (bufferHeading == null) {
                bufferHeading = section.heading();
            }
        }

        // ③ 收尾
        if (buffer.length() > 0) {
            result.add(TokenWindowChunker.buildChunk(buffer.toString(), seq++, options,
                    document.title(), bufferHeading));
        }
        return result;
    }
}
