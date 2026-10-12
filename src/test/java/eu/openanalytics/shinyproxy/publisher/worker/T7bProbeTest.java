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
import eu.openanalytics.shinyproxy.publisher.binaries.BinaryBuildQueue;
import eu.openanalytics.shinyproxy.publisher.build.BuildAdmission;
import eu.openanalytics.shinyproxy.publisher.build.BuildsConfig;
import eu.openanalytics.shinyproxy.publisher.bundle.BundleLimitsConfig;
import eu.openanalytics.shinyproxy.publisher.db.DataSourceConfig;
import eu.openanalytics.shinyproxy.publisher.storage.BuildLogWriter;
import eu.openanalytics.shinyproxy.publisher.storage.LogFinal;
import eu.openanalytics.shinyproxy.publisher.storage.ObjectKeys;
import eu.openanalytics.shinyproxy.publisher.storage.ObjectStorageConfig;
import eu.openanalytics.shinyproxy.publisher.storage.ObjectStore;
import eu.openanalytics.shinyproxy.publisher.storage.TestObjectStore;
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

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Java half of T7b's probe (dev/t7b-probe.py; WORKPLAN-BUNDLES T7b step 2, point 6): the
 * application's own configuration with the worker AND the shared binaries enabled, against a
 * real forge (the pinned image) whose CRAN proxy fronts the probe's own upstream, a platform
 * registered for the published R base's image ID, and a token scoped to that platform's tree.
 * The rows, in order (state carries from one to the next, so one method):
 * <ol>
 *   <li>A: the fixture R app plus the probe's packages, cold: its restore compiles from source
 *       and its success queues every package for shared binaries;</li>
 *   <li>B: the same lock, admitted at once, so the compiles yield while it builds; meanwhile,
 *       once skald.changes is verified, its tarball is republished upstream (forge's proxy
 *       pins the bytes it cached first, forge-issues/008, so the binary must be of those);</li>
 *   <li>the queue drained (deferred rows aside); skald.changes, if it was refused, re-opened;</li>
 *   <li>C: the same lock again, its restore timed with the binaries present;</li>
 *   <li>D: forge's registration re-pointed at another image: the build restores from the plain
 *       mirror, with the reason; the registration is put back;</li>
 *   <li>E: a lock naming a remote package: refused, and never a binary row.</li>
 * </ol>
 * Everything is written to results.json for the Python half, which reads the binaries from
 * forge and judges the rows.
 */
