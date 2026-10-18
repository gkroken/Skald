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
import eu.openanalytics.shinyproxy.publisher.binaries.BinaryLease;
import eu.openanalytics.shinyproxy.publisher.binaries.PackageRecipe;
import eu.openanalytics.shinyproxy.publisher.recipe.RecipeGenerator.Base;
import eu.openanalytics.shinyproxy.publisher.recipe.RecipeGenerator.Recipe;
import eu.openanalytics.shinyproxy.publisher.worker.DockerWorkerLauncher.Egress;
import eu.openanalytics.shinyproxy.publisher.worker.DockerWorkerLauncher.Images;
import eu.openanalytics.shinyproxy.publisher.worker.WorkerProfile.Settings;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mandas.docker.client.DockerClient;
import org.mandas.docker.client.builder.jersey.JerseyDockerClientBuilder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A package's sandbox through the shipped launcher and client (T7b step 2c, part 2): a real
 * worker behind its gateway, the context staged, a local-output client, the one exported file
 * collected through the Docker API and checked. The base is the test registry's busybox and the
 * recipe a stand-in that copies a prebuilt "installed package" tarball: everything but R
 * itself runs for real (R's own compile is the T7b pass probe's). `make test` builds the worker
 * and gateway images first.
 */
public class PackageBuildDriverTest {

    private static final String WORKER = "skald-buildkit-worker:test";
    private static final String GATEWAY = "skald-egress-gateway:test";
    private static final String R_VERSION = "4.6.1";
    private static final String R_PLATFORM = "x86_64-pc-linux-gnu";
    // Fails unless loopback is the RUN's only interface. /proc/net/dev is the RUN's own network
    // namespace; /sys/class/net is not remounted per namespace in a rootless worker (measured:
    // it listed interfaces under --network=none).
    private static final String NET_CHECK =
        "[ \\\"$(awk 'NR > 2 {print $1}' /proc/net/dev | tr -d ' ')\\\" = lo: ] || exit 7;";
    private static TestRegistry registry;
    private static DockerClient docker;
    private static DockerWorkerLauncher launcher;
    private static BuildKitClient client;

    @BeforeAll
    public static void start() throws Exception {
        docker = new JerseyDockerClientBuilder().fromEnv().build();
        for (String image : List.of(WORKER, GATEWAY, Images.HELPER, Images.SETUP, TestRegistry.IMAGE)) {
            assertNotNull(docker.inspectImage(image), image + " is missing; run `make test`");
        }
        Images images = Images.of(WORKER, GATEWAY);
        launcher = new DockerWorkerLauncher(docker, WorkerProfile.load("runc-rootless"), images);
        client = new BuildKitClient(docker, launcher, images);
        registry = new TestRegistry(docker, "skald-pbt", 15041);
    }

    @AfterAll
    public static void stop() throws Exception {
        if (docker != null) {
            try {
                if (registry != null) {
                    registry.close();
                }
            } finally {
                docker.close();
            }
        }
    }

    // ------------------------------------------------------------------ fixtures

