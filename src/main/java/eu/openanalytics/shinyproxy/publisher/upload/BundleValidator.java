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
package eu.openanalytics.shinyproxy.publisher.upload;

import eu.openanalytics.shinyproxy.publisher.bundle.BundleExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs bundle validation on this instance: {@code parallel} threads that claim VALIDATING
 * bundles, a renewal tick for the claims they hold, and the reaper (WORKPLAN-BUNDLES.md T8 plan).
 *
 * <p>Nothing here is the only copy of anything. The work is rows; a thread that dies, or an
 * instance that does, leaves a lease that lapses and a row another thread claims. An upload
 * only wakes the local threads ({@link #wake}) so a small bundle is judged at once; a full pool
 * means the row waits, never that an upload is refused.
 *
 * <p>Starting refuses a JVM without a UTF-8 file-name encoding (T5 carry, t5-f4f5f32-F4):
 * otherwise every bundle with a non-ASCII file name would fail validation one by one, for a
 * reason no publisher can fix.
 */
public class BundleValidator {

    private static final Logger log = LoggerFactory.getLogger(BundleValidator.class);

    /** The longest an idle thread waits before looking again without a wake. */
    static final Duration IDLE_POLL = Duration.ofSeconds(5);

    private final ValidationQueue queue;
    private final BundleValidation validation;
    private final ValidationSettings settings;
    private final String instance = UUID.randomUUID().toString();
    private final Object idle = new Object();
    private final Map<ValidationQueue.Claimed, Active> active = new ConcurrentHashMap<>();
    private final List<Thread> threads = new ArrayList<>();
    private volatile boolean running;
    private ScheduledExecutorService ticks;

    /** A claim being worked on, and how to stop it early. */
    private static final class Active {
        final AtomicBoolean lost = new AtomicBoolean();
        final AtomicReference<Closeable> source = new AtomicReference<>();
    }

    public BundleValidator(ValidationQueue queue, BundleValidation validation, ValidationSettings settings) {
        this.queue = queue;
        this.validation = validation;
        this.settings = settings;
    }

    public synchronized void start() throws IOException {
        BundleExtractor.requireUtf8FileNames();
        Path workspace = settings.workspaceOrDefault();
        Files.createDirectories(workspace);
        running = true;
        ticks = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "skald-validation-ticks");
            t.setDaemon(true);
            return t;
        });
        long renew = settings.renewEvery().toMillis();
        ticks.scheduleWithFixedDelay(this::renewAll, renew, renew, TimeUnit.MILLISECONDS);
        ticks.scheduleWithFixedDelay(this::reap, 0, renew, TimeUnit.MILLISECONDS);
        for (int i = 0; i < settings.parallel(); i++) {
            String owner = "validator:" + instance + "/" + i;
            Thread t = new Thread(() -> loop(owner), "skald-validator-" + i);
            t.setDaemon(true);
            threads.add(t);
            t.start();
        }
    }

    public synchronized void stop() {
        running = false;
        wake();
        if (ticks != null) {
            ticks.shutdownNow();
        }
        // Give back every claim held here before waiting for anything: a blocked store read or
        // a long extraction does not answer an interrupt, and a claim left to lapse would cost
        // its bundle a delay of up to a lease and one of its max-claims lapses (2a2186f-F2).
        // Marking it lost first makes the run end FENCED, not as a storage failure, when
        // closing its source breaks the read.
        for (Map.Entry<ValidationQueue.Claimed, Active> e : active.entrySet()) {
            lose(e.getKey(), e.getValue());
            try {
                queue.relinquished(e.getKey());
            } catch (RuntimeException ex) {
                log.warn("could not give back the validation claim on bundle {} at shutdown; it lapses"
                        + " after its lease: {}", e.getKey().bundleId(), ex.toString());
            }
        }
        for (Thread t : threads) {
            t.interrupt();
        }
        for (Thread t : threads) {
            try {
                t.join(Duration.ofSeconds(30).toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        threads.clear();
    }

    /** An upload committed a row: look now rather than at the next poll. */
    public void wake() {
        synchronized (idle) {
            idle.notifyAll();
        }
    }

    /** One claim, run to its end; empty when there was nothing to claim. Package-visible for tests. */
    Optional<BundleValidation.Result> runOnce(String owner) {
        Optional<ValidationQueue.Claimed> claimed = queue.claim(owner);
        if (claimed.isEmpty()) {
            return Optional.empty();
        }
        ValidationQueue.Claimed c = claimed.get();
        Active a = new Active();
        active.put(c, a);
        try {
            return Optional.of(validation.run(c, a.lost::get, a.source::set));
        } finally {
            active.remove(c);
        }
    }

    private void loop(String owner) {
        while (running) {
            try {
                if (runOnce(owner).isPresent()) {
                    continue;
                }
            } catch (RuntimeException e) {
                log.warn("bundle validation loop: {}; retrying", e.toString());
            }
            synchronized (idle) {
                if (!running) {
                    return;
                }
                try {
                    idle.wait(IDLE_POLL.toMillis());
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
    }

    /** Package-visible for tests. */
    void renewAll() {
        for (Map.Entry<ValidationQueue.Claimed, Active> e : active.entrySet()) {
            try {
                if (!queue.renew(e.getKey())) {
                    lose(e.getKey(), e.getValue());
                }
            } catch (RuntimeException ex) {
                // A renewal that could not reach the database is not a lost lease: the next
                // tick tries again, and the lease outlasts several missed ones.
                log.warn("could not renew the validation lease of bundle {}: {}", e.getKey().bundleId(),
                        ex.toString());
            }
        }
    }

    private static void lose(ValidationQueue.Claimed c, Active a) {
        a.lost.set(true);
        Closeable source = a.source.get();
        if (source != null) {
            try {
                source.close();
            } catch (IOException | RuntimeException ignored) {
                // Closing only ends the extraction sooner; the verdict is fenced either way.
            }
        }
        log.info("bundle {}: validation lease lost (generation {}); its result will be discarded",
                c.bundleId(), c.generation());
    }

    private void reap() {
        try {
            int n = queue.reap();
            if (n > 0) {
                log.info("rejected {} bundle(s) past their upload or validation deadline", n);
            }
        } catch (RuntimeException e) {
            log.warn("bundle reaping failed; retrying: {}", e.toString());
        }
    }
}
