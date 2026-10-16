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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PhaseClockTest {

    @Test
    public void lapsAreTheTimeSinceThePreviousLapAndARepeatedPhaseAddsUp() throws Exception {
        PhaseClock c = new PhaseClock();
        Thread.sleep(30);
        c.lap("a");
        Thread.sleep(20);
        c.lap("b");
        Thread.sleep(20);
        c.lap("a");
        Map<String, Double> s = c.seconds();
        assertEquals(List.of("a", "b"), List.copyOf(s.keySet()));
        assertTrue(s.get("a") >= 0.05, s.toString());
        assertTrue(s.get("b") >= 0.02 && s.get("b") < s.get("a"), s.toString());
    }

    @Test
    public void thePassProbeReadsTheLineTheDriverWrites() throws Exception {
        PhaseClock c = new PhaseClock();
        c.lap("prepare");
        c.lap("launch.conf");
        String line = "[skald] " + BuildKitDriver.PHASES + c.summary();
        assertTrue(line.matches("\\[skald\\] phases: total [0-9]+\\.[0-9] s: prepare [0-9.]+, launch\\.conf [0-9.]+"), line);
        Path log = Files.createTempFile("phases", ".jsonl");
        try {
            Files.writeString(log, new ObjectMapper().createObjectNode().put("t", line) + "\n");
            var parsed = T7PassProbeTest.phases(log);
            assertTrue(parsed.has("total") && parsed.has("prepare") && parsed.has("launch.conf"), parsed.toString());
        } finally {
            Files.delete(log);
        }
    }
}
