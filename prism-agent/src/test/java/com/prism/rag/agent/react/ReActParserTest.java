package com.prism.rag.agent.react;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ReAct 输出解析测试。
 */
class ReActParserTest {

    private final ReActParser parser = new ReActParser();

    @Test
    @DisplayName("解析标准的三段式输出")
    void shouldParseStandardOutput() {
        String output = """
                Thought: 我需要先查一下知识库。
                Action: knowledge_search
                Action Input: {"query": "报销标准"}
                """;

        ReActParser.Parsed parsed = parser.parse(output);
        assertTrue(parsed.hasAction());
        assertEquals("knowledge_search", parsed.action());
        assertEquals("{\"query\": \"报销标准\"}", parsed.actionInput());
        assertFalse(parsed.hasFinalAnswer());
    }

    @Test
    @DisplayName("解析最终答案")
    void shouldParseFinalAnswer() {
        String output = """
                Thought: 已经拿到检索结果，可以回答了。
                Final Answer: 差旅住宿标准一线城市为每晚 600 元。
                """;

        ReActParser.Parsed parsed = parser.parse(output);
        assertTrue(parsed.hasFinalAnswer());
        assertTrue(parsed.finalAnswer().contains("600"));
        assertFalse(parsed.hasAction());
    }

    @Test
    @DisplayName("『Action Input:』不会被误判成『Action:』")
    void shouldNotMistakeActionInputForAction() {
        String output = """
                Thought: 调工具。
                Action: knowledge_search
                Action Input: {"query": "x"}
                """;

        ReActParser.Parsed parsed = parser.parse(output);
        assertEquals("knowledge_search", parsed.action(), "工具名应是 knowledge_search 而不是 Input");
    }

    @Test
    @DisplayName("无法解析时返回空结果而不是抛异常")
    void shouldReturnEmptyOnGarbage() {
        ReActParser.Parsed parsed = parser.parse("这是一段完全没有格式的自由文本");
        assertFalse(parsed.hasAction());
        assertFalse(parsed.hasFinalAnswer());
    }

    @Test
    @DisplayName("空输出不抛异常")
    void shouldHandleEmptyOutput() {
        ReActParser.Parsed parsed = parser.parse("");
        assertFalse(parsed.hasAction());
        assertFalse(parsed.hasFinalAnswer());
    }
}
