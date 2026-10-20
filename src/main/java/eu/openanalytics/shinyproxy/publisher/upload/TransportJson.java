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

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.json.JsonWriteFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reading and writing the T8 transport's JSON (spec/admin-transport-v1.json {@code shapes}).
 *
 * <p><b>Out: ASCII only.</b> Every non-ASCII character is written as {@code \\uXXXX}, so a
 * rejection detail or a file name carrying a bidi override or a C1 control reaches the client
 * as an escape and never as the raw character ({@code shapes.untrusted_text.json}). The
 * envelope is ContainerProxy's ApiResponse shape, {@code {"status", "data"}}, with a refusal's
 * {@code data} an object a client branches on by {@code code}.
 *
 * <p><b>In: strict.</b> A body is a JSON object of bounded size, duplicate keys refused (as in
 * the manifest), and each endpoint names the fields it takes; anything else is BAD_REQUEST.
 * The media type is checked here, in code, rather than with {@code consumes}: a {@code consumes}
 * mismatch is refused before any handler is chosen, so only a global advice could answer it,
 * and that would change upstream's responses too. Checking here keeps the refusal in this
 * transport's envelope, with its code, and makes the CSRF rule a line a mutant can remove.
 */
final class TransportJson {

    static final ObjectMapper JSON = JsonMapper.builder()
            .enable(JsonWriteFeature.ESCAPE_NON_ASCII)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .build();

    /** A JSON request body is small: these endpoints take a few fields. */
    static final int MAX_BODY_BYTES = 64 * 1024;

    private TransportJson() {
    }

    static ResponseEntity<byte[]> success(int status, Object data, HttpHeaders headers) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "success");
        body.put("data", data);
        return write(status, body, headers);
    }

    static ResponseEntity<byte[]> refusal(TransportException e) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("code", e.code().name());
        data.put("message", e.getMessage());
        data.put("retryable", e.code().retryable());
        data.putAll(e.extra());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", e.code().status() >= 500 ? "error" : "fail");
        body.put("data", data);
        HttpHeaders headers = new HttpHeaders();
        if (e.code().status() == 503) {
            headers.set(HttpHeaders.RETRY_AFTER, "5");
        }
        return write(e.code().status(), body, headers);
    }

    private static ResponseEntity<byte[]> write(int status, Object body, HttpHeaders headers) {
        byte[] bytes;
        try {
            bytes = JSON.writeValueAsBytes(body);
        } catch (IOException e) {
            throw new IllegalStateException("could not write a response", e);
        }
        HttpHeaders out = new HttpHeaders();
        if (headers != null) {
            out.putAll(headers);
        }
        out.setContentType(MediaType.APPLICATION_JSON);
        return ResponseEntity.status(status).headers(out).body(bytes);
    }

    /**
     * Refuses anything but {@code expected}. A request with no Content-Type is refused too:
     * {@code navigator.sendBeacon} or a no-cors {@code fetch} can send one cross-site without
     * a preflight ({@code shapes.untyped_body}).
     */
    static void requireMediaType(HttpServletRequest request, MediaType expected) {
        String header = request.getContentType();
        if (header == null || header.isBlank()) {
            throw new TransportException(TransportError.UNSUPPORTED_MEDIA_TYPE,
                    "this endpoint takes " + expected + " and the request has no Content-Type");
        }
        MediaType actual;
        try {
            actual = MediaType.parseMediaType(header);
        } catch (InvalidMediaTypeException e) {
            throw new TransportException(TransportError.UNSUPPORTED_MEDIA_TYPE,
                    "this endpoint takes " + expected + "; the Content-Type could not be read");
        }
        if (!actual.getType().equalsIgnoreCase(expected.getType())
                || !actual.getSubtype().equalsIgnoreCase(expected.getSubtype())) {
            throw new TransportException(TransportError.UNSUPPORTED_MEDIA_TYPE,
                    "this endpoint takes " + expected + ", not " + actual.getType() + "/" + actual.getSubtype());
        }
    }

    /** The JSON object body, refusing every field but {@code allowed}. */
    static JsonNode readObject(HttpServletRequest request, String... allowed) {
        requireMediaType(request, MediaType.APPLICATION_JSON);
        byte[] bytes;
        try (InputStream in = request.getInputStream()) {
            bytes = in.readNBytes(MAX_BODY_BYTES + 1);
        } catch (IOException e) {
            throw new TransportException(TransportError.BAD_REQUEST, "the request body could not be read");
        }
        if (bytes.length > MAX_BODY_BYTES) {
            throw new TransportException(TransportError.BAD_REQUEST,
                    "the request body is larger than " + MAX_BODY_BYTES + " bytes");
        }
        JsonNode node;
        try {
            node = JSON.readTree(bytes);
        } catch (IOException e) {
            throw new TransportException(TransportError.BAD_REQUEST, "the request body is not JSON (or repeats a key)");
        }
        if (node == null || !node.isObject()) {
            throw new TransportException(TransportError.BAD_REQUEST, "the request body must be a JSON object");
        }
        java.util.Set<String> known = java.util.Set.of(allowed);
        for (java.util.Iterator<String> names = node.fieldNames(); names.hasNext(); ) {
            String name = names.next();
            if (!known.contains(name)) {
                throw new TransportException(TransportError.BAD_REQUEST, "unknown field '"
                        + eu.openanalytics.shinyproxy.publisher.bundle.BundleRejection.printable(name)
                        + "'; this endpoint takes " + (known.isEmpty() ? "no fields" : new java.util.TreeSet<>(known)));
            }
        }
        return node;
    }
}
