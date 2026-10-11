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

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * Where and how the trusted side reads public sources ({@code skald.builds.binaries.source-*}).
 *
 * <p>{@code source-repository} is the base URL of forge's CRAN PROXY member, the repository
 * that fronts CRAN itself -- never the group, which also serves the operator's hosted packages
 * (WORKPLAN-BUNDLES T7b step 2, point 2). Unset, nothing verifies sources (the default: shared
 * binaries are off until a deployment enables them after the security review).
 */
public record SourceSettings(URI repository, long maxIndexBytes, long maxSourceBytes, long maxExpandedBytes,
                             Duration fetchTimeout, Duration transferDeadline, Duration indexTtl,
                             Duration behindRetry, Duration behindMaxRetry, Duration behindBound,
                             boolean archived) {

    /**
     * An index behind the lock (9262bd8-F1) is retried after 5 minutes, doubling to at most 6
     * hours, and refused INDEX_BEHIND 48 hours after the first time: forge caches a proxy's
     * metadata for 24 hours by default.
     */
    public static final SourceSettings DEFAULTS = new SourceSettings(null, 64L << 20, 256L << 20, 2L << 30,
            Duration.ofSeconds(30), Duration.ofMinutes(10), Duration.ofMinutes(5),
            Duration.ofMinutes(5), Duration.ofHours(6), Duration.ofHours(48), false);

    /**
     * {@code source-archived} (user, 2026-10-11; off by default): build ARCHIVED versions too
     * -- the ones the public index no longer lists, so no published checksum exists -- from the
     * proxy's src/contrib/Archive path, trusting the first fetch (its SHA-256 is recorded and
     * never changes; a later different fetch is refused) and recording source_kind 'archive'.
     */
    public SourceSettings withArchived(boolean on) {
        return new SourceSettings(repository, maxIndexBytes, maxSourceBytes, maxExpandedBytes, fetchTimeout,
                transferDeadline, indexTtl, behindRetry, behindMaxRetry, behindBound, on);
    }

    /** These settings with other limits for an index behind the lock. */
    public SourceSettings withBehind(Duration retry, Duration maxRetry, Duration bound) {
        return new SourceSettings(repository, maxIndexBytes, maxSourceBytes, maxExpandedBytes, fetchTimeout,
                transferDeadline, indexTtl, retry, maxRetry, bound, archived);
    }

    /** The defaults' limits for an index behind the lock. */
    public SourceSettings(URI repository, long maxIndexBytes, long maxSourceBytes, long maxExpandedBytes,
                          Duration fetchTimeout, Duration transferDeadline, Duration indexTtl) {
        this(repository, maxIndexBytes, maxSourceBytes, maxExpandedBytes, fetchTimeout, transferDeadline, indexTtl,
                Duration.ofMinutes(5), Duration.ofHours(6), Duration.ofHours(48), false);
    }

    public SourceSettings {
        if (maxIndexBytes < 1 || maxSourceBytes < 1 || maxExpandedBytes < 1 || fetchTimeout.isNegative()
                || fetchTimeout.isZero() || transferDeadline.isNegative() || transferDeadline.isZero()
                || indexTtl.isNegative() || behindRetry.isNegative() || behindRetry.isZero()
                || behindMaxRetry.compareTo(behindRetry) < 0 || behindBound.isNegative()) {
            throw new IllegalArgumentException("source settings must be positive");
        }
        if (repository != null) {
            SafeUrls.requireBase("source-repository", repository);
        }
    }

    /** True if {@code key} belongs here (a "source-" key) rather than to BinaryQueueSettings. */
    public static boolean owns(String key) {
        return normal(key).startsWith("source_");
    }

    /** Defaults with the "source-" overrides; an unknown one stops startup. */
    public static SourceSettings fromOverrides(Map<String, ?> overrides) {
        URI repository = DEFAULTS.repository;
        long index = DEFAULTS.maxIndexBytes;
        long source = DEFAULTS.maxSourceBytes;
        long expanded = DEFAULTS.maxExpandedBytes;
        Duration timeout = DEFAULTS.fetchTimeout;
        Duration deadline = DEFAULTS.transferDeadline;
        Duration ttl = DEFAULTS.indexTtl;
        Duration retry = DEFAULTS.behindRetry;
        Duration maxRetry = DEFAULTS.behindMaxRetry;
        Duration bound = DEFAULTS.behindBound;
        boolean archived = DEFAULTS.archived;
        for (Map.Entry<String, ?> e : overrides.entrySet()) {
            String key = normal(e.getKey());
            String value = String.valueOf(e.getValue()).trim();
            if (key.equals("source_archived")) {
                if (!value.equals("true") && !value.equals("false")) {
                    throw new IllegalArgumentException("source-archived is true or false, not " + value);
                }
                archived = Boolean.parseBoolean(value);
                continue;
            }
            if (key.equals("source_repository")) {
                repository = value.isEmpty() ? null : SafeUrls.parse("source-repository", value);
                continue;
            }
            long n;
            try {
                n = Long.parseLong(value);
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("binary source setting '" + e.getKey()
                        + "' is not an integer: " + e.getValue());
            }
            switch (key) {
                case "source_max_index_bytes" -> index = n;
                case "source_max_bytes" -> source = n;
                case "source_max_expanded_bytes" -> expanded = n;
                case "source_fetch_timeout_seconds" -> timeout = Duration.ofSeconds(n);
                case "source_transfer_deadline_seconds" -> deadline = Duration.ofSeconds(n);
                case "source_index_ttl_seconds" -> ttl = Duration.ofSeconds(n);
                case "source_behind_retry_seconds" -> retry = Duration.ofSeconds(n);
                case "source_behind_max_retry_seconds" -> maxRetry = Duration.ofSeconds(n);
                case "source_behind_bound_hours" -> bound = Duration.ofHours(n);
                default -> throw new IllegalArgumentException("unknown binary source setting '" + e.getKey()
                        + "'; known settings are " + new TreeSet<>(List.of("source_repository",
                        "source_max_index_bytes", "source_max_bytes", "source_max_expanded_bytes",
                        "source_fetch_timeout_seconds", "source_transfer_deadline_seconds",
                        "source_index_ttl_seconds", "source_behind_retry_seconds",
                        "source_behind_max_retry_seconds", "source_behind_bound_hours", "source_archived")));
            }
        }
        return new SourceSettings(repository, index, source, expanded, timeout, deadline, ttl, retry, maxRetry, bound,
                archived);
    }

    private static String normal(String key) {
        return key == null ? "" : key.trim().replace('-', '_').toLowerCase(Locale.ROOT);
    }
}
