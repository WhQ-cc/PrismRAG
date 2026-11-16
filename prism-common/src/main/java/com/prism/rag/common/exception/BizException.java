package com.prism.rag.common.exception;

import com.prism.rag.common.api.ErrorCode;
import lombok.Getter;

/**
 * 业务异常。
 *
 * <p>凡是用业务原因导致的失败（参数不对、状态非法、模型不可用……）一律抛这个异常，
 * 由 {@code GlobalExceptionHandler} 统一翻译成 {@code R.fail(...)}。
 * 而不是"能预期的失败"就 try-catch 一下 return null——那样调用方无法区分
 * "真的没数据" 和 "出错了"，排查问题时会很痛苦。</p>
 */
@Getter
public class BizException extends RuntimeException {

    private final ErrorCode errorCode;

    public BizException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public BizException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public BizException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    /** 业务异常一般不用填堆栈，禁用 fillInStackTrace 可以省下大量 CPU */
    @Override
    public synchronized Throwable fillInStackTrace() {
        return this;
    }
}
