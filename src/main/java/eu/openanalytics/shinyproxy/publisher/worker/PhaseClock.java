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
package eu.openanalytics.shinyproxy.publisher.worker;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Where an attempt's time went (T7b, the fixed cost): named phases, each the time since the
 * previous lap, on the monotonic clock. A phase lapped twice adds up. The work thread laps,
 * the caller reads after joining it; both are synchronized, so a read during a stop sees a
 * consistent prefix.
 */
final class PhaseClock {

    private final long start = System.nanoTime();
    private long mark = start;
    private final Map<String, Long> phases = new LinkedHashMap<>();

    /** Ends the phase running since the previous lap (or the clock's start) as {@code phase}. */
    synchronized void lap(String phase) {
        long now = System.nanoTime();
        phases.merge(phase, now - mark, Long::sum);
        mark = now;
    }

    /** The phases so far, in the order first lapped, in seconds. */
    synchronized Map<String, Double> seconds() {
        Map<String, Double> out = new LinkedHashMap<>();
        phases.forEach((k, v) -> out.put(k, v / 1e9));
        return out;
    }

    /** "total 41.2 s: prepare 0.3, launch.conf 2.9, ..." -- one line, tenths of a second. */
    synchronized String summary() {
        StringBuilder b = new StringBuilder(String.format(java.util.Locale.ROOT, "total %.1f s:",
                (mark - start) / 1e9));
        String sep = " ";
        for (Map.Entry<String, Long> e : phases.entrySet()) {
            b.append(sep).append(e.getKey()).append(' ')
                    .append(String.format(java.util.Locale.ROOT, "%.1f", e.getValue() / 1e9));
            sep = ", ";
        }
        return b.toString();
    }
}
