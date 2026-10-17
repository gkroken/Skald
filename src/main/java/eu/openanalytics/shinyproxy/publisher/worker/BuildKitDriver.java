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

import eu.openanalytics.shinyproxy.publisher.build.BuildDriver;
import eu.openanalytics.shinyproxy.publisher.recipe.RecipeGenerator.Recipe;
import eu.openanalytics.shinyproxy.publisher.storage.BuildLogWriter;
import eu.openanalytics.shinyproxy.publisher.storage.HeadTailLog;
import eu.openanalytics.shinyproxy.publisher.storage.LogFinal;
import eu.openanalytics.shinyproxy.publisher.storage.LogLines;
import eu.openanalytics.shinyproxy.publisher.worker.BuildKitClient.Credential;
import eu.openanalytics.shinyproxy.publisher.worker.BuildKitClient.Push;
import eu.openanalytics.shinyproxy.publisher.worker.DockerWorkerLauncher.Egress;
import eu.openanalytics.shinyproxy.publisher.worker.DockerWorkerLauncher.Handle;
import eu.openanalytics.shinyproxy.publisher.worker.DockerWorkerLauncher.Request;
import eu.openanalytics.shinyproxy.publisher.worker.WorkerProfile.Settings;
import org.mandas.docker.client.DockerClient;
import org.mandas.docker.client.DockerClient.LogsParam;
import org.mandas.docker.client.LogStream;
import org.mandas.docker.client.exceptions.NotFoundException;
import org.mandas.docker.client.messages.ContainerState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * The real {@link BuildDriver}: one rootless worker per attempt behind its own gateway
 * ({@link DockerWorkerLauncher}), the context and buildctl through {@link BuildKitClient}.
 *
 * <p><b>The stop bound (T6 carry (a), cab64b9 review N1).</b> Every Docker call, the
 * context preparation included, runs on ONE work thread. The caller's thread does nothing
 * but wait on it in slices of {@code poll} and ask the stop signal between them, so it
 * answers the signal within {@code poll} however long any call blocks: a launch waiting
 * for its daemon, a hung daemon request, a RUN step that sleeps. On a true signal it
 * SIGKILLs the worker and removes every container of the attempt, so all of its code
 * stops, then interrupts the work thread and waits for it at most {@code settle}. The
 * volumes, network and loop device are disposed of by the work thread itself as its last
 * act after a stop, and by this thread only if the work thread has ended: a Docker call
 * ignores the interrupt, so a create may land late, and removing a volume under it would
 * make Docker re-create that volume unlabelled (0c71146-F1). {@code poll} must be at most
 * renew_every / 2.
 *
 * <p><b>Worker death is State.Running, never State.OOMKilled (T5 gate F6 carry (1)).</b>
 * A RUN step that is OOM-killed inside the worker sets the WORKER's OOMKilled flag while
 * the daemon keeps running and building; the step's own failure, if the build does not
 * tolerate it, reaches the client as a failed build.
 *
 * <p>Failure details are short and printable ASCII: a build's last log line is the
 * publisher's code talking, and it is shown, not interpreted.
 */
public final class BuildKitDriver implements BuildDriver {

    /** What a claimed attempt is built from. Part 4 reads it from the bundle store. */
    public interface ContextSource {
        Prepared prepare(Claimed build) throws Exception;
    }

    /**
     * A verified payload on disk, its recipe, the repository to push to, its cleanup, its
     * dependency cache key (recipe.DependencyCacheKey) or null for no cache, and its inputs
     * for the provenance record (BundleContextSource#inputs), never null.
     */
    public record Prepared(Path payload, Recipe recipe, String repository, Runnable release, String cacheKey,
                           Map<String, Object> inputs, Runnable onBuilt) {

        public Prepared {
            inputs = Collections.unmodifiableMap(new LinkedHashMap<>(inputs));
            onBuilt = onBuilt == null ? () -> { } : onBuilt;
        }

        /** Nothing to do once the build succeeds. */
        public Prepared(Path payload, Recipe recipe, String repository, Runnable release, String cacheKey,
                        Map<String, Object> inputs) {
            this(payload, recipe, repository, release, cacheKey, inputs, null);
        }

        public Prepared(Path payload, Recipe recipe, String repository, Runnable release) {
            this(payload, recipe, repository, release, null, Map.of());
        }

        public Prepared(Path payload, Recipe recipe, String repository, Runnable release, String cacheKey) {
            this(payload, recipe, repository, release, cacheKey, Map.of());
        }
    }

