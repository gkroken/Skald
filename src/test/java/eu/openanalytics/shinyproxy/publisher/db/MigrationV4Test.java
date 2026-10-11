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
package eu.openanalytics.shinyproxy.publisher.db;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V4, the binary queue's tables: each constraint and the immutability trigger refuse their
 * own case, and a populated V3 database migrates untouched. One PostgreSQL container, one
 * migrated database; every row has its own random identity, so the cases do not interact.
 */
public class MigrationV4Test {

    @SuppressWarnings("resource")
    private static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>("postgres:16").withDatabaseName("skald");

    private static final String KEY = "noble-x86_64/4.6";
    private static final String SHA = "a".repeat(64);
    private static final String MD5 = "b".repeat(32);
    private static JdbcTemplate jdbc;

    @BeforeAll
    public static void beforeAll() {
        POSTGRES.start();
        jdbc = database(null);
    }

    @AfterAll
    public static void afterAll() {
        POSTGRES.stop();
    }

    private static JdbcTemplate database(String target) {
        String name = "t" + UUID.randomUUID().toString().replace("-", "");
        new JdbcTemplate(source(POSTGRES.getJdbcUrl())).execute("CREATE DATABASE " + name);
        String url = POSTGRES.getJdbcUrl().replace("/skald", "/" + name);
        migrate(url, target);
        return new JdbcTemplate(source(url));
    }

    private static DriverManagerDataSource source(String url) {
        return new DriverManagerDataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static void migrate(String url, String target) {
        var config = Flyway.configure()
            .dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword())
            .schemas("skald").defaultSchema("skald")
            .locations("classpath:db/migration");
        if (target != null) {
            config = config.target(target);
        }
        config.load().migrate();
    }

