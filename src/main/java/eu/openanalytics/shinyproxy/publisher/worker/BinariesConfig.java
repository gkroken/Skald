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
import eu.openanalytics.shinyproxy.publisher.binaries.BinaryBuildQueue;
import eu.openanalytics.shinyproxy.publisher.binaries.BinaryEnqueuer;
import eu.openanalytics.shinyproxy.publisher.binaries.BinaryGate;
import eu.openanalytics.shinyproxy.publisher.binaries.BinaryHook;
import eu.openanalytics.shinyproxy.publisher.binaries.BinaryQueueSettings;
import eu.openanalytics.shinyproxy.publisher.binaries.BinaryUploader;
import eu.openanalytics.shinyproxy.publisher.binaries.ForgeHandIn;
import eu.openanalytics.shinyproxy.publisher.binaries.ForgePlatforms;
import eu.openanalytics.shinyproxy.publisher.binaries.ForgeSettings;
import eu.openanalytics.shinyproxy.publisher.binaries.HttpSourceFetcher;
import eu.openanalytics.shinyproxy.publisher.binaries.PackageRecipe;
import eu.openanalytics.shinyproxy.publisher.binaries.SourceSettings;
import eu.openanalytics.shinyproxy.publisher.binaries.SourceVerifier;
import eu.openanalytics.shinyproxy.publisher.build.CoordinatorSettings;
import eu.openanalytics.shinyproxy.publisher.recipe.BaseCatalog;
import eu.openanalytics.shinyproxy.publisher.worker.DockerWorkerLauncher.Egress;
import eu.openanalytics.shinyproxy.publisher.worker.DockerWorkerLauncher.Images;
import eu.openanalytics.shinyproxy.publisher.worker.WorkerProfile.Settings;
import org.mandas.docker.client.DockerClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The shared binaries, wired (T7b step 2e, part 1b): only with {@code skald.builds.binaries.enabled=true}
 * beside an enabled worker -- OFF by default, and to be enabled per deployment only after
 * T7b's security review (WORKPLAN-BUNDLES T7b step 2, point 7).
 *
 * <p>It refuses to start rather than run half-configured, reporting every problem at once: a
 * database, forge's URL and token, the source repository (forge's CRAN proxy member), the
 * worker's cran-linux-binaries switch (without it no build would ever ask for binaries), and
 * published bases that load. Every long-lived client (forge's registrations and uploads, the
 * hand-in) is created once here (a5b8816 N2).
 */
@Configuration
@ConditionalOnProperty(name = {"skald.builds.worker.enabled", "skald.builds.binaries.enabled"}, havingValue = "true")
public class BinariesConfig {

    private BaseCatalog catalog;

    /** What is wrong with the configuration, all at once. */
    static List<String> problems(WorkerConfig worker, ForgeSettings forge, SourceSettings source, boolean database) {
        List<String> problems = new ArrayList<>();
        if (!database) {
            problems.add("a database (spring.datasource.url): the binary queue is leased there");
        }
        if (forge.url() == null) {
            problems.add("skald.builds.binaries.forge-url (and forge-token) is not set");
        }
        if (source.repository() == null) {
            problems.add("skald.builds.binaries.source-repository (forge's CRAN proxy member) is not set");
        }
        if (!worker.cranLinuxBinaries()) {
            problems.add("skald.builds.worker.cran-linux-binaries is off: no build would ask for binaries");
        }
        return problems;
    }

    private BaseCatalog catalog(WorkerConfig worker) throws Exception {
        if (catalog == null) {
            try (InputStream in = Files.newInputStream(Path.of(worker.publishedBases()))) {
                catalog = BaseCatalog.load(in);
            }
        }
        return catalog;
    }

    @Bean
    public BinaryBuildQueue binaryBuildQueue(WorkerConfig worker, ObjectProvider<JdbcTemplate> jdbc,
                                             BinaryQueueSettings queueSettings, CoordinatorSettings apps,
                                             ForgeSettings forge, SourceSettings source) {
        List<String> problems = problems(worker, forge, source, jdbc.getIfAvailable() != null);
        if (!problems.isEmpty()) {
            throw new IllegalStateException("shared binaries are enabled but cannot run: " + String.join("; ", problems));
        }
        return new BinaryBuildQueue(jdbc.getObject(), queueSettings, apps);
    }

    @Bean
    public ForgePlatforms binaryForgePlatforms(ForgeSettings forge) {
        return new ForgePlatforms(forge);
    }

    @Bean
    public BinaryGate binaryGate(ForgePlatforms platforms) {
        return new BinaryGate(platforms);
    }

    @Bean
    public BinaryHook binaryHook(BinaryBuildQueue queue, BinaryGate gate, WorkerConfig worker) throws Exception {
        return new BinaryEnqueuer(queue, gate, catalog(worker));
    }

    @Bean(destroyMethod = "close")
    public SourceVerifier binarySourceVerifier(BinaryBuildQueue queue, SourceSettings source,
                                               BinaryQueueSettings queueSettings, WorkerConfig worker) throws Exception {
        Map<String, Map<String, String>> libraries = new HashMap<>();
        for (BaseCatalog.Entry e : catalog(worker).entries()) {
            e.platformKey().ifPresent(k -> libraries.put(k, e.rRecommended()));
        }
        return new SourceVerifier(queue, new HttpSourceFetcher(source.fetchTimeout()), source, queueSettings,
                key -> libraries.getOrDefault(key, Map.of()));
    }

