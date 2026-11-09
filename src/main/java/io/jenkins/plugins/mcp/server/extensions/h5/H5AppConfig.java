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

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record H5AppConfig(
        @JsonProperty(value = "target_dir", required = true)
        @Schema(description = "Build output directory, e.g. dist", minLength = 1, maxLength = 255)
        String targetDir,

        @JsonProperty(value = "archive_name", required = true)
        @Schema(description = "Artifact archive name, e.g. html.tar.gz", minLength = 1, maxLength = 255)
        String archiveName,

        @JsonProperty("display_name") String displayName,
        String team,
        String owner,
        String remark,

        @JsonProperty("node_version")
        @Schema(description = "Jenkins NodeJS installation name; the job's NODEJS_VER overrides this")
        String nodeVersion,

        @JsonProperty("package_manager") PackageManager packageManager,
        @JsonProperty("npmrc_config_id") String npmrcConfigId,
        @JsonProperty("build_path") String buildPath,
        @JsonProperty("storage_type") StorageType storageType,
        @JsonProperty("ossutil_config") String ossutilConfig,
        @JsonProperty("k8s_html") Boolean k8sHtml,
        @JsonProperty("prod_rm_dist") Boolean prodRmDist,
        @JsonProperty("h5version_env_file") String h5versionEnvFile,
        @JsonProperty("skip_build") Boolean skipBuild,
        @JsonProperty("skip_codecheck") Boolean skipCodecheck,

        @Schema(description = "Environment name (sit/uat/prod) to build script and deployment path")
        Map<String, EnvConfig> envs,

        @Schema(description = "Additional legacy fields; never include secrets")
        Map<String, Object> extra) {

    @JsonAnySetter
    public void rejectUnknownProperty(String name, Object value) {
        throw new IllegalArgumentException("Unknown H5 config field: " + name);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record EnvConfig(
            @JsonProperty("build_script") @Schema(description = "Package script name, e.g. build:sit")
            String buildScript,

            @JsonProperty("oss_path") @Schema(description = "OSS or MinIO destination path")
            String ossPath) {
        @JsonAnySetter
        public void rejectUnknownProperty(String name, Object value) {
            throw new IllegalArgumentException("Unknown H5 environment field: " + name);
        }
    }

    public enum PackageManager {
        yarn,
        npm,
        pnpm
    }

    public enum StorageType {
        oss,
        minio
    }
}
