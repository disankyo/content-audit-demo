package com.example.audit.common;

import java.io.Serializable;

/**
 * 统一返回结构。
 */
public record R<T>(int code, String msg, T data) implements Serializable {

    public static <T> R<T> ok(T data) {
        return new R<>(0, "ok", data);
    }

    public static <T> R<T> ok() {
        return new R<>(0, "ok", null);
    }

    public static <T> R<T> fail(String msg) {
        return new R<>(1, msg, null);
    }
}