    @Bean(destroyMethod = "close")
    public ForgeHandIn binaryHandIn(BinaryBuildQueue queue, SourceSettings source, ForgeSettings forge) {
        return new ForgeHandIn(queue, new HttpSourceFetcher(source.fetchTimeout()), source, forge,
                BinaryArchive.Limits.DEFAULTS);
    }

    @Bean
    public BinaryUploader binaryUploader(BinaryBuildQueue queue, BinaryGate gate, ForgeSettings forge) {
        return new BinaryUploader(queue, gate, forge, BinaryArchive.Limits.DEFAULTS);
    }

    /**
     * The package attempts' disposals, off their compile slots (T7c step 3); closed after the
     * loop, waiting for pending disposals as the app builds' Disposer does.
     */
    @Bean(destroyMethod = "close")
    public Disposer binaryDisposer(BinaryQueueSettings queueSettings) {
        return new Disposer(queueSettings.maxRunning());
    }

    /** The package sandboxes' pools, one per R base's driver; stopped after the loop. */
    @Bean(destroyMethod = "stop")
    public WorkerPool.Group binaryWorkerPools(DockerClient skaldWorkerDocker) {
        return new WorkerPool.Group();
    }

    @Bean(initMethod = "start", destroyMethod = "stop")
    public PackageBuildLoop packageBuildLoop(WorkerConfig worker, DockerClient skaldWorkerDocker,
                                             BinaryBuildQueue queue, SourceVerifier verifier, BinaryUploader uploader,
                                             BinaryGate gate, ForgeHandIn handIn, BinaryQueueSettings queueSettings,
                                             WorkerPool.Group binaryWorkerPools, HostCapacity skaldHostCapacity,
                                             Disposer binaryDisposer)
            throws Exception {
        Images images = Images.of(worker.workerImage(), worker.gatewayImage());
        DockerWorkerLauncher launcher = new DockerWorkerLauncher(skaldWorkerDocker,
                WorkerProfile.load("runc-rootless"), images);
        BuildKitClient client = new BuildKitClient(skaldWorkerDocker, launcher, images);
        Map<String, List<PackageBuildLoop.Platform>> platforms = new HashMap<>();
        BaseCatalog cat = catalog(worker);
        for (BaseCatalog.Entry e : cat.entries()) {
            if (e.platformKey().isEmpty()) {
                continue;
            }
            var resolved = cat.resolveEntry(e.type(), e.language(), e.version(), e.architecture());
            if (resolved.isEmpty() || !resolved.get().entry().id().equals(e.id())) {
                continue;
            }
            var base = resolved.get().base();
            // The base's registry and nothing else (PackageBuildDriver.Config refuses more).
            Egress egress = new Egress(worker.outerNetworks(), worker.registry(), List.of(), List.of(), List.of(),
                    List.of());
            // One pool per driver, launched with this driver's own request (4838310 N2): its
            // narrower egress and its output volume.
            WorkerPool pool = worker.poolSize() == 0 ? null : new WorkerPool(launcher, client,
                    id -> new DockerWorkerLauncher.Request(id, Settings.defaults(), worker.quotaMegabytes(), egress, true),
                    List.of(base.reference()), worker.registry(), worker.credential(), worker.poolSettings())
                    .withCapacity(skaldHostCapacity);
            PackageBuildDriver driver = new PackageBuildDriver(skaldWorkerDocker, launcher, client, handIn,
                    (s, d) -> PackageRecipe.render(base, s, d),
                    new PackageBuildDriver.Config(Settings.defaults(), worker.quotaMegabytes(), egress,
                            worker.credential(), worker.poll(), worker.settle(), e.version(), e.rPlatform(),
                            BinaryArchive.Limits.DEFAULTS),
                    pool, pool == null ? null : base.reference()).withCapacity(skaldHostCapacity)
                    .withDisposer(binaryDisposer);
            if (pool != null) {
                binaryWorkerPools.start(pool);
            }
            platforms.computeIfAbsent(e.platformKey().get(), k -> new ArrayList<>())
                    .add(new PackageBuildLoop.Platform(e, base.reference(), cat.imageId(e.id()), driver::run));
        }
        String owner = worker.owner() != null && !worker.owner().isBlank() ? worker.owner()
                : java.net.InetAddress.getLocalHost().getHostName();
        return new PackageBuildLoop(queue, verifier, uploader, gate, platforms, owner + "-binaries", queueSettings,
                worker.idlePause());
    }

    @Bean(initMethod = "start", destroyMethod = "stop")
    public BinaryHousekeeping binaryHousekeeping(WorkerConfig worker, DockerClient skaldWorkerDocker,
                                                 BinaryBuildQueue queue, BinaryQueueSettings queueSettings) {
        DockerWorkerLauncher launcher = new DockerWorkerLauncher(skaldWorkerDocker,
                WorkerProfile.load("runc-rootless"), Images.of(worker.workerImage(), worker.gatewayImage()));
        return new BinaryHousekeeping(queue::reap, () -> {
            try {
                return launcher.attemptsPresent();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }, () -> {
            Set<String> live = new HashSet<>();
            queue.runningLeases().forEach((row, gen) -> live.add(PackageBuildDriver.attemptId(row, gen)));
            return live;
        }, id -> {
            try {
                launcher.dispose(DockerWorkerLauncher.handle(id));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, id -> {
            // T7c step 3: left to its own driver's disposal while the marker is young.
            try {
                return PackageBuildDriver.stillDisposing(launcher.disposingSince(id),
                        java.time.Instant.now().getEpochSecond());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return true;
            }
        }, queueSettings.renewEvery(), worker.sweepPeriod());
    }
}
