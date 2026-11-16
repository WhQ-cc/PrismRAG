package com.prism.rag.rag.pipeline.chunk;

import java.util.ArrayList;
import java.util.List;

/**
 * 文本切分器：把文本切成"最小语义单元"。
 *
 * <p>单元的定义：</p>
 * <ul>
 *   <li>一个中文字符算一个单元；</li>
 *   <li>一个英文单词算一个单元；</li>
 *   <li>连续的标点/空白算一个单元（保留它们才能还原原文）。</li>
 * </ul>
 *
 * <p>这样切的好处是：滑窗时可以精确控制"多少个单元"，
 * 而这些单元基本等价于 token（中文 1 字 ≈ 1 token，英文 1 词 ≈ 1~2 token）。</p>
 */
final class TextSegmenter {

    private TextSegmenter() {
    }

    /**
     * 切分为单元序列。
     */
    static List<String> segment(String text) {
        List<String> units = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return units;
        }
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (isCjk(c)) {
                units.add(String.valueOf(c));
                i++;
            } else if (Character.isLetterOrDigit(c)) {
                int j = i;
                while (j < text.length() && Character.isLetterOrDigit(text.charAt(j)) && !isCjk(text.charAt(j))) {
                    j++;
                }
                units.add(text.substring(i, j));
                i = j;
            } else {
                int j = i;
                while (j < text.length() && !isCjk(text.charAt(j))
                        && !Character.isLetterOrDigit(text.charAt(j))) {
                    j++;
                }
                units.add(text.substring(i, j));
                i = j;
            }
        }
        return units;
    }

    private static boolean isCjk(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF) || (c >= 0x3400 && c <= 0x4DBF);
    }
}
