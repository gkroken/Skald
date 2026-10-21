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

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** skald.runtime.*: the defaults, overrides, and each value refused at startup. */
public class RuntimeSettingsTest {

    @Test
    public void defaultsAndOverrides() {
        assertEquals(new RuntimeSettings("2g", "1"), RuntimeSettings.fromOverrides(Map.of()));
        assertEquals(new RuntimeSettings("512m", "0.5"),
            RuntimeSettings.fromOverrides(Map.of("memory-limit", "512M", "cpu_limit", " 0.5 ")));
        assertEquals(6L * 1024 * 1024, RuntimeSettings.bytes("6m"));
        assertEquals(2L * 1024 * 1024 * 1024, RuntimeSettings.bytes("2g"));
        new RuntimeSettings(String.valueOf(6 * 1024 * 1024), "1");
        new RuntimeSettings("6144k", "0.001");
    }

    @Test
    public void badValuesStopStartup() {
        for (String memory : new String[] {"0", "5m", "6291455", "6143k", "2gb", "2 g", "-1g", "1.5g", "g", "",
                "999999999999g"}) {
            assertThrows(IllegalArgumentException.class, () -> new RuntimeSettings(memory, "1"), memory);
        }
        for (String cpus : new String[] {"0", "0.0", "-1", "1.2345", "one", "", "1e3", "10000"}) {
            assertThrows(IllegalArgumentException.class, () -> new RuntimeSettings("2g", cpus), cpus);
        }
        assertThrows(IllegalArgumentException.class, () -> RuntimeSettings.fromOverrides(Map.of("swap", "0")));
    }
}
