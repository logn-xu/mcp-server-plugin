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

package io.jenkins.plugins.mcp.server.extensions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import hudson.ExtensionList;
import hudson.model.User;
import hudson.security.ACL;
import io.jenkins.plugins.mcp.server.extensions.h5.H5AppConfig;
import io.jenkins.plugins.mcp.server.junit.JenkinsMcpClientBuilder;
import io.jenkins.plugins.mcp.server.junit.McpClientTest;
import io.jenkins.plugins.mcp.server.junit.StatelessMcpTestClient;
import io.jenkins.plugins.mcp.server.junit.TestCrumbIssuerMcpExtension;
import io.jenkins.plugins.mcp.server.junit.TestUtils;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import jenkins.model.Jenkins;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.springframework.security.access.AccessDeniedException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(TestCrumbIssuerMcpExtension.class)
class H5ConfigExtensionTest {
    private static final JsonMapper MAPPER = new JsonMapper();
    private static final Map<String, Object> CONFIG = Map.of(
            "target_dir", "dist",
            "archive_name", "html.tar.gz",
            "package_manager", "pnpm",
            "envs", Map.of("sit", Map.of("build_script", "build:sit")));
    private final List<JsonNode> payloads = new CopyOnWriteArrayList<>();
    private final List<String> operators = new CopyOnWriteArrayList<>();
    private final List<String> paths = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private String previousUrl;

    @BeforeEach
    void start() throws Exception {
        previousUrl = System.getProperty(H5ConfigExtension.BASE_URL_PROPERTY);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try (exchange) {
                paths.add(exchange.getRequestURI().getPath());
                String body;
                int status;
                if ("GET".equals(exchange.getRequestMethod())) {
                    String key = exchange.getRequestURI().getPath().substring("/api/v1/apps/".length());
                    body = MAPPER.writeValueAsString(Map.of("app_key", key, "revision", 1, "envs", Map.of()));
                    status = 200;
                } else {
                    JsonNode input = MAPPER.readTree(exchange.getRequestBody());
                    payloads.add(input);
                    operators.add(exchange.getRequestHeaders().getFirst("x-operator"));
                    boolean dryRun =
                            "dry_run=true".equals(exchange.getRequestURI().getQuery());
                    body = MAPPER.writeValueAsString(Map.of(
                            "dry_run",
                            dryRun,
                            "changed",
                            true,
                            "app",
                            Map.of("app_key", input.path("app_key").stringValue(), "revision", 1),
                            "diff",
                            List.of(),
                            "warnings",
                            List.of()));
                    status = 201;
                }
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        });
        server.start();
        System.setProperty(
                H5ConfigExtension.BASE_URL_PROPERTY,
                "http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stop() {
        if (previousUrl == null) {
            System.clearProperty(H5ConfigExtension.BASE_URL_PROPERTY);
        } else {
            System.setProperty(H5ConfigExtension.BASE_URL_PROPERTY, previousUrl);
        }
        server.stop(0);
    }

