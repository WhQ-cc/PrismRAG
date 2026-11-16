package com.prism.rag.rag.retrieval;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BM25 倒排索引测试。
 */
class Bm25IndexTest {

    private Bm25Index index;

    @BeforeEach
    void setUp() {
        index = new Bm25Index();
        index.add(1L, 10L, 100L, "差旅住宿费实行限额报销制度，一线城市住宿标准为每晚 600 元。");
        index.add(2L, 10L, 100L, "报销金额在 5000 元以下的由部门总监审批。");
        index.add(3L, 11L, 100L, "年假需提前 3 个工作日在系统提交申请，经部门负责人审批后生效。");
    }

    @Test
    @DisplayName("精确词能召回对应 chunk 且排第一")
    void shouldRankExactMatchFirst() {
        List<Bm25Hit> hits = index.search("住宿标准", 100L, 5);
        assertFalse(hits.isEmpty());
        assertEquals(1L, hits.get(0).chunkId(), "含『住宿标准』的 chunk 应排第一");
    }

    @Test
    @DisplayName("知识库过滤生效")
    void shouldFilterByKnowledgeBase() {
        List<Bm25Hit> hits = index.search("审批", 999L, 5);
        assertTrue(hits.isEmpty(), "其它知识库不应被召回");
    }

    @Test
    @DisplayName("完全不相关的词零召回")
    void shouldReturnEmptyForUnrelatedQuery() {
        List<Bm25Hit> hits = index.search("量子力学", 100L, 5);
        assertTrue(hits.isEmpty());
    }

    @Test
    @DisplayName("删除文档后其 chunk 不再被召回")
    void shouldRemoveDocument() {
        index.removeByDocument(10L);
        List<Bm25Hit> hits = index.search("住宿标准", 100L, 5);
        assertTrue(hits.isEmpty() || hits.get(0).chunkId() != 1L);
        assertEquals(1, index.size(), "只剩 1 个 chunk");
    }

    @Test
    @DisplayName("重复索引同一个 chunk 会覆盖而不是叠加")
    void shouldOverwriteSameChunk() {
        index.add(1L, 10L, 100L, "完全不同的内容 报销 差旅");
        assertEquals(3, index.size(), "chunk 数不应增加");

        List<Bm25Hit> hits = index.search("限额", 100L, 5);
        // 注意：结果不一定为空——"限额"分词后含单字"额"，
        // 可能命中其它含"金额"的 chunk。真正要断言的是"旧内容的 chunk 不再被召回"。
        assertTrue(hits.stream().noneMatch(h -> h.chunkId() == 1L),
                "旧内容所属的 chunk 不应再被召回，实际命中: " + hits);
    }
}
