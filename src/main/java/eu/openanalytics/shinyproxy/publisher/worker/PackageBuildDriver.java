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

import eu.openanalytics.shinyproxy.publisher.binaries.BinaryArchive;
import eu.openanalytics.shinyproxy.publisher.binaries.BinaryBuildQueue.BuildClaim;
import eu.openanalytics.shinyproxy.publisher.binaries.BinaryBuildQueue.BuiltDependency;
import eu.openanalytics.shinyproxy.publisher.binaries.PackageClosure;
import eu.openanalytics.shinyproxy.publisher.binaries.PackageHandIn;
import eu.openanalytics.shinyproxy.publisher.binaries.PackageRecipe;
import eu.openanalytics.shinyproxy.publisher.binaries.PackageRecipe.HandedIn;
import eu.openanalytics.shinyproxy.publisher.recipe.RecipeGenerator.Recipe;
import eu.openanalytics.shinyproxy.publisher.worker.BuildKitClient.Credential;
import eu.openanalytics.shinyproxy.publisher.worker.DockerWorkerLauncher.Egress;
import eu.openanalytics.shinyproxy.publisher.worker.DockerWorkerLauncher.Handle;
import eu.openanalytics.shinyproxy.publisher.worker.DockerWorkerLauncher.Request;
import eu.openanalytics.shinyproxy.publisher.worker.WorkerProfile.Settings;
import org.mandas.docker.client.DockerClient;
import org.mandas.docker.client.exceptions.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * One package's sandbox, run (WORKPLAN-BUNDLES T7b step 2, point 4; step 2c part 2): the
 * trusted side fetches what is handed in, renders {@link PackageRecipe}, launches a T5 worker
 * whose gateway allows the base's registry and nothing else, stages the context, runs a
 * local-output client, collects the one file the build exported through the Docker API, and
 * checks it ({@link BinaryArchive}). Nothing is uploaded here (step 2d).
 *
 * <p>Like {@link BuildKitDriver}: every Docker call runs on one work thread; the caller only
 * waits on it in slices of {@code poll} and asks the stop signal (the lease) between them, so a
 * lost lease or a shutdown stops the sandbox within {@code poll}: the worker is SIGKILLed and
 * every container removed, and the work thread disposes of the rest as its last act.
 */
public final class PackageBuildDriver {

    /** What the trusted side hands in for a claim: {@link PackageHandIn} (ForgeHandIn over forge). */
    public interface HandIn extends PackageHandIn { }

    /** Renders the recipe; PackageRecipe in production, a stand-in in Docker tests without R. */
    public interface Renderer {
        Recipe render(HandedIn source, List<HandedIn> dependencies) throws PackageRecipe.DigestMismatch;
    }

    /** How a sandbox ended. */
    public sealed interface Result permits Built, Refused, Failed, Stopped { }

    /**
     * A checked binary, ready for the upload, with the closure actually handed in (after
     * PackageClosure), which the upload's provenance and LinkingTo name (9159de6 N4).
     */
    public record Built(byte[] binary, BinaryArchive.Checked checked, List<BuiltDependency> handedIn)
            implements Result { }

    /** Final for this row: retrying would hand in and produce the same thing. */
    public record Refused(String code, String detail) implements Result { }

    /** A failed attempt: retried up to the queue's max-attempts. */
    public record Failed(String code, String detail) implements Result { }

    /** Stopped by the signal: nothing to record. */
    public record Stopped() implements Result { }

    /**
     * A deployment's choices. The egress is the base's registry ONLY: the install RUN has no
     * network, and the daemon needs nothing but the base (no mirror, no private repository).
     */
    public record Config(Settings settings, int quotaMegabytes, Egress egress, Credential credential,
                         Duration poll, Duration settle, String rVersion, String rPlatform,
                         BinaryArchive.Limits archiveLimits) {
        public Config {
            if (!egress.repos().isEmpty() || !egress.privateMirrors().isEmpty()) {
                throw new IllegalArgumentException("a package sandbox reaches the base's registry and nothing else");
            }
            if (poll.isNegative() || poll.isZero() || settle.isNegative()) {
                throw new IllegalArgumentException("poll must be positive and settle not negative");
            }
        }
    }

