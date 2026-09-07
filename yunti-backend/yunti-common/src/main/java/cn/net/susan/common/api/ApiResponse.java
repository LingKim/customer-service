package cn.net.susan.common.api;

import java.time.Instant;

/**
 * 统一响应体。
 */
public record ApiResponse<T>(int code, String message, T data, String requestId, long timestamp) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(ResultCode.SUCCESS.getCode(), ResultCode.SUCCESS.getMessage(), data, null, Instant.now().toEpochMilli());
    }

    public static <T> ApiResponse<T> ok() {
        return ok(null);
    }

    public static <T> ApiResponse<T> fail(ResultCode rc) {
        return new ApiResponse<>(rc.getCode(), rc.getMessage(), null, null, Instant.now().toEpochMilli());
    }

    public static <T> ApiResponse<T> fail(int code, String message) {
        return new ApiResponse<>(code, message, null, null, Instant.now().toEpochMilli());
    }
}
