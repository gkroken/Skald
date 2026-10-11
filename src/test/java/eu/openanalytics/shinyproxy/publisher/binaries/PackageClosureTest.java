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

import eu.openanalytics.shinyproxy.publisher.binaries.BinaryBuildQueue.BuiltDependency;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** One version per package in a closure, chosen by the claimed row's lock (ba5dd88 N1). */
public class PackageClosureTest {

    private static BuiltDependency d(String pkg, String version) {
        return new BuiltDependency(pkg, version, (pkg + version).repeat(64).substring(0, 64));
    }

    @Test
    public void aClosureWithOneVersionEachIsUnchanged() throws Exception {
        List<BuiltDependency> c = List.of(d("aaa", "1.0"), d("bbb", "2.0"));
        assertEquals(c, PackageClosure.resolve(c, Map.of()));
    }

    @Test
    public void theLockedVersionIsKeptAndTheOrderStays() throws Exception {
        List<BuiltDependency> c = List.of(d("aaa", "1.0"), d("rlang", "1.1.3"), d("bbb", "2.0"), d("rlang", "1.1.4"));
        assertEquals(List.of(d("aaa", "1.0"), d("rlang", "1.1.4"), d("bbb", "2.0")),
            PackageClosure.resolve(c, Map.of("rlang", "1.1.4")));
        assertEquals(List.of(d("aaa", "1.0"), d("rlang", "1.1.3"), d("bbb", "2.0")),
            PackageClosure.resolve(c, Map.of("rlang", "1.1.3")));
    }

    @Test
    public void aConflictTheLockDoesNotSettleIsReported() {
        List<BuiltDependency> c = List.of(d("rlang", "1.1.3"), d("rlang", "1.1.4"));
        PackageClosure.Conflict e = assertThrows(PackageClosure.Conflict.class,
            () -> PackageClosure.resolve(c, Map.of("rlang", "1.1.5")));
        assertTrue(e.getMessage().contains("1.1.5"), e.getMessage());
        assertThrows(PackageClosure.Conflict.class, () -> PackageClosure.resolve(c, Map.of()));
    }
}
