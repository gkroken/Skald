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

import eu.openanalytics.shinyproxy.publisher.build.BuildCoordinator;
import eu.openanalytics.shinyproxy.publisher.build.BuildLoop;
import eu.openanalytics.shinyproxy.publisher.build.BuildRunner;
import eu.openanalytics.shinyproxy.publisher.build.CoordinatorSettings;
import eu.openanalytics.shinyproxy.publisher.bundle.ExtractionLimits;
import eu.openanalytics.shinyproxy.publisher.recipe.BaseCatalog;
import eu.openanalytics.shinyproxy.publisher.recipe.RecipeGenerator.Mirrors;
import eu.openanalytics.shinyproxy.publisher.storage.BuildLogWriter;
import eu.openanalytics.shinyproxy.publisher.storage.BundleWriter;
import eu.openanalytics.shinyproxy.publisher.storage.HeadTailLog;
import eu.openanalytics.shinyproxy.publisher.storage.ObjectStore;
import eu.openanalytics.shinyproxy.publisher.worker.BuildKitClient.Credential;
import eu.openanalytics.shinyproxy.publisher.worker.DockerWorkerLauncher.Egress;
import eu.openanalytics.shinyproxy.publisher.worker.DockerWorkerLauncher.Request;
import eu.openanalytics.shinyproxy.publisher.worker.DockerWorkerLauncher.Images;
import eu.openanalytics.shinyproxy.publisher.worker.WorkerProfile.Settings;
import org.mandas.docker.client.DockerClient;
import org.mandas.docker.client.builder.jersey.JerseyDockerClientBuilder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DurationUnit;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The build worker, wired: when {@code skald.builds.worker.enabled=true}, a loop claims
 * queued attempts and builds each on its own rootless worker (T7, part 4c).
 *
 * <p><b>It refuses to start rather than fail every build.</b> Every condition a build would
 * otherwise meet only at run time is checked here, and all of them are reported at once:
 * a database and object storage are configured; the registry, its credential, the images,
 * the workspace and the mirrors are named; the published bases file loads against the
 * shipped catalog, and its registry is the one the gateway allows, {@code <registry>:5000}
 * (1ffdb20 review N1: the gateway's registry port, buildkitd.toml's plain-http entry and the
 * client's credential are all keyed on that); each mirror's host is one the gateway lets the
 * worker reach; the poll is at most renew_every / 2 (BuildDriver's bound); the sweep's
 * period is positive and its grace longer than the driver's settle; the four images the
 * launcher runs are present.
 */
@Configuration
@ConditionalOnProperty(name = "skald.builds.worker.enabled", havingValue = "true")
@ConfigurationProperties(prefix = "skald.builds.worker")
public class WorkerConfig {

    private String owner;
    private String workerImage;
    private String gatewayImage;
    private String registry;
    /**
     * The registry as ShinyProxy reaches it, http(s)://host[:port], for publication's digest
     * check. The build credential travels in a Basic header: prefer https, or an address
     * that stays on this host (5cd0826 review N2).
     */
    private String registryApi;
    private String registryUsername;
    private String registryPassword;
    private List<String> outerNetworks = new ArrayList<>();
    private List<String> repos = new ArrayList<>();
    private List<String> privateMirrors = new ArrayList<>();
    private String cranMirror;
    private String pypiMirror;
    /**
     * The CRAN mirror has forge's Linux binary layout (T7b): R restores from
     * {@code <cran-mirror>__linux__/<codename>/latest}. Off by default: a mirror without that
     * layout answers 404, and renv does not fall back to source.
     */
    private boolean cranLinuxBinaries = false;
    private String publishedBases;
    private String workspace;
    private String architecture = "amd64";
    /**
     * The workspace quota ({@code quota}, a size with its unit, such as 8GB). 8 GiB: a cold R build holds the extracted base (about 1.5
     * GiB for rocker/r-ver), BuildKit's snapshots of each step and the compiled library;
     * the 4 GiB the probes used is enough for the fixture apps and too tight for a real
     * dependency tree. T5 F6 carry (3) left the production number to this track.
     */
    private int quotaMegabytes = 8192;
    // The durations: a bare number is seconds, as everywhere under skald.* (T7c 0a), not
    // Spring's milliseconds.
    @DurationUnit(ChronoUnit.SECONDS)
    private Duration poll = Duration.ofSeconds(1);
    @DurationUnit(ChronoUnit.SECONDS)
    private Duration settle = Duration.ofSeconds(30);
    @DurationUnit(ChronoUnit.SECONDS)
    private Duration idlePause = Duration.ofSeconds(2);
    /** How often the reconciliation sweep runs after its first run at startup. */
    @DurationUnit(ChronoUnit.SECONDS)
    private Duration sweepPeriod = Duration.ofMinutes(10);
    /**
     * How long after a build finishes the sweep still leaves its attempt alone: its work
     * thread may outlive the driver's settle in a daemon call (2fc53d9-F1).
     */
    @DurationUnit(ChronoUnit.SECONDS)
    private Duration sweepGrace = Duration.ofMinutes(15);
    /**
     * Ready workers per published base (T7b, the fixed cost): launched and warmed with the base
     * ahead of any build, each used by one build and then disposed of. 0: every build launches
     * its own. Each ready one holds, while idle, a worker, a gateway, a loop device and a
     * workspace image with the base extracted (sparse; about the base's size on disk), so the
     * idle cost is pool-size times the number of published bases (4838310 N4); the binaries'
     * package sandboxes keep a pool of the same size per R base. The bases are those of the
     * catalog read at startup, as the builds' own (bc66e6f N2): a base published later needs a
     * restart for builds and pool alike.
     */
    private int poolSize = 1;
    /** How long a ready worker is kept before it is replaced by a fresh one. */
    @DurationUnit(ChronoUnit.SECONDS)
    private Duration poolTtl = Duration.ofMinutes(30);
    private String bundleBucket = "skald-bundles";
    private String logBucket = "skald-logs";

    /**
     * The build loop's pools; stopped after the loop (which depends on it) and before the
     * Docker client closes (this depends on it: the pool thread's last act is its disposals,
     * which failed on a closed client and left every ready entry, in the first pass probe run).
     */
    @Bean(destroyMethod = "stop")
    public WorkerPool.Group skaldWorkerPools(DockerClient skaldWorkerDocker) {
        return new WorkerPool.Group();
    }

    /** Settled attempts' disposals, after the loops that hand them over and before the Docker client closes. */
    @Bean(destroyMethod = "close")
    public Disposer skaldDisposer(DockerClient skaldWorkerDocker) {
        return new Disposer(4);
    }

    @Bean(destroyMethod = "close")
    public DockerClient skaldWorkerDocker() throws Exception {
        return new JerseyDockerClientBuilder().fromEnv().build();
    }

    /**
     * The workspace, locked for this process and cleared of what a dead one left
     * (WorkspaceLock), before the loop can extract into it. Closed after the loop stops. A
     * workspace that is not a writable directory is reported by the loop's own checks.
     */
    @Bean(destroyMethod = "close")
    public WorkspaceLock skaldWorkspaceLock() throws IOException {
        if (workspace == null || workspace.isBlank() || !Files.isDirectory(Path.of(workspace))
                || !Files.isWritable(Path.of(workspace))) {
            return null;
        }
        return WorkspaceLock.claim(Path.of(workspace));
    }

    // DependsOn: the lock is taken before the loop exists (a workspace another process holds
    // refuses the start) and released only after the loop has stopped and its last tree is
    // gone, so no other process can claim the workspace while this one still extracts.
    @Bean(initMethod = "start", destroyMethod = "stop")
    @org.springframework.context.annotation.DependsOn("skaldWorkspaceLock")
    public BuildLoop skaldBuildLoop(DockerClient skaldWorkerDocker, ObjectProvider<BuildCoordinator> coordinator,
                                    CoordinatorSettings settings, ObjectProvider<ObjectStore> store,
                                    ExtractionLimits limits,
                                    ObjectProvider<eu.openanalytics.shinyproxy.publisher.binaries.BinaryHook> binaryHook,
                                    WorkerPool.Group skaldWorkerPools, Disposer skaldDisposer)
            throws Exception {
        List<String> problems = problems(settings);
        if (coordinator.getIfAvailable() == null) {
            problems.add("a database (spring.datasource.url): builds are leased there");
        }
        if (store.getIfAvailable() == null) {
            problems.add("object storage (skald.storage.endpoint): bundles and logs live there");
        }
        BaseCatalog catalog = null;
        if (publishedBases != null && !publishedBases.isBlank()) {
            try (InputStream in = Files.newInputStream(Path.of(publishedBases))) {
                catalog = BaseCatalog.load(in);
                if (registry != null && !(registry + ":5000").equals(catalog.registry())) {
                    problems.add("the published bases name registry " + catalog.registry() + ", but the worker"
                            + " reaches " + registry + ":5000; publish them for that registry");
                }
            } catch (Exception e) {
                problems.add("published-bases " + publishedBases + ": " + e.getMessage());
            }
        }
        if (workerImage != null && gatewayImage != null) {
            for (String image : List.of(workerImage, gatewayImage, Images.HELPER, Images.SETUP)) {
                try {
                    skaldWorkerDocker.inspectImage(image);
                } catch (Exception e) {
                    problems.add("image " + image + " is not present: " + e.getMessage());
                }
            }
        }
        refuseIf(problems);

        Images images = Images.of(workerImage, gatewayImage);
        DockerWorkerLauncher launcher = new DockerWorkerLauncher(skaldWorkerDocker, WorkerProfile.load("runc-rootless"),
                images);
        BuildKitClient client = new BuildKitClient(skaldWorkerDocker, launcher, images);
        ObjectStore objects = store.getObject();
        Mirrors mirrors = new Mirrors(URI.create(cranMirror), URI.create(pypiMirror), cranLinuxBinaries);
        BundleContextSource source = new BundleContextSource(new BundleWriter(objects, bundleBucket), objects,
                bundleBucket, limits, Path.of(workspace), catalog, mirrors, architecture)
                // T7b: present only with skald.builds.binaries.enabled (BinariesConfig).
                .withBinaryHook(binaryHook.getIfAvailable());
        BuildLogWriter logWriter = new BuildLogWriter(objects, logBucket);
        Egress egress = new Egress(outerNetworks, registry, repos, privateMirrors, List.of(), List.of());
        WorkerPool pool = poolSize == 0 ? null : new WorkerPool(launcher, client,
                id -> new Request(id, Settings.defaults(), quotaMegabytes, egress), publishedReferences(catalog),
                registry, new Credential(registryUsername, registryPassword), poolSettings());
        BuildKitDriver driver = new BuildKitDriver(skaldWorkerDocker, launcher, client, source,
                logWriter,
                new BuildKitDriver.Config(Settings.defaults(), quotaMegabytes, egress,
                        new Credential(registryUsername, registryPassword), poll, settle, HeadTailLog.Limits.defaults()),
                pool, skaldDisposer)
                // 3d18996-F1: the base's blobs mounted, not uploaded again by every first push.
                .withBlobMounter(new BlobMounter(URI.create(registryApi), registry, architecture,
                        new Credential(registryUsername, registryPassword)));
        if (pool != null) {
            // Only now: every startup check passed, so no worker is launched for a refused start.
            skaldWorkerPools.start(pool);
        }
        PublishVerifier verifier = new PublishVerifier(URI.create(registryApi), registry,
                new Credential(registryUsername, registryPassword), logWriter);
        return new BuildLoop(new BuildRunner(coordinator.getObject(), settings, verifier), driver,
                owner != null && !owner.isBlank() ? owner : java.net.InetAddress.getLocalHost().getHostName(),
                idlePause);
    }

    /**
     * The reconciliation sweep (AttemptSweeper), created after the loop, so only once every
     * startup check has passed. It disposes of what finished or vanished attempts left on
     * this host and finishes the logs of attempts the platform lost.
     */
    @Bean(initMethod = "start", destroyMethod = "stop")
    public AttemptSweeper skaldAttemptSweeper(BuildLoop skaldBuildLoop, DockerClient skaldWorkerDocker,
                                              ObjectProvider<BuildCoordinator> coordinator,
                                              ObjectProvider<ObjectStore> store) {
        BuildCoordinator c = coordinator.getObject();
        return new AttemptSweeper(new DockerWorkerLauncher(skaldWorkerDocker, WorkerProfile.load("runc-rootless"),
                Images.of(workerImage, gatewayImage)), () -> c.unsettledBuildIds(sweepGrace), () -> c.recentlyLost(Duration.ofDays(1), sweepGrace),
                new BuildLogWriter(store.getObject(), logBucket), sweepPeriod);
    }

    /**
     * The published bases a build of this architecture can resolve to, by digest: each catalog
     * entry's newest recipe revision, as BundleContextSource resolves it.
     */
    List<String> publishedReferences(BaseCatalog catalog) {
        java.util.LinkedHashSet<String> refs = new java.util.LinkedHashSet<>();
        if (catalog != null) {
            for (BaseCatalog.Entry e : catalog.entries()) {
                if (!e.architecture().equals(architecture)) {
                    continue;
                }
                try {
                    catalog.resolveEntry(e.type(), e.language(), e.version(), e.architecture())
                            .ifPresent(r -> refs.add(r.base().reference()));
                } catch (IllegalStateException unpublished) {
                    // a build of it is refused at prepare time; nothing to warm
                }
            }
        }
        return List.copyOf(refs);
    }

    WorkerPool.Settings poolSettings() {
        return new WorkerPool.Settings(poolSize, poolTtl, Duration.ofMinutes(10), Duration.ofSeconds(5));
    }

    /** Every refusal that needs no Docker, catalog or bean: the configuration itself. */
    List<String> problems(CoordinatorSettings settings) {
        List<String> problems = new ArrayList<>();
        for (var named : List.of(new String[] {"worker-image", workerImage}, new String[] {"gateway-image", gatewayImage},
                new String[] {"registry", registry}, new String[] {"registry-api", registryApi},
                new String[] {"registry-username", registryUsername},
                new String[] {"registry-password", registryPassword}, new String[] {"cran-mirror", cranMirror},
                new String[] {"pypi-mirror", pypiMirror}, new String[] {"published-bases", publishedBases},
                new String[] {"workspace", workspace})) {
            if (named[1] == null || named[1].isBlank()) {
                problems.add("skald.builds.worker." + named[0] + " is not set");
            }
        }
        try {
            poolSettings();
        } catch (IllegalArgumentException e) {
            problems.add("pool-size / pool-ttl: " + e.getMessage());
        }
        if (sweepPeriod.isNegative() || sweepPeriod.isZero()) {
            problems.add("sweep-period " + sweepPeriod + " must be positive");
        }
        if (sweepGrace.compareTo(settle) <= 0) {
            problems.add("sweep-grace " + sweepGrace + " must be longer than settle (" + settle
                    + "): a stopped attempt's work thread may still be in a daemon call that long");
        } else if (sweepGrace.compareTo(Disposer.CLOSE_WAIT) <= 0) {
            problems.add("sweep-grace " + sweepGrace + " must be longer than " + Disposer.CLOSE_WAIT
                    + ": a settled build's disposal runs after its outcome and may take that long (bc66e6f N1)");
        }
        if (poll.isNegative() || poll.isZero() || poll.compareTo(settings.renewEvery().dividedBy(2)) > 0) {
            problems.add("poll " + poll + " must be positive and at most renew_every / 2 ("
                    + settings.renewEvery().dividedBy(2) + "): the driver's stop bound");
        }
        if (workspace != null && !workspace.isBlank()) {
            Path w = Path.of(workspace);
            if (!Files.isDirectory(w) || !Files.isWritable(w)) {
                problems.add("workspace " + workspace + " is not a writable directory");
            }
        }
        Set<String> allowed = new HashSet<>(repos);
        allowed.addAll(privateMirrors);
        for (String mirror : new String[] {cranMirror, pypiMirror}) {
            if (mirror == null || mirror.isBlank()) {
                continue;
            }
            try {
                URI uri = URI.create(mirror);
                String host = uri.getHost();
                if (host == null || !allowed.contains(host)) {
                    problems.add("mirror " + mirror + ": its host is not in repos or private-mirrors, so the"
                            + " gateway would refuse every restore");
                }
                // What the gateway the launcher ships lets through (4b8c6f8-F1): plain http to a
                // repository or private mirror on GatewayConfig.REPO_PORTS ("acl repo_ports"),
                // and https only by CONNECT to 443 ("deny CONNECT !SSL_ports"). A mirror on any
                // other port would be refused at every restore.
                boolean https = "https".equals(uri.getScheme());
                int port = uri.getPort() >= 0 ? uri.getPort() : https ? 443 : 80;
                List<Integer> served = https ? List.of(443) : GatewayConfig.REPO_PORTS;
                if (!served.contains(port)) {
                    problems.add("mirror " + mirror + ": port " + port + ", but the gateway serves "
                            + uri.getScheme() + " repositories on " + served + " only");
                }
                new Mirrors(URI.create(mirror), URI.create(mirror));
            } catch (IllegalArgumentException e) {
                problems.add("mirror " + mirror + ": " + e.getMessage());
            }
        }
        if (registryApi != null && !registryApi.isBlank()
                && !registryApi.matches("https?://[A-Za-z0-9.-]+(?::[0-9]{1,5})?")) {
            problems.add("registry-api " + registryApi + " is not http(s)://host[:port]");
        }
        if (quotaMegabytes < 1024) {
            problems.add("quota " + quotaMegabytes + "MB is below 1GB: no base fits");
        }
        return problems;
    }

    static void refuseIf(List<String> problems) {
        if (!problems.isEmpty()) {
            throw new IllegalStateException("skald.builds.worker is enabled but cannot run builds:\n  - "
                    + String.join("\n  - ", problems));
        }
    }

    public void setOwner(String owner) {
        this.owner = owner;
    }

    public void setWorkerImage(String workerImage) {
        this.workerImage = workerImage;
    }

    public void setGatewayImage(String gatewayImage) {
        this.gatewayImage = gatewayImage;
    }

    public void setRegistry(String registry) {
        this.registry = registry;
    }

    public void setRegistryApi(String registryApi) {
        this.registryApi = registryApi;
    }

    public void setRegistryUsername(String registryUsername) {
        this.registryUsername = registryUsername;
    }

    public void setRegistryPassword(String registryPassword) {
        this.registryPassword = registryPassword;
    }

    public void setOuterNetworks(List<String> outerNetworks) {
        this.outerNetworks = outerNetworks == null ? new ArrayList<>() : outerNetworks;
    }

    public void setRepos(List<String> repos) {
        this.repos = repos == null ? new ArrayList<>() : repos;
    }

    public void setPrivateMirrors(List<String> privateMirrors) {
        this.privateMirrors = privateMirrors == null ? new ArrayList<>() : privateMirrors;
    }

    public void setCranLinuxBinaries(boolean cranLinuxBinaries) {
        this.cranLinuxBinaries = cranLinuxBinaries;
    }

    public void setCranMirror(String cranMirror) {
        this.cranMirror = cranMirror;
    }

    public void setPypiMirror(String pypiMirror) {
        this.pypiMirror = pypiMirror;
    }

    public void setPublishedBases(String publishedBases) {
        this.publishedBases = publishedBases;
    }

    public void setWorkspace(String workspace) {
        this.workspace = workspace;
    }

    public void setArchitecture(String architecture) {
        this.architecture = architecture;
    }

    /** {@code skald.builds.worker.quota}: a size with its unit, kept in whole megabytes. */
    public void setQuota(String quota) {
        this.quotaMegabytes = Math.toIntExact(eu.openanalytics.shinyproxy.publisher.config.Settings
                .size("skald.builds.worker.quota", quota).toMegabytes());
    }

    public void setPoll(Duration poll) {
        this.poll = poll;
    }

    public void setSettle(Duration settle) {
        this.settle = settle;
    }

    public void setIdlePause(Duration idlePause) {
        this.idlePause = idlePause;
    }

    public void setSweepPeriod(Duration sweepPeriod) {
        this.sweepPeriod = sweepPeriod;
    }

    public void setSweepGrace(Duration sweepGrace) {
        this.sweepGrace = sweepGrace;
    }

    public void setPoolSize(int poolSize) {
        this.poolSize = poolSize;
    }

    public void setPoolTtl(Duration poolTtl) {
        this.poolTtl = poolTtl;
    }

    public void setBundleBucket(String bundleBucket) {
        this.bundleBucket = bundleBucket;
    }

    public void setLogBucket(String logBucket) {
        this.logBucket = logBucket;
    }

    // The settings BinariesConfig (T7b) shares with the app builds' worker.
    String registry() {
        return registry;
    }

    Credential credential() {
        return new Credential(registryUsername, registryPassword);
    }

    List<String> outerNetworks() {
        return outerNetworks;
    }

    String workerImage() {
        return workerImage;
    }

    String gatewayImage() {
        return gatewayImage;
    }

    String publishedBases() {
        return publishedBases;
    }

    boolean cranLinuxBinaries() {
        return cranLinuxBinaries;
    }

    int quotaMegabytes() {
        return quotaMegabytes;
    }

    Duration poll() {
        return poll;
    }

    Duration settle() {
        return settle;
    }

    Duration idlePause() {
        return idlePause;
    }

    Duration sweepPeriod() {
        return sweepPeriod;
    }

    String owner() {
        return owner;
    }

    int poolSize() {
        return poolSize;
    }
}
