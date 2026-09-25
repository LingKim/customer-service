package cn.net.susan.customer.internal;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QaAiClientTest {

    @Test
    void forwardsTenantIdentityAndParsesModelSource() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> tenant = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        server.createContext("/api/ai/v1/qa/evaluate", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            tenant.set(exchange.getRequestHeaders().getFirst("X-Tenant-Code"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = ("{\"code\":0,\"message\":\"成功\",\"data\":{\"aiScore\":82,"
                    + "\"riskLevel\":2,\"rules\":[\"必答项完整\"],\"comment\":\"需要补问\","
                    + "\"source\":\"llm-qwen\"}}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            QaAiClient client = new QaAiClient("http://127.0.0.1:" + server.getAddress().getPort());
            QaAiClient.Evaluation result = client.evaluate(new QaAiClient.Request(
                    "T000000000000001", "会话", "客服", "客户咨询发票", List.of(
                    new QaAiClient.Rule("必答项完整", "需核实发票信息"))), "Bearer local-test-token");
            assertEquals("Bearer local-test-token", authorization.get());
            assertEquals("T000000000000001", tenant.get());
            assertTrue(body.get().contains("客户咨询发票"));
            assertEquals(82, result.aiScore());
            assertEquals("llm-qwen", result.source());
        } finally {
            server.stop(0);
        }
    }
}
