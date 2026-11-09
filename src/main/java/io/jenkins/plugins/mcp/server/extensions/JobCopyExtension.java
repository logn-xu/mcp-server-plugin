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

import hudson.Extension;
import hudson.model.Item;
import hudson.model.Job;
import hudson.model.ParameterDefinition;
import hudson.model.TopLevelItem;
import hudson.security.ACL;
import io.jenkins.plugins.mcp.server.McpServerExtension;
import io.jenkins.plugins.mcp.server.annotation.Tool;
import io.jenkins.plugins.mcp.server.annotation.ToolParam;
import io.jenkins.plugins.mcp.server.extensions.util.JobParameterDefaults;
import jakarta.annotation.Nullable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import jenkins.model.Jenkins;
import jenkins.model.ModifiableTopLevelItemGroup;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;

@Extension
@Slf4j
public class JobCopyExtension implements McpServerExtension {

    public record CopyJobResult(
            String fullName,
            String url,
            String template,
            Map<String, Object> updatedParameters,
            Map<String, String> addedParameters) {}

    @Tool(
            description =
                    "Create a new job by copying an existing job (the template), like 'Copy from' in the Jenkins UI."
                            + " The whole configuration is copied (pipeline definition, SCM, properties, all parameters)."
                            + " Optionally change the default values of String, Text, Boolean and Choice parameters,"
                            + " append new String parameters, and set the description of the new job."
                            + " All inputs are validated before the job is created; if a later step fails the new job is removed.",
            annotations = @Tool.Annotations(readOnlyHint = false, destructiveHint = true))
    public CopyJobResult copyJob(
            @ToolParam(description = "Full path of the template job to copy (e.g., 'folder/template-job')")
                    String templateJobFullName,
            @ToolParam(description = "Name (or full path) of the job to create. Parent folders must already exist")
                    String jobName,
            @Nullable
                    @ToolParam(
                            description =
                                    "New default values for parameters that exist in the template, keyed by parameter name"
                                            + " (e.g., {\"CODE_PATH\": \"my-module\", \"DOCKER_DEPLOY\": false})."
                                            + " Supported parameter types: String, Text, Boolean, Choice (value must be one of the choices)",
                            required = false,
                            additionalProperties = "{\"type\":[\"string\",\"boolean\",\"integer\",\"number\"]}")
                    Map<String, Object> parameterDefaults,
            @Nullable
                    @ToolParam(
                            description =
                                    "New String parameters appended after the template parameters, keyed by parameter name"
                                            + " with their default value (e.g., {\"PACKAGE_NAME\": \"my-module\"})."
                                            + " Names must not already exist in the template",
                            required = false,
                            additionalProperties = "{\"type\":[\"string\",\"integer\",\"number\",\"boolean\"]}")
                    Map<String, Object> addStringParameters,
            @Nullable
                    @ToolParam(
                            description = "Description of the new job. If omitted, the template description is kept",
                            required = false)
                    String description)
            throws IOException {
        if (templateJobFullName == null || templateJobFullName.isBlank()) {
            throw new IllegalArgumentException("templateJobFullName must not be empty");
        }
        if (jobName == null || jobName.isBlank()) {
            throw new IllegalArgumentException("jobName must not be empty");
        }

        var jenkins = Jenkins.get();
        var template = jenkins.getItemByFullName(templateJobFullName, Job.class);
        if (!(template instanceof TopLevelItem templateItem)) {
            throw new IllegalArgumentException("Template job '" + templateJobFullName + "' does not exist.");
        }

        ModifiableTopLevelItemGroup parent = jenkins;
        String name = jobName;
        int lastSlash = jobName.lastIndexOf('/');
        if (lastSlash != -1) {
            String parentName = jobName.substring(0, lastSlash);
            name = jobName.substring(lastSlash + 1);
            if (jenkins.getItemByFullName(parentName) instanceof ModifiableTopLevelItemGroup itemGroup) {
                parent = itemGroup;
            } else {
                throw new IllegalArgumentException(
                        "Parent folder '" + parentName + "' does not exist or cannot contain jobs.");
            }
        }
        if (parent.getItem(name) != null) {
            throw new IllegalArgumentException("A job with name '" + jobName + "' already exists.");
        }

        // Validate everything up front so that invalid input never leaves a half-configured job behind.
        List<ParameterDefinition> templateParameters = JobParameterDefaults.definitionsOf(template);
        Map<String, ParameterDefinition> replacements = parameterDefaults == null
                ? Map.of()
                : JobParameterDefaults.resolve(
                        templateParameters, parameterDefaults, "template job '" + templateJobFullName + "'");
        Map<String, Object> updated = new LinkedHashMap<>();
        replacements.forEach(
                (paramName, definition) -> updated.put(paramName, JobParameterDefaults.defaultValueOf(definition)));
        Map<String, ParameterDefinition> additionsByName = addStringParameters == null
                ? Map.of()
                : JobParameterDefaults.resolveStringAdditions(
                        templateParameters, addStringParameters, "template job '" + templateJobFullName + "'");
        List<ParameterDefinition> additions = new ArrayList<>(additionsByName.values());
        Map<String, String> added = new LinkedHashMap<>();
        additionsByName.forEach((paramName, definition) ->
                added.put(paramName, (String) JobParameterDefaults.defaultValueOf(definition)));

        // Core performs the Item.CREATE, Item.EXTENDED_READ, secret and name checks.
        Job<?, ?> created = (Job<?, ?>) parent.copy(templateItem, name);
        try {
            if (!created.hasPermission(Item.CONFIGURE)) {
                throw new AccessDeniedException(
                        "Access denied: missing " + Item.CONFIGURE.getId() + " on the new job '" + jobName + "'");
            }
            if (!replacements.isEmpty() || !additions.isEmpty()) {
                JobParameterDefaults.apply(created, replacements, additions);
            }
            if (description != null) {
                created.setDescription(description);
            }
            created.save();
        } catch (IOException | RuntimeException e) {
            rollback(created);
            throw e;
        }

        return new CopyJobResult(
                created.getFullName(), jenkins.getRootUrl() + created.getUrl(), template.getFullName(), updated, added);
    }

    private static void rollback(Job<?, ?> created) {
        // The caller may lack Item.DELETE; removing the job this call just created is still the right outcome.
        try (var ignored = ACL.as2(ACL.SYSTEM2)) {
            created.delete();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while removing job '{}' after a failed copyJob call", created.getFullName(), e);
        } catch (IOException | RuntimeException e) {
            log.warn("Failed to remove job '{}' after a failed copyJob call", created.getFullName(), e);
        }
    }
}
