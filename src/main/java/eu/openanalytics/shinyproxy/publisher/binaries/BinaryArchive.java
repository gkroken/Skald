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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipException;

/**
 * The trusted side's check of what a package's sandbox produced, before anything is uploaded
 * (WORKPLAN-BUNDLES T7b step 2, point 5). The sandbox ran the package's own install code, so
 * its output is untrusted: it must be exactly what forge accepts as an installed-package
 * tarball and nothing that two readers could read differently.
 * <ul>
 *   <li>gzip of a tar whose every member is under ONE directory named for the package, no
 *       '.' or '..' segment, regular files and directories only (forge refuses links too;
 *       R CMD INSTALL writes none);</li>
 *   <li>{@code <pkg>/DESCRIPTION} once, naming this package and version, with a {@code Built:}
 *       field for THIS base's R version and platform (an arch-independent package records no
 *       platform), and {@code <pkg>/Meta/package.rds} (an installed package, not a source
 *       tree);</li>
 *   <li>bounded: compressed bytes, expanded bytes, entries.</li>
 * </ul>
 * The SHA-256 returned is over the bytes checked, which are the bytes uploaded.
 */
public final class BinaryArchive {

    /** What a checked archive is. */
    public record Checked(String sha256, long bytes, Map<String, String> description) { }

    /** The archive is not one this platform will share. */
    public static final class Refused extends Exception {
        private final String code;

