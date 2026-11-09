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

import hudson.BulkChange;
import hudson.Extension;
import hudson.model.Item;
import hudson.model.Job;
import hudson.model.ParameterDefinition;
import io.jenkins.plugins.mcp.server.McpServerExtension;
import io.jenkins.plugins.mcp.server.annotation.Tool;
import io.jenkins.plugins.mcp.server.annotation.ToolParam;
import io.jenkins.plugins.mcp.server.extensions.util.JobParameterDefaults;
import jakarta.annotation.Nullable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import jenkins.model.Jenkins;
import org.springframework.security.access.AccessDeniedException;

@Extension
public class JobParametersExtension implements McpServerExtension {

    public record ParameterChange(Object previous, Object current) {}

    public record UpdateJobParametersResult(
            String fullName,
            String url,
            Map<String, ParameterChange> updatedParameters,
            Map<String, String> addedParameters) {}

    @Tool(
            description = "Modify the parameters of an existing Jenkins job (e.g. a Pipeline): change default values of"
                    + " existing String, Text, Boolean and Choice parameters (a Choice value must be one of the existing"
                    + " choices) and/or append new String parameters. Requires the Job/Configure permission on the job."
                    + " All input is validated first; nothing is changed if any value is invalid.",
            annotations = @Tool.Annotations(readOnlyHint = false, destructiveHint = true))
    public UpdateJobParametersResult updateJobParameters(
            @ToolParam(description = "Full path of the Jenkins job (e.g., 'folder/job-name')") String jobFullName,
            @Nullable
                    @ToolParam(
                            description =
                                    "New default values for existing parameters, keyed by parameter name"
                                            + " (e.g., {\"CODE_PATH\": \"my-module\", \"DOCKER_DEPLOY\": false, \"ENV_LABEL\": \"uat\"})",
                            required = false,
                            additionalProperties = "{\"type\":[\"string\",\"boolean\",\"integer\",\"number\"]}")
                    Map<String, Object> parameterDefaults,
            @Nullable
                    @ToolParam(
                            description = "New String parameters appended after the existing parameters, keyed by"
                                    + " parameter name with their default value (e.g., {\"PACKAGE_NAME\": \"my-module\"})."
                                    + " Names must not already exist in the job",
                            required = false,
                            additionalProperties = "{\"type\":[\"string\",\"integer\",\"number\",\"boolean\"]}")
                    Map<String, Object> addStringParameters)
            throws IOException {
        if (jobFullName == null || jobFullName.isBlank()) {
            throw new IllegalArgumentException("jobFullName must not be empty");
        }
        // Jobs the caller cannot read are reported as missing, so their existence is not disclosed.
        var job = Jenkins.get().getItemByFullName(jobFullName, Job.class);
        if (job == null) {
            throw new IllegalArgumentException("Job '" + jobFullName + "' does not exist.");
        }
        if (!job.hasPermission(Item.CONFIGURE)) {
            throw new AccessDeniedException(
                    "Access denied: user '" + Jenkins.getAuthentication2().getName()
                            + "' has no permission to modify job '" + job.getFullName() + "' (requires "
                            + Item.CONFIGURE.group.title + "/" + Item.CONFIGURE.name + ").");
        }
        boolean hasUpdates = parameterDefaults != null && !parameterDefaults.isEmpty();
        boolean hasAdditions = addStringParameters != null && !addStringParameters.isEmpty();
        if (!hasUpdates && !hasAdditions) {
            throw new IllegalArgumentException(
                    "Provide at least one parameter in parameterDefaults or addStringParameters.");
        }

        String jobLabel = "job '" + job.getFullName() + "'";
        var existing = JobParameterDefaults.definitionsOf(job);
        if (hasUpdates && existing.isEmpty()) {
            throw new IllegalArgumentException(
                    "Job '" + job.getFullName() + "' has no parameters; use addStringParameters to add some.");
        }
        Map<String, ParameterDefinition> replacements =
                hasUpdates ? JobParameterDefaults.resolve(existing, parameterDefaults, jobLabel) : Map.of();
        Map<String, ParameterDefinition> additions = hasAdditions
                ? JobParameterDefaults.resolveStringAdditions(existing, addStringParameters, jobLabel)
                : Map.of();

        Map<String, ParameterChange> changes = new LinkedHashMap<>();
        for (var original : existing) {
            var replacement = replacements.get(original.getName());
            if (replacement != null) {
                changes.put(
                        original.getName(),
                        new ParameterChange(
                                JobParameterDefaults.defaultValueOf(original),
                                JobParameterDefaults.defaultValueOf(replacement)));
            }
        }
        Map<String, String> added = new LinkedHashMap<>();
        additions.forEach(
                (name, definition) -> added.put(name, (String) JobParameterDefaults.defaultValueOf(definition)));

        try (var bulkChange = new BulkChange(job)) {
            JobParameterDefaults.apply(job, replacements, new ArrayList<>(additions.values()));
            bulkChange.commit();
        }
        return new UpdateJobParametersResult(
                job.getFullName(), Jenkins.get().getRootUrl() + job.getUrl(), changes, added);
    }
}
