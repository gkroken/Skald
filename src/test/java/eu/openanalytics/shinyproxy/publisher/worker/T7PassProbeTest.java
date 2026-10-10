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
package eu.openanalytics.shinyproxy.publisher.worker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.openanalytics.shinyproxy.publisher.build.BuildAdmission;
import eu.openanalytics.shinyproxy.publisher.build.BuildsConfig;
import eu.openanalytics.shinyproxy.publisher.bundle.BundleExtractor;
import eu.openanalytics.shinyproxy.publisher.bundle.BundleLimitsConfig;
import eu.openanalytics.shinyproxy.publisher.bundle.ExtractionLimits;
import eu.openanalytics.shinyproxy.publisher.db.DataSourceConfig;
import eu.openanalytics.shinyproxy.publisher.storage.BuildLogWriter;
import eu.openanalytics.shinyproxy.publisher.storage.BundleReceipt;
import eu.openanalytics.shinyproxy.publisher.storage.BundleWriter;
import eu.openanalytics.shinyproxy.publisher.storage.LogFinal;
import eu.openanalytics.shinyproxy.publisher.storage.ObjectKeys;
import eu.openanalytics.shinyproxy.publisher.storage.ObjectStorageConfig;
import eu.openanalytics.shinyproxy.publisher.storage.ObjectStore;
import eu.openanalytics.shinyproxy.publisher.storage.TestObjectStore;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T7's Pass probe, its Java half (WORKPLAN-BUNDLES.md T7: "actual built image with recorded
 * digest/provenance, useful failed-build log, bounded teardown, and cold runtime pull from
 * the chosen registry authority. No mocked build succeeded counts toward this task").
 *
 * <p>Runs only from {@code dev/t7-pass-probe.py}, which provides what a deployment would:
 * forge on the outer network as {@code forge:80}, an authenticated registry, and the trusted
 * bases published to it. Skipped in {@code make test} (no {@code skald.t7probe.registry}).
 *
 * <p>The worker is the WIRED one: the application's own configuration classes (the data
 * source, Flyway, object storage, bundle limits, builds, WorkerConfig) in one context, with
 * a deployment's properties; the loop, the reaper and the sweeper run as they would in the
 * application. A bundle is stored as T8's upload will store it (extracted and validated,
 * then archive, manifest, inventory and receipt, and a VALIDATED bundle row) and admitted
 * through {@link BuildAdmission}. The Python half reads {@code results.json} and pulls and
 * runs each image.
 */
