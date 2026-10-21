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
package eu.openanalytics.shinyproxy.publisher.registry;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * What a version Skald built carries as {@code spec_json} (spec/runtime-spec-v1.json,
 * WORKPLAN-BUNDLES.md T9 (a)): the facts of its image -- the port the app listens on, the
 * non-root uid:gid it runs as, its language and the catalog base it was built from.
 *
 * <p>Judged by the published schema itself, loaded from the classpath, so the document and
 * this class cannot disagree; parsed strictly, so a repeated key or trailing bytes refuse it
 * rather than letting one reader see a different document than another. A document that does
 * not pass is never guessed at: the version it belongs to does not run.
 *
 * <p>The image is not here. It is stated once, in {@code content_version.image}, which for a
 * built version must be digest-pinned ({@link #digestPinned}), so the two cannot disagree
 * (29053ce-F1).
 */
public record RuntimeSpec(int port, String user, String language, String base) {

    public static final String SCHEMA = "skald.runtime/v1";
    static final String SCHEMA_RESOURCE = "/skald/spec/runtime-spec-v1.json";

    private static final Pattern DIGEST_PINNED = Pattern.compile("[^@\\s]+@sha256:[0-9a-f]{64}");
    private static final ObjectMapper STRICT = JsonMapper.builder()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();
    private static final ObjectMapper CANONICAL = new ObjectMapper();
    private static final Schema V1 = load();

    /** A refused document, with what was wrong with it. */
    public static final class Refused extends RuntimeException {
        Refused(String message) {
            super(message);
        }
    }

    /** The version's document, or {@link Refused} naming why it is not a v1 runtime spec. */
    public static RuntimeSpec parse(String json) {
        JsonNode node;
        try {
            node = STRICT.readTree(json == null ? "" : json);
        } catch (JsonProcessingException e) {
            throw new Refused("not one JSON document (or a repeated key)");
        }
        if (node == null || node.isMissingNode()) {
            throw new Refused("empty");
        }
        List<Error> errors = V1.validate(node);
        if (!errors.isEmpty()) {
            List<String> messages = errors.stream()
                    .map(e -> e.getInstanceLocation() + " " + e.getMessage())
                    .sorted(Comparator.naturalOrder()).distinct().toList();
            throw new Refused(String.join("; ", messages.subList(0, Math.min(5, messages.size()))));
        }
        return new RuntimeSpec(node.get("port").intValue(), node.get("user").textValue(),
                node.get("language").textValue(), node.get("base").textValue());
    }

    /** The document as stored: fixed key order, so one spec is always the same bytes. */
    public String toJson() {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("schema", SCHEMA);
        doc.put("port", port);
        doc.put("user", user);
        doc.put("language", language);
        doc.put("base", base);
        String json;
        try {
            json = CANONICAL.writeValueAsString(doc);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        // What is stored must be what is accepted: a generator bug fails here, at success,
        // rather than as a version that never starts.
        parse(json);
        return json;
    }

    /** Whether {@code image} names exactly one image by digest: {@code <repo>@sha256:<64 hex>}. */
    public static boolean digestPinned(String image) {
        return image != null && DIGEST_PINNED.matcher(image).matches();
    }

    private static Schema load() {
        JsonNode schema;
        try (InputStream in = RuntimeSpec.class.getResourceAsStream(SCHEMA_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(SCHEMA_RESOURCE + " is not on the classpath; a built version"
                        + " cannot be judged without the schema it is held to");
            }
            schema = CANONICAL.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        String id = schema.path("$id").asText();
        SchemaRegistry registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12,
                builder -> builder.schemas(Map.of(id, schema.toString()))
                        .schemaLoader(loader -> loader.fetchRemoteResources(false)
                                .block(iri -> !id.equals(iri.toString()))));
        return registry.getSchema(SchemaLocation.of(id));
    }
}
