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
import hudson.model.Item;
import hudson.model.Job;
import hudson.model.ParameterDefinition;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.PasswordParameterDefinition;
import hudson.model.StringParameterDefinition;
import hudson.model.User;
import hudson.security.ACL;
import io.jenkins.plugins.mcp.server.junit.JenkinsMcpClientBuilder;
import io.jenkins.plugins.mcp.server.junit.McpClientTest;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.springframework.security.access.AccessDeniedException;

@WithJenkins
class JobParametersExtensionTest {

    private static final String EDITOR = "editor";
    private static final String VIEWER = "viewer";
    private static final String OUTSIDER = "outsider";
    private static final String JOB = "service-pipeline";

    @McpClientTest
    void updatesStringChoiceAndBooleanDefaults(JenkinsRule jenkins, JenkinsMcpClientBuilder jenkinsMcpClientBuilder)
            throws Exception {
        var job = createJob(jenkins, JOB);
        secure(jenkins);
        try (var client = client(jenkins, jenkinsMcpClientBuilder, EDITOR)) {
            Map<String, Object> defaults = new LinkedHashMap<>();
            defaults.put("CODE_PATH", "auth");
            defaults.put("APP_PORT", 9200);
            defaults.put("DOCKER_DEPLOY", "false");
            defaults.put("ENV_LABEL", "uat");
            var response = client.callTool(new McpSchema.CallToolRequest(
                    "updateJobParameters", Map.of("jobFullName", JOB, "parameterDefaults", defaults)));

            assertThat(response.isError()).isFalse();
            assertThat(((McpSchema.TextContent) response.content().get(0)).text())
                    .contains("\"fullName\":\"" + JOB + "\"")
                    .contains("\"CODE_PATH\":{\"previous\":\".\",\"current\":\"auth\"}")
                    .contains("\"DOCKER_DEPLOY\":{\"previous\":true,\"current\":false}")
                    .contains("\"ENV_LABEL\":{\"previous\":\"sit\",\"current\":\"uat\"}");
        }

        job.doReload();
        assertThat(job.getProperty(ParametersDefinitionProperty.class).getParameterDefinitionNames())
                .containsExactly("CODE_PATH", "APP_PORT", "DOCKER_DEPLOY", "ENV_LABEL", "MVN_ARGS");
        var codePath = (StringParameterDefinition) parameter(job, "CODE_PATH");
        assertThat(codePath.getDefaultValue()).isEqualTo("auth");
        assertThat(codePath.getDescription()).isEqualTo("build path");
        assertThat(codePath.isTrim()).isTrue();
        assertThat(((StringParameterDefinition) parameter(job, "APP_PORT")).getDefaultValue())
                .isEqualTo("9200");
        assertThat(((BooleanParameterDefinition) parameter(job, "DOCKER_DEPLOY")).isDefaultValue())
                .isFalse();
        assertThat(((ChoiceParameterDefinition) parameter(job, "ENV_LABEL")).getChoices())
                .containsExactly("uat", "sit", "prod");
        assertThat(((StringParameterDefinition) parameter(job, "MVN_ARGS")).getDefaultValue())
                .isEqualTo("-DskipTests");
        assertThat(((CpsFlowDefinition) job.getDefinition()).getScript()).isEqualTo("echo 'hi'");
    }

    @McpClientTest
    void userWithoutConfigurePermissionIsToldSo(JenkinsRule jenkins, JenkinsMcpClientBuilder jenkinsMcpClientBuilder)
            throws Exception {
        var job = createJob(jenkins, JOB);
        secure(jenkins);
        try (var client = client(jenkins, jenkinsMcpClientBuilder, VIEWER)) {
            var response = client.callTool(new McpSchema.CallToolRequest(
                    "updateJobParameters",
                    Map.of("jobFullName", JOB, "parameterDefaults", Map.of("CODE_PATH", "auth"))));

            assertThat(response.isError()).isTrue();
            assertThat(((McpSchema.TextContent) response.content().get(0)).text())
                    .contains("has no permission to modify job '" + JOB + "'")
                    .contains("Job/Configure");
        }
        assertThat(((StringParameterDefinition) parameter(job, "CODE_PATH")).getDefaultValue())
                .isEqualTo(".");
    }

