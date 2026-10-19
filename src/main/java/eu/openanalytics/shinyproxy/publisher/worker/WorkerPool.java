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

import eu.openanalytics.shinyproxy.publisher.worker.BuildKitClient.Credential;
import eu.openanalytics.shinyproxy.publisher.worker.DockerWorkerLauncher.Handle;
import eu.openanalytics.shinyproxy.publisher.worker.DockerWorkerLauncher.Request;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * A small pool of pre-launched, never-used workers, each warmed with one published base
 * (T7b, the fixed cost: a launch costs about 10 s and every attempt pulled and extracted its
 * base again). Each entry is launched exactly as an attempt's worker is ({@code requestFor}),
 * then its base is pulled and extracted by a trusted warm-up client; no bundle has touched it.
 * An attempt takes one ({@link DockerWorkerLauncher#take}: a marker labelled with the attempt,
 * so the attempt's own stop, disposal and sweep reach it), uses it once, and disposes of it
 * with the rest of the attempt. Nothing is shared between attempts: one entry, one attempt.
 *
 * <p>One thread does every Docker call of the pool. It keeps {@code size} ready entries per
 * base, disposes of its own untaken entries at {@code ttl} and replaces them, and disposes of
 * any instance's untaken entry older than {@code ttl + MARGIN}: its id carries its launch time,
 * and a live owner never keeps one that long. {@link #take} hands out only entries younger than
 * {@code ttl - MARGIN}, so the margin covers a take in flight on either side.
 */
public final class WorkerPool {

    /** The pool's choices: entries per base (0: no pool), how long an untaken entry is kept. */
    public record Settings(int size, Duration ttl, Duration warmDeadline, Duration period) {

        public Settings {
            if (size < 0 || size > 8) {
                throw new IllegalArgumentException("pool size " + size + " is not 0-8");
            }
            if (ttl.compareTo(MARGIN.multipliedBy(3)) < 0) {
                throw new IllegalArgumentException("pool ttl " + ttl + " must be at least " + MARGIN.multipliedBy(3));
            }
            if (warmDeadline.isNegative() || warmDeadline.isZero() || period.isNegative() || period.isZero()) {
                throw new IllegalArgumentException("the warm-up deadline and the period must be positive");
            }
        }

        public static Settings defaults() {
            return new Settings(1, Duration.ofMinutes(30), Duration.ofMinutes(10), Duration.ofSeconds(5));
        }
    }

    /**
     * The pools of one configuration, stopped together when its bean is destroyed: after the
     * loop that takes from them, which depends on this bean.
     */
    public static final class Group {

        private final List<WorkerPool> pools = new java.util.concurrent.CopyOnWriteArrayList<>();

        /** Adds and starts a pool. */
        public void start(WorkerPool pool) {
            pools.add(pool);
            pool.start();
        }

        public void stop() throws InterruptedException {
            for (WorkerPool pool : pools) {
                pool.stop();
            }
        }

        /** Whether every pool has a takeable entry for every base (for probes measuring a warm server). */
        public boolean warm() {
            return pools.stream().allMatch(WorkerPool::warm);
        }

        /** Every pool's ready entries (for probes waiting for a quiet host). */
        java.util.Set<String> readyEntries() {
            java.util.Set<String> out = new java.util.HashSet<>();
            pools.forEach(p -> p.ready().values().forEach(out::addAll));
            return out;
        }
    }

    static final Duration MARGIN = Duration.ofMinutes(5);
    /** How often the stale sweep runs: its inventory starts a privileged helper. */
    static final Duration SWEEP_EVERY = Duration.ofMinutes(5);
    /** After a failed launch or warm-up for a base, how long before the next try. */
    static final Duration BACKOFF = Duration.ofMinutes(1);
    private static final Logger log = LoggerFactory.getLogger(WorkerPool.class);

    private final DockerWorkerLauncher launcher;
    private final BuildKitClient client;
    private final Function<String, Request> requestFor;
    private final List<String> bases;
    private final String registry;
    private final Credential credential;
    private final Settings settings;
    private final LongSupplier epochSeconds;
    private final java.util.random.RandomGenerator random = new java.security.SecureRandom();
    /** Ready entries per base, oldest first. Guarded by this. */
    private final Map<String, Deque<String>> ready = new LinkedHashMap<>();
    /** Per base, when the next launch may be tried (epoch seconds). Pool thread only. */
    private final Map<String, Long> notBefore = new LinkedHashMap<>();
    private volatile boolean running;
    /** Set by stop(): no further entry is made. */
    private volatile boolean stopping;
    /** Entries this pool tried to make, for tests. */
    final java.util.concurrent.atomic.AtomicInteger fills = new java.util.concurrent.atomic.AtomicInteger();
    /** Rounds completed; for tests that must observe a round's end rather than guess its length. */
    final java.util.concurrent.atomic.AtomicInteger rounds = new java.util.concurrent.atomic.AtomicInteger();
    private volatile HostCapacity capacity;
    /** When the stale sweep last ran (epoch seconds). Pool thread only. */
    private long lastSweep = Long.MIN_VALUE;
    private Thread thread;

    /**
     * {@code requestFor} turns an entry id into the launch request an attempt of this driver
     * would make (same settings, quota, egress, output); {@code bases} are the published bases
     * by digest; {@code registry} and {@code credential} the warm-up's pull.
     */
    public WorkerPool(DockerWorkerLauncher launcher, BuildKitClient client, Function<String, Request> requestFor,
                      List<String> bases, String registry, Credential credential, Settings settings) {
        this(launcher, client, requestFor, bases, registry, credential, settings,
                () -> java.time.Instant.now().getEpochSecond());
    }

    WorkerPool(DockerWorkerLauncher launcher, BuildKitClient client, Function<String, Request> requestFor,
               List<String> bases, String registry, Credential credential, Settings settings, LongSupplier epochSeconds) {
        for (String base : bases) {
            BuildKitClient.warmDockerfile(base);
        }
        this.launcher = launcher;
        this.client = client;
        this.requestFor = requestFor;
        this.bases = List.copyOf(new java.util.LinkedHashSet<>(bases));
        this.registry = registry;
        this.credential = credential;
        this.settings = settings;
        this.epochSeconds = epochSeconds;
        for (String base : this.bases) {
            ready.put(base, new ArrayDeque<>());
        }
    }

    /**
     * A ready entry warmed with {@code base}, marked as the attempt's, or empty (no pool, none
     * ready, or the mark failed: the entry is then left to the pool thread's sweep).
     */
    public Optional<Handle> take(String base, String attemptId) throws InterruptedException {
        String entry;
        synchronized (this) {
            Deque<String> q = ready.get(base);
            // The oldest still young enough; one too old to hand out stays for the pool thread
            // to dispose of at its ttl.
            entry = q == null ? null : q.stream()
                    .filter(e -> age(e) < settings.ttl().minus(MARGIN).toSeconds()).findFirst().orElse(null);
            if (entry != null) {
                q.remove(entry);
            }
            notifyAll();
        }
        if (entry == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(launcher.take(entry, attemptId));
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            log.warn("pool entry {}: could not be taken for {}: {}", entry, attemptId, e.toString());
            return Optional.empty();
        }
    }

    /** Whether every base has a takeable entry. */
    boolean warm() {
        return bases.stream().allMatch(b -> takeable(b) > 0);
    }

    /** Ready entries per base, for tests and logs. */
    synchronized Map<String, List<String>> ready() {
        Map<String, List<String>> out = new LinkedHashMap<>();
        ready.forEach((b, q) -> out.put(b, List.copyOf(q)));
        return out;
    }

    private long age(String entry) {
        return epochSeconds.getAsLong() - DockerWorkerLauncher.poolEntryEpochSeconds(entry);
    }

    public synchronized void start() {
        if (settings.size() == 0 || thread != null) {
            return;
        }
        running = true;
        thread = new Thread(this::loop, "skald-worker-pool");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Stops the pool thread; its last act disposes of every untaken entry it made (a Docker call
     * ignores an interrupt, so only that thread knows when its last create has landed).
     */
    public void stop() throws InterruptedException {
        Thread t;
        stopping = true;
        synchronized (this) {
            running = false;
            t = thread;
            notifyAll();
        }
        if (t != null) {
            t.interrupt();
            t.join(Duration.ofMinutes(3).toMillis());
            return;
        }
        // No pool thread ran (rounds were driven by hand): nothing is in flight, so this thread
        // disposes of the ready entries itself.
        List<String> mine = new ArrayList<>();
        synchronized (this) {
            ready.values().forEach(mine::addAll);
            ready.values().forEach(Deque::clear);
        }
        for (String entry : mine) {
            disposeQuietly(entry, "pool stopping");
        }
    }

    private void loop() {
        try {
            while (running) {
                try {
                    tick();
                    rounds.incrementAndGet();
                } catch (InterruptedException e) {
                    break;
                } catch (Throwable t) {
                    log.warn("worker pool: a round failed; continuing", t);
                }
                synchronized (this) {
                    if (running) {
                        wait(settings.period().toMillis());
                    }
                }
            }
        } catch (InterruptedException e) {
            // stopping
        } finally {
            Thread.interrupted();
            List<String> mine = new ArrayList<>();
            synchronized (this) {
                ready.values().forEach(mine::addAll);
                ready.values().forEach(Deque::clear);
            }
            for (String entry : mine) {
                disposeQuietly(entry, "pool stopping");
            }
        }
    }

    /**
     * Makes each refill wait for a free slot of the host's capacity, and wakes this pool's
     * loop as soon as one is released rather than at its next round (T7c step 2).
     */
    public WorkerPool withCapacity(HostCapacity capacity) {
        this.capacity = capacity;
        capacity.onFree(this::wake);
        return this;
    }

    private synchronized void wake() {
        notifyAll();
    }

    /** One round: expire, sweep, refill. Package-visible for tests. */
    void tick() throws Exception {
        // Our own untaken entries past the ttl: disposed of, then replaced below.
        List<String> expired = new ArrayList<>();
        synchronized (this) {
            for (Deque<String> q : ready.values()) {
                q.removeIf(e -> {
                    if (age(e) >= settings.ttl().toSeconds()) {
                        expired.add(e);
                        return true;
                    }
                    return false;
                });
            }
        }
        for (String e : expired) {
            disposeQuietly(e, "expired");
        }
        if (lastSweep == Long.MIN_VALUE || epochSeconds.getAsLong() - lastSweep >= SWEEP_EVERY.toSeconds()) {
            lastSweep = epochSeconds.getAsLong();
            sweepStale();
        }
        for (String base : bases) {
            while (!stopping && takeable(base) < settings.size()
                    && epochSeconds.getAsLong() >= notBefore.getOrDefault(base, 0L)) {
                // A warm-up only while the host has a slot free (T7c step 2); otherwise the
                // refill waits, and the capacity wakes this pool when a slot is released.
                HostCapacity c = capacity;
                Optional<HostCapacity.Slot> slot = c == null ? Optional.of(() -> { }) : c.warmUp();
                if (slot.isEmpty()) {
                    return;
                }
                String entry = DockerWorkerLauncher.poolEntryId(epochSeconds.getAsLong(), random);
                try (HostCapacity.Slot held = slot.get()) {
                    if (!fill(base, entry)) {
                        notBefore.put(base, epochSeconds.getAsLong() + BACKOFF.toSeconds());
                    }
                }
            }
        }
    }

    /**
     * Ready entries {@link #take} would still hand out. An entry past ttl - MARGIN no longer
     * counts, so its replacement is made while it waits out its ttl (4838310 N1: with size 1,
     * the last 5 min of every cycle had nothing to hand out).
     */
    private synchronized int takeable(String base) {
        return (int) ready.get(base).stream().filter(e -> age(e) < settings.ttl().minus(MARGIN).toSeconds()).count();
    }

    /** Launches and warms one entry; true once it is ready. A failure disposes of it. */
    private boolean fill(String base, String entry) throws InterruptedException {
        fills.incrementAndGet();
        PhaseClock clock = new PhaseClock();
        try {
            Handle h = launcher.launch(requestFor.apply(entry), clock);
            client.warm(h, registry, credential, base, settings.warmDeadline());
            clock.lap("warm");
        } catch (InterruptedException e) {
            disposeQuietly(entry, "interrupted while it was made");
            throw e;
        } catch (Exception e) {
            log.warn("pool entry {} for {}: {}; retrying in {}", entry, base, e.getMessage(), BACKOFF);
            disposeQuietly(entry, "failed");
            return false;
        }
        synchronized (this) {
            ready.get(base).addLast(entry);
        }
        log.info("pool entry {} ready for {}: {}{}", entry, base, BuildKitDriver.PHASES, clock.summary());
        return true;
    }

    /**
     * Any instance's untaken entry older than ttl + MARGIN is a dead process's: no live owner
     * keeps one past its ttl. A taken one is its attempt's, swept with the attempt.
     */
    void sweepStale() throws Exception {
        java.util.Set<String> mine = new java.util.HashSet<>();
        synchronized (this) {
            ready.values().forEach(mine::addAll);
        }
        long limit = settings.ttl().plus(MARGIN).toSeconds();
        for (String id : launcher.attemptsPresent()) {
            if (DockerWorkerLauncher.POOL_ENTRY.matcher(id).matches() && !mine.contains(id)
                    && age(id) > limit && !launcher.taken(id)) {
                disposeQuietly(id, "stale");
            }
        }
    }

    private void disposeQuietly(String entry, String why) {
        try {
            List<String> left = launcher.dispose(DockerWorkerLauncher.handle(entry));
            if (!left.isEmpty()) {
                log.warn("pool entry {} ({}): disposal left {}", entry, why, left);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("pool entry {} ({}): disposal interrupted", entry, why);
        }
    }
}
