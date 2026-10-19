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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Runs {@link BuildRunner#runOnce} in a loop on its own thread, around the real driver (T6
 * carry (c): "wire a BuildRunner loop around the real driver that logs and continues on
 * anything runOnce throws").
 *
 * <p>The loop never dies of what runOnce throws: a claim that hits a database blip, a
 * coordinator write that fails, a driver bug. Each is logged, the loop pauses
 * {@code idlePause}, and it tries again. A loop that silently stopped would leave every
 * queued build queued forever, which is the failure 071ef50-F1 found in the heartbeat. When
 * there was nothing to claim, it pauses too; when it ran an attempt, it goes straight on.
 *
 * <p>{@code parallel} threads run that loop side by side (T7c step 1): how many builds this
 * instance's Docker host takes at once. Each claims through the coordinator, which counts
 * running builds across the deployment under its claim lock, so the threads of every
 * instance together never exceed {@code skald.builds.max-running}; each keeps its own
 * attempt's lease, stop and settle, as one thread did.
 */
public final class BuildLoop {

    private static final Logger log = LoggerFactory.getLogger(BuildLoop.class);

    private final java.util.concurrent.Callable<BuildRunner.Result> once;
    private final Duration idlePause;
    private final AtomicLong attempts = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();
    private final int parallel;
    private volatile boolean running;
    private final java.util.List<Thread> threads = new java.util.ArrayList<>();

    /** {@code parallel} loops around {@code runner.runOnce(owner, driver)}. */
    public BuildLoop(BuildRunner runner, BuildDriver driver, String owner, Duration idlePause, int parallel) {
        this(() -> runner.runOnce(owner, driver), idlePause, parallel);
    }

    BuildLoop(java.util.concurrent.Callable<BuildRunner.Result> once, Duration idlePause) {
        this(once, idlePause, 1);
    }

    BuildLoop(java.util.concurrent.Callable<BuildRunner.Result> once, Duration idlePause, int parallel) {
        if (parallel < 1) {
            throw new IllegalArgumentException("parallel " + parallel + " must be at least 1");
        }
        this.once = once;
        this.idlePause = idlePause;
        this.parallel = parallel;
    }

    public synchronized void start() {
        if (!threads.isEmpty()) {
            return;
        }
        running = true;
        for (int i = 0; i < parallel; i++) {
            Thread thread = new Thread(this::loop, parallel == 1 ? "skald-build-loop" : "skald-build-loop-" + i);
            thread.setDaemon(true);
            threads.add(thread);
            thread.start();
        }
    }

    private void loop() {
        while (running) {
            boolean idle;
            try {
                BuildRunner.Result result = once.call();
                idle = result == BuildRunner.Result.IDLE;
                if (!idle) {
                    attempts.incrementAndGet();
                    log.info("build attempt ended: {}", result);
                }
            } catch (Throwable failure) {
                failures.incrementAndGet();
                // ERROR: whatever it was (an Error included) is caught so the loop survives,
                // and a looping failure must be visible (4b8c6f8 review N2).
                log.error("the build loop's attempt failed; going on after {}", idlePause, failure);
                idle = true;
            }
            if (idle) {
                try {
                    Thread.sleep(idlePause.toMillis());
                } catch (InterruptedException e) {
                    // stop() interrupts the pause; the loop condition decides
                }
            }
        }
    }

    /**
     * Stops every thread after its current attempt, waiting two minutes for all of them
     * together. Each attempt itself is the driver's to stop, by its lease.
     */
    public synchronized void stop() throws InterruptedException {
        running = false;
        threads.forEach(Thread::interrupt);
        long until = System.nanoTime() + Duration.ofMinutes(2).toNanos();
        for (Thread thread : threads) {
            thread.join(Math.max(1, (until - System.nanoTime()) / 1_000_000));
        }
        threads.clear();
    }

    /** Attempts that ran (anything but IDLE) since start; for tests and metrics. */
    public long attempts() {
        return attempts.get();
    }

    /** runOnce calls that threw since start; for tests and metrics. */
    public long failures() {
        return failures.get();
    }
}
