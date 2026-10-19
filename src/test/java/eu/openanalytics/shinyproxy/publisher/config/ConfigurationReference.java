/*
 * Skald
 *
 * Copyright (C) 2026 Gard Kroken
 *
 * Built on ShinyProxy, Copyright (C) 2016-2026 Open Analytics NV.
 *
 * ===========================================================================
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the Apache License as published by
 * The Apache Software Foundation, either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * Apache License for more details.
 *
 * You should have received a copy of the Apache License
 * along with this program.  If not, see <http://www.apache.org/licenses/>
 */
package eu.openanalytics.shinyproxy.publisher.config;

import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.openanalytics.shinyproxy.publisher.bundle.ExtractionLimits;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What {@code spec/configuration-v1.json} generates (T7c step 0c): the JSON Schema of the
 * {@code skald:} section, for editors (a yaml-language-server modeline), CI and later a Helm
 * values schema, and the reference block of {@code docs/CONFIGURATION.md}. Written by
 * {@link ConfigurationSpecTest} with {@code -Dskald.regenerate=true}, compared otherwise.
 */
final class ConfigurationReference {

    static final String SCHEMA_PATH = "schemas/config/skald-config.schema.json";
    static final String EXAMPLE_PATH = "config/application.example.yml";
    static final String DOC_PATH = "docs/CONFIGURATION.md";
    static final String BEGIN = "<!-- BEGIN generated from spec/configuration-v1.json by ConfigurationSpecTest;"
            + " do not edit by hand -->";
    static final String END = "<!-- END generated -->";

    /** A duration: a bare number of seconds, or a number with a unit, or ISO-8601. */
    private static final String DURATION = "^([0-9]+(ns|us|ms|s|m|h|d)?|P[0-9TDHMS.]+)$";
    private static final String SIZE = "^[0-9]+(B|KB|MB|GB|TB)$";

    private ConfigurationReference() {
    }

    static String schema(ConfigurationSpec spec) throws IOException {
        ObjectMapper json = new ObjectMapper();
        ObjectNode root = json.createObjectNode();
        root.put("$schema", "https://json-schema.org/draft/2020-12/schema");
        root.put("$id", "https://skald.invalid/schemas/config/skald-config.schema.json");
        root.put("title", "Skald configuration");
        root.put("description", "The skald: section of application.yml, generated from spec/configuration-v1.json."
                + " Other sections (proxy:, spring:) are left open.");
        root.put("type", "object");
        ObjectNode skald = section(root.putObject("properties").putObject("skald"));
        for (ConfigurationSpec.Key k : spec.keys()) {
            put(json, skald, k.key().substring("skald.".length()), property(json, k));
        }
        JsonNode bounds = extractionSpec(json).path("bounds");
        for (ExtractionLimits.Bound b : ExtractionLimits.Bound.values()) {
            ExtractionLimits.Declaration d = ExtractionLimits.declaration(b);
            ObjectNode p = json.createObjectNode();
            p.put("type", "integer").put("minimum", 1).put("maximum", d.absoluteMax()).put("default", d.defaultValue());
            p.put("description", bounds.path(b.specName()).path("what").asText() + " (unit: "
                    + bounds.path(b.specName()).path("unit").asText() + ")");
            put(json, skald, b.propertyName().substring("skald.".length()), p);
        }
        DefaultPrettyPrinter pretty = new DefaultPrettyPrinter().withObjectIndenter(new DefaultIndenter("  ", "\n"));
        return json.writer(pretty).writeValueAsString(root) + "\n";
    }

    private static ObjectNode section(ObjectNode node) {
        node.put("type", "object");
        node.put("additionalProperties", false);
        node.putObject("properties");
        return node;
    }

    private static void put(ObjectMapper json, ObjectNode skald, String relative, ObjectNode property) {
        String[] parts = relative.split("\\.");
        ObjectNode at = skald;
        for (int i = 0; i < parts.length - 1; i++) {
            ObjectNode props = (ObjectNode) at.get("properties");
            ObjectNode next = (ObjectNode) props.get(parts[i]);
            if (next == null) {
                next = section(props.putObject(parts[i]));
            }
            at = next;
        }
        ((ObjectNode) at.get("properties")).set(parts[parts.length - 1], property);
    }

