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
import eu.openanalytics.containerproxy.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;

/**
 * build.create, build.status, build.list and build.cancel (spec/admin-transport-v1.json).
 *
 * <p>Authorization and the CSRF rule are BundleUploadController's: every path is under
 * {@code /admin}, gated by UISecurityConfig on isAdmin, and both POSTs take application/json
 * only, checked in code, so a form type or an untyped body is refused in this transport's
 * envelope. The GETs change nothing. Ids are looked up as (content, build).
 */
@RestController
@RequestMapping("/admin/content/{contentId}/builds")
@ConditionalOnProperty(name = {"spring.datasource.url", "skald.storage.endpoint"})
public class BuildController extends TransportController {

    private static final Set<String> LIST_PARAMETERS = Set.of("limit", "state", "before");

    private final BuildService service;
    private final UserService userService;

    public BuildController(BuildService service, @Lazy UserService userService) {
        this.service = service;
        this.userService = userService;
    }

    @PostMapping
    public ResponseEntity<byte[]> create(@PathVariable UUID contentId, HttpServletRequest request) {
        JsonNode body = TransportJson.readObject(request, "bundleId", "idempotencyKey", "retryOf");
        BuildService.Created made = service.create(contentId, actor(), id(body, "bundleId"),
                key(body), id(body, "retryOf"), absolute());
        Map<String, Object> data = new LinkedHashMap<>(made.status());
        data.put("created", made.created());
        data.put("supersededBuildId", made.superseded() == null ? null : made.superseded().toString());
        HttpHeaders headers = new HttpHeaders();
        headers.setLocation(URI.create(String.valueOf(((Map<?, ?>) made.status().get("links")).get("self"))));
        return TransportJson.success(202, data, headers);
    }

    @GetMapping
    public ResponseEntity<byte[]> list(@PathVariable UUID contentId, HttpServletRequest request) {
        // A misspelt parameter would otherwise be a filter silently not applied.
        for (String name : request.getParameterMap().keySet()) {
            if (!LIST_PARAMETERS.contains(name)) {
                throw new TransportException(TransportError.BAD_REQUEST, "unknown parameter '"
                        + eu.openanalytics.shinyproxy.publisher.bundle.BundleRejection.printable(name)
                        + "'; this endpoint takes " + new java.util.TreeSet<>(LIST_PARAMETERS));
            }
        }
        int limit = BuildService.DEFAULT_LIMIT;
        String[] limits = request.getParameterValues("limit");
        if (limits != null) {
            if (limits.length != 1 || !limits[0].matches("[0-9]{1,4}")) {
                throw new TransportException(TransportError.BAD_REQUEST,
                        "limit is one whole number, 1 to " + BuildService.MAX_LIMIT);
            }
            limit = Integer.parseInt(limits[0]);
        }
        String[] states = request.getParameterValues("state");
        List<String> filter = states == null ? List.of() : new ArrayList<>(Arrays.asList(states));
        UUID before = null;
        String[] befores = request.getParameterValues("before");
        if (befores != null) {
            if (befores.length != 1) {
                throw new TransportException(TransportError.BAD_REQUEST, "before is given once");
            }
            before = uuid("before", befores[0]);
        }
        return TransportJson.success(200, service.list(contentId, filter, before, limit, absolute()), null);
    }

    @GetMapping("/{buildId}")
    public ResponseEntity<byte[]> status(@PathVariable UUID contentId, @PathVariable UUID buildId) {
        return TransportJson.success(200, service.status(contentId, buildId, absolute()), null);
    }

    @PostMapping("/{buildId}/cancel")
    public ResponseEntity<byte[]> cancel(@PathVariable UUID contentId, @PathVariable UUID buildId,
                                         HttpServletRequest request) {
        TransportJson.readObject(request);
        return TransportJson.success(202, service.cancel(contentId, buildId, actor(), absolute()), null);
    }

    // ------------------------------------------------------------------ fields

    /** An optional id field: absent or null is none; anything else is a UUID string. */
    private static UUID id(JsonNode body, String field) {
        JsonNode v = body.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        if (!v.isTextual()) {
            throw new TransportException(TransportError.BAD_REQUEST, field + " is a string");
        }
        return uuid(field, v.asText());
    }

    private static UUID uuid(String field, String text) {
        // UUID.fromString accepts short groups ("1-2-3-4-5"); an id is only ever the canonical form.
        if (!text.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
            throw new TransportException(TransportError.INVALID_ID, field + " '"
                    + eu.openanalytics.shinyproxy.publisher.bundle.BundleRejection.printable(text) + "' is not an id");
        }
        return UUID.fromString(text);
    }

    /** The key's form is the admission's rule (KEY_INVALID); only its JSON type is checked here. */
    private static String key(JsonNode body) {
        JsonNode v = body.get("idempotencyKey");
        if (v == null || v.isNull()) {
            return null;
        }
        if (!v.isTextual()) {
            throw new TransportException(TransportError.BAD_REQUEST, "idempotencyKey is a string");
        }
        return v.asText();
    }

    private static UnaryOperator<String> absolute() {
        return path -> ServletUriComponentsBuilder.fromCurrentContextPath().path(path).toUriString();
    }

    private String actor() {
        String user = userService.getCurrentUserId();
        return user == null ? "unknown" : user;
    }
}
