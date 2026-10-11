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
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipException;

/**
 * One pass over a CRAN source tarball as it arrives: its SHA-256 and MD5 over EVERY byte
 * received, and its DESCRIPTION, without writing anything to disk or keeping the tarball.
 *
 * <p>The trusted side reads this; the package's code never runs here. What it needs is narrow
 * -- the digests and one file -- so it reads narrowly, and refuses anything that could make two
 * readers disagree about that file:
 * <ul>
 *   <li>every member lies under ONE top directory named for the package (R CMD build's own
 *       layout), with no absolute path and no ".." segment;</li>
 *   <li>{@code <pkg>/DESCRIPTION} is a regular file, exactly once;</li>
 *   <li>a member's name comes from its header, a GNU long name, or a PAX path -- never two (a
 *       second metadata header before a member is refused), and a global PAX header may not
 *       name a path;</li>
 *   <li>a PAX record that changes a member's framing ({@code size}, GNU sparse maps) is
 *       refused: honoured by GNU tar and libarchive, it would make this reader and the
 *       sandbox's disagree about every member after it (e149183 N1);</li>
 *   <li>bounded: the compressed bytes, the expanded bytes and the DESCRIPTION's size. The
 *       wall time and the lease are not bounded here: a read that blocks cannot check them.
 *       {@link TransferGuard} does, from outside (e149183-F1).</li>
 * </ul>
 * A base-256 size, a sparse or other unknown member type, or a header whose checksum fails
 * is refused: R CMD build writes none of them.
 */
final class SourceTarball {

    static final int BLOCK = 512;
    static final int MAX_DESCRIPTION = 1 << 20;
    private static final int MAX_METADATA = 64 * 1024;

    /** What one pass established. */
    record Read(String sha256, String md5, long bytes, String description) { }

    /** Bounds for one read. */
    record Limits(long maxBytes, long maxExpandedBytes) { }

    private SourceTarball() {
    }

