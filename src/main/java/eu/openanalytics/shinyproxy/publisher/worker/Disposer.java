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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Disposes of an attempt once its outcome is settled, off the critical path (T7b, the fixed
 * cost: with the worker taken from the pool, disposal was the largest fixed cost left, 7-17 s
 * between a pushed image and its PUBLISHING write). The drivers hand it an attempt only after
 * their work thread has ended, so no daemon call of the attempt is in flight (0c71146-F1), and
 * never a stopped attempt, whose disposal stays the stop path's. App builds only: the
 * reconciliation sweep leaves a settled build's attempt alone for its grace (15 min, and never
 * less than {@link #CLOSE_WAIT}: WorkerConfig refuses a shorter one), so it does not race a
 * disposal here; one that fails or is cut short by a shutdown is what the sweep finds later.
 * The package driver disposes inline instead: its sweep has no grace and takes any attempt no
 * RUNNING row owns, which a settled row's still is not (bc66e6f-F1). {@link #close} waits for
 * pending ones.
 */
public final class Disposer implements AutoCloseable {

    /** How long close() waits for pending disposals: as a stop's bound on its own disposal. */
    static final Duration CLOSE_WAIT = Duration.ofMinutes(3);
    private static final Logger log = LoggerFactory.getLogger(Disposer.class);

    /** One disposal, returning what it left (empty when clean). */
    public interface Disposal {
        List<String> run() throws Exception;
    }

    private final AtomicInteger threads = new AtomicInteger();
    private final ExecutorService executor;

    public Disposer(int parallel) {
        executor = Executors.newFixedThreadPool(parallel, r -> {
            Thread t = new Thread(r, "skald-dispose-" + threads.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    /** Disposes of {@code what} on a disposal thread; the future ends with it. */
    public Future<?> submit(String what, Disposal disposal) {
        return executor.submit(() -> {
            try {
                List<String> left = disposal.run();
                if (!left.isEmpty()) {
                    log.warn("{}: disposal left {}", what, left);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("{}: disposal interrupted", what);
            } catch (Exception e) {
                log.warn("{}: disposal failed: {}", what, e.toString());
            }
        });
    }

    /** Takes no more, and waits up to {@link #CLOSE_WAIT} for the pending ones. */
    @Override
    public void close() throws InterruptedException {
        executor.shutdown();
        if (!executor.awaitTermination(CLOSE_WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
            log.warn("disposals still running after {}; the sweeps take what they leave", CLOSE_WAIT);
        }
    }
}
