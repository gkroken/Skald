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

import eu.openanalytics.shinyproxy.publisher.build.BuildCoordinator.LostAttempt;
import eu.openanalytics.shinyproxy.publisher.storage.BuildLogWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Restart reconciliation for the worker side ("On restart reconcile DB records, worker
 * labels/handles and artifact receipts"): at startup and every {@code period} it disposes
 * of what a finished or vanished attempt left on this host, and finishes the logs of
 * attempts the platform lost.
 *
 * <p><b>Never a live attempt.</b> The inventory (DockerWorkerLauncher.attemptsPresent:
 * labelled containers, volumes and networks, volumes the daemon re-created under an
 * attempt's names, quota images, loop devices) is taken FIRST, the set of unsettled builds
 * (BuildCoordinator.unsettledBuildIds) SECOND. An attempt's objects exist only after its
 * claim has committed RUNNING, so anything in the inventory whose build was unfinished then
 * is still in the set read after it, and is left alone. That set counts QUEUED, RUNNING and
 * PUBLISHING whatever the lease says: an expired lease the reaper has not settled may
 * belong to a worker still stopping itself, here or on another instance. Only an id that is
 * a build id (32 hex) is ever disposed of; other attempt ids are not this platform's builds.
 * Disposal itself is label-checked (DockerWorkerLauncher.dispose), and a sweep that meets
 * an error tries again next period.
 *
 * <p><b>Nor its late calls (2fc53d9-F1).</b> A finished build's work thread can outlive it:
 * the driver returns after its settle while that thread is still in a daemon call, and a
 * call that binds a volume the sweep removed makes the daemon re-create it, unlabelled. So
 * the set also holds every build finished within a grace (longer than the settle), which
 * keeps the sweep from racing such a thread as a matter of routine. The grace is not a
 * bound, since a daemon call has none: what makes the end state clean is that disposal
 * also removes a volume re-created for a bind, and that thread's own disposal is its last
 * act; if the process dies first, the next sweep finds the volume by its name.
 *
 * <p><b>Lost logs.</b> A build the reaper made INTERRUPTED or TIMED_OUT may never have had
 * its log finished: its writer held the tail in memory and died, or stopped. For each such
 * build within {@code window} that has no final.json, the sweep writes one (create-only) with outcome
 * INTERRUPTED, fenced by the build's lease generation (0adc78e N2, e4306c6 N1), so a reader
 * is never left on a head that will never grow. Only once the build settled longer ago than
 * the sweep grace (01683f9-F1): until then its own writer may still be finishing it (on a
 * deadline it always is), and its finish names the reason and keeps the tail.
 */
public final class AttemptSweeper {

    private static final Logger log = LoggerFactory.getLogger(AttemptSweeper.class);
    private static final Pattern BUILD_ATTEMPT = Pattern.compile("[0-9a-f]{32}");
    /** How long stop() waits for a sweep: one helper removal's whole retry budget, and some. */
    static final Duration STOP_WAIT = Duration.ofMillis(
            DockerWorkerLauncher.REMOVAL_ATTEMPTS * DockerWorkerLauncher.REMOVAL_PAUSE_MILLIS + 10_000);

    /** What one sweep did. */
    public record Report(List<String> disposed, List<String> leftBehind, List<UUID> logsFinished) { }

    private final DockerWorkerLauncher launcher;
    private final Supplier<Set<UUID>> unsettled;
    private final Supplier<List<LostAttempt>> lost;
    private final BuildLogWriter logs;
    private final Duration period;
    private final java.util.function.Predicate<String> scope;
    private ScheduledExecutorService timer;

    public AttemptSweeper(DockerWorkerLauncher launcher, Supplier<Set<UUID>> unsettled,
                          Supplier<List<LostAttempt>> lost, BuildLogWriter logs, Duration period) {
        this(launcher, unsettled, lost, logs, period, id -> true);
    }

    /** As above, sweeping only attempt ids {@code scope} accepts (a test's own, on a shared host). */
    AttemptSweeper(DockerWorkerLauncher launcher, Supplier<Set<UUID>> unsettled, Supplier<List<LostAttempt>> lost,
                   BuildLogWriter logs, Duration period, java.util.function.Predicate<String> scope) {
        this.scope = scope;
        this.launcher = launcher;
        this.unsettled = unsettled;
        this.lost = lost;
        this.logs = logs;
        this.period = period;
    }

    public Report sweep() throws Exception {
        Set<String> present = launcher.attemptsPresent();
        Set<UUID> live = unsettled.get();
        List<String> disposed = new ArrayList<>();
        List<String> leftBehind = new ArrayList<>();
        for (String id : present) {
            if (!BUILD_ATTEMPT.matcher(id).matches() || !scope.test(id)) {
                continue;
            }
            UUID build = UUID.fromString(id.replaceFirst("(.{8})(.{4})(.{4})(.{4})(.{12})", "$1-$2-$3-$4-$5"));
            if (live.contains(build)) {
                continue;
            }
            List<String> left = launcher.dispose(DockerWorkerLauncher.handle(id));
            if (left.isEmpty()) {
                disposed.add(id);
            } else {
                leftBehind.add(id + " " + left);
            }
        }
        List<UUID> finished = new ArrayList<>();
        for (LostAttempt a : lost.get()) {
            // finalise() is create-only: a log its own writer finished (or an earlier sweep)
            // is left as it is, and only a missing final.json is written.
            if (logs.finalise(a.contentId(), a.buildId(), a.generation(), "INTERRUPTED", false).isPresent()) {
                finished.add(a.buildId());
            }
        }
        return new Report(disposed, leftBehind, finished);
    }

    public synchronized void start() {
        if (timer != null) {
            return;
        }
        timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "skald-attempt-sweeper");
            t.setDaemon(true);
            return t;
        });
        timer.scheduleWithFixedDelay(this::tick, 0, period.toMillis(), TimeUnit.MILLISECONDS);
    }

    void tick() {
        // Never throws: a scheduled task that throws is never run again (071ef50-F1).
        try {
            Report r = sweep();
            if (!r.disposed().isEmpty() || !r.leftBehind().isEmpty() || !r.logsFinished().isEmpty()) {
                log.info("attempt sweep: disposed {}, left behind {}, logs finished {}", r.disposed(), r.leftBehind(),
                        r.logsFinished());
            }
        } catch (Throwable failure) {
            log.warn("attempt sweep failed; retrying in {}", period, failure);
        }
    }

    /**
     * Stops the timer and waits, bounded, for a sweep in progress to end: interrupted, it
     * still removes its own helper container (DockerWorkerLauncher.runHelper), and a process
     * that exited at once would leave that helper behind.
     */
    public synchronized void stop() throws InterruptedException {
        if (timer != null) {
            timer.shutdownNow();
            if (!timer.awaitTermination(STOP_WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
                log.warn("attempt sweep did not end within {} of the stop", STOP_WAIT);
            }
            timer = null;
        }
    }
}
