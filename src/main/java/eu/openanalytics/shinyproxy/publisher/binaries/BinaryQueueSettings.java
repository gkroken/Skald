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

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * The binary queue's timing and limits ({@code skald.builds.binaries.*}): a 60 s lease renewed
 * every 20 s, as for app builds; a 60-minute deadline, since one large package (arrow, duckdb)
 * can compile for half an hour on its own; ONE compile at a time beside the app builds; and
 * two claims per phase before a row is FAILED, so a package that never builds stops costing
 * CPU and its apps keep restoring it from source.
 */
public record BinaryQueueSettings(Duration lease, Duration renewEvery, Duration deadline,
                                  int maxRunning, int maxAttempts) {

    public static final BinaryQueueSettings DEFAULTS = new BinaryQueueSettings(
            Duration.ofSeconds(60), Duration.ofSeconds(20), Duration.ofMinutes(60), 1, 2);

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
     * Defaults with overrides: lease-seconds, renew-every-seconds, deadline-minutes,
     * max-running, max-attempts. An unknown key stops startup rather than being ignored.
     */
    public static BinaryQueueSettings fromOverrides(Map<String, ?> overrides) {
        Duration lease = DEFAULTS.lease;
        Duration renew = DEFAULTS.renewEvery;
        Duration deadline = DEFAULTS.deadline;
        int running = DEFAULTS.maxRunning;
        int attempts = DEFAULTS.maxAttempts;
        for (Map.Entry<String, ?> e : overrides.entrySet()) {
            String key = e.getKey() == null ? "" : e.getKey().trim().replace('-', '_')
                    .toLowerCase(Locale.ROOT);
            long value;
            try {
                value = Long.parseLong(String.valueOf(e.getValue()).trim());
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("binary queue setting '" + e.getKey()
                        + "' is not an integer: " + e.getValue());
            }
            switch (key) {
                case "lease_seconds" -> lease = Duration.ofSeconds(value);
                case "renew_every_seconds" -> renew = Duration.ofSeconds(value);
                case "deadline_minutes" -> deadline = Duration.ofMinutes(value);
                case "max_running" -> running = Math.toIntExact(value);
                case "max_attempts" -> attempts = Math.toIntExact(value);
                default -> throw new IllegalArgumentException("unknown binary queue setting '"
                        + e.getKey() + "'; known settings are " + new TreeSet<>(List.of("lease_seconds",
                        "renew_every_seconds", "deadline_minutes", "max_running", "max_attempts")));
            }
        }
        return new BinaryQueueSettings(lease, renew, deadline, running, attempts);
    }
}
