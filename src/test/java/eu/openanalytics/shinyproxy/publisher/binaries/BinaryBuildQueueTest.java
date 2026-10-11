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

import eu.openanalytics.shinyproxy.publisher.binaries.BinaryBuildQueue.BuildClaim;
import eu.openanalytics.shinyproxy.publisher.binaries.BinaryBuildQueue.Dependency;
import eu.openanalytics.shinyproxy.publisher.binaries.BinaryBuildQueue.Recorded;
import eu.openanalytics.shinyproxy.publisher.binaries.BinaryBuildQueue.SourceClaim;
import eu.openanalytics.shinyproxy.publisher.build.BuildsConfig;
import eu.openanalytics.shinyproxy.publisher.build.CoordinatorSettings;
import eu.openanalytics.shinyproxy.publisher.build.Fenced;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The binary queue against real PostgreSQL (WORKPLAN-BUNDLES.md T7b, step 2a). Time passing
 * is modelled by moving lease_expires_at or deadline_at into the past, as in
 * BuildCoordinatorTest; app builds are rows written directly, in the states that matter to
 * the yield.
 */
public class BinaryBuildQueueTest {

    @SuppressWarnings("resource")
    private static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>("postgres:16").withDatabaseName("skald");

    private static final String KEY = "noble-x86_64/4.6";
    private static final String SHA = "a".repeat(64);
    private static final String MD5 = "b".repeat(32);
    private static final Map<String, Object> PROVENANCE = Map.of("schema", "test");
    private static JdbcTemplate jdbc;

    @BeforeAll
    public static void beforeAll() {
        POSTGRES.start();
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .schemas("skald").defaultSchema("skald").locations("classpath:db/migration")
            .load().migrate();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    }

    @AfterAll
    public static void afterAll() {
        POSTGRES.stop();
    }

    @BeforeEach
    public void clean() {
        // TRUNCATE: upload intents are append-only to every row-level change, a test's reset included.
        jdbc.update("TRUNCATE skald.binary_build_upload, skald.binary_build_dep, skald.binary_build");
        jdbc.update("DELETE FROM skald.content");
        jdbc.update("DELETE FROM skald.audit_event");
    }

    private static BinaryBuildQueue queue() {
        return queue(BinaryQueueSettings.DEFAULTS, CoordinatorSettings.DEFAULTS);
    }

    private static BinaryBuildQueue queue(BinaryQueueSettings settings, CoordinatorSettings apps) {
        return new BinaryBuildQueue(jdbc, settings, apps);
    }

    private static String state(UUID row) {
        return jdbc.queryForObject("SELECT state FROM skald.binary_build WHERE id = ?", String.class, row);
    }

    private static Map<String, Object> row(UUID row) {
        return jdbc.queryForMap("SELECT * FROM skald.binary_build WHERE id = ?", row);
    }

    private static UUID id(String pkg) {
        return jdbc.queryForObject("SELECT id FROM skald.binary_build WHERE platform_key = ? AND package = ?",
            UUID.class, KEY, pkg);
    }

    private static void expire(UUID row) {
        jdbc.update("UPDATE skald.binary_build SET lease_expires_at = now() - interval '1 second' WHERE id = ?", row);
    }

    /** Enqueues {@code pkg} 1.0 and walks it through verification to QUEUED with these dependencies. */
    private static UUID verified(BinaryBuildQueue q, String pkg, String... deps) {
        UUID id = q.enqueue(KEY, pkg, "1.0", null);
        SourceClaim claim = claimSourceOf(q, id);
        List<Dependency> list = new ArrayList<>();
        for (String d : deps) {
            list.add(new Dependency(d, "1.0"));
        }
        assertEquals(Recorded.QUEUED, q.recordSource(claim.lease(), SHA, MD5, list));
        return id;
    }

    /** Claims sources until {@code id}'s comes up (others claimed on the way are put back). */
    private static SourceClaim claimSourceOf(BinaryBuildQueue q, UUID id) {
        List<UUID> others = new ArrayList<>();
        try {
            while (true) {
                SourceClaim c = q.claimSource("verifier").orElseThrow();
                if (c.rowId().equals(id)) {
                    return c;
                }
                others.add(c.rowId());
            }
        } finally {
            for (UUID o : others) {
                jdbc.update("UPDATE skald.binary_build SET state = 'PENDING_SOURCE', attempts = 0,"
                    + " lease_owner = NULL, lease_expires_at = NULL WHERE id = ?", o);
            }
        }
    }

