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
package eu.openanalytics.shinyproxy.publisher.build;

import eu.openanalytics.shinyproxy.publisher.config.Settings;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * The coordinator's timing and concurrency (WORKPLAN-BUNDLES.md T6, decisions 2 and 4 of
 * 2026-10-01): lease 300 s renewed every 30 s, one running build at a time, and the plan's
 * 20-minute wall deadline. Configurable, positive only, with renewal well inside the lease --
 * a renewal interval at or above the lease would let every healthy build expire.
 *
 * <p>The lease was 60 s renewed every 20 s until 2026-10-18. On a busy host the whole JVM went
 * unscheduled for up to 129 s at a time, and the reaper, starved with it, ran before the
 * renewals and expired three healthy package compiles at once, duckdb twice. A holder now
 * survives a stall of 270 s (the lease less one renewal interval); the cost is that an
 * instance that really died holds its build that much longer before it is INTERRUPTED.
 */
public record CoordinatorSettings(Duration lease, Duration renewEvery, Duration deadline,
                                  int maxRunning) {

    public static final CoordinatorSettings DEFAULTS = new CoordinatorSettings(
            Duration.ofSeconds(300), Duration.ofSeconds(30), Duration.ofMinutes(20), 1);

    public CoordinatorSettings {
        if (lease.isNegative() || lease.isZero() || renewEvery.isNegative() || renewEvery.isZero()
                || deadline.isNegative() || deadline.isZero() || maxRunning < 1) {
            throw new IllegalArgumentException("coordinator settings must be positive");
        }
        if (renewEvery.multipliedBy(2).compareTo(lease) > 0) {
            throw new IllegalArgumentException("renew-every (" + renewEvery + ") must be at most"
                    + " half the lease (" + lease + "), or one late renewal expires a healthy build");
        }
    }

    /**
     * The keys of {@code skald.builds.coordinator}; values are durations in whole seconds
     * ("300s", "20m"; a bare number is seconds).
     */
    public static final List<String> KEYS = List.of("lease", "renew-every", "deadline");

    /**
     * Defaults with overrides: {@code skald.builds.coordinator.*} ({@link #KEYS}) and the
     * deployment's {@code skald.builds.max-running} (null: the default). Keys in kebab-case or
     * Spring-relaxed spelling; an unknown key stops startup, naming the known ones.
     */
    public static CoordinatorSettings fromOverrides(Map<String, ?> overrides, Object maxRunning) {
        Duration lease = DEFAULTS.lease;
        Duration renew = DEFAULTS.renewEvery;
        Duration deadline = DEFAULTS.deadline;
        int running = DEFAULTS.maxRunning;
        for (Map.Entry<String, ?> e : overrides.entrySet()) {
            String key = e.getKey() == null ? "" : e.getKey().trim().replace('_', '-')
                    .toLowerCase(Locale.ROOT);
            switch (key) {
                case "lease" -> lease = Settings.seconds("skald.builds.coordinator.lease", e.getValue());
                case "renew-every" -> renew = Settings.seconds("skald.builds.coordinator.renew-every", e.getValue());
                case "deadline" -> deadline = Settings.seconds("skald.builds.coordinator.deadline", e.getValue());
                default -> throw new IllegalArgumentException("unknown setting 'skald.builds.coordinator."
                        + e.getKey() + "'; known settings are " + new TreeSet<>(KEYS)
                        + " (the deployment's build limit is skald.builds.max-running)");
            }
        }
        if (maxRunning != null) {
            running = Settings.integer("skald.builds.max-running", maxRunning);
        }
        return new CoordinatorSettings(lease, renew, deadline, running);
    }
}