        Refused(String code, String detail) {
            super(RDependencies.printable(detail));
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    /** The bounds of one check. */
    public record Limits(long maxBytes, long maxExpandedBytes, int maxEntries) {
        public static final Limits DEFAULTS = new Limits(512L << 20, 2L << 30, 100_000);
    }

    private BinaryArchive() {
    }

    /**
     * @param rVersion  the base's R version, e.g. 4.6.1
     * @param rPlatform the base's R.version$platform, e.g. x86_64-pc-linux-gnu
     */
    public static Checked check(byte[] tgz, String pkg, String version, String rVersion, String rPlatform,
                                Limits limits) throws Refused {
        if (tgz.length > limits.maxBytes()) {
            throw new Refused("BINARY_TOO_LARGE", "the binary is " + tgz.length + " bytes");
        }
        String top = pkg + "/";
        String descriptionPath = top + "DESCRIPTION";
        String description = null;
        boolean meta = false;
        long expanded = 0;
        int entries = 0;
        try (GZIPInputStream tar = new GZIPInputStream(new ByteArrayInputStream(tgz))) {
            byte[] header = new byte[TarFormat.BLOCK];
            String pendingName = null;
            while (true) {
                if (!TarFormat.readBlock(tar, header)) {
                    throw new Refused("BINARY_UNREADABLE", "the archive ends without its end-of-archive blocks");
                }
                if (TarFormat.allZero(header)) {
                    break;
                }
                TarFormat.checksum(header);
                char type = (char) (header[156] & 0xff);
                long size = TarFormat.size(header);
                long padded = (size + TarFormat.BLOCK - 1) / TarFormat.BLOCK * TarFormat.BLOCK;
                expanded += TarFormat.BLOCK + padded;
                if (expanded > limits.maxExpandedBytes() || ++entries > limits.maxEntries()) {
                    throw new Refused("BINARY_TOO_LARGE", "expands past the limits");
                }
                switch (type) {
                    case 'L', 'x' -> {
                        if (pendingName != null) {
                            throw new Refused("BINARY_UNREADABLE", "two metadata headers name one member");
                        }
                        if (size > 64 * 1024) {
                            throw new Refused("BINARY_UNREADABLE", "a metadata header of " + size + " bytes");
                        }
                        byte[] content = TarFormat.readContent(tar, size, padded);
                        pendingName = type == 'L' ? TarFormat.cString(content, 0, content.length)
                                : TarFormat.paxPath(content);
                    }
                    case '0', '\0', '5' -> {
                        String name = pendingName != null ? pendingName : TarFormat.headerName(header);
                        pendingName = null;
                        if (name.startsWith("./")) {
                            name = name.substring(2);
                        }
                        String bare = name.endsWith("/") ? name.substring(0, name.length() - 1) : name;
                        if (!name.startsWith(top) && !bare.equals(pkg)) {
                            throw new Refused("BINARY_LAYOUT", "member outside " + top + ": " + name);
                        }
                        for (String segment : bare.split("/", -1)) {
                            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                                throw new Refused("BINARY_LAYOUT", "an unsafe path: " + name);
                            }
                        }
                        if (type == '5') {
                            if (size != 0) {
                                throw new Refused("BINARY_UNREADABLE", "a directory with content: " + name);
                            }
                        } else if (bare.equals(descriptionPath)) {
                            if (description != null) {
                                throw new Refused("BINARY_LAYOUT", "DESCRIPTION appears twice");
                            }
                            if (size > SourceTarball.MAX_DESCRIPTION) {
                                throw new Refused("BINARY_LAYOUT", "DESCRIPTION is " + size + " bytes");
                            }
                            description = new String(TarFormat.readContent(tar, size, padded), StandardCharsets.UTF_8);
                        } else {
                            meta |= bare.equals(top + "Meta/package.rds");
                            TarFormat.skip(tar, padded);
                        }
                    }
                    case '1', '2' -> throw new Refused("BINARY_LAYOUT", "a link: "
                            + (pendingName != null ? pendingName : TarFormat.headerName(header)));
                    default -> throw new Refused("BINARY_UNREADABLE", "member type '"
                            + RDependencies.printable(String.valueOf(type)) + "'");
                }
            }
        } catch (ZipException e) {
            throw new Refused("BINARY_UNREADABLE", "not gzip: " + e.getMessage());
        } catch (SourceRefusal e) {
            throw new Refused("BINARY_UNREADABLE", e.getMessage());
        } catch (IOException e) {
            throw new Refused("BINARY_UNREADABLE", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        if (description == null) {
            throw new Refused("BINARY_LAYOUT", "no " + descriptionPath);
        }
        if (!meta) {
            throw new Refused("BINARY_LAYOUT", "no " + top + "Meta/package.rds: not an installed package");
        }
        Map<String, String> fields;
        try {
            List<Map<String, String>> records = Dcf.parse(description, 1);
            if (records.size() != 1) {
                throw new Refused("BINARY_DESCRIPTION", "the DESCRIPTION holds " + records.size() + " records");
            }
            fields = records.get(0);
        } catch (Dcf.Malformed e) {
            throw new Refused("BINARY_DESCRIPTION", e.getMessage());
        }
        if (!pkg.equals(fields.get("Package")) || !version.equals(fields.get("Version"))) {
            throw new Refused("BINARY_DESCRIPTION", "the DESCRIPTION names " + fields.get("Package") + " "
                    + fields.get("Version"));
        }
        checkBuilt(fields.get("Built"), rVersion, rPlatform);
        return new Checked(sha256(tgz), tgz.length, fields);
    }

    /**
     * {@code Built: R 4.6.1; x86_64-pc-linux-gnu; 2026-10-11 10:00:00 UTC; unix}: this R, this
     * platform or none (a package without compiled code), on unix.
     */
    static void checkBuilt(String built, String rVersion, String rPlatform) throws Refused {
        if (built == null) {
            throw new Refused("BINARY_BUILT", "the DESCRIPTION has no Built field");
        }
        String[] parts = built.split(";", -1);
        if (parts.length != 4) {
            throw new Refused("BINARY_BUILT", "Built is not 'R x; platform; date; os': " + built);
        }
        if (!parts[0].strip().equals("R " + rVersion)) {
            throw new Refused("BINARY_BUILT", "built with " + parts[0].strip() + ", not R " + rVersion);
        }
        String platform = parts[1].strip();
        if (!platform.isEmpty() && !platform.equals(rPlatform)) {
            throw new Refused("BINARY_BUILT", "built for " + platform + ", not " + rPlatform);
        }
        if (!parts[3].strip().equals("unix")) {
            throw new Refused("BINARY_BUILT", "built for OS type " + parts[3].strip());
        }
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
