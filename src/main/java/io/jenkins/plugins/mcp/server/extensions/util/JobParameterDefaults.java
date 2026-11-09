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

package io.jenkins.plugins.mcp.server.extensions.util;

import hudson.model.BooleanParameterDefinition;
import hudson.model.ChoiceParameterDefinition;
import hudson.model.Job;
import hudson.model.ParameterDefinition;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.StringParameterDefinition;
import hudson.model.TextParameterDefinition;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Validates and applies new default values for String, Text, Boolean and Choice job parameters. */
public final class JobParameterDefaults {

    private JobParameterDefaults() {}

    public static List<ParameterDefinition> definitionsOf(Job<?, ?> job) {
        var property = job.getProperty(ParametersDefinitionProperty.class);
        return property == null ? List.of() : property.getParameterDefinitions();
    }

    /**
     * Builds replacement definitions for {@code defaults} without touching the job.
     *
     * @param jobLabel used in error messages, e.g. {@code "job 'folder/name'"}
     * @return replacements keyed by parameter name, in request order
     * @throws IllegalArgumentException if a parameter is unknown, unsupported or the value is invalid
     */
    public static Map<String, ParameterDefinition> resolve(
            List<ParameterDefinition> existing, Map<String, Object> defaults, String jobLabel) {
        Map<String, ParameterDefinition> replacements = new LinkedHashMap<>();
        for (var entry : defaults.entrySet()) {
            var original = existing.stream()
                    .filter(p -> p.getName().equals(entry.getKey()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Parameter '" + entry.getKey() + "' does not exist in " + jobLabel + "."));
            replacements.put(original.getName(), withDefaultValue(original, entry.getValue()));
        }
        return replacements;
    }

    /**
     * Builds new String parameter definitions to append, without touching the job.
     *
     * @param existing parameters already defined; new names must not clash with them
     * @param additions parameter name to default value ({@code null} means an empty default)
     * @throws IllegalArgumentException if a name is empty or already exists, or a value is not a scalar
     */
    public static Map<String, ParameterDefinition> resolveStringAdditions(
            List<ParameterDefinition> existing, Map<String, Object> additions, String jobLabel) {
        Map<String, ParameterDefinition> result = new LinkedHashMap<>();
        for (var entry : additions.entrySet()) {
            String name = entry.getKey();
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("Parameter names in addStringParameters must not be empty.");
            }
            if (existing.stream().anyMatch(p -> p.getName().equals(name))) {
                throw new IllegalArgumentException("Parameter '" + name + "' already exists in " + jobLabel
                        + "; use parameterDefaults to change its default value.");
            }
            String value = entry.getValue() == null ? "" : toScalarString(name, entry.getValue());
            result.put(name, new StringParameterDefinition(name, value));
        }
        return result;
    }

    /** Replaces the parameters of {@code job} in place, keeping the original order. Does not save the job. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void apply(
            Job job, Map<String, ParameterDefinition> replacements, List<ParameterDefinition> additions)
            throws IOException {
        List<ParameterDefinition> parameters = new ArrayList<>();
        for (var parameter : definitionsOf(job)) {
            parameters.add(replacements.getOrDefault(parameter.getName(), parameter));
        }
        parameters.addAll(additions);
        job.removeProperty(ParametersDefinitionProperty.class);
        job.addProperty(new ParametersDefinitionProperty(parameters));
    }

    public static ParameterDefinition withDefaultValue(ParameterDefinition original, Object value) {
        String name = original.getName();
        if (value == null) {
            throw new IllegalArgumentException("Default value for parameter '" + name + "' must not be null.");
        }
        // Exact class checks: subclasses may carry state that a rebuilt definition would silently drop.
        Class<?> type = original.getClass();
        if (type == StringParameterDefinition.class) {
            var string = (StringParameterDefinition) original;
            return new StringParameterDefinition(
                    name, toScalarString(name, value), original.getDescription(), string.isTrim());
        }
        if (type == TextParameterDefinition.class) {
            var text = new TextParameterDefinition(name, toScalarString(name, value), original.getDescription());
            text.setTrim(((TextParameterDefinition) original).isTrim());
            return text;
        }
        if (type == BooleanParameterDefinition.class) {
            return new BooleanParameterDefinition(name, toBoolean(name, value), original.getDescription());
        }
        if (type == ChoiceParameterDefinition.class) {
            var choice = (ChoiceParameterDefinition) original;
            String selected = toScalarString(name, value);
            if (!choice.getChoices().contains(selected)) {
                throw new IllegalArgumentException("Value '" + selected + "' is not a valid choice for parameter '"
                        + name + "'. Valid choices: " + choice.getChoices());
            }
            // Jenkins uses the first choice as the default, and re-saving the job in the UI keeps only the order.
            List<String> choices = new ArrayList<>(choice.getChoices());
            choices.remove(selected);
            choices.add(0, selected);
            return new ChoiceParameterDefinition(name, choices.toArray(new String[0]), original.getDescription());
        }
        throw new IllegalArgumentException("Changing the default value of parameter '" + name + "' of type "
                + type.getName() + " is not supported. Supported types: String, Text, Boolean, Choice.");
    }

    public static Object defaultValueOf(ParameterDefinition definition) {
        if (definition instanceof BooleanParameterDefinition bool) {
            return bool.isDefaultValue();
        }
        if (definition instanceof StringParameterDefinition string) {
            return string.getDefaultValue();
        }
        var value = definition.getDefaultParameterValue();
        return value == null ? null : value.getValue();
    }

    public static String toScalarString(String name, Object value) {
        if (value instanceof String || value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        throw new IllegalArgumentException("Value for parameter '" + name + "' must be a string, number or boolean.");
    }

    private static boolean toBoolean(String name, Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String string) {
            if ("true".equalsIgnoreCase(string.trim())) {
                return true;
            }
            if ("false".equalsIgnoreCase(string.trim())) {
                return false;
            }
        }
        throw new IllegalArgumentException("Value for boolean parameter '" + name + "' must be true or false.");
    }
}
