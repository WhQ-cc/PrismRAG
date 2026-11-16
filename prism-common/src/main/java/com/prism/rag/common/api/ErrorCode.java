package com.prism.rag.common.api;

import lombok.Getter;

/**
 * 业务错误码。
 *
 * <p>约定：前两位对齐 HTTP 状态语义（40xxx 参数/请求问题，50xxx 服务端问题），
 * 后三位是业务细分。这样前端既能按 HTTP 状态粗分类，又能按 code 精确定位。</p>
 */
@Getter
public enum ErrorCode {

    /** 成功 */
    SUCCESS(0, "ok"),

    // ---------- 40xxx：请求本身有问题 ----------
    BAD_REQUEST(40000, "请求参数不合法"),
    PARAM_MISSING(40001, "缺少必要参数"),
    KNOWLEDGE_BASE_NOT_FOUND(40400, "知识库不存在"),
    DOCUMENT_NOT_FOUND(40401, "文档不存在"),
    TASK_NOT_FOUND(40402, "入库任务不存在"),
    CONVERSATION_NOT_FOUND(40403, "会话不存在"),

    // ---------- 50xxx：服务端执行出问题 ----------
    SYSTEM_ERROR(50000, "系统内部错误"),
    INGESTION_FAILED(50001, "入库流水线执行失败"),
    LLM_UNAVAILABLE(50300, "大模型不可用，请检查模型配置或 Key"),
    EMBEDDING_FAILED(50301, "向量化失败"),
    RETRIEVAL_FAILED(50302, "检索失败"),
    TOOL_EXECUTION_FAILED(50303, "工具执行失败");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }
}
