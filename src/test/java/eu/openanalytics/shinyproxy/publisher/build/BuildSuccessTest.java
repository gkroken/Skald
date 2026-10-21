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
package eu.openanalytics.shinyproxy.publisher.build;

import eu.openanalytics.shinyproxy.publisher.db.TestPostgres;
import eu.openanalytics.shinyproxy.publisher.registry.RuntimeSpec;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A verified attempt becomes one staged version (WORKPLAN-BUNDLES.md T9 (b), (d)): the runner
 * derives the runtime document from the build's own provenance and records the success, which
 * activates nothing. A record without a base fails the attempt; a database that fails at the
 * success is retried while the lease holds and otherwise leaves the attempt to the reaper; a
 * success whose reply was lost is recognised on retry, never doubled; a cancel that lands
 * after PUBLISHING is too late. Real PostgreSQL, fake drivers.
 */
public class BuildSuccessTest {

    @SuppressWarnings("resource")
    private static final PostgreSQLContainer<?> POSTGRES = TestPostgres.create();
    private static final String DIGEST = "registry:5000/skald/content/c@sha256:" + "c".repeat(64);
    private static final Map<String, Object> PROVENANCE = Map.of("schema", "test", "base",
        Map.of("catalog_id", "python-shiny-3.13.16-amd64-r1", "language", "python", "uid", "1001", "gid", "1002"));
    private static final CoordinatorSettings ONE = CoordinatorSettings.DEFAULTS;
    private static final CoordinatorSettings SHORT = new CoordinatorSettings(
        Duration.ofSeconds(3), Duration.ofSeconds(1), Duration.ofMinutes(20), 1);
    private static JdbcTemplate jdbc;

