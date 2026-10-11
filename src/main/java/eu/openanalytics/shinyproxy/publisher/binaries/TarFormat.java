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

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * The tar header rules the trusted side's two readers share (a source tarball,
 * {@link SourceTarball}; a sandbox's binary, {@link BinaryArchive}): checksummed POSIX
 * headers, octal fields only, a name from exactly one place, PAX framing keys refused.
 */
final class TarFormat {

    static final int BLOCK = 512;

    private TarFormat() {
    }

    static String headerName(byte[] h) {
        String name = cString(h, 0, 100);
        // POSIX ustar only ("ustar\0" "00"): old GNU headers ("ustar  \0") keep times where
        // the prefix would be (e149183 N2).
        boolean ustar = h[257] == 'u' && h[258] == 's' && h[259] == 't' && h[260] == 'a' && h[261] == 'r'
                && h[262] == 0 && h[263] == '0' && h[264] == '0';
        String prefix = ustar ? cString(h, 345, 155) : "";
        return prefix.isEmpty() ? name : prefix + "/" + name;
    }

    /** A PAX header's "path" record, or null; refused if malformed. */
    static String paxPath(byte[] content) throws SourceRefusal {
        String path = null;
        int i = 0;
        while (i < content.length) {
            int space = i;
            while (space < content.length && content[space] != ' ') {
                space++;
            }
            int length;
            try {
                length = Integer.parseInt(new String(content, i, space - i, StandardCharsets.US_ASCII));
            } catch (NumberFormatException ex) {
                throw new SourceRefusal("TARBALL_UNREADABLE", "a PAX record without a length");
            }
            if (length <= space - i + 1 || i + length > content.length || content[i + length - 1] != '\n') {
                throw new SourceRefusal("TARBALL_UNREADABLE", "a PAX record whose length does not hold");
            }
            String record = new String(content, space + 1, i + length - space - 2, StandardCharsets.UTF_8);
            int eq = record.indexOf('=');
            if (eq < 0) {
                throw new SourceRefusal("TARBALL_UNREADABLE", "a PAX record without '='");
            }
            String key = record.substring(0, eq);
            if (key.equals("size") || key.startsWith("GNU.sparse")) {
                throw new SourceRefusal("TARBALL_UNREADABLE", "a PAX '" + key + "' record changes a member's framing");
            }
            if (key.equals("path")) {
                if (path != null) {
                    throw new SourceRefusal("TARBALL_UNREADABLE", "a PAX header names two paths");
                }
                path = record.substring(eq + 1);
            }
            i += length;
        }
        return path;
    }

    static void checksum(byte[] h) throws SourceRefusal {
        long sum = 0;
        for (int i = 0; i < BLOCK; i++) {
            sum += (i >= 148 && i < 156) ? ' ' : (h[i] & 0xff);
        }
        long stored = octal(h, 148, 8, "checksum");
        if (stored != sum) {
            throw new SourceRefusal("TARBALL_UNREADABLE", "a header checksum does not hold");
        }
    }

    /** A base-256 size (high bit set) is refused by the octal reader like any non-octal byte. */
    static long size(byte[] h) throws SourceRefusal {
        return octal(h, 124, 12, "size");
    }

    static long octal(byte[] h, int off, int len, String what) throws SourceRefusal {
        long v = 0;
        boolean digits = false;
        for (int i = off; i < off + len; i++) {
            int c = h[i] & 0xff;
            if (c == 0 || c == ' ') {
                if (digits) {
                    break;
                }
                continue;
            }
            if (c < '0' || c > '7') {
                throw new SourceRefusal("TARBALL_UNREADABLE", "a header " + what + " that is not octal");
            }
            digits = true;
            v = v * 8 + (c - '0');
        }
        return v;
    }

    static String cString(byte[] b, int off, int len) {
        int end = off;
        while (end < off + len && b[end] != 0) {
            end++;
        }
        return new String(b, off, end - off, StandardCharsets.UTF_8);
    }

    static boolean allZero(byte[] b) {
        for (byte x : b) {
            if (x != 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * False when the decompressed stream ends cleanly before a whole block. A transfer cut
     * short ends the gzip stream mid-member, which GZIPInputStream reports as an EOFException:
     * an IOException, so retried, never a refusal.
     */
    static boolean readBlock(InputStream in, byte[] block) throws IOException {
        return in.readNBytes(block, 0, BLOCK) == BLOCK;
    }

    static byte[] readContent(InputStream in, long size, long padded) throws IOException {
        byte[] content = in.readNBytes((int) size);
        if (content.length != size) {
            throw new EOFException("a member ends early");
        }
        in.skipNBytes(padded - size);
        return content;
    }

    static void skip(InputStream in, long n) throws IOException {
        in.skipNBytes(n);
    }
}
