package com.minijudge.common;

/**
 * 统一响应体（与单体项目同一契约风格：code=0 成功）。
 * 不引 Spring/Lombok 重依赖，保持公共模块最薄。
 */
public class Result<T> {

    public final int code;
    public final String msg;
    public final T data;

    private Result(int code, String msg, T data) {
        this.code = code;
        this.msg = msg;
        this.data = data;
    }

    public static <T> Result<T> ok(T data) { return new Result<>(0, "ok", data); }

    public static <T> Result<T> fail(int code, String msg) { return new Result<>(code, msg, null); }
}
