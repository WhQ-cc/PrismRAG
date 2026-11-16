package com.prism.rag.rag.chat.dto;

import lombok.Data;

/**
 * 问答请求。
 */
@Data
public class AskRequest {

    /** 用户问题 */
    private String question;

    /** 知识库 ID（为空表示全库检索） */
    private Long kbId;

    /** 会话 ID（为空表示新会话） */
    private Long conversationId;

    /** SSE 会话 ID（前端生成，用于取消） */
    private String sessionId;

    /** 检索条数 */
    private Integer topK;

    /** 是否开启深度思考 */
    private Boolean deepThinking = false;
}
