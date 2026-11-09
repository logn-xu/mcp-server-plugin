/*
 * The MIT License
 *
 * Copyright (c) 2026, Jenkins MCP Server Plugin contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package io.jenkins.plugins.mcp.server.extensions.h5;

import hudson.ProxyConfiguration;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

public final class H5ConfigClient {
    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();
    private static final int MAX_REQUEST_BYTES = 256 * 1024;
    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;
    private static final TypeReference<Map<String, Object>> OBJECT_TYPE = new TypeReference<>() {};
    private final HttpClient http;
    private final Duration timeout;

    public H5ConfigClient() {
        this(
                ProxyConfiguration.newHttpClientBuilder()
                        .connectTimeout(Duration.ofSeconds(5))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build(),
                Duration.ofSeconds(20));
    }

    H5ConfigClient(HttpClient http, Duration timeout) {
        this.http = http;
        this.timeout = timeout;
    }

    public Map<String, Object> get(String baseUrl, String appKey) throws IOException, InterruptedException {
        var response = send(baseUrl, "/api/v1/apps/" + encode(appKey), null, null, 200);
        checkApp(response, appKey);
        return MAPPER.convertValue(response, OBJECT_TYPE);
    }

    public Map<String, Object> create(
            String baseUrl, String appKey, H5AppConfig config, boolean dryRun, String operator, String changeNote)
            throws IOException, InterruptedException {
        ObjectNode payload = MAPPER.valueToTree(config);
        payload.put("app_key", appKey);
        if (changeNote != null) {
            payload.put("change_note", changeNote);
        }
        var response = send(baseUrl, "/api/v1/apps?dry_run=" + dryRun, payload, operator, 201);
        if (!response.path("dry_run").isBoolean()
                || response.path("dry_run").booleanValue() != dryRun
                || !response.path("changed").isBoolean()
                || !response.path("diff").isArray()
                || !response.path("warnings").isArray()) {
            throw invalidResponse();
        }
        checkApp(response.path("app"), appKey);
        return MAPPER.convertValue(response, OBJECT_TYPE);
    }

    private ObjectNode send(String baseUrl, String path, ObjectNode payload, String operator, int expectedStatus)
            throws IOException, InterruptedException {
        // Uvicorn's HTTP/1.1 endpoint does not support h2c upgrade requests with a body.
        // Pin each request (including injected clients) instead of relying on the client's HTTP/2 default.
        var request = HttpRequest.newBuilder(endpoint(baseUrl, path))
                .version(HttpClient.Version.HTTP_1_1)
                .timeout(timeout)
                .header("Accept", "application/json");
        if (payload == null) {
            request.GET();
        } else {
            byte[] body = MAPPER.writeValueAsBytes(payload);
            if (body.length > MAX_REQUEST_BYTES) {
                throw new IllegalArgumentException("H5 configuration request exceeds 256 KiB.");
            }
            request.header("Content-Type", "application/json")
                    .header("x-operator", encode(operator))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        }
        HttpResponse<byte[]> response;
        var pending = http.sendAsync(request.build(), ignored -> new LimitedBodySubscriber());
        try {
            // Bound the entire body read on Java 17 too, not just receipt of response headers.
            response = pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw e;
        } catch (ExecutionException | TimeoutException e) {
            pending.cancel(true);
            String reason = e instanceof ExecutionException
                            && e.getCause() instanceof IOException cause
                            && "H5 API response exceeds 1 MiB.".equals(cause.getMessage())
                    ? "H5 API response exceeds 1 MiB."
                    : "H5 configuration request failed or timed out.";
            var failure = new IOException(reason + " If creating, query the application before retrying;"
                    + " the server may already have committed the request.");
            // The MCP wrapper reports the deepest cause; retain diagnostics without losing the retry warning.
            failure.addSuppressed(e);
            throw failure;
        }
        int status = response.statusCode();
        if (status != expectedStatus) {
            String explanation =
                    switch (status) {
                        case 404 -> "Application or API endpoint not found";
                        case 409 -> "Application already exists or conflicts; no overwrite was attempted";
                        case 401, 403 -> "Configuration service denied access";
                        case 422 -> "Configuration validation failed";
                        default -> "Unexpected configuration service response";
                    };
            throw new IOException("H5 API HTTP " + status + ": " + explanation + errorDetail(response.body())
                    + ". If creating, query the application before retrying.");
        }
        try {
            var json = MAPPER.readTree(response.body());
            if (json instanceof ObjectNode object) {
                return object;
            }
        } catch (JacksonException e) {
            throw invalidResponse();
        }
        throw invalidResponse();
    }

    private static URI endpoint(String baseUrl, String path) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("H5 configuration service is not configured. Set "
                    + "io.jenkins.plugins.mcp.server.extensions.H5ConfigExtension.baseUrl.");
        }
        URI base;
        try {
            base = URI.create(baseUrl);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("H5 configuration baseUrl must be a valid HTTP(S) URL.");
        }
        if (!("http".equals(base.getScheme()) || "https".equals(base.getScheme()))
                || base.getHost() == null
                || base.getUserInfo() != null
                || base.getRawQuery() != null
                || base.getRawFragment() != null) {
            throw new IllegalArgumentException(
                    "H5 configuration baseUrl must be an HTTP(S) URL without user-info, query or fragment.");
        }
        return URI.create(baseUrl.replaceAll("/+$", "") + path);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static void checkApp(JsonNode app, String appKey) throws IOException {
        if (!app.isObject()
                || !app.path("app_key").isString()
                || !appKey.equals(app.path("app_key").stringValue())
                || !app.path("revision").isIntegralNumber()
                || app.path("revision").longValue() < 0) {
            throw invalidResponse();
        }
    }

    private static IOException invalidResponse() {
        return new IOException("H5 API returned an invalid response (JSON shape, app_key or dry_run mismatch)."
                + " If creating, query the application before retrying.");
    }

    private static String errorDetail(byte[] body) {
        try {
            var json = MAPPER.readTree(body);
            if (json != null && json.isObject()) {
                // h5-config-api uses code/message/details, not FastAPI's default detail envelope.
                // Only expose diagnostic fields, never echo request input or arbitrary backend diagnostics.
                var diagnostic = MAPPER.createObjectNode();
                for (String field : List.of("code", "message")) {
                    if (json.path(field).isString()) {
                        diagnostic.put(field, json.path(field).stringValue());
                    }
                }
                if (json.path("details").isArray()) {
                    var details = diagnostic.putArray("details");
                    for (var issue : json.path("details")) {
                        var entry = MAPPER.createObjectNode();
                        for (String field : List.of("field", "message", "type")) {
                            if (issue.path(field).isString()) {
                                entry.put(field, issue.path(field).stringValue());
                            }
                        }
                        if (!entry.isEmpty()) {
                            details.add(entry);
                        }
                    }
                }
                if (!diagnostic.isEmpty()) {
                    return boundedDetail(diagnostic.toString());
                }
            }
            if (json != null && json.hasNonNull("detail")) {
                return boundedDetail(json.get("detail").toString());
            }
            return "";
        } catch (JacksonException e) {
            // A proxy may return HTML; the HTTP status still gives an explicit failure.
            return "; response body is not valid JSON";
        }
    }

    private static String boundedDetail(String value) {
        String detail = value.replaceAll("[\\r\\n\\p{Cntrl}]", " ");
        return "; detail=" + detail.substring(0, Math.min(detail.length(), 2048));
    }

    private static final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
        private Flow.Subscription subscription;
        private int received;
        private boolean done;

        @Override
        public CompletionStage<byte[]> getBody() {
            return delegate.getBody();
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            delegate.onSubscribe(subscription);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            if (done) {
                return;
            }
            for (var buffer : buffers) {
                if (buffer.remaining() > MAX_RESPONSE_BYTES - received) {
                    done = true;
                    subscription.cancel();
                    delegate.onError(new IOException("H5 API response exceeds 1 MiB."));
                    return;
                }
                received += buffer.remaining();
            }
            delegate.onNext(buffers);
        }

        @Override
        public void onError(Throwable error) {
            if (!done) {
                done = true;
                delegate.onError(error);
            }
        }

        @Override
        public void onComplete() {
            if (!done) {
                done = true;
                delegate.onComplete();
            }
        }
    }
}
