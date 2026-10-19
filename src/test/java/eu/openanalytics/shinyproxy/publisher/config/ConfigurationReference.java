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

    private static JsonNode extractionSpec(ObjectMapper json) throws IOException {
        try (InputStream in = ExtractionLimits.class.getResourceAsStream(ExtractionLimits.SPEC_RESOURCE)) {
            return json.readTree(in);
        }
    }
}