    private static ObjectNode property(ObjectMapper json, ConfigurationSpec.Key k) {
        ObjectNode p = json.createObjectNode();
        switch (k.type()) {
            case "integer" -> {
                p.put("type", "integer");
                if (k.min() != null) {
                    p.put("minimum", k.min());
                }
                if (k.max() != null) {
                    p.put("maximum", k.max());
                }
            }
            case "boolean" -> p.put("type", "boolean");
            case "duration" -> {
                ArrayNode any = p.putArray("anyOf");
                any.addObject().put("type", "integer").put("minimum", 0);
                any.addObject().put("type", "string").put("pattern", DURATION);
            }
            case "size" -> p.put("type", "string").put("pattern", SIZE);
            case "list" -> p.put("type", "array").putObject("items").put("type", "string");
            case "url" -> p.put("type", "string").put("format", "uri");
            case "string", "image", "path" -> p.put("type", "string");
            default -> throw new IllegalStateException(k.key() + ": unknown type " + k.type());
        }
        if (k.defaultValue() != null && !k.defaultValue().isNull()) {
            p.set("default", k.defaultValue());
        }
        if (k.secret()) {
            p.put("writeOnly", true);
        }
        p.put("description", (k.secret() ? "SECRET: give it as ${VARIABLE} or through configtree:, never in the file. "
                : "") + k.description());
        return p;
    }

    static String reference(ConfigurationSpec spec) throws IOException {
        StringBuilder b = new StringBuilder(BEGIN).append("\n\n");
        b.append("## Conventions\n\n");
        spec.conventions().fields().forEachRemaining(e -> b.append("- **").append(e.getKey()).append("**: ")
                .append(e.getValue().asText()).append("\n"));
        Map<String, List<ConfigurationSpec.Key>> sections = new LinkedHashMap<>();
        for (ConfigurationSpec.Key k : spec.keys()) {
            sections.computeIfAbsent(k.key().substring(0, k.key().lastIndexOf('.')), s -> new ArrayList<>()).add(k);
        }
        for (Map.Entry<String, List<ConfigurationSpec.Key>> s : sections.entrySet()) {
            b.append("\n## ").append(s.getKey()).append("\n\n");
            b.append("| Key | Type | Default | Scope | Description |\n|---|---|---|---|---|\n");
            for (ConfigurationSpec.Key k : s.getValue()) {
                String type = k.type() + (k.min() != null ? ", min " + k.min() : "") + (k.max() != null ? ", max " + k.max()
                        : "") + (k.parser().equals("settings-seconds") ? ", whole seconds" : "");
                String def = k.defaultText() == null ? "unset" : "`" + k.defaultText() + "`";
                String notes = (k.secret() ? "**Secret.** " : "") + (k.requiredWithWorker() ? "**Required** with the"
                        + " worker. " : "") + k.description();
                b.append("| `").append(k.key().substring(s.getKey().length() + 1)).append("` | ").append(type)
                        .append(" | ").append(def).append(" | ").append(k.scope()).append(" | ")
                        .append(notes.replace("|", "\\|")).append(" |\n");
            }
        }
        b.append("\n## Renamed keys\n\n").append(spec.conventions().path("renamed").asText()).append("\n\n");
        b.append("| Old key | New key |\n|---|---|\n");
        spec.renamed().forEach((from, to) -> b.append("| `").append(from).append("` | `").append(to).append("` |\n"));
        JsonNode bounds = extractionSpec(new ObjectMapper()).path("bounds");
        for (ConfigurationSpec.Included inc : spec.included()) {
            b.append("\n## ").append(inc.prefix(), 0, inc.prefix().length() - 1).append("\n\n")
                    .append(inc.description()).append("\n\n");
            b.append("| Key | Unit | Default | Most | Description |\n|---|---|---|---|---|\n");
            for (ExtractionLimits.Bound bound : ExtractionLimits.Bound.values()) {
                ExtractionLimits.Declaration d = ExtractionLimits.declaration(bound);
                JsonNode n = bounds.path(bound.specName());
                b.append("| `").append(bound.propertyName().substring(inc.prefix().length())).append("` | ")
                        .append(n.path("unit").asText()).append(" | `").append(d.defaultValue()).append("` | ")
                        .append(d.absoluteMax()).append(" | ").append(n.path("what").asText().replace("|", "\\|"))
                        .append(" |\n");
            }
        }
        return b.append("\n").append(END).append("\n").toString();
    }

