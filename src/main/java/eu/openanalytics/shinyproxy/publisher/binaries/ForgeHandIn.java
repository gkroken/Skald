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

import eu.openanalytics.shinyproxy.publisher.binaries.BinaryBuildQueue.BuildClaim;
import eu.openanalytics.shinyproxy.publisher.binaries.BinaryBuildQueue.BuiltDependency;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * The trusted side's hand-in over forge (T7b step 2d): the source from the public repository
 * the source check read (the proxy member; for an 'archive' row its Archive path), every
 * closure member's binary from Skald's own binary tree for the key with the forge token, and
 * the claimed row's lock. Never the group's URL (89a33dc N1, c365d95-F1). Each transfer is
 * bounded in bytes and, from outside the read, in wall time (TransferGuard, e149183-F1);
 * anything that goes wrong is an IOException: a failed attempt, never a refusal (9159de6 N2).
 */
public final class ForgeHandIn implements PackageHandIn, AutoCloseable {

    private final BinaryBuildQueue queue;
    private final SourceFetcher sources;
    private final SourceSettings sourceSettings;
    private final ForgeSettings forge;
    private final BinaryArchive.Limits limits;
    private final HttpClient http;
    private final ScheduledExecutorService beats = daemon("skald-handin-renew");
    private final ScheduledExecutorService deadlines = daemon("skald-handin-deadline");

    public ForgeHandIn(BinaryBuildQueue queue, SourceFetcher sources, SourceSettings sourceSettings, ForgeSettings forge,
                       BinaryArchive.Limits limits) {
        if (sourceSettings.repository() == null || forge.url() == null) {
            throw new IllegalStateException("a hand-in needs the source repository and forge");
        }
        this.queue = queue;
        this.sources = sources;
        this.sourceSettings = sourceSettings;
        this.forge = forge;
        this.limits = limits;
        this.http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(forge.timeout()).build();
    }

    private static ScheduledExecutorService daemon(String name) {
        return Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * The source from the path its kind names first, and from the other one if that answers
     * 404 (0b37c7c-F1): a version verified while current is archived by CRAN later -- a row
     * waiting behind a slow dependency, or re-opened weeks on -- and its kind, the FIRST
     * fetch's, stays 'index'. Integrity does not depend on the path: PackageRecipe checks the
     * bytes against the digests the first fetch recorded either way.
     */
    @Override
    public byte[] source(BuildClaim claim) throws IOException {
        String file = claim.pkg() + "_" + claim.version() + ".tar.gz";
        String current = "src/contrib/" + file;
        String archived = "src/contrib/Archive/" + claim.pkg() + "/" + file;
        boolean isArchive = claim.sourceKind() == BinaryBuildQueue.SourceKind.ARCHIVE;
        try {
            return fetchSource(sourceSettings.repository().resolve(isArchive ? archived : current));
        } catch (SourceFetcher.NotFound first) {
            return fetchSource(sourceSettings.repository().resolve(isArchive ? current : archived));
        }
    }

    private byte[] fetchSource(URI uri) throws IOException {
        try (TransferGuard guard = guard()) {
            try (InputStream in = guard.watch(sources.open(uri))) {
                return bounded(in, sourceSettings.maxSourceBytes(), uri);
            } catch (SourceFetcher.NotFound e) {
                throw e;
            } catch (IOException e) {
                throw guard.failure(e);
            }
        }
    }

    @Override
    public byte[] dependency(BuildClaim claim, BuiltDependency d) throws IOException {
        URI uri = ForgeTree.binary(forge, claim.platformKey(), d.pkg(), d.version());
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(forge.timeout()).GET()
                .header("Authorization", "Bearer " + forge.token()).build();
        try (TransferGuard guard = guard()) {
            HttpResponse<InputStream> response;
            try {
                response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new java.io.InterruptedIOException("interrupted fetching " + uri.getPath());
            }
            try (InputStream in = guard.watch(response.body())) {
                if (response.statusCode() != 200) {
                    throw new IOException("forge answered " + response.statusCode() + " for " + uri.getPath());
                }
                return bounded(in, limits.maxBytes(), uri);
            } catch (IOException e) {
                throw guard.failure(e);
            }
        }
    }

    @Override
    public Map<String, String> lock(BuildClaim claim) {
        return queue.lockOf(claim.requestedBy()).orElse(Map.of());
    }

    /** A transfer's wall bound; the sandbox's own lease is the driver's stop signal, not renewed here. */
    private TransferGuard guard() {
        return new TransferGuard(beats, deadlines, sourceSettings.transferDeadline(), sourceSettings.transferDeadline(),
                () -> true);
    }

    private static byte[] bounded(InputStream in, long max, URI uri) throws IOException {
        byte[] bytes = in.readNBytes((int) Math.min(Integer.MAX_VALUE - 8, max + 1));
        if (bytes.length > max) {
            throw new IOException(uri.getPath() + " is over " + max + " bytes");
        }
        return bytes;
    }

    @Override
    public void close() {
        beats.shutdownNow();
        deadlines.shutdownNow();
    }
}
