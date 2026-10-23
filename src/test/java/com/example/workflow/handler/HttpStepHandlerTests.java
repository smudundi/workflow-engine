package com.example.workflow.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.workflow.domain.StepFailedException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HttpStepHandlerTests {

    private final HttpStepHandler handler = new HttpStepHandler();
    private HttpServer server;
    private String base;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ok", exchange -> reply(exchange, 200, "hello"));
        server.createContext("/unavailable", exchange -> reply(exchange, 503, "down"));
        server.createContext("/slow", HttpStepHandlerTests::slowReply);
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void declaresHttpType() {
        assertThat(handler.type()).isEqualTo("HTTP");
    }

    @Test
    void successfulResponseReturnsStatusAndBody() {
        StepResult result = handler.execute(context(Map.of("url", base + "/ok")));

        assertThat(result.output()).containsEntry("status", 200).containsEntry("body", "hello");
    }

    @Test
    void nonSuccessStatusFailsStep() {
        assertThatThrownBy(() -> handler.execute(context(Map.of("url", base + "/unavailable"))))
                .isInstanceOf(StepFailedException.class)
                .hasMessage("HTTP 503 from " + base + "/unavailable");
    }

    @Test
    void responseSlowerThanTimeoutFailsStep() {
        assertThatThrownBy(() -> handler.execute(context(Map.of("url", base + "/slow", "timeoutMs", 200))))
                .isInstanceOf(StepFailedException.class)
                .hasMessageContaining("timed out after 200 ms");
    }

    @Test
    void unreachableServerFailsStep() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }

        assertThatThrownBy(() -> handler.execute(context(Map.of("url", "http://127.0.0.1:" + closedPort + "/"))))
                .isInstanceOf(StepFailedException.class)
                .hasMessageContaining("failed:");
    }

    @Test
    void missingUrlFailsStep() {
        assertThatThrownBy(() -> handler.execute(context(Map.of())))
                .isInstanceOf(StepFailedException.class)
                .hasMessage("HTTP step requires a 'url' input");
    }

    private static StepContext context(Map<String, Object> input) {
        return new StepContext("step-1", 1, input, Map.of());
    }

    private static void reply(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void slowReply(HttpExchange exchange) throws IOException {
        try {
            Thread.sleep(1_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        reply(exchange, 200, "late");
    }
}
