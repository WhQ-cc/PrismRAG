package com.prism.rag.rag.pipeline.chunk;

import com.prism.rag.rag.pipeline.ParsedDocument;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分块策略测试。
 */
class ChunkStrategyTest {

    private ParsedDocument doc(String text) {
        return new ParsedDocument("测试文档", text, List.of(new ParsedDocument.Section(null, text)));
    }

    @Test
    @DisplayName("滑动窗口：块数与重叠符合预期")
    void windowShouldProduceOverlappingChunks() {
        String text = "差旅住宿费实行限额报销制度。一线城市住宿标准为每晚 600 元。"
                + "二线城市为每晚 450 元。其他城市为每晚 350 元。";

        List<ChunkDraft> chunks = new TokenWindowChunker().split(
                doc(text), new ChunkOptions(20, 5, false, "window"));

        assertTrue(chunks.size() > 1, "长文本应切成多块");
        assertEquals(0, chunks.get(0).seq(), "序号应从 0 开始且连续");
        for (int i = 1; i < chunks.size(); i++) {
            assertEquals(i, chunks.get(i).seq());
        }
    }

    @Test
    @DisplayName("滑动窗口：开启标题前置后每个块都带标题")
    void windowShouldPrependTitle() {
        List<ChunkDraft> chunks = new TokenWindowChunker().split(
                doc("住宿标准为每晚 600 元。"),
                new ChunkOptions(50, 10, true, "window"));

        assertFalse(chunks.isEmpty());
        assertTrue(chunks.get(0).content().startsWith("《测试文档》"),
                "块正文应以《标题》开头，实际: " + chunks.get(0).content());
    }

    @Test
    @DisplayName("段落分块：短段落会被合并，不产生碎片")
    void paragraphShouldMergeShortSections() {
        ParsedDocument document = new ParsedDocument("t", "",
                List.of(new ParsedDocument.Section("A", "很短的一句话。"),
                        new ParsedDocument.Section("B", "也很短。"),
                        new ParsedDocument.Section("C", "第三段内容稍微长一点，包含更多信息用于测试合并逻辑。")));

        List<ChunkDraft> chunks = new ParagraphChunker().split(document, new ChunkOptions(60, 10, false, "paragraph"));

        assertFalse(chunks.isEmpty());
        // 三段合起来不足 60 token，应该合并成一块
        assertEquals(1, chunks.size(), "短段落应合并为一块，实际: " + chunks.size());
    }

    @Test
    @DisplayName("非法分块参数必须被拒绝")
    void shouldRejectInvalidOptions() {
        ChunkOptions invalid = new ChunkOptions(100, 100, true, "window");
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, invalid::validate);

        ChunkOptions negative = new ChunkOptions(-1, 0, true, "window");
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, negative::validate);
    }

    @Test
    @DisplayName("空文本不产生分块")
    void shouldHandleEmptyText() {
        List<ChunkDraft> chunks = new TokenWindowChunker().split(
                doc("   "), new ChunkOptions(100, 10, true, "window"));
        assertTrue(chunks.isEmpty());
    }
}
