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

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import org.springframework.mock.web.MockHttpServletRequest;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The transport's JSON: ASCII on the wire, the refusal object, Retry-After on a 503. */
public class TransportJsonTest {

    @Test
    public void nonAsciiLeavesAsAnEscapeNeverRaw() {
        // A bidi override, a C1 control and an ordinary accented letter.
        ResponseEntity<byte[]> r = TransportJson.success(200, Map.of("t", "a\u202Eb\u0085c\u00e9"), null);
        String wire = new String(r.getBody(), StandardCharsets.ISO_8859_1);
        assertTrue(wire.chars().allMatch(ch -> ch < 0x80), wire);
        assertTrue(wire.contains("\\u202E") && wire.contains("\\u0085") && wire.contains("\\u00E9"), wire);
    }

    @Test
    public void aRepeatedKeyIsRefusedEvenWhenTheFieldIsAllowed() {
        // Otherwise the last value wins silently: a body naming two bundles builds one of them.
        MockHttpServletRequest twice = new MockHttpServletRequest("POST", "/x");
        twice.setContentType("application/json");
        twice.setContent("{\"bundleId\":\"a\",\"bundleId\":\"b\"}".getBytes(StandardCharsets.UTF_8));
        TransportException e = org.junit.jupiter.api.Assertions.assertThrows(TransportException.class,
            () -> TransportJson.readObject(twice, "bundleId"));
        assertEquals(TransportError.BAD_REQUEST, e.code());
        MockHttpServletRequest once = new MockHttpServletRequest("POST", "/x");
        once.setContentType("application/json; charset=utf-8");
        once.setContent("{\"bundleId\":\"a\"}".getBytes(StandardCharsets.UTF_8));
        assertEquals("a", TransportJson.readObject(once, "bundleId").path("bundleId").asText());
    }

    @Test
    public void anythingAfterTheBodysOneValueIsRefused() {
        for (String body : new String[] {"{} {\"x\":1}", "{}garbage", "{}]"}) {
            MockHttpServletRequest r = new MockHttpServletRequest("POST", "/x");
            r.setContentType("application/json");
            r.setContent(body.getBytes(StandardCharsets.UTF_8));
            TransportException e = org.junit.jupiter.api.Assertions.assertThrows(TransportException.class,
                () -> TransportJson.readObject(r), body);
            assertEquals(TransportError.BAD_REQUEST, e.code(), body);
        }
    }

    @Test
    public void aRefusalCarriesCodeMessageRetryableAndItsFields() {
        ResponseEntity<byte[]> r = TransportJson.refusal(new TransportException(TransportError.BUNDLE_NOT_UPLOADING, "m")
            .with("state", "VALIDATED"));
        assertEquals(409, r.getStatusCode().value());
        assertEquals("{\"status\":\"fail\",\"data\":{\"code\":\"BUNDLE_NOT_UPLOADING\",\"message\":\"m\","
            + "\"retryable\":false,\"state\":\"VALIDATED\"}}", new String(r.getBody(), StandardCharsets.UTF_8));
        ResponseEntity<byte[]> busy = TransportJson.refusal(new TransportException(TransportError.STORAGE_UNAVAILABLE, "m"));
        assertEquals(503, busy.getStatusCode().value());
        assertTrue(new String(busy.getBody(), StandardCharsets.UTF_8).startsWith("{\"status\":\"error\""));
        assertEquals("5", busy.getHeaders().getFirst("Retry-After"));
    }
}
