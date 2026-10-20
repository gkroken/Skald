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
package eu.openanalytics.shinyproxy.publisher.upload;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The error codes in code are exactly spec/admin-transport-v1.json shapes.errors. */
public class TransportErrorTest {

    @Test
    public void theCodesAreTheSpecsWithTheirStatusAndRetryability() throws Exception {
        JsonNode errors = new ObjectMapper().readTree(new File("spec/admin-transport-v1.json"))
            .path("shapes").path("errors");
        Map<String, String> spec = new TreeMap<>();
        errors.fields().forEachRemaining(e -> spec.put(e.getKey(),
            e.getValue().path("status").asInt() + "/" + e.getValue().path("retryable").asBoolean()));
        Map<String, String> code = new TreeMap<>();
        for (TransportError t : TransportError.values()) {
            code.put(t.name(), t.status() + "/" + t.retryable());
        }
        assertEquals(spec, code);
    }
}