    /** A fresh package name, so rows never collide on the identity. */
    private static String pkg() {
        return "p" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    /** A PENDING_SOURCE row on {@code key}. */
    private static UUID pending(String key) {
        return jdbc.queryForObject("INSERT INTO skald.binary_build (platform_key, package, version)"
            + " VALUES (?, ?, '1.0') RETURNING id", UUID.class, key, pkg());
    }

    /** Inserts a row with {@code columns} = {@code values} (SQL literals) besides its identity. */
    private static void insert(String columns, String values) {
        jdbc.update("INSERT INTO skald.binary_build (platform_key, package, version, " + columns
            + ") VALUES ('" + KEY + "', '" + pkg() + "', '1.0', " + values + ")");
    }

    private static void refusedBy(String constraint, Runnable statement) {
        DataIntegrityViolationException ex =
            assertThrows(DataIntegrityViolationException.class, statement::run, constraint);
        assertTrue(String.valueOf(ex.getMessage()).contains(constraint),
            "refused, but not by " + constraint + ": " + ex.getMessage());
    }

    private static void accepted(String key, String pkg, String version) {
        jdbc.update("INSERT INTO skald.binary_build (platform_key, package, version) VALUES (?, ?, ?)",
            key, pkg + pkg().substring(1), version);
    }

    // ------------------------------------------------------------------ forms

    @Test
    public void aPlatformKeyIsForgesFormAndNothingElse() {
        accepted("noble-x86_64/4.6", "a", "1.0");
        accepted("trixie-aarch64/10.12", "a", "1.0");
        for (String bad : new String[] {"noble-x86_64", "Noble-x86_64/4.6", "noble-arm64/4.6",
                "noble-x86_64/4", "noble-x86_64/4.6.1", "noble-x86_64/4.6/x", "../x86_64/4.6",
                "n-x86_64/4.6", "noble_x-x86_64/4.6", "noble-x86_64/4.6 "}) {
            refusedBy("binary_build_key_form", () -> jdbc.update("INSERT INTO skald.binary_build"
                + " (platform_key, package, version) VALUES (?, 'abc', '1.0')", bad));
        }
    }

    @Test
    public void aPackageNameIsRsAndNothingElse() {
        accepted(KEY, "data.table", "1.0");
        accepted(KEY, "R6", "1.0");
        for (String bad : new String[] {"a", "1abc", "abc.", "a/b", "a b", "a_b", "../ab",
                ".ab", "a".repeat(101)}) {
            refusedBy("binary_build_package_form", () -> jdbc.update("INSERT INTO skald.binary_build"
                + " (platform_key, package, version) VALUES (?, ?, '1.0')", KEY, bad));
        }
    }

    @Test
    public void aVersionIsRsAndNothingElse() {
        accepted(KEY, "a", "1.0-2");
        accepted(KEY, "a", "2024.1.15");
        for (String bad : new String[] {"1", "1.0a", "1..0", "../1.0", "1.0/x", "v1.0", "1.0 ",
                "1." + "1".repeat(64)}) {
            refusedBy("binary_build_version_form", () -> jdbc.update("INSERT INTO skald.binary_build"
                + " (platform_key, package, version) VALUES (?, 'abc', ?)", KEY, bad));
        }
    }

    @Test
    public void aPlatformPackageAndVersionIsOneRow() {
        String p = pkg();
        jdbc.update("INSERT INTO skald.binary_build (platform_key, package, version) VALUES (?, ?, '1.0')", KEY, p);
        refusedBy("binary_build_identity", () -> jdbc.update("INSERT INTO skald.binary_build"
            + " (platform_key, package, version) VALUES (?, ?, '1.0')", KEY, p));
        jdbc.update("INSERT INTO skald.binary_build (platform_key, package, version) VALUES (?, ?, '1.0')",
            "trixie-x86_64/4.6", p);
    }

    // ------------------------------------------------------------------ states

    @Test
    public void anUnknownStateIsRefused() {
        refusedBy("binary_build_state_known", () -> insert("state", "'DONE'"));
    }

    @Test
    public void aFinishedRowHasAFinishTimeAndAWaitingOneNone() {
        refusedBy("binary_build_terminal_has_time", () -> insert("state, refusal_code", "'REFUSED', 'X'"));
        refusedBy("binary_build_terminal_has_time", () -> insert("finished_at", "now()"));
    }

    @Test
    public void aLeasedStateHasAWholeLease() {
        String lease = "'n', now(), now(), 1, now()";
        insert("state, lease_owner, lease_expires_at, deadline_at, lease_generation, started_at",
            "'VERIFYING', " + lease);
        refusedBy("binary_build_leased", () -> insert("state", "'VERIFYING'"));
        refusedBy("binary_build_leased", () -> insert(
            "state, lease_owner, lease_expires_at, deadline_at, lease_generation, started_at",
            "'VERIFYING', 'n', now(), now(), 0, now()"));
        refusedBy("binary_build_leased", () -> insert(
            "state, lease_owner, lease_expires_at, lease_generation, started_at",
            "'VERIFYING', 'n', now(), 1, now()"));
    }

    @Test
    public void aWaitingRowHoldsNoLease() {
        refusedBy("binary_build_leased", () -> insert("lease_owner", "'n'"));
        refusedBy("binary_build_leased", () -> insert("lease_expires_at", "now()"));
    }

    @Test
    public void aQueuedRowHasItsSourceDigests() {
        insert("state, source_sha256, source_md5", "'QUEUED', '" + SHA + "', '" + MD5 + "'");
        refusedBy("binary_build_verified_has_source", () -> insert("state", "'QUEUED'"));
        refusedBy("binary_build_verified_has_source", () -> insert("state, source_sha256",
            "'QUEUED', '" + SHA + "'"));
    }

    @Test
    public void anUploadedRowHasItsDigestAndProvenance() {
        String source = "'" + SHA + "', '" + MD5 + "', now()";
        insert("state, source_sha256, source_md5, finished_at, binary_sha256, provenance",
            "'UPLOADED', " + source + ", '" + SHA + "', '{\"schema\": \"s\"}'");
        refusedBy("binary_build_uploaded_is_recorded", () -> insert(
            "state, source_sha256, source_md5, finished_at, provenance",
            "'UPLOADED', " + source + ", '{\"schema\": \"s\"}'"));
        refusedBy("binary_build_uploaded_is_recorded", () -> insert(
            "state, source_sha256, source_md5, finished_at, binary_sha256",
            "'UPLOADED', " + source + ", '" + SHA + "'"));
        refusedBy("binary_build_uploaded_is_recorded", () -> insert(
            "state, source_sha256, source_md5, finished_at, binary_sha256, provenance",
            "'UPLOADED', " + source + ", '" + SHA + "', '{\"x\": 1}'"));
    }

    @Test
    public void aForeignBinaryHasItsDigest() {
        insert("state, finished_at, binary_sha256", "'PRESENT_FOREIGN', now(), '" + SHA + "'");
        refusedBy("binary_build_foreign_has_digest", () -> insert("state, finished_at",
            "'PRESENT_FOREIGN', now()"));
    }

    @Test
    public void aRefusalNamesItsCodeAndOnlyARefusalHasOne() {
        refusedBy("binary_build_refused_names_code", () -> insert("state, finished_at", "'REFUSED', now()"));
        refusedBy("binary_build_refused_names_code", () -> insert("refusal_code", "'X'"));
    }

    @Test
    public void aFailureNamesItsCode() {
        insert("state, finished_at, error_code", "'FAILED', now(), 'X'");
        refusedBy("binary_build_failed_names_code", () -> insert("state, finished_at", "'FAILED', now()"));
    }

    @Test
    public void digestsAreLowerCaseHex() {
        refusedBy("binary_build_digests_hex", () -> insert("source_sha256", "'" + "A".repeat(64) + "'"));
        refusedBy("binary_build_digests_hex", () -> insert("source_md5", "'" + "a".repeat(31) + "'"));
        refusedBy("binary_build_digests_hex", () -> insert("binary_sha256", "'sha256:" + SHA + "'"));
    }

    @Test
    public void attemptsAreNotNegative() {
        refusedBy("binary_build_attempts_nonnegative", () -> insert("attempts", "-1"));
    }

    // ------------------------------------------------------------------ what never changes

    @Test
    public void aRowsIdentityNeverChanges() {
        UUID id = pending(KEY);
        refusedBy("binary_build_identity_immutable", () -> jdbc.update(
            "UPDATE skald.binary_build SET version = '2.0' WHERE id = ?", id));
        refusedBy("binary_build_identity_immutable", () -> jdbc.update(
            "UPDATE skald.binary_build SET package = ? WHERE id = ?", pkg(), id));
        refusedBy("binary_build_identity_immutable", () -> jdbc.update(
            "UPDATE skald.binary_build SET platform_key = 'trixie-x86_64/4.6' WHERE id = ?", id));
    }

    @Test
    public void sourceDigestsAreRecordedOnceAndNeverChange() {
        UUID id = pending(KEY);
        jdbc.update("UPDATE skald.binary_build SET source_sha256 = ?, source_md5 = ? WHERE id = ?", SHA, MD5, id);
        refusedBy("binary_build_source_immutable", () -> jdbc.update(
            "UPDATE skald.binary_build SET source_sha256 = ? WHERE id = ?", "c".repeat(64), id));
        refusedBy("binary_build_source_immutable", () -> jdbc.update(
            "UPDATE skald.binary_build SET source_sha256 = NULL WHERE id = ?", id));
        refusedBy("binary_build_source_immutable", () -> jdbc.update(
            "UPDATE skald.binary_build SET source_md5 = ? WHERE id = ?", "c".repeat(32), id));
        jdbc.update("UPDATE skald.binary_build SET attempts = 3 WHERE id = ?", id);
        assertEquals(SHA, jdbc.queryForObject("SELECT source_sha256 FROM skald.binary_build WHERE id = ?",
            String.class, id));
    }

    @Test
    public void aBinaryDigestIsRecordedOnceAndNeverChanges() {
        UUID id = pending(KEY);
        jdbc.update("UPDATE skald.binary_build SET binary_sha256 = ? WHERE id = ?", SHA, id);
        refusedBy("binary_build_binary_immutable", () -> jdbc.update(
            "UPDATE skald.binary_build SET binary_sha256 = ? WHERE id = ?", "c".repeat(64), id));
    }

    // ------------------------------------------------------------------ edges

    @Test
    public void anEdgeJoinsTwoRowsOfOnePlatform() {
        UUID a = pending(KEY);
        UUID b = pending(KEY);
        UUID other = pending("trixie-x86_64/4.6");
        jdbc.update("INSERT INTO skald.binary_build_dep (binary_build_id, dependency_id, platform_key)"
            + " VALUES (?, ?, ?)", a, b, KEY);
        refusedBy("binary_build_dep_to", () -> jdbc.update("INSERT INTO skald.binary_build_dep"
            + " (binary_build_id, dependency_id, platform_key) VALUES (?, ?, ?)", a, other, KEY));
        refusedBy("binary_build_dep_from", () -> jdbc.update("INSERT INTO skald.binary_build_dep"
            + " (binary_build_id, dependency_id, platform_key) VALUES (?, ?, 'trixie-x86_64/4.6')", a, other));
        refusedBy("binary_build_dep_not_self", () -> jdbc.update("INSERT INTO skald.binary_build_dep"
            + " (binary_build_id, dependency_id, platform_key) VALUES (?, ?, ?)", a, a, KEY));
    }

    // ------------------------------------------------------------------ upgrade

    @Test
    public void aPopulatedV3DatabaseMigratesUntouched() throws Exception {
        JdbcTemplate old = database("3");
        UUID c = UUID.randomUUID();
        old.update("INSERT INTO skald.content (id, title, owner, type) VALUES (?, 't', 'alice', 'shiny')", c);
        String url;
        try (var conn = old.getDataSource().getConnection()) {
            url = conn.getMetaData().getURL();
        }
        migrate(url, null);
        assertEquals(1, old.queryForObject("SELECT count(*) FROM skald.content WHERE id = ?", Integer.class, c));
        assertEquals(0, old.queryForObject("SELECT count(*) FROM skald.binary_build", Integer.class));
        assertEquals("4", old.queryForObject("SELECT max(version::int)::text FROM skald.flyway_schema_history",
            String.class));
    }
}
