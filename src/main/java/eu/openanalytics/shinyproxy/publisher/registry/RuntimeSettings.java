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
package eu.openanalytics.shinyproxy.publisher.registry;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * The limits a built version runs with (WORKPLAN-BUNDLES.md T9 (a)): {@code skald.runtime.*}.
 *
 * <p>Policy, not a fact of the image, so it is the deployment's and not the version's: an
 * operator who raises the memory limit does not rebuild every app, and the next container
 * start uses the new value. Applied to built versions only -- their code is a publisher's,
 * untrusted -- and never to an admin's direct image, which keeps the engine's behaviour.
 *
 * @param memoryLimit Docker's memory format: a whole number with an optional b, k, m or g
 * @param cpuLimit    CPUs, a positive decimal (Docker's {@code --cpus})
 */
public record RuntimeSettings(String memoryLimit, String cpuLimit) {

    /** The keys of {@code skald.runtime}. */
    public static final List<String> KEYS = List.of("memory-limit", "cpu-limit");

    private static final Pattern MEMORY = Pattern.compile("[1-9][0-9]{0,12}[bkmg]?");
    private static final Pattern CPUS = Pattern.compile("(0|[1-9][0-9]{0,3})(\\.[0-9]{1,3})?");

    // After the patterns it is checked against: static fields initialise in order.
    public static final RuntimeSettings DEFAULTS = new RuntimeSettings("2g", "1");

    public RuntimeSettings {
        if (memoryLimit == null || !MEMORY.matcher(memoryLimit).matches()) {
            throw new IllegalArgumentException("skald.runtime.memory-limit '" + memoryLimit
                    + "' is not a memory size: a whole number with an optional b, k, m or g (e.g. 2g)");
        }
        long size;
        try {
            size = bytes(memoryLimit);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("skald.runtime.memory-limit '" + memoryLimit + "' is too large");
        }
        if (size < 6L * 1024 * 1024) {
            throw new IllegalArgumentException("skald.runtime.memory-limit '" + memoryLimit
                    + "' is below Docker's minimum of 6m");
        }
        if (cpuLimit == null || !CPUS.matcher(cpuLimit).matches() || Double.parseDouble(cpuLimit) <= 0) {
            throw new IllegalArgumentException("skald.runtime.cpu-limit '" + cpuLimit
                    + "' is not a positive number of CPUs (e.g. 1 or 0.5)");
        }
    }

    /** A size in Docker's memory format, in bytes. */
    static long bytes(String size) {
        char unit = size.charAt(size.length() - 1);
        long scale = switch (unit) {
            case 'k' -> 1024L;
            case 'm' -> 1024L * 1024;
            case 'g' -> 1024L * 1024 * 1024;
            default -> 1L;
        };
        String digits = Character.isDigit(unit) ? size : size.substring(0, size.length() - 1);
        return Math.multiplyExact(Long.parseLong(digits), scale);
    }

    /** Defaults with overrides; an unknown key stops startup, naming the known ones. */
    public static RuntimeSettings fromOverrides(Map<String, ?> overrides) {
        String memory = DEFAULTS.memoryLimit;
        String cpus = DEFAULTS.cpuLimit;
        for (Map.Entry<String, ?> e : overrides.entrySet()) {
            String key = e.getKey() == null ? "" : e.getKey().trim().replace('_', '-').toLowerCase(Locale.ROOT);
            String value = e.getValue() == null ? null : String.valueOf(e.getValue()).trim().toLowerCase(Locale.ROOT);
            switch (key) {
                case "memory-limit" -> memory = value;
                case "cpu-limit" -> cpus = value;
                default -> throw new IllegalArgumentException("unknown setting 'skald.runtime." + e.getKey()
                        + "'; known settings are " + new TreeSet<>(KEYS));
            }
        }
        return new RuntimeSettings(memory, cpus);
    }
}