    /**
     * The example configuration (T7c step 0f): every key of the skald: section with its
     * description, at its default; a key with no default is commented out with its example.
     * {@code filled}: every example set too, which the test validates to prove the examples.
     */
    static String example(ConfigurationSpec spec, boolean filled) throws IOException {
        StringBuilder b = new StringBuilder();
        b.append("# yaml-language-server: $schema=../schemas/config/skald-config.schema.json\n");
        b.append("#\n# Skald's configuration: every key of the skald: section, each at its default. Copy the\n");
        b.append("# section into your application.yml and change what you need; docs/CONFIGURATION.md\n");
        b.append("# describes each key, and --validate-config checks a file before you deploy it. A key\n");
        b.append("# with no default is shown commented out, with an example value. Secrets are ${VARIABLES}\n");
        b.append("# from the environment: never write one into this file.\n");
        b.append("# Generated from spec/configuration-v1.json by ConfigurationSpecTest; do not edit by hand.\n");
        b.append("skald:\n");
        List<String> open = new ArrayList<>();
        for (ConfigurationSpec.Key k : spec.keys()) {
            List<String> parts = List.of(k.key().substring("skald.".length()).split("\\."));
            open = descend(b, open, parts.subList(0, parts.size() - 1));
            String indent = "  ".repeat(parts.size());
            String meta = k.type() + (k.parser().equals("settings-seconds") ? ", whole seconds" : "")
                    + (k.min() != null ? ", min " + k.min() : "") + (k.max() != null ? ", max " + k.max() : "")
                    + "; " + k.scope() + (k.secret() ? "; SECRET" : "") + (k.requiredWithWorker()
                    ? "; required with the worker" : "");
            comment(b, indent, k.description() + " (" + meta + ")");
            String name = parts.get(parts.size() - 1);
            boolean unset = k.defaultValue() == null || k.defaultValue().isNull();
            JsonNode shown = unset || filled && k.example() != null ? k.example() : k.defaultValue();
            String line = name + ": " + yaml(shown);
            if (unset && !filled) {
                b.append(indent).append("# ").append(line).append("\n");
            } else {
                b.append(indent).append(line);
                if (!filled && k.type().equals("list") && k.example() != null) {
                    b.append("  # e.g. ").append(yaml(k.example()));
                }
                b.append("\n");
            }
        }
        JsonNode bounds = extractionSpec(new ObjectMapper()).path("bounds");
        for (ConfigurationSpec.Included inc : spec.included()) {
            List<String> parts = List.of(inc.prefix().substring("skald.".length(), inc.prefix().length() - 1).split("\\."));
            open = descend(b, open, parts);
            String indent = "  ".repeat(parts.size() + 1);
            for (ExtractionLimits.Bound bound : ExtractionLimits.Bound.values()) {
                JsonNode n = bounds.path(bound.specName());
                comment(b, indent, n.path("what").asText() + " (" + n.path("unit").asText() + ", at most "
                        + ExtractionLimits.declaration(bound).absoluteMax() + "; deployment)");
                b.append(indent).append(bound.propertyName().substring(inc.prefix().length())).append(": ")
                        .append(ExtractionLimits.declaration(bound).defaultValue()).append("\n");
            }
        }
        return b.toString();
    }

    /** Opens the mappings {@code path} needs after {@code open}; returns the new open path. */
    private static List<String> descend(StringBuilder b, List<String> open, List<String> path) {
        int same = 0;
        while (same < open.size() && same < path.size() && open.get(same).equals(path.get(same))) {
            same++;
        }
        for (int i = same; i < path.size(); i++) {
            b.append("\n").append("  ".repeat(i + 1)).append(path.get(i)).append(":\n");
        }
        return path;
    }

    private static void comment(StringBuilder b, String indent, String text) {
        int width = 92 - indent.length() - 2;
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            if (line.length() > 0 && line.length() + 1 + word.length() > width) {
                b.append(indent).append("# ").append(line).append("\n");
                line.setLength(0);
            }
            line.append(line.length() > 0 ? " " : "").append(word);
        }
        b.append(indent).append("# ").append(line).append("\n");
    }

    /** A value as YAML: lists in flow style, strings plain unless YAML would read them otherwise. */
    private static String yaml(JsonNode v) {
        if (v == null || v.isNull()) {
            return "";
        }
        if (v.isArray()) {
            List<String> items = new ArrayList<>();
            v.forEach(i -> items.add(yaml(i)));
            return "[" + String.join(", ", items) + "]";
        }
        if (!v.isTextual()) {
            return v.toString();
        }
        String s = v.asText();
        boolean plain = s.matches("[A-Za-z0-9$/][A-Za-z0-9${}_./:@-]*") && !s.matches("(?i)true|false|null|yes|no|on|off")
                && !s.contains(": ") && !s.matches("[0-9.]+");
        return plain ? s : "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static JsonNode extractionSpec(ObjectMapper json) throws IOException {
        try (InputStream in = ExtractionLimits.class.getResourceAsStream(ExtractionLimits.SPEC_RESOURCE)) {
            return json.readTree(in);
        }
    }
}
