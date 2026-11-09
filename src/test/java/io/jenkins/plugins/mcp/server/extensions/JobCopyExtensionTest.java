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
import hudson.model.BooleanParameterDefinition;
import hudson.model.ChoiceParameterDefinition;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.ParameterDefinition;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.PasswordParameterDefinition;
import hudson.model.StringParameterDefinition;
import hudson.model.User;
import hudson.plugins.git.GitSCM;
import hudson.security.ACL;
import hudson.tasks.LogRotator;
import io.jenkins.plugins.mcp.server.junit.JenkinsMcpClientBuilder;
import io.jenkins.plugins.mcp.server.junit.McpClientTest;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import jenkins.model.BuildDiscarderProperty;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.workflow.cps.CpsScmFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.springframework.security.access.AccessDeniedException;

@WithJenkins
class JobCopyExtensionTest {

    private static final String CREATOR = "creator";
    private static final String TEMPLATE = "pipeline-template";
    private static final String SCM_URL = "https://example.com/pipelines.git";

    @McpClientTest
    void copiesScmPipelineTemplateAndUpdatesParameters(
            JenkinsRule jenkins, JenkinsMcpClientBuilder jenkinsMcpClientBuilder) throws Exception {
        var template = createTemplate(jenkins);
        secure(jenkins);
        try (var client = clientAsCreator(jenkins, jenkinsMcpClientBuilder)) {
            Map<String, Object> defaults = new LinkedHashMap<>();
            defaults.put("Code_URL", "https://example.com/app.git");
            defaults.put("CODE_PATH", "app-auth");
            defaults.put("APP_PORT", 9200);
            defaults.put("DOCKER_DEPLOY", false);
            defaults.put("JDK_VERSION", "jdk21");
            var response = client.callTool(new McpSchema.CallToolRequest(
                    "copyJob",
                    Map.of(
                            "templateJobFullName",
                            TEMPLATE,
                            "jobName",
                            "app-auth",
                            "parameterDefaults",
                            defaults,
                            "addStringParameters",
                            Map.of("PACKAGE_NAME", "app-auth"),
                            "description",
                            "auth module")));

            assertThat(response.isError()).isFalse();
            assertThat(((McpSchema.TextContent) response.content().get(0)).text())
                    .contains("\"fullName\":\"app-auth\"")
                    .contains("\"template\":\"" + TEMPLATE + "\"");

            var job = jenkins.jenkins.getItemByFullName("app-auth", WorkflowJob.class);
            assertThat(job).isNotNull();
            job.doReload();
            assertCopiedJob(job);

            // The template itself stays untouched.
            assertThat(template.getDescription()).isEqualTo("template");
            assertThat(stringDefault(template, "CODE_PATH")).isEqualTo(".");
            assertThat(template.getProperty(ParametersDefinitionProperty.class).getParameterDefinitionNames())
                    .doesNotContain("PACKAGE_NAME");

            var again = client.callTool(new McpSchema.CallToolRequest(
                    "copyJob", Map.of("templateJobFullName", TEMPLATE, "jobName", "app-auth")));
            assertThat(again.isError()).isTrue();
            assertThat(((McpSchema.TextContent) again.content().get(0)).text()).contains("already exists");
        }
    }

    @Test
    void invalidInputNeverCreatesJob(JenkinsRule jenkins) throws Exception {
        var template = createTemplate(jenkins);
        template.removeProperty(ParametersDefinitionProperty.class);
        template.addProperty(new ParametersDefinitionProperty(
                new StringParameterDefinition("NAME", "x"),
                new BooleanParameterDefinition("FLAG", true, null),
                new ChoiceParameterDefinition("ENV", new String[] {"sit", "uat"}, null),
                new PasswordParameterDefinition("SECRET", "s3cr3t", null)));
        jenkins.createFreeStyleProject("existing");
        var extension = ExtensionList.lookupSingleton(JobCopyExtension.class);

        assertInvalid(extension, TEMPLATE, "copy", Map.of("MISSING", "v"), null, "does not exist in template");
        assertInvalid(extension, TEMPLATE, "copy", Map.of("ENV", "prod"), null, "not a valid choice");
        assertInvalid(extension, TEMPLATE, "copy", Map.of("FLAG", "maybe"), null, "must be true or false");
        assertInvalid(extension, TEMPLATE, "copy", Map.of("NAME", Map.of("a", "b")), null, "must be a string");
        assertInvalid(extension, TEMPLATE, "copy", Map.of("SECRET", "other"), null, "is not supported");
        assertInvalid(extension, TEMPLATE, "copy", null, Map.of("NAME", "dup"), "already exists in template job");
        assertInvalid(extension, "no-such-template", "copy", null, null, "does not exist");
        assertInvalid(extension, TEMPLATE, "no-such-folder/copy", null, null, "does not exist or cannot contain");
        assertInvalid(extension, TEMPLATE, "existing", null, null, "already exists");

        assertThat(jenkins.jenkins.getItem("copy")).isNull();
        assertThat(jenkins.jenkins.getItem("existing")).isInstanceOf(FreeStyleProject.class);
    }

