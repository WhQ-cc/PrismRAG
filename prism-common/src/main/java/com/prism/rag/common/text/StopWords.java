package com.prism.rag.common.text;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * 中文停用词表。
 *
 * <p>为什么需要？用户问"报销的流程是什么"，"的" "是" "什么" 这些字在几乎所有文档里都出现，
 * 如果参与 BM25 打分，它们会因为 df 极大而 idf 接近 0，本身影响不大；
 * 但在"精确匹配"场景（例如用户输入与文档标题完全一致）里，噪声词会稀释真正关键词的权重。
 * 所以在分词阶段直接丢弃，是最省事的做法。</p>
 *
 * <p>这里只放高频虚词。真正的关键词保留不动。</p>
 */
public final class StopWords {

    private StopWords() {
    }

    private static final Set<String> WORDS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "的", "了", "是", "在", "我", "有", "和", "就", "不", "人", "都", "一", "一个",
            "上", "也", "很", "到", "说", "要", "去", "你", "会", "着", "没有", "看", "好", "自己", "这",
            "那", "什么", "怎么", "如何", "为什么", "哪些", "哪个", "可以", "请问", "吗", "呢", "吧", "啊",
            "以及", "并且", "或者", "但是", "因为", "所以", "如果", "就是", "还是", "我们", "他们", "它们",
            "这个", "那个", "这些", "那些", "一下", "一些", "需要", "进行", "通过", "对于", "关于",
            "the", "a", "an", "is", "are", "was", "were", "be", "of", "to", "in", "on", "and", "or",
            "for", "with", "what", "how", "why", "which", "that", "this", "it", "you", "i", "we",
            "do", "does", "did", "can", "should", "would", "please"
    )));

    public static boolean contains(String token) {
        return token != null && WORDS.contains(token.toLowerCase());
    }

    public static Set<String> all() {
        return WORDS;
    }
}
