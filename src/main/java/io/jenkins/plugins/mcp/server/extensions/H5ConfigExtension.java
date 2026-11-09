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

import hudson.Extension;
import io.jenkins.plugins.mcp.server.McpServerExtension;
import io.jenkins.plugins.mcp.server.annotation.Tool;
import io.jenkins.plugins.mcp.server.annotation.ToolParam;
import io.jenkins.plugins.mcp.server.extensions.h5.H5AppConfig;
import io.jenkins.plugins.mcp.server.extensions.h5.H5ConfigClient;
import jakarta.annotation.Nullable;
import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import jenkins.model.Jenkins;
import jenkins.util.SystemProperties;

@Extension
public class H5ConfigExtension implements McpServerExtension {
    public static final String BASE_URL_PROPERTY = H5ConfigExtension.class.getName() + ".baseUrl";
    private static final Pattern NAME = Pattern.compile("[\\p{L}\\p{N}][\\p{L}\\p{N}_.-]*");
    private final H5ConfigClient client = new H5ConfigClient();

    @Tool(
            description =
                    "Get canonical H5 build configuration and revision by Jenkins job base name and optional module."
                            + " Requires Overall/Administer and an administrator-configured H5 service URL."
                            + " Does not require the job to exist. Does not fetch legacy pipeline configuration.",
            permissions = {"hudson.model.Hudson.Administer"},
            structuredOutput = true,
            annotations = @Tool.Annotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true))
    public Map<String, Object> getH5Config(
            @ToolParam(description = "Job base name, without folder or :module; normalized to lowercase")
                    String projectName,
            @Nullable @ToolParam(description = "Optional module name, without colon or slash", required = false)
                    String module)
            throws IOException, InterruptedException {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        return client.get(SystemProperties.getString(BASE_URL_PROPERTY), appKey(projectName, module));
    }

    @Tool(
            description = "Validate or create a new canonical H5 build configuration. Requires Overall/Administer."
                    + " dryRun defaults to true (no persistence); pass false explicitly only after approval to create."
                    + " Existing applications are errors, never overwritten. Returns dry_run, changed, app, diff, warnings."
                    + " Does not create a Jenkins job or trigger a build; use copyJob separately after creation."
                    + " After a network failure, query the application before retrying.",
            permissions = {"hudson.model.Hudson.Administer"},
            structuredOutput = true,
            annotations = @Tool.Annotations(readOnlyHint = false, destructiveHint = false, idempotentHint = false))
    public Map<String, Object> createH5Config(
            @ToolParam(description = "Job base name, without folder or :module; normalized to lowercase")
                    String projectName,
            @ToolParam(
                            description = "Canonical configuration. Required: target_dir and archive_name."
                                    + " Omitted optional fields use service defaults. Do not include secrets.")
                    H5AppConfig config,
            @Nullable @ToolParam(description = "Optional module name, without colon or slash", required = false)
                    String module,
            @Nullable
                    @ToolParam(
                            description = "Defaults to true. Only explicit false persists configuration",
                            required = false)
                    Boolean dryRun,
            @Nullable @ToolParam(description = "Optional change explanation, at most 512 characters", required = false)
                    String changeNote)
            throws IOException, InterruptedException {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        String key = appKey(projectName, module);
        if (config == null
                || config.targetDir() == null
                || config.targetDir().isBlank()
                || config.archiveName() == null
                || config.archiveName().isBlank()) {
            throw new IllegalArgumentException("config.target_dir and config.archive_name must not be blank.");
        }
        if (changeNote != null && changeNote.length() > 512) {
            throw new IllegalArgumentException("changeNote must not exceed 512 characters.");
        }
        return client.create(
                SystemProperties.getString(BASE_URL_PROPERTY),
                key,
                config,
                !Boolean.FALSE.equals(dryRun),
                Jenkins.getAuthentication2().getName(),
                changeNote);
    }

    private static String appKey(String projectName, String module) {
        if (projectName == null || !NAME.matcher(projectName).matches()) {
            throw new IllegalArgumentException(
                    "projectName must start with a letter or digit and contain only letters, digits, '-', '_' or '.'."
                            + " Use the job base name without a folder or :module.");
        }
        if (module != null && !NAME.matcher(module).matches()) {
            throw new IllegalArgumentException(
                    "module must start with a letter or digit and contain only letters, digits, '-', '_' or '.'.");
        }
        String key = (projectName + (module == null ? "" : ":" + module)).toLowerCase(Locale.ROOT);
        if (key.length() > 191) {
            throw new IllegalArgumentException("The combined project:module key must not exceed 191 characters.");
        }
        return key;
    }
}
