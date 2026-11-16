package com.prism.rag.infra.ai.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 检索到的知识片段。
 *
 * <p>注意它定义在模型层而不是业务层：这样模型层可以独立地实现 Mock 版本，
 * 业务层把检索结果映射成它，两者不会产生循环依赖。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContextItem {

    /** 引用编号，从 1 开始，前端展示 [1] [2] 就用这个 */
    private Integer index;

    /** 来源标题（通常是文档名） */
    private String title;

    /** 片段正文 */
    private String content;

    /** 命中的知识库 ID */
    private Long knowledgeBaseId;

    /** 命中的分块 ID（引用溯源用） */
    private Long chunkId;
}