    static final String OUTPUT = "binary.tar.gz";
    private static final Logger log = LoggerFactory.getLogger(PackageBuildDriver.class);

    private final DockerClient docker;
    private final DockerWorkerLauncher launcher;
    private final BuildKitClient client;
    private final PackageHandIn handIn;
    private final Renderer renderer;
    private final Config config;

    public PackageBuildDriver(DockerClient docker, DockerWorkerLauncher launcher, BuildKitClient client,
                              PackageHandIn handIn, Renderer renderer, Config config) {
        this.docker = docker;
        this.launcher = launcher;
        this.client = client;
        this.handIn = handIn;
        this.renderer = renderer;
        this.config = config;
    }

    /**
     * The attempt id the launcher names every object by: "p", then the row id's first 24 hex
     * digits and the lease generation's 8, 33 characters. Never 32 hex digits: the attempt
     * sweeper takes those for app builds and disposes of every one no unsettled build owns,
     * which would remove a running sandbox. Sweeping a dead sandbox's leftovers against the
     * queue's leases is step 2e's.
     */
    static String attemptId(BuildClaim claim) {
        return attemptId(claim.rowId(), claim.lease().generation());
    }

    /** As above, for a row id and lease generation (the package-attempt sweep's inventory). */
    static String attemptId(java.util.UUID rowId, long generation) {
        String row = rowId.toString().replace("-", "");
        return "p" + row.substring(0, 24) + String.format("%08x", generation & 0xffffffffL);
    }

    public Result run(BuildClaim claim, BooleanSupplier stop) throws InterruptedException {
        Handle h = DockerWorkerLauncher.handle(attemptId(claim));
        AtomicBoolean aborted = new AtomicBoolean();
        AtomicReference<Result> result = new AtomicReference<>();
        AtomicReference<Throwable> crashed = new AtomicReference<>();
        PhaseClock clock = new PhaseClock();
        Thread work = new Thread(() -> {
            try {
                result.set(attempt(claim, h, aborted, clock));
            } catch (Throwable t) {
                crashed.set(t);
            } finally {
                if (aborted.get()) {
                    // As in BuildKitDriver: the last act after a stop, its interrupt cleared.
                    Thread.interrupted();
                    disposeQuietly(h);
                }
            }
        }, "skald-package-" + h.attemptId());
        work.setDaemon(true);
        work.start();
        try {
            while (work.isAlive()) {
                if (stop.getAsBoolean()) {
                    aborted.set(true);
                    stopEverything(h, work);
                    return new Stopped();
                }
                work.join(config.poll().toMillis());
            }
        } catch (InterruptedException e) {
            aborted.set(true);
            stopEverything(h, work);
            throw e;
        }
        clock.lap("finish");
        List<String> left = launcher.dispose(h);
        if (!left.isEmpty()) {
            log.warn("package attempt {}: disposal left {}", h.attemptId(), left);
        }
        clock.lap("dispose");
        log.info("package attempt {} ({} {}): {}{}", h.attemptId(), claim.pkg(), claim.version(),
                BuildKitDriver.PHASES, clock.summary());
        return crashed.get() != null
                ? new Failed("DRIVER_ERROR", BuildKitDriver.detail(crashed.get().getClass().getSimpleName()))
                : result.get();
    }

    private void stopEverything(Handle h, Thread work) throws InterruptedException {
        launcher.killWorker(h);
        launcher.removeContainers(h);
        work.interrupt();
        work.join(Math.max(1, config.settle().toMillis()));
        if (!work.isAlive()) {
            disposeQuietly(h);
        }
    }

