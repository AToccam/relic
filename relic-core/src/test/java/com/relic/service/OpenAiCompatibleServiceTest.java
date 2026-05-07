package com.relic.service;

import com.relic.dto.ToolCallResult;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OpenAiCompatibleServiceTest {

    @Test
    void streamParserPreservesWhitespaceOnlyContentChunks() throws Exception {
        TestOpenAiService service = new TestOpenAiService();
        StringBuilder streamed = new StringBuilder();
        String sse = String.join("\n",
                "data: {\"choices\":[{\"delta\":{\"content\":\"# China\"},\"finish_reason\":null}]}",
                "data: {\"choices\":[{\"delta\":{\"content\":\"\\n\\n\"},\"finish_reason\":null}]}",
                "data: {\"choices\":[{\"delta\":{\"content\":\"## Basic Info\"},\"finish_reason\":null}]}",
                "data: [DONE]",
                "");

        ToolCallResult result = invokeParseStreamResponse(
                service,
                new ByteArrayInputStream(sse.getBytes(StandardCharsets.UTF_8)),
                streamed::append);

        assertEquals("# China\n\n## Basic Info", streamed.toString());
        assertEquals(streamed.toString(), result.getContentString());
    }

    private static ToolCallResult invokeParseStreamResponse(
            OpenAiCompatibleService service,
            InputStream input,
            java.util.function.Consumer<String> onChunk) throws Exception {
        Method method = OpenAiCompatibleService.class.getDeclaredMethod(
                "parseStreamResponse",
                InputStream.class,
                java.util.function.Consumer.class,
                List.class,
                List.class);
        method.setAccessible(true);
        return (ToolCallResult) method.invoke(service, input, onChunk, List.of(), null);
    }

    private static final class TestOpenAiService extends OpenAiCompatibleService {
        @Override
        protected String getApiKey() {
            return "test";
        }

        @Override
        protected String getUrl() {
            return "http://localhost";
        }

        @Override
        protected String getModel() {
            return "test-model";
        }

        @Override
        public String getName() {
            return "test";
        }

        @Override
        protected void applyToolPayload(
                Map<String, Object> requestBody,
                List<Map<String, Object>> messages,
                List<Map<String, Object>> tools) {
        }
    }
}
