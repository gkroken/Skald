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

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T7c step 2: a warm-up only while the host has a slot free; an attempt always. */
public class HostCapacityTest {

    @Test
    public void aWarmUpWaitsWhileRunningAttemptsFillTheHostAndAnAttemptNeverDoes() {
        HostCapacity c = new HostCapacity(2);
        AtomicInteger freed = new AtomicInteger();
        c.onFree(freed::incrementAndGet);
        HostCapacity.Slot a = c.attempt();
        HostCapacity.Slot warm = c.warmUp().orElseThrow();
        assertTrue(c.warmUp().isEmpty(), "an attempt and a warm-up fill two slots");
        HostCapacity.Slot b = c.attempt();
        HostCapacity.Slot third = c.attempt();
        assertEquals(3, c.running(), "attempts are counted, never refused (a cold launch is never held up)");
        warm.close();
        assertTrue(c.warmUp().isEmpty(), "three attempts still fill the host");
        b.close();
        third.close();
        Optional<HostCapacity.Slot> again = c.warmUp();
        assertTrue(again.isPresent(), "a slot freed by an attempt's end");
        a.close();
        a.close();
        assertEquals(0, c.running(), "a slot closed twice is released once");
        assertEquals(4, freed.get(), "each release is announced, once");
        again.get().close();
        assertThrows(IllegalArgumentException.class, () -> new HostCapacity(0));
    }
}