    @BeforeAll
    public static void beforeAll() {
        POSTGRES.start();
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .schemas("skald").defaultSchema("skald").locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
            POSTGRES.getPassword()));
    }

    @AfterAll
    public static void afterAll() {
        POSTGRES.stop();
    }

    @BeforeEach
    public void clean() {
        jdbc.update("DELETE FROM skald.content");
    }

    private static UUID queued() {
        UUID c = UUID.randomUUID();
        jdbc.update("INSERT INTO skald.content (id, title, owner, type) VALUES (?, 't', 'alice', 'shiny')", c);
        UUID b = UUID.randomUUID();
        jdbc.update("INSERT INTO skald.bundle (id, content_id, created_by, state, upload_deadline, finished_at,"
            + " object_key, object_sha256, object_bytes, manifest_sha256, inventory_files, inventory_bytes)"
            + " VALUES (?, ?, 'alice', 'VALIDATED', now() + interval '1 hour', now(), ?, ?, 10, ?, 1, 10)",
            b, c, "bundles/" + b, "a".repeat(64), "b".repeat(64));
        return new BuildAdmission(jdbc, new AdmissionLimits(100, 100))
            .request(c, "alice", "k-" + UUID.randomUUID(), new BuildAdmission.Inputs(b, Map.of())).buildId();
    }

    private static String state(UUID build) {
        return jdbc.queryForObject("SELECT state FROM skald.build WHERE id = ?", String.class, build);
    }

    private static int versions(UUID build) {
        return jdbc.queryForObject("SELECT count(*) FROM skald.content_version WHERE build_id = ?", Integer.class, build);
    }

    private static final BuildDriver BUILT = (b, stop) -> new BuildDriver.Built(DIGEST, PROVENANCE);
    private static final BuildRunner.Publishing VERIFIED = (b, image) -> 3;

    @Test
    public void aVerifiedAttemptBecomesOneStagedVersionWithItsRuntimeDocument() {
        UUID build = queued();
        BuildCoordinator coordinator = new BuildCoordinator(jdbc, ONE);
        assertEquals(BuildRunner.Result.SUCCEEDED, new BuildRunner(coordinator, ONE, VERIFIED).runOnce("n1", BUILT));
        assertEquals("SUCCEEDED", state(build));
        Map<String, Object> v = jdbc.queryForMap("SELECT v.version, v.image, v.spec_json::text AS spec,"
            + " c.active_version_id FROM skald.content_version v JOIN skald.content c ON c.id = v.content_id"
            + " WHERE v.build_id = ?", build);
        assertEquals(1, v.get("version"));
        assertEquals(DIGEST, v.get("image"));
        assertEquals(new RuntimeSpec(3838, "1001:1002", "python", "python-shiny-3.13.16-amd64-r1"),
            RuntimeSpec.parse((String) v.get("spec")));
        assertNull(v.get("active_version_id"), "a success is staged; activating it is an admin's act");
    }

    @Test
    public void aRecordWithoutItsBaseFailsTheAttemptAndMakesNoVersion() {
        UUID build = queued();
        BuildCoordinator coordinator = new BuildCoordinator(jdbc, ONE);
        BuildDriver noBase = (b, stop) -> new BuildDriver.Built(DIGEST, Map.of("schema", "test"));
        assertEquals(BuildRunner.Result.FAILED, new BuildRunner(coordinator, ONE, VERIFIED).runOnce("n1", noBase));
        assertEquals("FAILED", state(build));
        assertEquals("RUNTIME_SPEC", jdbc.queryForObject("SELECT error_code FROM skald.build WHERE id = ?",
            String.class, build));
        assertEquals(0, versions(build));
        BuildDriver rootBase = (b, stop) -> new BuildDriver.Built(DIGEST, Map.of("schema", "test", "base",
            Map.of("catalog_id", "python-shiny-3.13.16-amd64-r1", "language", "python", "uid", "0", "gid", "0")));
        UUID root = queued();
        assertEquals(BuildRunner.Result.FAILED, new BuildRunner(coordinator, ONE, VERIFIED).runOnce("n1", rootBase));
        assertEquals("RUNTIME_SPEC", jdbc.queryForObject("SELECT error_code FROM skald.build WHERE id = ?",
            String.class, root), "a base claiming uid 0 never becomes a version");
        assertEquals(0, versions(root));
        BuildDriver noUid = (b, stop) -> new BuildDriver.Built(DIGEST, Map.of("schema", "test", "base",
            Map.of("catalog_id", "python-shiny-3.13.16-amd64-r1", "language", "python")));
        UUID missing = queued();
        assertEquals(BuildRunner.Result.FAILED, new BuildRunner(coordinator, ONE, VERIFIED).runOnce("n1", noUid));
        assertEquals("RUNTIME_SPEC", jdbc.queryForObject("SELECT error_code FROM skald.build WHERE id = ?",
            String.class, missing));
        BuildDriver numericId = (b, stop) -> new BuildDriver.Built(DIGEST, Map.of("schema", "test", "base",
            Map.of("catalog_id", 7, "language", "python", "uid", "1001", "gid", "1002")));
        UUID odd = queued();
        assertEquals(BuildRunner.Result.FAILED, new BuildRunner(coordinator, ONE, VERIFIED).runOnce("n1", numericId),
            "a record of the wrong shape is the attempt's failure, not an exception");
        assertEquals("RUNTIME_SPEC", jdbc.queryForObject("SELECT error_code FROM skald.build WHERE id = ?",
            String.class, odd));
    }

    @Test
    public void aDatabaseFailureAtSuccessIsRetriedWhileTheLeaseHolds() {
        UUID build = queued();
        BuildCoordinator coordinator = new BuildCoordinator(jdbc, ONE);
        AtomicInteger tries = new AtomicInteger();
        BuildRunner runner = new BuildRunner(coordinator, ONE, VERIFIED, (lease, runtime) -> {
            if (tries.incrementAndGet() <= 2) {
                throw new DataAccessResourceFailureException("injected: the database is unavailable");
            }
            coordinator.succeed(lease, runtime);
        });
        assertEquals(BuildRunner.Result.SUCCEEDED, runner.runOnce("n1", BUILT));
        assertEquals(3, tries.get());
        assertEquals("SUCCEEDED", state(build));
        assertEquals(1, versions(build));
    }

    /**
     * A database that answers and refuses is not unavailable (2fb308e-F1): no retry changes a
     * constraint's answer, so the attempt is FAILED VERSION_TX at once, not a timeout later.
     */
    @Test
    public void aRefusalAtSuccessFailsTheAttemptAtOnce() {
        UUID build = queued();
        BuildCoordinator coordinator = new BuildCoordinator(jdbc, SHORT);
        AtomicInteger tries = new AtomicInteger();
        BuildRunner runner = new BuildRunner(coordinator, SHORT, VERIFIED, (lease, runtime) -> {
            tries.incrementAndGet();
            throw new org.springframework.dao.DataIntegrityViolationException("injected: a constraint refused it");
        });
        long t0 = System.nanoTime();
        assertEquals(BuildRunner.Result.FAILED, runner.runOnce("n1", BUILT));
        assertTrue(System.nanoTime() - t0 < Duration.ofSeconds(10).toNanos(), "promptly, not at the deadline");
        assertEquals(1, tries.get());
        assertEquals("FAILED", state(build));
        assertEquals("VERSION_TX", jdbc.queryForObject("SELECT error_code FROM skald.build WHERE id = ?",
            String.class, build));
        assertEquals(0, versions(build));
    }

    /**
     * A TCP forwarder to PostgreSQL on a port of its own that stays the same, so the database
     * can go away and come back at one address: down, it refuses every new connection and
     * resets the open ones, which is what the driver meets when the database host is gone.
     */
    private static final class Outage implements AutoCloseable {
        private final int port;
        private final java.util.Set<java.net.Socket> open = java.util.concurrent.ConcurrentHashMap.newKeySet();
        private volatile java.net.ServerSocket server;

        Outage() throws java.io.IOException {
            try (java.net.ServerSocket probe = new java.net.ServerSocket(0)) {
                port = probe.getLocalPort();
            }
            up();
        }

        String url() {
            return POSTGRES.getJdbcUrl().replaceFirst("//[^/]+/", "//localhost:" + port + "/");
        }

        synchronized void up() throws java.io.IOException {
            java.net.ServerSocket s = new java.net.ServerSocket();
            s.setReuseAddress(true);
            s.bind(new java.net.InetSocketAddress("localhost", port));
            server = s;
            Thread accept = new Thread(() -> {
                while (!s.isClosed()) {
                    try {
                        java.net.Socket client = s.accept();
                        java.net.Socket db = new java.net.Socket(POSTGRES.getHost(), POSTGRES.getFirstMappedPort());
                        open.add(client);
                        open.add(db);
                        pipe(client, db);
                        pipe(db, client);
                    } catch (java.io.IOException closed) {
                        return;
                    }
                }
            });
            accept.setDaemon(true);
            accept.start();
        }

        private void pipe(java.net.Socket from, java.net.Socket to) {
            Thread t = new Thread(() -> {
                try {
                    from.getInputStream().transferTo(to.getOutputStream());
                } catch (java.io.IOException ignored) {
                    // one side closed
                } finally {
                    try {
                        from.close();
                        to.close();
                    } catch (java.io.IOException ignored) {
                        // closing
                    }
                }
            });
            t.setDaemon(true);
            t.start();
        }

        synchronized void down() throws java.io.IOException {
            server.close();
            for (java.net.Socket s : open) {
                try {
                    s.setSoLinger(true, 0);
                    s.close();
                } catch (java.io.IOException alreadyClosed) {
                    // the other side went first
                }
            }
            open.clear();
        }

        @Override
        public void close() throws java.io.IOException {
            down();
        }
    }

    /**
     * The database really goes away under succeed() (00cfc58 N1): the driver's own failure and
     * Spring's own translation of it are what the runner meets, not an exception a test made
     * up. It must land in mayHeal, be retried, and end in one version once the database is back.
     */
    @Test
    public void aRealDatabaseOutageAtSuccessIsRetriedUntilItReturns() throws Exception {
        UUID build = queued();
        try (Outage outage = new Outage()) {
            JdbcTemplate through = new JdbcTemplate(new DriverManagerDataSource(outage.url(), POSTGRES.getUsername(),
                POSTGRES.getPassword()));
            BuildCoordinator coordinator = new BuildCoordinator(through, ONE);
            java.util.List<Throwable> met = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
            AtomicInteger tries = new AtomicInteger();
            Thread restore = new Thread(() -> {
                try {
                    Thread.sleep(2500);
                    outage.up();
                } catch (InterruptedException | java.io.IOException e) {
                    met.add(e);
                }
            });
            BuildRunner runner = new BuildRunner(coordinator, ONE, VERIFIED, (lease, runtime) -> {
                if (tries.incrementAndGet() == 1) {
                    try {
                        outage.down();
                    } catch (java.io.IOException e) {
                        throw new IllegalStateException(e);
                    }
                    restore.start();
                }
                try {
                    coordinator.succeed(lease, runtime);
                } catch (RuntimeException e) {
                    met.add(e);
                    throw e;
                }
            });
            try {
                BuildRunner.Result result = runner.runOnce("n1", BUILT);
                assertEquals(BuildRunner.Result.SUCCEEDED, result, "met: " + met);
            } finally {
                restore.join(30_000);
            }
            assertTrue(!met.isEmpty(), "the outage was never met");
            for (Throwable e : met) {
                assertTrue(BuildRunner.mayHeal(e), "a real outage read as a refusal: " + e);
            }
            assertTrue(tries.get() >= 2, "retried");
        }
        assertEquals("SUCCEEDED", state(build));
        assertEquals(1, versions(build));
    }

    /**
     * The worker dies between the complete log and the success: nothing calls succeed(), the
     * lease lapses, and the reaper makes the attempt INTERRUPTED with no version -- a verified,
     * logged image does not become a version by itself.
     */
    @Test
    public void aWorkerKilledBetweenTheLogAndTheSuccessLeavesNoVersion() {
        UUID build = queued();
        BuildCoordinator coordinator = new BuildCoordinator(jdbc, ONE);
        Lease lease = coordinator.claim("n1").orElseThrow().lease();
        coordinator.toPublishing(lease, DIGEST, PROVENANCE);
        coordinator.markLogComplete(lease, 3);
        // ...and the process is gone: no renewal, no succeed().
        jdbc.update("UPDATE skald.build SET lease_expires_at = now() - interval '1 second' WHERE id = ?", build);
        assertEquals(1, coordinator.reap());
        assertEquals("INTERRUPTED", state(build));
        assertEquals(0, versions(build));
        assertEquals(false, coordinator.succeededUnder(lease));
    }

    /** A push or log failure (the publication check, or the driver) is FAILED and never a version. */
    @Test
    public void aFailedPublicationOrBuildMakesNoVersion() {
        BuildCoordinator coordinator = new BuildCoordinator(jdbc, ONE);
        UUID unverified = queued();
        assertEquals(BuildRunner.Result.FAILED, new BuildRunner(coordinator, ONE, (b, image) -> {
            throw new IllegalStateException("the registry has no such manifest");
        }).runOnce("n1", BUILT));
        assertEquals("PUBLISH_VERIFY", jdbc.queryForObject("SELECT error_code FROM skald.build WHERE id = ?",
            String.class, unverified));
        assertEquals(0, versions(unverified));
        UUID failed = queued();
        assertEquals(BuildRunner.Result.FAILED, new BuildRunner(coordinator, ONE, VERIFIED).runOnce("n1",
            (b, stop) -> new BuildDriver.Failed("LOG_FAILED", "the log could not be written", PROVENANCE)));
        assertEquals("FAILED", state(failed));
        assertEquals(0, versions(failed));
    }

    /** What may heal, and what may not: the classes the retry is limited to. */
    @Test
    public void onlyAFailureThatMayHealIsRetried() {
        assertTrue(BuildRunner.mayHeal(new DataAccessResourceFailureException("down")));
        assertTrue(BuildRunner.mayHeal(new org.springframework.jdbc.CannotGetJdbcConnectionException("no connection")));
        assertTrue(BuildRunner.mayHeal(new org.springframework.dao.TransientDataAccessResourceException("blip")));
        assertTrue(BuildRunner.mayHeal(new org.springframework.dao.RecoverableDataAccessException("recover")));
        assertTrue(BuildRunner.mayHeal(new org.springframework.dao.PessimisticLockingFailureException("deadlock")));
        assertTrue(BuildRunner.mayHeal(new org.springframework.transaction.CannotCreateTransactionException("no tx")));
        assertTrue(BuildRunner.mayHeal(new org.springframework.transaction.TransactionSystemException("commit failed")));
        assertEquals(false, BuildRunner.mayHeal(new org.springframework.dao.DataIntegrityViolationException("no")));
        assertEquals(false, BuildRunner.mayHeal(new org.springframework.jdbc.BadSqlGrammarException("x", "SELECT",
            new java.sql.SQLException("bad"))));
        assertEquals(false, BuildRunner.mayHeal(new IllegalStateException("the ledger holds no image")));
    }

    /** The lost-reply check is asked again while the database is still unwell (2fb308e N1). */
    @Test
    public void theLostReplyCheckIsRetriedToo() {
        UUID build = queued();
        AtomicInteger checks = new AtomicInteger();
        // The coordinator's own query for "is this lease's attempt SUCCEEDED" meets the same
        // unwell database once, as the lost reply did.
        JdbcTemplate flaky = new JdbcTemplate(jdbc.getDataSource()) {
            @Override
            public <T> T queryForObject(String sql, Class<T> type, Object... args) {
                if (sql.contains("state = 'SUCCEEDED'") && checks.incrementAndGet() == 1) {
                    throw new DataAccessResourceFailureException("injected: the database is still unwell");
                }
                return super.queryForObject(sql, type, args);
            }
        };
        BuildCoordinator coordinator = new BuildCoordinator(flaky, ONE);
        AtomicInteger tries = new AtomicInteger();
        BuildRunner runner = new BuildRunner(coordinator, ONE, VERIFIED, (lease, runtime) -> {
            coordinator.succeed(lease, runtime);
            if (tries.incrementAndGet() == 1) {
                throw new DataAccessResourceFailureException("injected: the commit's reply was lost");
            }
        });
        assertEquals(BuildRunner.Result.SUCCEEDED, runner.runOnce("n1", BUILT));
        assertEquals(2, checks.get(), "asked again after the first check failed");
        assertEquals(1, versions(build));
    }

    /** Only the lease decides: a cancel requested during RUNNING does not abandon the retries. */
    @Test
    public void aCancelRequestDoesNotAbandonTheRetriesOfASuccess() {
        UUID build = queued();
        UUID content = jdbc.queryForObject("SELECT content_id FROM skald.build WHERE id = ?", UUID.class, build);
        BuildCoordinator coordinator = new BuildCoordinator(jdbc, ONE);
        BuildDriver builtAnyway = (b, stop) -> {
            coordinator.cancel(content, b.buildId(), "alice");
            // Wait until the runner's heartbeat has seen the request.
            long until = System.nanoTime() + Duration.ofSeconds(60).toNanos();
            while (!stop.getAsBoolean() && System.nanoTime() < until) {
                Thread.sleep(50);
            }
            assertTrue(stop.getAsBoolean(), "the stop signal never carried the cancel");
            return new BuildDriver.Built(DIGEST, PROVENANCE);
        };
        AtomicInteger tries = new AtomicInteger();
        BuildRunner runner = new BuildRunner(coordinator, SHORT, VERIFIED, (lease, runtime) -> {
            if (tries.incrementAndGet() <= 2) {
                throw new DataAccessResourceFailureException("injected: the database is unavailable");
            }
            coordinator.succeed(lease, runtime);
        });
        assertEquals(BuildRunner.Result.SUCCEEDED, runner.runOnce("n1", builtAnyway));
        assertEquals(1, versions(build));
    }

    @Test
    public void aDatabaseThatStaysAwayLeavesTheAttemptToTheReaperWithNoVersion() {
        UUID build = queued();
        BuildCoordinator coordinator = new BuildCoordinator(jdbc, SHORT);
        BuildRunner runner = new BuildRunner(coordinator, SHORT, VERIFIED, (lease, runtime) -> {
            // As a long outage looks once it ends: the lease ran out meanwhile.
            jdbc.update("UPDATE skald.build SET lease_expires_at = now() - interval '1 second' WHERE id = ?",
                lease.buildId());
            throw new DataAccessResourceFailureException("injected: the database is unavailable");
        });
        long t0 = System.nanoTime();
        assertEquals(BuildRunner.Result.INTERRUPTED, runner.runOnce("n1", BUILT));
        assertTrue(System.nanoTime() - t0 < Duration.ofSeconds(30).toNanos(), "it gave up with the lease");
        assertEquals("PUBLISHING", state(build), "never FAILED: that needs the database");
        assertEquals(1, coordinator.reap());
        assertEquals("INTERRUPTED", state(build));
        assertEquals(0, versions(build));
    }

    @Test
    public void aSuccessWhoseReplyWasLostIsRecognisedNotDoubled() {
        UUID build = queued();
        BuildCoordinator coordinator = new BuildCoordinator(jdbc, ONE);
        AtomicInteger tries = new AtomicInteger();
        BuildRunner runner = new BuildRunner(coordinator, ONE, VERIFIED, (lease, runtime) -> {
            coordinator.succeed(lease, runtime);
            if (tries.incrementAndGet() == 1) {
                throw new DataAccessResourceFailureException("injected: the commit's reply was lost");
            }
        });
        assertEquals(BuildRunner.Result.SUCCEEDED, runner.runOnce("n1", BUILT));
        assertEquals(1, tries.get(), "the replay was fenced by the first, committed success");
        assertEquals("SUCCEEDED", state(build));
        assertEquals(1, versions(build));
    }

    /** A fence on retry that is NOT our own success -- the attempt was reaped meanwhile -- stays a fence. */
    @Test
    public void aRetryFencedByTheReaperIsNotTakenForASuccess() {
        UUID build = queued();
        BuildCoordinator coordinator = new BuildCoordinator(jdbc, ONE);
        BuildRunner runner = new BuildRunner(coordinator, ONE, VERIFIED, (lease, runtime) -> {
            if (!"INTERRUPTED".equals(state(lease.buildId()))) {
                jdbc.update("UPDATE skald.build SET lease_expires_at = now() - interval '1 second' WHERE id = ?",
                    lease.buildId());
                assertEquals(1, coordinator.reap());
                throw new DataAccessResourceFailureException("injected: the database is unavailable");
            }
            coordinator.succeed(lease, runtime);
        });
        assertEquals(BuildRunner.Result.FENCED, runner.runOnce("n1", BUILT));
        assertEquals("INTERRUPTED", state(build));
        assertEquals(0, versions(build));
    }

    @Test
    public void aCancelThatLandsAfterPublishingIsTooLateAndTheVersionStands() {
        UUID build = queued();
        UUID content = jdbc.queryForObject("SELECT content_id FROM skald.build WHERE id = ?", UUID.class, build);
        BuildCoordinator coordinator = new BuildCoordinator(jdbc, ONE);
        // The driver ignores the stop and builds: the request was recorded while RUNNING.
        BuildDriver builtAnyway = (b, stop) -> {
            assertEquals(BuildCoordinator.CancelOutcome.REQUESTED, coordinator.cancel(content, b.buildId(), "alice"));
            return new BuildDriver.Built(DIGEST, PROVENANCE);
        };
        assertEquals(BuildRunner.Result.SUCCEEDED, new BuildRunner(coordinator, ONE, VERIFIED).runOnce("n1", builtAnyway));
        assertEquals("SUCCEEDED", state(build));
        assertEquals(1, versions(build));
        assertEquals(BuildCoordinator.CancelOutcome.TOO_LATE, coordinator.cancel(content, build, "alice"));
    }
}
