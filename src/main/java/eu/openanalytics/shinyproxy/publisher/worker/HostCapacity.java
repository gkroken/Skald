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

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * What this instance's Docker host is sized to run at once (T7c step 2): {@code slots} =
 * skald.builds.worker.parallel plus, with shared binaries on, skald.builds.binaries.max-running.
 * Every running attempt, app build or package compile, holds a slot, and so does every pool
 * entry while it is launched and warmed. An attempt is never refused (its own limits already
 * bound it, and a cold launch is never held up by the pool); a warm-up is made only while a
 * slot is free, so a refill waits instead of competing with running builds for the host -- the
 * T7b probe measured the setup helper at a median 43 s with three refills beside running
 * builds, 3.9 s on an idle host. A ready entry left idle holds no slot: it costs memory and
 * disk, not CPU.
 *
 * <p>Per host, not per driver (8144a20 N3): the app builds' and the package compiles' pools
 * share one Docker host, so one count bounds both.
 */
public final class HostCapacity {

    /** A held slot; closing it more than once releases it once. */
    public interface Slot extends AutoCloseable {
        @Override
        void close();
    }

    private final int slots;
    private int running;
    private int warming;
    private final List<Runnable> onFree = new CopyOnWriteArrayList<>();

    public HostCapacity(int slots) {
        if (slots < 1) {
            throw new IllegalArgumentException("host capacity " + slots + " must be at least 1");
        }
        this.slots = slots;
    }

    /** A running attempt's slot: always granted, counted until closed. */
    public Slot attempt() {
        synchronized (this) {
            running++;
        }
        return slot(true);
    }

    /** A pool entry's warm-up slot, only while running attempts and warm-ups leave one free. */
    public Optional<Slot> warmUp() {
        synchronized (this) {
            if (running + warming >= slots) {
                return Optional.empty();
            }
            warming++;
        }
        return Optional.of(slot(false));
    }

    /** Runs {@code r} whenever a slot is released (a pool, to refill without waiting a round). */
    public void onFree(Runnable r) {
        onFree.add(r);
    }

    public int slots() {
        return slots;
    }

    synchronized int running() {
        return running;
    }

    synchronized int warming() {
        return warming;
    }

    private Slot slot(boolean attempt) {
        AtomicBoolean closed = new AtomicBoolean();
        return () -> {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            synchronized (this) {
                if (attempt) {
                    running--;
                } else {
                    warming--;
                }
            }
            onFree.forEach(Runnable::run);
        };
    }
}
