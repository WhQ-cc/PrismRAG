package com.prism.rag.rag.chat;

/**
 * 引用（溯源）。
 *
 * <p><b>为什么 RAG 必须给引用？</b></p>
 * <p>大模型会"一本正经地胡说八道"（幻觉）。在企业场景里，
 * 员工最怕的不是 AI 答不上来，而是 <b>AI 答错了还说得很有道理</b>——
 * 这会直接影响决策。给引用，用户 3 秒钟就能判断"这段是不是真的来自制度文件"，
 * 信任成本大幅下降。</p>
 *
 * <p>所以本项目把引用做成一等公民：检索时带出来 → 流式推给前端 →
 * 落库（刷新页面还在）→ 前端可展开原文。</p>
 *
 * @param index    引用编号（回答里出现的 [1] [2]）
 * @param chunkId  分块 ID（用于展开原文）
 * @param docId    文档 ID
 * @param title    文档标题
 * @param snippet  最能说明问题的原文片段
 * @param content  完整分块内容
 * @param score    融合得分
 * @param channels 命中通道（vector / bm25）
 */
public record Citation(
        Integer index,
        Long chunkId,
        Long docId,
        String title,
        String snippet,
        String content,
        double score,
        java.util.List<String> channels
) {
}
