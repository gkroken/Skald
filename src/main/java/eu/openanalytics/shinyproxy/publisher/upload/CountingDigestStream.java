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
package eu.openanalytics.shinyproxy.publisher.upload;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * An archive's bytes on their way somewhere, counted and digested as they pass, so whoever
 * handed them on can say afterwards exactly what went through -- the PUT's request body into
 * the store, or the stored archive into the extractor.
 *
 * <p><b>A read of 0 bytes fails closed</b> (T5 carry, b991250 review note). GzipMember reads
 * a source returning 0 as the end of the upload. A blocking stream never returns 0 for a
 * non-empty request, but a servlet or SDK adapter may; failing here, before the extractor
 * sees it, turns "0 means truncated" from an assumption about every adapter into a check.
 */
final class CountingDigestStream extends FilterInputStream {

    private final MessageDigest digest;
    private long count;
    private boolean ended;

    CountingDigestStream(InputStream in) {
        super(in);
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    long count() {
        return count;
    }

    /** Whether the source has reported its end. */
    boolean ended() {
        return ended;
    }

    /** The digest of every byte that passed. Call once, at the end. */
    String sha256() {
        return HexFormat.of().formatHex(digest.digest());
    }

    /** Reads (and digests) the rest of the source, at most {@code limit} more bytes. */
    void drain(long limit) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long left = limit;
        while (left > 0) {
            int n = read(buffer, 0, (int) Math.min(buffer.length, left));
            if (n == -1) {
                return;
            }
            left -= n;
        }
    }

    @Override
    public int read() throws IOException {
        int b = in.read();
        if (b == -1) {
            ended = true;
        } else {
            count++;
            digest.update((byte) b);
        }
        return b;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        int n = in.read(b, off, len);
        if (n == 0 && len > 0) {
            throw new IOException("the source returned 0 bytes for a read of " + len
                    + "; refusing to read that as the end of the archive");
        }
        if (n == -1) {
            ended = true;
        } else if (n > 0) {
            count += n;
            digest.update(b, off, n);
        }
        return n;
    }

    @Override
    public long skip(long n) throws IOException {
        // Skipped bytes would pass undigested; read them instead.
        byte[] buffer = new byte[(int) Math.min(8192, Math.max(1, n))];
        long skipped = 0;
        while (skipped < n) {
            int r = read(buffer, 0, (int) Math.min(buffer.length, n - skipped));
            if (r == -1) {
                break;
            }
            skipped += r;
        }
        return skipped;
    }

    @Override
    public boolean markSupported() {
        return false;
    }
}
