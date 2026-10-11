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
package eu.openanalytics.shinyproxy.publisher.binaries;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * forge's platform registrations, read with Skald's own token (forge issue 004, fixed in forge
 * 8545709: GET /api/v1/cran/platforms/{key} with any valid token; 404 for an unknown key; 401
 * without a valid token). Read before each R build and each upload, cached for
 * {@code registrationTtl} (c365d95 N1: an admin can re-register a key while Skald runs); a
 * failed read is never cached and never taken as a registration.
 */
public final class ForgePlatforms {

    /** What forge registers for a key: the builder image uploads must report, and R's platform. */
    public record Registration(String key, String rPlatform, String builderImage, String builderDigest) { }

    private static final Pattern DIGEST = Pattern.compile("sha256:[0-9a-f]{64}");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_BYTES = 1 << 20;

    private record Cached(Optional<Registration> registration, long at) { }

    private final ForgeSettings settings;
    private final HttpClient http;
    private final LongSupplier nanoTime;
    private final Map<String, Cached> cache = new HashMap<>();

    public ForgePlatforms(ForgeSettings settings) {
        this(settings, System::nanoTime);
    }

    ForgePlatforms(ForgeSettings settings, LongSupplier nanoTime) {
        if (settings.url() == null) {
            throw new IllegalArgumentException("no forge-url is configured");
        }
        this.settings = settings;
        this.nanoTime = nanoTime;
        this.http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(settings.timeout()).build();
    }

    /**
     * The registration of {@code key}, empty if forge has none.
     *
     * @throws IOException forge could not be read, refused the token, or answered something
     *                     that is not this key's registration
     */
    public Optional<Registration> registration(String key) throws IOException {
        synchronized (cache) {
            Cached c = cache.get(key);
            if (c != null && nanoTime.getAsLong() - c.at() < settings.registrationTtl().toNanos()) {
                return c.registration();
            }
        }
        long at = nanoTime.getAsLong();
        Optional<Registration> read = read(key);
        synchronized (cache) {
            cache.put(key, new Cached(read, at));
        }
        return read;
    }

    private Optional<Registration> read(String key) throws IOException {
        URI uri = settings.url().resolve("api/v1/cran/platforms/" + key);
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(settings.timeout()).GET()
                .header("Authorization", "Bearer " + settings.token()).header("Accept", "application/json").build();
        HttpResponse<InputStream> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new java.io.InterruptedIOException("interrupted reading " + uri);
        }
        byte[] body;
        try (InputStream in = response.body()) {
            body = in.readNBytes(MAX_BYTES + 1);
        }
        int status = response.statusCode();
        if (status == 404) {
            return Optional.empty();
        }
        if (status != 200) {
            // 401/403 is a configuration error (the token); never a registration, never cached
            // as one. The body is forge's, not echoed: only the status.
            throw new IOException("forge answered " + status + " for the platform registration of " + key);
        }
        if (body.length > MAX_BYTES) {
            throw new IOException("forge's registration of " + key + " is over " + MAX_BYTES + " bytes");
        }
        JsonNode n = JSON.readTree(body);
        String rPlatform = n == null ? null : n.path("rPlatform").asText(null);
        String builderDigest = n == null ? null : n.path("builderDigest").asText(null);
        if (n == null || !key.equals(n.path("key").asText(null)) || rPlatform == null || builderDigest == null
                || !DIGEST.matcher(builderDigest).matches()) {
            throw new IOException("forge's answer is not the registration of " + key);
        }
        return Optional.of(new Registration(key, rPlatform, n.path("builderImage").asText(""), builderDigest));
    }
}
