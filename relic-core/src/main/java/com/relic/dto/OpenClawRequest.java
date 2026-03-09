package com.relic.dto;

import lombok.Data;

@Data
public class OpenClawRequest {
    // 对应 JSON 中的 "type": "req"
    private String type;

    // 对应 JSON 中的 "id": "fd58351c..." (流水号)
    private String id;

    // 对应 JSON 中的 "method": "chat.send"
    private String method;

    // 嵌套的 params 对象
    private Params params;

    @Data
    public static class Params {
        // 对应 "sessionKey": "agent:main:main"
        private String sessionKey;

        // 核心数据：用户发送的具体消息
        private String message;

        // 对应 "deliver": false
        private Boolean deliver;

        // 对应 "idempotencyKey": "cd453e..."
        private String idempotencyKey;
    }
}