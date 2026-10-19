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

import eu.openanalytics.shinyproxy.publisher.config.Settings;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Where Skald's shared binaries live in forge ({@code skald.builds.binaries.forge-*}): forge's
 * base URL, the binary repository (forge's own example name, cran-linux), the write token, and
 * how long a platform registration is trusted before it is read again.
 *
 * <p>The token is scoped by the operator to exactly {@code bin/linux/<codename>-<arch>/contrib/<rminor>/**}
 * of the binary repository (c365d95 N3) and is a secret like the registry password: given by
 * the deployment's configuration (its environment), never in the database, never logged --
 * {@link #toString} masks it -- and never handed to any container (only the trusted side's
 * HTTP client sends it). Unset URL: shared binaries are off.
 */
public record ForgeSettings(URI url, String binaryRepository, String token, Duration registrationTtl,
                            Duration timeout, Duration uploadDeadline) {

    // Before DEFAULTS: static fields initialise in order, and DEFAULTS's constructor uses it.
    private static final Pattern REPOSITORY = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");

    public static final ForgeSettings DEFAULTS = new ForgeSettings(null, "cran-linux", null,
            Duration.ofSeconds(60), Duration.ofSeconds(30), Duration.ofMinutes(10));

    /**
     * Settings with the default upload deadline. {@code timeout} bounds connecting and every
     * small exchange (a registration); {@code uploadDeadline} bounds an upload and the 409
     * path's read of the existing binary, whose time grows with the binary (ae287bf N1).
     */
    public ForgeSettings(URI url, String binaryRepository, String token, Duration registrationTtl, Duration timeout) {
        this(url, binaryRepository, token, registrationTtl, timeout, Duration.ofMinutes(10));
    }

    public ForgeSettings {
        if (url != null) {
            SafeUrls.requireBase("skald.builds.binaries.forge-url", url);
            if (token == null || token.isBlank() || token.chars().anyMatch(c -> c < 0x21 || c > 0x7e)) {
                throw new IllegalArgumentException(
                        "skald.builds.binaries.forge-token is required with forge-url, as printable ASCII");
            }
        }
        if (binaryRepository == null || !REPOSITORY.matcher(binaryRepository).matches()) {
            throw new IllegalArgumentException("skald.builds.binaries.forge-binary-repository is not a"
                    + " repository name: " + binaryRepository);
        }
        if (registrationTtl.isNegative() || timeout.isNegative() || timeout.isZero() || uploadDeadline.isNegative()
                || uploadDeadline.isZero()) {
            throw new IllegalArgumentException("forge settings must be positive");
        }
    }

    /**
     * The {@code forge-} keys of {@code skald.builds.binaries}; the durations are whole seconds.
     * The token's value is never parsed by {@link Settings}, whose errors repeat the value.
     */
    public static final List<String> KEYS = List.of("forge-url", "forge-binary-repository", "forge-token",
            "forge-registration-ttl", "forge-timeout", "forge-upload-deadline");

    /** True if {@code key} belongs here (a "forge-" key). */
    public static boolean owns(String key) {
        return normal(key).startsWith("forge-");
    }

    public static ForgeSettings fromOverrides(Map<String, ?> overrides) {
        URI url = DEFAULTS.url;
        String repo = DEFAULTS.binaryRepository;
        String token = DEFAULTS.token;
        Duration ttl = DEFAULTS.registrationTtl;
        Duration timeout = DEFAULTS.timeout;
        Duration upload = DEFAULTS.uploadDeadline;
        for (Map.Entry<String, ?> e : overrides.entrySet()) {
            String key = normal(e.getKey());
            String full = "skald.builds.binaries." + key;
            String value = String.valueOf(e.getValue()).trim();
            switch (key) {
                case "forge-url" -> url = value.isEmpty() ? null : SafeUrls.parse(full, value);
                case "forge-binary-repository" -> repo = value;
                case "forge-token" -> token = value.isEmpty() ? null : value;
                case "forge-registration-ttl" -> ttl = Settings.seconds(full, value);
                case "forge-timeout" -> timeout = Settings.seconds(full, value);
                case "forge-upload-deadline" -> upload = Settings.seconds(full, value);
                default -> throw new IllegalArgumentException("unknown setting 'skald.builds.binaries."
                        + e.getKey() + "'; known forge settings are " + new TreeSet<>(KEYS));
            }
        }
        return new ForgeSettings(url, repo, token, ttl, timeout, upload);
    }

    private static String normal(String key) {
        return key == null ? "" : key.trim().replace('_', '-').toLowerCase(Locale.ROOT);
    }

    @Override
    public String toString() {
        return "ForgeSettings[url=" + url + ", binaryRepository=" + binaryRepository + ", token="
                + (token == null ? "unset" : "****") + ", registrationTtl=" + registrationTtl + ", timeout=" + timeout
                + ", uploadDeadline=" + uploadDeadline + "]";
    }
}
