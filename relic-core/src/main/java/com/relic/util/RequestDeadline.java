package com.relic.util;

/**
 * Per-request deadline shared by streaming, model calls and tool execution.
 */
public final class RequestDeadline {

    private static final ThreadLocal<Long> DEADLINE_EPOCH_MS = new ThreadLocal<>();

    private RequestDeadline() {
    }

    public static void start(long timeoutMs) {
        DEADLINE_EPOCH_MS.set(System.currentTimeMillis() + Math.max(1L, timeoutMs));
    }

    public static void setDeadlineEpochMillis(Long deadlineEpochMs) {
        if (deadlineEpochMs == null) {
            DEADLINE_EPOCH_MS.remove();
        } else {
            DEADLINE_EPOCH_MS.set(deadlineEpochMs);
        }
    }

    public static Long currentDeadlineEpochMillis() {
        return DEADLINE_EPOCH_MS.get();
    }

    public static long remainingMillis(long fallbackMs) {
        long safeFallback = Math.max(1L, fallbackMs);
        Long deadline = DEADLINE_EPOCH_MS.get();
        if (deadline == null) {
            return safeFallback;
        }
        return Math.max(0L, Math.min(safeFallback, deadline - System.currentTimeMillis()));
    }

    public static boolean isExpired() {
        Long deadline = DEADLINE_EPOCH_MS.get();
        return deadline != null && System.currentTimeMillis() >= deadline;
    }

    public static void throwIfExpired() {
        if (isExpired()) {
            throw new IllegalStateException("请求处理超时，请稍后重试。");
        }
    }

    public static void clear() {
        DEADLINE_EPOCH_MS.remove();
    }
}
