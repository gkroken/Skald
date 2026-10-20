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

import eu.openanalytics.shinyproxy.publisher.config.Settings;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

/**
 * How bundle validation runs (WORKPLAN-BUNDLES.md T8 plan): {@code skald.bundle.validation.*}.
 *
 * <p>A VALIDATING bundle is claimed, leased work (6a23bcb-F1), so its lease has the build's
 * reasons and the build's numbers: 300 s renewed every 30 s survives a host that stops
 * scheduling the JVM for minutes (129 s measured, CoordinatorSettings), and a validator that
 * really died hands its bundle to another after at most the lease. Running one validation
 * twice is harmless -- every write is create-only and byte-identical across runs -- so a stall
 * that outlives the lease costs one counted lapse, not a wrong result.
 *
 * <p>The deadline is 30 minutes from entering VALIDATING: room for max-claims (3) lapses of a
 * full lease and the work itself, so a bundle that crashes its validators is rejected for
 * that (VALIDATION_INTERRUPTED) rather than reaching the deadline first and being called
 * STALLED. retry-after is the pause after a storage failure released the claim.
 *
 * @param parallel   validator threads per instance
 * @param workspace  where bundles are extracted to be checked; empty: under java.io.tmpdir
 */
public record ValidationSettings(int parallel, Duration lease, Duration renewEvery,
                                 Duration deadline, int maxClaims, Duration retryAfter,
                                 Optional<Path> workspace) {

    public static final ValidationSettings DEFAULTS = new ValidationSettings(2,
            Duration.ofSeconds(300), Duration.ofSeconds(30), Duration.ofMinutes(30), 3,
            Duration.ofSeconds(15), Optional.empty());

    /** The keys of {@code skald.bundle.validation}. */
    public static final List<String> KEYS = List.of("parallel", "lease", "renew-every", "deadline",
            "max-claims", "retry-after", "workspace");

    public ValidationSettings {
        if (parallel < 1 || maxClaims < 1 || !positive(lease) || !positive(renewEvery)
                || !positive(deadline) || !positive(retryAfter)) {
            throw new IllegalArgumentException("skald.bundle.validation settings must be positive");
        }
        if (renewEvery.multipliedBy(2).compareTo(lease) > 0) {
            throw new IllegalArgumentException("skald.bundle.validation.renew-every (" + renewEvery
                    + ") must be at most half the lease (" + lease + "), or one late renewal"
                    + " expires a healthy validation");
        }
        if (lease.multipliedBy(maxClaims).compareTo(deadline) >= 0) {
            throw new IllegalArgumentException("skald.bundle.validation.deadline (" + deadline
                    + ") must exceed max-claims (" + maxClaims + ") lapses of the lease (" + lease
                    + "), or a bundle that crashes its validators reaches the deadline first and"
                    + " is reported as stalled rather than as interrupted");
        }
        workspace = workspace == null ? Optional.empty() : workspace;
    }

    private static boolean positive(Duration d) {
        return d != null && !d.isNegative() && !d.isZero();
    }

    /** The workspace in effect. */
    public Path workspaceOrDefault() {
        return workspace.orElseGet(() -> Path.of(System.getProperty("java.io.tmpdir"), "skald-validation"));
    }

    /**
     * Defaults with overrides, kebab-case or Spring-relaxed spelling; an unknown key stops
     * startup, naming the known ones.
     */
    public static ValidationSettings fromOverrides(Map<String, ?> overrides) {
        int parallel = DEFAULTS.parallel;
        Duration lease = DEFAULTS.lease;
        Duration renew = DEFAULTS.renewEvery;
        Duration deadline = DEFAULTS.deadline;
        int maxClaims = DEFAULTS.maxClaims;
        Duration retry = DEFAULTS.retryAfter;
        Optional<Path> workspace = DEFAULTS.workspace;
        for (Map.Entry<String, ?> e : overrides.entrySet()) {
            String key = e.getKey() == null ? "" : e.getKey().trim().replace('_', '-')
                    .toLowerCase(Locale.ROOT);
            String name = "skald.bundle.validation." + key;
            switch (key) {
                case "parallel" -> parallel = Settings.integer(name, e.getValue());
                case "lease" -> lease = Settings.seconds(name, e.getValue());
                case "renew-every" -> renew = Settings.seconds(name, e.getValue());
                case "deadline" -> deadline = Settings.seconds(name, e.getValue());
                case "max-claims" -> maxClaims = Settings.integer(name, e.getValue());
                case "retry-after" -> retry = Settings.seconds(name, e.getValue());
                case "workspace" -> {
                    String text = e.getValue() == null ? "" : String.valueOf(e.getValue()).trim();
                    workspace = text.isEmpty() ? Optional.empty() : Optional.of(Path.of(text));
                }
                default -> throw new IllegalArgumentException("unknown setting 'skald.bundle.validation."
                        + e.getKey() + "'; known settings are " + new TreeSet<>(KEYS));
            }
        }
        return new ValidationSettings(parallel, lease, renew, deadline, maxClaims, retry, workspace);
    }
}
