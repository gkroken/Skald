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
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class DisposerTest {

    @Test
    @Timeout(10)
    public void closeWaitsForPendingDisposalsAndAFailedOneDoesNotStopTheOthers() throws Exception {
        Disposer d = new Disposer(1);
        AtomicBoolean second = new AtomicBoolean();
        d.submit("failing", () -> {
            throw new IllegalStateException("simulated");
        });
        d.submit("slow", () -> {
            Thread.sleep(500);
            second.set(true);
            return List.of("something left");
        });
        d.close();
        assertTrue(second.get(), "close() waited for the pending disposal, after the failed one");
    }
}
