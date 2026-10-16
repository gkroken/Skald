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
import org.mandas.docker.client.exceptions.DockerException;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** DockerWorkerLauncher.inParallel: both run at once, and nothing returns before both end. */
public class LauncherParallelTest {

    @Test
    @Timeout(10)
    public void bothRunAtOnce() throws Exception {
        // Each waits for the other to have started: run one after another, this never ends.
        CountDownLatch a = new CountDownLatch(1);
        CountDownLatch b = new CountDownLatch(1);
        DockerWorkerLauncher.inParallel(() -> {
            a.countDown();
            assertTrue(b.await(5, TimeUnit.SECONDS));
        }, () -> {
            b.countDown();
            assertTrue(a.await(5, TimeUnit.SECONDS));
        });
    }

    @Test
    public void aFailedMainStillWaitsForTheSide() {
        AtomicBoolean sideEnded = new AtomicBoolean();
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> DockerWorkerLauncher.inParallel(() -> {
            Thread.sleep(300);
            sideEnded.set(true);
        }, () -> {
            throw new IllegalStateException("main");
        }));
        assertEquals("main", e.getMessage());
        assertTrue(sideEnded.get(), "returned only after the side ended");
    }

    @Test
    public void theSidesFailurePropagatesAndMainsComesFirst() {
        DockerException side = assertThrows(DockerException.class, () -> DockerWorkerLauncher.inParallel(() -> {
            throw new DockerException("side");
        }, () -> { }));
        assertEquals("side", side.getMessage());
        IllegalStateException both = assertThrows(IllegalStateException.class, () -> DockerWorkerLauncher.inParallel(() -> {
            throw new DockerException("side");
        }, () -> {
            Thread.sleep(100);
            throw new IllegalStateException("main");
        }));
        assertEquals("main", both.getMessage());
        assertEquals(1, both.getSuppressed().length);
        assertEquals("side", both.getSuppressed()[0].getMessage());
    }

    @Test
    @Timeout(10)
    public void anInterruptedMainInterruptsTheSideAndWaitsForIt() {
        AtomicBoolean sideInterrupted = new AtomicBoolean();
        assertThrows(InterruptedException.class, () -> DockerWorkerLauncher.inParallel(() -> {
            try {
                Thread.sleep(60_000);
            } catch (InterruptedException e) {
                Thread.sleep(200);
                sideInterrupted.set(true);
                throw e;
            }
        }, () -> {
            Thread.sleep(100);
            throw new InterruptedException("main");
        }));
        assertTrue(sideInterrupted.get(), "the side was interrupted, and waited for");
        assertFalse(Thread.interrupted(), "the InterruptedException carries the interrupt");
    }

    @Test
    @Timeout(10)
    public void anInterruptWhileWaitingIsRestoredAfterTheSideEnds() throws Exception {
        Thread caller = Thread.currentThread();
        AtomicBoolean sideEnded = new AtomicBoolean();
        boolean[] seen = new boolean[1];
        DockerWorkerLauncher.inParallel(() -> {
            caller.interrupt();
            try {
                Thread.sleep(60_000);
            } catch (InterruptedException e) {
                sideEnded.set(true);
            }
        }, () -> { });
        seen[0] = Thread.interrupted();
        assertTrue(sideEnded.get());
        assertArrayEquals(new boolean[] {true}, seen, "the caller's interrupt is restored");
    }
}