    @Test
    void copiesFreestyleJobIntoFolder(JenkinsRule jenkins) throws Exception {
        var template = jenkins.createFreeStyleProject("freestyle-template");
        template.addProperty(new ParametersDefinitionProperty(
                new ChoiceParameterDefinition("ENV", new String[] {"sit", "uat", "prod"}, null)));
        jenkins.createFolder("team");
        var extension = ExtensionList.lookupSingleton(JobCopyExtension.class);

        var result = extension.copyJob("freestyle-template", "team/copy", Map.of("ENV", "uat"), null, null);

        assertThat(result.fullName()).isEqualTo("team/copy");
        assertThat(result.updatedParameters()).containsEntry("ENV", "uat");
        var copy = jenkins.jenkins.getItemByFullName("team/copy", FreeStyleProject.class);
        assertThat(copy).isNotNull();
        var env = (ChoiceParameterDefinition) parameter(copy, "ENV");
        assertThat(env.getChoices()).containsExactly("uat", "sit", "prod");
        assertThat(((ChoiceParameterDefinition) parameter(template, "ENV")).getChoices())
                .containsExactly("sit", "uat", "prod");
    }

    @ParameterizedTest
    @ValueSource(strings = {"no-create", "no-extended-read", "no-configure", "allowed"})
    void permissions(String scenario, JenkinsRule jenkins) throws Exception {
        createTemplate(jenkins);
        jenkins.jenkins.setSecurityRealm(jenkins.createDummySecurityRealm());
        var authorization = new MockAuthorizationStrategy()
                .grant(Jenkins.READ, Item.READ)
                .everywhere()
                .to(CREATOR);
        switch (scenario) {
            case "no-create" -> authorization.grant(Item.CONFIGURE).everywhere().to(CREATOR);
            case "no-extended-read" ->
                authorization.grant(Item.CREATE).everywhere().to(CREATOR);
            case "no-configure" ->
                authorization
                        .grant(Item.CREATE, Item.EXTENDED_READ)
                        .everywhere()
                        .to(CREATOR);
            default ->
                authorization.grant(Item.CREATE, Item.CONFIGURE).everywhere().to(CREATOR);
        }
        jenkins.jenkins.setAuthorizationStrategy(authorization);
        var extension = ExtensionList.lookupSingleton(JobCopyExtension.class);

        try (var ignored = ACL.as2(User.getById(CREATOR, true).impersonate2())) {
            if ("allowed".equals(scenario)) {
                extension.copyJob(TEMPLATE, "copy", Map.of("CODE_PATH", "x"), null, null);
            } else {
                var denied = assertThatThrownBy(
                                () -> extension.copyJob(TEMPLATE, "copy", Map.of("CODE_PATH", "x"), null, null))
                        .isInstanceOf(AccessDeniedException.class);
                if ("no-configure".equals(scenario)) {
                    // Copy succeeded, so this exercises the rollback path.
                    denied.hasMessageContaining("on the new job");
                }
            }
        }

        var copy = jenkins.jenkins.getItem("copy");
        if ("allowed".equals(scenario)) {
            assertThat(copy).isInstanceOf(WorkflowJob.class);
            assertThat(stringDefault((WorkflowJob) copy, "CODE_PATH")).isEqualTo("x");
        } else {
            assertThat(copy).isNull();
        }
    }