    @Test
    void permissionIsCheckedBeforeInputValidation(JenkinsRule jenkins) throws Exception {
        createJob(jenkins, JOB);
        secure(jenkins);
        var extension = ExtensionList.lookupSingleton(JobParametersExtension.class);

        try (var ignored = ACL.as2(User.getById(VIEWER, true).impersonate2())) {
            assertThatThrownBy(() -> extension.updateJobParameters(JOB, Map.of("NO_SUCH", "x"), null))
                    .isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> extension.updateJobParameters(JOB, null, Map.of("PACKAGE_NAME", "x")))
                    .isInstanceOf(AccessDeniedException.class);
        }
        try (var ignored = ACL.as2(User.getById(OUTSIDER, true).impersonate2())) {
            assertThatThrownBy(() -> extension.updateJobParameters(JOB, Map.of("CODE_PATH", "x"), null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("does not exist");
        }
    }

    @Test
    void invalidInputLeavesJobUnchanged(JenkinsRule jenkins) throws Exception {
        var job = createJob(jenkins, JOB);
        var withSecret = jenkins.createProject(WorkflowJob.class, "with-secret");
        withSecret.addProperty(
                new ParametersDefinitionProperty(new PasswordParameterDefinition("TOKEN", "secret", null)));
        jenkins.createProject(WorkflowJob.class, "no-params");
        var extension = ExtensionList.lookupSingleton(JobParametersExtension.class);

        assertInvalid(extension, JOB, Map.of("NO_SUCH", "x"), null, "does not exist in job");
        assertInvalid(extension, JOB, Map.of("ENV_LABEL", "qa"), null, "not a valid choice");
        assertInvalid(extension, JOB, Map.of("DOCKER_DEPLOY", "yes"), null, "must be true or false");
        assertInvalid(extension, JOB, Map.of("CODE_PATH", "ok", "ENV_LABEL", "qa"), null, "not a valid choice");
        assertInvalid(extension, JOB, Map.of(), null, "at least one parameter");
        assertInvalid(extension, JOB, null, Map.of(), "at least one parameter");
        assertInvalid(extension, JOB, null, Map.of("CODE_PATH", "x"), "already exists in job");
        assertInvalid(extension, JOB, null, Map.of(" ", "x"), "must not be empty");
        assertInvalid(extension, JOB, null, Map.of("PACKAGE_NAME", Map.of("a", "b")), "must be a string");
        // A valid addition must not be applied when an update in the same call is invalid.
        assertInvalid(extension, JOB, Map.of("ENV_LABEL", "qa"), Map.of("PACKAGE_NAME", "x"), "not a valid choice");
        assertInvalid(extension, "with-secret", Map.of("TOKEN", "x"), null, "is not supported");
        assertInvalid(extension, "no-params", Map.of("A", "x"), null, "has no parameters");
        assertInvalid(extension, "missing", Map.of("A", "x"), null, "does not exist");

        job.doReload();
        assertThat(((StringParameterDefinition) parameter(job, "CODE_PATH")).getDefaultValue())
                .isEqualTo(".");
        assertThat(((ChoiceParameterDefinition) parameter(job, "ENV_LABEL")).getChoices())
                .containsExactly("sit", "uat", "prod");
        assertThat(job.getProperty(ParametersDefinitionProperty.class).getParameterDefinitionNames())
                .doesNotContain("PACKAGE_NAME");
    }

    @McpClientTest
    void addsStringParameterAndUpdatesDefaultsInOneCall(
            JenkinsRule jenkins, JenkinsMcpClientBuilder jenkinsMcpClientBuilder) throws Exception {
        var job = createJob(jenkins, JOB);
        secure(jenkins);
        try (var client = client(jenkins, jenkinsMcpClientBuilder, EDITOR)) {
            var response = client.callTool(new McpSchema.CallToolRequest(
                    "updateJobParameters",
                    Map.of(
                            "jobFullName", JOB,
                            "parameterDefaults", Map.of("CODE_PATH", "auth"),
                            "addStringParameters", Map.of("PACKAGE_NAME", "ruoyi-auth"))));

            assertThat(response.isError()).isFalse();
            assertThat(((McpSchema.TextContent) response.content().get(0)).text())
                    .contains("\"addedParameters\":{\"PACKAGE_NAME\":\"ruoyi-auth\"}")
                    .contains("\"CODE_PATH\":{\"previous\":\".\",\"current\":\"auth\"}");

            var onlyAdd = client.callTool(new McpSchema.CallToolRequest(
                    "updateJobParameters", Map.of("jobFullName", JOB, "addStringParameters", Map.of("EXTRA", 1))));
            assertThat(onlyAdd.isError()).isFalse();
        }

        job.doReload();
        assertThat(job.getProperty(ParametersDefinitionProperty.class).getParameterDefinitionNames())
                .containsExactly(
                        "CODE_PATH", "APP_PORT", "DOCKER_DEPLOY", "ENV_LABEL", "MVN_ARGS", "PACKAGE_NAME", "EXTRA");
        assertThat(((StringParameterDefinition) parameter(job, "PACKAGE_NAME")).getDefaultValue())
                .isEqualTo("ruoyi-auth");
        assertThat(((StringParameterDefinition) parameter(job, "EXTRA")).getDefaultValue())
                .isEqualTo("1");
        assertThat(((StringParameterDefinition) parameter(job, "CODE_PATH")).getDefaultValue())
                .isEqualTo("auth");
        assertThat(((CpsFlowDefinition) job.getDefinition()).getScript()).isEqualTo("echo 'hi'");
    }

    @Test
    void addsParametersToJobWithoutParameters(JenkinsRule jenkins) throws Exception {
        var job = jenkins.createProject(WorkflowJob.class, "plain");
        var extension = ExtensionList.lookupSingleton(JobParametersExtension.class);

        var result = extension.updateJobParameters("plain", null, Map.of("PACKAGE_NAME", "svc"));

        assertThat(result.updatedParameters()).isEmpty();
        assertThat(result.addedParameters()).containsExactly(Map.entry("PACKAGE_NAME", "svc"));
        job.doReload();
        assertThat(job.isParameterized()).isTrue();
        assertThat(((StringParameterDefinition) parameter(job, "PACKAGE_NAME")).getDefaultValue())
                .isEqualTo("svc");
    }

    @Test
    void updatesJobInFolder(JenkinsRule jenkins) throws Exception {
        var folder = jenkins.createFolder("team");
        var job = folder.createProject(WorkflowJob.class, "svc");
        job.addProperty(new ParametersDefinitionProperty(new StringParameterDefinition("NAME", "a")));
        var extension = ExtensionList.lookupSingleton(JobParametersExtension.class);

        var result = extension.updateJobParameters("team/svc", Map.of("NAME", "b"), null);

        assertThat(result.fullName()).isEqualTo("team/svc");
        assertThat(result.updatedParameters())
                .containsEntry("NAME", new JobParametersExtension.ParameterChange("a", "b"));
        assertThat(((StringParameterDefinition) parameter(job, "NAME")).getDefaultValue())
                .isEqualTo("b");
    }

    private static void assertInvalid(
            JobParametersExtension extension,
            String jobFullName,
            Map<String, Object> defaults,
            Map<String, Object> additions,
            String message) {
        assertThatThrownBy(() -> extension.updateJobParameters(jobFullName, defaults, additions))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(message);
    }

    private static WorkflowJob createJob(JenkinsRule jenkins, String name) throws Exception {
        var job = jenkins.createProject(WorkflowJob.class, name);
        job.setDefinition(new CpsFlowDefinition("echo 'hi'", true));
        var codePath = new StringParameterDefinition("CODE_PATH", ".", "build path");
        codePath.setTrim(true);
        job.addProperty(new ParametersDefinitionProperty(
                codePath,
                new StringParameterDefinition("APP_PORT", "8080"),
                new BooleanParameterDefinition("DOCKER_DEPLOY", true, null),
                new ChoiceParameterDefinition("ENV_LABEL", new String[] {"sit", "uat", "prod"}, null),
                new StringParameterDefinition("MVN_ARGS", "-DskipTests")));
        return job;
    }

    private static ParameterDefinition parameter(Job<?, ?> job, String name) {
        return job.getProperty(ParametersDefinitionProperty.class).getParameterDefinition(name);
    }

    /** {@link #EDITOR} may configure jobs, {@link #VIEWER} may only read them, {@link #OUTSIDER} sees no jobs. */
    private static void secure(JenkinsRule jenkins) throws Exception {
        jenkins.jenkins.setSecurityRealm(jenkins.createDummySecurityRealm());
        jenkins.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.READ)
                .everywhere()
                .to(EDITOR, VIEWER, OUTSIDER)
                .grant(Item.READ)
                .everywhere()
                .to(EDITOR, VIEWER)
                .grant(Item.CONFIGURE)
                .everywhere()
                .to(EDITOR));
        jenkins.jenkins.save();
    }

    private static McpSyncClient client(JenkinsRule jenkins, JenkinsMcpClientBuilder builder, String user) {
        return builder.jenkins(jenkins)
                .requestCustomizer((httpRequestBuilder, method, endpoint, body, context) ->
                        httpRequestBuilder.setHeader("Authorization", basicAuth(user)))
                .build();
    }

    private static String basicAuth(String user) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + user).getBytes());
    }
}
