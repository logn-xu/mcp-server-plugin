/*
 *
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
 *
 */

package io.jenkins.plugins.mcp.server.extensions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import hudson.ExtensionList;
import hudson.model.Item;
import hudson.model.ListView;
import hudson.model.User;
import hudson.model.View;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.mcp.server.junit.JenkinsMcpClientBuilder;
import io.jenkins.plugins.mcp.server.junit.McpClientTest;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import jenkins.model.Jenkins;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.springframework.security.access.AccessDeniedException;

@WithJenkins
class ViewExtensionTest {

    private static final String MANAGER = "view-manager";

    @Test
    void createsAndEditsRootListViewAndPersistsChanges(JenkinsRule rule) throws Exception {
        var tools = extension();
        secure(rule, MANAGER, View.CREATE, View.CONFIGURE, View.READ, Item.READ);
        try (var ignored = asUser(MANAGER)) {
            var created = tools.createView("team", "First description", "web-.*");
            assertThat(created.name()).isEqualTo("team");
            assertThat(created.includeRegex()).isEqualTo("web-.*");
            assertThat(created.description()).isEqualTo("First description");
            assertThatThrownBy(() -> tools.createView("team", null, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("already exists");
            assertThatThrownBy(() -> tools.createView("bad/name", null, null)).isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> tools.createView("invalid", null, "["))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(rule.jenkins.getView("invalid")).isNull();
            rule.jenkins.setPrimaryView(rule.jenkins.getView("team"));
            assertThat(tools.renameView("team", "renamed").name()).isEqualTo("renamed");
            assertThat(rule.jenkins.getPrimaryView().getViewName()).isEqualTo("renamed");
            assertThatThrownBy(() -> tools.renameView("renamed", "bad/name")).isInstanceOf(Exception.class);
            assertThatThrownBy(() -> tools.renameView("renamed", "all")).isInstanceOf(Exception.class);
            assertThat(tools.setViewDescription("renamed", "").description()).isNull();
            assertThat(tools.setViewIncludeRegex("renamed", "").includeRegex()).isNull();
        }
        rule.jenkins.reload();
        var reloaded = (ListView) rule.jenkins.getView("renamed");
        assertThat(reloaded).isNotNull();
        assertThat(reloaded.getDescription()).isNull();
        assertThat(reloaded.getIncludeRegex()).isNull();
        assertThat(rule.jenkins.getView("team")).isNull();
        assertThat(rule.jenkins.getPrimaryView().getViewName()).isEqualTo("renamed");
    }

    @ParameterizedTest
    @ValueSource(strings = {"both", "create-only", "configure-only", "neither"})
    void creationRequiresBothPermissionsBeforeInputValidation(String scenario, JenkinsRule rule) throws Exception {
        rule.jenkins.setSecurityRealm(rule.createDummySecurityRealm());
        var strategy = new MockAuthorizationStrategy()
                .grant(Jenkins.READ, View.READ)
                .everywhere()
                .to(MANAGER);
        switch (scenario) {
            case "both" ->
                strategy.grant(View.CREATE, View.CONFIGURE).everywhere().to(MANAGER);
            case "create-only" -> strategy.grant(View.CREATE).everywhere().to(MANAGER);
            case "configure-only" -> strategy.grant(View.CONFIGURE).everywhere().to(MANAGER);
            default -> {}
        }
        rule.jenkins.setAuthorizationStrategy(strategy);
        try (var ignored = asUser(MANAGER)) {
            if ("both".equals(scenario)) {
                assertThat(extension().createView("authorized", null, null).name())
                        .isEqualTo("authorized");
            } else {
                assertThatThrownBy(() -> extension().createView("", null, null))
                        .isInstanceOf(AccessDeniedException.class);
                assertThatThrownBy(() -> extension().createView("unauthorized", null, null))
                        .isInstanceOf(AccessDeniedException.class);
            }
        }
        assertThat(rule.jenkins.getView("unauthorized")).isNull();
        try (var anonymous = ACL.as2(Jenkins.ANONYMOUS2)) {
            assertThatThrownBy(() -> extension().createView("anonymous-view", null, null))
                    .isInstanceOf(AccessDeniedException.class);
        }
        assertThat(rule.jenkins.getView("anonymous-view")).isNull();
    }

    @Test
    void creationFailsClosedWithoutSecurity(JenkinsRule rule) {
        assertThatThrownBy(() -> extension().createView("unprotected", null, null))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(rule.jenkins.getView("unprotected")).isNull();
    }

    @Test
    void unreadableExistingNameIsNotDuplicated(JenkinsRule rule) throws Exception {
        rule.jenkins.addView(new ListView("hidden"));
        rule.jenkins.addView(new ListView("editable"));
        rule.jenkins.setSecurityRealm(rule.createDummySecurityRealm());
        rule.jenkins.setAuthorizationStrategy(
                new MockAuthorizationStrategy() {
                    @Override
                    public ACL getACL(View view) {
                        ACL root = super.getACL(view);
                        return ACL.lambda2((authentication, permission) ->
                                !view.getViewName().equals("hidden") || permission != View.READ
                                        ? root.hasPermission2(authentication, permission)
                                        : authentication.equals(ACL.SYSTEM2));
                    }
                }.grant(Jenkins.READ, View.READ, View.CREATE, View.CONFIGURE)
                        .everywhere()
                        .to(MANAGER));
        try (var ignored = asUser(MANAGER)) {
            assertThat(extension().getViews())
                    .extracting(ViewExtension.ViewSummary::name)
                    .doesNotContain("hidden");
            assertThatThrownBy(() -> extension().getView("hidden"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("does not exist");
            assertThatThrownBy(() -> extension().createView("hidden", null, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("already exists");
            assertThatThrownBy(() -> extension().renameView("editable", "hidden"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("already exists");
        }
        assertThat(rule.jenkins.getViews().stream()
                        .filter(view -> "hidden".equals(view.getViewName()))
                        .count())
                .isEqualTo(1);
    }

    @Test
    void jobsAndRegexAreIndependentAndInvalidBatchDoesNotModifyView(JenkinsRule rule) throws Exception {
        rule.createFreeStyleProject("web-api");
        rule.createFreeStyleProject("web-ui");
        rule.createFreeStyleProject("other");
        rule.createFolder("folder").createProject(hudson.model.FreeStyleProject.class, "nested");
        var tools = extension();
        secure(rule, MANAGER, View.CREATE, View.CONFIGURE, View.READ, Item.READ);
        try (var ignored = asUser(MANAGER)) {
            tools.createView("team", null, "web-.*");
            assertThat(tools.getView("team").visibleJobs()).containsExactly("web-api", "web-ui");
            assertThat(tools.getView("team").explicitJobs()).isEmpty();
            assertThat(tools.updateViewJobs("team", List.of("web-api", "other"), null)
                            .added())
                    .containsExactly("other", "web-api");
            assertThat(tools.updateViewJobs("team", null, List.of("web-api", "other", "web-ui")))
                    .satisfies(result -> {
                        assertThat(result.removed()).containsExactly("other", "web-api");
                        assertThat(result.notInView()).containsExactly("web-ui");
                        assertThat(result.stillVisible()).containsExactly("web-api");
                    });
            assertThatThrownBy(() -> tools.updateViewJobs("team", List.of("other"), List.of("folder/nested")))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> tools.updateViewJobs("team", List.of("other"), List.of("other")))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> tools.updateViewJobs("team", List.of("missing"), null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(tools.getView("team").explicitJobs()).isEmpty();
            try (var system = ACL.as2(ACL.SYSTEM2)) {
                rule.createFreeStyleProject("web-new");
            }
            assertThat(tools.getView("team").visibleJobs()).contains("web-new");
            assertThatThrownBy(() -> tools.setViewIncludeRegex("team", "["))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(tools.getView("team").includeRegex()).isEqualTo("web-.*");
            assertThatThrownBy(() -> tools.setViewIncludeRegex("team", "a".repeat(1001)))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(tools.setViewIncludeRegex("team", "").visibleJobs()).isEmpty();
            tools.updateViewJobs("team", List.of("other"), null);
            assertThat(tools.getView("team").visibleJobs()).containsExactly("other");
            assertThatThrownBy(() ->
                            tools.updateViewJobs(rule.jenkins.getPrimaryView().getViewName(), List.of("other"), null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("not a List View");
        }
        rule.jenkins.reload();
        var saved = (ListView) rule.jenkins.getView("team");
        assertThat(saved.getJobNames()).containsExactly("other");
        assertThat(saved.getIncludeRegex()).isNull();
    }

    @Test
    void readAndWritePermissionsAreCheckedOnViewsAndJobs(JenkinsRule rule) throws Exception {
        rule.createFreeStyleProject("secret");
        var view = new ListView("private");
        rule.jenkins.addView(view);
        view.add(rule.jenkins.getItem("secret"));
        rule.jenkins.setSecurityRealm(rule.createDummySecurityRealm());
        rule.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.READ, View.READ)
                .everywhere()
                .to("reader")
                .grant(Jenkins.READ, View.READ, View.CONFIGURE)
                .everywhere()
                .to("editor"));
        try (var ignored = asUser("reader")) {
            assertThat(extension().getView("private").explicitJobs()).isEmpty();
            assertThat(extension().getView("private").visibleJobs()).isEmpty();
            assertThatThrownBy(() -> extension().setViewDescription("private", "no"))
                    .isInstanceOf(AccessDeniedException.class);
        }
        try (var ignored = asUser("editor")) {
            assertThatThrownBy(() -> extension().updateViewJobs("private", List.of("secret"), null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("does not exist or is not readable");
            assertThat(extension().setViewDescription("private", "visible").description())
                    .isEqualTo("visible");
        }
    }

    @McpClientTest
    void mcpToolsListAndCallRespectViewPermissions(JenkinsRule rule, JenkinsMcpClientBuilder builder) throws Exception {
        rule.createFreeStyleProject("web-api");
        rule.jenkins.setSecurityRealm(rule.createDummySecurityRealm());
        rule.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.READ, View.READ)
                .everywhere()
                .to("reader")
                .grant(Jenkins.READ, View.READ, View.CONFIGURE)
                .everywhere()
                .to("editor")
                .grant(Jenkins.READ, View.READ, View.CONFIGURE, View.CREATE, Item.READ)
                .everywhere()
                .to(MANAGER));
        try (var reader = client(rule, builder, "reader")) {
            assertThat(reader.listTools().tools())
                    .extracting(McpSchema.Tool::name)
                    .contains("getView", "getViews")
                    .doesNotContain("createView", "renameView", "updateViewJobs");
            var denied = reader.callTool(new McpSchema.CallToolRequest("createView", Map.of("name", "blocked")));
            assertThat(denied.isError()).isTrue();
            assertThat(rule.jenkins.getView("blocked")).isNull();
        }
        try (var editor = client(rule, builder, "editor")) {
            assertThat(editor.listTools().tools())
                    .extracting(McpSchema.Tool::name)
                    .contains("createView");
            var denied = editor.callTool(new McpSchema.CallToolRequest("createView", Map.of("name", "blocked")));
            assertThat(denied.isError()).isTrue();
        }
        try (var manager = client(rule, builder, MANAGER)) {
            var created = manager.callTool(new McpSchema.CallToolRequest(
                    "createView", Map.of("name", "via-mcp", "description", "mcp", "includeRegex", "web-.*")));
            assertThat(created.isError()).isFalse();
            assertThat(((McpSchema.TextContent) created.content().get(0)).text())
                    .contains("via-mcp", "web-.*");
            assertThat(rule.jenkins.getView("via-mcp")).isNotNull();
            var listed = manager.callTool(new McpSchema.CallToolRequest("getViews", Map.of()));
            assertThat(listed.isError()).isFalse();
            assertThat(((McpSchema.TextContent) listed.content().get(0)).text()).contains("via-mcp");
            var detail = manager.callTool(new McpSchema.CallToolRequest("getView", Map.of("viewName", "via-mcp")));
            assertThat(detail.isError()).isFalse();
            assertThat(((McpSchema.TextContent) detail.content().get(0)).text()).contains("web-api", "visibleJobCount");
            var changed = manager.callTool(new McpSchema.CallToolRequest(
                    "updateViewJobs", Map.of("viewName", "via-mcp", "addJobs", List.of("web-api"))));
            assertThat(changed.isError()).isFalse();
            assertThat(((McpSchema.TextContent) changed.content().get(0)).text())
                    .contains("web-api");
        }
    }

    private static ViewExtension extension() {
        return ExtensionList.lookupSingleton(ViewExtension.class);
    }

    private static void secure(JenkinsRule rule, String user, hudson.security.Permission... permissions) {
        rule.jenkins.setSecurityRealm(rule.createDummySecurityRealm());
        rule.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.READ)
                .everywhere()
                .to(user)
                .grant(permissions)
                .everywhere()
                .to(user));
    }

    private static ACLContext asUser(String user) {
        return ACL.as2(User.getById(user, true).impersonate2());
    }

    private static io.modelcontextprotocol.client.McpSyncClient client(
            JenkinsRule rule, JenkinsMcpClientBuilder builder, String user) {
        return builder.jenkins(rule)
                .requestCustomizer((request, method, endpoint, body, context) -> request.setHeader(
                        "Authorization", "Basic " + Base64.getEncoder().encodeToString((user + ":" + user).getBytes())))
                .build();
    }
}