    private static void assertCopiedJob(WorkflowJob job) {
        assertThat(job.getDescription()).isEqualTo("auth module");
        assertThat(job.getProperty(BuildDiscarderProperty.class)).isNotNull();
        assertThat(job.getDefinition()).isInstanceOfSatisfying(CpsScmFlowDefinition.class, definition -> {
            assertThat(definition.getScriptPath()).isEqualTo("pipelines/java.jenkinsfile");
            assertThat(((GitSCM) definition.getScm())
                            .getUserRemoteConfigs()
                            .get(0)
                            .getUrl())
                    .isEqualTo(SCM_URL);
        });
        assertThat(job.getProperty(ParametersDefinitionProperty.class).getParameterDefinitionNames())
                .containsExactly(
                        "Code_URL",
                        "ENV_LABEL",
                        "CODE_PATH",
                        "APP_PORT",
                        "DOCKER_DEPLOY",
                        "JDK_VERSION",
                        "PACKAGE_NAME");
        assertThat(stringDefault(job, "Code_URL")).isEqualTo("https://example.com/app.git");
        assertThat(stringDefault(job, "CODE_PATH")).isEqualTo("app-auth");
        assertThat(((StringParameterDefinition) parameter(job, "CODE_PATH")).isTrim())
                .isTrue();
        assertThat(((StringParameterDefinition) parameter(job, "CODE_PATH")).getDescription())
                .isEqualTo("build path");
        assertThat(stringDefault(job, "APP_PORT")).isEqualTo("9200");
        assertThat(((BooleanParameterDefinition) parameter(job, "DOCKER_DEPLOY")).isDefaultValue())
                .isFalse();
        assertThat(((ChoiceParameterDefinition) parameter(job, "JDK_VERSION")).getChoices())
                .containsExactly("jdk21", "jdk8");
        assertThat(((ChoiceParameterDefinition) parameter(job, "ENV_LABEL")).getChoices())
                .containsExactly("sit", "uat");
        assertThat(stringDefault(job, "PACKAGE_NAME")).isEqualTo("app-auth");
    }

    private static void assertInvalid(
            JobCopyExtension extension,
            String template,
            String jobName,
            Map<String, Object> defaults,
            Map<String, Object> additions,
            String message) {
        assertThatThrownBy(() -> extension.copyJob(template, jobName, defaults, additions, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(message);
    }

    private static WorkflowJob createTemplate(JenkinsRule jenkins) throws Exception {
        var template = jenkins.createProject(WorkflowJob.class, TEMPLATE);
        template.setDescription("template");
        template.setDefinition(new CpsScmFlowDefinition(new GitSCM(SCM_URL), "pipelines/java.jenkinsfile"));
        template.addProperty(new BuildDiscarderProperty(new LogRotator(-1, 10, -1, -1)));
        var codePath = new StringParameterDefinition("CODE_PATH", ".", "build path");
        codePath.setTrim(true);
        template.addProperty(new ParametersDefinitionProperty(
                new StringParameterDefinition("Code_URL", "https://example.com/xx.git"),
                new ChoiceParameterDefinition("ENV_LABEL", new String[] {"sit", "uat"}, null),
                codePath,
                new StringParameterDefinition("APP_PORT", "8080"),
                new BooleanParameterDefinition("DOCKER_DEPLOY", true, null),
                new ChoiceParameterDefinition("JDK_VERSION", new String[] {"jdk8", "jdk21"}, null)));
        return template;
    }

    private static ParameterDefinition parameter(hudson.model.Job<?, ?> job, String name) {
        return job.getProperty(ParametersDefinitionProperty.class).getParameterDefinition(name);
    }

    private static String stringDefault(hudson.model.Job<?, ?> job, String name) {
        return ((StringParameterDefinition) parameter(job, name)).getDefaultValue();
    }

    /** Lets {@link #CREATOR} read, create and configure jobs anywhere. */
    private static void secure(JenkinsRule jenkins) throws Exception {
        jenkins.jenkins.setSecurityRealm(jenkins.createDummySecurityRealm());
        jenkins.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.READ, Item.READ, Item.CREATE, Item.CONFIGURE)
                .everywhere()
                .to(CREATOR));
        jenkins.jenkins.save();
    }

    private static McpSyncClient clientAsCreator(JenkinsRule jenkins, JenkinsMcpClientBuilder builder) {
        return builder.jenkins(jenkins)
                .requestCustomizer((httpRequestBuilder, method, endpoint, body, context) ->
                        httpRequestBuilder.setHeader("Authorization", basicAuth(CREATOR)))
                .build();
    }

    private static String basicAuth(String user) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + user).getBytes());
    }
}
