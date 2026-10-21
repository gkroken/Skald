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
package eu.openanalytics.shinyproxy.publisher.registry;

import eu.openanalytics.containerproxy.model.spec.ContainerSpec;
import eu.openanalytics.containerproxy.model.spec.ProxySpec;
import eu.openanalytics.containerproxy.service.ProxyService;
import eu.openanalytics.shinyproxy.publisher.db.TestPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How a version's runtime document reaches the container it starts (WORKPLAN-BUNDLES.md T9
 * (a)), against a real PostgreSQL with every migration applied: a legacy direct-image
 * version runs exactly as before; a built version runs as its document says, with the
 * deployment's limits; a built version without a valid document or a digest-pinned image is
 * not served at all; and V6 refuses to store a built version without one.
 */
public class RuntimeSpecRepositoryTest {

    @SuppressWarnings("resource")
    private static final PostgreSQLContainer<?> POSTGRES = TestPostgres.create();
    private static final String PINNED = "registry:5000/skald/content/c@sha256:" + "a".repeat(64);
    private static final String DOC =
        "{\"schema\":\"skald.runtime/v1\",\"port\":3838,\"user\":\"999:998\",\"language\":\"r\","
            + "\"base\":\"r-shiny-4.6.1-amd64-r3\"}";
    private static JdbcTemplate jdbc;
    private static ContentSpecRepository repository;

