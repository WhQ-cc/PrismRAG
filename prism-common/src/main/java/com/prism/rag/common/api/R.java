package com.prism.rag.common.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 统一响应体。
 *
 * <p>为什么要统一？——前后端约定一套信封，前端只需要写一次解析逻辑：
 * 永远先看 {@code code == 0} 判定成功，再取 {@code data}。
 * 如果每个接口各返回各的形状，前端会写出十几种判断分支。</p>
 *
 * <p>{@code traceId} 会被前端显示在页脚，方便拿着一个 ID 找后端日志定位问题。
 * 它由 {@code TraceContext} 在请求入口生成，跨线程透传（见 prism-framework）。</p>
 *
 * @param <T> 业务数据类型
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class R<T> {

    /** 业务状态码，0 表示成功，其余见 {@link ErrorCode} */
    private int code;

    /** 提示信息，失败时给用户看，成功时一般为 "ok" */
    private String message;

    /** 真正的业务数据，失败时为 null */
    private T data;

    /** 链路追踪 ID，前端展示用 */
    private String traceId;

    /** 服务端时间戳（毫秒） */
    private long timestamp = System.currentTimeMillis();

    public static <T> R<T> ok(T data) {
        return new R<>(ErrorCode.SUCCESS.getCode(), ErrorCode.SUCCESS.getMessage(), data, null, System.currentTimeMillis());
    }

    public static <T> R<T> ok() {
        return ok(null);
    }

    public static <T> R<T> fail(ErrorCode errorCode, String message) {
        return new R<>(errorCode.getCode(), message, null, null, System.currentTimeMillis());
    }

    public static <T> R<T> fail(ErrorCode errorCode) {
        return fail(errorCode, errorCode.getMessage());
    }

    public static <T> R<T> fail(int code, String message) {
        return new R<>(code, message, null, null, System.currentTimeMillis());
    }
}