    /**
     * Reads {@code raw} to its end.
     *
     * @throws SourceRefusal the bytes are not a tarball this reader accepts
     * @throws IOException   the transfer failed (a transient failure, retried)
     */
    static Read read(InputStream raw, String pkg, Limits limits) throws IOException, SourceRefusal {
        MessageDigest sha;
        MessageDigest md5;
        try {
            sha = MessageDigest.getInstance("SHA-256");
            md5 = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
        Bounded bounded = new Bounded(raw, limits);
        InputStream digesting = new DigestInputStream(new DigestInputStream(bounded, sha), md5);
        String description;
        try {
            GZIPInputStream gz = new GZIPInputStream(digesting, 64 * 1024);
            description = walk(gz, pkg, limits.maxExpandedBytes());
            gz.transferTo(OutputSink.INSTANCE);
            // Whatever follows the gzip stream is digested too: the MD5 the index publishes
            // covers the whole file, and so does what is recorded.
            digesting.transferTo(OutputSink.INSTANCE);
        } catch (ZipException ex) {
            throw new SourceRefusal("TARBALL_UNREADABLE", "not gzip: " + ex.getMessage());
        } catch (Bounded.TooLarge ex) {
            throw new SourceRefusal("TOO_LARGE", ex.getMessage());
        }
        return new Read(HexFormat.of().formatHex(sha.digest()), HexFormat.of().formatHex(md5.digest()),
                bounded.count, description);
    }

    private static String walk(InputStream tar, String pkg, long maxExpanded) throws IOException, SourceRefusal {
        String top = pkg + "/";
        String descriptionPath = top + "DESCRIPTION";
        String description = null;
        String pendingName = null;
        long expanded = 0;
        byte[] header = new byte[BLOCK];
        while (true) {
            if (!TarFormat.readBlock(tar, header)) {
                throw new SourceRefusal("TARBALL_UNREADABLE", "the archive ends without its end-of-archive blocks");
            }
            expanded += BLOCK;
            if (TarFormat.allZero(header)) {
                return requireDescription(description, descriptionPath);
            }
            TarFormat.checksum(header);
            char type = (char) (header[156] & 0xff);
            long size = TarFormat.size(header);
            long padded = (size + BLOCK - 1) / BLOCK * BLOCK;
            expanded += padded;
            if (expanded > maxExpanded) {
                throw new SourceRefusal("TOO_LARGE", "expands past " + maxExpanded + " bytes");
            }
            switch (type) {
                case 'L', 'x' -> {
                    if (pendingName != null) {
                        throw new SourceRefusal("TARBALL_UNREADABLE", "two metadata headers name one member");
                    }
                    if (size > MAX_METADATA) {
                        throw new SourceRefusal("TARBALL_UNREADABLE", "a metadata header of " + size + " bytes");
                    }
                    byte[] content = TarFormat.readContent(tar, size, padded);
                    // A PAX header without a path says nothing about the name: null, as before.
                    pendingName = type == 'L' ? TarFormat.cString(content, 0, content.length)
                            : TarFormat.paxPath(content);
                }
                case 'g' -> {
                    if (size > MAX_METADATA) {
                        throw new SourceRefusal("TARBALL_UNREADABLE", "a global header of " + size + " bytes");
                    }
                    if (TarFormat.paxPath(TarFormat.readContent(tar, size, padded)) != null) {
                        throw new SourceRefusal("TARBALL_UNREADABLE", "a global header names a path");
                    }
                }
                case '0', '\0', '7', '5', '1', '2' -> {
                    String name = pendingName != null ? pendingName : TarFormat.headerName(header);
                    pendingName = null;
                    requireUnderTop(name, top, pkg);
                    if (name.equals(descriptionPath) || name.equals(descriptionPath + "/")) {
                        if (type != '0' && type != '\0' && type != '7') {
                            throw new SourceRefusal("DESCRIPTION_UNREADABLE", "DESCRIPTION is not a regular file");
                        }
                        if (description != null) {
                            throw new SourceRefusal("DESCRIPTION_UNREADABLE", "DESCRIPTION appears twice");
                        }
                        if (size > MAX_DESCRIPTION) {
                            throw new SourceRefusal("DESCRIPTION_UNREADABLE", "DESCRIPTION is " + size + " bytes");
                        }
                        description = new String(TarFormat.readContent(tar, size, padded), StandardCharsets.UTF_8);
                    } else if (type == '0' || type == '\0' || type == '7') {
                        TarFormat.skip(tar, padded);
                    } else if (size != 0) {
                        throw new SourceRefusal("TARBALL_UNREADABLE", "a link or directory with content: " + name);
                    }
                }
                default -> throw new SourceRefusal("TARBALL_UNREADABLE",
                        "member type '" + RDependencies.printable(String.valueOf(type)) + "' is not one R CMD build writes");
            }
        }
    }

    private static String requireDescription(String description, String path) throws SourceRefusal {
        if (description == null) {
            throw new SourceRefusal("DESCRIPTION_UNREADABLE", "no " + path);
        }
        return description;
    }

    private static void requireUnderTop(String name, String top, String pkg) throws SourceRefusal {
        if (!name.startsWith(top) && !name.equals(pkg)) {
            throw new SourceRefusal("NOT_ONE_TOP_DIRECTORY", "member outside " + top + ": " + name);
        }
        for (String segment : name.split("/", -1)) {
            if (segment.equals("..") || segment.equals(".")) {
                throw new SourceRefusal("NOT_ONE_TOP_DIRECTORY", "member with a '" + segment + "' segment: " + name);
            }
        }
    }

    /** Counts and bounds the raw bytes. */
    private static final class Bounded extends InputStream {
        static final class TooLarge extends IOException {
            TooLarge(String message) {
                super(message);
            }
        }

        private final InputStream in;
        private final Limits limits;
        private long count;

        Bounded(InputStream in, Limits limits) {
            this.in = in;
            this.limits = limits;
        }

        private void check(int n) throws IOException {
            if (n > 0) {
                count += n;
                if (count > limits.maxBytes()) {
                    throw new TooLarge("more than " + limits.maxBytes() + " bytes");
                }
            }
        }

        @Override
        public int read() throws IOException {
            int b = in.read();
            check(b < 0 ? 0 : 1);
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = in.read(b, off, len);
            check(n);
            return n;
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }

    private static final class OutputSink extends java.io.OutputStream {
        static final OutputSink INSTANCE = new OutputSink();

        @Override
        public void write(int b) {
        }

        @Override
        public void write(byte[] b, int off, int len) {
        }
    }
}