    /** Walks {@code pkg} (already QUEUED, its deps satisfied) through a compile to UPLOADED. */
    private static void uploaded(BinaryBuildQueue q, UUID id, String digest) {
        BuildClaim c = q.claimBuild("builder").orElseThrow();
        assertEquals(id, c.rowId(), "the claim is the row expected");
        upload(q, c.lease(), digest);
    }

    /** What step 2d does around a successful PUT: the intent first, then the row. */
    private static void upload(BinaryBuildQueue q, BinaryLease lease, String digest) {
        q.intendUpload(lease, digest, PROVENANCE);
        q.uploaded(lease, digest);
    }

    /** An app build in {@code state} (QUEUED, or RUNNING with a lease). */
    private static void appBuild(String state) {
        UUID c = UUID.randomUUID();
        jdbc.update("INSERT INTO skald.content (id, title, owner, type) VALUES (?, 't', 'alice', 'shiny')", c);
        UUID b = UUID.randomUUID();
        jdbc.update("INSERT INTO skald.bundle (id, content_id, created_by, upload_deadline)"
            + " VALUES (?, ?, 'alice', now() + interval '1 hour')", b, c);
        boolean running = !state.equals("QUEUED");
        jdbc.update("INSERT INTO skald.build (content_id, bundle_id, created_by, idempotency_key,"
            + " input_fingerprint, state, lease_owner, lease_expires_at, lease_generation)"
            + " VALUES (?, ?, 'alice', ?, ?, ?, ?, ?, ?)", c, b, UUID.randomUUID().toString(),
            "f".repeat(64), state, running ? "n" : null,
            running ? java.sql.Timestamp.from(java.time.Instant.now().plusSeconds(60)) : null,
            running ? 1 : 0);
    }

    // ------------------------------------------------------------------ enqueue and source

