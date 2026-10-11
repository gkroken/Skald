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
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Wall-time bounds for reading forge's response bodies (ae287bf-F1). java.net.http's request
 * timeout ends at the response HEADERS; a body that stalls after them -- forge restarting, a
 * load balancer's idle drop, a hung proxy -- never returns on its own, and the thread reading it
 * would hold a binary worker's slot forever. Every forge body is read through {@link #read},
 * under a TransferGuard that closes the stream and interrupts the reader at the deadline.
 * Daemon timers, one for deadlines (it runs nothing else) and one for the guard's no-op beats.
 */
final class ForgeTransfers implements AutoCloseable {

    private final ScheduledExecutorService beats = daemon("skald-forge-beat");
    private final ScheduledExecutorService deadlines = daemon("skald-forge-deadline");

    private static ScheduledExecutorService daemon(String name) {
        return Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        });
    }

    /** What a bounded read does with the body. */
    interface Reader<T> {
        T read(InputStream in) throws IOException;
    }

    /**
     * Reads {@code body} with {@code reader} within {@code deadline}; past it, an IOException
     * (SocketTimeoutException). The body is closed either way.
     */
    <T> T read(InputStream body, Duration deadline, Reader<T> reader) throws IOException {
        try (TransferGuard guard = new TransferGuard(beats, deadlines, deadline, deadline, () -> true)) {
            try (InputStream in = guard.watch(body)) {
                return reader.read(in);
            } catch (IOException e) {
                throw guard.failure(e);
            }
        }
    }

    /** At most {@code max} bytes of {@code in}; more is an IOException. */
    static byte[] bounded(InputStream in, long max) throws IOException {
        byte[] bytes = in.readNBytes((int) Math.min(Integer.MAX_VALUE - 8, max + 1));
        if (bytes.length > max) {
            throw new IOException("a response over " + max + " bytes");
        }
        return bytes;
    }

    @Override
    public void close() {
        beats.shutdownNow();
        deadlines.shutdownNow();
    }
}
