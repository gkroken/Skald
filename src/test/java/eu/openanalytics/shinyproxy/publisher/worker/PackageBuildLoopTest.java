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

import com.sun.net.httpserver.HttpServer;
import eu.openanalytics.shinyproxy.publisher.binaries.BinaryArchive;
import eu.openanalytics.shinyproxy.publisher.binaries.BinaryBuildQueue;
import eu.openanalytics.shinyproxy.publisher.binaries.BinaryBuildQueue.BuildClaim;
import eu.openanalytics.shinyproxy.publisher.binaries.BinaryGate;
import eu.openanalytics.shinyproxy.publisher.binaries.BinaryQueueSettings;
import eu.openanalytics.shinyproxy.publisher.binaries.BinaryUploader;
import eu.openanalytics.shinyproxy.publisher.binaries.ForgePlatforms;
import eu.openanalytics.shinyproxy.publisher.binaries.ForgeSettings;
import eu.openanalytics.shinyproxy.publisher.binaries.SourceSettings;
import eu.openanalytics.shinyproxy.publisher.build.CoordinatorSettings;
import eu.openanalytics.shinyproxy.publisher.db.TestPostgres;
import eu.openanalytics.shinyproxy.publisher.recipe.BaseCatalog;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The binaries' loop and housekeeping (T7b step 2e, part 1b) against real PostgreSQL, a stub
 * forge (registration and binary tree) and a fake sandbox runner: what each outcome does to the
 * row, the gate before any sandbox, and the lease renewed across the whole attempt.
 */
public class PackageBuildLoopTest {

    @SuppressWarnings("resource")
    private static final PostgreSQLContainer<?> POSTGRES =
        TestPostgres.create();

    private static final String KEY = "noble-x86_64/4.6";
    private static final String IMAGE_ID = "sha256:" + "a".repeat(64);
    private static final String TOKEN = "loop_test_token";
    private static JdbcTemplate jdbc;
    private static HttpServer server;
    private static URI forge;
    private static volatile String registration;
    private static final Map<String, byte[]> stored = new ConcurrentHashMap<>();

