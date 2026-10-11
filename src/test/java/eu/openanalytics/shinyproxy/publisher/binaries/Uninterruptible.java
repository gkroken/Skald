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
import java.util.concurrent.CountDownLatch;

/**
 * A stream whose read blocks until it is CLOSED and ignores interrupts, as a blocking socket
 * read does: what a guard must still be able to stop.
 */
final class Uninterruptible extends InputStream {

    private final CountDownLatch closed = new CountDownLatch(1);

    @Override
    public int read() throws IOException {
        while (closed.getCount() > 0) {
            try {
                closed.await();
            } catch (InterruptedException ignored) {
                // A socket read does not end on an interrupt; neither does this.
            }
        }
        throw new IOException("closed");
    }

    @Override
    public void close() {
        closed.countDown();
    }
}