    private void disposeQuietly(Handle h) {
        try {
            List<String> left = launcher.dispose(h);
            if (!left.isEmpty()) {
                log.warn("package attempt {}: disposal left {}", h.attemptId(), left);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** The work thread: the hand-in, then every Docker call. */
    private Result attempt(BuildClaim claim, Handle h, AtomicBoolean aborted, PhaseClock clock) throws Exception {
        Recipe recipe;
        List<BuiltDependency> closure;
        try {
            closure = PackageClosure.resolve(claim.dependencies(), handIn.lock(claim));
            HandedIn source = new HandedIn(claim.pkg(), claim.version(), handIn.source(claim), claim.sourceSha256());
            List<HandedIn> deps = new ArrayList<>();
            for (BuiltDependency d : closure) {
                deps.add(new HandedIn(d.pkg(), d.version(), handIn.dependency(claim, d), d.binarySha256()));
            }
            recipe = renderer.render(source, deps);
        } catch (PackageClosure.Conflict e) {
            return new Failed("DEPENDENCY_CONFLICT", BuildKitDriver.detail(e.getMessage()));
        } catch (PackageRecipe.DigestMismatch e) {
            // What forge serves for a recorded digest is not those bytes: the shared tree or
            // the mirror changed under the queue's record. Never compiled against.
            return new Refused("HANDIN_DIGEST", BuildKitDriver.detail(e.getMessage()));
        } catch (IllegalArgumentException e) {
            return new Refused("HANDIN_REFUSED", BuildKitDriver.detail(e.getMessage()));
        } catch (IOException e) {
            return new Failed("HANDIN_FETCH", BuildKitDriver.detail(e.getClass().getSimpleName() + ": " + e.getMessage()));
        }
        clock.lap("handin");
        if (aborted.get()) {
            return new Stopped();
        }
        Path payload = Files.createTempDirectory("skald-package-");
        try {
            try {
                launcher.launch(new Request(h.attemptId(), config.settings(), config.quotaMegabytes(), config.egress()),
                        clock);
            } catch (IllegalStateException e) {
                return new Failed("WORKER_LAUNCH", BuildKitDriver.detail(e.getMessage()));
            }
            if (aborted.get()) {
                return new Stopped();
            }
            try {
                client.stage(h, payload, recipe);
                clock.lap("stage");
                if (aborted.get()) {
                    return new Stopped();
                }
                client.startLocal(h, config.egress().registry(), config.credential());
                clock.lap("client.start");
            } catch (IOException | IllegalStateException | org.mandas.docker.client.exceptions.DockerException e) {
                return new Failed("STAGE", BuildKitDriver.detail(e.getMessage()));
            }
            while (!aborted.get()) {
                var exit = client.exitCode(h);
                if (exit.isPresent()) {
                    clock.lap("build");
                }
                if (exit.isPresent() && exit.get() == 0) {
                    byte[] binary;
                    try {
                        binary = client.collect(h, OUTPUT, config.archiveLimits().maxBytes());
                    } catch (IllegalStateException e) {
                        return new Refused("OUTPUT", BuildKitDriver.detail(e.getMessage()));
                    }
                    try {
                        return new Built(binary, BinaryArchive.check(binary, claim.pkg(), claim.version(),
                                config.rVersion(), config.rPlatform(), config.archiveLimits()), closure);
                    } catch (BinaryArchive.Refused r) {
                        return new Refused(r.code(), BuildKitDriver.detail(r.getMessage()));
                    }
                }
                if (!workerRunning(h)) {
                    return new Failed("WORKER_DIED", "the worker stopped");
                }
                if (exit.isPresent()) {
                    return new Failed("BUILD_FAILED", BuildKitDriver.detail("exit " + exit.get() + ": "
                            + lastLine(client.log(h))));
                }
                Thread.sleep(config.poll().toMillis());
            }
            return new Stopped();
        } finally {
            Files.deleteIfExists(payload);
        }
    }

    private boolean workerRunning(Handle h) throws Exception {
        try {
            return docker.inspectContainer(h.worker()).state().running();
        } catch (NotFoundException e) {
            return false;
        }
    }

    static String lastLine(String log) {
        String[] lines = log.strip().split("\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            if (!lines[i].isBlank()) {
                return lines[i].strip();
            }
        }
        return "";
    }
}
