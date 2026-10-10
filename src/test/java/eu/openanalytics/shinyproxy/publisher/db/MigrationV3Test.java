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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V3, the build record's provenance: against a FRESH database and a POPULATED V2 one. Each
 * test gets its own database in one PostgreSQL container.
 */
public class MigrationV3Test {

    @SuppressWarnings("resource")
    private static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>("postgres:16").withDatabaseName("skald");

    private static final String IMAGE = "'registry:5000/x@sha256:' || repeat('b', 64)";

    @BeforeAll
    public static void beforeAll() {
        POSTGRES.start();
    }

    @AfterAll
    public static void afterAll() {
        POSTGRES.stop();
    }

    /** A new, empty database, migrated to {@code target} (null for the latest). */
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

    private static String urlOf(JdbcTemplate jdbc) {
        try (var c = jdbc.getDataSource().getConnection()) {
            return c.getMetaData().getURL();
        } catch (java.sql.SQLException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static UUID content(JdbcTemplate jdbc) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO skald.content (id, title, owner, type) VALUES (?, 't', 'alice', 'shiny')", id);
        return id;
    }

    private static UUID bundle(JdbcTemplate jdbc, UUID content) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO skald.bundle (id, content_id, created_by, upload_deadline)"
            + " VALUES (?, ?, 'alice', now() + interval '1 hour')", id, content);
        return id;
    }

    /** A FAILED build, with an image or without. */
    private static UUID failed(JdbcTemplate jdbc, UUID content, UUID bundle, boolean withImage) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO skald.build (id, content_id, bundle_id, created_by, idempotency_key,"
            + " input_fingerprint, state, finished_at, output_image) VALUES (?, ?, ?, 'alice', ?, ?,"
            + " 'FAILED', now(), " + (withImage ? IMAGE : "NULL") + ")",
            id, content, bundle, id.toString(), "f".repeat(64));
        return id;
    }

    private static String provenance(JdbcTemplate jdbc, UUID build) {
        return jdbc.queryForObject("SELECT provenance::text FROM skald.build WHERE id = ?", String.class, build);
    }

    private static void refusedBy(String constraint, Runnable statement) {
        DataIntegrityViolationException ex =
            assertThrows(DataIntegrityViolationException.class, statement::run, constraint);
        assertTrue(String.valueOf(ex.getMessage()).contains(constraint),
            "refused, but not by " + constraint + ": " + ex.getMessage());
    }

    @Test
    public void aPopulatedV2DatabaseMarksItsImagesAsUnrecordedAndNothingElse() {
        JdbcTemplate jdbc = database("2");
        UUID c = content(jdbc);
        UUID b = bundle(jdbc, c);
        UUID withImage = failed(jdbc, c, b, true);
        UUID without = failed(jdbc, c, b, false);

        migrate(urlOf(jdbc), null);

        assertEquals("{\"schema\": \"skald.build-provenance/unrecorded\"}", provenance(jdbc, withImage),
            "a pre-V3 image is marked as built before provenance was recorded");
        assertNull(provenance(jdbc, without), "a build without an image gained provenance");
        assertEquals("{}", jdbc.queryForObject("SELECT effective_limits::text FROM skald.build WHERE id = ?",
            String.class, withImage), "effective_limits changed across V3");
    }

    @Test
    public void aBuildWithAnImageHasProvenance() {
        JdbcTemplate jdbc = database(null);
        UUID c = content(jdbc);
        UUID b = bundle(jdbc, c);
        refusedBy("build_image_has_provenance", () -> failed(jdbc, c, b, true));
        UUID build = failed(jdbc, c, b, false);
        refusedBy("build_image_has_provenance", () -> jdbc.update(
            "UPDATE skald.build SET output_image = " + IMAGE + " WHERE id = ?", build));
        jdbc.update("UPDATE skald.build SET output_image = " + IMAGE + ", provenance = '{\"schema\": \"x\"}'"
            + " WHERE id = ?", build);
        refusedBy("build_image_has_provenance", () -> jdbc.update(
            "UPDATE skald.build SET provenance = NULL WHERE id = ?", build));
    }

    @Test
    public void provenanceIsARecordWithAStringSchema() {
        JdbcTemplate jdbc = database(null);
        UUID c = content(jdbc);
        UUID build = failed(jdbc, c, bundle(jdbc, c), false);
        for (String bad : new String[] {"[]", "\"x\"", "{}", "{\"schema\": 1}", "{\"schema\": null}"}) {
            refusedBy("build_provenance_is_a_record", () -> jdbc.update(
                "UPDATE skald.build SET provenance = ?::jsonb WHERE id = ?", bad, build));
        }
        jdbc.update("UPDATE skald.build SET provenance = '{\"schema\": \"x\"}' WHERE id = ?", build);
        assertEquals("{\"schema\": \"x\"}", provenance(jdbc, build));
    }
}
