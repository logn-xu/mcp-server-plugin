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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class H5ConfigClientTest {
    private static final JsonMapper MAPPER = new JsonMapper();
    private static final String APP = "{\"app_key\":\"frontend-demo\",\"revision\":1,\"envs\":{}}";
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private String baseUrl;
    private volatile int status = 200;
    private volatile String response = APP;
    private volatile boolean delayBody;
    private final CountDownLatch releaseBody = new CountDownLatch(1);
    private final H5ConfigClient client = new H5ConfigClient(HttpClient.newHttpClient(), Duration.ofSeconds(2));

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try (exchange) {
                requests.add(new Request(
                        exchange.getRequestMethod(),
                        exchange.getRequestURI().toASCIIString(),
                        exchange.getRequestHeaders().getFirst("x-operator"),
                        exchange.getRequestHeaders().getFirst("Authorization"),
                        exchange.getRequestHeaders().getFirst("Cookie"),
                        exchange.getRequestHeaders().getFirst("Upgrade"),
                        exchange.getRequestHeaders().getFirst("HTTP2-Settings"),
                        new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.getResponseHeaders().set("Location", "/redirect-target");
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, bytes.length);
                if (delayBody) {
                    try {
                        releaseBody.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException(e);
                    }
                }
                exchange.getResponseBody().write(bytes);
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        releaseBody.countDown();
        server.stop(0);
    }

    @Test
    void getsCanonicalConfigWithEncodedKeyAndPathPrefix() throws Exception {
        response = "{\"app_key\":\"frontend-demo:模块\",\"revision\":7,\"envs\":{}}";
        assertThat(client.get(baseUrl + "/config/", "frontend-demo:模块"))
                .containsEntry("revision", 7)
                .containsEntry("app_key", "frontend-demo:模块");
        assertThat(requests).hasSize(1);
        assertThat(requests.get(0).uri()).isEqualTo("/config/api/v1/apps/frontend-demo%3A%E6%A8%A1%E5%9D%97");
        assertThat(requests.get(0).method()).isEqualTo("GET");
        assertThat(requests.get(0).authorization()).isNull();
        assertThat(requests.get(0).cookie()).isNull();
    }

    @Test
    void postsCanonicalConfigWithEncodedOperatorAndNoImplicitDefaults() throws Exception {
        status = 201;
        response = writeResult(true);
        var source = Map.<String, Object>of(
                "target_dir", "dist",
                "archive_name", "html.tar.gz",
                "package_manager", "pnpm",
                "envs", Map.of("sit", Map.of("build_script", "build:sit")));
        var config = MAPPER.convertValue(source, H5AppConfig.class);

        assertThat(client.create(baseUrl, "frontend-demo", config, true, "运维 admin", "创建配置"))
                .containsEntry("dry_run", true)
                .containsKeys("changed", "app", "diff", "warnings");
        var sent = requests.get(0);
        assertThat(sent.method()).isEqualTo("POST");
        assertThat(sent.uri()).isEqualTo("/api/v1/apps?dry_run=true");
        assertThat(sent.operator()).isEqualTo("%E8%BF%90%E7%BB%B4%20admin");
        JsonNode body = MAPPER.readTree(sent.body());
        assertThat(body.path("app_key").stringValue()).isEqualTo("frontend-demo");
        assertThat(body.path("change_note").stringValue()).isEqualTo("创建配置");
        assertThat(body.path("package_manager").stringValue()).isEqualTo("pnpm");
        assertThat(body.path("envs").path("sit").path("build_script").stringValue())
                .isEqualTo("build:sit");
        assertThat(body.has("node_version")).isFalse();
        assertThat(body.has("storage_type")).isFalse();
        assertThat(body.has("skip_build")).isFalse();
        assertThat(source).doesNotContainKeys("app_key", "change_note");
        assertThat(sent.authorization()).isNull();
        assertThat(sent.cookie()).isNull();

        response = writeResult(false);
        assertThat(client.create(baseUrl, "frontend-demo", config, false, "admin", null))
                .containsEntry("dry_run", false);
        assertThat(requests.get(1).uri()).isEqualTo("/api/v1/apps?dry_run=false");
        assertThat(requests).hasSize(2);
    }

    @Test
    void usesHttp11WithoutH2cUpgradeForGetAndCreate() throws Exception {
        assertThat(client.get(baseUrl, "frontend-demo")).containsEntry("revision", 1);
        status = 201;
        response = writeResult(true);
        assertThat(client.create(baseUrl, "frontend-demo", config(), true, "admin", null))
                .containsEntry("dry_run", true);
        assertThat(requests).hasSize(2).allSatisfy(request -> {
            assertThat(request.upgrade()).as("no unsupported h2c upgrade").isNull();
            assertThat(request.http2Settings()).isNull();
        });
    }

    @Test
    void reportsCanonicalBackendValidationDetailsWithoutEchoingInput() {
        status = 422;
        response = """
                {"code":"VALIDATION_ERROR","message":"请求参数校验失败","details":[
                  {"field":"body","message":"Field required","type":"missing","input":"secret-token"}
                ],"debug":"internal-secret"}
                """;
        assertThatThrownBy(() -> client.create(baseUrl, "frontend-demo", config(), true, "admin", null))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("HTTP 422")
                .hasMessageContaining("VALIDATION_ERROR")
                .hasMessageContaining("请求参数校验失败")
                .hasMessageContaining("body")
                .hasMessageContaining("Field required")
                .hasMessageContaining("missing")
                .hasMessageNotContaining("secret-token")
                .hasMessageNotContaining("internal-secret");
        assertThat(requests).hasSize(1);
    }

    @Test
    void boundsCanonicalErrorDetailsAndIgnoresUnstructuredDiagnostics() {
        status = 422;
        response = "{\"code\":\"VALIDATION_ERROR\",\"message\":\"invalid\\nrequest\",\"details\":["
                + "{\"field\":\"body\",\"message\":\"" + "x".repeat(5000) + "\",\"type\":\"missing\"}]}";
        assertThatThrownBy(() -> client.get(baseUrl, "frontend-demo"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("VALIDATION_ERROR")
                .hasMessageNotContaining("\n")
                .satisfies(error -> assertThat(error.getMessage().length()).isLessThan(2300));
        response = "{\"code\":\"DB_UNAVAILABLE\",\"message\":\"数据库暂不可用\",\"details\":\"password=secret\"}";
        assertThatThrownBy(() -> client.get(baseUrl, "frontend-demo"))
                .hasMessageContaining("DB_UNAVAILABLE")
                .hasMessageNotContaining("password=secret");
    }

    @Test
    void reportsHttpErrorsWithoutRetryOrRedirect() {
        for (int code : List.of(404, 409, 401, 403, 422, 500, 302)) {
            status = code;
            response = "{\"detail\":\"backend explanation\"}";
            assertThatThrownBy(() -> client.create(baseUrl, "frontend-demo", config(), false, "admin", null))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("HTTP " + code)
                    .hasMessageContaining("backend explanation")
                    .hasMessageContaining("query the application before retrying");
        }
        assertThat(requests).hasSize(7).allSatisfy(request -> {
            assertThat(request.method()).isEqualTo("POST");
            assertThat(request.uri()).isEqualTo("/api/v1/apps?dry_run=false");
        });
    }

    @Test
    void rejectsMalformedAndMismatchedSuccessResponses() {
        for (String invalid : List.of(
                "not JSON",
                "null",
                "[]",
                "{}",
                APP + "{}",
                "{\"app_key\":\"another-app\",\"revision\":1}",
                "{\"app_key\":\"frontend-demo\",\"revision\":\"1\"}",
                "{\"app_key\":\"frontend-demo\",\"revision\":-1}")) {
            response = invalid;
            assertThatThrownBy(() -> client.get(baseUrl, "frontend-demo"))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("invalid response");
        }
        status = 201;
        for (String invalid : List.of(
                writeResult(false),
                writeResult(true).replace("\"warnings\":[]", "\"warnings\":null"),
                writeResult(true).replace("\"diff\":[]", "\"diff\":{}"),
                writeResult(true).replace("\"changed\":true", "\"changed\":\"true\""),
                writeResult(true).replace("frontend-demo", "another-app"))) {
            response = invalid;
            assertThatThrownBy(() -> client.create(baseUrl, "frontend-demo", config(), true, "admin", null))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("invalid response");
        }
    }

    @Test
    void boundsResponsesAndErrorDetails() {
        response = "x".repeat(1024 * 1024 + 1);
        assertThatThrownBy(() -> client.get(baseUrl, "frontend-demo"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("exceeds 1 MiB");
        status = 422;
        response = "{\"detail\":\"" + "x".repeat(5000) + "\"}";
        assertThatThrownBy(() -> client.get(baseUrl, "frontend-demo"))
                .isInstanceOf(IOException.class)
                .satisfies(error -> assertThat(error.getMessage().length()).isLessThan(2300));
        response = "<html>proxy failure</html>";
        assertThatThrownBy(() -> client.get(baseUrl, "frontend-demo"))
                .hasMessageContaining("HTTP 422")
                .hasMessageContaining("not valid JSON");
    }

    @Test
    void rejectsLargeRequestBeforeSending() {
        var config = MAPPER.convertValue(
                Map.of(
                        "target_dir",
                        "dist",
                        "archive_name",
                        "html.tar.gz",
                        "extra",
                        Map.of("data", "x".repeat(262144))),
                H5AppConfig.class);
        assertThatThrownBy(() -> client.create(baseUrl, "frontend-demo", config, false, "admin", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("256 KiB");
        assertThat(requests).isEmpty();
    }

    @Test
    void acceptsBodiesAtTheExactSizeLimits() throws Exception {
        String prefix = "{\"app_key\":\"frontend-demo\",\"revision\":1,\"padding\":\"";
        response = prefix + "x".repeat(1024 * 1024 - prefix.length() - 2) + "\"}";
        assertThat(response.getBytes(StandardCharsets.UTF_8)).hasSize(1024 * 1024);
        assertThat(client.get(baseUrl, "frontend-demo")).containsEntry("revision", 1);

        var empty = MAPPER.createObjectNode();
        empty.put("target_dir", "dist").put("archive_name", "html.tar.gz").put("app_key", "frontend-demo");
        empty.putObject("extra").put("padding", "");
        int overhead = MAPPER.writeValueAsBytes(empty).length;
        var config = MAPPER.convertValue(
                Map.of(
                        "target_dir",
                        "dist",
                        "archive_name",
                        "html.tar.gz",
                        "extra",
                        Map.of("padding", "x".repeat(256 * 1024 - overhead))),
                H5AppConfig.class);
        status = 201;
        response = writeResult(true);
        assertThat(client.create(baseUrl, "frontend-demo", config, true, "admin", null))
                .containsEntry("dry_run", true);
        assertThat(requests.get(1).body().getBytes(StandardCharsets.UTF_8)).hasSize(256 * 1024);
    }

    @Test
    void rejectsUnknownModelFieldsInsteadOfSilentlyDroppingThem() {
        assertThatThrownBy(() -> MAPPER.readValue(
                        "{\"target_dir\":\"dist\",\"archive_name\":\"html.tar.gz\",\"app_key\":\"spoofed\"}",
                        H5AppConfig.class))
                .hasMessageContaining("Unknown H5 config field");
        assertThatThrownBy(() -> MAPPER.readValue(
                        "{\"target_dir\":\"dist\",\"archive_name\":\"html.tar.gz\",\"envs\":{\"sit\":{\"bad\":true}}}",
                        H5AppConfig.class))
                .hasMessageContaining("Unknown H5 environment field");
    }

    @Test
    void rejectsMissingOrUnsafeServiceUrlBeforeSending() {
        var invalidUrls = new ArrayList<>(List.of(
                "",
                " ",
                "not a URL",
                "file:///tmp/config",
                "http://user:password@localhost",
                baseUrl + "?token=secret",
                baseUrl + "#fragment"));
        invalidUrls.add(null);
        for (String url : invalidUrls) {
            assertThatThrownBy(() -> client.get(url, "frontend-demo"))
                    .isInstanceOfAny(IllegalArgumentException.class, IllegalStateException.class);
        }
        assertThat(requests).isEmpty();
    }

    @Test
    void timesOutEvenWhenHeadersHaveArrivedButBodyStalls() {
        delayBody = true;
        var shortTimeoutClient = new H5ConfigClient(HttpClient.newHttpClient(), Duration.ofMillis(200));
        long start = System.nanoTime();
        assertThatThrownBy(() -> shortTimeoutClient.get(baseUrl, "frontend-demo"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("timed out");
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));
    }

    private static H5AppConfig config() {
        return MAPPER.convertValue(Map.of("target_dir", "dist", "archive_name", "html.tar.gz"), H5AppConfig.class);
    }

    private static String writeResult(boolean dryRun) {
        return "{\"dry_run\":" + dryRun + ",\"changed\":true,\"app\":" + APP + ",\"diff\":[],\"warnings\":[]}";
    }

    private record Request(
            String method,
            String uri,
            String operator,
            String authorization,
            String cookie,
            String upgrade,
            String http2Settings,
            String body) {}
}
