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
import eu.openanalytics.shinyproxy.publisher.db.TestPostgres;
import eu.openanalytics.shinyproxy.publisher.recipe.BaseCatalog;
import eu.openanalytics.shinyproxy.publisher.recipe.RecipeGenerator.Base;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The hook over the queue and the gate (T7b step 2e): what a successful R build queues, and when. */
public class BinaryEnqueuerTest {

    @SuppressWarnings("resource")
    private static final PostgreSQLContainer<?> POSTGRES =
        TestPostgres.create();

    private static final String KEY = "noble-x86_64/4.6";
    private static final String R_ID = "r-shiny-4.6.1-amd64-r3";
    private static final String IMAGE_ID = "sha256:" + "a".repeat(64);
    private static JdbcTemplate jdbc;
    private static HttpServer server;
    private static volatile String registration;
    private static BaseCatalog catalog;

    @BeforeAll
    public static void start() throws Exception {
        POSTGRES.start();
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .schemas("skald").defaultSchema("skald").locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
            POSTGRES.getPassword()));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/cran/platforms/", exchange -> {
            byte[] b = registration == null ? new byte[0] : registration.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(registration == null ? 404 : 200, b.length == 0 ? -1 : b.length);
            if (b.length > 0) {
                exchange.getResponseBody().write(b);
            }
            exchange.close();
        });
        server.start();
        byte[] catalogBytes = Files.readAllBytes(Path.of("images/catalog.json"));
        String published = "{\"layout_version\":1,\"catalog_sha256\":\"" + HexFormat.of().formatHex(
            java.security.MessageDigest.getInstance("SHA-256").digest(catalogBytes)) + "\",\"registry\":\"reg:5000\","
            + "\"bases\":{\"" + R_ID + "\":\"reg:5000/skald/base/" + R_ID + "@sha256:" + "e".repeat(64) + "\"},"
            + "\"image_ids\":{\"" + R_ID + "\":\"" + IMAGE_ID + "\"}}";
        catalog = BaseCatalog.load(new ByteArrayInputStream(published.getBytes(StandardCharsets.UTF_8)));
    }

    @AfterAll
    public static void stop() {
        server.stop(0);
        POSTGRES.stop();
    }

    @BeforeEach
    public void clean() {
        jdbc.update("TRUNCATE skald.binary_build_upload, skald.binary_build_dep, skald.binary_build, skald.binary_lock");
        registration = "{\"key\":\"" + KEY + "\",\"rPlatform\":\"x86_64-pc-linux-gnu\",\"builderImage\":\"b\","
            + "\"builderDigest\":\"" + IMAGE_ID + "\"}";
    }

    private static BinaryEnqueuer enqueuer() {
        ForgeSettings forge = new ForgeSettings(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"),
            "cran-linux", "t", Duration.ofSeconds(0), Duration.ofSeconds(5));
        return new BinaryEnqueuer(new BinaryBuildQueue(jdbc, BinaryQueueSettings.DEFAULTS, CoordinatorSettings.DEFAULTS),
            new BinaryGate(new ForgePlatforms(forge)), catalog);
    }

    private static BaseCatalog.Resolved rBase() {
        return catalog.resolveEntry("shiny", "r", "4.6.1", "amd64").orElseThrow();
    }

    private static List<String> queued() {
        return jdbc.queryForList("SELECT package || ' ' || version FROM skald.binary_build ORDER BY package", String.class);
    }

    @Test
    public void aSuccessfulBuildQueuesItsLockButNotWhatTheBaseCarries() {
        UUID build = UUID.randomUUID();
        enqueuer().built(build, rBase(), Map.of("shiny", "1.9.1", "Matrix", "1.7-5", "MASS", "7.3-66"));
        assertEquals(List.of("MASS 7.3-66", "shiny 1.9.1"), queued(),
            "Matrix at the base's own version is not queued; MASS at another version is");
        assertEquals(build.toString(), jdbc.queryForObject("SELECT DISTINCT requested_by_build::text FROM"
            + " skald.binary_build", String.class));
        assertEquals("{\"MASS\": \"7.3-66\", \"shiny\": \"1.9.1\", \"Matrix\": \"1.7-5\"}".length(),
            jdbc.queryForObject("SELECT versions::text FROM skald.binary_lock WHERE build_id = ?", String.class, build)
                .length(), "the whole lock recorded");
        enqueuer().built(UUID.randomUUID(), rBase(), Map.of("shiny", "1.9.1"));
        assertEquals(2, queued().size(), "a version already queued is one row");
    }

    @Test
    public void builtDoesNotAskForgeAgain() {
        // a16cf2a N2: built() runs only for a build whose prepare-time decision was on; the
        // uploader rechecks the registration before anything leaves. A registration gone since
        // does not stop the queue (nothing would be uploaded for it).
        registration = null;
        enqueuer().built(UUID.randomUUID(), rBase(), Map.of("shiny", "1.9.1"));
        assertEquals(List.of("shiny 1.9.1"), queued());
        assertFalse(enqueuer().binariesFor(rBase()).binaries(), "the decision itself is the gate's");
    }

    @Test
    public void onePackageTheQueueRefusesDoesNotStopTheRestNorTheBuild() {
        // The bad entry FIRST (an ordered map), so a loop that stops at it queues nothing after.
        Map<String, String> lock = new java.util.LinkedHashMap<>();
        lock.put("bad name", "1.0");
        lock.put("shiny", "1.9.1");
        lock.put("later", "1.3.2");
        enqueuer().built(UUID.randomUUID(), rBase(), lock);
        assertEquals(List.of("later 1.3.2", "shiny 1.9.1"), queued());
    }

    @Test
    public void theGatesDecisionIsTheHooks() {
        assertTrue(enqueuer().binariesFor(rBase()).binaries());
    }
}
