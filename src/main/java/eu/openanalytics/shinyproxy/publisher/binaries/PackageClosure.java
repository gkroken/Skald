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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One version per package in a sandbox's dependency closure (ba5dd88 N1). Shared rows are
 * keyed by package and version and resolve their own dependencies against their FIRST
 * asker's lock, so as locks drift apart a closure can hold two versions of one package
 * (dplyr's row built under rlang 1.1.3, the claimed row's lock at 1.1.4). Unpacked into one
 * library, the last would win silently. Instead: the version the claimed row's own lock
 * fixes is kept -- the one that app installs and loads beside these binaries, so the sandbox
 * compiles against what runs -- and R's own version checks at install decide the rest. A
 * conflict the lock does not settle (it fixes neither version) is reported, not guessed.
 */
public final class PackageClosure {

    /** Two versions of a package, neither fixed by the claimed row's lock. */
    public static final class Conflict extends Exception {
        Conflict(String message) {
            super(RDependencies.printable(message));
        }
    }

    private PackageClosure() {
    }

    /**
     * The closure with one entry per package, in the claim's order.
     *
     * @param lock the claimed row's lock (package -> version); may be empty
     */
    public static List<BinaryBuildQueue.BuiltDependency> resolve(List<BinaryBuildQueue.BuiltDependency> closure,
                                                                 Map<String, String> lock) throws Conflict {
        Map<String, List<BinaryBuildQueue.BuiltDependency>> byPackage = new LinkedHashMap<>();
        for (BinaryBuildQueue.BuiltDependency d : closure) {
            byPackage.computeIfAbsent(d.pkg(), k -> new ArrayList<>()).add(d);
        }
        List<BinaryBuildQueue.BuiltDependency> out = new ArrayList<>();
        for (Map.Entry<String, List<BinaryBuildQueue.BuiltDependency>> e : byPackage.entrySet()) {
            List<BinaryBuildQueue.BuiltDependency> versions = e.getValue();
            if (versions.size() == 1) {
                out.add(versions.get(0));
                continue;
            }
            String locked = lock.get(e.getKey());
            BinaryBuildQueue.BuiltDependency chosen = versions.stream().filter(d -> d.version().equals(locked))
                    .findFirst().orElse(null);
            if (chosen == null) {
                throw new Conflict("the closure holds " + e.getKey() + " at " + versions.stream()
                        .map(BinaryBuildQueue.BuiltDependency::version).toList() + " and the lock fixes "
                        + (locked == null ? "none of them" : locked));
            }
            out.add(chosen);
        }
        return out;
    }
}
