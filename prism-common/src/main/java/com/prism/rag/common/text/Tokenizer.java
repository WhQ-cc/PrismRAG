package com.prism.rag.common.text;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 中英文混合分词器。
 *
 * <p><b>这是本项目检索能力的根基。</b>BM25 倒排索引和本地向量模型都建立在"把文本切成词"之上，
 * 分词质量直接决定召回质量。</p>
 *
 * <p><b>为什么不用现成的分词库？</b>IK / HanLP 等第三方分词器需要额外依赖和词典，
 * 而且真正的分词器（带词性、歧义消解）对检索来说往往"过度思考"。
 * 这里采用搜索引擎里最经典的 <b>二元组（bigram）</b> 方案：</p>
 *
 * <ol>
 *   <li><b>英文/数字</b>：按 {@code [a-zA-Z0-9_]+} 整体切分并转小写，
 *       这样 "SpringBoot" 是一个词，"Redis" 不会和 "Red" 混淆。</li>
 *   <li><b>中文</b>：同时产出 <b>单字</b> 和 <b>相邻二字组</b>。
 *       例："知识库检索" → 知/识/库/检/索 + 知识/识库/库检/检索。
 *       二字组是中文检索的主力信号：单字歧义大，四个字的词又切不出来，
 *       bigram 恰好落在"召回够准、切分够细"的平衡点上。</li>
 * </ol>
 *
 * <p><b>一个具体例子说明为什么 bigram 有效：</p>
 * 文档里有"数据仓库"，用户问"数仓"。
 * 单字方案能命中"数"和"仓"（噪声很大）；纯词表方案完全命中不了。
 * bigram 至少还能通过"数据""据仓"这类组合部分重叠，最终配合 RRF 融合给出合理排序。</p>
 *
 * <p>如果你之后要接入 ES，可以直接把这里的逻辑换成 IK 的分词结果；
 * 如果要接入中文向量模型，也建议先跑一遍本分词器做对照实验。</p>
 */
public final class Tokenizer {

    private Tokenizer() {
    }

    /**
     * 分词并过滤停用词（默认入口）。
     *
     * @param text 原始文本
     * @return 词元列表
     */
    public static List<String> tokenize(String text) {
        List<String> raw = tokenizeRaw(text);
        List<String> result = new ArrayList<>(raw.size());
        for (String token : raw) {
            if (!StopWords.contains(token)) {
                result.add(token);
            }
        }
        return result;
    }

    /**
     * 分词但不过滤停用词。
     *
     * <p>用于"原文预览高亮"这类场景——高亮需要保留原文的每一个字符单元。</p>
     */
    public static List<String> tokenizeRaw(String text) {
        List<String> tokens = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return tokens;
        }

        StringBuilder asciiBuffer = new StringBuilder();
        List<Character> cjkBuffer = new ArrayList<>();

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isCjk(c)) {
                flushAscii(asciiBuffer, tokens);
                cjkBuffer.add(c);
            } else if (isWordChar(c)) {
                flushCjk(cjkBuffer, tokens);
                asciiBuffer.append(Character.toLowerCase(c));
            } else {
                // 标点/空白是天然的分隔符
                flushAscii(asciiBuffer, tokens);
                flushCjk(cjkBuffer, tokens);
            }
        }
        flushAscii(asciiBuffer, tokens);
        flushCjk(cjkBuffer, tokens);
        return tokens;
    }

    /** 冲刷英文/数字缓冲 */
    private static void flushAscii(StringBuilder buffer, List<String> tokens) {
        if (buffer.isEmpty()) {
            return;
        }
        String token = buffer.toString();
        buffer.setLength(0);
        // 纯数字一律保留（订单号、版本号、"第3章"里的 3 都有意义）
        // 纯字母要求长度 >= 2，避免 a / I 这类单字母噪声
        boolean numeric = token.chars().allMatch(Character::isDigit);
        if (numeric || token.length() >= 2) {
            tokens.add(token);
        }
    }

    /** 冲刷中文缓冲：产出单字 + 相邻二字组 */
    private static void flushCjk(List<Character> buffer, List<String> tokens) {
        if (buffer.isEmpty()) {
            return;
        }
        int n = buffer.size();
        for (int i = 0; i < n; i++) {
            // 单字
            tokens.add(String.valueOf(buffer.get(i)));
            // 二字组：最后一个字没有后继，就不产出 bigram
            if (i + 1 < n) {
                tokens.add("" + buffer.get(i) + buffer.get(i + 1));
            }
        }
        buffer.clear();
    }

    /** 是否为中日韩统一表意文字（含扩展区） */
    private static boolean isCjk(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF)
                || (c >= 0x3400 && c <= 0x4DBF)
                || (c >= 0xF900 && c <= 0xFAFF);
    }

    /** 是否为可构成"单词"的字符 */
    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) && c < 0x4E00;
    }

    /** 供其他模块复用的字符串归一化 */
    public static String normalize(String text) {
        return text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
    }
}