    @McpClientTest
    void adminCanDiscoverQueryPreviewAndCreate(JenkinsRule jenkins, JenkinsMcpClientBuilder builder) throws Exception {
        secure(jenkins);
        try (var client = clientAs(jenkins, builder, "admin")) {
            var tools = client.listTools();
            var get = TestUtils.findToolByName(tools, "getH5Config");
            var create = TestUtils.findToolByName(tools, "createH5Config");
            assertThat(get).isNotNull();
            assertThat(create).isNotNull();
            assertThat(get.annotations().readOnlyHint()).isTrue();
            assertThat(create.annotations().readOnlyHint()).isFalse();
            JsonNode schema = MAPPER.valueToTree(create.inputSchema());
            assertThat(schema.path("required").toString())
                    .contains("projectName", "config")
                    .doesNotContain("dryRun");
            JsonNode configSchema = schema.path("properties").path("config");
            assertThat(configSchema.path("required").toString()).contains("target_dir", "archive_name");
            assertThat(configSchema.path("additionalProperties").booleanValue()).isFalse();
            assertThat(configSchema.path("properties").has("app_key")).isFalse();
            assertThat(configSchema.path("properties").has("envs")).isTrue();
            assertThat(configSchema.path("properties").path("package_manager").toString())
                    .contains("pnpm", "npm", "yarn");

            var query = client.callTool(new McpSchema.CallToolRequest(
                    "getH5Config", Map.of("projectName", "Frontend-Demo", "module", "Web")));
            assertThat(query.isError()).isFalse();
            assertThat(result(query).path("app_key").stringValue()).isEqualTo("frontend-demo:web");
            assertThat(result(query).path("revision").intValue()).isEqualTo(1);

            var preview = client.callTool(new McpSchema.CallToolRequest(
                    "createH5Config", Map.of("projectName", "Frontend-Demo", "config", CONFIG)));
            assertThat(preview.isError()).isFalse();
            assertThat(result(preview).path("dry_run").booleanValue()).isTrue();
            var write = client.callTool(new McpSchema.CallToolRequest(
                    "createH5Config",
                    Map.of(
                            "projectName",
                            "Frontend-Demo",
                            "config",
                            CONFIG,
                            "dryRun",
                            false,
                            "changeNote",
                            "initial")));
            assertThat(write.isError()).isFalse();
            assertThat(result(write).path("dry_run").booleanValue()).isFalse();
            assertThat(result(write).has("warnings")).isTrue();
            assertThat(result(write).has("diff")).isTrue();
            assertThat(payloads).hasSize(2);
            assertThat(payloads.get(1).path("change_note").stringValue()).isEqualTo("initial");
            assertThat(operators).containsExactly("admin", "admin");
            assertThat(jenkins.jenkins.getItem("Frontend-Demo")).isNull();
            assertThat(jenkins.jenkins.getQueue().getItems()).isEmpty();

            for (Map<String, Object> invalid : List.of(
                    Map.<String, Object>of("target_dir", "dist"),
                    Map.<String, Object>of("target_dir", "dist", "archive_name", "html.tar.gz", "app_key", "spoofed"),
                    Map.<String, Object>of(
                            "target_dir",
                            "dist",
                            "archive_name",
                            "html.tar.gz",
                            "envs",
                            Map.of("sit", Map.of("unknown", "value"))),
                    Map.<String, Object>of(
                            "target_dir", "dist", "archive_name", "html.tar.gz", "package_manager", "bad"))) {
                assertThat(client.callTool(new McpSchema.CallToolRequest(
                                        "createH5Config", Map.of("projectName", "frontend-demo", "config", invalid)))
                                .isError())
                        .as("invalid config: %s", invalid)
                        .isTrue();
            }
            assertThat(payloads).hasSize(2);
        }
    }

    @McpClientTest
    void nonAdminCannotSeeOrCallEitherTool(JenkinsRule jenkins, JenkinsMcpClientBuilder builder) throws Exception {
        secure(jenkins);
        try (var client = clientAs(jenkins, builder, "reader")) {
            for (String name : List.of("getH5Config", "createH5Config")) {
                assertThat(TestUtils.findToolByName(client.listTools(), name)).isNull();
                assertThat(client.callTool(new McpSchema.CallToolRequest(
                                        name, Map.of("projectName", "frontend-demo", "config", CONFIG)))
                                .isError())
                        .isTrue();
            }
            assertThat(TestUtils.findToolByName(client.listTools(), "whoAmI")).isNotNull();
        }
        assertThat(paths).isEmpty();
    }

    @Test
    @WithJenkins
    void statelessHonorsPermissionsAndAuditIdentity(JenkinsRule jenkins) throws Exception {
        secure(jenkins);
        try (var admin = new StatelessMcpTestClient(jenkins, basicAuth("admin"))) {
            assertThat(TestUtils.findToolByName(admin.listTools(), "getH5Config"))
                    .isNotNull();
            assertThat(TestUtils.findToolByName(admin.listTools(), "createH5Config"))
                    .isNotNull();
            assertThat(admin.callTool("getH5Config", Map.of("projectName", "frontend-demo"))
                            .isError())
                    .isFalse();
            var created = admin.callTool(
                    "createH5Config", Map.of("projectName", "frontend-demo", "config", CONFIG, "dryRun", false));
            assertThat(created.isError()).isFalse();
            assertThat(result(created).path("dry_run").booleanValue()).isFalse();
        }
        for (String user : List.of("reader", "")) {
            try (var denied = new StatelessMcpTestClient(jenkins, user.isEmpty() ? null : basicAuth(user))) {
                for (String name : List.of("getH5Config", "createH5Config")) {
                    assertThat(TestUtils.findToolByName(denied.listTools(), name))
                            .isNull();
                    assertThat(denied.callTool(name, Map.of("projectName", "frontend-demo", "config", CONFIG))
                                    .isError())
                            .isTrue();
                }
            }
        }
        assertThat(paths).hasSize(2);
        assertThat(operators).containsExactly("admin");
    }