@EnabledIfSystemProperty(named = "skald.t7probe.registry", matches = "[a-z0-9-]+")
public class T7PassProbeTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String REGISTRY = System.getProperty("skald.t7probe.registry");
    private static final Path OUT = Path.of(System.getProperty("skald.t7probe.out", "target/t7probe"));
    private static final Path APPS = Path.of("dev/fixtures/apps");

    @SuppressWarnings("resource")
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16").withDatabaseName("skald");
    private static TestObjectStore objects;
    private static Path workspace;
    private static final ArrayNode RESULTS = JSON.createArrayNode();

    @BeforeAll
    public static void start() throws Exception {
        POSTGRES.start();
        objects = new TestObjectStore("skald-bundles");
        objects.createBucket("skald-logs");
        workspace = Files.createDirectories(OUT.resolve("workspace"));
    }

    @AfterAll
    public static void stop() throws Exception {
        Files.writeString(OUT.resolve("results.json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(RESULTS));
        if (objects != null) {
            objects.close();
        }
        POSTGRES.stop();
    }

    private static ApplicationContextRunner application(String... overrides) {
        List<String> p = new ArrayList<>(List.of(
                "spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "spring.datasource.username=" + POSTGRES.getUsername(),
                "spring.datasource.password=" + POSTGRES.getPassword(),
                "spring.flyway.schemas=skald", "spring.flyway.default-schema=skald",
                "skald.storage.endpoint=" + objects.endpoint(),
                "skald.storage.access-key=" + TestObjectStore.ACCESS_KEY,
                "skald.storage.secret-key=" + TestObjectStore.SECRET_KEY,
                "skald.builds.worker.enabled=true",
                "skald.builds.worker.owner=t7probe",
                "skald.builds.worker.worker-image=skald-buildkit-worker:test",
                "skald.builds.worker.gateway-image=skald-egress-gateway:test",
                "skald.builds.worker.registry=" + REGISTRY,
                "skald.builds.worker.registry-api=http://localhost:" + System.getProperty("skald.t7probe.registry-port"),
                "skald.builds.worker.registry-username=" + System.getProperty("skald.t7probe.username"),
                // From the environment, not a -D: a command line is visible to every process on the host.
                "skald.builds.worker.registry-password=" + System.getenv("SKALD_T7PROBE_PASSWORD"),
                "skald.builds.worker.outer-networks=" + System.getProperty("skald.t7probe.outer"),
                "skald.builds.worker.private-mirrors=forge",
                "skald.builds.worker.cran-mirror=http://forge/repository/cran-public/",
                "skald.builds.worker.pypi-mirror=http://forge/repository/pypi-public/simple/",
                "skald.builds.worker.published-bases=" + System.getProperty("skald.t7probe.published-bases"),
                "skald.builds.worker.workspace=" + workspace.toAbsolutePath()));
        p.addAll(List.of(overrides));
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class,
                        FlywayAutoConfiguration.class))
                .withUserConfiguration(DataSourceConfig.class, ObjectStorageConfig.class, BundleLimitsConfig.class,
                        BuildsConfig.class, WorkerConfig.class)
                .withPropertyValues(p.toArray(String[]::new));
    }

    // ------------------------------------------------------------------ bundles, as T8 stores them

    record App(String fixture, String language, String version, String entrypoint, String format, String lock) { }

    static final App R = new App("r-shiny", "r", "4.6.1", ".", "renv", "renv.lock");
    static final App PY = new App("python-shiny", "python", "3.13.16", "app.py", "pip-hashed", "requirements.lock");

    private static String sha256(byte[] b) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
    }

    private static Map<String, byte[]> payload(App app) throws Exception {
        Map<String, byte[]> files = new LinkedHashMap<>();
        try (var s = Files.list(APPS.resolve(app.fixture()))) {
            for (Path f : s.sorted().toList()) {
                files.put(f.getFileName().toString(), Files.readAllBytes(f));
            }
        }
        return files;
    }

    private static byte[] manifest(App app, Map<String, byte[]> payload) throws Exception {
        ObjectNode doc = JSON.createObjectNode();
        doc.put("schema_version", 1).put("type", "shiny");
        doc.putObject("runtime").put("language", app.language()).put("version", app.version());
        doc.put("entrypoint", app.entrypoint());
        doc.putObject("dependencies").put("format", app.format()).put("path", app.lock());
        ArrayNode files = doc.putArray("files");
        for (Map.Entry<String, byte[]> f : payload.entrySet()) {
            files.addObject().put("path", f.getKey()).put("size", f.getValue().length).put("sha256", sha256(f.getValue()));
        }
        return JSON.writeValueAsBytes(doc);
    }

    private static byte[] archive(byte[] manifest, Map<String, byte[]> payload) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(out); TarArchiveOutputStream tar = new TarArchiveOutputStream(gz)) {
            Map<String, byte[]> all = new LinkedHashMap<>();
            all.put("manifest.json", manifest);
            payload.forEach((k, v) -> all.put("app/" + k, v));
            for (Map.Entry<String, byte[]> f : all.entrySet()) {
                TarArchiveEntry e = new TarArchiveEntry(f.getKey());
                e.setSize(f.getValue().length);
                e.setMode(0644);
                tar.putArchiveEntry(e);
                tar.write(f.getValue());
                tar.closeArchiveEntry();
            }
        }
        return out.toByteArray();
    }

    /** A content item and a VALIDATED bundle of {@code payload}, stored as T8's upload will store it. */
    private static UUID[] store(ApplicationContext ctx, App app, Map<String, byte[]> payload) throws Exception {
        JdbcTemplate jdbc = ctx.getBean(JdbcTemplate.class);
        ObjectStore store = ctx.getBean(ObjectStore.class);
        BundleWriter bundles = new BundleWriter(store, "skald-bundles");
        UUID content = UUID.randomUUID();
        UUID bundle = UUID.randomUUID();
        byte[] manifest = manifest(app, payload);
        byte[] archive = archive(manifest, payload);
        // Validated first, under the same limits the worker uses: nothing is stored on trust.
        var extracted = BundleExtractor.extract(new ByteArrayInputStream(archive), archive.length,
                Files.createDirectories(workspace.resolve("upload")), ExtractionLimits.defaults());
        try {
            ObjectNode inventory = JSON.createObjectNode();
            ArrayNode files = inventory.putArray("files");
            for (Map.Entry<String, byte[]> f : payload.entrySet()) {
                files.addObject().put("path", f.getKey()).put("size", f.getValue().length)
                        .put("sha256", sha256(f.getValue()));
            }
            byte[] inv = JSON.writeValueAsBytes(inventory);
            jdbc.update("INSERT INTO skald.content (id, title, owner, type) VALUES (?, ?, 'probe', 'shiny')",
                    content, "t7 probe " + app.fixture());
            jdbc.update("INSERT INTO skald.bundle (id, content_id, created_by, state, upload_deadline, finished_at,"
                    + " object_key, object_sha256, object_bytes, manifest_sha256, inventory_files, inventory_bytes)"
                    + " VALUES (?, ?, 'probe', 'VALIDATED', now() + interval '1 hour', now(), ?, ?, ?, ?, ?, ?)",
                    bundle, content, "bundles/" + bundle, sha256(archive), (long) archive.length, sha256(manifest),
                    extracted.files(), extracted.bytes());
            bundles.writeArchive(content, bundle, new ByteArrayInputStream(archive), archive.length);
            bundles.writeManifest(content, bundle, manifest);
            bundles.writeInventory(content, bundle, inv);
            bundles.commit(new BundleReceipt(1, 1, content, bundle, sha256(archive), archive.length, sha256(manifest),
                    sha256(inv))).orElseThrow();
        } finally {
            extracted.root().deleteTree();
        }
        return new UUID[] {content, bundle};
    }

    /** Admits a build and waits for it to settle: terminal, or PUBLISHING with its log complete. */
    private static Map<String, Object> build(ApplicationContext ctx, String name, UUID content, UUID bundle,
                                             Duration bound) throws Exception {
        JdbcTemplate jdbc = ctx.getBean(JdbcTemplate.class);
        UUID build = ctx.getBean(BuildAdmission.class).request(content, "probe", name,
                new BuildAdmission.Inputs(bundle, Map.of())).buildId();
        long started = System.nanoTime();
        Map<String, Object> row;
        do {
            Thread.sleep(2000);
            row = jdbc.queryForMap("SELECT id, content_id, state, error_code, error_detail, output_image,"
                    + " provenance::text AS provenance, effective_limits::text AS limits, log_complete,"
                    + " extract(epoch FROM (coalesce(finished_at, publishing_at, now()) - started_at)) AS seconds"
                    + " FROM skald.build WHERE id = ?", build);
        } while (!settled(row) && System.nanoTime() - started < bound.toNanos());
        return row;
    }

    private static boolean settled(Map<String, Object> row) {
        String state = (String) row.get("state");
        return List.of("FAILED", "CANCELLED", "TIMED_OUT", "INTERRUPTED", "SUCCEEDED").contains(state)
                || ("PUBLISHING".equals(state) && Boolean.TRUE.equals(row.get("log_complete")));
    }

    private static ObjectNode result(ApplicationContext ctx, String name, App app, Map<String, Object> row)
            throws Exception {
        ObjectNode r = RESULTS.addObject();
        r.put("case", name).put("app", app.fixture());
        r.put("build_id", String.valueOf(row.get("id"))).put("content_id", String.valueOf(row.get("content_id")));
        r.put("state", (String) row.get("state")).put("error_code", (String) row.get("error_code"))
                .put("error_detail", (String) row.get("error_detail"))
                .put("output_image", (String) row.get("output_image"))
                .put("log_complete", (Boolean) row.get("log_complete"))
                .put("seconds", String.valueOf(row.get("seconds")));
        r.set("provenance", row.get("provenance") == null ? null : JSON.readTree((String) row.get("provenance")));
        BuildLogWriter logs = new BuildLogWriter(ctx.getBean(ObjectStore.class), "skald-logs");
        UUID c = (UUID) row.get("content_id");
        UUID b = (UUID) row.get("id");
        LogFinal fin = logs.readFinal(c, b).orElse(null);
        r.set("log_final", fin == null ? null : JSON.valueToTree(fin));
        // The log as its owner will read it, every chunk in order, beside results.json: what the
        // "useful failed-build log" check reads.
        if (fin != null) {
            ObjectStore store = ctx.getBean(ObjectStore.class);
            ByteArrayOutputStream text = new ByteArrayOutputStream();
            for (long seq = 1; seq <= fin.lastSequence(); seq++) {
                try (var in = store.open("skald-logs", ObjectKeys.logChunk(c, b, seq))) {
                    in.transferTo(text);
                }
            }
            Path file = OUT.resolve(name + "-" + app.fixture() + ".log.jsonl");
            Files.write(file, text.toByteArray());
            r.put("log_file", file.getFileName().toString());
        }
        return r;
    }

    // ------------------------------------------------------------------ part 4e-1: cold builds of both apps

    @Test
    public void bothFixtureAppsBuildColdThroughTheWiredWorker() {
        application().run(ctx -> {
            assertTrue(ctx.getStartupFailure() == null, String.valueOf(ctx.getStartupFailure()));
            for (App app : List.of(PY, R)) {
                UUID[] ids = store(ctx, app, payload(app));
                Map<String, Object> row = build(ctx, "cold-" + app.fixture(), ids[0], ids[1], Duration.ofMinutes(40));
                ObjectNode r = result(ctx, "cold", app, row);
                assertEquals("PUBLISHING", row.get("state"), r.toString());
                assertEquals(Boolean.TRUE, row.get("log_complete"), r.toString());
                String image = (String) row.get("output_image");
                assertTrue(image.matches(REGISTRY + ":5000/content/" + ids[0] + "@sha256:[0-9a-f]{64}"), image);
                JsonNode prov = r.path("provenance");
                assertEquals("skald.build-provenance/v1", prov.path("schema").asText(), prov.toString());
                assertTrue(prov.path("base").path("reference").asText().startsWith(REGISTRY + ":5000/skald/base/"),
                        prov.toString());
                assertEquals(app.version(), prov.path("base").path("version").asText());
                assertEquals("https://slsa.dev/provenance/v1", prov.path("attestation").path("type").asText());
                assertTrue(prov.path("worker").path("images").path("worker").path("id").asText().startsWith("sha256:"));
                assertEquals("BUILT", r.path("log_final").path("outcome").asText(), r.toString());
                assertTrue(r.path("log_final").path("complete").asBoolean(), r.toString());
            }
        });
    }
}
