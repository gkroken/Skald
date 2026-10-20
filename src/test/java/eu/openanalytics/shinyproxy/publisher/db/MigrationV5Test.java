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

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V5, a VALIDATING bundle's lease: each new constraint refuses its own case, and a populated
 * V4 database -- a bundle in every state V4 code could leave, and a build -- migrates
 * untouched (a709e98 N2: a test of the argument that no pre-V5 row can meet V5's CHECKs).
 */
public class MigrationV5Test {

    @SuppressWarnings("resource")
    private static final PostgreSQLContainer<?> POSTGRES =
        TestPostgres.create();

    private static final String SHA = "a".repeat(64);
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

    private static UUID content(JdbcTemplate db) {
        UUID id = UUID.randomUUID();
        db.update("INSERT INTO skald.content (id, title, owner, type) VALUES (?, 't', 'alice', 'shiny')", id);
        return id;
    }

    /** An UPLOADING bundle; the cases below move it where they need it. */
    private static UUID uploading(JdbcTemplate db, UUID content) {
        UUID id = UUID.randomUUID();
        db.update("INSERT INTO skald.bundle (id, content_id, created_by, upload_deadline)"
            + " VALUES (?, ?, 'alice', now() + interval '1 hour')", id, content);
        return id;
    }

    private static UUID validated(JdbcTemplate db, UUID content) {
        UUID id = uploading(db, content);
        db.update("UPDATE skald.bundle SET state = 'VALIDATED', finished_at = now(), object_key = ?,"
            + " object_sha256 = ?, object_bytes = 10, manifest_sha256 = ?, inventory_files = 1,"
            + " inventory_bytes = 5 WHERE id = ?", "k/" + id, SHA, SHA, id);
        return id;
    }

    private static void refusedBy(String constraint, Runnable statement) {
        DataIntegrityViolationException ex =
            assertThrows(DataIntegrityViolationException.class, statement::run, constraint);
        assertTrue(String.valueOf(ex.getMessage()).contains(constraint),
            "refused, but not by " + constraint + ": " + ex.getMessage());
    }

    // ------------------------------------------------------------------ constraints

    @Test
    public void aLeaseExistsOnlyWhileValidating() {
        UUID b = uploading(jdbc, content(jdbc));
        refusedBy("bundle_lease_only_validating", () -> jdbc.update("UPDATE skald.bundle SET"
            + " validation_owner = 'v', validation_lease_expires_at = now() WHERE id = ?", b));
    }

    @Test
    public void anOwnerAndItsLeaseGoTogether() {
        UUID b = uploading(jdbc, content(jdbc));
        jdbc.update("UPDATE skald.bundle SET state = 'VALIDATING', validating_at = now(), object_key = ?,"
            + " object_sha256 = ?, object_bytes = 10 WHERE id = ?", "k/" + b, SHA, b);
        refusedBy("bundle_owner_has_lease", () -> jdbc.update("UPDATE skald.bundle SET"
            + " validation_owner = 'v' WHERE id = ?", b));
        refusedBy("bundle_owner_has_lease", () -> jdbc.update("UPDATE skald.bundle SET"
            + " validation_lease_expires_at = now() WHERE id = ?", b));
        jdbc.update("UPDATE skald.bundle SET validation_owner = 'v', validation_lease_expires_at = now()"
            + " WHERE id = ?", b);
    }

    @Test
    public void aValidatingRowCarriesItsTimeAndItsArchive() {
        UUID b = uploading(jdbc, content(jdbc));
        refusedBy("bundle_validating_has_time", () -> jdbc.update("UPDATE skald.bundle SET"
            + " state = 'VALIDATING', object_key = ?, object_sha256 = ?, object_bytes = 10 WHERE id = ?",
            "k/" + b, SHA, b));
        refusedBy("bundle_validating_has_archive", () -> jdbc.update("UPDATE skald.bundle SET"
            + " state = 'VALIDATING', validating_at = now(), object_key = ?, object_bytes = 10 WHERE id = ?",
            "k/" + b, b));
        refusedBy("bundle_validating_has_archive", () -> jdbc.update("UPDATE skald.bundle SET"
            + " state = 'VALIDATING', validating_at = now(), object_sha256 = ?, object_bytes = 10 WHERE id = ?",
            SHA, b));
        refusedBy("bundle_validating_has_archive", () -> jdbc.update("UPDATE skald.bundle SET"
            + " state = 'VALIDATING', validating_at = now(), object_key = ?, object_sha256 = ? WHERE id = ?",
            "k/" + b, SHA, b));
    }

    @Test
    public void theCountsAreNeverNegative() {
        UUID b = uploading(jdbc, content(jdbc));
        refusedBy("bundle_validation_counts_nonnegative", () -> jdbc.update("UPDATE skald.bundle SET"
            + " validation_claims = -1 WHERE id = ?", b));
        refusedBy("bundle_validation_counts_nonnegative", () -> jdbc.update("UPDATE skald.bundle SET"
            + " validation_generation = -1 WHERE id = ?", b));
    }

    // ------------------------------------------------------------------ upgrade

    @Test
    public void aPopulatedV4DatabaseMigratesUntouched() throws Exception {
        JdbcTemplate old = database("4");
        UUID c = content(old);
        UUID up = uploading(old, c);
        UUID ok = validated(old, c);
        UUID no = uploading(old, c);
        old.update("UPDATE skald.bundle SET state = 'REJECTED', finished_at = now(),"
            + " rejection_rule = 'PATH_TRAVERSAL', rejection_detail = 'd' WHERE id = ?", no);
        UUID build = UUID.randomUUID();
        old.update("INSERT INTO skald.build (id, content_id, bundle_id, created_by, idempotency_key,"
            + " input_fingerprint) VALUES (?, ?, ?, 'alice', 'k', ?)", build, c, ok, "f".repeat(64));
        String before = old.queryForObject("SELECT string_agg(b::text, '|' ORDER BY id) FROM skald.bundle b",
            String.class);
        String url;
        try (var conn = old.getDataSource().getConnection()) {
            url = conn.getMetaData().getURL();
        }
        migrate(url, "5");
        assertEquals("5", old.queryForObject("SELECT max(version::int)::text FROM skald.flyway_schema_history",
            String.class));
        for (UUID b : new UUID[] {up, ok, no}) {
            Map<String, Object> row = old.queryForMap("SELECT state, validating_at, validation_owner,"
                + " validation_lease_expires_at, validation_generation, validation_claims,"
                + " validation_storage_error, validation_retry_at FROM skald.bundle WHERE id = ?", b);
            assertNull(row.get("validating_at"), b.toString());
            assertNull(row.get("validation_owner"), b.toString());
            assertNull(row.get("validation_lease_expires_at"), b.toString());
            assertEquals(0L, row.get("validation_generation"), b.toString());
            assertEquals(0, row.get("validation_claims"), b.toString());
            assertNull(row.get("validation_storage_error"), b.toString());
            assertNull(row.get("validation_retry_at"), b.toString());
        }
        // Every V4 column of every row as it was: the row text minus the seven new columns.
        String after = old.queryForObject("SELECT string_agg((b.id, b.content_id, b.created_by, b.state,"
            + " b.object_key, b.object_sha256, b.object_bytes, b.manifest_sha256, b.inventory_files,"
            + " b.inventory_bytes, b.rejection_rule, b.rejection_detail, b.upload_deadline, b.created_at,"
            + " b.updated_at, b.finished_at)::text, '|' ORDER BY id) FROM skald.bundle b", String.class);
        assertEquals(before, after);
        assertEquals("QUEUED", old.queryForObject("SELECT state FROM skald.build WHERE id = ?", String.class, build));
    }
}
