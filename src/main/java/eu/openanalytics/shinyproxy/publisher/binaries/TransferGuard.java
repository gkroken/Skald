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
package eu.openanalytics.shinyproxy.publisher.binaries;

import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * Bounds one thread's transfers from OUTSIDE the blocking read (e149183-F1). A read that
 * blocks -- a server that sent its headers and some bytes and then nothing, a silent network
 * partition -- never returns to be checked, so neither a deadline nor a heartbeat inside the
 * read can act. Here a timer does both:
 * <ul>
 *   <li>every {@code beatEvery} it runs the heartbeat (the lease renewal); a heartbeat that
 *       answers false means the lease is lost;</li>
 *   <li>at the deadline, or on a lost lease, it closes every stream it watches and interrupts
 *       the reading thread, so the read ends with an IOException.</li>
 * </ul>
 * {@link #failure} then turns that IOException into what happened: a {@link
 * SocketTimeoutException} for the deadline, the lease's own exception for a lost lease.
 *
 * <p>The heartbeat and the deadline run on DIFFERENT timers (9262bd8 N1): a renewal that
 * blocks in the database must never delay a deadline, so the deadline timer runs nothing but
 * deadlines.
 * {@link #close} stops the timer and clears an interrupt the watchdog may have left, so it
 * never leaks into the thread's next database call.
 */
final class TransferGuard implements AutoCloseable {

    enum Reason { NONE, DEADLINE, LEASE_LOST }

    private final Thread reader = Thread.currentThread();
    private final List<InputStream> watched = new ArrayList<>();
    private final ScheduledFuture<?> beats;
    private final ScheduledFuture<?> deadline;
    private final BooleanSupplier heartbeat;
    private Reason reason = Reason.NONE;
    private boolean done;

    TransferGuard(ScheduledExecutorService beatTimer, ScheduledExecutorService deadlineTimer, Duration deadline,
                  Duration beatEvery, BooleanSupplier heartbeat) {
        this.heartbeat = heartbeat;
        long every = Math.max(1, beatEvery.toMillis());
        this.beats = beatTimer.scheduleAtFixedRate(this::beat, every, every, TimeUnit.MILLISECONDS);
        this.deadline = deadlineTimer.schedule(() -> stop(Reason.DEADLINE), Math.max(1, deadline.toMillis()),
                TimeUnit.MILLISECONDS);
    }

    /** Watches {@code in}: it is closed if the transfer is stopped. */
    synchronized InputStream watch(InputStream in) throws IOException {
        if (reason != Reason.NONE) {
            in.close();
            throw failure(new IOException("stopped"));
        }
        watched.add(in);
        return in;
    }

    private void beat() {
        boolean alive;
        try {
            alive = heartbeat.getAsBoolean();
        } catch (RuntimeException ex) {
            alive = false;
        }
        if (!alive) {
            stop(Reason.LEASE_LOST);
        }
    }

    private synchronized void stop(Reason why) {
        if (done || reason != Reason.NONE) {
            return;
        }
        reason = why;
        for (InputStream in : watched) {
            try {
                in.close();
            } catch (IOException | RuntimeException ignored) {
                // Closing is a nudge; the interrupt below is the other.
            }
        }
        reader.interrupt();
    }

    synchronized Reason reason() {
        return reason;
    }

    /**
     * What an IOException (or an interrupt) during a watched transfer means: the deadline,
     * a lost lease ({@link LeaseLost}), or itself.
     */
    synchronized IOException failure(IOException ex) {
        return switch (reason) {
            case DEADLINE -> new SocketTimeoutException("the transfer passed its deadline");
            case LEASE_LOST -> new LeaseLost();
            case NONE -> ex;
        };
    }

    /** The lease was lost during the transfer: nothing may be written. */
    static final class LeaseLost extends IOException {
        LeaseLost() {
            super("the lease was lost during the transfer");
        }
    }

    @Override
    public void close() {
        beats.cancel(false);
        deadline.cancel(false);
        synchronized (this) {
            done = true;
        }
        // An interrupt the watchdog sent after the read had already ended must not reach the
        // thread's next blocking call (a JDBC write).
        Thread.interrupted();
    }
}
