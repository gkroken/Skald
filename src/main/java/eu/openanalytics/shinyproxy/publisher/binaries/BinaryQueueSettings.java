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

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * The binary queue's timing and limits ({@code skald.builds.binaries.*}): a 300 s lease renewed
 * every 30 s, as for app builds (see
 * {@link eu.openanalytics.shinyproxy.publisher.build.CoordinatorSettings} for why a holder must
 * survive a stalled host); a 180-minute deadline, since one large package compiles for a long
 * time on its own (duckdb about two hours on a busy 2026 dev host); ONE compile at a time
 * beside the app builds; and two claims per phase before a row is FAILED, so a package that
 * never builds stops costing CPU and its apps keep restoring it from source.
 */
public record BinaryQueueSettings(Duration lease, Duration renewEvery, Duration deadline,
                                  int maxRunning, int maxAttempts) {

    public static final BinaryQueueSettings DEFAULTS = new BinaryQueueSettings(
            Duration.ofSeconds(300), Duration.ofSeconds(30), Duration.ofMinutes(180), 1, 2);

    public BinaryQueueSettings {
        if (lease.isNegative() || lease.isZero() || renewEvery.isNegative() || renewEvery.isZero()
                || deadline.isNegative() || deadline.isZero() || maxRunning < 1 || maxAttempts < 1) {
            throw new IllegalArgumentException("binary queue settings must be positive");
        }
        if (renewEvery.multipliedBy(2).compareTo(lease) > 0) {
            throw new IllegalArgumentException("renew-every (" + renewEvery + ") must be at most"
                    + " half the lease (" + lease + "), or one late renewal expires a healthy compile");
        }
    }

    /**
     * The queue's own keys of {@code skald.builds.binaries} (the {@code forge-} and
     * {@code source-} keys belong to {@link ForgeSettings} and {@link SourceSettings}); the
     * durations are whole seconds ("300s", "3h"; a bare number is seconds).
     */
    public static final List<String> KEYS = List.of("lease", "renew-every", "deadline", "max-running", "max-attempts");

    /** Defaults with overrides ({@link #KEYS}); an unknown key stops startup, naming the known ones. */
    public static BinaryQueueSettings fromOverrides(Map<String, ?> overrides) {
        Duration lease = DEFAULTS.lease;
        Duration renew = DEFAULTS.renewEvery;
        Duration deadline = DEFAULTS.deadline;
        int running = DEFAULTS.maxRunning;
        int attempts = DEFAULTS.maxAttempts;
        for (Map.Entry<String, ?> e : overrides.entrySet()) {
            String key = e.getKey() == null ? "" : e.getKey().trim().replace('_', '-')
                    .toLowerCase(Locale.ROOT);
            String full = "skald.builds.binaries." + key;
            switch (key) {
                case "lease" -> lease = Settings.seconds(full, e.getValue());
                case "renew-every" -> renew = Settings.seconds(full, e.getValue());
                case "deadline" -> deadline = Settings.seconds(full, e.getValue());
                case "max-running" -> running = Settings.integer(full, e.getValue());
                case "max-attempts" -> attempts = Settings.integer(full, e.getValue());
                default -> throw new IllegalArgumentException("unknown setting 'skald.builds.binaries."
                        + e.getKey() + "'; known settings are " + new TreeSet<>(KEYS)
                        + ", and the forge- and source- settings");
            }
        }
        return new BinaryQueueSettings(lease, renew, deadline, running, attempts);
    }
}
