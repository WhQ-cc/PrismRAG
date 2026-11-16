package com.prism.rag.common.text;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 文本处理小工具：摘要片段、Jaccard 相似度等。
 *
 * <p>这些方法看起来不起眼，但它们直接决定了"引用溯源"展示得好不好看：
 * 用户点开一条引用，我们不能把整个分块甩给他，而要挑出最能回答问题的那句话。</p>
 */
public final class TextUtils {

    private TextUtils() {
    }

    /**
     * 截断文本，超长时补省略号。
     */
    public static String truncate(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        String trimmed = text.trim();
        if (trimmed.length() <= maxLength) {
            return trimmed;
        }
        return trimmed.substring(0, maxLength) + "…";
    }

    /**
     * 句子切分：按中英文标点断句。
     */
    public static List<String> splitSentences(String text) {
        List<String> sentences = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return sentences;
        }
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            current.append(c);
            if (c == '。' || c == '！' || c == '？' || c == '；' || c == '\n'
                    || c == '.' || c == '!' || c == '?' || c == ';') {
                String sentence = current.toString().trim();
                if (!sentence.isEmpty()) {
                    sentences.add(sentence);
                }
                current.setLength(0);
            }
        }
        String rest = current.toString().trim();
        if (!rest.isEmpty()) {
            sentences.add(rest);
        }
        return sentences;
    }

    /**
     * 围绕查询词挑选最能说明问题的片段（用于引用卡片摘要）。
     *
     * <p>做法：把文本切成句子，用分词器算每句与查询的词元重合度，
     * 取重合度最高的句子；没有重合就取第一句。</p>
     *
     * @param content 原文（通常是某个 chunk）
     * @param query   用户问题
     * @param maxLen  片段最大长度
     */
    public static String bestSnippet(String content, String query, int maxLen) {
        if (content == null || content.isBlank()) {
            return "";
        }
        List<String> sentences = splitSentences(content);
        if (sentences.isEmpty()) {
            return truncate(content, maxLen);
        }
        Set<String> queryTokens = new HashSet<>(Tokenizer.tokenize(query));
        if (queryTokens.isEmpty()) {
            return truncate(sentences.get(0), maxLen);
        }

        String best = sentences.get(0);
        double bestScore = -1;
        for (String sentence : sentences) {
            Set<String> sentenceTokens = new HashSet<>(Tokenizer.tokenize(sentence));
            double score = jaccard(queryTokens, sentenceTokens);
            // 词元重合数作为次要权重，兼顾"句子长但只有一个词命中"的情况
            score += 0.01 * countCommon(queryTokens, sentenceTokens);
            if (score > bestScore) {
                bestScore = score;
                best = sentence;
            }
        }
        return truncate(best, maxLen);
    }

    /**
     * Jaccard 相似度：|A∩B| / |A∪B|。
     */
    public static double jaccard(Set<String> a, Set<String> b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        int intersection = countCommon(a, b);
        return (double) intersection / union.size();
    }

    /**
     * 两个集合的交集大小。
     */
    public static int countCommon(Set<String> a, Set<String> b) {
        if (a == null || b == null) {
            return 0;
        }
        Set<String> small = a.size() <= b.size() ? a : b;
        Set<String> large = small == a ? b : a;
        int count = 0;
        for (String item : small) {
            if (large.contains(item)) {
                count++;
            }
        }
        return count;
    }

    /**
     * 按分隔符切分为词元集合（辅助方法）。
     */
    public static Set<String> splitToSet(String text, String separator) {
        Set<String> set = new HashSet<>();
        if (text == null || text.isBlank()) {
            return set;
        }
        set.addAll(Arrays.asList(text.split(separator)));
        return set;
    }

    /**
     * 判断是否为空白。
     */
    public static boolean isBlank(String text) {
        return text == null || text.isBlank();
    }
}
