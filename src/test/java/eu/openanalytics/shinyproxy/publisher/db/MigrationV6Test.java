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
 * V6, a built version's runtime document (T9 (a)): a populated V5 database -- a direct-image
 * version and a build -- migrates untouched, and the CHECK then refuses a built version
 * without the document's schema. The CHECK's own cases are RuntimeSpecRepositoryTest's.
 */
public class MigrationV6Test {

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

    // ------------------------------------------------------------------ upgrade

    @Test
    public void aPopulatedV5DatabaseMigratesUntouched() throws Exception {
        JdbcTemplate old = database("5");
        UUID c = content(old);
        UUID b = validated(old, c);
        old.update("INSERT INTO skald.content_version (content_id, version, image, created_by) VALUES (?, 1,"
            + " 'openanalytics/shinyproxy-demo', 'alice')", c);
        UUID build = UUID.randomUUID();
        old.update("INSERT INTO skald.build (id, content_id, bundle_id, created_by, idempotency_key,"
            + " input_fingerprint) VALUES (?, ?, ?, 'alice', 'k', ?)", build, c, b, "f".repeat(64));
        String before = old.queryForObject("SELECT string_agg(v::text, '|' ORDER BY id) FROM skald.content_version v",
            String.class);
        String url;
        try (var conn = old.getDataSource().getConnection()) {
            url = conn.getMetaData().getURL();
        }
        migrate(url, "6");
        assertEquals("6", old.queryForObject("SELECT max(version::int)::text FROM skald.flyway_schema_history",
            String.class));
        assertEquals(before, old.queryForObject("SELECT string_agg(v::text, '|' ORDER BY id) FROM skald.content_version v",
            String.class));
        // A direct-image version keeps {}; a built one needs its runtime document's schema.
        old.update("INSERT INTO skald.content_version (content_id, version, image, created_by) VALUES (?, 2,"
            + " 'x', 'alice')", c);
        refusedBy("content_version_built_has_runtime_spec", () -> old.update("INSERT INTO skald.content_version"
            + " (content_id, version, image, created_by, build_id) VALUES (?, 3, 'x', 'alice', ?)", c, build));
    }
}
