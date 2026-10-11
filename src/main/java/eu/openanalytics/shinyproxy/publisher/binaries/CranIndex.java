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
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * The public repository's source index, {@code src/contrib/PACKAGES}: per package, the version
 * it serves now and that tarball's MD5sum, the only checksum CRAN publishes. Fetched at most
 * once per TTL and shared by every verification in this process.
 *
 * <p>A record without a well-formed Package, Version and MD5sum is skipped, not trusted. A
 * package listed twice is ambiguous and is answered as absent (never built), whatever the two
 * records say.
 */
final class CranIndex {

    /** What the index says of one package. */
    record Entry(String version, String md5) { }

    private static final Pattern NAME = Pattern.compile("[A-Za-z][A-Za-z0-9.]*[A-Za-z0-9]");
    private static final Pattern VERSION = Pattern.compile("[0-9]+([.-][0-9]+)+");
    private static final Pattern MD5 = Pattern.compile("[0-9a-f]{32}");
    // CRAN lists ~22,000 packages; ten times that is not an index.
    private static final int MAX_RECORDS = 250_000;

    private final SourceFetcher fetcher;
    private final URI uri;
    private final long maxBytes;
    private final long ttlNanos;
    private final LongSupplier nanoTime;
    private Map<String, Entry> entries;
    private long fetchedAt;

    CranIndex(SourceFetcher fetcher, URI repository, long maxBytes, long ttlNanos, LongSupplier nanoTime) {
        this.fetcher = fetcher;
        this.uri = repository.resolve("src/contrib/PACKAGES");
        this.maxBytes = maxBytes;
        this.ttlNanos = ttlNanos;
        this.nanoTime = nanoTime;
    }

    /** The package's entry, if the index lists it exactly once. */
    synchronized Optional<Entry> lookup(String pkg) throws IOException, SourceRefusal {
        long now = nanoTime.getAsLong();
        if (entries == null || now - fetchedAt >= ttlNanos) {
            entries = fetch();
            fetchedAt = now;
        }
        return Optional.ofNullable(entries.get(pkg));
    }

    private Map<String, Entry> fetch() throws IOException, SourceRefusal {
        byte[] body;
        try (InputStream in = fetcher.open(uri)) {
            body = in.readNBytes((int) Math.min(Integer.MAX_VALUE - 8, maxBytes + 1));
        } catch (SourceFetcher.NotFound ex) {
            // Not transient: the configured repository has no source index at all.
            throw new SourceRefusal("NO_PUBLIC_INDEX", "the repository serves no " + uri);
        }
        if (body.length > maxBytes) {
            throw new IOException("the index " + uri + " is larger than " + maxBytes + " bytes");
        }
        java.util.List<Map<String, String>> records;
        try {
            records = Dcf.parse(new String(body, StandardCharsets.UTF_8), MAX_RECORDS);
        } catch (Dcf.Malformed ex) {
            throw new IOException("the index " + uri + " is not a DCF document: " + ex.getMessage());
        }
        Map<String, Entry> out = new HashMap<>();
        Map<String, Integer> seen = new HashMap<>();
        for (Map<String, String> r : records) {
            String pkg = r.get("Package");
            String version = r.get("Version");
            String md5 = r.get("MD5sum");
            if (pkg == null || version == null || md5 == null || !NAME.matcher(pkg).matches()
                    || !VERSION.matcher(version).matches() || !MD5.matcher(md5).matches()) {
                continue;
            }
            if (seen.merge(pkg, 1, Integer::sum) > 1) {
                out.remove(pkg);
                continue;
            }
            out.put(pkg, new Entry(version, md5));
        }
        return out;
    }
}
