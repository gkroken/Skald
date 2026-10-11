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

import eu.openanalytics.shinyproxy.publisher.build.Fenced;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * The trusted side's source check (WORKPLAN-BUNDLES T7b step 2, point 2; step 2b): claims a
 * PENDING_SOURCE row and decides whether its package version is a public CRAN source that may
 * be compiled for the shared tree, and what it depends on.
 *
 * <p>In order, each refusal final for the row (its apps keep restoring it from source):
 * <ol>
 *   <li>NO_LOCK: the row has no recorded lock to resolve its dependencies against;</li>
 *   <li>NOT_IN_PUBLIC_INDEX: the public repository's index does not list this package at this
 *       version. CRAN's index lists only each package's CURRENT version, so an archived version
 *       is refused here: no checksum is published for it, and the plan's rule is a source
 *       verified against the public index's checksum;</li>
 *   <li>the tarball, from the same repository: NOT_IN_PUBLIC_SOURCE if absent; its MD5 must be
 *       the index's (MD5_MISMATCH); SOURCE_CHANGED if the row recorded other digests at a
 *       first fetch (a re-opened row);</li>
 *   <li>its DESCRIPTION names this package and version (DESCRIPTION_MISMATCH), and reads
 *       (DESCRIPTION_UNREADABLE; the tarball's own layout: SourceTarball);</li>
 *   <li>each Depends, Imports and LinkingTo package, R and the base packages excluded, is in the
 *       lock (NOT_IN_LOCK) at a version its constraint accepts (CONSTRAINT_UNMET). One the base
 *       image already carries in R's system library AT the locked version (a recommended
 *       package: Matrix, MASS, ...) is not a dependency to queue: the sandbox has it from the
 *       trusted base, whose digest is the platform registration (e149183 N4). The base's
 *       versions come from the catalog, never from inside a sandbox (9262bd8 N2).</li>
 * </ol>
 * Then the SHA-256 computed here over the bytes received, the MD5, and the dependencies at
 * their locked versions are recorded ({@link BinaryBuildQueue#recordSource}). A transfer that
 * fails or a repository that errs is a failed attempt, not a refusal.
 *
 * <p>The index and tarball transfers run under one {@link TransferGuard}: a timer renews the
 * lease while they run and, at {@code source-transfer-deadline} or on a lost lease, closes
 * them and interrupts this thread, so a server that stalls mid-body cannot hold it
 * (e149183-F1). No database call runs under the guard. A lost lease writes nothing.
 *
 * <p>An index whose version for the package is OLDER than the one claimed (a release newer
 * than the cached or proxied index) is fetched once more, ignoring the TTL (e149183 N3); still
 * older, the row is DEFERRED, not failed: back to PENDING_SOURCE with no attempt counted, not
 * claimable for {@code source-behind-retry}, doubling to {@code source-behind-max-retry}, and
 * refused INDEX_BEHIND only once {@code source-behind-bound} has passed since its first
 * deferral (9262bd8-F1: forge caches a proxy's index for a day, and two quick failed attempts
 * would have FAILED the row and refused its dependents for good).
 */
public final class SourceVerifier implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SourceVerifier.class);

    /** What one call did: the row, and how it ended (QUEUED, REFUSED:<code>, DEFERRED, FAILED, FENCED). */
    public record Result(java.util.UUID rowId, String outcome) { }

    private final BinaryBuildQueue queue;
    private final SourceFetcher fetcher;
    private final SourceSettings settings;
    private final BinaryQueueSettings queueSettings;
    private final CranIndex index;
    private final java.util.function.Function<String, Map<String, String>> baseLibrary;
    // Two timers: renewals (JDBC) on one, deadlines alone on the other (9262bd8 N1).
    private final ScheduledExecutorService beats = timer("skald-source-renew");
    private final ScheduledExecutorService deadlines = timer("skald-source-deadline");

    /** A verifier for platforms whose bases carry no R library to account for. */
    public SourceVerifier(BinaryBuildQueue queue, SourceFetcher fetcher, SourceSettings settings,
                          BinaryQueueSettings queueSettings) {
        this(queue, fetcher, settings, queueSettings, key -> Map.of());
    }

    /**
     * @param baseLibrary per platform key, the base's R system library (package -> version):
     *                    BaseCatalog.Entry#rRecommended of the base registered for that key
     */
    public SourceVerifier(BinaryBuildQueue queue, SourceFetcher fetcher, SourceSettings settings,
                          BinaryQueueSettings queueSettings,
                          java.util.function.Function<String, Map<String, String>> baseLibrary) {
        this.baseLibrary = baseLibrary;
        if (settings.repository() == null) {
            throw new IllegalArgumentException("no source-repository is configured");
        }
        this.queue = queue;
        this.fetcher = fetcher;
        this.settings = settings;
        this.queueSettings = queueSettings;
        this.index = new CranIndex(fetcher, settings.repository(), settings.maxIndexBytes(),
                settings.indexTtl().toNanos(), System::nanoTime);
    }

    /** Verifies the next PENDING_SOURCE row, if there is one. */
    public Optional<Result> verifyNext(String owner) {
        Optional<BinaryBuildQueue.SourceClaim> claimed = queue.claimSource(owner);
        if (claimed.isEmpty()) {
            return Optional.empty();
        }
        BinaryBuildQueue.SourceClaim claim = claimed.get();
        String what = claim.pkg() + " " + claim.version() + " (" + claim.platformKey() + ")";
        try {
            Checked checked = check(claim);
            BinaryBuildQueue.Recorded recorded = queue.recordSource(claim.lease(), checked.read().sha256(),
                    checked.read().md5(), checked.dependencies());
            log.info("binary source {}: {}", what, recorded);
            return Optional.of(new Result(claim.rowId(), recorded == BinaryBuildQueue.Recorded.QUEUED
                    ? "QUEUED" : "REFUSED:DEPENDENCY_CYCLE"));
        } catch (IndexBehind behind) {
            BinaryBuildQueue.Deferred d = queue.defer(claim.lease(), settings.behindRetry(), settings.behindMaxRetry(),
                    settings.behindBound(), "INDEX_BEHIND", behind.getMessage());
            log.info("binary source {}: {} ({})", what, d, behind.getMessage());
            return Optional.of(new Result(claim.rowId(), d == BinaryBuildQueue.Deferred.DEFERRED
                    ? "DEFERRED" : "REFUSED:INDEX_BEHIND"));
        } catch (SourceRefusal refusal) {
            queue.refuse(claim.lease(), refusal.code(), refusal.getMessage());
            log.info("binary source {} refused: {} {}", what, refusal.code(), refusal.getMessage());
            return Optional.of(new Result(claim.rowId(), "REFUSED:" + refusal.code()));
        } catch (Fenced | TransferGuard.LeaseLost lost) {
            log.warn("binary source {}: the lease was lost; nothing recorded", what);
            return Optional.of(new Result(claim.rowId(), "FENCED"));
        } catch (IOException failure) {
            String detail = RDependencies.printable(failure.getClass().getSimpleName() + ": " + failure.getMessage());
            try {
                queue.fail(claim.lease(), "SOURCE_FETCH", detail);
            } catch (Fenced fenced) {
                return Optional.of(new Result(claim.rowId(), "FENCED"));
            }
            log.warn("binary source {}: {}", what, detail);
            return Optional.of(new Result(claim.rowId(), "FAILED"));
        }
    }

    private record Checked(SourceTarball.Read read, List<BinaryBuildQueue.Dependency> dependencies) { }

    private Checked check(BinaryBuildQueue.SourceClaim claim) throws IOException, SourceRefusal, IndexBehind {
        Map<String, String> lock = queue.lockOf(claim.requestedBy()).orElseThrow(() -> new SourceRefusal(
                "NO_LOCK", "no recorded lock resolves this package's dependencies"));
        Optional<List<String>> recorded = queue.recordedSource(claim.rowId());
        CranIndex.Entry entry;
        SourceTarball.Read read;
        try (TransferGuard guard = new TransferGuard(beats, deadlines, settings.transferDeadline(), queueSettings.renewEvery(),
                () -> queue.renew(claim.lease()))) {
            entry = indexEntry(claim, guard);
            URI tarball = settings.repository().resolve("src/contrib/" + claim.pkg() + "_" + claim.version() + ".tar.gz");
            try (InputStream in = guard.watch(fetcher.open(tarball))) {
                read = SourceTarball.read(in, claim.pkg(), new SourceTarball.Limits(settings.maxSourceBytes(),
                        settings.maxExpandedBytes()));
            } catch (SourceFetcher.NotFound ex) {
                throw new SourceRefusal("NOT_IN_PUBLIC_SOURCE", "the public repository has no " + tarball.getPath());
            } catch (IOException ex) {
                throw guard.failure(ex);
            }
        }
        if (recorded.isPresent() && !recorded.get().equals(List.of(read.sha256(), read.md5()))) {
            // 604d63b N1: a tarball republished under the same version is refused, never
            // re-recorded (V4's trigger would refuse the write too).
            throw new SourceRefusal("SOURCE_CHANGED", "first fetched with sha256 " + recorded.get().get(0)
                    + "; now " + read.sha256());
        }
        if (!read.md5().equals(entry.md5())) {
            throw new SourceRefusal("MD5_MISMATCH", "the tarball's MD5 is " + read.md5() + "; the public index says "
                    + entry.md5());
        }
        Map<String, String> description = description(read.description());
        if (!claim.pkg().equals(description.get("Package")) || !claim.version().equals(description.get("Version"))) {
            throw new SourceRefusal("DESCRIPTION_MISMATCH", "the DESCRIPTION names " + description.get("Package")
                    + " " + description.get("Version"));
        }
        return new Checked(read, dependencies(description, lock, baseLibrary.apply(claim.platformKey())));
    }

    /** The index's entry for exactly the claimed version, refetched once if the index is behind. */
    private CranIndex.Entry indexEntry(BinaryBuildQueue.SourceClaim claim, TransferGuard guard)
            throws IOException, SourceRefusal, IndexBehind {
        Optional<CranIndex.Entry> entry = index.lookup(claim.pkg(), false, guard);
        if (entry.isPresent() && behind(entry.get(), claim.version())) {
            entry = index.lookup(claim.pkg(), true, guard);
            if (entry.isPresent() && behind(entry.get(), claim.version())) {
                throw new IndexBehind("the public index lists " + claim.pkg() + " " + entry.get().version()
                        + ", older than the locked " + claim.version());
            }
        }
        CranIndex.Entry found = entry.orElseThrow(() -> new SourceRefusal("NOT_IN_PUBLIC_INDEX",
                "the public index does not list " + claim.pkg()));
        if (!found.version().equals(claim.version())) {
            throw new SourceRefusal("NOT_IN_PUBLIC_INDEX", "the public index lists " + claim.pkg() + " "
                    + found.version() + ", not " + claim.version() + " (an archived version has no published checksum)");
        }
        return found;
    }

    /** The index lags the lock: time resolves it, a retry does not. */
    private static final class IndexBehind extends Exception {
        IndexBehind(String message) {
            super(RDependencies.printable(message));
        }
    }

    private static boolean behind(CranIndex.Entry entry, String version) {
        return RDependencies.compare(entry.version(), version) < 0;
    }

    private static ScheduledExecutorService timer(String name) {
        return Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        });
    }

    @Override
    public void close() {
        beats.shutdownNow();
        deadlines.shutdownNow();
    }

    private static Map<String, String> description(String text) throws SourceRefusal {
        List<Map<String, String>> records;
        try {
            records = Dcf.parse(text, 1);
        } catch (Dcf.Malformed ex) {
            throw new SourceRefusal("DESCRIPTION_UNREADABLE", ex.getMessage());
        }
        if (records.size() != 1) {
            throw new SourceRefusal("DESCRIPTION_UNREADABLE", "the DESCRIPTION holds " + records.size() + " records");
        }
        return records.get(0);
    }

    /**
     * The dependencies to queue at their locked versions, each checked against every
     * constraint on it; R, the base packages and what the base's library holds at the locked
     * version are not queued.
     */
    static List<BinaryBuildQueue.Dependency> dependencies(Map<String, String> description, Map<String, String> lock,
                                                          Map<String, String> baseLibrary) throws SourceRefusal {
        Map<String, List<RDependencies.Requirement>> byPackage = new LinkedHashMap<>();
        for (String field : RDependencies.FIELDS) {
            try {
                for (RDependencies.Requirement r : RDependencies.parse(field, description.get(field))) {
                    if (!r.pkg().equals("R") && !RDependencies.BASE.contains(r.pkg())) {
                        byPackage.computeIfAbsent(r.pkg(), k -> new ArrayList<>()).add(r);
                    }
                }
            } catch (RDependencies.Unreadable ex) {
                throw new SourceRefusal("DESCRIPTION_UNREADABLE", ex.getMessage());
            }
        }
        List<BinaryBuildQueue.Dependency> out = new ArrayList<>();
        for (Map.Entry<String, List<RDependencies.Requirement>> e : byPackage.entrySet()) {
            String version = lock.get(e.getKey());
            if (version == null) {
                throw new SourceRefusal("NOT_IN_LOCK", "depends on " + e.getKey() + ", which the lock does not fix");
            }
            for (RDependencies.Requirement r : e.getValue()) {
                if (!r.satisfiedBy(version)) {
                    throw new SourceRefusal("CONSTRAINT_UNMET", "needs " + r + "; the lock fixes " + version);
                }
            }
            if (!version.equals(baseLibrary.get(e.getKey()))) {
                out.add(new BinaryBuildQueue.Dependency(e.getKey(), version));
            }
        }
        return out;
    }
}