    @Test
    @WithJenkins
    void directCallsCheckPermissionsBeforeInputAndServiceConfiguration(JenkinsRule jenkins) throws Exception {
        secure(jenkins);
        System.clearProperty(H5ConfigExtension.BASE_URL_PROPERTY);
        var extension = ExtensionList.lookupSingleton(H5ConfigExtension.class);
        for (var identity : List.of(User.getById("reader", true).impersonate2(), Jenkins.ANONYMOUS2)) {
            try (var ignored = ACL.as2(identity)) {
                assertThatThrownBy(() -> extension.getH5Config(null, null)).isInstanceOf(AccessDeniedException.class);
                assertThatThrownBy(() -> extension.createH5Config(null, null, null, null, null))
                        .isInstanceOf(AccessDeniedException.class);
            }
        }
        try (var ignored = ACL.as2(User.getById("admin", true).impersonate2())) {
            assertThatThrownBy(() -> extension.getH5Config("frontend-demo", null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("not configured");
            assertThatThrownBy(() -> extension.createH5Config("frontend-demo", config(), null, false, null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("not configured");
        }
        assertThat(paths).isEmpty();
    }

    @Test
    @WithJenkins
    void validatesKeyAndRequiredInputsAndUsesLocaleIndependentLowercase(JenkinsRule jenkins) throws Exception {
        var extension = ExtensionList.lookupSingleton(H5ConfigExtension.class);
        for (String name : List.of("", " ", "folder/job", "job:module", "../job", "job?key", "x".repeat(192))) {
            assertThatThrownBy(() -> extension.getH5Config(name, null)).isInstanceOf(IllegalArgumentException.class);
        }
        for (String module : List.of("", " ", "../module", "module:other", "x".repeat(191))) {
            assertThatThrownBy(() -> extension.getH5Config("project", module))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> extension.createH5Config("frontend-demo", null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> extension.createH5Config("frontend-demo", config(), null, null, "x".repeat(513)))
                .isInstanceOf(IllegalArgumentException.class);
        var blank = MAPPER.convertValue(Map.of("target_dir", " ", "archive_name", "html.tar.gz"), H5AppConfig.class);
        assertThatThrownBy(() -> extension.createH5Config("frontend-demo", blank, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(paths).isEmpty();
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertThat(extension.getH5Config("FRONTEND-I", "WEB")).containsEntry("app_key", "frontend-i:web");
        } finally {
            Locale.setDefault(previous);
        }
        assertThat(extension.createH5Config("frontend-demo", config(), null, null, null))
                .containsEntry("dry_run", true);
    }

    private static JsonNode result(McpSchema.CallToolResult result) {
        assertThat(result.structuredContent()).isNotNull();
        return MAPPER.valueToTree(result.structuredContent());
    }

    private static H5AppConfig config() {
        return MAPPER.convertValue(CONFIG, H5AppConfig.class);
    }

    private static String basicAuth(String user) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + user).getBytes(StandardCharsets.UTF_8));
    }

    private static McpSyncClient clientAs(JenkinsRule jenkins, JenkinsMcpClientBuilder builder, String user) {
        return builder.jenkins(jenkins)
                .requestCustomizer((request, method, endpoint, body, context) ->
                        request.setHeader("Authorization", basicAuth(user)))
                .build();
    }

    private static void secure(JenkinsRule jenkins) throws Exception {
        jenkins.jenkins.setSecurityRealm(jenkins.createDummySecurityRealm());
        jenkins.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER)
                .everywhere()
                .to("admin")
                .grant(Jenkins.READ)
                .everywhere()
                .to("reader", "anonymous"));
        jenkins.jenkins.save();
    }
}
