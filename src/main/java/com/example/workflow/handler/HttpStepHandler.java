package com.example.workflow.handler;

import com.example.workflow.domain.StepFailedException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class HttpStepHandler implements StepHandler {

    private static final long DEFAULT_TIMEOUT_MS = 5_000;

    private final HttpClient client = HttpClient.newHttpClient();

    @Override
    public String type() {
        return "HTTP";
    }

    @Override
    public StepResult execute(StepContext context) {
        if (!(context.input().get("url") instanceof String url) || url.isBlank()) {
            throw new StepFailedException("HTTP step requires a 'url' input");
        }
        String method = context.input().get("method") instanceof String text ? text : "GET";
        long timeoutMs = context.input().get("timeoutMs") instanceof Number n ? n.longValue() : DEFAULT_TIMEOUT_MS;
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .method(method, HttpRequest.BodyPublishers.noBody())
                .timeout(Duration.ofMillis(timeoutMs))
                .build();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status < 200 || status > 299) {
                throw new StepFailedException("HTTP " + status + " from " + url);
            }
            return new StepResult(Map.of("status", status, "body", response.body()));
        } catch (HttpTimeoutException e) {
            throw new StepFailedException("HTTP request to " + url + " timed out after " + timeoutMs + " ms");
        } catch (IOException e) {
            throw new StepFailedException("HTTP request to " + url + " failed: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StepFailedException("HTTP request to " + url + " was interrupted");
        }
    }
}
