package com.prism.rag.framework.web;

import com.prism.rag.common.api.ErrorCode;
import com.prism.rag.common.api.R;
import com.prism.rag.common.exception.BizException;
import com.prism.rag.framework.context.TraceContext;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理器。
 *
 * <p><b>为什么必须统一？</b>没有它的话，一个 Controller 里抛了异常，
 * Spring 会返回一段 Whitelabel Error Page 的 HTML；前端 JSON.parse 直接报错，
 * 用户看到的是"出错了"而不是"知识库不存在"。</p>
 *
 * <p>统一之后，无论哪里抛异常，前端拿到的都是同一种信封：
 * {@code {code, message, data, traceId}}。</p>
 *
 * <p><b>设计原则：</b></p>
 * <ul>
 *   <li>能预期的业务异常 → 返回明确业务码 + 人话提示；</li>
 *   <li>没预料到的系统异常 → 返回 500 + 通用提示，<b>但把真实堆栈打进日志</b>，
 *       绝不能把堆栈或 SQL 泄露给前端（那是安全红线）。</li>
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 业务异常：可预期，把业务码和提示原样返回。
     */
    @ExceptionHandler(BizException.class)
    public R<Void> handleBizException(BizException e, HttpServletRequest request) {
        log.warn("[业务异常] {} {} -> {}", request.getMethod(), request.getRequestURI(), e.getMessage());
        R<Void> result = R.fail(e.getErrorCode(), e.getMessage());
        result.setTraceId(TraceContext.getOrCreateTraceId());
        return result;
    }

    /**
     * 参数校验异常（@Valid 失败）。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public R<Void> handleValidation(MethodArgumentNotValidException e) {
        FieldError fieldError = e.getBindingResult().getFieldError();
        String message = fieldError == null
                ? ErrorCode.BAD_REQUEST.getMessage()
                : fieldError.getField() + " " + fieldError.getDefaultMessage();
        return R.fail(ErrorCode.BAD_REQUEST, message);
    }

    /**
     * 缺少必填请求参数。
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public R<Void> handleMissingParam(MissingServletRequestParameterException e) {
        return R.fail(ErrorCode.PARAM_MISSING, "缺少参数: " + e.getParameterName());
    }

    /**
     * 兜底：所有没被上面捕获的异常。
     *
     * <p>注意 HTTP 状态码仍然返回 500，但响应体是我们自己的信封，
     * 前端统一按 {@code code} 判断即可，不必同时看 HTTP 状态。</p>
     */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public R<Void> handleException(Exception e, HttpServletRequest request) {
        String traceId = TraceContext.getOrCreateTraceId();
        // 真实原因只进日志，不返回给前端
        log.error("[系统异常] traceId={} {} {}", traceId, request.getMethod(), request.getRequestURI(), e);
        R<Void> result = R.fail(ErrorCode.SYSTEM_ERROR, "系统开小差了，请稍后重试（traceId: " + traceId + "）");
        result.setTraceId(traceId);
        return result;
    }
}
