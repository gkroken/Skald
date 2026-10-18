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
package eu.openanalytics.shinyproxy.publisher.binaries;

import com.sun.net.httpserver.HttpServer;
import eu.openanalytics.shinyproxy.publisher.binaries.BinaryBuildQueue.BuildClaim;
import eu.openanalytics.shinyproxy.publisher.binaries.BinaryBuildQueue.BuiltDependency;
import eu.openanalytics.shinyproxy.publisher.build.CoordinatorSettings;
import eu.openanalytics.shinyproxy.publisher.db.TestPostgres;
import eu.openanalytics.shinyproxy.publisher.recipe.BaseCatalog;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The trusted side's forge half (T7b step 2d, part 2): the hand-in over forge and the upload,
 * against real PostgreSQL and a stub forge that serves the registration, the source and the
 * binary tree, and records what it was sent.
 */
public class BinaryUploaderTest {

    @SuppressWarnings("resource")
    private static final PostgreSQLContainer<?> POSTGRES =
        TestPostgres.create();

    private static final String KEY = "noble-x86_64/4.6";
    private static final String TOKEN = "forge_uploader_test_token";
    private static final String IMAGE_ID = "sha256:" + "a".repeat(64);
    private static final String BUILT = "R 4.6.1; x86_64-pc-linux-gnu; 2026-10-11 10:00:00 UTC; unix";
    private static final String TREE = "/repository/cran-linux/bin/linux/noble-x86_64/contrib/4.6/";
    private static JdbcTemplate jdbc;
    private static HttpServer server;
    private static URI forge;
    private static final Map<String, byte[]> stored = new ConcurrentHashMap<>();
    private static final Map<String, Integer> putStatus = new ConcurrentHashMap<>();
    private static final Map<String, Map<String, String>> putHeaders = new ConcurrentHashMap<>();
    private static volatile String registration;
    /** Paths whose response stalls after its headers and a few bytes (ae287bf-F1). */
    private static final java.util.Set<String> stalled = ConcurrentHashMap.newKeySet();
    private static final java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);

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
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            if (stalled.contains(exchange.getRequestMethod() + " " + path)) {
                exchange.getRequestBody().readAllBytes();
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write("{\"k".getBytes(StandardCharsets.UTF_8));
                exchange.getResponseBody().flush();
                try {
                    release.await();
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                exchange.close();
                return;
            }
            byte[] body = new byte[0];
            int status;
            if (path.equals("/api/v1/cran/platforms/" + KEY)) {
                status = registration == null ? 404 : 200;
                body = registration == null ? body : registration.getBytes(StandardCharsets.UTF_8);
            } else if (path.startsWith("/repository/cran-proxy/")) {
                body = stored.get(path);
                status = body == null ? 404 : 200;
            } else if (!("Bearer " + TOKEN).equals(auth)) {
                status = 401;
            } else if (exchange.getRequestMethod().equals("PUT")) {
                byte[] sent = exchange.getRequestBody().readAllBytes();
                Map<String, String> h = new ConcurrentHashMap<>();
                exchange.getRequestHeaders().forEach((k, v) -> h.put(k.toLowerCase(), String.join(",", v)));
                putHeaders.put(path, h);
                status = putStatus.getOrDefault(path, stored.containsKey(path) ? 409 : 201);
                if (status == 201) {
                    stored.put(path, sent);
                }
                body = (status == 400 ? "refused: Built is not this platform" : "ok").getBytes(StandardCharsets.UTF_8);
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
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
        forge = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
    }

    @AfterAll
    public static void stop() {
        release.countDown();
        server.stop(0);
        POSTGRES.stop();
    }

    @BeforeEach
    public void clean() {
        jdbc.update("TRUNCATE skald.binary_build_upload, skald.binary_build_dep, skald.binary_build, skald.binary_lock");
        stored.clear();
        putStatus.clear();
        putHeaders.clear();
        stalled.clear();
        registration = "{\"key\":\"" + KEY + "\",\"rPlatform\":\"x86_64-pc-linux-gnu\",\"builderImage\":\"b\","
            + "\"builderDigest\":\"" + IMAGE_ID + "\"}";
    }

    // ------------------------------------------------------------------ fixtures

    private static ForgeSettings forgeSettings() {
        return new ForgeSettings(forge, "cran-linux", TOKEN, Duration.ofSeconds(0), Duration.ofSeconds(5));
    }

    private static BinaryBuildQueue queue() {
        return new BinaryBuildQueue(jdbc, BinaryQueueSettings.DEFAULTS, CoordinatorSettings.DEFAULTS);
    }

    private static BinaryUploader uploader(BinaryBuildQueue q) {
        return new BinaryUploader(q, new BinaryGate(new ForgePlatforms(forgeSettings())), forgeSettings(),
            BinaryArchive.Limits.DEFAULTS);
    }

    private static BaseCatalog.Entry base() {
        return new BaseCatalog.Entry("r-shiny-4.6.1-amd64-r2", "shiny", "r", "4.6.1", "amd64", 2, "10001", "10001",
            Map.of(), "noble", "x86_64-pc-linux-gnu", Map.of("Matrix", "1.7-5"));
    }

    private static byte[] binary(String pkg, String version, String linkingTo) {
        return new TarBuilder().dir(pkg + "/").file(pkg + "/DESCRIPTION", "Package: " + pkg + "\nVersion: " + version
            + "\nBuilt: " + BUILT + "\n" + (linkingTo == null ? "" : "LinkingTo: " + linkingTo + "\n"))
            .file(pkg + "/Meta/package.rds", "rds").tgz();
    }

    /** A RUNNING row for aaa 1.0, its compile done. */
    private static BuildClaim running(BinaryBuildQueue q) {
        UUID id = q.enqueue(KEY, "aaa", "1.0", null);
        BinaryBuildQueue.SourceClaim s = q.claimSource("v").orElseThrow();
        assertEquals(id, s.rowId());
        q.recordSource(s.lease(), "1".repeat(64), "2".repeat(32), List.of());
        return q.claimBuild("b").orElseThrow();
    }

    private static Map<String, Object> row(UUID id) {
        return jdbc.queryForMap("SELECT * FROM skald.binary_build WHERE id = ?", id);
    }

    private static BinaryUploader.Result upload(BinaryBuildQueue q, BuildClaim c, byte[] bin, List<BuiltDependency> deps)
            throws Exception {
        BinaryArchive.Checked checked = BinaryArchive.check(bin, c.pkg(), c.version(), "4.6.1", "x86_64-pc-linux-gnu",
            BinaryArchive.Limits.DEFAULTS);
        return uploader(q).upload(c, bin, checked, deps, base(), "reg:5000/skald/base/r@sha256:" + "e".repeat(64),
            Optional.of(IMAGE_ID));
    }

    // ------------------------------------------------------------------ the upload

    @Test
    public void aBinaryIsUploadedWithTheBasesImageIdAndItsLinkingToAndRecordedAsSkalds() throws Exception {
        BinaryBuildQueue q = queue();
        BuildClaim c = running(q);
        byte[] bin = binary("aaa", "1.0", "Rcpp, Matrix, missing");
        List<BuiltDependency> deps = List.of(new BuiltDependency("Rcpp", "1.0.13", "3".repeat(64)));
        BinaryUploader.Result r = upload(q, c, bin, deps);
        assertEquals(BinaryUploader.Uploaded.UPLOADED, r.outcome(), r.detail());
        assertArrayEquals(bin, stored.get(TREE + "aaa_1.0.tar.gz"), "the bytes uploaded are the bytes checked");
        Map<String, String> h = putHeaders.get(TREE + "aaa_1.0.tar.gz");
        assertEquals(IMAGE_ID, h.get("x-forge-builder-digest"));
        assertEquals("Rcpp 1.0.13, Matrix 1.7-5", h.get("x-forge-linking-to"),
            "from what was handed in and the base's library; a package neither has is left out");
        Map<String, Object> row = row(c.rowId());
        assertEquals("UPLOADED", row.get("state"));
        assertEquals(BinaryArchive.sha256(bin), row.get("binary_sha256"));
        String prov = String.valueOf(row.get("provenance"));
        for (String part : List.of("skald.binary-provenance/v1", "\"image_id\": \"" + IMAGE_ID + "\"", "Rcpp",
                "index: MD5 matched the public index", "--network=none", "\"linking_to\": \"Rcpp 1.0.13, Matrix 1.7-5\"")) {
            assertTrue(prov.contains(part), part + " in " + prov);
        }
        assertFalse(prov.contains(TOKEN), "the token is in no record");
        assertFalse(jdbc.queryForObject("SELECT string_agg(detail_json::text, ' ') FROM skald.audit_event",
            String.class).contains(TOKEN), "nor in the audit");
    }

    @Test
    public void a409ForSkaldsOwnEarlierUploadIsRecordedAsSkalds() throws Exception {
        BinaryBuildQueue q = queue();
        byte[] bin = binary("aaa", "1.0", null);
        BuildClaim first = running(q);
        q.intendUpload(first.lease(), BinaryArchive.sha256(bin), Map.of("schema", "test"));
        stored.put(TREE + "aaa_1.0.tar.gz", bin);
        jdbc.update("UPDATE skald.binary_build SET lease_expires_at = now() - interval '1 second' WHERE id = ?",
            first.rowId());
        q.reap();
        BuildClaim second = q.claimBuild("b").orElseThrow();
        // This attempt compiled different bytes (R binaries are not reproducible).
        BinaryUploader.Result r = upload(q, second, binary("aaa", "1.0", "Rcpp"), List.of());
        assertEquals(BinaryUploader.Uploaded.UPLOADED_EARLIER, r.outcome());
        assertEquals("UPLOADED", row(second.rowId()).get("state"));
        assertEquals(BinaryArchive.sha256(bin), row(second.rowId()).get("binary_sha256"));
    }

    @Test
    public void a409ForAnotherBuildersBinaryIsForeign() throws Exception {
        BinaryBuildQueue q = queue();
        BuildClaim c = running(q);
        byte[] theirs = binary("aaa", "1.0", "other");
        stored.put(TREE + "aaa_1.0.tar.gz", theirs);
        BinaryUploader.Result r = upload(q, c, binary("aaa", "1.0", null), List.of());
        assertEquals(BinaryUploader.Uploaded.FOREIGN, r.outcome());
        assertEquals("PRESENT_FOREIGN", row(c.rowId()).get("state"));
        assertEquals(BinaryArchive.sha256(theirs), row(c.rowId()).get("binary_sha256"));
    }

    @Test
    public void aBinaryForgeRefusesIsRefusedWithForgesReason() throws Exception {
        BinaryBuildQueue q = queue();
        BuildClaim c = running(q);
        putStatus.put(TREE + "aaa_1.0.tar.gz", 400);
        BinaryUploader.Result r = upload(q, c, binary("aaa", "1.0", null), List.of());
        assertEquals(BinaryUploader.Uploaded.REFUSED, r.outcome());
        Map<String, Object> row = row(c.rowId());
        assertEquals("REFUSED", row.get("state"));
        assertEquals("FORGE_REJECTED", row.get("refusal_code"));
        assertTrue(String.valueOf(row.get("refusal_detail")).contains("Built is not this platform"));
    }

    @Test
    public void aForgeErrorIsAFailedAttempt() throws Exception {
        for (int status : new int[] {403, 500}) {
            clean();
            BinaryBuildQueue q = queue();
            BuildClaim c = running(q);
            putStatus.put(TREE + "aaa_1.0.tar.gz", status);
            BinaryUploader.Result r = upload(q, c, binary("aaa", "1.0", null), List.of());
            assertEquals(BinaryUploader.Uploaded.FAILED, r.outcome(), "status " + status);
            Map<String, Object> row = row(c.rowId());
            assertEquals("QUEUED", row.get("state"), "retried");
            assertEquals("UPLOAD", row.get("error_code"));
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM skald.binary_build_upload WHERE binary_build_id = ?",
                Integer.class, c.rowId()), "the intent was recorded before the PUT");
        }
    }

    @Test
    public void aBaseForgeNoLongerRegistersGetsNoUpload() throws Exception {
        BinaryBuildQueue q = queue();
        BuildClaim c = running(q);
        registration = "{\"key\":\"" + KEY + "\",\"rPlatform\":\"x86_64-pc-linux-gnu\",\"builderImage\":\"b\","
            + "\"builderDigest\":\"sha256:" + "f".repeat(64) + "\"}";
        BinaryUploader.Result r = upload(q, c, binary("aaa", "1.0", null), List.of());
        assertEquals(BinaryUploader.Uploaded.FAILED, r.outcome());
        assertEquals("REGISTRATION", row(c.rowId()).get("error_code"));
        assertTrue(putHeaders.isEmpty(), "nothing was sent to forge");
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM skald.binary_build_upload", Integer.class),
            "no intent either");
    }

    @Test
    public void aRowOfAnotherPlatformKeyIsNeverUploadedForThisBase() throws Exception {
        BinaryBuildQueue q = queue();
        BuildClaim c = running(q);
        BuildClaim other = new BuildClaim(c.rowId(), "noble-x86_64/4.5", c.pkg(), c.version(), c.sourceSha256(),
            c.sourceMd5(), c.dependencies(), c.lease(), c.requestedBy(), c.sourceKind());
        BinaryUploader.Result r = upload(q, other, binary("aaa", "1.0", null), List.of());
        assertEquals(BinaryUploader.Uploaded.FAILED, r.outcome());
        assertEquals("REGISTRATION", row(c.rowId()).get("error_code"));
        assertTrue(putHeaders.isEmpty());
    }

    // ------------------------------------------------------------------ stalls (ae287bf-F1)

    private static ForgeSettings quick() {
        return new ForgeSettings(forge, "cran-linux", TOKEN, Duration.ofSeconds(0), Duration.ofSeconds(2),
            Duration.ofSeconds(3));
    }

    private static BinaryUploader.Result quickUpload(BinaryBuildQueue q, BuildClaim c, byte[] bin) throws Exception {
        BinaryArchive.Checked checked = BinaryArchive.check(bin, c.pkg(), c.version(), "4.6.1", "x86_64-pc-linux-gnu",
            BinaryArchive.Limits.DEFAULTS);
        return new BinaryUploader(q, new BinaryGate(new ForgePlatforms(quick())), quick(), BinaryArchive.Limits.DEFAULTS)
            .upload(c, bin, checked, List.of(), base(), "reg:5000/skald/base/r@sha256:" + "e".repeat(64),
                Optional.of(IMAGE_ID));
    }

    @Test
    @org.junit.jupiter.api.Timeout(value = 30, threadMode = org.junit.jupiter.api.Timeout.ThreadMode.SEPARATE_THREAD)
    public void aPutWhoseAnswerStallsIsAFailedAttemptAtTheDeadline() throws Exception {
        BinaryBuildQueue q = queue();
        BuildClaim c = running(q);
        stalled.add("PUT " + TREE + "aaa_1.0.tar.gz");
        long start = System.nanoTime();
        BinaryUploader.Result r = quickUpload(q, c, binary("aaa", "1.0", null));
        assertTrue((System.nanoTime() - start) / 1_000_000 < 15_000, "returned after the deadline, not the stall");
        assertEquals(BinaryUploader.Uploaded.FAILED, r.outcome(), r.detail());
        assertEquals("QUEUED", row(c.rowId()).get("state"));
    }

    @Test
    @org.junit.jupiter.api.Timeout(value = 30, threadMode = org.junit.jupiter.api.Timeout.ThreadMode.SEPARATE_THREAD)
    public void aStalledExistingBinaryAfterA409IsAFailedAttemptAtTheDeadline() throws Exception {
        BinaryBuildQueue q = queue();
        BuildClaim c = running(q);
        stored.put(TREE + "aaa_1.0.tar.gz", binary("aaa", "1.0", "other"));
        stalled.add("GET " + TREE + "aaa_1.0.tar.gz");
        long start = System.nanoTime();
        BinaryUploader.Result r = quickUpload(q, c, binary("aaa", "1.0", null));
        assertTrue((System.nanoTime() - start) / 1_000_000 < 15_000);
        assertEquals(BinaryUploader.Uploaded.FAILED, r.outcome(), r.detail());
        assertEquals("UPLOAD_409", row(c.rowId()).get("error_code"));
    }

    @Test
    @org.junit.jupiter.api.Timeout(value = 30, threadMode = org.junit.jupiter.api.Timeout.ThreadMode.SEPARATE_THREAD)
    public void aStalledRegistrationIsUnreadableAtTheDeadline() {
        stalled.add("GET /api/v1/cran/platforms/" + KEY);
        long start = System.nanoTime();
        BinaryGate.Decision d = new BinaryGate(new ForgePlatforms(quick())).decide(base(), Optional.of(IMAGE_ID));
        assertTrue((System.nanoTime() - start) / 1_000_000 < 15_000);
        assertFalse(d.binaries(), d.reason());
        assertTrue(d.reason().contains("could not be read"), d.reason());
    }

    @Test
    public void linkingToNamesWhatWasHandedInOrTheBaseHasOnce() throws Exception {
        assertEquals("aa 1.0, bb 2.0", BinaryUploader.linkingTo("aa (>= 0.9), bb, aa, cc",
            List.of(new BuiltDependency("aa", "1.0", "x")), Map.of("bb", "2.0")));
        assertEquals("", BinaryUploader.linkingTo(null, List.of(), Map.of()));
        assertThrows(RDependencies.Unreadable.class, () -> BinaryUploader.linkingTo("not a list", List.of(), Map.of()));
    }

    // ------------------------------------------------------------------ the hand-in

    private static ForgeHandIn handIn(BinaryBuildQueue q) {
        return new ForgeHandIn(q, new HttpSourceFetcher(Duration.ofSeconds(5)), new SourceSettings(
            forge.resolve("repository/cran-proxy/"), 1 << 20, 1 << 20, 1 << 24, Duration.ofSeconds(5),
            Duration.ofSeconds(30), Duration.ofMinutes(5)), forgeSettings(), BinaryArchive.Limits.DEFAULTS);
    }

    private static BuildClaim claim(BinaryBuildQueue.SourceKind kind, UUID requestedBy) {
        UUID row = UUID.randomUUID();
        return new BuildClaim(row, KEY, "aaa", "1.0", "1".repeat(64), "2".repeat(32), List.of(),
            new BinaryLease(row, 1, "b"), requestedBy, kind);
    }

    @Test
    public void theSourceComesFromTheProxyMemberAndAnArchivedOneFromItsArchive() throws Exception {
        stored.put("/repository/cran-proxy/src/contrib/aaa_1.0.tar.gz", "current".getBytes(StandardCharsets.UTF_8));
        stored.put("/repository/cran-proxy/src/contrib/Archive/aaa/aaa_1.0.tar.gz", "old".getBytes(StandardCharsets.UTF_8));
        try (ForgeHandIn h = handIn(queue())) {
            assertEquals("current", new String(h.source(claim(BinaryBuildQueue.SourceKind.INDEX, null)),
                StandardCharsets.UTF_8));
            assertEquals("old", new String(h.source(claim(BinaryBuildQueue.SourceKind.ARCHIVE, null)),
                StandardCharsets.UTF_8));
        }
    }

    @Test
    public void aSourceArchivedSinceItsFirstFetchIsStillHandedIn() throws Exception {
        // 0b37c7c-F1: kind 'index' (the first fetch's, kept), the version archived since.
        try (ForgeHandIn h = handIn(queue())) {
            stored.put("/repository/cran-proxy/src/contrib/Archive/aaa/aaa_1.0.tar.gz", "old".getBytes(StandardCharsets.UTF_8));
            assertEquals("old", new String(h.source(claim(BinaryBuildQueue.SourceKind.INDEX, null)),
                StandardCharsets.UTF_8));
            stored.clear();
            stored.put("/repository/cran-proxy/src/contrib/aaa_1.0.tar.gz", "current".getBytes(StandardCharsets.UTF_8));
            assertEquals("current", new String(h.source(claim(BinaryBuildQueue.SourceKind.ARCHIVE, null)),
                StandardCharsets.UTF_8), "and the other way, should an archived version be current again");
            stored.clear();
            assertThrows(IOException.class, () -> h.source(claim(BinaryBuildQueue.SourceKind.INDEX, null)),
                "on neither path: a failed fetch");
        }
    }

    @Test
    public void aDependencyComesFromSkaldsBinaryTreeWithTheToken() throws Exception {
        byte[] dep = binary("bbb", "2.0", null);
        stored.put(TREE + "bbb_2.0.tar.gz", dep);
        try (ForgeHandIn h = handIn(queue())) {
            assertArrayEquals(dep, h.dependency(claim(BinaryBuildQueue.SourceKind.INDEX, null),
                new BuiltDependency("bbb", "2.0", "x")));
            IOException missing = assertThrows(IOException.class, () -> h.dependency(
                claim(BinaryBuildQueue.SourceKind.INDEX, null), new BuiltDependency("ccc", "1.0", "x")));
            assertTrue(missing.getMessage().contains("404"), missing.getMessage());
        }
    }

    @Test
    public void aDependencyOverTheLimitIsAFailedFetch() throws Exception {
        byte[] dep = binary("bbb", "2.0", null);
        stored.put(TREE + "bbb_2.0.tar.gz", dep);
        try (ForgeHandIn h = new ForgeHandIn(queue(), new HttpSourceFetcher(Duration.ofSeconds(5)), new SourceSettings(
                forge.resolve("repository/cran-proxy/"), 1 << 20, 1 << 20, 1 << 24, Duration.ofSeconds(5),
                Duration.ofSeconds(30), Duration.ofMinutes(5)), forgeSettings(),
                new BinaryArchive.Limits(dep.length - 1, 1L << 30, 1000))) {
            IOException e = assertThrows(IOException.class, () -> h.dependency(claim(BinaryBuildQueue.SourceKind.INDEX,
                null), new BuiltDependency("bbb", "2.0", "x")));
            assertTrue(e.getMessage().contains("over"), e.getMessage());
        }
    }

    @Test
    public void aMissingSourceIsAFailedFetchNotARefusal() {
        try (ForgeHandIn h = handIn(queue())) {
            // An IOException (a failed attempt, retried), never an IllegalArgumentException (a refusal).
            assertThrows(IOException.class, () -> h.source(claim(BinaryBuildQueue.SourceKind.INDEX, null)));
        }
    }

    @Test
    public void theLockIsTheRequestingBuilds() {
        BinaryBuildQueue q = queue();
        UUID build = UUID.randomUUID();
        q.recordLock(build, Map.of("rlang", "1.1.4"));
        try (ForgeHandIn h = handIn(q)) {
            assertEquals(Map.of("rlang", "1.1.4"), h.lock(claim(BinaryBuildQueue.SourceKind.INDEX, build)));
            assertEquals(Map.of(), h.lock(claim(BinaryBuildQueue.SourceKind.INDEX, null)));
        }
    }

    @Test
    public void aClaimCarriesItsRequesterAndSourceKind() {
        BinaryBuildQueue q = queue();
        UUID build = UUID.randomUUID();
        q.recordLock(build, Map.of());
        q.enqueue(KEY, "aaa", "1.0", build);
        q.recordSource(q.claimSource("v").orElseThrow().lease(), "1".repeat(64), "2".repeat(32),
            BinaryBuildQueue.SourceKind.ARCHIVE, List.of());
        BuildClaim c = q.claimBuild("b").orElseThrow();
        assertEquals(build, c.requestedBy());
        assertEquals(BinaryBuildQueue.SourceKind.ARCHIVE, c.sourceKind());
    }
}
