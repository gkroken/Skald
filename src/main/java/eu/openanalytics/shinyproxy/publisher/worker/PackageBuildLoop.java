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
package eu.openanalytics.shinyproxy.publisher.worker;

import eu.openanalytics.shinyproxy.publisher.binaries.BinaryBuildQueue;
import eu.openanalytics.shinyproxy.publisher.binaries.BinaryBuildQueue.BuildClaim;
import eu.openanalytics.shinyproxy.publisher.binaries.BinaryGate;
import eu.openanalytics.shinyproxy.publisher.binaries.BinaryQueueSettings;
import eu.openanalytics.shinyproxy.publisher.binaries.BinaryUploader;
import eu.openanalytics.shinyproxy.publisher.binaries.SourceVerifier;
import eu.openanalytics.shinyproxy.publisher.build.Fenced;
import eu.openanalytics.shinyproxy.publisher.recipe.BaseCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * The shared binaries' work (T7b step 2e, part 1b): one thread verifies sources
 * (SourceVerifier), and up to {@code max-running} threads each compile and upload one package
 * at a time.
 *
 * <p>One compile, from a claim on:
 * <ol>
 *   <li>the gate is asked for the row's key BEFORE a sandbox is spent (ef929f5 N1): a base
 *       forge no longer registers is a failed attempt, REGISTRATION, and nothing runs;</li>
 *   <li>the claim's lease is renewed on a timer for the WHOLE attempt -- hand-in, sandbox and
 *       upload (ae287bf N2) -- and a renewal that fails is the driver's stop signal;</li>
 *   <li>the driver's outcome: Built -> the upload; Refused -> refuse; Failed -> fail; Stopped
 *       -> nothing. Every write is fenced by the lease, so after a lost lease each is refused
 *       (Fenced) -- the upload's intent before its PUT -- and nothing reaches forge or the row.</li>
 * </ol>
 * Every loop iteration catches everything: a thread that died would silently stop building.
 */
public final class PackageBuildLoop {

    /** One package's sandbox run (PackageBuildDriver#run in production). */
    public interface Runner {
        PackageBuildDriver.Result run(BuildClaim claim, BooleanSupplier stop) throws InterruptedException;
    }

    /** A base that can compile for a key: its catalog entry, published reference, image ID and runner. */
    public record Platform(BaseCatalog.Entry entry, String reference, Optional<String> imageId, Runner runner) { }

    private static final Logger log = LoggerFactory.getLogger(PackageBuildLoop.class);

    private final BinaryBuildQueue queue;
    private final SourceVerifier verifier;
    private final BinaryUploader uploader;
    private final BinaryGate gate;
    private final Map<String, List<Platform>> platforms;
    private final String owner;
    private final BinaryQueueSettings settings;
    private final Duration idlePause;
    private final ScheduledExecutorService renewals = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "skald-binary-renew");
        t.setDaemon(true);
        return t;
    });
    private final List<Thread> threads = new ArrayList<>();
    private volatile boolean running;

    public PackageBuildLoop(BinaryBuildQueue queue, SourceVerifier verifier, BinaryUploader uploader, BinaryGate gate,
                            Map<String, List<Platform>> platforms, String owner, BinaryQueueSettings settings,
                            Duration idlePause) {
        this.queue = queue;
        this.verifier = verifier;
        this.uploader = uploader;
        this.gate = gate;
        this.platforms = Map.copyOf(platforms);
        this.owner = owner;
        this.settings = settings;
        this.idlePause = idlePause;
    }

    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        threads.add(thread("skald-binary-verify", this::verifyOnce));
        for (int i = 0; i < settings.maxRunning(); i++) {
            threads.add(thread("skald-binary-compile-" + i, this::compileOnce));
        }
        threads.forEach(Thread::start);
    }

    private interface Step {
        boolean once() throws InterruptedException;
    }

    private Thread thread(String name, Step step) {
        Thread t = new Thread(() -> {
            while (running) {
                boolean worked;
                try {
                    worked = step.once();
                } catch (InterruptedException e) {
                    return;
                } catch (Throwable t2) {
                    log.warn("{}: an iteration failed; continuing", name, t2);
                    worked = false;
                }
                if (!worked) {
                    try {
                        Thread.sleep(idlePause.toMillis());
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
        }, name);
        t.setDaemon(true);
        return t;
    }

    public synchronized void stop() throws InterruptedException {
        running = false;
        for (Thread t : threads) {
            t.interrupt();
        }
        for (Thread t : threads) {
            t.join(Duration.ofSeconds(90).toMillis());
        }
        threads.clear();
        renewals.shutdownNow();
    }

    /** Verifies one PENDING_SOURCE row, if there is one. */
    boolean verifyOnce() {
        return verifier.verifyNext(owner).isPresent();
    }

    /** Compiles and uploads one QUEUED row, if one may be claimed. */
    boolean compileOnce() throws InterruptedException {
        Optional<BuildClaim> claimed = queue.claimBuild(owner);
        if (claimed.isEmpty()) {
            return false;
        }
        BuildClaim claim = claimed.get();
        AtomicBoolean lost = new AtomicBoolean();
        long every = settings.renewEvery().toMillis();
        ScheduledFuture<?> renewal = renewals.scheduleAtFixedRate(() -> {
            try {
                if (!queue.renew(claim.lease())) {
                    lost.set(true);
                }
            } catch (RuntimeException e) {
                lost.set(true);
            }
        }, every, every, TimeUnit.MILLISECONDS);
        try {
            Optional<Platform> platform = platformFor(claim);
            if (platform.isEmpty()) {
                return true;
            }
            PackageBuildDriver.Result result = platform.get().runner().run(claim, lost::get);
            // A lease lost meanwhile needs no check here: every write below is fenced by the
            // lease (the upload's intent, recorded before the PUT, first), so it is refused as
            // Fenced and nothing reaches forge.
            switch (result) {
                case PackageBuildDriver.Built b -> {
                    BinaryUploader.Result r = uploader.upload(claim, b.binary(), b.checked(), b.handedIn(),
                            platform.get().entry(), platform.get().reference(), platform.get().imageId());
                    log.info("binary {} {} ({}): {} {}", claim.pkg(), claim.version(), claim.platformKey(), r.outcome(),
                            r.detail());
                }
                case PackageBuildDriver.Refused r -> queue.refuse(claim.lease(), r.code(), r.detail());
                case PackageBuildDriver.Failed f -> queue.fail(claim.lease(), f.code(), f.detail());
                case PackageBuildDriver.Stopped s -> log.info("binary {} {}: stopped", claim.pkg(), claim.version());
            }
            return true;
        } catch (Fenced fenced) {
            log.warn("binary {} {}: no longer this holder's ({})", claim.pkg(), claim.version(), fenced.getMessage());
            return true;
        } finally {
            renewal.cancel(false);
        }
    }

    /**
     * The base that compiles for the claim's key: the first whose registration forge holds for
     * it (several R bases may share a key; forge binds it to one image). None: a failed attempt
     * with the gate's reason, before any sandbox runs (ef929f5 N1).
     */
    private Optional<Platform> platformFor(BuildClaim claim) {
        String reason = "no published R base has the key " + claim.platformKey();
        for (Platform p : platforms.getOrDefault(claim.platformKey(), List.of())) {
            BinaryGate.Decision d = gate.decide(p.entry(), p.imageId());
            if (d.binaries()) {
                return Optional.of(p);
            }
            reason = d.reason();
        }
        queue.fail(claim.lease(), "REGISTRATION", reason);
        return Optional.empty();
    }
}