    @BeforeAll
    public static void beforeAll() {
        POSTGRES.start();
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .schemas("skald").defaultSchema("skald").locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
            POSTGRES.getPassword()));
        repository = new ContentSpecRepository(jdbc, new StaticListableBeanFactory().getBeanProvider(ProxyService.class),
            new RuntimeSettings("512m", "0.5"));
    }

    @AfterAll
    public static void afterAll() {
        POSTGRES.stop();
    }

    @BeforeEach
    public void clean() {
        jdbc.update("DELETE FROM skald.content");
    }

    private static UUID content() {
        UUID c = UUID.randomUUID();
        jdbc.update("INSERT INTO skald.content (id, title, owner, type) VALUES (?, 't', 'alice', 'shiny')", c);
        return c;
    }

    /** A finished build of {@code c}, which a version may name. */
    private static UUID build(UUID c) {
        UUID b = UUID.randomUUID();
        jdbc.update("INSERT INTO skald.bundle (id, content_id, created_by, state, upload_deadline, finished_at,"
            + " object_key, object_sha256, object_bytes, manifest_sha256, inventory_files, inventory_bytes)"
            + " VALUES (?, ?, 'alice', 'VALIDATED', now(), now(), ?, ?, 1, ?, 1, 1)",
            b, c, "k/" + b, "a".repeat(64), "b".repeat(64));
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO skald.build (id, content_id, bundle_id, created_by, idempotency_key,"
            + " input_fingerprint, state, finished_at) VALUES (?, ?, ?, 'alice', ?, ?, 'FAILED', now())",
            id, c, b, id.toString(), "f".repeat(64));
        return id;
    }

    /** Version {@code v} of {@code c}, made active. */
    private static String version(UUID c, int v, String image, String spec, UUID build) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO skald.content_version (id, content_id, version, image, spec_json, created_by,"
            + " build_id) VALUES (?, ?, ?, ?, ?::jsonb, 'alice', ?)", id, c, v, image, spec, build);
        jdbc.update("UPDATE skald.content SET active_version_id = ? WHERE id = ?", id, c);
        return ContentSpecRepository.specId(c, v);
    }

    private static ContainerSpec container(ProxySpec spec) {
        return spec.getContainerSpecs().get(0);
    }

    private static boolean listed(String specId) {
        return repository.findActiveSpecs().stream().anyMatch(s -> s.getId().equals(specId));
    }

    @Test
    public void aDirectImageVersionRunsExactlyAsBefore() {
        String id = version(content(), 1, "openanalytics/shinyproxy-demo", "{}", null);
        ContainerSpec c = container(repository.findSpec(id));
        assertEquals("openanalytics/shinyproxy-demo", c.getImage().getOriginalValue());
        assertEquals(3838, c.getPortMapping().get(0).getPort());
        assertNull(c.getDockerUser().getOriginalValue(), "no user override");
        assertNull(c.getMemoryLimit().getOriginalValue(), "no limits on an admin's image");
        assertNull(c.getCpuLimit().getOriginalValue());
        assertTrue(listed(id));
    }

    @Test
    public void aBuiltVersionRunsAsItsDocumentSaysWithTheDeploymentsLimits() {
        UUID c = content();
        UUID b = build(c);
        String id = version(c, 1, PINNED, DOC.replace("3838", "8000"), b);
        ProxySpec spec = repository.findSpec(id);
        ContainerSpec cs = container(spec);
        assertEquals(PINNED, cs.getImage().getOriginalValue());
        assertEquals(8000, cs.getPortMapping().get(0).getPort());
        assertEquals("999:998", cs.getDockerUser().getOriginalValue());
        assertEquals("512m", cs.getMemoryLimit().getOriginalValue());
        assertEquals("0.5", cs.getCpuLimit().getOriginalValue());
        assertEquals(Map.of("eu.skald.content", c.toString(), "eu.skald.version", "1", "eu.skald.build", b.toString()),
            cs.getLabels().getOriginalValue());
        assertTrue(listed(id));
        assertSame(spec, repository.findSpec(id), "an unchanged row is the same ProxySpec (the index needs it)");
        jdbc.update("UPDATE skald.content_version SET spec_json = ?::jsonb WHERE content_id = ?",
            DOC.replace("999:998", "1000:1000"), c);
        ProxySpec changed = repository.findSpec(id);
        assertNotSame(spec, changed, "a changed document is a new ProxySpec");
        assertEquals("1000:1000", container(changed).getDockerUser().getOriginalValue());
    }

    /** Each a row V6 lets through (schema string right) that the repository still refuses. */
    @Test
    public void aBuiltVersionWithoutAValidDocumentOrAPinnedImageIsNotServed() {
        String[][] rows = {
            {PINNED, DOC.replace("999:998", "0:0")},
            {PINNED, DOC.replace("3838", "80")},
            {PINNED, DOC.replace("}", ",\"privileged\":true}")},
            {PINNED, DOC.replace(",\"base\":\"r-shiny-4.6.1-amd64-r3\"", "")},
            {"registry:5000/skald/content/c:latest", DOC},
            {"registry:5000/skald/content/c", DOC},
        };
        for (String[] row : rows) {
            UUID c = content();
            String id = version(c, 1, row[0], row[1], build(c));
            assertNull(repository.findSpec(id), row[0] + " " + row[1]);
            assertFalse(listed(id), row[0] + " " + row[1]);
        }
    }

    @Test
    public void aRefusedVersionThatIsFixedIsServedAgain() {
        UUID c = content();
        String id = version(c, 1, PINNED, DOC.replace("999:998", "0:0"), build(c));
        assertNull(repository.findSpec(id));
        jdbc.update("UPDATE skald.content_version SET spec_json = ?::jsonb WHERE content_id = ?", DOC, c);
        assertEquals("999:998", container(repository.findSpec(id)).getDockerUser().getOriginalValue());
    }

    @Test
    public void theDatabaseRefusesABuiltVersionWithoutARuntimeDocument() {
        UUID c = content();
        UUID b = build(c);
        DataIntegrityViolationException e = assertThrows(DataIntegrityViolationException.class,
            () -> version(c, 1, PINNED, "{}", b));
        assertTrue(String.valueOf(e.getMessage()).contains("content_version_built_has_runtime_spec"), e.getMessage());
        assertThrows(DataIntegrityViolationException.class,
            () -> version(c, 1, PINNED, "{\"schema\":\"skald.runtime/v2\"}", b));
        // A direct image keeps {}.
        version(c, 1, "openanalytics/shinyproxy-demo", "{}", null);
    }

    /**
     * The repository's own rule, not only the CHECK's: legacy is decided by build_id, so a built
     * version with {} is refused even if a database without V6's constraint stored one.
     */
    @Test
    public void aBuiltVersionWithAnEmptyDocumentIsRefusedWithoutTheDatabasesHelp() {
        jdbc.execute("ALTER TABLE skald.content_version DROP CONSTRAINT content_version_built_has_runtime_spec");
        try {
            UUID c = content();
            String id = version(c, 1, PINNED, "{}", build(c));
            assertNull(repository.findSpec(id));
            assertFalse(listed(id));
            jdbc.update("DELETE FROM skald.content");
        } finally {
            jdbc.execute("ALTER TABLE skald.content_version ADD CONSTRAINT content_version_built_has_runtime_spec"
                + " CHECK (build_id IS NULL OR COALESCE(spec_json ->> 'schema' = 'skald.runtime/v1', false))");
        }
    }
}
