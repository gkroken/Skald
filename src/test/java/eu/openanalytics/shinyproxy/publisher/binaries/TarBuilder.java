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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.zip.GZIPOutputStream;

/** Builds tar archives header by header, so a test can write exactly the hostile case it means. */
final class TarBuilder {

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    /** A regular file. */
    TarBuilder file(String name, String content) {
        return entry(name, '0', content.getBytes(StandardCharsets.UTF_8));
    }

    TarBuilder dir(String name) {
        return entry(name, '5', new byte[0]);
    }

    TarBuilder symlink(String name) {
        return entry(name, '2', new byte[0]);
    }

    /** A GNU long-name header naming the next member. */
    TarBuilder longName(String name) {
        return entry("././@LongLink", 'L', (name + "\0").getBytes(StandardCharsets.UTF_8));
    }

    /** A PAX extended header with one path record. */
    TarBuilder paxPath(String path) {
        return entry("PaxHeader", 'x', pax("path", path));
    }

    /** A global PAX header with one record. */
    TarBuilder globalPax(String key, String value) {
        return entry("GlobalHead", 'g', pax(key, value));
    }

    static byte[] pax(String key, String value) {
        String body = " " + key + "=" + value + "\n";
        int len = body.length();
        int total = len + String.valueOf(len).length();
        if (String.valueOf(total).length() != String.valueOf(len).length()) {
            total++;
        }
        return (total + body).getBytes(StandardCharsets.UTF_8);
    }

    TarBuilder entry(String name, char type, byte[] content) {
        byte[] h = header(name, type, content.length);
        out.writeBytes(h);
        out.writeBytes(content);
        out.writeBytes(new byte[(512 - content.length % 512) % 512]);
        return this;
    }

    /** A raw header block, already checksummed, for tests that corrupt it afterwards. */
    static byte[] header(String name, char type, long size) {
        byte[] h = new byte[512];
        byte[] n = name.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(n, 0, h, 0, Math.min(100, n.length));
        put(h, 100, "0000644");
        put(h, 108, "0000000");
        put(h, 116, "0000000");
        put(h, 124, String.format("%011o", size));
        put(h, 136, "00000000000");
        h[156] = (byte) type;
        put(h, 257, "ustar");
        put(h, 263, "00");
        checksum(h);
        return h;
    }

    static void checksum(byte[] h) {
        for (int i = 148; i < 156; i++) {
            h[i] = ' ';
        }
        long sum = 0;
        for (byte b : h) {
            sum += b & 0xff;
        }
        put(h, 148, String.format("%06o", sum));
        h[154] = 0;
        h[155] = ' ';
    }

    private static void put(byte[] h, int off, String s) {
        byte[] b = s.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(b, 0, h, off, b.length);
    }

    TarBuilder raw(byte[] block) {
        out.writeBytes(block);
        return this;
    }

    /** The archive with its two end blocks. */
    byte[] tar() {
        ByteArrayOutputStream t = new ByteArrayOutputStream();
        t.writeBytes(out.toByteArray());
        t.writeBytes(new byte[1024]);
        return t.toByteArray();
    }

    /** The archive WITHOUT end blocks. */
    byte[] tarWithoutEnd() {
        return out.toByteArray();
    }

    byte[] tgz() {
        return gzip(tar());
    }

    static byte[] gzip(byte[] bytes) {
        try {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            try (GZIPOutputStream gz = new GZIPOutputStream(b)) {
                gz.write(bytes);
            }
            return b.toByteArray();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    static String digest(String algorithm, byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(bytes));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** A CRAN-like source tarball of {@code pkg} with this DESCRIPTION. */
    static byte[] source(String pkg, String description) {
        return new TarBuilder().dir(pkg + "/").file(pkg + "/DESCRIPTION", description)
            .file(pkg + "/R/" + pkg + ".R", "f <- function() 1\n").file(pkg + "/NAMESPACE", "export(f)\n").tgz();
    }

    static String description(String pkg, String version, String extra) {
        return "Package: " + pkg + "\nType: Package\nTitle: Test\nVersion: " + version + "\n" + extra
            + "License: MIT\nNeedsCompilation: no\n";
    }
}
