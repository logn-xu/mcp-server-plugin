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
import hudson.model.Descriptor;
import hudson.model.ListView;
import hudson.model.TopLevelItem;
import hudson.model.View;
import hudson.security.ACL;
import io.jenkins.plugins.mcp.server.McpServerExtension;
import io.jenkins.plugins.mcp.server.annotation.Tool;
import io.jenkins.plugins.mcp.server.annotation.ToolParam;
import jakarta.annotation.Nullable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import jenkins.model.Jenkins;
import org.springframework.security.access.AccessDeniedException;

@Extension
public class ViewExtension implements McpServerExtension {

    private static final int MAX_REGEX_LENGTH = 1000;
    private static final int MAX_VISIBLE_JOBS = 100;

    public record ViewSummary(String name, String type, String description, String url) {}

    public record ViewDetails(
            String name,
            String type,
            String description,
            String url,
            @Nullable String includeRegex,
            @Nullable List<String> explicitJobs,
            @Nullable List<String> visibleJobs,
            int visibleJobCount) {}

    public record ViewJobsResult(
            String viewName,
            List<String> added,
            List<String> removed,
            List<String> notInView,
            List<String> stillVisible) {}

    @Tool(
            description = "List readable views at the Jenkins root (name, type, description and URL).",
            annotations = @Tool.Annotations(readOnlyHint = true, destructiveHint = false))
    public List<ViewSummary> getViews() {
        return Jenkins.get().getViews().stream().map(ViewExtension::summary).toList();
    }

    @Tool(
            description = "Get a readable root view, including its regex, explicit jobs and up to 100 visible jobs."
                    + " Job names not readable by the caller are omitted.",
            annotations = @Tool.Annotations(readOnlyHint = true, destructiveHint = false))
    public ViewDetails getView(@ToolParam(description = "Name of a view at the Jenkins root") String viewName) {
        return details(rootView(viewName));
    }

    @Tool(
            description = "Create a root List View. Requires both View/Create and View/Configure on Jenkins."
                    + " Jenkins security must be enabled. An optional regex dynamically includes matching jobs.",
            permissions = {"hudson.model.View.Configure"},
            annotations = @Tool.Annotations(readOnlyHint = false, destructiveHint = false))
    public ViewDetails createView(
            @ToolParam(description = "Name of the new root List View") String name,
            @Nullable @ToolParam(description = "Optional view description", required = false) String description,
            @Nullable @ToolParam(description = "Optional Java regex matching entire root job names", required = false)
                    String includeRegex)
            throws IOException {
        Jenkins jenkins = Jenkins.get();
        // Do not leak existing names or validate inputs before checking both independent permissions.
        if (!jenkins.isUseSecurity()) {
            throw new AccessDeniedException("Creating views requires Jenkins security to be enabled");
        }
        jenkins.checkPermission(View.CREATE);
        jenkins.checkPermission(View.CONFIGURE);
        requireName(name, "name");
        Jenkins.checkGoodName(name);
        checkRegex(includeRegex);
        synchronized (jenkins) {
            // getViews() is READ-filtered: check duplicates under SYSTEM only after the caller was authorized.
            if (rootNameExists(jenkins, name)) {
                throw new IllegalArgumentException("View '" + name + "' already exists");
            }
            // Validate and populate the unregistered object first, so bad inputs leave no view behind.
            var view = new ListView(name);
            if (description != null) {
                view.setDescription(description);
            }
            if (includeRegex != null) {
                view.setIncludeRegex(includeRegex);
            }
            jenkins.addView(view);
            return details(view);
        }
    }

    @Tool(
            description = "Rename a root view. Requires View/Configure on that view.",
            permissions = {"hudson.model.View.Configure"},
            annotations = @Tool.Annotations(readOnlyHint = false, destructiveHint = true))
    public ViewDetails renameView(
            @ToolParam(description = "Name of an existing root view") String viewName,
            @ToolParam(description = "New view name") String newName)
            throws IOException, Descriptor.FormException {
        View view = configurableView(viewName);
        requireName(newName, "newName");
        synchronized (Jenkins.get()) {
            if (!newName.equals(view.getViewName()) && rootNameExists(Jenkins.get(), newName)) {
                throw new IllegalArgumentException("View '" + newName + "' already exists");
            }
            view.rename(newName);
            view.save();
        }
        return details(view);
    }

    @Tool(
            description = "Set a root view's description; use an empty string to clear it. Requires View/Configure.",
            permissions = {"hudson.model.View.Configure"},
            annotations = @Tool.Annotations(readOnlyHint = false, destructiveHint = false))
    public ViewDetails setViewDescription(
            @ToolParam(description = "Name of an existing root view") String viewName,
            @ToolParam(description = "New description; empty string clears it") String description)
            throws IOException {
        View view = configurableView(viewName);
        if (description == null) {
            throw new IllegalArgumentException("description must not be null");
        }
        synchronized (view) {
            String previous = view.getDescription();
            view.setDescription(description);
            try {
                view.save();
            } catch (IOException | RuntimeException e) {
                view.setDescription(previous);
                throw e;
            }
        }
        return details(view);
    }

