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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The runtime document of a built version (spec/runtime-spec-v1.json), judged by the shipped
 * schema itself: what passes, and each way a document is refused.
 */
public class RuntimeSpecTest {

    private static final String VALID =
        "{\"schema\":\"skald.runtime/v1\",\"port\":3838,\"user\":\"999:999\",\"language\":\"r\","
            + "\"base\":\"r-shiny-4.6.1-amd64-r3\"}";

    @Test
    public void aValidDocumentIsReadAndWrittenBackAsTheSameBytes() {
        RuntimeSpec spec = RuntimeSpec.parse(VALID);
        assertEquals(new RuntimeSpec(3838, "999:999", "r", "r-shiny-4.6.1-amd64-r3"), spec);
        assertEquals(VALID, spec.toJson());
        assertEquals(spec, RuntimeSpec.parse(" " + VALID + "\n"));
    }

    private static void refused(String json) {
        assertThrows(RuntimeSpec.Refused.class, () -> RuntimeSpec.parse(json), json);
    }

    private static String with(String field, String value) {
        return VALID.replaceFirst("\"" + field + "\":(\"[^\"]*\"|[0-9]+)", "\"" + field + "\":" + value);
    }

    @Test
    public void everyFieldIsHeldToTheSchema() {
        refused(with("schema", "\"skald.runtime/v2\""));
        refused(with("port", "1023"));
        refused(with("port", "65536"));
        refused(with("port", "\"3838\""));
        refused(with("user", "\"0:0\""));
        refused(with("user", "\"999:0\""));
        refused(with("user", "\"shiny:shiny\""));
        refused(with("user", "\"999\""));
        refused(with("user", "\"0999:999\""));
        refused(with("language", "\"julia\""));
        refused(with("base", "\"../other\""));
        refused(with("base", "\"R-Shiny\""));
        // Each still a valid value of every other field: the refusal is that field's.
        RuntimeSpec.parse(with("port", "8000"));
        RuntimeSpec.parse(with("language", "\"python\""));
    }

    @Test
    public void theDocumentIsExactlyItsFields() {
        refused("{}");
        refused(VALID.replace(",\"base\":\"r-shiny-4.6.1-amd64-r3\"", ""));
        refused(VALID.replace("}", ",\"env\":{\"A\":\"1\"}}"));
        refused("[]");
        refused("null");
        refused("");
        refused(null);
    }

    @Test
    public void itIsParsedStrictly() {
        refused(VALID.replace("\"port\":3838", "\"port\":3838,\"port\":3839"));
        refused(VALID + "{}");
        refused(VALID + "x");
        refused("{");
    }

    @Test
    public void anImageIsPinnedOnlyByAWholeDigest() {
        String hex = "a".repeat(64);
        assertTrue(RuntimeSpec.digestPinned("registry:5000/skald/content/c@sha256:" + hex));
        assertFalse(RuntimeSpec.digestPinned("registry:5000/skald/content/c:latest"));
        assertFalse(RuntimeSpec.digestPinned("registry:5000/skald/content/c@sha256:" + hex.substring(1)));
        assertFalse(RuntimeSpec.digestPinned("registry:5000/skald/content/c@sha256:" + hex.toUpperCase()));
        assertFalse(RuntimeSpec.digestPinned("@sha256:" + hex));
        assertFalse(RuntimeSpec.digestPinned("a b@sha256:" + hex));
        assertFalse(RuntimeSpec.digestPinned(null));
    }
}
