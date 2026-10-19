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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.openanalytics.shinyproxy.publisher.bundle.ExtractionLimits;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code spec/configuration-v1.json}, every key of the {@code skald:} section (T7c step 0c),
 * as the startup checks and the generated reference read it. The bundle limits are included
 * by reference to {@code extraction-limits-v1.json}: one key per {@link ExtractionLimits.Bound}.
 */
public final class ConfigurationSpec {

    /** Packaged from {@code spec/} by the {@code <resources>} block in pom.xml. */
    public static final String RESOURCE = "/skald/spec/configuration-v1.json";

    /** One key: its default as the file gives it (null: unset), the parser that reads it. */
    public record Key(String key, String type, JsonNode defaultValue, String parser, String scope, boolean secret,
                      boolean requiredWithWorker, Long min, Long max, String description) {

        /** The default as an operator would write it, or null when unset. */
        public String defaultText() {
            if (defaultValue == null || defaultValue.isNull()) {
                return null;
            }
            return defaultValue.isTextual() ? defaultValue.asText() : defaultValue.toString();
        }
    }

    /** Keys under {@code prefix} that another spec file describes. */
    public record Included(String prefix, String spec, String parser, String scope, String description) {
    }

    private final JsonNode root;
    private final List<Key> keys;
    private final List<Included> included;
    private final Map<String, String> renamed;

    private ConfigurationSpec(JsonNode root) {
        this.root = root;
        List<Key> k = new ArrayList<>();
        for (JsonNode n : root.path("keys")) {
            k.add(new Key(n.path("key").asText(), n.path("type").asText(), n.has("default") ? n.get("default") : null,
                    n.path("parser").asText(), n.path("scope").asText(), n.path("secret").asBoolean(false),
                    n.path("required_with_worker").asBoolean(false), n.has("min") ? n.get("min").asLong() : null,
                    n.has("max") ? n.get("max").asLong() : null, n.path("description").asText()));
        }
        List<Included> i = new ArrayList<>();
        for (JsonNode n : root.path("included")) {
            i.add(new Included(n.path("prefix").asText(), n.path("spec").asText(), n.path("parser").asText(),
                    n.path("scope").asText(), n.path("description").asText()));
        }
        Map<String, String> r = new LinkedHashMap<>();
        for (JsonNode n : root.path("renamed")) {
            r.put(n.path("from").asText(), n.path("to").asText());
        }
        this.renamed = Collections.unmodifiableMap(r);
        this.keys = List.copyOf(k);
        this.included = List.copyOf(i);
    }

    public static ConfigurationSpec load() {
        try (InputStream in = ConfigurationSpec.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(RESOURCE + " is not on the classpath");
            }
            return new ConfigurationSpec(new ObjectMapper().readTree(in));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public List<Key> keys() {
        return keys;
    }

    public List<Included> included() {
        return included;
    }

    /** Old names (before T7c) to their new ones; an old name is refused, naming the new. */
    public Map<String, String> renamed() {
        return renamed;
    }

    /** The key, if listed; the included bundle limits are not. */
    public java.util.Optional<Key> key(String name) {
        return keys.stream().filter(k -> k.key().equals(name)).findFirst();
    }

    /** The file's conventions: what a duration, a size and a secret are. */
    public JsonNode conventions() {
        return root.path("conventions");
    }

    /** Every key an operator may set: the listed ones and the included bundle limits. */
    public Set<String> allKeys() {
        Set<String> all = new LinkedHashSet<>();
        keys.forEach(k -> all.add(k.key()));
        for (ExtractionLimits.Bound b : ExtractionLimits.Bound.values()) {
            all.add(b.propertyName());
        }
        return all;
    }
}
