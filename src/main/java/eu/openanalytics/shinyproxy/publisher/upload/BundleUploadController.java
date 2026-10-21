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

import eu.openanalytics.containerproxy.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * bundle.create, bundle.upload, bundle.status (spec/admin-transport-v1.json).
 *
 * <p><b>Authorization is inherited</b>: every path is under {@code /admin}, which
 * UISecurityConfig gates on isAdmin, as for spine #1's ContentAdminController. A non-admin
 * gets upstream's 403 and a signed-out caller its redirect to log in.
 *
 * <p><b>The CSRF rule is the whole defence</b> (T1): POST takes application/json only, the
 * upload application/gzip plus {@code X-Skald-Upload}, and each is checked in code so that a
 * form type, an untyped body and a missing header are refused in this transport's envelope.
 *
 * <p><b>Ids are looked up as a pair.</b> A bundle of another content item is BUNDLE_NOT_FOUND,
 * identical to one that does not exist.
 */
@RestController
@RequestMapping("/admin/content/{contentId}/bundles")
@ConditionalOnProperty(name = {"spring.datasource.url", "skald.storage.endpoint"})
public class BundleUploadController extends TransportController {

    static final String UPLOAD_HEADER = "X-Skald-Upload";
    static final MediaType GZIP = MediaType.parseMediaType("application/gzip");

    private final BundleService service;
    private final UserService userService;

    public BundleUploadController(BundleService service, @Lazy UserService userService) {
        this.service = service;
        this.userService = userService;
    }

    @PostMapping
    public ResponseEntity<byte[]> create(@PathVariable UUID contentId, HttpServletRequest request) {
        TransportJson.readObject(request);
        Map<String, Object> bundle = service.create(contentId, actor());
        UUID id = UUID.fromString((String) bundle.get("id"));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", bundle.get("id"));
        data.put("contentId", bundle.get("contentId"));
        data.put("state", bundle.get("state"));
        data.put("uploadUrl", url(contentId, id, "/content").toString());
        data.put("uploadDeadline", bundle.get("uploadDeadline"));
        data.put("maxBytes", service.maxBytes());
        HttpHeaders headers = new HttpHeaders();
        headers.setLocation(url(contentId, id, ""));
        return TransportJson.success(201, data, headers);
    }

    @PutMapping("/{bundleId}/content")
    public ResponseEntity<byte[]> upload(@PathVariable UUID contentId, @PathVariable UUID bundleId,
                                         HttpServletRequest request,
                                         jakarta.servlet.http.HttpServletResponse response) throws IOException {
        try {
            return receive(contentId, bundleId, request);
        } catch (TransportException e) {
            // A refusal may come with most of the body still unsent (a store that failed half way,
            // a size refused before a byte was read). Without this the server neither reads the
            // rest nor closes, and a client that writes the whole body before reading the reply
            // waits on its own timeout (found probing a stopped MinIO, f859b52 (f)). Closed, its
            // next write fails at once and the reply is there to read.
            response.setHeader(HttpHeaders.CONNECTION, "close");
            throw e;
        }
    }

    private ResponseEntity<byte[]> receive(UUID contentId, UUID bundleId, HttpServletRequest request)
            throws IOException {
        if (request.getHeader(UPLOAD_HEADER) == null) {
            throw new TransportException(TransportError.UPLOAD_HEADER_MISSING,
                    "an upload carries the " + UPLOAD_HEADER + " header, which no cross-site form can set");
        }
        TransportJson.requireMediaType(request, GZIP);
        long length = request.getContentLengthLong();
        if (length < 0) {
            throw new TransportException(TransportError.LENGTH_REQUIRED,
                    "an upload declares its Content-Length, so a bundle over the limit is refused before it is sent");
        }
        if (length > service.maxBytes()) {
            throw new TransportException(TransportError.BUNDLE_TOO_LARGE,
                    "the bundle is " + length + " bytes and this deployment accepts at most " + service.maxBytes())
                    .with("maxBytes", service.maxBytes());
        }
        try (InputStream body = request.getInputStream()) {
            return TransportJson.success(200, service.upload(contentId, bundleId, actor(), length, body), null);
        }
    }

    @GetMapping("/{bundleId}")
    public ResponseEntity<byte[]> status(@PathVariable UUID contentId, @PathVariable UUID bundleId) {
        return TransportJson.success(200, service.status(contentId, bundleId), null);
    }

    private static URI url(UUID contentId, UUID bundleId, String suffix) {
        return ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/admin/content/{c}/bundles/{b}" + suffix).buildAndExpand(contentId, bundleId).toUri();
    }

    private String actor() {
        String user = userService.getCurrentUserId();
        return user == null ? "unknown" : user;
    }
}
