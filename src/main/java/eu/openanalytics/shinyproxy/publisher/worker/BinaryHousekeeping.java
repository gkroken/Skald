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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The binaries' housekeeping on a timer (T7b step 2e, part 1b), each tick never throwing (a
 * scheduled task that throws is never run again, 071ef50-F1):
 * <ul>
 *   <li>the queue's reaper: a lost lease or passed deadline settled (BinaryBuildQueue#reap);</li>
 *   <li>the package-attempt sweep: every package attempt present on this host ("p" ids, never
 *       an app build's 32 hex, which AttemptSweeper owns) whose row is not RUNNING at that
 *       generation is disposed of -- what a process that died mid-sandbox left behind
 *       (9159de6: carried here). A row RUNNING at that generation is a live sandbox: kept.</li>
 * </ul>
 */
public final class BinaryHousekeeping {

    private static final Logger log = LoggerFactory.getLogger(BinaryHousekeeping.class);

    private final Runnable reap;
    private final Supplier<Set<String>> present;
    private final Supplier<Set<String>> live;
    private final Consumer<String> dispose;
    private final Duration period;
    private final Duration sweepPeriod;
    private ScheduledExecutorService timer;

    /**
     * @param present every attempt id with objects on this host
     * @param live    the package attempt ids of RUNNING rows
     */
    /**
     * @param period      how often the reaper runs (the lease's renewal cadence)
     * @param sweepPeriod how often the sweep runs (its inventory runs a helper container)
     */
    public BinaryHousekeeping(Runnable reap, Supplier<Set<String>> present, Supplier<Set<String>> live,
                              Consumer<String> dispose, Duration period, Duration sweepPeriod) {
        this.reap = reap;
        this.present = present;
        this.live = live;
        this.dispose = dispose;
        this.period = period;
        this.sweepPeriod = sweepPeriod;
    }

    public synchronized void start() {
        if (timer != null) {
            return;
        }
        timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "skald-binary-housekeeping");
            t.setDaemon(true);
            return t;
        });
        timer.scheduleWithFixedDelay(this::reapTick, period.toMillis(), period.toMillis(), TimeUnit.MILLISECONDS);
        timer.scheduleWithFixedDelay(this::sweepTick, sweepPeriod.toMillis(), sweepPeriod.toMillis(),
                TimeUnit.MILLISECONDS);
    }

    public synchronized void stop() {
        if (timer != null) {
            timer.shutdownNow();
            timer = null;
        }
    }

    void reapTick() {
        try {
            reap.run();
        } catch (Throwable t) {
            log.warn("binary reap failed; retrying in {}", period, t);
        }
    }

    void sweepTick() {
        try {
            sweep();
        } catch (Throwable t) {
            log.warn("package-attempt sweep failed; retrying in {}", sweepPeriod, t);
        }
    }

    /** The package attempts disposed of. */
    List<String> sweep() {
        // The host's inventory FIRST, the live leases SECOND, as AttemptSweeper does (fca2f81-F1):
        // a sandbox's objects exist only after its row is claimed, so anything in the inventory is
        // either live in the fresher lease read or dead. The other order disposed of a sandbox
        // claimed and launched while the (slow, helper-container) inventory ran.
        Set<String> onHost = present.get();
        Set<String> running = live.get();
        List<String> disposed = new ArrayList<>();
        for (String id : onHost) {
            if (id.length() == 33 && id.charAt(0) == 'p' && !running.contains(id)) {
                dispose.accept(id);
                disposed.add(id);
            }
        }
        if (!disposed.isEmpty()) {
            log.info("package-attempt sweep disposed of {}", disposed);
        }
        return disposed;
    }
}
