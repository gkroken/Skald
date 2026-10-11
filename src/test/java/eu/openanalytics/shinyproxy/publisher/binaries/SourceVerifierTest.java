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
import eu.openanalytics.shinyproxy.publisher.build.CoordinatorSettings;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The source check against real PostgreSQL and a CRAN-like repository served over HTTP (T7b
 * step 2b). Each test sets what the repository serves; the index is built from what it holds
 * unless a test overrides it.
 */
public class SourceVerifierTest {

    @SuppressWarnings("resource")
    private static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>("postgres:16").withDatabaseName("skald");

    private static final String KEY = "noble-x86_64/4.6";
    private static JdbcTemplate jdbc;
    private static HttpServer server;
    private static URI repository;
    private static final Map<String, byte[]> served = new ConcurrentHashMap<>();
    private static final Map<String, Integer> statusOf = new ConcurrentHashMap<>();
    private static final AtomicInteger indexFetches = new AtomicInteger();
    private static final int STALL = -1;
    private static final java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);

    @BeforeAll
    public static void beforeAll() throws IOException {
        POSTGRES.start();
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .schemas("skald").defaultSchema("skald").locations("classpath:db/migration")
            .load().migrate();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/repository/cran-proxy/", exchange -> {
            String file = exchange.getRequestURI().getPath().substring("/repository/cran-proxy/".length());
            if (file.equals("src/contrib/PACKAGES")) {
                indexFetches.incrementAndGet();
            }
            byte[] body = served.get(file);
            int status = statusOf.getOrDefault(file, body == null ? 404 : 200);
            if (status == STALL) {
                // Headers and a few bytes, then nothing: a stall after the response began.
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write(body, 0, Math.min(20, body.length));
                exchange.getResponseBody().flush();
                try {
                    release.await();
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                exchange.close();
                return;
            }
            if (status != 200) {
                exchange.sendResponseHeaders(status, -1);
            } else {
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
        repository = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/repository/cran-proxy/");
    }

    @AfterAll
    public static void afterAll() {
        release.countDown();
        server.stop(0);
        POSTGRES.stop();
    }

    @BeforeEach
    public void clean() {
        jdbc.update("TRUNCATE skald.binary_build_upload, skald.binary_build_dep, skald.binary_build, skald.binary_lock");
        served.clear();
        indexRecords.clear();
        statusOf.clear();
        indexFetches.set(0);
    }

    private static BinaryBuildQueue queue() {
        return new BinaryBuildQueue(jdbc, BinaryQueueSettings.DEFAULTS, CoordinatorSettings.DEFAULTS);
    }

    private static SourceVerifier verifier(BinaryBuildQueue q) {
        return verifier(q, new SourceSettings(repository, 1 << 20, 1 << 20, 1 << 24, Duration.ofSeconds(5),
            Duration.ofMinutes(1), Duration.ofMinutes(5)), BinaryQueueSettings.DEFAULTS);
    }

    private static SourceSettings deadline(Duration deadline) {
        return new SourceSettings(repository, 1 << 20, 1 << 20, 1 << 24, Duration.ofSeconds(5), deadline,
            Duration.ofMinutes(5));
    }

    private static SourceVerifier verifier(BinaryBuildQueue q, SourceSettings s, BinaryQueueSettings qs) {
        return new SourceVerifier(q, new HttpSourceFetcher(Duration.ofSeconds(5)), s, qs);
    }

    /** Serves {@code pkg}'s tarball and lists it in the index with its true MD5. */
    private static byte[] publish(String pkg, String version, String extra) {
        byte[] tgz = TarBuilder.source(pkg, TarBuilder.description(pkg, version, extra));
        served.put("src/contrib/" + pkg + "_" + version + ".tar.gz", tgz);
        index(pkg, version, TarBuilder.digest("MD5", tgz));
        return tgz;
    }

    private static final Map<String, String> indexRecords = new ConcurrentHashMap<>();

    private static void index(String pkg, String version, String md5) {
        indexRecords.put(pkg, "Package: " + pkg + "\nVersion: " + version + "\nMD5sum: " + md5 + "\n");
        rebuildIndex();
    }

    private static void rebuildIndex() {
        served.put("src/contrib/PACKAGES", String.join("\n", indexRecords.values()).getBytes(StandardCharsets.UTF_8));
    }

    /** Enqueues {@code pkg} for a build whose lock is {@code lock}. */
    private static UUID enqueue(BinaryBuildQueue q, String pkg, String version, Map<String, String> lock) {
        UUID build = UUID.randomUUID();
        q.recordLock(build, lock);
        return q.enqueue(KEY, pkg, version, build);
    }

    private static Map<String, Object> row(UUID id) {
        return jdbc.queryForMap("SELECT * FROM skald.binary_build WHERE id = ?", id);
    }

    private static String outcome(BinaryBuildQueue q) {
        return verifier(q).verifyNext("v").orElseThrow().outcome();
    }

    private static void refusedWith(String code, BinaryBuildQueue q, UUID id) {
        assertEquals("REFUSED:" + code, outcome(q));
        Map<String, Object> r = row(id);
        assertEquals("REFUSED", r.get("state"));
        assertEquals(code, r.get("refusal_code"));
        assertNull(r.get("source_sha256"), "a refused source records no digest");
    }

    // ------------------------------------------------------------------ the good path

    @Test
    public void aPublicSourceIsRecordedWithItsDependenciesAtTheirLockedVersions() {
        BinaryBuildQueue q = queue();
        byte[] tgz = publish("shiny", "1.9.1", "Depends: R (>= 3.0.2), methods\nImports: utils, httpuv (>= 1.5.2),"
            + "\n    later (>= 1.0.0), Rcpp\nLinkingTo: Rcpp\n");
        UUID id = enqueue(q, "shiny", "1.9.1", Map.of("shiny", "1.9.1", "httpuv", "1.6.15", "later", "1.3.2",
            "Rcpp", "1.0.13"));
        assertEquals("QUEUED", outcome(q));
        Map<String, Object> r = row(id);
        assertEquals("QUEUED", r.get("state"));
        assertEquals(TarBuilder.digest("SHA-256", tgz), r.get("source_sha256"));
        assertEquals(TarBuilder.digest("MD5", tgz), r.get("source_md5"));
        Set<String> deps = new HashSet<>(jdbc.queryForList("SELECT x.package || ' ' || x.version FROM"
            + " skald.binary_build_dep d JOIN skald.binary_build x ON x.id = d.dependency_id WHERE d.binary_build_id = ?",
            String.class, id));
        assertEquals(Set.of("httpuv 1.6.15", "later 1.3.2", "Rcpp 1.0.13"), deps,
            "R and base packages excluded; Rcpp once though named twice");
        assertEquals(1, jdbc.queryForObject("SELECT count(DISTINCT requested_by_build) FROM skald.binary_build",
            Integer.class), "dependencies resolve against the same lock");
    }

    @Test
    public void theIndexIsFetchedOncePerTtl() {
        BinaryBuildQueue q = queue();
        publish("aaa", "1.0", "");
        publish("bbb", "1.0", "");
        enqueue(q, "aaa", "1.0", Map.of());
        enqueue(q, "bbb", "1.0", Map.of());
        SourceVerifier v = verifier(q);
        assertEquals("QUEUED", v.verifyNext("v").orElseThrow().outcome());
        assertEquals("QUEUED", v.verifyNext("v").orElseThrow().outcome());
        assertEquals(1, indexFetches.get());
        assertTrue(v.verifyNext("v").isEmpty(), "nothing left");
    }

    // ------------------------------------------------------------------ refusals

    @Test
    public void noLockIsRefused() {
        BinaryBuildQueue q = queue();
        publish("aaa", "1.0", "");
        UUID id = q.enqueue(KEY, "aaa", "1.0", null);
        refusedWith("NO_LOCK", q, id);
    }

    @Test
    public void aPackageTheIndexDoesNotListYetIsDeferredWithEitherSwitch() {
        // 7980b8e-F1: a package's first release, locked within forge's cached index's TTL: the
        // tarball is served at src/contrib, the index does not list it. Neither a refusal (off)
        // nor an Archive miss (on) may end it; the row waits for the index.
        for (boolean archivedOn : new boolean[] {false, true}) {
            clean();
            BinaryBuildQueue q = queue();
            publish("other", "1.0", "");
            byte[] tgz = TarBuilder.source("aaa", TarBuilder.description("aaa", "1.0", ""));
            served.put("src/contrib/aaa_1.0.tar.gz", tgz);
            UUID id = enqueue(q, "aaa", "1.0", Map.of());
            SourceVerifier v = verifier(q, deadline(Duration.ofMinutes(1)).withArchived(archivedOn),
                BinaryQueueSettings.DEFAULTS);
            assertEquals("DEFERRED", v.verifyNext("v").orElseThrow().outcome(), "archived=" + archivedOn);
            Map<String, Object> r = row(id);
            assertEquals("PENDING_SOURCE", r.get("state"));
            assertEquals(1, r.get("deferrals"));
            assertEquals(0, r.get("attempts"));
            index("aaa", "1.0", TarBuilder.digest("MD5", tgz));
            jdbc.update("UPDATE skald.binary_build SET not_before = now() - interval '1 second' WHERE id = ?", id);
            assertEquals("QUEUED", v.verifyNext("v").orElseThrow().outcome(), "once listed, archived=" + archivedOn);
            assertEquals("index", jdbc.queryForObject("SELECT source_kind FROM skald.binary_build WHERE id = ?",
                String.class, id), "held to the index's MD5, never taken without one");
        }
    }

    @Test
    public void aPackageStillUnlistedAtTheBoundIsRefused() {
        BinaryBuildQueue q = queue();
        publish("other", "1.0", "");
        UUID id = enqueue(q, "aaa", "1.0", Map.of());
        assertEquals("DEFERRED", outcome(q));
        jdbc.update("UPDATE skald.binary_build SET not_before = NULL, deferred_since = now() - interval '49 hours'"
            + " WHERE id = ?", id);
        assertEquals("REFUSED:INDEX_BEHIND", outcome(q));
    }

    @Test
    public void aVersionTheIndexDoesNotListIsRefused() {
        BinaryBuildQueue q = queue();
        publish("aaa", "1.1", "");
        served.put("src/contrib/aaa_1.0.tar.gz", TarBuilder.source("aaa", TarBuilder.description("aaa", "1.0", "")));
        UUID id = enqueue(q, "aaa", "1.0", Map.of());
        refusedWith("NOT_IN_PUBLIC_INDEX", q, id);
        assertTrue(String.valueOf(row(id).get("refusal_detail")).contains("archived"), "the detail says why");
    }

    // ------------------------------------------------------------------ archived versions (user, 2026-10-11)

    private static String kind(UUID id) {
        return jdbc.queryForObject("SELECT source_kind FROM skald.binary_build WHERE id = ?", String.class, id);
    }

    private static SourceVerifier archived(BinaryBuildQueue q) {
        return verifier(q, deadline(Duration.ofMinutes(1)).withArchived(true), BinaryQueueSettings.DEFAULTS);
    }

    /** Serves {@code pkg} {@code version} under the Archive path only. */
    private static byte[] archive(String pkg, String version) {
        byte[] tgz = TarBuilder.source(pkg, TarBuilder.description(pkg, version, ""));
        served.put("src/contrib/Archive/" + pkg + "/" + pkg + "_" + version + ".tar.gz", tgz);
        return tgz;
    }

    @Test
    public void anArchivedVersionIsFetchedFromTheArchiveAndTrustedOnItsFirstFetchWhenOn() {
        BinaryBuildQueue q = queue();
        publish("aaa", "1.1", "");
        byte[] old = archive("aaa", "1.0");
        UUID id = enqueue(q, "aaa", "1.0", Map.of());
        assertEquals("QUEUED", archived(q).verifyNext("v").orElseThrow().outcome());
        Map<String, Object> r = row(id);
        assertEquals(TarBuilder.digest("SHA-256", old), r.get("source_sha256"));
        assertEquals(TarBuilder.digest("MD5", old), r.get("source_md5"), "computed: no published one to match");
        assertEquals("archive", kind(id));
    }

    @Test
    public void anUnlistedPackageTheArchiveLacksIsDeferredNotRefused() {
        BinaryBuildQueue q = queue();
        publish("other", "1.0", "");
        UUID id = enqueue(q, "aaa", "1.0", Map.of());
        assertEquals("DEFERRED", archived(q).verifyNext("v").orElseThrow().outcome());
        assertTrue(String.valueOf(row(id).get("error_detail")).contains("Archive"), String.valueOf(row(id).get("error_detail")));
    }

    @Test
    public void aPackageGoneFromTheIndexIsAnArchivedVersion() {
        BinaryBuildQueue q = queue();
        publish("other", "1.0", "");
        archive("aaa", "1.0");
        UUID id = enqueue(q, "aaa", "1.0", Map.of());
        assertEquals("QUEUED", archived(q).verifyNext("v").orElseThrow().outcome());
        assertEquals("archive", kind(id));
    }

    @Test
    public void aCurrentVersionIsStillCheckedAgainstTheIndexWhenArchivedIsOn() {
        BinaryBuildQueue q = queue();
        byte[] tgz = publish("aaa", "1.0", "");
        UUID id = enqueue(q, "aaa", "1.0", Map.of());
        assertEquals("QUEUED", archived(q).verifyNext("v").orElseThrow().outcome());
        assertEquals("index", kind(id));
        assertEquals(TarBuilder.digest("MD5", tgz), row(id).get("source_md5"));
        publish("bbb", "1.0", "");
        index("bbb", "1.0", "0".repeat(32));
        UUID bad = enqueue(q, "bbb", "1.0", Map.of());
        assertEquals("REFUSED:MD5_MISMATCH", archived(q).verifyNext("v").orElseThrow().outcome());
        assertEquals("REFUSED", row(bad).get("state"));
    }

    @Test
    public void anArchivedVersionTheProxyCannotServeIsRefused() {
        BinaryBuildQueue q = queue();
        publish("aaa", "1.1", "");
        UUID id = enqueue(q, "aaa", "1.0", Map.of());
        assertEquals("REFUSED:NOT_IN_PUBLIC_SOURCE", archived(q).verifyNext("v").orElseThrow().outcome());
        assertEquals("REFUSED", row(id).get("state"));
    }

    @Test
    public void anArchivedTarballThatChangedSinceItsFirstFetchIsRefused() {
        BinaryBuildQueue q = queue();
        publish("aaa", "1.1", "");
        archive("aaa", "1.0");
        UUID id = enqueue(q, "aaa", "1.0", Map.of());
        jdbc.update("UPDATE skald.binary_build SET source_sha256 = ?, source_md5 = ?, source_kind = 'archive'"
            + " WHERE id = ?", "e".repeat(64), "e".repeat(32), id);
        assertEquals("REFUSED:SOURCE_CHANGED", archived(q).verifyNext("v").orElseThrow().outcome());
    }

    @Test
    public void anIndexBehindTheLockIsStillDeferredWhenArchivedIsOn() {
        BinaryBuildQueue q = queue();
        publish("aaa", "0.9", "");
        archive("aaa", "1.0");
        UUID id = enqueue(q, "aaa", "1.0", Map.of());
        assertEquals("DEFERRED", archived(q).verifyNext("v").orElseThrow().outcome(),
            "a release newer than the index is not an archived one");
        assertEquals("PENDING_SOURCE", row(id).get("state"));
    }

    @Test
    public void aReOpenedRowKeepsTheKindOfItsFirstFetch() {
        BinaryBuildQueue q = queue();
        publish("aaa", "1.1", "");
        byte[] old = archive("aaa", "1.0");
        UUID id = enqueue(q, "aaa", "1.0", Map.of());
        jdbc.update("UPDATE skald.binary_build SET source_sha256 = ?, source_md5 = ?, source_kind = 'index'"
            + " WHERE id = ?", TarBuilder.digest("SHA-256", old), TarBuilder.digest("MD5", old), id);
        assertEquals("QUEUED", archived(q).verifyNext("v").orElseThrow().outcome());
        assertEquals("index", kind(id), "verified against the index when current; archived since");
        assertEquals("index", jdbc.queryForObject("SELECT detail_json ->> 'source_kind' FROM skald.audit_event"
            + " WHERE action = 'binary.queued' AND subject_id = ?", String.class, id.toString()),
            "the audit records the kind the row keeps (7980b8e N2)");
    }

    @Test
    public void aPackageListedTwiceIsRefused() {
        BinaryBuildQueue q = queue();
        byte[] tgz = publish("aaa", "1.0", "");
        served.put("src/contrib/PACKAGES", ("Package: aaa\nVersion: 1.0\nMD5sum: " + TarBuilder.digest("MD5", tgz)
            + "\n\nPackage: aaa\nVersion: 1.0\nMD5sum: " + TarBuilder.digest("MD5", tgz) + "\n").getBytes(StandardCharsets.UTF_8));
        UUID id = enqueue(q, "aaa", "1.0", Map.of());
        refusedWith("NOT_IN_PUBLIC_INDEX", q, id);
    }

    @Test
    public void aMissingTarballIsRefused() {
        BinaryBuildQueue q = queue();
        publish("aaa", "1.0", "");
        served.remove("src/contrib/aaa_1.0.tar.gz");
        UUID id = enqueue(q, "aaa", "1.0", Map.of());
        refusedWith("NOT_IN_PUBLIC_SOURCE", q, id);
    }

    @Test
    public void aTarballWhoseMd5IsNotTheIndexsIsRefused() {
        BinaryBuildQueue q = queue();
        publish("aaa", "1.0", "");
        index("aaa", "1.0", "0".repeat(32));
        UUID id = enqueue(q, "aaa", "1.0", Map.of());
        refusedWith("MD5_MISMATCH", q, id);
    }

    @Test
    public void aTarballThatChangedSinceItsFirstFetchIsRefused() {
        BinaryBuildQueue q = queue();
        publish("aaa", "1.0", "");
        UUID id = enqueue(q, "aaa", "1.0", Map.of());
        // As a re-opened row holds them: digests recorded at a first fetch of other bytes.
        jdbc.update("UPDATE skald.binary_build SET source_sha256 = ?, source_md5 = ? WHERE id = ?",
            "e".repeat(64), "e".repeat(32), id);
        assertEquals("REFUSED:SOURCE_CHANGED", outcome(q));
        assertEquals("e".repeat(64), row(id).get("source_sha256"), "the first fetch's digests stand");
    }

    @Test
    public void theSameTarballOnARefetchIsQueued() {
        BinaryBuildQueue q = queue();
        byte[] tgz = publish("aaa", "1.0", "");
        UUID id = enqueue(q, "aaa", "1.0", Map.of());
        jdbc.update("UPDATE skald.binary_build SET source_sha256 = ?, source_md5 = ? WHERE id = ?",
            TarBuilder.digest("SHA-256", tgz), TarBuilder.digest("MD5", tgz), id);
        assertEquals("QUEUED", outcome(q));
    }

    @Test
    public void aDescriptionForAnotherPackageOrVersionIsRefused() {
        BinaryBuildQueue q = queue();
        byte[] other = TarBuilder.source("aaa", TarBuilder.description("aaa", "2.0", ""));
        served.put("src/contrib/aaa_1.0.tar.gz", other);
        index("aaa", "1.0", TarBuilder.digest("MD5", other));
        UUID id = enqueue(q, "aaa", "1.0", Map.of());
        refusedWith("DESCRIPTION_MISMATCH", q, id);

        byte[] renamed = new TarBuilder().file("bbb/DESCRIPTION", TarBuilder.description("ccc", "1.0", "")).tgz();
        served.put("src/contrib/bbb_1.0.tar.gz", renamed);
        index("bbb", "1.0", TarBuilder.digest("MD5", renamed));
        UUID b = enqueue(q, "bbb", "1.0", Map.of());
        refusedWith("DESCRIPTION_MISMATCH", q, b);
    }

    @Test
    public void anUnreadableDescriptionIsRefused() {
        BinaryBuildQueue q = queue();
        publish("aaa", "1.0", "Imports: not a package list\n");
        UUID id = enqueue(q, "aaa", "1.0", Map.of());
        refusedWith("DESCRIPTION_UNREADABLE", q, id);
    }

    @Test
    public void aDependencyTheLockDoesNotFixIsRefused() {
        BinaryBuildQueue q = queue();
        publish("aaa", "1.0", "Imports: bbb\n");
        UUID id = enqueue(q, "aaa", "1.0", Map.of("ccc", "1.0"));
        refusedWith("NOT_IN_LOCK", q, id);
    }

    @Test
    public void aLockedVersionOutsideAConstraintIsRefused() {
        BinaryBuildQueue q = queue();
        publish("aaa", "1.0", "Imports: bbb (>= 2.0)\nLinkingTo: bbb\n");
        UUID id = enqueue(q, "aaa", "1.0", Map.of("bbb", "1.9"));
        refusedWith("CONSTRAINT_UNMET", q, id);
    }

    @Test
    public void aDependencyTheBaseCarriesAtTheLockedVersionIsNotQueued() {
        // e149183 N4: R's recommended packages ship in the base at R's own version.
        BinaryBuildQueue q = queue();
        publish("aaa", "1.0", "Imports: Matrix, MASS, bbb\n");
        UUID id = enqueue(q, "aaa", "1.0", Map.of("Matrix", "1.7-5", "MASS", "7.3-66", "bbb", "1.0"));
        SourceVerifier v = new SourceVerifier(q, new HttpSourceFetcher(Duration.ofSeconds(5)), deadline(
            Duration.ofMinutes(1)), BinaryQueueSettings.DEFAULTS,
            key -> key.equals(KEY) ? Map.of("Matrix", "1.7-5", "MASS", "7.3-65") : Map.of());
        assertEquals("QUEUED", v.verifyNext("v").orElseThrow().outcome());
        Set<String> deps = new HashSet<>(jdbc.queryForList("SELECT x.package || ' ' || x.version FROM"
            + " skald.binary_build_dep d JOIN skald.binary_build x ON x.id = d.dependency_id WHERE d.binary_build_id = ?",
            String.class, id));
        assertEquals(Set.of("MASS 7.3-66", "bbb 1.0"), deps,
            "Matrix is the base's at the locked version; MASS is locked at another, so queued");
    }

    @Test
    public void everyConstraintOnAPackageIsChecked() {
        BinaryBuildQueue q = queue();
        publish("aaa", "1.0", "Imports: bbb\nLinkingTo: bbb (< 1.5)\n");
        UUID id = enqueue(q, "aaa", "1.0", Map.of("bbb", "1.9"));
        refusedWith("CONSTRAINT_UNMET", q, id);
    }

    @Test
    public void anOversizedTarballIsRefused() {
        BinaryBuildQueue q = queue();
        byte[] tgz = publish("aaa", "1.0", "");
        UUID id = enqueue(q, "aaa", "1.0", Map.of());
        assertEquals("REFUSED:TOO_LARGE", verifier(q, new SourceSettings(repository, 1 << 20, tgz.length - 1, 1 << 24,
            Duration.ofSeconds(5), Duration.ofMinutes(1), Duration.ofMinutes(5)), BinaryQueueSettings.DEFAULTS)
            .verifyNext("v").orElseThrow().outcome());
        assertEquals("TOO_LARGE", row(id).get("refusal_code"));
    }

    // ------------------------------------------------------------------ failures

    @Test
    public void aRepositoryErrorIsAFailedAttemptNotARefusal() {
        BinaryBuildQueue q = queue();
        publish("aaa", "1.0", "");
        statusOf.put("src/contrib/aaa_1.0.tar.gz", 503);
        UUID id = enqueue(q, "aaa", "1.0", Map.of());
        assertEquals("FAILED", outcome(q));
        Map<String, Object> r = row(id);
        assertEquals("PENDING_SOURCE", r.get("state"), "retried");
        assertEquals("SOURCE_FETCH", r.get("error_code"));
        assertTrue(String.valueOf(r.get("error_detail")).contains("503"));
        statusOf.clear();
        assertEquals("QUEUED", outcome(q), "the next attempt succeeds");
    }

    @Test
    public void anIndexErrorIsAFailedAttempt() {
        BinaryBuildQueue q = queue();
        publish("aaa", "1.0", "");
        statusOf.put("src/contrib/PACKAGES", 500);
        UUID id = enqueue(q, "aaa", "1.0", Map.of());
        assertEquals("FAILED", outcome(q));
        assertEquals("PENDING_SOURCE", row(id).get("state"));
    }

    @Test
    public void aRepositoryWithNoIndexIsRefused() {
        BinaryBuildQueue q = queue();
        UUID id = enqueue(q, "aaa", "1.0", Map.of());
        refusedWith("NO_PUBLIC_INDEX", q, id);
    }

    @Test
    public void aLeaseLostDuringTheTransferRecordsNothing() {
        BinaryBuildQueue q = queue();
        publish("aaa", "1.0", "");
        UUID id = enqueue(q, "aaa", "1.0", Map.of());
        BinaryQueueSettings everyRead = new BinaryQueueSettings(Duration.ofSeconds(60), Duration.ofNanos(1),
            Duration.ofMinutes(60), 1, 2);
        SourceFetcher expiring = uri -> {
            InputStream body = new HttpSourceFetcher(Duration.ofSeconds(5)).open(uri);
            if (uri.getPath().endsWith(".tar.gz")) {
                jdbc.update("UPDATE skald.binary_build SET lease_expires_at = now() - interval '1 second' WHERE id = ?", id);
            }
            return body;
        };
        SourceVerifier v = new SourceVerifier(q, expiring, new SourceSettings(repository, 1 << 20, 1 << 20, 1 << 24,
            Duration.ofSeconds(5), Duration.ofMinutes(1), Duration.ofMinutes(5)), everyRead);
        assertEquals("FENCED", v.verifyNext("v").orElseThrow().outcome());
        Map<String, Object> r = row(id);
        assertEquals("VERIFYING", r.get("state"), "left to the reaper");
        assertNull(r.get("source_sha256"));
    }

    @Test
    @Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void aTarballThatStallsAfterItsHeadersIsStoppedAtTheDeadline() {
        BinaryBuildQueue q = queue();
        publish("aaa", "1.0", "");
        statusOf.put("src/contrib/aaa_1.0.tar.gz", STALL);
        UUID id = enqueue(q, "aaa", "1.0", Map.of());
        long start = System.nanoTime();
        assertEquals("FAILED", verifier(q, deadline(Duration.ofSeconds(2)), BinaryQueueSettings.DEFAULTS)
            .verifyNext("v").orElseThrow().outcome());
        long ms = (System.nanoTime() - start) / 1_000_000;
        assertTrue(ms < 10_000, "returned " + ms + " ms after a 2 s deadline");
        Map<String, Object> r = row(id);
        assertEquals("PENDING_SOURCE", r.get("state"));
        assertTrue(String.valueOf(r.get("error_detail")).contains("deadline"), String.valueOf(r.get("error_detail")));
        assertTrue(!Thread.currentThread().isInterrupted());
    }

    @Test
    @Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void anIndexThatStallsAfterItsHeadersIsStoppedAtTheDeadline() {
        BinaryBuildQueue q = queue();
        publish("aaa", "1.0", "");
        statusOf.put("src/contrib/PACKAGES", STALL);
        UUID id = enqueue(q, "aaa", "1.0", Map.of());
        long start = System.nanoTime();
        assertEquals("FAILED", verifier(q, deadline(Duration.ofSeconds(2)), BinaryQueueSettings.DEFAULTS)
            .verifyNext("v").orElseThrow().outcome());
        assertTrue((System.nanoTime() - start) / 1_000_000 < 10_000);
        assertEquals("PENDING_SOURCE", row(id).get("state"));
    }

    @Test
    @Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void theLeaseIsRenewedDuringAStallAndALostLeaseStopsIt() {
        BinaryBuildQueue q = queue();
        publish("aaa", "1.0", "");
        statusOf.put("src/contrib/aaa_1.0.tar.gz", STALL);
        UUID id = enqueue(q, "aaa", "1.0", Map.of());
        BinaryQueueSettings fast = new BinaryQueueSettings(Duration.ofSeconds(60), Duration.ofMillis(200),
            Duration.ofMinutes(60), 1, 2);
        java.util.concurrent.ScheduledExecutorService side = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
        java.util.List<java.sql.Timestamp> seen = new java.util.concurrent.CopyOnWriteArrayList<>();
        try {
            side.scheduleAtFixedRate(() -> seen.add(jdbc.queryForObject("SELECT lease_expires_at FROM"
                + " skald.binary_build WHERE id = ?", java.sql.Timestamp.class, id)), 300, 300,
                java.util.concurrent.TimeUnit.MILLISECONDS);
            side.schedule(() -> jdbc.update("UPDATE skald.binary_build SET lease_owner = 'someone-else' WHERE id = ?",
                id), 2000, java.util.concurrent.TimeUnit.MILLISECONDS);
            long start = System.nanoTime();
            assertEquals("FENCED", verifier(q, deadline(Duration.ofMinutes(5)), fast).verifyNext("v").orElseThrow()
                .outcome());
            assertTrue((System.nanoTime() - start) / 1_000_000 < 10_000, "a lost lease stops the stalled read");
        } finally {
            side.shutdownNow();
        }
        assertTrue(new HashSet<>(seen.stream().filter(java.util.Objects::nonNull).toList()).size() >= 3,
            "the lease was renewed while the read was blocked: " + seen);
        assertNull(row(id).get("source_sha256"));
    }

    /** A fetcher whose {@code stalled} file blocks until closed, ignoring interrupts. */
    private static SourceFetcher stallingOn(String stalled) {
        HttpSourceFetcher http = new HttpSourceFetcher(Duration.ofSeconds(5));
        return uri -> uri.getPath().endsWith(stalled) ? new Uninterruptible() : http.open(uri);
    }

    @Test
    @Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void aTarballReadThatIgnoresInterruptsIsClosedAtTheDeadline() {
        BinaryBuildQueue q = queue();
        publish("aaa", "1.0", "");
        UUID id = enqueue(q, "aaa", "1.0", Map.of());
        SourceVerifier v = new SourceVerifier(q, stallingOn("aaa_1.0.tar.gz"), deadline(Duration.ofSeconds(2)),
            BinaryQueueSettings.DEFAULTS);
        assertEquals("FAILED", v.verifyNext("v").orElseThrow().outcome());
        assertEquals("PENDING_SOURCE", row(id).get("state"));
    }

    @Test
    @Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void anIndexReadThatIgnoresInterruptsIsClosedAtTheDeadline() {
        BinaryBuildQueue q = queue();
        publish("aaa", "1.0", "");
        UUID id = enqueue(q, "aaa", "1.0", Map.of());
        SourceVerifier v = new SourceVerifier(q, stallingOn("PACKAGES"), deadline(Duration.ofSeconds(2)),
            BinaryQueueSettings.DEFAULTS);
        assertEquals("FAILED", v.verifyNext("v").orElseThrow().outcome());
        assertEquals("PENDING_SOURCE", row(id).get("state"));
    }

    @Test
    public void anIndexBehindTheLockIsFetchedAgain() {
        BinaryBuildQueue q = queue();
        publish("zzz", "1.0", "");
        publish("aaa", "0.9", "");
        enqueue(q, "zzz", "1.0", Map.of());
        SourceVerifier v = verifier(q);
        assertEquals("QUEUED", v.verifyNext("v").orElseThrow().outcome(), "primes the cached index with aaa 0.9");
        publish("aaa", "1.0", "");
        UUID id = enqueue(q, "aaa", "1.0", Map.of());
        assertEquals("QUEUED", v.verifyNext("v").orElseThrow().outcome(), "refetched despite the TTL");
        assertEquals(2, indexFetches.get());
        assertEquals("QUEUED", row(id).get("state"));
    }

    @Test
    public void anIndexStillBehindTheLockDefersTheRowUntilItCatchesUp() {
        // 9262bd8-F1: forge caches a proxy's index for a day; the row waits, counting no attempt.
        BinaryBuildQueue q = queue();
        publish("aaa", "0.9", "");
        UUID id = enqueue(q, "aaa", "1.0", Map.of());
        SourceVerifier v = verifier(q);
        for (int i = 0; i < 4; i++) {
            assertEquals("DEFERRED", v.verifyNext("v").orElseThrow().outcome());
            Map<String, Object> r = row(id);
            assertEquals("PENDING_SOURCE", r.get("state"));
            assertEquals(0, r.get("attempts"), "a deferral is not an attempt");
            assertEquals("INDEX_BEHIND", r.get("error_code"));
            assertTrue(String.valueOf(r.get("error_detail")).contains("older than the locked"));
            assertTrue(v.verifyNext("v").isEmpty(), "not claimable before its not_before");
            jdbc.update("UPDATE skald.binary_build SET not_before = now() - interval '1 second' WHERE id = ?", id);
        }
        publish("aaa", "1.0", "");
        assertEquals("QUEUED", v.verifyNext("v").orElseThrow().outcome(), "the index caught up");
    }

    @Test
    public void anIndexBehindPastTheBoundIsRefused() {
        BinaryBuildQueue q = queue();
        publish("aaa", "0.9", "");
        UUID id = enqueue(q, "aaa", "1.0", Map.of());
        assertEquals("DEFERRED", outcome(q));
        jdbc.update("UPDATE skald.binary_build SET not_before = NULL, deferred_since = now() - interval '49 hours'"
            + " WHERE id = ?", id);
        assertEquals("REFUSED:INDEX_BEHIND", outcome(q));
        assertEquals("REFUSED", row(id).get("state"));
    }

    @Test
    public void aVerifierNeedsARepository() {
        assertThrows(IllegalArgumentException.class, () -> new SourceVerifier(queue(),
            new HttpSourceFetcher(Duration.ofSeconds(1)), SourceSettings.DEFAULTS, BinaryQueueSettings.DEFAULTS));
    }

    // ------------------------------------------------------------------ the lock record

    @Test
    public void aLockIsRecordedOnceAndReadBack() {
        BinaryBuildQueue q = queue();
        UUID build = UUID.randomUUID();
        q.recordLock(build, Map.of("a", "1.0", "b", "2.0"));
        q.recordLock(build, Map.of("a", "9.9"));
        assertEquals(Map.of("a", "1.0", "b", "2.0"), q.lockOf(build).orElseThrow(), "the first record stands");
        assertTrue(q.lockOf(UUID.randomUUID()).isEmpty());
        assertTrue(q.lockOf(null).isEmpty());
    }
}
