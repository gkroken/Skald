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

import eu.openanalytics.shinyproxy.publisher.recipe.BaseCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The hook over the queue and forge's registration (T7b step 2e):
 * <ul>
 *   <li>{@link #binariesFor}: BinaryGate for the base and its published image ID;</li>
 *   <li>{@link #built}: called only for a build the gate turned binaries on for at its
 *       preparation (forge is not asked again here; the uploader rechecks before any upload,
 *       a16cf2a N2), the build's lock is recorded (its dependencies resolve against it, e149183) and every package in it is
 *       queued for the base's key, except one the base's library carries at the locked version
 *       (e149183 N4). Queueing is idempotent: a version already queued, built, refused or
 *       failed is left as it is. Every failure is logged, never thrown into the build.</li>
 * </ul>
 */
public final class BinaryEnqueuer implements BinaryHook {

    private static final Logger log = LoggerFactory.getLogger(BinaryEnqueuer.class);

    private final BinaryBuildQueue queue;
    private final BinaryGate gate;
    private final BaseCatalog catalog;

    public BinaryEnqueuer(BinaryBuildQueue queue, BinaryGate gate, BaseCatalog catalog) {
        this.queue = queue;
        this.gate = gate;
        this.catalog = catalog;
    }

    @Override
    public BinaryGate.Decision binariesFor(BaseCatalog.Resolved base) {
        BinaryGate.Decision d = gate.decide(base.entry(), catalog.imageId(base.entry().id()));
        if (!d.binaries()) {
            log.info("shared binaries off for base {}: {}", base.entry().id(), d.reason());
        }
        return d;
    }

    /**
     * T7c step 4 (user, 2026-10-19): every FAILED row of this platform that the published build's
     * lock names goes back to the queue. The build installed each such version and succeeded,
     * so it compiles on this base; a temporary failure (a lost BuildKit session, a stalled host)
     * no longer costs a package its binary for good, and a package that is really broken also
     * fails the app builds, so it is never re-opened by one. Only FAILED rows, never a REFUSED
     * or PRESENT_FOREIGN one (843ad74 N1); a row another build re-opened first is done, not an
     * error (N2); called after the build's outcome is settled, off its thread, outside its
     * transaction (BinaryHook). The audit names the build, whose row names its publisher (N5).
     *
     * <p>What it does not prove (N3, N4): a failure the app build cannot share -- the binary
     * path's own hand-in or dependency closure -- may fail again, up to max-attempts compiles
     * per published build that names the version, bounded by the publish rate; and a row
     * FAILED by a lost upload write on its last attempt (932cbd1 N1) has Skald's own binary in
     * forge, which this build may have installed instead of compiling: its re-open is then a
     * retry whose upload's 409 matches the row's own intent, and the row becomes UPLOADED.
     */
    private void reopenFailed(UUID buildId, String key, Map<String, String> lock, Map<String, String> library) {
        Map<String, String> asked = new java.util.TreeMap<>();
        lock.forEach((pkg, version) -> {
            if (!version.equals(library.get(pkg))) {
                asked.put(pkg, version);
            }
        });
        int reopened = 0;
        for (UUID row : queue.failed(key, asked)) {
            try {
                if (queue.reopenFailed(row, "build " + buildId).isPresent()) {
                    reopened++;
                }
            } catch (RuntimeException e) {
                log.warn("build {}: FAILED binary row {} not re-opened: {}", buildId, row, e.getMessage());
            }
        }
        if (reopened > 0) {
            log.info("build {}: {} FAILED binary row(s) back to the queue on {}: this build installed them",
                    buildId, reopened, key);
        }
    }

    @Override
    public void built(UUID buildId, BaseCatalog.Resolved base, Map<String, String> lock) {
        try {
            Optional<String> key = base.entry().platformKey();
            if (key.isEmpty()) {
                return;
            }
            queue.recordLock(buildId, lock);
            Map<String, String> library = base.entry().rRecommended();
            int queued = 0;
            for (Map.Entry<String, String> p : lock.entrySet()) {
                if (p.getValue().equals(library.get(p.getKey()))) {
                    continue;
                }
                try {
                    queue.enqueue(key.get(), p.getKey(), p.getValue(), buildId);
                    queued++;
                } catch (RuntimeException e) {
                    // One package (say, a name the queue's forms refuse) does not stop the rest.
                    log.warn("build {}: {} {} not queued: {}", buildId, p.getKey(), p.getValue(), e.getMessage());
                }
            }
            log.info("build {}: {} package version(s) asked for shared binaries on {}", buildId, queued, key.get());
            reopenFailed(buildId, key.get(), lock, library);
        } catch (RuntimeException e) {
            log.warn("build {}: queueing shared binaries failed; the build is unaffected", buildId, e);
        }
    }
}