    static String sha256(byte[] b) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
    }

    /** An installed package as R CMD INSTALL leaves it, packed. */
    static byte[] installed(String pkg, String version) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(new GZIPOutputStream(b))) {
            for (String dir : List.of(pkg + "/", pkg + "/Meta/")) {
                tar.putArchiveEntry(new TarArchiveEntry(dir));
                tar.closeArchiveEntry();
            }
            Map<String, String> files = new LinkedHashMap<>();
            files.put(pkg + "/DESCRIPTION", "Package: " + pkg + "\nVersion: " + version + "\nBuilt: R " + R_VERSION
                + "; " + R_PLATFORM + "; 2026-10-11 10:00:00 UTC; unix\n");
            files.put(pkg + "/Meta/package.rds", "rds");
            for (var f : files.entrySet()) {
                byte[] bytes = f.getValue().getBytes(StandardCharsets.UTF_8);
                TarArchiveEntry e = new TarArchiveEntry(f.getKey());
                e.setSize(bytes.length);
                tar.putArchiveEntry(e);
                tar.write(bytes);
                tar.closeArchiveEntry();
            }
        }
        return b.toByteArray();
    }

    private static BuildClaim claim(String pkg, List<BuiltDependency> deps, byte[] source) throws Exception {
        UUID row = UUID.randomUUID();
        return new BuildClaim(row, "noble-x86_64/4.6", pkg, "1.0", sha256(source), "0".repeat(32), deps,
            new BinaryLease(row, 3, "test"));
    }

    private static PackageBuildDriver.HandIn handIn(byte[] source, Map<String, byte[]> deps, Map<String, String> lock) {
        return new PackageBuildDriver.HandIn() {
            @Override
            public byte[] source(BuildClaim claim) {
                return source;
            }

            @Override
            public byte[] dependency(BuildClaim claim, BuiltDependency d) {
                return deps.get(d.pkg() + " " + d.version());
            }

            @Override
            public Map<String, String> lock(BuildClaim claim) {
                return lock;
            }
        };
    }

    /**
     * A stand-in recipe on busybox: the context holds {@code prebuilt}; the one RUN (as the real
     * recipe's: --network=none, unless {@code network}) runs {@code script} and copies it to
     * the output; the export is a scratch stage with {@code exported}.
     */
    private static PackageBuildDriver.Renderer standIn(byte[] prebuilt, String script, boolean network,
                                                       String... exported) {
        return (source, deps) -> {
            StringBuilder df = new StringBuilder("FROM " + registry.base() + " AS build\n"
                + "COPY skald/ /w/\n"
                + "RUN " + (network ? "" : "--network=none ") + "[\"sh\", \"-euc\", \"mkdir -p /w/out; " + script
                + " cp /w/prebuilt.tar.gz /w/out/binary.tar.gz; echo extra > /w/out/extra\"]\n"
                + "FROM scratch\n");
            for (String e : exported) {
                df.append("COPY --from=build /w/out/").append(e).append(" /").append(e).append("\n");
            }
            return new Recipe(df.toString(), Map.of("skald/prebuilt.tar.gz", prebuilt));
        };
    }

    private static PackageBuildDriver driver(PackageBuildDriver.HandIn handIn, PackageBuildDriver.Renderer renderer) {
        return new PackageBuildDriver(docker, launcher, client, handIn, renderer, config(List.of()));
    }

    private static PackageBuildDriver.Config config(List<String> repos) {
        return new PackageBuildDriver.Config(Settings.defaults(), 256,
            new Egress(List.of(registry.outer), registry.name, repos, List.of(), List.of(), List.of()),
            TestRegistry.CRED, Duration.ofMillis(500), Duration.ofSeconds(60), R_VERSION, R_PLATFORM,
            BinaryArchive.Limits.DEFAULTS);
    }

    private static void assertNothingLeft(BuildClaim claim) throws Exception {
        assertEquals(List.of(), launcher.leftovers(DockerWorkerLauncher.handle(PackageBuildDriver.attemptId(claim))));
    }

    // ------------------------------------------------------------------ the sandbox

    @Test
    @Timeout(value = 15, unit = java.util.concurrent.TimeUnit.MINUTES)
    public void aSandboxExportsOneCheckedBinaryWithNoNetworkInItsRun() throws Exception {
        byte[] prebuilt = installed("foo", "1.0");
        byte[] source = "source".getBytes(StandardCharsets.UTF_8);
        BuildClaim c = claim("foo", List.of(), source);
        // The RUN fails unless loopback is its only interface: what --network=none means.
        PackageBuildDriver.Result r = driver(handIn(source, Map.of(), Map.of()),
            standIn(prebuilt, NET_CHECK, false, "binary.tar.gz"))
            .run(c, () -> false);
        PackageBuildDriver.Built built = assertInstanceOf(PackageBuildDriver.Built.class, r, r.toString());
        assertArrayEquals(prebuilt, built.binary());
        assertEquals(sha256(prebuilt), built.checked().sha256());
        assertEquals("foo", built.checked().description().get("Package"));
        assertNothingLeft(c);
    }

    @Test
    @Timeout(value = 20, unit = java.util.concurrent.TimeUnit.MINUTES)
    public void aPooledSandboxExportsOneCheckedBinaryWithNoNetworkAndLeavesNothingOfItsEntry() throws Exception {
        // T7b, the fixed cost: the sandbox comes from the driver's own pool, launched with the
        // driver's request (its output volume, its egress) and warmed with the recipe's base.
        String base = registry.baseByDigest();
        byte[] prebuilt = installed("foo", "1.0");
        byte[] source = "source".getBytes(StandardCharsets.UTF_8);
        BuildClaim c = claim("foo", List.of(), source);
        PackageBuildDriver.Config config = config(List.of());
        WorkerPool pool = new WorkerPool(launcher, client,
            id -> new DockerWorkerLauncher.Request(id, config.settings(), config.quotaMegabytes(), config.egress(), true),
            List.of(base), registry.name, TestRegistry.CRED, WorkerPool.Settings.defaults());
        pool.tick();
        String entry = pool.ready().get(base).get(0);
        PackageBuildDriver.Renderer renderer = standIn(prebuilt, NET_CHECK, false, "binary.tar.gz");
        PackageBuildDriver.Renderer onDigest = (src, deps) -> {
            Recipe r = renderer.render(src, deps);
            return new Recipe(r.dockerfile().replace("FROM " + registry.base() + " ", "FROM " + base + " "), r.files());
        };
        try {
            PackageBuildDriver.Result r = new PackageBuildDriver(docker, launcher, client, handIn(source, Map.of(), Map.of()),
                onDigest, config, pool, base).run(c, () -> false);
            PackageBuildDriver.Built built = assertInstanceOf(PackageBuildDriver.Built.class, r, r.toString());
            assertArrayEquals(prebuilt, built.binary());
            assertEquals(List.of(), pool.ready().get(base), "the entry was taken");
            // bc66e6f-F1: checked the moment run() returns, with no wait: the loop settles the
            // row then, and from then on the package sweep (no grace) disposes of any attempt no
            // RUNNING row owns, so a disposal still pending at that point would race the sweep's.
            assertEquals(List.of(), launcher.leftovers(DockerWorkerLauncher.handle(PackageBuildDriver.attemptId(c))),
                "nothing of the attempt is left when run() returns");
            assertEquals(List.of(), launcher.leftovers(DockerWorkerLauncher.handle(entry)),
                "nothing of its entry is left when run() returns");
        } finally {
            pool.stop();
        }
        assertNothingLeft(c);
        assertEquals(List.of(), launcher.leftovers(DockerWorkerLauncher.handle(entry)), "the entry went with it");
    }

    @Test
    @Timeout(value = 15, unit = java.util.concurrent.TimeUnit.MINUTES)
    public void theNetworkCheckCanSeeAnInterface() throws Exception {
        // The control for the row above: the same RUN WITH a network fails the check, so the
        // check is not one that always passes.
        byte[] prebuilt = installed("foo", "1.0");
        byte[] source = "source".getBytes(StandardCharsets.UTF_8);
        BuildClaim c = claim("foo", List.of(), source);
        PackageBuildDriver.Result r = driver(handIn(source, Map.of(), Map.of()),
            standIn(prebuilt, NET_CHECK, true, "binary.tar.gz"))
            .run(c, () -> false);
        PackageBuildDriver.Failed f = assertInstanceOf(PackageBuildDriver.Failed.class, r, r.toString());
        assertEquals("BUILD_FAILED", f.code());
        assertNothingLeft(c);
    }

    @Test
    @Timeout(value = 15, unit = java.util.concurrent.TimeUnit.MINUTES)
    public void anythingElseTheBuildExportsIsRefused() throws Exception {
        byte[] prebuilt = installed("foo", "1.0");
        byte[] source = "source".getBytes(StandardCharsets.UTF_8);
        BuildClaim c = claim("foo", List.of(), source);
        PackageBuildDriver.Result r = driver(handIn(source, Map.of(), Map.of()),
            standIn(prebuilt, "", false, "binary.tar.gz", "extra")).run(c, () -> false);
        PackageBuildDriver.Refused refused = assertInstanceOf(PackageBuildDriver.Refused.class, r, r.toString());
        assertEquals("OUTPUT", refused.code());
        assertTrue(refused.detail().contains("extra"), refused.detail());
        assertNothingLeft(c);
    }

    @Test
    @Timeout(value = 15, unit = java.util.concurrent.TimeUnit.MINUTES)
    public void anExportOverTheLimitIsRefusedBeforeItIsRead() throws Exception {
        byte[] prebuilt = installed("foo", "1.0");
        byte[] source = "source".getBytes(StandardCharsets.UTF_8);
        BuildClaim c = claim("foo", List.of(), source);
        PackageBuildDriver.Config small = new PackageBuildDriver.Config(Settings.defaults(), 256,
            new Egress(List.of(registry.outer), registry.name, List.of(), List.of(), List.of(), List.of()),
            TestRegistry.CRED, Duration.ofMillis(500), Duration.ofSeconds(60), R_VERSION, R_PLATFORM,
            new BinaryArchive.Limits(prebuilt.length - 1, 1L << 30, 1000));
        PackageBuildDriver.Result r = new PackageBuildDriver(docker, launcher, client, handIn(source, Map.of(), Map.of()),
            standIn(prebuilt, "", false, "binary.tar.gz"), small).run(c, () -> false);
        PackageBuildDriver.Refused refused = assertInstanceOf(PackageBuildDriver.Refused.class, r, r.toString());
        assertEquals("OUTPUT", refused.code());
        assertTrue(refused.detail().contains("bytes, over"), refused.detail());
        assertNothingLeft(c);
    }

    @Test
    @Timeout(value = 15, unit = java.util.concurrent.TimeUnit.MINUTES)
    public void anExportThatIsNotThisPackageForThisBaseIsRefused() throws Exception {
        byte[] other = installed("foo", "2.0");
        byte[] source = "source".getBytes(StandardCharsets.UTF_8);
        BuildClaim c = claim("foo", List.of(), source);
        PackageBuildDriver.Result r = driver(handIn(source, Map.of(), Map.of()),
            standIn(other, "", false, "binary.tar.gz")).run(c, () -> false);
        PackageBuildDriver.Refused refused = assertInstanceOf(PackageBuildDriver.Refused.class, r, r.toString());
        assertEquals("BINARY_DESCRIPTION", refused.code());
        assertNothingLeft(c);
    }

    @Test
    @Timeout(value = 15, unit = java.util.concurrent.TimeUnit.MINUTES)
    public void aFailedInstallIsAFailedAttemptNamingItsLastLine() throws Exception {
        byte[] prebuilt = installed("foo", "1.0");
        byte[] source = "source".getBytes(StandardCharsets.UTF_8);
        BuildClaim c = claim("foo", List.of(), source);
        PackageBuildDriver.Result r = driver(handIn(source, Map.of(), Map.of()),
            standIn(prebuilt, "echo compile-error-xyz >&2; exit 3;", false, "binary.tar.gz")).run(c, () -> false);
        PackageBuildDriver.Failed f = assertInstanceOf(PackageBuildDriver.Failed.class, r, r.toString());
        assertEquals("BUILD_FAILED", f.code());
        assertTrue(f.detail().startsWith("exit 1"), f.detail());
        assertNothingLeft(c);
    }

    @Test
    @Timeout(value = 15, unit = java.util.concurrent.TimeUnit.MINUTES)
    public void aStopEndsTheSandboxAndLeavesNothing() throws Exception {
        byte[] prebuilt = installed("foo", "1.0");
        byte[] source = "source".getBytes(StandardCharsets.UTF_8);
        BuildClaim c = claim("foo", List.of(), source);
        long[] startedRun = {0};
        AtomicInteger polls = new AtomicInteger();
        PackageBuildDriver d = driver(handIn(source, Map.of(), Map.of()),
            standIn(prebuilt, "sleep 600;", false, "binary.tar.gz"));
        long start = System.nanoTime();
        PackageBuildDriver.Result r = d.run(c, () -> {
            // Stop once the client is up (the sleep is running), as a lost lease would.
            try {
                boolean clientUp = docker.inspectContainer(DockerWorkerLauncher.handle(PackageBuildDriver.attemptId(c))
                    .client()).state().running();
                if (clientUp && startedRun[0] == 0) {
                    startedRun[0] = System.nanoTime();
                }
            } catch (Exception e) {
                // not yet
            }
            polls.incrementAndGet();
            return startedRun[0] != 0 && System.nanoTime() - startedRun[0] > 5_000_000_000L;
        });
        assertInstanceOf(PackageBuildDriver.Stopped.class, r);
        assertTrue((System.nanoTime() - start) < 600_000_000_000L, "stopped long before the RUN would end");
        assertNothingLeft(c);
    }

    @Test
    @Timeout(value = 15, unit = java.util.concurrent.TimeUnit.MINUTES)
    public void aClientStalledDuringTheCompileStillExportsTheBinary() throws Exception {
        // 2026-10-18: duckdb compiled for about 90 minutes on a stalled host, twice, and each
        // time the export failed with "no active session": BuildKit 0.33 drops a session after
        // two missed 15 s health checks, and only the export needs one. A 45 s pause of the
        // client during the RUN misses those checks the same way.
        byte[] prebuilt = installed("foo", "1.0");
        byte[] source = "source".getBytes(StandardCharsets.UTF_8);
        BuildClaim c = claim("foo", List.of(), source);
        String clientName = DockerWorkerLauncher.handle(PackageBuildDriver.attemptId(c)).client();
        java.util.concurrent.atomic.AtomicBoolean paused = new java.util.concurrent.atomic.AtomicBoolean();
        Thread staller = new Thread(() -> {
            try {
                while (true) {
                    try {
                        if (docker.inspectContainer(clientName).state().running()) {
                            break;
                        }
                    } catch (Exception e) {
                        // not created yet
                    }
                    Thread.sleep(500);
                }
                Thread.sleep(15_000);
                docker.pauseContainer(clientName);
                paused.set(true);
                Thread.sleep(45_000);
                docker.unpauseContainer(clientName);
            } catch (Exception e) {
                // the assertions below report it
            }
        });
        staller.start();
        PackageBuildDriver.Result r = driver(handIn(source, Map.of(), Map.of()),
            standIn(prebuilt, "sleep 90;", false, "binary.tar.gz")).run(c, () -> false);
        staller.join(120_000);
        assertTrue(paused.get(), "the client was paused during the RUN");
        PackageBuildDriver.Built built = assertInstanceOf(PackageBuildDriver.Built.class, r, r.toString());
        assertArrayEquals(prebuilt, built.binary());
        assertNothingLeft(c);
    }

    // ------------------------------------------------------------------ before any Docker call

    @Test
    public void aHandedInFileThatIsNotTheRecordedOneIsRefusedBeforeAnyWorker() throws Exception {
        byte[] source = "source".getBytes(StandardCharsets.UTF_8);
        byte[] dep = installed("bar", "2.0");
        BuildClaim c = claim("foo", List.of(new BuiltDependency("bar", "2.0", "f".repeat(64))), source);
        Base base = new Base(registry.name + ":5000/base/busybox@sha256:" + "a".repeat(64), "r", R_VERSION, "10001",
            "10001", "noble");
        PackageBuildDriver.Result r = driver(handIn(source, Map.of("bar 2.0", dep), Map.of()),
            (s, deps) -> PackageRecipe.render(base, s, deps)).run(c, () -> false);
        PackageBuildDriver.Refused refused = assertInstanceOf(PackageBuildDriver.Refused.class, r, r.toString());
        assertEquals("HANDIN_DIGEST", refused.code());
        assertFalse(docker.listContainers(DockerClient.ListContainersParam.allContainers(),
            DockerClient.ListContainersParam.withLabel(DockerWorkerLauncher.ATTEMPT_LABEL,
                PackageBuildDriver.attemptId(c))).iterator().hasNext(), "no worker was launched");
    }

    @Test
    public void twoVersionsTheLockDoesNotSettleFailBeforeAnyWorkerAndTheLockedOneIsKept() throws Exception {
        byte[] source = "source".getBytes(StandardCharsets.UTF_8);
        byte[] v1 = installed("bar", "1.0");
        byte[] v2 = installed("bar", "2.0");
        List<BuiltDependency> closure = List.of(new BuiltDependency("bar", "1.0", sha256(v1)),
            new BuiltDependency("bar", "2.0", sha256(v2)));
        BuildClaim c = claim("foo", closure, source);
        PackageBuildDriver.Result r = driver(handIn(source, Map.of("bar 1.0", v1, "bar 2.0", v2), Map.of("bar", "3.0")),
            (s, deps) -> {
                throw new AssertionError("nothing is rendered");
            }).run(c, () -> false);
        assertEquals("DEPENDENCY_CONFLICT", assertInstanceOf(PackageBuildDriver.Failed.class, r).code());
        List<List<PackageRecipe.HandedIn>> seen = new java.util.ArrayList<>();
        driver(handIn(source, Map.of("bar 1.0", v1, "bar 2.0", v2), Map.of("bar", "2.0")), (s, deps) -> {
            seen.add(deps);
            throw new IllegalArgumentException("stop here");
        }).run(claim("foo", closure, source), () -> false);
        assertEquals(1, seen.get(0).size());
        assertEquals("2.0", seen.get(0).get(0).version(), "the version the claimed row's lock fixes");
    }

    @Test
    public void aSandboxReachesTheBasesRegistryAndNothingElse() {
        assertThrows(IllegalArgumentException.class, () -> config(List.of("cloud.r-project.org")));
        assertThrows(IllegalArgumentException.class, () -> new PackageBuildDriver.Config(Settings.defaults(), 256,
            new Egress(List.of(), "skald-registry", List.of(), List.of("mirror.internal"), List.of(), List.of()),
            TestRegistry.CRED, Duration.ofMillis(500), Duration.ofSeconds(60), R_VERSION, R_PLATFORM,
            BinaryArchive.Limits.DEFAULTS));
    }

    @Test
    public void aPackageAttemptIsNeverMistakenForAnAppBuildsBySweeper() throws Exception {
        String id = PackageBuildDriver.attemptId(claim("foo", List.of(), new byte[] {1}));
        assertFalse(id.matches("[0-9a-f]{32}"), id);
        assertTrue(id.matches("[a-z0-9]{8,40}"), "a valid attempt id: " + id);
    }
}