    /** The deployment's choices for every attempt. */
    public record Config(Settings settings, int quotaMegabytes, Egress egress, Credential credential,
                         Duration poll, Duration settle, HeadTailLog.Limits logLimits) {

        public Config {
            if (poll.isNegative() || poll.isZero() || settle.isNegative()) {
                throw new IllegalArgumentException("poll must be positive and settle not negative");
            }
        }
    }

    static final int DETAIL_MAX = 200;
    /** Opens the build log's timing line: "[skald] phases: total 41.2 s: prepare 0.3, ...". */
    static final String PHASES = "phases: ";
    /** The build record's provenance document (V3). */
    static final String PROVENANCE_SCHEMA = "skald.build-provenance/v1";
    /** How long the decision waits for the rest of an exited client's output. */
    static final long FOLLOW_SETTLE_MILLIS = 30_000;
    private static final Logger log = LoggerFactory.getLogger(BuildKitDriver.class);
    /** Where a built attempt's onBuilt runs: one daemon thread, outside every attempt's stop window. */
    private static final java.util.concurrent.ExecutorService AFTER_BUILT =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "skald-after-built");
                t.setDaemon(true);
                return t;
            });

    private final DockerClient docker;
    private final DockerWorkerLauncher launcher;
    private final BuildKitClient client;
    private final ContextSource source;
    private final BuildLogWriter logs;
    private final Config config;
    /** Ready workers warmed with a base, or null: every attempt launches its own. */
    private final WorkerPool pool;
    /** Disposes of a settled attempt after run() returns, or null: run() disposes itself. */
    private final Disposer disposer;
    /** Mounts the base's blobs into the push's repositories before the build, or null: the push uploads them. */
    private volatile BlobMounter mounter;
    /** The last attempt's disposal on the disposer, so a test can wait for it. */
    volatile java.util.concurrent.Future<?> lastDisposal;
    /** The last attempt's work thread, so a test can wait for its own disposal. */
    volatile Thread lastWork;

    public BuildKitDriver(DockerClient docker, DockerWorkerLauncher launcher, BuildKitClient client,
                          ContextSource source, BuildLogWriter logs, Config config) {
        this(docker, launcher, client, source, logs, config, null, null);
    }

    /**
     * As above, taking a ready worker from {@code pool} when one is warmed with the build's base
     * (T7b, the fixed cost). The pool must launch its entries with this driver's request: one
     * pool per driver (4838310 N2).
     */
    public BuildKitDriver(DockerClient docker, DockerWorkerLauncher launcher, BuildKitClient client,
                          ContextSource source, BuildLogWriter logs, Config config, WorkerPool pool) {
        this(docker, launcher, client, source, logs, config, pool, null);
    }

    /**
     * As above; with a {@code disposer}, a settled attempt (built or failed, never stopped) is
     * disposed of there once its outcome is returned, off the critical path.
     */
    public BuildKitDriver(DockerClient docker, DockerWorkerLauncher launcher, BuildKitClient client,
                          ContextSource source, BuildLogWriter logs, Config config, WorkerPool pool,
                          Disposer disposer) {
        this.pool = pool;
        this.disposer = disposer;
        this.docker = docker;
        this.launcher = launcher;
        this.client = client;
        this.source = source;
        this.logs = logs;
        this.config = config;
    }

    /** With {@code mounter}, the base's blobs are mounted into the push's repositories before each build (3d18996-F1). */
    public BuildKitDriver withBlobMounter(BlobMounter mounter) {
        this.mounter = mounter;
        return this;
    }

    /** The attempt id the launcher names every object by: the build id's 32 hex digits. */
    static String attemptId(Claimed build) {
        return build.buildId().toString().replace("-", "");
    }

    @Override
    public Outcome run(Claimed build, BooleanSupplier cancelRequested) throws Exception {
        Handle h = DockerWorkerLauncher.handle(attemptId(build));
        HeadTailLog buildLog = new HeadTailLog(logs, build.contentId(), build.buildId(), build.lease().generation(),
                config.logLimits());
        AtomicBoolean aborted = new AtomicBoolean();
        AtomicReference<Outcome> result = new AtomicReference<>();
        AtomicReference<Throwable> crashed = new AtomicReference<>();
        AtomicReference<Map<String, Object>> record = new AtomicReference<>();
        AtomicReference<Runnable> afterBuilt = new AtomicReference<>();
        PhaseClock clock = new PhaseClock();
        Thread work = new Thread(() -> {
            try {
                result.set(attempt(build, h, aborted, buildLog, record, afterBuilt, clock));
            } catch (Throwable t) {
                crashed.set(t);
            } finally {
                if (aborted.get()) {
                    // The last thing this thread does: its calls are synchronous (a Jersey
                    // request ignores an interrupt), so anything a late call made exists by
                    // now, and nothing of the attempt is made after this (0c71146-F1).
                    // The stop's interrupt only served to wake this thread; left pending, it
                    // makes the first retry's sleep throw and the whole disposal give up on
                    // one transient daemon error (904eb75-F1). Cleared, the disposal runs to
                    // the end.
                    Thread.interrupted();
                    disposeQuietly(h, "work thread, after a stop");
                }
            }
        }, "skald-build-" + h.attemptId());
        lastWork = work;
        work.setDaemon(true);
        work.start();
        long poll = config.poll().toMillis();
        try {
            while (work.isAlive()) {
                if (cancelRequested.getAsBoolean()) {
                    aborted.set(true);
                    stopEverything(h, work);
                    String why = cancelRequested instanceof StopSignal s ? s.reason() : null;
                    buildLog.line("[skald] stopped" + (why == null ? "" : ": " + why), false);
                    buildLog.finish("STOPPED");
                    return new Stopped();
                }
                work.join(poll);
            }
        } catch (InterruptedException e) {
            aborted.set(true);
            stopEverything(h, work);
            throw e;
        }
        // The work is over; whatever it said, nothing of the attempt outlives this call.
        clock.lap("finish");
        if (disposer == null) {
            List<String> left = launcher.dispose(h, clock);
            if (!left.isEmpty()) {
                log.warn("attempt {}: disposal left {}", h.attemptId(), left);
            }
            clock.lap("dispose");
        }
        // Where the time went, for the owner and the pass probe (T7b, the fixed cost): the
        // last line before the outcome. A stopped attempt has none.
        buildLog.line("[skald] " + PHASES + clock.summary(), false);
        log.info("attempt {}: {}{}", h.attemptId(), PHASES, clock.summary());
        // A stop that arrives after the work ended stopped nothing, so the outcome is what
        // happened: Stopped would claim a stop that never occurred (T8 carry (4): the
        // attempt goes on to PUBLISHING with its cancel request recorded).
        Outcome outcome = crashed.get() != null
                ? new Failed("DRIVER_ERROR", detail(crashed.get().getClass().getSimpleName()))
                : result.get();
        Outcome done = withRecord(withLog(buildLog, outcome), record.get());
        // T7b, a16cf2a-F1: the binaries' bookkeeping (a lock, a queue of packages) runs only for
        // a build whose outcome IS Built, after it is settled, on its own thread: nothing about
        // it -- its length, a stop, an exception -- can turn a pushed image into another outcome.
        if (disposer != null) {
            // The work thread has ended: nothing of the attempt is in flight at the daemon.
            lastDisposal = disposer.submit("attempt " + h.attemptId(), () -> {
                PhaseClock d = new PhaseClock();
                List<String> left = launcher.dispose(h, d);
                log.info("attempt {}: disposed after its outcome: {}{}", h.attemptId(), PHASES, d.summary());
                return left;
            });
        }
        Runnable after = afterBuilt.get();
        if (done instanceof Built && after != null) {
            AFTER_BUILT.execute(() -> {
                try {
                    after.run();
                } catch (RuntimeException e) {
                    log.warn("attempt {}: after the build, {}", h.attemptId(), e.toString());
                }
            });
        }
        return done;
    }

    /**
     * A failure carries the provenance the attempt got as far as, whichever path failed it
     * (a build step, the log, a crash after the inputs were known): which base and lock a
     * failed build ran against is what its owner needs to see.
     */
    private static Outcome withRecord(Outcome outcome, Map<String, Object> record) {
        if (record != null && outcome instanceof Failed f && f.provenance() == null) {
            return new Failed(f.code(), f.detail(), record);
        }
        return outcome;
    }

    /**
     * The provenance record so far (schema skald.build-provenance/v1): the attempt, the
     * prepared inputs, the effective limits and the egress policy; the worker's side once it
     * is launched; the attestation once there is an image.
     */
    private Map<String, Object> record(Claimed build, Prepared prepared, Map<String, Object> worker,
                                       boolean attested) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("schema", PROVENANCE_SCHEMA);
        Map<String, Object> attempt = new LinkedHashMap<>();
        attempt.put("build_id", build.buildId().toString());
        attempt.put("content_id", build.contentId().toString());
        attempt.put("bundle_id", build.bundleId().toString());
        attempt.put("lease_generation", build.lease().generation());
        doc.put("attempt", attempt);
        doc.putAll(prepared.inputs());
        Settings s = config.settings();
        Map<String, Object> limits = new LinkedHashMap<>();
        limits.put("cpus", s.cpuQuota());
        limits.put("memory", s.memoryLimit());
        limits.put("pids", s.pidLimit());
        limits.put("tmpfs", s.tmpfsSize());
        limits.put("workspace_megabytes", config.quotaMegabytes());
        limits.put("step_log_max_bytes", s.stepLogMaxBytes());
        limits.put("step_log_max_bytes_per_second", s.stepLogMaxBytesPerSecond());
        doc.put("limits", limits);
        Map<String, Object> egress = new LinkedHashMap<>();
        egress.put("registry", config.egress().registry());
        egress.put("repositories", config.egress().repos());
        egress.put("private_mirrors", config.egress().privateMirrors());
        doc.put("egress", egress);
        if (worker != null) {
            doc.put("worker", worker);
        }
        if (attested) {
            doc.put("attestation", BuildKitClient.attestation());
        }
        return doc;
    }

    /**
     * Ends the attempt's log with its outcome. A success is a success only with a complete
     * log: if the log failed or could not be finished, the attempt is a log failure instead,
     * never a silent success (WORKPLAN-BUNDLES.md: "if durability cannot be recovered, stop
     * the build and report a storage/log failure").
     */
    private Outcome withLog(HeadTailLog buildLog, Outcome outcome) {
        String label = outcome instanceof Built ? "BUILT"
                : outcome instanceof Failed f ? "FAILED:" + f.code() : "STOPPED";
        if (outcome instanceof Failed f) {
            buildLog.line("[skald] failed: " + f.code() + " " + f.detail(), false);
        }
        Optional<LogFinal> fin = buildLog.finish(label);
        if (outcome instanceof Built && (fin.isEmpty() || !fin.get().complete())) {
            return new Failed("LOG_STORAGE", detail(buildLog.failure() != null ? buildLog.failure()
                    : "the build log could not be finished complete"));
        }
        return outcome;
    }

    /**
     * The stop path. Every process of the attempt ends at once: the worker by one SIGKILL,
     * then every container of the attempt. Volumes, the network and the loop device are NOT
     * touched while the work thread may still be in a daemon call: a create that lands
     * after a volume was removed makes Docker re-create that volume for its bind, unlabelled
     * and beyond any disposal (0c71146-F1). The full disposal is the work thread's own last
     * act after a stop; this thread does it too only if the work thread has ended within
     * {@code settle}.
     */
    private void stopEverything(Handle h, Thread work) throws InterruptedException {
        List<String> killed = launcher.killWorker(h);
        List<String> removed = launcher.removeContainers(h);
        work.interrupt();
        work.join(Math.max(1, config.settle().toMillis()));
        if (work.isAlive()) {
            log.warn("attempt {}: stopped; kill {}, containers {}; the work thread is still in a daemon"
                    + " call and disposes of the attempt when it returns", h.attemptId(), killed, removed);
            return;
        }
        disposeQuietly(h, "after a stop");
    }

    private void disposeQuietly(Handle h, String when) {
        try {
            List<String> left = launcher.dispose(h);
            if (!left.isEmpty()) {
                log.warn("attempt {}: disposal {} left {}", h.attemptId(), when, left);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("attempt {}: disposal {} interrupted", h.attemptId(), when);
        }
    }

    /** The work thread: every Docker call of the attempt. */
    private Outcome attempt(Claimed build, Handle h, AtomicBoolean aborted, HeadTailLog buildLog,
                            AtomicReference<Map<String, Object>> record, AtomicReference<Runnable> afterBuilt,
                            PhaseClock clock) throws Exception {
        buildLog.line("[skald] attempt " + h.attemptId() + ": preparing the build context", false);
        Prepared prepared;
        try {
            prepared = source.prepare(build);
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            return new Failed("CONTEXT", detail(e.getMessage()));
        }
        clock.lap("prepare");
        record.set(record(build, prepared, null, false));
        try {
            if (aborted.get()) {
                return new Stopped();
            }
            Push push = new Push(config.egress().registry(), prepared.repository(), build.buildId(),
                    config.credential(), prepared.cacheKey() == null ? null
                            : new BuildKitClient.Cache(build.contentId(), prepared.cacheKey()));
            // A ready worker warmed with this base, if the pool has one: it runs under its own
            // names (eff), and the attempt's own handle (h) reaches it through the marker, so
            // the stop path and every disposal below stay h's.
            String base = baseOf(prepared);
            Optional<Handle> pooled = pool == null || base == null ? Optional.empty() : pool.take(base, h.attemptId());
            Handle eff = pooled.orElse(h);
            if (pooled.isPresent()) {
                buildLog.line("[skald] taking a ready build worker", false);
                clock.lap("pool.take");
            } else {
                buildLog.line("[skald] launching the build worker", false);
                try {
                    launcher.launch(new Request(h.attemptId(), config.settings(), config.quotaMegabytes(),
                            config.egress()), clock);
                } catch (IllegalStateException e) {
                    return new Failed("WORKER_LAUNCH", detail(e.getMessage()));
                }
            }
            if (aborted.get()) {
                return new Stopped();
            }
            // What ran, by the images its containers run: an attempt whose worker cannot be
            // put on record builds nothing (V3: an image's inputs are on record, or it is not
            // the platform's).
            Map<String, Object> worker;
            try {
                worker = launcher.provenance(eff);
                if (pooled.isPresent()) {
                    worker = poolRecord(worker, eff, base);
                }
            } catch (IllegalStateException | org.mandas.docker.client.exceptions.DockerException e) {
                return new Failed("PROVENANCE", detail(e.getMessage()));
            }
            record.set(record(build, prepared, worker, false));
            clock.lap("provenance");
            try {
                client.stage(eff, prepared.payload(), prepared.recipe());
                clock.lap("stage");
                if (aborted.get()) {
                    return new Stopped();
                }
                mountBase(base, push, buildLog);
                clock.lap("mount");
                buildLog.line("[skald] building", false);
                client.start(eff, push);
                clock.lap("client.start");
            } catch (java.io.IOException | IllegalStateException
                     | org.mandas.docker.client.exceptions.DockerException e) {
                return new Failed("STAGE", detail(e.getMessage()));
            }
            Thread follower = follow(eff, buildLog);
            while (!aborted.get()) {
                buildLog.tick();
                if (buildLog.failed()) {
                    // The log could not be persisted: the build stops, and says why.
                    return new Failed("LOG_STORAGE", detail(buildLog.failure()));
                }
                var exit = client.exitCode(eff);
                if (exit.isPresent()) {
                    clock.lap("build");
                    // The client exited, so its output is ending: take all of it before
                    // deciding, since a failure's detail is its last line.
                    follower.join(FOLLOW_SETTLE_MILLIS);
                }
                // A push that completed is the outcome, whatever happens to the worker after.
                if (exit.isPresent() && exit.get() == 0) {
                    Built built;
                    try {
                        built = new Built(client.pushed(eff, push), record(build, prepared, worker, true));
                    } catch (IllegalStateException e) {
                        return new Failed("BUILD_RESULT", detail(e.getMessage()));
                    }
                    // T7b: the build's lock queues shared binaries -- but only once the outcome is
                    // settled, off this thread (a16cf2a-F1): run() hands it on after the work ends.
                    afterBuilt.set(prepared.onBuilt());
                    return built;
                }
                // The one death check, before a failed exit is read as the build's: a client
                // whose worker died fails too, and the cause is the worker. It also ends an
                // attempt whose client hangs on a dead worker.
                if (!workerRunning(eff)) {
                    return new Failed("WORKER_DIED", detail(workerState(eff)));
                }
                if (exit.isPresent()) {
                    return new Failed("BUILD_FAILED", detail("exit " + exit.get() + ": " + buildLog.lastLine()));
                }
                Thread.sleep(config.poll().toMillis());
            }
            return new Stopped();
        } finally {
            prepared.release().run();
        }
    }

    /** The build's base reference by digest, from its prepared inputs, or null. */
    /**
     * The base's blobs, mounted into the repositories the build pushes to, so the push sends
     * only the build's own layers (3d18996-F1). Never fails the build: whatever is not mounted,
     * the push uploads, as it did before.
     */
    private void mountBase(String base, Push push, HeadTailLog buildLog) throws InterruptedException {
        BlobMounter m = mounter;
        if (m == null || base == null) {
            return;
        }
        try {
            BlobMounter.Result r = m.mount(base, push.repositories());
            buildLog.line("[skald] base layers linked into the push's repositories: " + r.mounted()
                    + (r.notMounted() == 0 ? "" : ", " + r.notMounted() + " left for the push to upload"), false);
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            buildLog.line("[skald] base layers not linked, the push uploads them: " + detail(e.getMessage()), false);
        }
    }

    static String baseOf(Prepared prepared) {
        Object base = prepared.inputs().get("base");
        Object ref = base instanceof Map<?, ?> m ? m.get("reference") : null;
        return ref instanceof String r ? r : null;
    }

    /** The worker's provenance, saying it came from the pool: which entry, warmed with what. */
    static Map<String, Object> poolRecord(Map<String, Object> worker, Handle entry, String base) {
        Map<String, Object> out = new LinkedHashMap<>(worker);
        Map<String, Object> pooled = new LinkedHashMap<>();
        pooled.put("entry", entry.attemptId());
        pooled.put("warmed_with", base);
        pooled.put("never_used", true);
        out.put("pool", pooled);
        return out;
    }

    /**
     * Follows the client's stderr (buildctl's progress, every RUN step's output included)
     * into the log, on its own thread, until the client's output ends. Its stdout is the
     * metadata and is not followed.
     */
    private Thread follow(Handle h, HeadTailLog buildLog) {
        Thread t = new Thread(() -> {
            LogLines lines = new LogLines(config.logLimits().lineBytes(), buildLog::line);
            try (LogStream stream = docker.logs(h.client(), LogsParam.follow(), LogsParam.stderr())) {
                while (stream.hasNext()) {
                    lines.feed(stream.next().content());
                }
            } catch (Exception e) {
                buildLog.line("[skald] the build log stream ended: " + detail(e.getMessage()), false);
            } finally {
                lines.end();
            }
        }, "skald-log-" + h.attemptId());
        t.setDaemon(true);
        t.start();
        return t;
    }

    private boolean workerRunning(Handle h) throws Exception {
        try {
            return docker.inspectContainer(h.worker()).state().running();
        } catch (NotFoundException e) {
            return false;
        }
    }

    private String workerState(Handle h) throws Exception {
        try {
            ContainerState s = docker.inspectContainer(h.worker()).state();
            return "the worker exited " + s.exitCode() + (Boolean.TRUE.equals(s.oomKilled())
                    ? " (OOMKilled)" : "");
        } catch (NotFoundException e) {
            return "the worker is gone";
        }
    }

    /** Printable ASCII, at most {@link #DETAIL_MAX} characters; anything else becomes '?'. */
    static String detail(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < text.length() && b.length() < DETAIL_MAX; i++) {
            char c = text.charAt(i);
            b.append(c >= 0x20 && c < 0x7f ? c : '?');
        }
        return b.toString();
    }
}