    @Tool(
            description = "Add or remove explicitly listed root jobs in a List View. All jobs are validated before"
                    + " one save. Removing an explicit job does not hide it when the view regex still matches it.",
            permissions = {"hudson.model.View.Configure"},
            annotations = @Tool.Annotations(readOnlyHint = false, destructiveHint = true))
    public ViewJobsResult updateViewJobs(
            @ToolParam(description = "Name of an existing root List View") String viewName,
            @Nullable @ToolParam(description = "Root job names to add", required = false) List<String> addJobs,
            @Nullable @ToolParam(description = "Root job names to remove", required = false) List<String> removeJobs)
            throws IOException {
        ListView view = listView(configurableView(viewName));
        List<String> toAdd = addJobs == null ? List.of() : addJobs;
        List<String> toRemove = removeJobs == null ? List.of() : removeJobs;
        if (toAdd.isEmpty() && toRemove.isEmpty()) {
            throw new IllegalArgumentException("Provide at least one job in addJobs or removeJobs");
        }
        Set<String> addNames = new HashSet<>();
        Set<String> removeNames = new HashSet<>();
        for (String name : toAdd) {
            addNames.add(rootJob(name).getName());
        }
        for (String name : toRemove) {
            removeNames.add(rootJob(name).getName());
        }
        if (!java.util.Collections.disjoint(addNames, removeNames)) {
            throw new IllegalArgumentException("Cannot add and remove the same job in one call");
        }
        List<String> added = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        List<String> notInView = new ArrayList<>();
        synchronized (view) {
            Set<String> original = view.getJobNames();
            Set<String> updated = new TreeSet<>(original);
            for (String name : addNames.stream().sorted().toList()) {
                if (updated.add(name)) {
                    added.add(name);
                }
            }
            for (String name : removeNames.stream().sorted().toList()) {
                if (updated.remove(name)) {
                    removed.add(name);
                } else {
                    notInView.add(name);
                }
            }
            if (!updated.equals(original)) {
                view.setJobNames(updated);
                try {
                    view.save();
                } catch (IOException | RuntimeException e) {
                    view.setJobNames(original);
                    throw e;
                }
            }
        }
        Set<String> visible = visibleJobNames(view);
        return new ViewJobsResult(
                view.getViewName(),
                added,
                removed,
                notInView,
                removed.stream().filter(visible::contains).toList());
    }

    @Tool(
            description = "Set the dynamic include regex on a root List View. Empty string clears the regex;"
                    + " explicit jobs remain. Requires View/Configure.",
            permissions = {"hudson.model.View.Configure"},
            annotations = @Tool.Annotations(readOnlyHint = false, destructiveHint = true))
    public ViewDetails setViewIncludeRegex(
            @ToolParam(description = "Name of an existing root List View") String viewName,
            @ToolParam(description = "Java regex (max 1000 characters); empty string clears it") String includeRegex)
            throws IOException {
        ListView view = listView(configurableView(viewName));
        if (includeRegex == null) {
            throw new IllegalArgumentException("includeRegex must not be null");
        }
        checkRegex(includeRegex);
        synchronized (view) {
            String previous = view.getIncludeRegex();
            view.setIncludeRegex(includeRegex);
            try {
                view.save();
            } catch (IOException | RuntimeException e) {
                view.setIncludeRegex(previous);
                throw e;
            }
        }
        return details(view);
    }

    private static boolean rootNameExists(Jenkins jenkins, String name) {
        try (var ignored = ACL.as2(ACL.SYSTEM2)) {
            return jenkins.getViews().stream()
                    .anyMatch(view -> view.getViewName().equals(name));
        }
    }

    private static void requireName(String name, String parameter) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException(parameter + " must not be empty");
        }
    }

    private static void checkRegex(@Nullable String regex) {
        if (regex != null) {
            if (regex.length() > MAX_REGEX_LENGTH) {
                throw new IllegalArgumentException("includeRegex must not exceed " + MAX_REGEX_LENGTH + " characters");
            }
            if (!regex.isEmpty()) {
                Pattern.compile(regex);
            }
        }
    }

    private static View rootView(String name) {
        requireName(name, "viewName");
        // Jenkins.getView(name) may search nested views without filtering READ.
        return Jenkins.get().getViews().stream()
                .filter(view -> view.getOwner() == Jenkins.get()
                        && view.getViewName().equals(name)
                        && view.hasPermission(View.READ))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("View '" + name + "' does not exist"));
    }

    private static View configurableView(String name) {
        View view = rootView(name);
        view.checkPermission(View.CONFIGURE);
        return view;
    }

    private static ListView listView(View view) {
        if (view instanceof ListView listView) {
            return listView;
        }
        throw new IllegalArgumentException("View '" + view.getViewName() + "' is not a List View");
    }

    private static TopLevelItem rootJob(String name) {
        requireName(name, "job name");
        TopLevelItem item = Jenkins.get().getItemByFullName(name, TopLevelItem.class);
        if (item == null || item.getParent() != Jenkins.get()) {
            throw new IllegalArgumentException("Root job '" + name + "' does not exist or is not readable");
        }
        return item;
    }

    private static ViewSummary summary(View view) {
        return new ViewSummary(
                view.getViewName(), view.getClass().getSimpleName(), view.getDescription(), view.getUrl());
    }

    private static ViewDetails details(View view) {
        var summary = summary(view);
        if (!(view instanceof ListView listView)) {
            return new ViewDetails(
                    summary.name(), summary.type(), summary.description(), summary.url(), null, null, null, 0);
        }
        List<String> explicit = listView.getJobNames().stream()
                .filter(name -> Jenkins.get().getItemByFullName(name, TopLevelItem.class) != null)
                .sorted()
                .toList();
        Set<String> visible = visibleJobNames(listView);
        return new ViewDetails(
                summary.name(),
                summary.type(),
                summary.description(),
                summary.url(),
                listView.getIncludeRegex(),
                explicit,
                visible.stream().sorted().limit(MAX_VISIBLE_JOBS).toList(),
                visible.size());
    }

    private static Set<String> visibleJobNames(View view) {
        Set<String> result = new HashSet<>();
        for (TopLevelItem item : view.getItems()) {
            if (item.hasPermission(hudson.model.Item.READ)) {
                result.add(item.getFullName());
            }
        }
        return result;
    }
}