    @Test
    public void enqueueingTwiceIsOneRow() {
        BinaryBuildQueue q = queue();
        UUID build = UUID.randomUUID();
        UUID first = q.enqueue(KEY, "jsonlite", "2.0.0", build);
        UUID again = q.enqueue(KEY, "jsonlite", "2.0.0", UUID.randomUUID());
        assertEquals(first, again);
        assertEquals("PENDING_SOURCE", state(first));
        assertEquals(build, row(first).get("requested_by_build"), "the first asker is kept");
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM skald.audit_event"
            + " WHERE action = 'binary.enqueued'", Integer.class));
        assertTrue(!first.equals(q.enqueue("trixie-x86_64/4.6", "jsonlite", "2.0.0", null)),
            "another platform is another row");
    }

    @Test
    public void aSourceClaimLeasesTheOldestPendingRow() throws Exception {
        BinaryBuildQueue q = queue();
        UUID first = q.enqueue(KEY, "aaa", "1.0", null);
        Thread.sleep(5);
        q.enqueue(KEY, "bbb", "1.0", null);
        SourceClaim c = q.claimSource("v1").orElseThrow();
        assertEquals(first, c.rowId());
        assertEquals("aaa", c.pkg());
        assertEquals(KEY, c.platformKey());
        assertEquals(1, c.lease().generation());
        Map<String, Object> r = row(first);
        assertEquals("VERIFYING", r.get("state"));
        assertEquals("v1", r.get("lease_owner"));
        assertEquals(1, r.get("attempts"));
        assertTrue(q.claimSource("v2").isPresent());
        assertFalse(q.claimSource("v3").isPresent(), "nothing left to verify");
    }

    @Test
    public void recordingTheSourceQueuesTheRowAndItsMissingDependencies() {
        BinaryBuildQueue q = queue();
        UUID build = UUID.randomUUID();
        UUID shiny = q.enqueue(KEY, "shiny", "1.9.1", build);
        UUID existing = q.enqueue(KEY, "httpuv", "1.6.15", null);
        SourceClaim c = q.claimSource("v").orElseThrow();
        assertEquals(shiny, c.rowId());
        assertEquals(Recorded.QUEUED, q.recordSource(c.lease(), SHA, MD5, List.of(
            new Dependency("httpuv", "1.6.15"), new Dependency("later", "1.3.2"),
            new Dependency("later", "1.3.2"))));
        Map<String, Object> r = row(shiny);
        assertEquals("QUEUED", r.get("state"));
        assertEquals(SHA, r.get("source_sha256"));
        assertEquals(MD5, r.get("source_md5"));
        assertEquals(0, r.get("attempts"), "the compile gets its own attempts");
        assertNull(r.get("lease_owner"));
        UUID later = id("later");
        assertEquals("PENDING_SOURCE", state(later));
        assertEquals(build, row(later).get("requested_by_build"));
        assertEquals(java.util.Set.of(existing, later), new java.util.HashSet<>(jdbc.queryForList(
            "SELECT dependency_id FROM skald.binary_build_dep WHERE binary_build_id = ?", UUID.class, shiny)));
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM skald.binary_build_dep"
            + " WHERE binary_build_id = ?", Integer.class, shiny), "a repeated dependency is one edge");
    }

    @Test
    public void sourceDigestsMustBeHex() {
        BinaryBuildQueue q = queue();
        q.enqueue(KEY, "aaa", "1.0", null);
        SourceClaim c = q.claimSource("v").orElseThrow();
        assertThrows(IllegalArgumentException.class, () -> q.recordSource(c.lease(), "A".repeat(64), MD5, List.of()));
        assertThrows(IllegalArgumentException.class, () -> q.recordSource(c.lease(), SHA, "b".repeat(31), List.of()));
        assertThrows(IllegalArgumentException.class, () -> q.recordSource(c.lease(), null, MD5, List.of()));
        assertEquals("VERIFYING", state(c.rowId()));
    }

    @Test
    public void aDependencyThatAlreadyDependsOnTheRowIsACycle() {
        BinaryBuildQueue q = queue();
        UUID a = verified(q, "aaa", "bbb");
        UUID b = id("bbb");
        SourceClaim c = claimSourceOf(q, b);
        assertEquals(Recorded.REFUSED_CYCLE, q.recordSource(c.lease(), SHA, MD5, List.of(new Dependency("aaa", "1.0"))));
        Map<String, Object> r = row(b);
        assertEquals("REFUSED", r.get("state"));
        assertEquals("DEPENDENCY_CYCLE", r.get("refusal_code"));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM skald.binary_build_dep"
            + " WHERE binary_build_id = ?", Integer.class, b), "no edge of a cycle is kept");
        assertEquals("QUEUED", state(a));
        assertFalse(queue().claimBuild("x").isPresent(), "aaa waits on a refused dependency: refused");
        assertEquals("REFUSED", state(a));
    }

    @Test
    public void aPackageThatDependsOnItselfIsACycle() {
        BinaryBuildQueue q = queue();
        UUID a = q.enqueue(KEY, "aaa", "1.0", null);
        SourceClaim c = q.claimSource("v").orElseThrow();
        assertEquals(Recorded.REFUSED_CYCLE, q.recordSource(c.lease(), SHA, MD5, List.of(new Dependency("aaa", "1.0"))));
        assertEquals("REFUSED", state(a));
    }

    @Test
    public void aLongerCycleIsFound() {
        BinaryBuildQueue q = queue();
        verified(q, "aaa", "bbb");
        verified(q, "bbb", "ccc");
        UUID c = id("ccc");
        assertEquals(Recorded.REFUSED_CYCLE, q.recordSource(claimSourceOf(q, c).lease(), SHA, MD5,
            List.of(new Dependency("aaa", "1.0"))));
    }

    @Test
    public void edgesAreRecordedUnderThePlatformsLock() throws Exception {
        // Two rows recording at once must not each miss the other's half of a cycle: the edges
        // of one platform are recorded under one lock. Held here from another connection, a
        // recording waits for it.
        BinaryBuildQueue q = queue();
        q.enqueue(KEY, "aaa", "1.0", null);
        SourceClaim c = q.claimSource("v").orElseThrow();
        var tm = new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource());
        var holder = tm.getTransaction(new org.springframework.transaction.support.DefaultTransactionDefinition());
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            jdbc.queryForObject("SELECT pg_advisory_xact_lock(?, hashtext(?))", Object.class, 0x5ca1d4, KEY);
            Future<Recorded> recording = pool.submit(() -> q.recordSource(c.lease(), SHA, MD5, List.of()));
            Thread.sleep(1000);
            assertFalse(recording.isDone(), "the recording waits for the platform's lock");
            tm.commit(holder);
            holder = null;
            assertEquals(Recorded.QUEUED, recording.get(30, java.util.concurrent.TimeUnit.SECONDS));
        } finally {
            if (holder != null) {
                tm.rollback(holder);
            }
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------------------------ dependency order

    @Test
    public void aRowWaitsUntilEveryDependencyIsUploadedBySkald() {
        BinaryBuildQueue q = queue();
        UUID shiny = verified(q, "shiny", "httpuv", "later");
        UUID httpuv = verified(q, "httpuv", "later");
        UUID later = verified(q, "later");

        uploaded(q, later, "1".repeat(64));
        BuildClaim c = q.claimBuild("builder").orElseThrow();
        assertEquals(httpuv, c.rowId(), "shiny still waits on httpuv");
        assertEquals(List.of(new BinaryBuildQueue.BuiltDependency("later", "1.0", "1".repeat(64))),
            c.dependencies(), "handed later's Skald-uploaded digest");
        assertEquals(SHA, c.sourceSha256());
        assertEquals(MD5, c.sourceMd5());
        assertFalse(q.claimBuild("other").isPresent(), "one compile at a time, and shiny waits");
        upload(q, c.lease(), "2".repeat(64));

        BuildClaim s = q.claimBuild("builder").orElseThrow();
        assertEquals(shiny, s.rowId());
        assertEquals(List.of(new BinaryBuildQueue.BuiltDependency("httpuv", "1.0", "2".repeat(64)),
            new BinaryBuildQueue.BuiltDependency("later", "1.0", "1".repeat(64))), s.dependencies());
    }

    @Test
    public void aRowWithAnUnverifiedDependencyWaits() {
        BinaryBuildQueue q = queue();
        verified(q, "shiny", "httpuv");
        assertEquals("PENDING_SOURCE", state(id("httpuv")));
        assertFalse(q.claimBuild("builder").isPresent());
        assertEquals("QUEUED", state(id("shiny")));
    }

    @Test
    public void aForeignDependencyRefusesItsDependents() {
        BinaryBuildQueue q = queue();
        UUID shiny = verified(q, "shiny", "httpuv");
        UUID httpuv = verified(q, "httpuv");
        assertEquals(BinaryBuildQueue.Present.FOREIGN,
            q.alreadyPresent(q.claimBuild("b").orElseThrow().lease(), "9".repeat(64), "409 on upload"));
        assertEquals("PRESENT_FOREIGN", state(httpuv));
        assertEquals("9".repeat(64), row(httpuv).get("binary_sha256"));
        assertFalse(q.claimBuild("b").isPresent(), "a foreign binary is never handed in");
        Map<String, Object> r = row(shiny);
        assertEquals("REFUSED", r.get("state"));
        assertEquals("DEPENDENCY_NOT_SKALD_BUILT", r.get("refusal_code"));
        assertEquals("httpuv 1.0 is PRESENT_FOREIGN", r.get("refusal_detail"));
        assertNotNull(r.get("finished_at"));
    }

    @Test
    public void aRefusedDependencyRefusesItsDependents() {
        BinaryBuildQueue q = queue();
        UUID shiny = verified(q, "shiny", "httpuv");
        q.refuse(claimSourceOf(q, id("httpuv")).lease(), "NOT_PUBLIC", "a hosted package");
        assertFalse(q.claimBuild("b").isPresent());
        assertEquals("httpuv 1.0 is REFUSED", row(shiny).get("refusal_detail"));
    }

    @Test
    public void aFailedDependencyRefusesItsDependents() {
        BinaryBuildQueue q = queue(new BinaryQueueSettings(Duration.ofSeconds(60), Duration.ofSeconds(20),
            Duration.ofMinutes(60), 1, 1), CoordinatorSettings.DEFAULTS);
        UUID shiny = verified(q, "shiny", "httpuv");
        verified(q, "httpuv");
        q.fail(q.claimBuild("b").orElseThrow().lease(), "COMPILE", "error: x");
        assertEquals("FAILED", state(id("httpuv")));
        assertEquals(0, q.reap(), "no lease to settle");
        assertEquals("REFUSED", state(shiny), "the reaper refuses it too");
        assertEquals("httpuv 1.0 is FAILED", row(shiny).get("refusal_detail"));
    }

    @Test
    public void aRefusalTravelsUpTheChain() {
        BinaryBuildQueue q = queue();
        UUID top = verified(q, "aaa", "bbb");
        UUID mid = verified(q, "bbb", "ccc");
        verified(q, "ccc");
        assertEquals(BinaryBuildQueue.Present.FOREIGN,
            q.alreadyPresent(q.claimBuild("b").orElseThrow().lease(), "9".repeat(64), "found"));
        assertEquals(2, q.refuseBlocked());
        assertEquals("REFUSED", state(mid));
        assertEquals("REFUSED", state(top));
        assertEquals("bbb 1.0 is REFUSED", row(top).get("refusal_detail"));
    }

    // ------------------------------------------------------------------ the yield to app builds

    @Test
    public void aQueuedAppBuildGoesFirst() {
        BinaryBuildQueue q = queue();
        UUID a = verified(q, "aaa");
        appBuild("QUEUED");
        assertFalse(q.claimBuild("b").isPresent());
        jdbc.update("DELETE FROM skald.content");
        assertEquals(a, q.claimBuild("b").orElseThrow().rowId());
    }

    @Test
    public void aCompileTakesNoAppBuildsSlot() {
        BinaryBuildQueue q = queue(BinaryQueueSettings.DEFAULTS, new CoordinatorSettings(Duration.ofSeconds(60),
            Duration.ofSeconds(20), Duration.ofMinutes(20), 2));
        verified(q, "aaa");
        appBuild("RUNNING");
        appBuild("PUBLISHING");
        assertFalse(q.claimBuild("b").isPresent(), "both app slots are busy");
        jdbc.update("DELETE FROM skald.build WHERE state = 'PUBLISHING'");
        assertTrue(q.claimBuild("b").isPresent(), "a slot is idle");
    }

    @Test
    public void atMostMaxRunningCompilesRun() {
        BinaryBuildQueue one = queue();
        verified(one, "aaa");
        verified(one, "bbb");
        verified(one, "ccc");
        assertTrue(one.claimBuild("b1").isPresent());
        assertFalse(one.claimBuild("b2").isPresent());
        BinaryBuildQueue two = queue(new BinaryQueueSettings(Duration.ofSeconds(60), Duration.ofSeconds(20),
            Duration.ofMinutes(60), 2, 2), new CoordinatorSettings(Duration.ofSeconds(60),
            Duration.ofSeconds(20), Duration.ofMinutes(20), 1));
        assertTrue(two.claimBuild("b2").isPresent());
        assertFalse(two.claimBuild("b3").isPresent());
    }

    @Test
    public void concurrentClaimsNeverExceedTheLimit() throws Exception {
        BinaryBuildQueue q = queue();
        for (int i = 0; i < 6; i++) {
            verified(q, "pkg" + i);
        }
        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Optional<BuildClaim>>> claims = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                String owner = "b" + i;
                Callable<Optional<BuildClaim>> claim = () -> {
                    go.await();
                    return q.claimBuild(owner);
                };
                claims.add(pool.submit(claim));
            }
            go.countDown();
            int won = 0;
            for (Future<Optional<BuildClaim>> f : claims) {
                won += f.get().isPresent() ? 1 : 0;
            }
            assertEquals(1, won);
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM skald.binary_build"
                + " WHERE state = 'RUNNING'", Integer.class));
        } finally {
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------------------------ fencing

    @Test
    public void anExpiredLeaseIsFenced() {
        BinaryBuildQueue q = queue();
        UUID a = verified(q, "aaa");
        BuildClaim c = q.claimBuild("b").orElseThrow();
        expire(a);
        assertFalse(q.renew(c.lease()));
        assertThrows(Fenced.class, () -> q.intendUpload(c.lease(), SHA, PROVENANCE));
        assertThrows(Fenced.class, () -> q.uploaded(c.lease(), SHA));
        assertEquals("RUNNING", state(a));
        assertNull(row(a).get("binary_sha256"));
    }

    @Test
    public void aPassedDeadlineIsFenced() {
        BinaryBuildQueue q = queue();
        UUID a = verified(q, "aaa");
        BuildClaim c = q.claimBuild("b").orElseThrow();
        q.intendUpload(c.lease(), SHA, PROVENANCE);
        jdbc.update("UPDATE skald.binary_build SET deadline_at = now() - interval '1 second' WHERE id = ?", a);
        assertFalse(q.renew(c.lease()));
        assertThrows(Fenced.class, () -> q.uploaded(c.lease(), SHA));
    }

    @Test
    public void anotherGenerationOrOwnerIsFenced() {
        BinaryBuildQueue q = queue();
        UUID a = verified(q, "aaa");
        BuildClaim c = q.claimBuild("b").orElseThrow();
        BinaryLease old = new BinaryLease(a, c.lease().generation() - 1, "b");
        BinaryLease thief = new BinaryLease(a, c.lease().generation(), "x");
        q.intendUpload(c.lease(), SHA, PROVENANCE);
        assertThrows(Fenced.class, () -> q.intendUpload(old, "c".repeat(64), PROVENANCE));
        assertThrows(Fenced.class, () -> q.uploaded(old, SHA));
        assertThrows(Fenced.class, () -> q.uploaded(thief, SHA));
        assertThrows(Fenced.class, () -> q.refuse(thief, "X", null));
        assertThrows(Fenced.class, () -> q.fail(thief, "X", null));
        assertThrows(Fenced.class, () -> q.alreadyPresent(thief, SHA, null));
        assertThrows(Fenced.class, () -> q.alreadyPresent(thief, "c".repeat(64), null));
        assertFalse(q.renew(thief));
        assertTrue(q.renew(c.lease()));
        assertEquals("RUNNING", state(a));
    }

    @Test
    public void aVerifyingRowCannotBeUploaded() {
        BinaryBuildQueue q = queue();
        q.enqueue(KEY, "aaa", "1.0", null);
        SourceClaim c = q.claimSource("v").orElseThrow();
        assertThrows(Fenced.class, () -> q.intendUpload(c.lease(), SHA, PROVENANCE));
        assertThrows(Fenced.class, () -> q.uploaded(c.lease(), SHA));
        assertEquals("VERIFYING", state(c.rowId()));
    }

    @Test
    public void aRunningRowCannotRecordItsSourceAgain() {
        BinaryBuildQueue q = queue();
        verified(q, "aaa");
        BuildClaim c = q.claimBuild("b").orElseThrow();
        assertThrows(Fenced.class, () -> q.recordSource(c.lease(), "c".repeat(64), MD5, List.of()));
        assertEquals(SHA, row(c.rowId()).get("source_sha256"));
    }

    @Test
    public void anUploadNeedsAHexDigestAProvenanceRecordAndAnIntent() {
        BinaryBuildQueue q = queue();
        verified(q, "aaa");
        BuildClaim c = q.claimBuild("b").orElseThrow();
        assertThrows(IllegalArgumentException.class, () -> q.intendUpload(c.lease(), "sha256:" + SHA, PROVENANCE));
        assertThrows(IllegalArgumentException.class, () -> q.intendUpload(c.lease(), SHA, Map.of("x", 1)));
        assertThrows(IllegalArgumentException.class, () -> q.uploaded(c.lease(), "z".repeat(64)));
        assertThrows(IllegalArgumentException.class, () -> q.alreadyPresent(c.lease(), "z".repeat(64), null));
        assertThrows(IllegalArgumentException.class, () -> q.refuse(c.lease(), " ", null));
        assertThrows(IllegalArgumentException.class, () -> q.fail(c.lease(), null, null));
        assertThrows(IllegalArgumentException.class, () -> q.fail(c.lease(), " ", null));
        assertThrows(IllegalStateException.class, () -> q.uploaded(c.lease(), SHA), "no intent recorded");
        assertEquals("RUNNING", state(c.rowId()));
        q.intendUpload(c.lease(), SHA, Map.of("schema", "p", "attempt", 1));
        q.intendUpload(c.lease(), SHA, PROVENANCE);
        q.uploaded(c.lease(), SHA);
        Map<String, Object> r = row(c.rowId());
        assertEquals("UPLOADED", r.get("state"));
        assertEquals(SHA, r.get("binary_sha256"));
        assertTrue(String.valueOf(r.get("provenance")).contains("\"schema\": \"p\""),
            "the row takes the intent's provenance, the first recorded for this digest: " + r.get("provenance"));
        assertNull(r.get("lease_owner"));
        assertNotNull(r.get("finished_at"));
    }

    @Test
    public void anAttemptIntendsOneDigest() {
        BinaryBuildQueue q = queue();
        verified(q, "aaa");
        BuildClaim c = q.claimBuild("b").orElseThrow();
        q.intendUpload(c.lease(), SHA, PROVENANCE);
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
            () -> q.intendUpload(c.lease(), "c".repeat(64), PROVENANCE));
    }

    @Test
    public void aLostUploadWriteIsRecognisedAsSkaldsOwnOnTheNext409() {
        // 1fba61d-F1, the review's sequence: the first attempt uploads, then loses its lease
        // before writing the row; the second attempt's PUT meets a 409 with the first's digest.
        BinaryBuildQueue q = queue();
        UUID a = verified(q, "aaa");
        UUID b = verified(q, "bbb", "aaa");
        BuildClaim first = q.claimBuild("b").orElseThrow();
        assertEquals(a, first.rowId());
        q.intendUpload(first.lease(), "1".repeat(64), Map.of("schema", "test", "attempt", "first"));
        expire(a);
        assertThrows(Fenced.class, () -> q.uploaded(first.lease(), "1".repeat(64)));
        assertEquals(1, q.reap());
        assertEquals("QUEUED", state(a));
        BuildClaim second = q.claimBuild("b").orElseThrow();
        assertEquals(a, second.rowId());
        q.intendUpload(second.lease(), "2".repeat(64), PROVENANCE);
        assertEquals(BinaryBuildQueue.Present.OURS, q.alreadyPresent(second.lease(), "1".repeat(64), "409 on upload"));
        Map<String, Object> r = row(a);
        assertEquals("UPLOADED", r.get("state"));
        assertEquals("1".repeat(64), r.get("binary_sha256"));
        assertTrue(String.valueOf(r.get("provenance")).contains("first"), "the first attempt's provenance");
        assertEquals(0, q.reap());
        BuildClaim dependent = q.claimBuild("b").orElseThrow();
        assertEquals(b, dependent.rowId(), "its dependent is not refused");
        assertEquals(List.of(new BinaryBuildQueue.BuiltDependency("aaa", "1.0", "1".repeat(64))),
            dependent.dependencies());
    }

    @Test
    public void aDigestNoAttemptIntendedIsForeignEvenAfterIntents() {
        BinaryBuildQueue q = queue();
        UUID a = verified(q, "aaa");
        BuildClaim first = q.claimBuild("b").orElseThrow();
        q.intendUpload(first.lease(), "1".repeat(64), PROVENANCE);
        expire(a);
        q.reap();
        BuildClaim second = q.claimBuild("b").orElseThrow();
        assertEquals(BinaryBuildQueue.Present.FOREIGN, q.alreadyPresent(second.lease(), "9".repeat(64), "409"));
        assertEquals("PRESENT_FOREIGN", state(a));
        assertEquals("9".repeat(64), row(a).get("binary_sha256"));
        assertNull(row(a).get("provenance"));
    }

    @Test
    public void aBinaryFoundBeforeCompilingIsForeign() {
        BinaryBuildQueue q = queue();
        UUID a = q.enqueue(KEY, "aaa", "1.0", null);
        SourceClaim c = q.claimSource("v").orElseThrow();
        assertEquals(BinaryBuildQueue.Present.FOREIGN, q.alreadyPresent(c.lease(), "9".repeat(64),
            "present before compiling"));
        assertEquals("PRESENT_FOREIGN", state(a));
    }

    // ------------------------------------------------------------------ failures and the reaper

    @Test
    public void aFailedCompileIsRetriedThenFailed() {
        BinaryBuildQueue q = queue();
        UUID a = verified(q, "aaa");
        q.fail(q.claimBuild("b").orElseThrow().lease(), "COMPILE", "error 1");
        Map<String, Object> r = row(a);
        assertEquals("QUEUED", r.get("state"));
        assertEquals("COMPILE", r.get("error_code"));
        assertNull(r.get("finished_at"));
        assertNull(r.get("lease_owner"));
        BuildClaim second = q.claimBuild("b").orElseThrow();
        assertEquals(3, second.lease().generation(), "verification, then two compile claims");
        q.fail(second.lease(), "COMPILE", "error 2");
        r = row(a);
        assertEquals("FAILED", r.get("state"));
        assertEquals("error 2", r.get("error_detail"));
        assertNotNull(r.get("finished_at"));
        assertFalse(q.claimBuild("b").isPresent());
    }

    @Test
    public void aFailedVerificationGoesBackToPendingSource() {
        BinaryBuildQueue q = queue();
        UUID a = q.enqueue(KEY, "aaa", "1.0", null);
        q.fail(q.claimSource("v").orElseThrow().lease(), "FETCH", "timeout");
        assertEquals("PENDING_SOURCE", state(a));
        q.fail(q.claimSource("v").orElseThrow().lease(), "FETCH", "timeout");
        assertEquals("FAILED", state(a));
    }

    @Test
    public void aVerificationRetryDoesNotUseUpTheCompilesAttempts() {
        BinaryBuildQueue q = queue();
        UUID a = q.enqueue(KEY, "aaa", "1.0", null);
        q.fail(q.claimSource("v").orElseThrow().lease(), "FETCH", "timeout");
        assertEquals(Recorded.QUEUED, q.recordSource(q.claimSource("v").orElseThrow().lease(), SHA, MD5, List.of()));
        q.fail(q.claimBuild("b").orElseThrow().lease(), "COMPILE", "error");
        assertEquals("QUEUED", state(a), "the compile's first failure, not the row's third");
    }

    @Test
    public void theReaperReturnsALostCompileToTheQueue() {
        BinaryBuildQueue q = queue();
        UUID a = verified(q, "aaa");
        BuildClaim c = q.claimBuild("b").orElseThrow();
        assertEquals(0, q.reap(), "a live lease is left alone");
        expire(a);
        assertEquals(1, q.reap());
        Map<String, Object> r = row(a);
        assertEquals("QUEUED", r.get("state"));
        assertEquals("LEASE_EXPIRED", r.get("error_code"));
        assertNull(r.get("lease_owner"));
        assertThrows(Fenced.class, () -> q.intendUpload(c.lease(), SHA, PROVENANCE));
    }

    @Test
    public void theReaperReturnsALostVerificationToPendingSource() {
        BinaryBuildQueue q = queue();
        UUID a = q.enqueue(KEY, "aaa", "1.0", null);
        q.claimSource("v").orElseThrow();
        jdbc.update("UPDATE skald.binary_build SET deadline_at = now() - interval '1 second' WHERE id = ?", a);
        assertEquals(1, q.reap());
        assertEquals("PENDING_SOURCE", state(a));
        assertEquals("DEADLINE_EXCEEDED", row(a).get("error_code"));
    }

    @Test
    public void theReaperFailsARowOutOfAttempts() {
        BinaryBuildQueue q = queue();
        UUID a = verified(q, "aaa");
        UUID dependent = verified(q, "zzz", "aaa");
        for (int i = 0; i < 2; i++) {
            q.claimBuild("b").orElseThrow();
            expire(a);
            assertEquals(1, q.reap());
        }
        assertEquals("FAILED", state(a));
        assertNotNull(row(a).get("finished_at"));
        assertEquals("REFUSED", state(dependent), "and its dependents are refused in the same pass");
    }

    // ------------------------------------------------------------------ settings

    @Test
    public void settingsHaveDefaultsAndRefuseUnknownKeysAndBadValues() {
        assertEquals(BinaryQueueSettings.DEFAULTS, BinaryQueueSettings.fromOverrides(Map.of()));
        BinaryQueueSettings s = BinaryQueueSettings.fromOverrides(Map.of("max-running", "3",
            "max_attempts", "4", "deadline-minutes", "90", "lease-seconds", "120", "renew-every-seconds", "30"));
        assertEquals(new BinaryQueueSettings(Duration.ofSeconds(120), Duration.ofSeconds(30),
            Duration.ofMinutes(90), 3, 4), s);
        assertThrows(IllegalArgumentException.class, () -> BinaryQueueSettings.fromOverrides(Map.of("max-runing", "1")));
        assertThrows(IllegalArgumentException.class, () -> BinaryQueueSettings.fromOverrides(Map.of("max-running", "x")));
        assertThrows(IllegalArgumentException.class, () -> BinaryQueueSettings.fromOverrides(Map.of("max-running", "0")));
        assertThrows(IllegalArgumentException.class, () -> BinaryQueueSettings.fromOverrides(Map.of("max-attempts", "0")));
        assertThrows(IllegalArgumentException.class, () -> BinaryQueueSettings.fromOverrides(
            Map.of("lease-seconds", "30", "renew-every-seconds", "20")));
    }

    @Test
    public void theApplicationBindsTheSettingsAndStopsOnATypo() {
        ApplicationContextRunner context = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(BuildsConfig.class);
        context.withPropertyValues("skald.builds.binaries.max-running=2").run(ctx ->
            assertEquals(2, ctx.getBean(BinaryQueueSettings.class).maxRunning()));
        context.run(ctx -> assertEquals(BinaryQueueSettings.DEFAULTS, ctx.getBean(BinaryQueueSettings.class)));
        context.withPropertyValues("skald.builds.binaries.max-runing=2").run(ctx -> {
            assertNotNull(ctx.getStartupFailure());
            assertTrue(String.valueOf(ctx.getStartupFailure()).contains("max-runing")
                || rootMessage(ctx.getStartupFailure()).contains("max-runing"), rootMessage(ctx.getStartupFailure()));
        });
    }

    private static String rootMessage(Throwable t) {
        while (t.getCause() != null) {
            t = t.getCause();
        }
        return String.valueOf(t.getMessage());
    }
}
