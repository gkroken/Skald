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
package eu.openanalytics.shinyproxy.publisher.build;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The loop never dies of what runOnce throws (T6 carry (c)), pauses when idle, and stops. */
public class BuildLoopTest {

    @Test
    public void whateverAnAttemptThrowsTheLoopGoesOn() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(1);
        BuildLoop loop = new BuildLoop(() -> {
            int n = calls.incrementAndGet();
            switch (n) {
                case 1 -> throw new IllegalStateException("a database blip");
                case 2 -> throw new AssertionError("a driver bug, as an Error");
                case 3 -> throw new Exception("checked");
                case 4 -> {
                    return BuildRunner.Result.FAILED;
                }
                case 5 -> {
                    return BuildRunner.Result.PUBLISHING;
                }
                default -> {
                    done.countDown();
                    return BuildRunner.Result.IDLE;
                }
            }
        }, Duration.ofMillis(10));
        loop.start();
        try {
            assertTrue(done.await(10, TimeUnit.SECONDS), "the loop reached its sixth call");
        } finally {
            loop.stop();
        }
        assertEquals(3, loop.failures(), "three throws, each logged and survived");
        assertEquals(2, loop.attempts(), "two attempts that ran");
    }

    @Test
    public void parallelLoopsRunThatManyAttemptsSideBySideAndNoMore() throws Exception {
        // T7c step 1: three threads, each holding an attempt until all three are in flight.
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger most = new AtomicInteger();
        CountDownLatch three = new CountDownLatch(3);
        CountDownLatch release = new CountDownLatch(1);
        BuildLoop loop = new BuildLoop(() -> {
            int now = inFlight.incrementAndGet();
            most.accumulateAndGet(now, Math::max);
            three.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } finally {
                inFlight.decrementAndGet();
            }
            return BuildRunner.Result.IDLE;
            // A long idle pause: only stop's interrupt can end a thread in time.
        }, Duration.ofSeconds(60), 3);
        loop.start();
        try {
            assertTrue(three.await(10, TimeUnit.SECONDS), "three attempts in flight at once");
            Thread.sleep(300);
            assertEquals(3, most.get(), "and never a fourth");
        } finally {
            release.countDown();
        }
        long stopping = System.nanoTime();
        loop.stop();
        assertTrue(System.nanoTime() - stopping < TimeUnit.SECONDS.toNanos(5),
                "stop interrupts every thread's pause rather than waiting it out");
        assertEquals(List.of(), loopThreads(), "stop ends every thread");
        assertThrows(IllegalArgumentException.class, () -> new BuildLoop(() -> BuildRunner.Result.IDLE,
                Duration.ofMillis(10), 0));
    }

    @Test
    public void stopWaitsForEveryThreadsCurrentAttempt() throws Exception {
        // "Stops after the current attempt", for every thread: the pools and the disposer stop
        // after the loop, so no thread may still be in an attempt when stop returns.
        AtomicInteger started = new AtomicInteger();
        AtomicInteger finished = new AtomicInteger();
        java.util.concurrent.atomic.AtomicBoolean release = new java.util.concurrent.atomic.AtomicBoolean();
        BuildLoop loop = new BuildLoop(() -> {
            started.incrementAndGet();
            // Staggered by thread: the first ends first, the others 300 and 600 ms later.
            String name = Thread.currentThread().getName();
            long extra = 300L * Integer.parseInt(name.substring(name.lastIndexOf('-') + 1));
            while (!release.get()) {
                try {
                    Thread.sleep(20);
                } catch (InterruptedException ignored) {
                    // an attempt ends on its own terms, not on the loop's interrupt
                }
            }
            long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(extra);
            while (System.nanoTime() < end) {
                try {
                    Thread.sleep(20);
                } catch (InterruptedException ignored) {
                    // as above
                }
            }
            finished.incrementAndGet();
            return BuildRunner.Result.FAILED;
        }, Duration.ofSeconds(60), 3);
        loop.start();
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (started.get() < 3 && System.nanoTime() < until) {
            Thread.sleep(20);
        }
        assertEquals(3, started.get(), "three attempts in flight");
        Thread releaser = new Thread(() -> {
            try {
                Thread.sleep(500);
            } catch (InterruptedException ignored) {
                // released below anyway
            }
            release.set(true);
        });
        releaser.start();
        loop.stop();
        assertEquals(3, finished.get(), "stop returned only after every thread's attempt ended");
        releaser.join();
    }

    @Test
    public void aThreadThatOutlivedStopNeverJoinsALaterStart() throws Exception {
        // 90b17e4 N1: stop waits a bounded time; a thread still in its attempt then must end
        // once the attempt does, even though the loop was started again meanwhile.
        java.util.concurrent.atomic.AtomicReference<Thread> stuck = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicBoolean release = new java.util.concurrent.atomic.AtomicBoolean();
        AtomicInteger callsByStuck = new AtomicInteger();
        CountDownLatch inAttempt = new CountDownLatch(1);
        BuildLoop loop = new BuildLoop(() -> {
            if (stuck.compareAndSet(null, Thread.currentThread())) {
                inAttempt.countDown();
                while (!release.get()) {
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException ignored) {
                        // outlives stop's wait on purpose
                    }
                }
            } else if (Thread.currentThread() == stuck.get()) {
                callsByStuck.incrementAndGet();
            }
            return BuildRunner.Result.IDLE;
        }, Duration.ofMillis(50), 1);
        loop.stopWait = Duration.ofMillis(100);
        loop.start();
        assertTrue(inAttempt.await(10, TimeUnit.SECONDS));
        loop.stop();
        assertTrue(stuck.get().isAlive(), "still in its attempt after stop's wait");
        loop.start();
        release.set(true);
        stuck.get().join(5000);
        try {
            assertFalse(stuck.get().isAlive(), "the old thread ended once its attempt did");
            assertEquals(0, callsByStuck.get(), "and never claimed beside the new start's threads");
        } finally {
            loop.stop();
        }
    }

    private static List<String> loopThreads() {
        return Thread.getAllStackTraces().keySet().stream().filter(Thread::isAlive).map(Thread::getName)
                .filter(n -> n.startsWith("skald-build-loop")).toList();
    }

    @Test
    public void anIdleLoopPausesAndStopEndsIt() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        BuildLoop loop = new BuildLoop(() -> {
            calls.incrementAndGet();
            return BuildRunner.Result.IDLE;
        }, Duration.ofMillis(200));
        loop.start();
        Thread.sleep(1000);
        loop.stop();
        int seen = calls.get();
        assertTrue(seen >= 2 && seen <= 8, "paused between idle calls: " + seen + " calls in 1 s at 200 ms");
        Thread.sleep(500);
        assertEquals(seen, calls.get(), "nothing runs after stop");
    }
}