@EnabledIfSystemProperty(named = "skald.t7b.forge-port", matches = "[0-9]+")
public class T7bProbeTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path OUT = Path.of(System.getProperty("skald.t7b.out", "target/t7b"));
    private static final Path DATA = Path.of(System.getProperty("skald.t7b.data", "target/t7b/upstream"));
    private static final String FORGE = "http://localhost:" + System.getProperty("skald.t7b.forge-port") + "/";
    private static final String KEY = "noble-x86_64/4.6";

    @SuppressWarnings("resource")
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16").withDatabaseName("skald");
    private static TestObjectStore objects;
    private static Path workspace;
    private static final ObjectNode RESULTS = JSON.createObjectNode();

    @BeforeAll
    public static void start() throws Exception {
        POSTGRES.start();
        objects = new TestObjectStore("skald-bundles", System.getProperty("skald.t7b.s3-endpoint"));
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

    private static ApplicationContextRunner application() {
        List<String> p = new ArrayList<>(List.of(
                "spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "spring.datasource.username=" + POSTGRES.getUsername(),
                "spring.datasource.password=" + POSTGRES.getPassword(),
                "spring.flyway.schemas=skald", "spring.flyway.default-schema=skald",
                "skald.storage.endpoint=" + objects.endpoint(),
                "skald.storage.access-key=" + TestObjectStore.ACCESS_KEY,
                "skald.storage.secret-key=" + TestObjectStore.SECRET_KEY,
                "skald.builds.worker.enabled=true",
                "skald.builds.worker.owner=t7b",
                "skald.builds.worker.worker-image=skald-buildkit-worker:test",
                "skald.builds.worker.gateway-image=skald-egress-gateway:test",
                "skald.builds.worker.registry=" + System.getProperty("skald.t7b.registry"),
                "skald.builds.worker.registry-api=http://localhost:" + System.getProperty("skald.t7b.registry-port"),
                "skald.builds.worker.registry-username=" + System.getProperty("skald.t7b.username"),
                "skald.builds.worker.registry-password=" + System.getenv("SKALD_T7B_PASSWORD"),
                "skald.builds.worker.outer-networks=" + System.getProperty("skald.t7b.outer"),
                "skald.builds.worker.private-mirrors=forge",
                "skald.builds.worker.cran-mirror=http://forge/repository/cran-public/",
                "skald.builds.worker.cran-linux-binaries=true",
                "skald.builds.worker.pypi-mirror=http://forge/repository/pypi-public/simple/",
                "skald.builds.worker.published-bases=" + System.getProperty("skald.t7b.published-bases"),
                "skald.builds.worker.workspace=" + workspace.toAbsolutePath(),
                // The shared binaries (T7b): the trusted side reaches forge on the host.
                "skald.builds.binaries-enabled=true",
                "skald.builds.binaries.forge-url=" + FORGE,
                "skald.builds.binaries.forge-token=" + System.getenv("SKALD_T7B_FORGE_TOKEN"),
                "skald.builds.binaries.forge-registration-ttl-seconds=5",
                // The source mutant (dev/t7b-probe.py --mutant source) names the group instead.
                "skald.builds.binaries.source-repository=" + FORGE + "repository/"
                        + System.getProperty("skald.t7b.source-repo", "cran-proxy") + "/",
                "skald.builds.binaries.source-archived=true",
                "skald.builds.binaries.max-running=3"));
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class,
                        FlywayAutoConfiguration.class))
                .withUserConfiguration(DataSourceConfig.class, ObjectStorageConfig.class, BundleLimitsConfig.class,
                        BuildsConfig.class, WorkerConfig.class, BinariesConfig.class)
                .withPropertyValues(p.toArray(String[]::new));
    }

    // ------------------------------------------------------------------ the app

    /** Pure R, no dependencies, unchanged on CRAN for years: a cache miss that costs nothing. */
    static final String[] B_EXTRA = {"praise", "1.0.0"};
    static final String[] C_EXTRA = {"fortunes", "1.5-4"};

    /** The probe's packages added to the fixture lock: name -> version (all "CRAN" by the upstream). */
    static final Map<String, String> PROBE_PACKAGES = new LinkedHashMap<>();

    static {
        for (String p : List.of("skald.chaina", "skald.chainb", "skald.chainc", "skald.shadowed", "skald.uses",
                "skald.changes", "skald.netprobe", "skald.private")) {
            PROBE_PACKAGES.put(p, "1.0");
        }
        PROBE_PACKAGES.put("skald.old", "1.0");
    }

    /**
     * The fixture R app with the probe's packages in its lock, plus {@code extra} (name and
     * version of one small CRAN package, or null) and, for E, a remote one. The dependency
     * cache is keyed on the rendered lock, so B and C each add a different package: without
     * it both would reuse A's restore, and neither would yield to nor time anything.
     */
    private static Map<String, byte[]> payload(String marker, String[] extra, boolean remote) throws Exception {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("app.R", (new String(Files.readAllBytes(Path.of("dev/fixtures/apps/r-shiny/app.R")),
                StandardCharsets.UTF_8) + "\n# " + marker + "\n").getBytes(StandardCharsets.UTF_8));
        ObjectNode lock = (ObjectNode) JSON.readTree(Files.readAllBytes(Path.of("dev/fixtures/apps/r-shiny/renv.lock")));
        ObjectNode packages = (ObjectNode) lock.get("Packages");
        PROBE_PACKAGES.forEach((name, version) -> packages.putObject(name).put("Package", name).put("Version", version)
                .put("Source", "Repository").put("Repository", "CRAN"));
        if (extra != null) {
            packages.putObject(extra[0]).put("Package", extra[0]).put("Version", extra[1])
                    .put("Source", "Repository").put("Repository", "CRAN");
        }
        if (remote) {
            packages.putObject("skald.remote").put("Package", "skald.remote").put("Version", "1.0")
                    .put("Source", "GitHub").put("RemoteType", "github").put("RemoteUsername", "x")
                    .put("RemoteRepo", "skald.remote");
        }
        files.put("renv.lock", JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(lock));
        return files;
    }

    private static Map<String, Object> build(ApplicationContext ctx, String name, Map<String, byte[]> payload,
                                             boolean wait) throws Exception {
        UUID[] ids = T7PassProbeTest.store(ctx, T7PassProbeTest.R, payload, workspace);
        UUID build = ctx.getBean(BuildAdmission.class).request(ids[0], "probe", name,
                new BuildAdmission.Inputs(ids[1], Map.of())).buildId();
        Map<String, Object> row = Map.of("id", build);
        if (wait) {
            row = settle(ctx, build, Duration.ofMinutes(90));
        }
        return row;
    }

    private static Map<String, Object> settle(ApplicationContext ctx, UUID build, Duration bound) throws Exception {
        JdbcTemplate jdbc = ctx.getBean(JdbcTemplate.class);
        long started = System.nanoTime();
        Map<String, Object> row;
        do {
            Thread.sleep(2000);
            row = jdbc.queryForMap("SELECT id, content_id, state, error_code, error_detail, output_image,"
                    + " provenance::text AS provenance, log_complete,"
                    + " extract(epoch FROM (coalesce(finished_at, publishing_at, now()) - started_at)) AS seconds"
                    + " FROM skald.build WHERE id = ?", build);
        } while (!T7PassProbeTest.settled(row) && System.nanoTime() - started < bound.toNanos());
        return row;
    }

    /** The build's row, its log written beside results.json, and its restore step's seconds. */
    private static ObjectNode record(ApplicationContext ctx, String name, Map<String, Object> row) throws Exception {
        ObjectNode r = ((ObjectNode) RESULTS.withObject("/builds")).putObject(name);
        r.put("state", (String) row.get("state")).put("error_code", (String) row.get("error_code"))
                .put("error_detail", (String) row.get("error_detail"))
                .put("seconds", String.valueOf(row.get("seconds")));
        r.set("provenance", row.get("provenance") == null ? null : JSON.readTree((String) row.get("provenance")));
        LogFinal fin = new BuildLogWriter(ctx.getBean(ObjectStore.class), "skald-logs")
                .readFinal((UUID) row.get("content_id"), (UUID) row.get("id")).orElse(null);
        if (fin != null) {
            ByteArrayOutputStream text = new ByteArrayOutputStream();
            for (long seq = 1; seq <= fin.lastSequence(); seq++) {
                try (var in = ctx.getBean(ObjectStore.class).open("skald-logs",
                        ObjectKeys.logChunk((UUID) row.get("content_id"), (UUID) row.get("id"), seq))) {
                    in.transferTo(text);
                }
            }
            Path file = OUT.resolve(name + ".log.jsonl");
            Files.write(file, text.toByteArray());
            r.put("log_file", file.getFileName().toString());
            r.put("restore_seconds", restoreSeconds(file));
        }
        return r;
    }

    /** The R restore step's own duration, from the BuildKit progress in the log ("#N DONE 123.4s"). */
    static double restoreSeconds(Path log) throws Exception {
        String step = null;
        double seconds = -1;
        for (String l : Files.readAllLines(log)) {
            String t = JSON.readTree(l).path("t").asText();
            var run = java.util.regex.Pattern.compile("^(#\\d+) \\[\\d+/\\d+\\] RUN .*renv::restore\\(").matcher(t);
            if (run.find()) {
                step = run.group(1);
            }
            if (step != null) {
                var done = java.util.regex.Pattern.compile("^" + java.util.regex.Pattern.quote(step) + " DONE ([0-9.]+)s$")
                        .matcher(t);
                if (done.find()) {
                    seconds = Double.parseDouble(done.group(1));
                }
            }
        }
        return seconds;
    }

    // ------------------------------------------------------------------ the queue

    private static List<Map<String, Object>> rows(JdbcTemplate jdbc) {
        return jdbc.queryForList("SELECT id, package, version, state, source_kind, refusal_code, refusal_detail,"
                + " error_code, error_detail, source_sha256, binary_sha256, provenance::text AS provenance, deferrals,"
                + " not_before > now() AS deferred FROM skald.binary_build ORDER BY package");
    }

    /** Waits until no row is waiting or running, deferred rows aside. */
    private static void drain(JdbcTemplate jdbc, Duration bound) throws Exception {
        long started = System.nanoTime();
        while (System.nanoTime() - started < bound.toNanos()) {
            int busy = jdbc.queryForObject("SELECT count(*) FROM skald.binary_build WHERE state IN"
                    + " ('VERIFYING', 'RUNNING') OR (state IN ('PENDING_SOURCE', 'QUEUED')"
                    + " AND (not_before IS NULL OR not_before <= now()))", Integer.class);
            if (busy == 0) {
                return;
            }
            Thread.sleep(5000);
        }
        throw new AssertionError("the binary queue did not drain within " + bound);
    }

    private static String state(JdbcTemplate jdbc, String pkg) {
        return jdbc.queryForObject("SELECT state FROM skald.binary_build WHERE platform_key = ? AND package = ?",
                String.class, KEY, pkg);
    }

    private static ArrayNode table(JdbcTemplate jdbc) {
        ArrayNode out = JSON.createArrayNode();
        for (Map<String, Object> r : rows(jdbc)) {
            ObjectNode o = out.addObject();
            r.forEach((k, v) -> o.put(k, v == null ? null : String.valueOf(v)));
        }
        return out;
    }

    // ------------------------------------------------------------------ forge's registration

    private static String forge(String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(FORGE + path))
                .header("Authorization", "Bearer " + System.getenv("SKALD_T7B_FORGE_ADMIN"));
        b = body == null ? b.method(method, HttpRequest.BodyPublishers.noBody())
                : b.method(method, HttpRequest.BodyPublishers.ofString(body)).header("Content-Type", "application/json");
        try (HttpClient http = HttpClient.newHttpClient()) {
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            assertTrue(r.statusCode() / 100 == 2, method + " " + path + ": " + r.statusCode() + " " + r.body());
            return r.body();
        }
    }

    // ------------------------------------------------------------------ the rows

    @Test
    public void sharedBinariesEndToEnd() throws Exception {
        application().run(ctx -> {
            try {
                if ("mutant".equals(System.getProperty("skald.t7b.mode"))) {
                    mutantRows(ctx);
                } else {
                    rowsInOrder(ctx);
                }
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }

    private static void rowsInOrder(ApplicationContext ctx) throws Exception {
        JdbcTemplate jdbc = ctx.getBean(JdbcTemplate.class);
        BinaryBuildQueue queue = ctx.getBean(BinaryBuildQueue.class);

        // A: cold. Its success queues the lock's packages.
        Map<String, Object> a = build(ctx, "A", payload("A", null, false), true);
        record(ctx, "A", a);
        assertEquals("PUBLISHING", a.get("state"), String.valueOf(a.get("error_detail")));

        // B: admitted at once, so the compiles yield to it (c18c5ab N1) while sources verify.
        Map<String, Object> bAdmitted = build(ctx, "B", payload("B", B_EXTRA, false), false);
        long waitStart = System.nanoTime();
        while (!"QUEUED".equals(rowState(jdbc, "skald.changes")) && System.nanoTime() - waitStart < 600_000_000_000L) {
            Thread.sleep(2000);
        }
        RESULTS.put("changes_state_before_republish", rowState(jdbc, "skald.changes"));
        // Republished upstream under the same version (604d63b N1): other bytes, a new MD5.
        Files.copy(DATA.resolve("republished/skald.changes_1.0.tar.gz"),
                DATA.resolve("contrib/skald.changes_1.0.tar.gz"), StandardCopyOption.REPLACE_EXISTING);
        Map<String, Object> b = settle(ctx, (UUID) bAdmitted.get("id"), Duration.ofMinutes(90));
        record(ctx, "B", b);

        long drainStart = System.nanoTime();
        drain(jdbc, Duration.ofHours(3));
        RESULTS.put("drain_seconds", (System.nanoTime() - drainStart) / 1e9);
        RESULTS.set("rows_after_drain", table(jdbc));

        // The republished tarball: refused at its hand-in; re-opened, refused SOURCE_CHANGED.
        UUID changes = jdbc.queryForObject("SELECT id FROM skald.binary_build WHERE package = 'skald.changes'",
                UUID.class);
        RESULTS.put("changes_state_after_drain", state(jdbc, "skald.changes"));
        if (List.of("FAILED", "REFUSED").contains(state(jdbc, "skald.changes"))) {
            queue.reopen(changes, "t7b-probe", false);
            drain(jdbc, Duration.ofMinutes(10));
        }
        RESULTS.set("changes_after_reopen", JSON.valueToTree(jdbc.queryForMap("SELECT state, refusal_code,"
                + " refusal_detail FROM skald.binary_build WHERE id = ?", changes)));

        // C: A's lock (plus one package, for the cache) with the binaries present.
        Map<String, Object> c = build(ctx, "C", payload("C", C_EXTRA, false), true);
        record(ctx, "C", c);

        // D: the registration names another image: the plain mirror, with the reason.
        String registration = forge("GET", "api/v1/cran/platforms/" + KEY, null);
        ObjectNode other = (ObjectNode) JSON.readTree(registration);
        other.remove("managedBy");
        other.put("builderDigest", "sha256:" + "0".repeat(64));
        forge("PUT", "api/v1/cran/platforms/" + KEY, other.toString());
        Thread.sleep(6000);
        int before = jdbc.queryForObject("SELECT count(*) FROM skald.binary_build", Integer.class);
        Map<String, Object> d;
        try {
            d = build(ctx, "D", payload("D", null, false), true);
        } finally {
            ObjectNode back = (ObjectNode) JSON.readTree(registration);
            back.remove("managedBy");
            forge("PUT", "api/v1/cran/platforms/" + KEY, back.toString());
        }
        record(ctx, "D", d);
        Thread.sleep(2000);
        RESULTS.put("rows_added_by_D", jdbc.queryForObject("SELECT count(*) FROM skald.binary_build", Integer.class)
                - before);

        // E: a remote package in the lock: the build is refused, and no row ever names it.
        try {
            record(ctx, "E", build(ctx, "E", payload("E", null, true), true));
        } catch (RuntimeException refused) {
            // Refused at admission, before any build row: the same outcome, earlier.
            ((ObjectNode) RESULTS.withObject("/builds")).putObject("E").put("state", "REFUSED_AT_ADMISSION")
                    .put("error_code", refused.getClass().getSimpleName()).put("error_detail", refused.getMessage());
        }
        RESULTS.put("remote_rows", jdbc.queryForObject("SELECT count(*) FROM skald.binary_build WHERE package"
                + " = 'skald.remote'", Integer.class));

        RESULTS.set("rows_final", table(jdbc));
    }

    /**
     * The short run the probe's mutants use: the two packages the isolation, source and token
     * rows read (skald.netprobe, skald.shadowed) queued as an R build's success queues them
     * (BinaryEnqueuer.built: the lock recorded, each version enqueued), built, and the rows
     * recorded. No app build: an R lock must carry shiny and its closure, forty compiles per
     * mutant, and how app builds feed the queue is the full run's row, not the mutants'.
     */
    private static void mutantRows(ApplicationContext ctx) throws Exception {
        BinaryBuildQueue queue = ctx.getBean(BinaryBuildQueue.class);
        UUID requestedBy = UUID.randomUUID();
        Map<String, String> lock = Map.of("skald.netprobe", "1.0", "skald.shadowed", "1.0");
        queue.recordLock(requestedBy, lock);
        lock.forEach((pkg, version) -> queue.enqueue(KEY, pkg, version, requestedBy));
        JdbcTemplate jdbc = ctx.getBean(JdbcTemplate.class);
        drain(jdbc, Duration.ofMinutes(30));
        RESULTS.set("rows_after_drain", table(jdbc));
        RESULTS.set("rows_final", table(jdbc));
    }

    private static String rowState(JdbcTemplate jdbc, String pkg) {
        List<String> s = jdbc.queryForList("SELECT state FROM skald.binary_build WHERE package = ?", String.class, pkg);
        return s.isEmpty() ? null : s.get(0);
    }
}
