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
import eu.openanalytics.shinyproxy.publisher.build.BuildCoordinator;
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
import org.mandas.docker.client.DockerClient;
import org.mandas.docker.client.builder.jersey.JerseyDockerClientBuilder;
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

    /**
     * A fixture app; {@code source} is a file only the app reads, and {@code restore} a
     * fragment of the dependency-restore RUN step's header, as the build log prints it.
     */
    record App(String fixture, String language, String version, String entrypoint, String format, String lock,
               String source, String restore) { }

    static final App R = new App("r-shiny", "r", "4.6.1", ".", "renv", "renv.lock", "app.R", "renv::restore(");
    static final App PY = new App("python-shiny", "python", "3.13.16", "app.py", "pip-hashed", "requirements.lock",
            "app.py", "\"pip\",\"install\"");

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
        UUID content = UUID.randomUUID();
        ctx.getBean(JdbcTemplate.class).update("INSERT INTO skald.content (id, title, owner, type)"
                + " VALUES (?, ?, 'probe', 'shiny')", content, "t7 probe " + app.fixture());
        return new UUID[] {content, storeBundle(ctx, app, content, payload)};
    }

    /** A further VALIDATED bundle of {@code payload} for an existing content item. */
    private static UUID storeBundle(ApplicationContext ctx, App app, UUID content, Map<String, byte[]> payload)
            throws Exception {
        JdbcTemplate jdbc = ctx.getBean(JdbcTemplate.class);
        ObjectStore store = ctx.getBean(ObjectStore.class);
        BundleWriter bundles = new BundleWriter(store, "skald-bundles");
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
        return bundle;
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

    // ------------------------------------------------------------------ the dependency cache, read from a log

    enum Restore { RAN, REUSED }

    /**
     * Whether the dependency restore ran in this build, read from its log as the owner reads
     * it. REUSED means the step settled (CACHED, or DONE after pulling the cached layer: an
     * imported registry hit is not always reported as CACHED) and printed nothing of its
     * own; RAN means it printed output ("#N 1.234 ..."). A log without the step fails.
     */
    static Restore restore(App app, Path log) throws Exception {
        List<String> lines = new ArrayList<>();
        for (String l : Files.readAllLines(log)) {
            lines.add(JSON.readTree(l).path("t").asText());
        }
        String step = null;
        for (String l : lines) {
            var m = java.util.regex.Pattern.compile("^(#\\d+) \\[\\d+/\\d+\\] RUN ").matcher(l);
            if (m.find() && l.contains(app.restore())) {
                step = m.group(1);
            }
        }
        assertTrue(step != null, "the restore step is in " + log);
        String id = step;
        boolean ran = lines.stream().anyMatch(l -> l.matches(java.util.regex.Pattern.quote(id) + " [0-9.]+ .*"));
        boolean settled = lines.stream().anyMatch(l -> l.equals(id + " CACHED") || l.startsWith(id + " DONE"));
        assertTrue(settled, "the restore step settled in " + log);
        return ran ? Restore.RAN : Restore.REUSED;
    }

    /** The registry cache refs the build imported, from its log. */
    static List<String> imported(Path log) throws Exception {
        List<String> refs = new ArrayList<>();
        for (String l : Files.readAllLines(log)) {
            var m = java.util.regex.Pattern.compile("^#\\d+ importing cache manifest from (\\S+)$")
                    .matcher(JSON.readTree(l).path("t").asText());
            if (m.find()) {
                refs.add(m.group(1));
            }
        }
        return refs;
    }

    /** The digest the registry serves for a content item's cache ref, or null if there is none. */
    static String cacheDigest(UUID content, String key) throws Exception {
        String auth = java.util.Base64.getEncoder().encodeToString((System.getProperty("skald.t7probe.username")
                + ":" + System.getenv("SKALD_T7PROBE_PASSWORD")).getBytes(StandardCharsets.UTF_8));
        var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:"
                        + System.getProperty("skald.t7probe.registry-port") + "/v2/skald/cache/" + content
                        + "/manifests/" + key))
                .method("HEAD", java.net.http.HttpRequest.BodyPublishers.noBody())
                .header("Authorization", "Basic " + auth)
                .header("Accept", "application/vnd.oci.image.index.v1+json, application/vnd.oci.image.manifest.v1+json,"
                        + " application/vnd.docker.distribution.manifest.list.v2+json,"
                        + " application/vnd.docker.distribution.manifest.v2+json").build();
        try (var http = java.net.http.HttpClient.newHttpClient()) {
            var response = http.send(request, java.net.http.HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() == 404) {
                return null;
            }
            assertEquals(200, response.statusCode(), "HEAD " + request.uri());
            return response.headers().firstValue("Docker-Content-Digest").orElseThrow();
        }
    }

    /** {@code payload} with {@code file} replaced. */
    private static Map<String, byte[]> with(Map<String, byte[]> payload, String file, String content) {
        Map<String, byte[]> out = new LinkedHashMap<>(payload);
        out.put(file, content.getBytes(StandardCharsets.UTF_8));
        return out;
    }

    private static String text(Map<String, byte[]> payload, String file) {
        return new String(payload.get(file), StandardCharsets.UTF_8);
    }

    /** One cache row: builds, records the result with what the cache did, and returns it. */
    private static ObjectNode cacheRow(ApplicationContext ctx, String name, App app, UUID content,
                                       Map<String, byte[]> payload, Duration bound) throws Exception {
        UUID bundle = storeBundle(ctx, app, content, payload);
        Map<String, Object> row = build(ctx, name + "-" + app.fixture(), content, bundle, bound);
        ObjectNode r = result(ctx, name, app, row);
        assertEquals("PUBLISHING", row.get("state"), r.toString());
        Path log = OUT.resolve(r.path("log_file").asText());
        ObjectNode cache = r.putObject("cache");
        cache.put("restore", restore(app, log).name());
        cache.put("key", r.path("provenance").path("dependencies").path("cache_key").asText());
        cache.putPOJO("imported", imported(log));
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

    // ------------------------------------------------------------------ part 4e-2: decision 7's cache, for real

    /**
     * The dependency cache through the wired worker, judged from the build log as its owner
     * reads it. Every attempt runs on a fresh worker with an empty workspace, so the only
     * thing a build can reuse is the registry cache ref the coordinator names,
     * {@code skald/cache/<content UUID>:<key>}: a hit needs the same content AND the same key.
     * The key's other inputs (base digest, architecture, recipe revision, package-manager
     * versions) are proved to change it in DependencyCacheKeyTest; here, on real builds, a
     * changed key and a different content item each miss.
     */
    @Test
    public void theDependencyCacheIsReusedWithinItsKeyAndContentOnly() {
        Map<String, String> keys = new LinkedHashMap<>();
        UUID[] x = new UUID[1];
        application().run(ctx -> {
            assertTrue(ctx.getStartupFailure() == null, String.valueOf(ctx.getStartupFailure()));
            Map<String, byte[]> py = payload(PY);
            x[0] = UUID.randomUUID();
            ctx.getBean(JdbcTemplate.class).update("INSERT INTO skald.content (id, title, owner, type)"
                    + " VALUES (?, 't7 probe cache', 'probe', 'shiny')", x[0]);
            Duration bound = Duration.ofMinutes(20);

            ObjectNode cold = cacheRow(ctx, "cache-cold", PY, x[0], py, bound);
            String key = cold.path("cache").path("key").asText();
            assertTrue(key.matches("[0-9a-f]{64}"), cold.toString());
            assertEquals("RAN", cold.path("cache").path("restore").asText(), "cold: nothing to reuse");
            assertTrue(cacheDigest(x[0], key) != null, "the cold build exported its cache ref");

            ObjectNode source = cacheRow(ctx, "cache-source-edit", PY, x[0],
                    with(py, PY.source(), text(py, PY.source()) + "\n# a source-only edit\n"), bound);
            assertEquals(key, source.path("cache").path("key").asText(), "a source edit keeps the key");
            assertEquals("REUSED", source.path("cache").path("restore").asText(), "a source-only edit hits");
            assertEquals(List.of(REGISTRY + ":5000/skald/cache/" + x[0] + ":" + key),
                    JSON.convertValue(source.path("cache").path("imported"), List.class));

            // The key is over the RENDERED lock, so a comment changes nothing pip would read.
            ObjectNode comment = cacheRow(ctx, "cache-lock-comment", PY, x[0],
                    with(py, PY.lock(), "# a comment pip ignores\n" + text(py, PY.lock())), bound);
            assertEquals(key, comment.path("cache").path("key").asText(), "an equivalent lock keeps the key");
            assertEquals("REUSED", comment.path("cache").path("restore").asText(), "an equivalent lock hits");

            // A second accepted hash for one package: pip installs the same wheel, but the lock
            // it reads is different, so the key is.
            String lock = text(py, PY.lock());
            var first = java.util.regex.Pattern.compile("--hash=sha256:[0-9a-f]{64}").matcher(lock);
            assertTrue(first.find());
            String changed = lock.substring(0, first.end()) + " \\\n    --hash=sha256:" + "0".repeat(64)
                    + lock.substring(first.end());
            ObjectNode lockChange = cacheRow(ctx, "cache-lock-change", PY, x[0], with(py, PY.lock(), changed), bound);
            assertTrue(!key.equals(lockChange.path("cache").path("key").asText()), "a lock change changes the key");
            assertEquals("RAN", lockChange.path("cache").path("restore").asText(), "a lock change misses");

            // Another content item, the same bundle: the same key, a different scope. It
            // neither reads this item's cache nor changes it. The digest is taken here: this
            // item's own builds re-export its ref (mode=min carries the app layer, so each
            // source edit changes it), and only the other item's build may not.
            String before = cacheDigest(x[0], key);
            UUID[] y = store(ctx, PY, py);
            Map<String, Object> row = build(ctx, "cache-other-content-" + PY.fixture(), y[0], y[1], bound);
            ObjectNode other = result(ctx, "cache-other-content", PY, row);
            assertEquals("PUBLISHING", row.get("state"), other.toString());
            Path log = OUT.resolve(other.path("log_file").asText());
            ObjectNode c = other.putObject("cache");
            c.put("restore", restore(PY, log).name());
            c.put("key", other.path("provenance").path("dependencies").path("cache_key").asText());
            c.putPOJO("imported", imported(log));
            assertEquals(key, c.path("key").asText(), "the same bundle has the same key");
            assertEquals("RAN", c.path("restore").asText(), "another content item never reads this one's cache");
            assertEquals(List.of(REGISTRY + ":5000/skald/cache/" + y[0] + ":" + key),
                    JSON.convertValue(c.path("imported"), List.class), "it imports its own scope only");
            assertEquals(before, cacheDigest(x[0], key), "and this item's cache ref is unchanged by it");
            keys.put("py", key);
        });

        // The configured repository set is in the key (decision 7, Q3): the same lock resolved
        // against another mirror misses. forge2 is the same forge under a second name.
        application("skald.builds.worker.private-mirrors=forge,forge2",
                "skald.builds.worker.pypi-mirror=http://forge2/repository/pypi-public/simple/").run(ctx -> {
            assertTrue(ctx.getStartupFailure() == null, String.valueOf(ctx.getStartupFailure()));
            ObjectNode mirror = cacheRow(ctx, "cache-mirror-change", PY, x[0], payload(PY), Duration.ofMinutes(20));
            assertTrue(!keys.get("py").equals(mirror.path("cache").path("key").asText()),
                    "a different mirror changes the key");
            assertEquals("RAN", mirror.path("cache").path("restore").asText(), "a different mirror misses");
        });

        // R: the cold build compiles from source (no Linux binaries from forge); a source-only
        // republish reuses it.
        application().run(ctx -> {
            assertTrue(ctx.getStartupFailure() == null, String.valueOf(ctx.getStartupFailure()));
            Map<String, byte[]> r = payload(R);
            UUID content = UUID.randomUUID();
            ctx.getBean(JdbcTemplate.class).update("INSERT INTO skald.content (id, title, owner, type)"
                    + " VALUES (?, 't7 probe cache R', 'probe', 'shiny')", content);
            ObjectNode cold = cacheRow(ctx, "cache-cold", R, content, r, Duration.ofMinutes(40));
            assertEquals("RAN", cold.path("cache").path("restore").asText(), "cold: nothing to reuse");
            ObjectNode source = cacheRow(ctx, "cache-source-edit", R, content,
                    with(r, R.source(), text(r, R.source()) + "\n# a source-only edit\n"), Duration.ofMinutes(20));
            assertEquals(cold.path("cache").path("key").asText(), source.path("cache").path("key").asText());
            assertEquals("REUSED", source.path("cache").path("restore").asText(), "a source-only R edit hits");
        });
    }

    // ------------------------------------------------------------------ part 4e-3: injected failures

    /** The log so far, every chunk written yet, in order; empty before the first. */
    private static String liveLog(ApplicationContext ctx, UUID content, UUID build) throws Exception {
        ObjectStore store = ctx.getBean(ObjectStore.class);
        StringBuilder text = new StringBuilder();
        for (long seq = 1; ; seq++) {
            try (var in = store.open("skald-logs", ObjectKeys.logChunk(content, build, seq))) {
                for (String l : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                    if (!l.isBlank()) {
                        text.append(JSON.readTree(l).path("t").asText()).append('\n');
                    }
                }
            } catch (Exception missing) {
                return text.toString();
            }
        }
    }

    /** Admits a build and returns its id once its log shows the restore step started. */
    private static UUID startAndReachRestore(ApplicationContext ctx, App app, UUID content, UUID bundle, String name)
            throws Exception {
        UUID build = ctx.getBean(BuildAdmission.class).request(content, "probe", name,
                new BuildAdmission.Inputs(bundle, Map.of())).buildId();
        long started = System.nanoTime();
        while (System.nanoTime() - started < Duration.ofMinutes(15).toNanos()) {
            String log = liveLog(ctx, content, build);
            if (log.lines().anyMatch(l -> l.matches("#\\d+ \\[\\d+/\\d+\\] RUN .*") && l.contains(app.restore()))) {
                return build;
            }
            Thread.sleep(500);
        }
        throw new AssertionError("the restore step never started: " + liveLog(ctx, content, build));
    }

    /** Waits for a terminal state and a final log record, within {@code bound}. */
    private static Map<String, Object> settleWithLog(ApplicationContext ctx, UUID build, Duration bound) throws Exception {
        JdbcTemplate jdbc = ctx.getBean(JdbcTemplate.class);
        BuildLogWriter logs = new BuildLogWriter(ctx.getBean(ObjectStore.class), "skald-logs");
        long started = System.nanoTime();
        Map<String, Object> row;
        do {
            Thread.sleep(2000);
            row = jdbc.queryForMap("SELECT id, content_id, state, error_code, error_detail, output_image,"
                    + " provenance::text AS provenance, effective_limits::text AS limits, log_complete,"
                    + " extract(epoch FROM (coalesce(finished_at, publishing_at, now()) - started_at)) AS seconds"
                    + " FROM skald.build WHERE id = ?", build);
        } while (!(List.of("FAILED", "CANCELLED", "TIMED_OUT", "INTERRUPTED").contains((String) row.get("state"))
                && logs.readFinal((UUID) row.get("content_id"), build).isPresent())
                && System.nanoTime() - started < bound.toNanos());
        return row;
    }

    /**
     * Seconds until nothing of the attempt is left on the host, or -1 past {@code bound}:
     * nothing labelled with it, and nothing the launcher's own per-attempt check finds (the
     * unlabelled volumes a late bind re-creates, the loop device, the quota image; 01683f9 N1).
     */
    private static double teardownSeconds(DockerClient docker, String attempt, Duration bound) throws Exception {
        DockerWorkerLauncher launcher = new DockerWorkerLauncher(docker, WorkerProfile.load("runc-rootless"),
                DockerWorkerLauncher.Images.of("skald-buildkit-worker:test", "skald-egress-gateway:test"));
        long started = System.nanoTime();
        String label = DockerWorkerLauncher.ATTEMPT_LABEL + "=" + attempt;
        while (System.nanoTime() - started < bound.toNanos()) {
            boolean left = !docker.listContainers(DockerClient.ListContainersParam.allContainers(),
                    DockerClient.ListContainersParam.withLabel(DockerWorkerLauncher.ATTEMPT_LABEL, attempt)).isEmpty()
                    || docker.listVolumes(DockerClient.ListVolumesParam.filter("label", label)).volumes() != null
                    && !docker.listVolumes(DockerClient.ListVolumesParam.filter("label", label)).volumes().isEmpty()
                    || !docker.listNetworks(DockerClient.ListNetworksParam.withLabel(DockerWorkerLauncher.ATTEMPT_LABEL,
                    attempt)).isEmpty();
            if (!left && launcher.leftovers(DockerWorkerLauncher.handle(attempt)).isEmpty()) {
                return (System.nanoTime() - started) / 1e9;
            }
            Thread.sleep(500);
        }
        return -1;
    }

    private static String attemptOf(String log) {
        var m = java.util.regex.Pattern.compile("\\[skald\\] attempt ([0-9a-f]{32})").matcher(log);
        assertTrue(m.find(), "the log names its attempt: " + log);
        return m.group(1);
    }

    /**
     * One injected failure, judged: the state and code the attempt ends in, a complete log
     * whose text names the cause, and teardown of everything the attempt owned within the
     * bound (the disposal is the code's; how long the daemon takes is the host's, so the
     * measured time is recorded and the bound is generous).
     */
    /** Every cause pattern a row used, so a successful build's log can be checked against all of them. */
    private static final List<String> CAUSES = new ArrayList<>();

    /** A saved log as its owner reads it: each line's text, one per line. */
    private static String decoded(Path log) throws Exception {
        StringBuilder text = new StringBuilder();
        for (String l : Files.readAllLines(log)) {
            text.append(JSON.readTree(l).path("t").asText()).append('\n');
        }
        return text.toString();
    }

    private static ObjectNode injected(ApplicationContext ctx, DockerClient docker, String name, App app,
                                       Map<String, Object> row, String state, String code, String cause)
            throws Exception {
        CAUSES.add(cause);
        ObjectNode r = result(ctx, name, app, row);
        assertEquals(state, row.get("state"), r.toString());
        if (code != null) {
            assertEquals(code, row.get("error_code"), r.toString());
        }
        assertTrue(r.path("log_final").path("complete").asBoolean(), "the log is complete: " + r);
        String log = decoded(OUT.resolve(r.path("log_file").asText()));
        assertTrue(java.util.regex.Pattern.compile(cause).matcher(log).find(),
                "the log names the cause /" + cause + "/: " + r.path("log_file").asText());
        double seconds = teardownSeconds(docker, attemptOf(liveLog(ctx, (UUID) row.get("content_id"),
                (UUID) row.get("id"))), Duration.ofMinutes(3));
        r.put("teardown_seconds", seconds);
        assertTrue(seconds >= 0, "everything the attempt owned is gone within 3 minutes: " + r);
        return r;
    }

    /**
     * T7's injections, through the wired worker: a failed restore, an unreachable repository,
     * a push failure, a timeout, a cancellation and the worker's death. Each must end in its
     * own state with a complete log that names the cause, and leave nothing behind; and the
     * worker must build again afterwards.
     */
    @Test
    public void injectedFailuresEndCleanlyWithALogThatNamesTheCause() throws Exception {
        String[] success = new String[1];
        try (DockerClient docker = new JerseyDockerClientBuilder().fromEnv().build()) {
            application("skald.builds.worker.sweep-period=30s").run(ctx -> {
                assertTrue(ctx.getStartupFailure() == null, String.valueOf(ctx.getStartupFailure()));
                Map<String, byte[]> py = payload(PY);

                // A failed restore: one package's only hash is wrong, so pip refuses the wheel.
                String lock = text(py, PY.lock());
                var first = java.util.regex.Pattern.compile("--hash=sha256:[0-9a-f]{64}").matcher(lock);
                assertTrue(first.find());
                String wrong = lock.substring(0, first.start()) + "--hash=sha256:" + "0".repeat(64)
                        + lock.substring(first.end());
                UUID[] ids = store(ctx, PY, with(py, PY.lock(), wrong));
                Map<String, Object> row = settleWithLog(ctx, ctx.getBean(BuildAdmission.class).request(ids[0], "probe",
                        "failed-restore", new BuildAdmission.Inputs(ids[1], Map.of())).buildId(), Duration.ofMinutes(15));
                injected(ctx, docker, "inject-failed-restore", PY, row, "FAILED", "BUILD_FAILED",
                        "DO NOT MATCH THE HASHES");

                // A push failure: the registry goes away once the restore has started (the
                // base was pulled and the cache imported before it), and comes back after.
                ids = store(ctx, PY, py);
                UUID build = startAndReachRestore(ctx, PY, ids[0], ids[1], "push-failure");
                docker.stopContainer(REGISTRY, 5);
                try {
                    row = settleWithLog(ctx, build, Duration.ofMinutes(15));
                } finally {
                    docker.startContainer(REGISTRY);
                }
                injected(ctx, docker, "inject-push-failure", PY, row, "FAILED", "BUILD_FAILED",
                        "failed to push " + java.util.regex.Pattern.quote(REGISTRY) + ":5000/content/");

                // A cancellation while the restore runs.
                ids = store(ctx, PY, py);
                build = startAndReachRestore(ctx, PY, ids[0], ids[1], "cancel");
                assertEquals(BuildCoordinator.CancelOutcome.REQUESTED,
                        ctx.getBean(BuildCoordinator.class).cancel(ids[0], build, "probe"));
                row = settleWithLog(ctx, build, Duration.ofMinutes(5));
                ObjectNode cancelled = injected(ctx, docker, "inject-cancel", PY, row, "CANCELLED", null,
                        "(?m)^\\[skald\\] stopped: a cancellation was requested$");
                assertEquals("STOPPED", cancelled.path("log_final").path("outcome").asText());

                // The worker dies while the restore runs.
                ids = store(ctx, PY, py);
                build = startAndReachRestore(ctx, PY, ids[0], ids[1], "worker-death");
                docker.killContainer(DockerWorkerLauncher.handle(attemptOf(liveLog(ctx, ids[0], build))).worker());
                row = settleWithLog(ctx, build, Duration.ofMinutes(5));
                injected(ctx, docker, "inject-worker-death", PY, row, "FAILED", "WORKER_DIED",
                        "(?m)^\\[skald\\] failed: WORKER_DIED ");

                // And the worker builds again after all of that.
                ids = store(ctx, PY, py);
                row = build(ctx, "after-injections-" + PY.fixture(), ids[0], ids[1], Duration.ofMinutes(20));
                ObjectNode after = result(ctx, "after-injections", PY, row);
                assertEquals("PUBLISHING", row.get("state"), after.toString());
                success[0] = decoded(OUT.resolve(after.path("log_file").asText()));
            });

            // An unreachable repository: the mirror's host is allowed, but nothing answers.
            application("skald.builds.worker.private-mirrors=forge,forge-down",
                    "skald.builds.worker.pypi-mirror=http://forge-down/repository/pypi-public/simple/").run(ctx -> {
                assertTrue(ctx.getStartupFailure() == null, String.valueOf(ctx.getStartupFailure()));
                UUID[] ids = store(ctx, PY, payload(PY));
                Map<String, Object> row = settleWithLog(ctx, ctx.getBean(BuildAdmission.class).request(ids[0], "probe",
                        "unreachable", new BuildAdmission.Inputs(ids[1], Map.of())).buildId(), Duration.ofMinutes(15));
                injected(ctx, docker, "inject-unreachable-repository", PY, row, "FAILED", "BUILD_FAILED",
                        // What pip prints: the mirror it tried, then no versions at all (the
                        // gateway's error page reads as an empty index; measured).
                        "(?s)Looking in indexes: http://forge-down/.*Could not find a version that satisfies"
                        + " the requirement \\S+ \\(from versions: none\\)");
            });

            // A timeout: a one-minute wall deadline on a from-source R build, which needs
            // several. The reaper settles it TIMED_OUT; the worker's own stop finishes the log,
            // STOPPED with the reason, and the sweep (every 30 s here, so it lands in that gap
            // if it can) leaves it to the writer (01683f9-F1).
            application("skald.builds.coordinator.deadline-minutes=1", "skald.builds.worker.sweep-period=30s").run(ctx -> {
                assertTrue(ctx.getStartupFailure() == null, String.valueOf(ctx.getStartupFailure()));
                UUID[] ids = store(ctx, R, payload(R));
                Map<String, Object> row = settleWithLog(ctx, ctx.getBean(BuildAdmission.class).request(ids[0], "probe",
                        "timeout", new BuildAdmission.Inputs(ids[1], Map.of())).buildId(), Duration.ofMinutes(10));
                ObjectNode timedOut = injected(ctx, docker, "inject-timeout", R, row, "TIMED_OUT", "DEADLINE_EXCEEDED",
                        "(?m)^\\[skald\\] stopped: the build passed its deadline of 1 minute\\(s\\)$");
                assertEquals("STOPPED", timedOut.path("log_final").path("outcome").asText(),
                        "the worker's own finish, not the sweep's INTERRUPTED: " + timedOut);
            });
        }
        // The control for every cause pattern (01683f9-F2): none matches a successful build's
        // log, so "the log names the cause" can fail.
        assertEquals(6, CAUSES.size());
        for (String cause : CAUSES) {
            assertTrue(!java.util.regex.Pattern.compile(cause).matcher(success[0]).find(),
                    "/" + cause + "/ also matches a successful build's log");
        }
    }
}