    @BeforeAll
    public static void start() throws IOException {
        POSTGRES.start();
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .schemas("skald").defaultSchema("skald").locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
            POSTGRES.getPassword()));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            byte[] body = new byte[0];
            int status;
            if (path.startsWith("/api/v1/cran/platforms/")) {
                status = registration == null ? 404 : 200;
                body = registration == null ? body : registration.getBytes(StandardCharsets.UTF_8);
            } else if (exchange.getRequestMethod().equals("PUT")) {
                stored.put(path, exchange.getRequestBody().readAllBytes());
                status = 201;
            } else {
                body = stored.get(path);
                status = body == null ? 404 : 200;
            }
            exchange.sendResponseHeaders(status, body == null || body.length == 0 ? -1 : body.length);
            if (body != null && body.length > 0) {
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        server.start();
        forge = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
    }

    @AfterAll
    public static void stop() {
        server.stop(0);
        POSTGRES.stop();
    }

    @BeforeEach
    public void clean() {
        jdbc.update("TRUNCATE skald.binary_build_upload, skald.binary_build_dep, skald.binary_build, skald.binary_lock");
        stored.clear();
        registration = "{\"key\":\"" + KEY + "\",\"rPlatform\":\"x86_64-pc-linux-gnu\",\"builderImage\":\"b\","
            + "\"builderDigest\":\"" + IMAGE_ID + "\"}";
    }

    // ------------------------------------------------------------------ fixtures

    private static final BinaryQueueSettings SHORT = new BinaryQueueSettings(Duration.ofSeconds(2),
        Duration.ofMillis(400), Duration.ofMinutes(10), 1, 2);

    private static ForgeSettings forgeSettings() {
        return new ForgeSettings(forge, "cran-linux", TOKEN, Duration.ofSeconds(0), Duration.ofSeconds(5));
    }

    private static BaseCatalog.Entry base() {
        return new BaseCatalog.Entry("r-shiny-4.6.1-amd64-r3", "shiny", "r", "4.6.1", "amd64", 3, "10001", "10001",
            Map.of(), "noble", "x86_64-pc-linux-gnu", Map.of());
    }

    private static byte[] binary(String pkg) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(new GZIPOutputStream(b))) {
            for (var f : Map.of(pkg + "/DESCRIPTION", "Package: " + pkg + "\nVersion: 1.0\nBuilt: R 4.6.1;"
                    + " x86_64-pc-linux-gnu; 2026-10-11; unix\n", pkg + "/Meta/package.rds", "rds").entrySet()) {
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

    private static BinaryBuildQueue queue(BinaryQueueSettings s) {
        return new BinaryBuildQueue(jdbc, s, CoordinatorSettings.DEFAULTS);
    }

    /** aaa 1.0, verified and QUEUED. */
    private static UUID queued(BinaryBuildQueue q) {
        UUID id = q.enqueue(KEY, "aaa", "1.0", null);
        q.recordSource(q.claimSource("v").orElseThrow().lease(), "1".repeat(64), "2".repeat(32), List.of());
        return id;
    }

    private static PackageBuildLoop loop(BinaryBuildQueue q, BinaryQueueSettings s, PackageBuildLoop.Runner runner) {
        BinaryGate gate = new BinaryGate(new ForgePlatforms(forgeSettings()));
        return new PackageBuildLoop(q, null, new BinaryUploader(q, gate, forgeSettings(), BinaryArchive.Limits.DEFAULTS),
            gate, Map.of(KEY, List.of(new PackageBuildLoop.Platform(base(), "reg:5000/skald/base/r@sha256:"
                + "e".repeat(64), Optional.of(IMAGE_ID), runner))), "test", s, Duration.ofMillis(50));
    }

    private static PackageBuildDriver.Built built(BuildClaim claim) {
        try {
            byte[] b = binary(claim.pkg());
            return new PackageBuildDriver.Built(b, BinaryArchive.check(b, claim.pkg(), claim.version(), "4.6.1",
                "x86_64-pc-linux-gnu", BinaryArchive.Limits.DEFAULTS), List.of());
        } catch (BinaryArchive.Refused | IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Map<String, Object> row(UUID id) {
        return jdbc.queryForMap("SELECT * FROM skald.binary_build WHERE id = ?", id);
    }

    // ------------------------------------------------------------------ outcomes

    @Test
    public void aBuiltBinaryIsUploaded() throws Exception {
        BinaryBuildQueue q = queue(BinaryQueueSettings.DEFAULTS);
        UUID id = queued(q);
        assertTrue(loop(q, BinaryQueueSettings.DEFAULTS, (c, stop) -> built(c)).compileOnce());
        assertEquals("UPLOADED", row(id).get("state"));
        assertTrue(stored.containsKey("/repository/cran-linux/bin/linux/noble-x86_64/contrib/4.6/aaa_1.0.tar.gz"));
        assertFalse(loop(q, BinaryQueueSettings.DEFAULTS, (c, stop) -> built(c)).compileOnce(), "nothing left");
    }

    @Test
    public void aRefusalAFailureAndAStopEachDoWhatTheySay() throws Exception {
        BinaryBuildQueue q = queue(BinaryQueueSettings.DEFAULTS);
        UUID a = queued(q);
        loop(q, BinaryQueueSettings.DEFAULTS, (c, stop) -> new PackageBuildDriver.Failed("BUILD_FAILED", "x")).compileOnce();
        assertEquals("QUEUED", row(a).get("state"), "a failed attempt is retried");
        assertEquals("BUILD_FAILED", row(a).get("error_code"));
        loop(q, BinaryQueueSettings.DEFAULTS, (c, stop) -> new PackageBuildDriver.Refused("OUTPUT", "y")).compileOnce();
        assertEquals("REFUSED", row(a).get("state"));
        assertEquals("OUTPUT", row(a).get("refusal_code"));
        clean();
        UUID b = queued(q);
        loop(q, BinaryQueueSettings.DEFAULTS, (c, stop) -> new PackageBuildDriver.Stopped()).compileOnce();
        assertEquals("RUNNING", row(b).get("state"), "a stop writes nothing; the reaper settles it");
    }

    @Test
    public void theGateIsAskedBeforeAnySandboxRuns() throws Exception {
        BinaryBuildQueue q = queue(BinaryQueueSettings.DEFAULTS);
        UUID id = queued(q);
        registration = null;
        AtomicInteger ran = new AtomicInteger();
        loop(q, BinaryQueueSettings.DEFAULTS, (c, stop) -> {
            ran.incrementAndGet();
            return built(c);
        }).compileOnce();
        assertEquals(0, ran.get(), "no sandbox for a base forge does not register (ef929f5 N1)");
        assertEquals("REGISTRATION", row(id).get("error_code"));
        assertEquals("QUEUED", row(id).get("state"));
    }

    @Test
    public void aKeyNoBaseServesIsAFailedAttempt() throws Exception {
        BinaryBuildQueue q = queue(BinaryQueueSettings.DEFAULTS);
        UUID id = q.enqueue("trixie-x86_64/4.6", "aaa", "1.0", null);
        q.recordSource(q.claimSource("v").orElseThrow().lease(), "1".repeat(64), "2".repeat(32), List.of());
        loop(q, BinaryQueueSettings.DEFAULTS, (c, stop) -> built(c)).compileOnce();
        assertEquals("REGISTRATION", row(id).get("error_code"));
    }

    // ------------------------------------------------------------------ the lease across the attempt

    @Test
    public void theLeaseIsRenewedAcrossALongAttempt() throws Exception {
        BinaryBuildQueue q = queue(SHORT);
        UUID id = queued(q);
        // Three seconds of "sandbox" against a two-second lease: renewed, so the upload lands.
        loop(q, SHORT, (c, stop) -> {
            Thread.sleep(3000);
            return built(c);
        }).compileOnce();
        assertEquals("UPLOADED", row(id).get("state"), "the lease outlived the sandbox (ae287bf N2)");
    }

    @Test
    public void aCompileThisProcessHoldsSurvivesItsOwnReaperAfterAStall() throws Exception {
        // d6fbcde N1: the whole process stalls past the lease, and its reaper runs before the
        // renewal. The compile is alive here, so the row stays its own and the upload lands.
        BinaryBuildQueue q = queue(BinaryQueueSettings.DEFAULTS);
        UUID id = queued(q);
        int[] reaped = {-1};
        boolean[] renewed = {false};
        loop(q, BinaryQueueSettings.DEFAULTS, (c, stop) -> {
            jdbc.update("UPDATE skald.binary_build SET lease_expires_at = now() - interval '1 second' WHERE id = ?", id);
            reaped[0] = q.reap();
            // Then the renewal, late: it takes the lease back.
            renewed[0] = q.renew(c.lease());
            return built(c);
        }).compileOnce();
        assertEquals(0, reaped[0], "the reaper left the live compile's row alone");
        assertTrue(renewed[0]);
        assertEquals("UPLOADED", row(id).get("state"));
    }

    @Test
    public void aSandboxThatFinishesAsAStallEndsStillUploads() throws Exception {
        // 4219fc0 N1: the lease expired during a stall and the sandbox returns before the next
        // scheduled renewal (30 s away); the loop renews once before its first write.
        BinaryBuildQueue q = queue(BinaryQueueSettings.DEFAULTS);
        UUID id = queued(q);
        loop(q, BinaryQueueSettings.DEFAULTS, (c, stop) -> {
            jdbc.update("UPDATE skald.binary_build SET lease_expires_at = now() - interval '1 second' WHERE id = ?", id);
            return built(c);
        }).compileOnce();
        assertEquals("UPLOADED", row(id).get("state"));
    }

    @Test
    public void aLostLeaseIsTheSandboxsStopSignalAndNothingIsWritten() throws Exception {
        BinaryBuildQueue q = queue(SHORT);
        UUID id = queued(q);
        AtomicBoolean sawStop = new AtomicBoolean();
        loop(q, SHORT, (c, stop) -> {
            jdbc.update("UPDATE skald.binary_build SET lease_owner = 'someone-else' WHERE id = ?", id);
            for (int i = 0; i < 50 && !stop.getAsBoolean(); i++) {
                Thread.sleep(100);
            }
            sawStop.set(stop.getAsBoolean());
            return built(c);
        }).compileOnce();
        assertTrue(sawStop.get(), "the renewal's failure reached the sandbox");
        assertEquals("RUNNING", row(id).get("state"));
        assertTrue(stored.isEmpty(), "nothing uploaded");
    }

    @Test
    public void theLoopsThreadsRunAClaimEndToEnd() throws Exception {
        BinaryBuildQueue q = queue(BinaryQueueSettings.DEFAULTS);
        UUID id = queued(q);
        PackageBuildLoop loop = new PackageBuildLoop(q, new eu.openanalytics.shinyproxy.publisher.binaries.SourceVerifier(q,
            uri -> {
                throw new IOException("no source repository in this test");
            }, new SourceSettings(forge, 1, 1, 1, Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofMinutes(5)),
                BinaryQueueSettings.DEFAULTS),
            new BinaryUploader(q, new BinaryGate(new ForgePlatforms(forgeSettings())), forgeSettings(),
                BinaryArchive.Limits.DEFAULTS), new BinaryGate(new ForgePlatforms(forgeSettings())),
            Map.of(KEY, List.of(new PackageBuildLoop.Platform(base(), "ref", Optional.of(IMAGE_ID), (c, stop) -> built(c)))),
            "test", BinaryQueueSettings.DEFAULTS, Duration.ofMillis(50));
        loop.start();
        try {
            for (int i = 0; i < 100 && !"UPLOADED".equals(row(id).get("state")); i++) {
                Thread.sleep(100);
            }
        } finally {
            loop.stop();
        }
        assertEquals("UPLOADED", row(id).get("state"));
    }

    // ------------------------------------------------------------------ housekeeping

    @Test
    public void theSweepDisposesOnlyPackageAttemptsNoRunningRowOwns() {
        String live = "p" + "1".repeat(24) + "00000003";
        String dead = "p" + "2".repeat(24) + "00000001";
        String app = "a".repeat(32);
        List<String> disposed = new ArrayList<>();
        BinaryHousekeeping h = new BinaryHousekeeping(() -> { }, () -> Set.of(live, dead, app), () -> Set.of(live),
            disposed::add, Duration.ofMinutes(1), Duration.ofMinutes(10));
        assertEquals(List.of(dead), h.sweep());
        assertEquals(List.of(dead), disposed, "never a live sandbox, never an app build's attempt");
    }

    @Test
    public void aSandboxClaimedAndLaunchedWhileTheInventoryRunsIsKept() {
        // fca2f81-F1, the reviewer's probe: the row is claimed and its sandbox launched during the
        // inventory, so it is on the host and was not live before the inventory began.
        String fresh = "p" + "3".repeat(24) + "00000001";
        Set<String> leases = new java.util.HashSet<>();
        List<String> order = new ArrayList<>();
        List<String> disposed = new ArrayList<>();
        BinaryHousekeeping h = new BinaryHousekeeping(() -> { }, () -> {
            order.add("present");
            leases.add(fresh);
            return Set.of(fresh);
        }, () -> {
            order.add("live");
            return Set.copyOf(leases);
        }, disposed::add, Duration.ofMinutes(1), Duration.ofMinutes(10));
        assertEquals(List.of(), h.sweep(), "a live sandbox is never disposed of");
        assertEquals(List.of("present", "live"), order);
        assertTrue(disposed.isEmpty());
    }

    @Test
    public void housekeepingNeverThrows() {
        BinaryHousekeeping h = new BinaryHousekeeping(() -> {
            throw new IllegalStateException("db down");
        }, () -> {
            throw new IllegalStateException("docker down");
        }, Set::of, id -> { }, Duration.ofMinutes(1), Duration.ofMinutes(10));
        h.reapTick();
        h.sweepTick();
    }

    @Test
    public void theRunningLeasesAreThePackageAttemptsThatMayExist() {
        BinaryBuildQueue q = queue(BinaryQueueSettings.DEFAULTS);
        UUID id = queued(q);
        BuildClaim c = q.claimBuild("b").orElseThrow();
        assertEquals(Map.of(id, c.lease().generation()), q.runningLeases());
        assertEquals(PackageBuildDriver.attemptId(c), PackageBuildDriver.attemptId(id, c.lease().generation()));
    }

    // ------------------------------------------------------------------ the wiring's refusals

    @Test
    public void enabledBinariesRefuseToRunHalfConfigured() {
        WorkerConfig worker = new WorkerConfig();
        List<String> p = BinariesConfig.problems(worker, ForgeSettings.DEFAULTS, SourceSettings.DEFAULTS, false);
        assertEquals(4, p.size(), String.valueOf(p));
        worker.setCranLinuxBinaries(true);
        assertTrue(BinariesConfig.problems(worker, forgeSettings(), new SourceSettings(forge, 1, 1, 1,
            Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofMinutes(5)), true).isEmpty());
    }

    @Test
    public void binariesAreOffByDefault() {
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
            .withUserConfiguration(BinariesConfig.class)
            .withPropertyValues("skald.builds.worker.enabled=true")
            .run(ctx -> assertTrue(ctx.getBeansOfType(PackageBuildLoop.class).isEmpty(), "off without binaries-enabled"));
    }
}
