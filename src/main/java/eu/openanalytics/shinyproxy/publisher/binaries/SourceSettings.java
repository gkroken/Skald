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
            SafeUrls.requireBase("skald.builds.binaries.source-repository", repository);
        }
    }

    /**
     * The {@code source-} keys of {@code skald.builds.binaries}: sizes with their unit ("64MB"),
     * durations in whole seconds ("30s", "48h"; a bare number is seconds).
     */
    public static final List<String> KEYS = List.of("source-repository", "source-max-index-size", "source-max-size",
            "source-max-expanded-size", "source-fetch-timeout", "source-transfer-deadline", "source-index-ttl",
            "source-behind-retry", "source-behind-max-retry", "source-behind-bound", "source-archived");

    /** True if {@code key} belongs here (a "source-" key) rather than to BinaryQueueSettings. */
    public static boolean owns(String key) {
        return normal(key).startsWith("source-");
    }

    /** Defaults with the "source-" overrides; an unknown one stops startup, naming the known ones. */
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
            String full = "skald.builds.binaries." + key;
            Object value = e.getValue();
            switch (key) {
                case "source-repository" -> {
                    String url = String.valueOf(value).trim();
                    repository = url.isEmpty() ? null : SafeUrls.parse(full, url);
                }
                case "source-archived" -> archived = Settings.bool(full, value);
                case "source-max-index-size" -> index = Settings.size(full, value).toBytes();
                case "source-max-size" -> source = Settings.size(full, value).toBytes();
                case "source-max-expanded-size" -> expanded = Settings.size(full, value).toBytes();
                case "source-fetch-timeout" -> timeout = Settings.seconds(full, value);
                case "source-transfer-deadline" -> deadline = Settings.seconds(full, value);
                case "source-index-ttl" -> ttl = Settings.seconds(full, value);
                case "source-behind-retry" -> retry = Settings.seconds(full, value);
                case "source-behind-max-retry" -> maxRetry = Settings.seconds(full, value);
                case "source-behind-bound" -> bound = Settings.seconds(full, value);
                default -> throw new IllegalArgumentException("unknown setting 'skald.builds.binaries."
                        + e.getKey() + "'; known source settings are " + new TreeSet<>(KEYS));
            }
        }
        return new SourceSettings(repository, index, source, expanded, timeout, deadline, ttl, retry, maxRetry, bound,
                archived);
    }

    private static String normal(String key) {
        return key == null ? "" : key.trim().replace('_', '-').toLowerCase(Locale.ROOT);
    }
}
