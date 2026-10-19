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

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The package-attempt sweep's choice of what to dispose of. */
public class BinaryHousekeepingTest {

    private static final String LIVE = "p" + "a".repeat(32);
    private static final String DISPOSING = "p" + "b".repeat(32);
    private static final String DEAD = "p" + "c".repeat(32);
    private static final String APP_BUILD = "d".repeat(32);

    @Test
    public void anAttemptItsOwnDriverIsDisposingOfIsLeftToIt() {
        // T7c step 3: a finished attempt marked disposing (within its grace) is its driver's to
        // dispose of; a dead one unmarked is the sweep's; a live one and an app build are not.
        List<String> disposed = new ArrayList<>();
        BinaryHousekeeping h = new BinaryHousekeeping(() -> { }, () -> Set.of(LIVE, DISPOSING, DEAD, APP_BUILD),
                () -> Set.of(LIVE), disposed::add, DISPOSING::equals, Duration.ofSeconds(30), Duration.ofMinutes(10));
        assertEquals(List.of(DEAD), h.sweep());
        assertEquals(List.of(DEAD), disposed);
    }

    @Test
    public void aMarkerHoldsTheSweepOffForItsGraceOnly() {
        long now = 1_000_000;
        assertEquals(false, PackageBuildDriver.stillDisposing(java.util.Optional.empty(), now), "unmarked");
        assertEquals(true, PackageBuildDriver.stillDisposing(java.util.Optional.of(now - 60), now), "just marked");
        assertEquals(false, PackageBuildDriver.stillDisposing(java.util.Optional.of(now - 31 * 60), now),
                "a marker a crash left behind no longer holds the sweep off");
    }
}
