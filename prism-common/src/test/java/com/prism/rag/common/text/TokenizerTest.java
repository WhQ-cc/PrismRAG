package com.prism.rag.common.text;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分词器测试。
 *
 * <p>分词是 BM25 与本地向量模型的共同地基，
 * 它的行为一旦变化，检索质量会整体漂移——所以必须有测试兜底。</p>
 */
class TokenizerTest {

    @Test
    @DisplayName("中文同时产出单字与相邻二字组")
    void shouldProduceUnigramAndBigram() {
        List<String> tokens = Tokenizer.tokenizeRaw("知识库");
        assertTrue(tokens.contains("知"), "应包含单字");
        assertTrue(tokens.contains("知识"), "应包含二字组");
        assertTrue(tokens.contains("识库"), "应包含二字组");
        assertTrue(tokens.contains("库"), "应包含单字");
    }

    @Test
    @DisplayName("英文按单词切分并转小写")
    void shouldSplitEnglishWords() {
        List<String> tokens = Tokenizer.tokenizeRaw("SpringBoot and Redis");
        assertTrue(tokens.contains("springboot"));
        assertTrue(tokens.contains("and"));
        assertTrue(tokens.contains("redis"));
        assertFalse(tokens.contains("SpringBoot"), "应转小写");
    }

    @Test
    @DisplayName("纯数字一律保留，字母数字混合串按字母段/数字段切分")
    void shouldKeepNumbers() {
        List<String> tokens = Tokenizer.tokenizeRaw("订单号 SO20240315001");
        assertTrue(tokens.contains("20240315001"), "数字段应独立成词，便于只搜数字时命中");
        assertTrue(tokens.contains("so"), "字母段也应独立成词");
    }

    @Test
    @DisplayName("标点是天然分隔符")
    void shouldSplitByPunctuation() {
        List<String> tokens = Tokenizer.tokenizeRaw("报销，住宿；标准。");
        assertTrue(tokens.contains("报销"));
        assertTrue(tokens.contains("住宿"));
        assertTrue(tokens.contains("标准"));
    }

    @Test
    @DisplayName("停用词被过滤")
    void shouldRemoveStopWords() {
        List<String> tokens = Tokenizer.tokenize("报销的流程是什么");
        assertFalse(tokens.contains("的"));
        assertFalse(tokens.contains("是"));
        assertTrue(tokens.contains("报销"));
    }

    @Test
    @DisplayName("空输入不炸")
    void shouldHandleEmptyInput() {
        assertTrue(Tokenizer.tokenize(null).isEmpty());
        assertTrue(Tokenizer.tokenize("").isEmpty());
        assertTrue(Tokenizer.tokenize("   ").isEmpty());
    }
}
