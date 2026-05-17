package com.relic.util;

import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.TimeoutException;

public final class TimeoutMessages {

    public static final String REQUEST_DEADLINE =
            "本次请求已达到总超时时间，已停止继续处理。请缩短问题或稍后重试。";
    public static final String SSE_TIMEOUT =
            "本次请求处理时间过长，已停止生成。请缩短问题或稍后重试。";
    public static final String TOOL_TIMEOUT =
            "工具执行超时，已停止本次工具调用。请缩小文件、图表或读取内容后重试。";
    public static final String TOOL_QUEUE_FULL =
            "工具执行队列已满，当前并发工具任务较多，请稍后重试。";
    public static final String TOOL_INTERRUPTED =
            "工具执行被中断，本次工具调用已停止。请稍后重试。";
    public static final String ADVISOR_TIMEOUT =
            "部分顾问模型响应超时，已使用已返回结果继续生成。";

    private TimeoutMessages() {
    }

    public static String modelTimeout(String providerName, Throwable error) {
        String provider = providerName == null || providerName.isBlank() ? "当前" : providerName;
        if (isDeadlineTimeout(error)) {
            return REQUEST_DEADLINE;
        }
        if (isConnectTimeout(error)) {
            return "连接 " + provider + " 模型服务超时，请检查网络或稍后重试。";
        }
        if (isTimeout(error)) {
            return "等待 " + provider + " 模型响应超时，请稍后重试或缩短本次问题。";
        }
        return null;
    }

    public static String requestError(Throwable error) {
        if (isDeadlineTimeout(error)) {
            return REQUEST_DEADLINE;
        }
        if (isTimeout(error)) {
            return SSE_TIMEOUT;
        }
        String message = error == null ? "" : error.getMessage();
        return "后端处理失败：" + (message == null || message.isBlank() ? "未知错误" : message);
    }

    public static boolean isDeadlineTimeout(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            String message = current.getMessage();
            if (message != null && (message.contains("请求已达到总超时时间")
                    || message.contains("请求处理超时")
                    || message.contains("request deadline"))) {
                return true;
            }
        }
        return false;
    }

    public static boolean isConnectTimeout(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof HttpConnectTimeoutException) {
                return true;
            }
            String name = current.getClass().getName().toLowerCase();
            String message = current.getMessage() == null ? "" : current.getMessage().toLowerCase();
            if (name.contains("connecttimeoutexception") || message.contains("connect timed out")) {
                return true;
            }
        }
        return false;
    }

    public static boolean isTimeout(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof TimeoutException
                    || current instanceof HttpTimeoutException
                    || current instanceof SocketTimeoutException
                    || current instanceof InterruptedIOException) {
                return true;
            }
            String name = current.getClass().getName().toLowerCase();
            String message = current.getMessage() == null ? "" : current.getMessage().toLowerCase();
            if (name.contains("timeoutexception")
                    || message.contains("timed out")
                    || message.contains("timeout")) {
                return true;
            }
        }
        return false;
    }
}
