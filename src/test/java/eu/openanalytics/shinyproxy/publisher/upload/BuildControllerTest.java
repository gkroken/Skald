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
package eu.openanalytics.shinyproxy.publisher.upload;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.openanalytics.containerproxy.ContainerProxyApplication;
import eu.openanalytics.containerproxy.test.helpers.ShinyProxyClient;
import eu.openanalytics.shinyproxy.publisher.build.BuildAdmission;
import eu.openanalytics.shinyproxy.publisher.build.BuildCoordinator;
import eu.openanalytics.shinyproxy.publisher.db.TestPostgres;
import eu.openanalytics.shinyproxy.publisher.storage.BuildLogWriter;
import eu.openanalytics.shinyproxy.publisher.storage.ForwardingObjectStore;
import eu.openanalytics.shinyproxy.publisher.storage.ObjectStoreException;
import eu.openanalytics.shinyproxy.publisher.storage.StoredObject;
import eu.openanalytics.shinyproxy.publisher.storage.TestObjectStore;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * build.create, build.status, build.list and build.cancel over real HTTP, against the whole
 * application with a real PostgreSQL and MinIO (T8 (c)). No build worker runs here: each
 * attempt is placed in the state a case needs, so what is tested is the transport and the
 * rules it answers by.
 *
 * <p>Deny cases first: a non-admin (403) and a signed-out caller (sent to log in) on every
 * endpoint, another content item's ids (404, no oracle), and the bodies a cross-site page
 * could send. Then the shapes, the log states, the list's paging and every row of the cancel
 * table.
 */
public class BuildControllerTest {

    private static final int PORT = 7596;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String LOGS = "skald-logs";
    private static final String IMAGE = "registry:5000/c@sha256:" + "c".repeat(64);
    /** spec/admin-transport-v1.json shapes.bodies["build.status"].response, in its order. */
    private static final List<String> STATUS_FIELDS = List.of("id", "contentId", "bundleId", "retryOf",
        "supersededBy", "state", "createdBy", "createdAt", "startedAt", "publishingAt", "finishedAt", "deadlineAt",
        "error", "image", "recipe", "effectiveLimits", "cancel", "log", "links");

    @SuppressWarnings("resource")
    private static final PostgreSQLContainer<?> POSTGRES = TestPostgres.create();

    private static TestObjectStore objects;
    private static ConfigurableApplicationContext app;
    private static JdbcTemplate jdbc;
    private static ShinyProxyClient admin;
    private static ShinyProxyClient plain;
    private static OkHttpClient anonymous;
    private static String base;
    private static BuildLogWriter logs;

    @BeforeAll
    public static void beforeAll() throws IOException {
        POSTGRES.start();
        objects = new TestObjectStore("skald-bundles");
        objects.createBucket(LOGS);
        logs = new BuildLogWriter(objects.store, LOGS);
        SpringApplication application = new SpringApplication(ContainerProxyApplication.class);
        Properties p = ContainerProxyApplication.getDefaultProperties();
        p.put("spring.config.location", "src/test/resources/application-test-admin.yml");
        p.put("server.port", PORT);
        p.put("management.server.port", PORT % 1000 + 9000);
        p.put("spring.datasource.url", POSTGRES.getJdbcUrl());
        p.put("spring.datasource.username", POSTGRES.getUsername());
        p.put("spring.datasource.password", POSTGRES.getPassword());
        p.put("spring.flyway.schemas", "skald");
        p.put("spring.flyway.default-schema", "skald");
        p.put("skald.storage.endpoint", objects.endpoint());
        // Each case places its own attempts; the limits are admission's, tested in BuildAdmissionTest.
        p.put("skald.builds.admission.per-publisher-active", "1000");
        p.put("skald.builds.admission.queue-capacity", "1000");
        // The store's credentials as mounted files (configtree), as BundleUploadControllerTest.
        Path secrets = Files.createTempDirectory("skald-test-secrets");
        Files.writeString(secrets.resolve("skald.storage.access-key"), TestObjectStore.ACCESS_KEY);
        Files.writeString(secrets.resolve("skald.storage.secret-key"), TestObjectStore.SECRET_KEY);
        org.springframework.core.env.StandardEnvironment environment = new org.springframework.core.env.StandardEnvironment();
        environment.getPropertySources().addFirst(
            new org.springframework.boot.env.ConfigTreePropertySource("test-secrets", secrets));
        application.setEnvironment(environment);
        application.setDefaultProperties(p);
        app = application.run();
        jdbc = app.getBean(JdbcTemplate.class);
        admin = new ShinyProxyClient("adminuser", PORT);
        plain = new ShinyProxyClient("plainuser", PORT);
        base = admin.getBaseUrl();
        anonymous = new OkHttpClient.Builder().followRedirects(false).callTimeout(30, TimeUnit.SECONDS).build();
    }

    @AfterAll
    public static void afterAll() {
        if (app != null) {
            app.stop();
            app.close();
        }
        if (objects != null) {
            objects.close();
        }
        POSTGRES.stop();
    }

    @BeforeEach
    public void clean() {
        jdbc.update("DELETE FROM skald.content");
        jdbc.update("DELETE FROM skald.audit_event");
    }

    // ------------------------------------------------------------------ helpers

    private record Reply(int status, JsonNode body, Response raw, String text) {
        JsonNode data() {
            return body.path("data");
        }
    }

    private static Reply call(ShinyProxyClient who, Request request) throws IOException {
        try (Response r = who == null ? anonymous.newCall(request).execute() : who.newCall(request).execute()) {
            String text = r.body() == null ? "" : r.body().string();
            return new Reply(r.code(), text.startsWith("{") ? JSON.readTree(text) : null, r, text);
        }
    }

    private static String code(Reply r) {
        return r.body() == null ? null : r.body().path("data").path("code").asText(null);
    }

    private static UUID content() {
        UUID c = UUID.randomUUID();
        jdbc.update("INSERT INTO skald.content (id, title, owner, type) VALUES (?, 't', 'adminuser', 'shiny')", c);
        return c;
    }

    private static UUID bundle(UUID content, String state) {
        UUID id = UUID.randomUUID();
        boolean done = state.equals("VALIDATED") || state.equals("REJECTED");
        jdbc.update("INSERT INTO skald.bundle (id, content_id, created_by, state, upload_deadline,"
            + " finished_at, object_key, object_sha256, object_bytes, manifest_sha256,"
            + " inventory_files, inventory_bytes, rejection_rule, validating_at) VALUES (?, ?, 'adminuser', ?,"
            + " now() + interval '1 hour', CASE WHEN ? THEN now() END, ?, ?, 10, ?, 1, 10,"
            + " CASE WHEN ? = 'REJECTED' THEN 'MANIFEST_MISSING' END,"
            + " CASE WHEN ? = 'VALIDATING' THEN now() END)",
            id, content, state, done, "bundles/" + id, "a".repeat(64), "b".repeat(64), state, state);
        return id;
    }

    private static String url(UUID c, String suffix) {
        return base + "/admin/content/" + c + "/builds" + suffix;
    }

    private static Request post(String url, String mediaType, String body) {
        RequestBody b = mediaType == null ? RequestBody.create(body.getBytes(StandardCharsets.UTF_8))
            : RequestBody.create(body, MediaType.get(mediaType));
        return new Request.Builder().url(url).post(b).build();
    }

    private static Request create(UUID c, String body) {
        return post(url(c, ""), "application/json", body);
    }

    private static String createBody(UUID bundle, String key) {
        return "{\"bundleId\":\"" + bundle + "\",\"idempotencyKey\":\"" + key + "\"}";
    }

    private static Request get(String url) {
        return new Request.Builder().url(url).get().build();
    }

    private static Request status(UUID c, UUID b) {
        return get(url(c, "/" + b));
    }

    private static Request cancel(UUID c, UUID b) {
        return post(url(c, "/" + b + "/cancel"), "application/json", "{}");
    }

    private static UUID newBuild(UUID c, UUID bundle) throws IOException {
        Reply r = call(admin, create(c, createBody(bundle, "k-" + UUID.randomUUID())));
        assertEquals(202, r.status(), String.valueOf(r.body()));
        return UUID.fromString(r.data().path("id").asText());
    }

    /** An attempt of a new content item placed in {@code state}, with or without a cancel request. */
    private static UUID[] at(String state, boolean requested) throws IOException {
        UUID c = content();
        UUID b = newBuild(c, bundle(c, "VALIDATED"));
        place(b, state, requested);
        return new UUID[] {c, b};
    }

    private static void place(UUID build, String state, boolean requested) {
        boolean leased = state.equals("RUNNING") || state.equals("PUBLISHING");
        boolean terminal = !leased && !state.equals("QUEUED");
        boolean image = state.equals("SUCCEEDED") || state.equals("PUBLISHING");
        jdbc.update("UPDATE skald.build SET state = ?, lease_generation = 1,"
            + " lease_owner = CASE WHEN ? THEN 'worker-7' END,"
            + " lease_expires_at = CASE WHEN ? THEN now() + interval '1 hour' END,"
            + " worker_handle = 'container-abc', deadline_at = now() + interval '1 hour',"
            + " started_at = CASE WHEN ? <> 'QUEUED' THEN now() END, finished_at = CASE WHEN ? THEN now() END,"
            + " output_image = CASE WHEN ? THEN ? END,"
            + " provenance = CASE WHEN ? THEN '{\"schema\": \"t\"}'::jsonb END,"
            + " cancel_requested_at = CASE WHEN ? THEN now() END WHERE id = ?",
            state, leased, leased, state, terminal, image, IMAGE, image, requested, build);
    }

    private static List<String> fields(JsonNode node) {
        List<String> out = new ArrayList<>();
        for (Iterator<String> names = node.fieldNames(); names.hasNext(); ) {
            out.add(names.next());
        }
        return out;
    }

    private static int builds() {
        return jdbc.queryForObject("SELECT count(*) FROM skald.build", Integer.class);
    }

    private static String state(UUID build) {
        return jdbc.queryForObject("SELECT state FROM skald.build WHERE id = ?", String.class, build);
    }

    // ------------------------------------------------------------------ who may (deny first)

    @Test
    public void aNonAdminIsDeniedEveryEndpoint() throws Exception {
        UUID c = content();
        UUID bundle = bundle(c, "VALIDATED");
        UUID b = newBuild(c, bundle);
        for (Request r : List.of(create(c, createBody(bundle, "other")), get(url(c, "")), status(c, b), cancel(c, b))) {
            assertEquals(403, call(plain, r).status(), r.method() + " " + r.url());
        }
        assertEquals(1, builds());
        assertEquals("QUEUED", state(b));
        assertEquals(null, jdbc.queryForObject("SELECT cancel_requested_at FROM skald.build WHERE id = ?",
            Object.class, b));
    }

    @Test
    public void aSignedOutCallerIsSentToLogInFromEveryEndpoint() throws Exception {
        UUID c = content();
        UUID bundle = bundle(c, "VALIDATED");
        UUID b = newBuild(c, bundle);
        for (Request r : List.of(create(c, createBody(bundle, "other")), get(url(c, "")), status(c, b), cancel(c, b))) {
            Reply reply = call(null, r);
            assertEquals(302, reply.status(), r.method() + " " + r.url());
            assertTrue(String.valueOf(reply.raw().header("Location")).contains("/login"), r.method() + " " + r.url());
        }
        assertEquals(1, builds());
        assertEquals("QUEUED", state(b));
    }

    @Test
    public void anotherContentItemsBuildsAndBundlesAreNotFoundNotForbidden() throws Exception {
        UUID mine = content();
        UUID theirs = content();
        UUID theirBundle = bundle(theirs, "VALIDATED");
        UUID b = newBuild(theirs, theirBundle);
        place(b, "FAILED", false);
        Reply s = call(admin, status(mine, b));
        assertEquals(404, s.status());
        assertEquals("BUILD_NOT_FOUND", code(s));
        assertEquals("BUILD_NOT_FOUND", code(call(admin, status(mine, UUID.randomUUID()))));
        assertEquals("BUILD_NOT_FOUND", code(call(admin, cancel(mine, b))));
        assertEquals("BUNDLE_NOT_FOUND", code(call(admin, create(mine, createBody(theirBundle, "k")))));
        assertEquals("BUILD_NOT_FOUND", code(call(admin, create(mine,
            "{\"retryOf\":\"" + b + "\",\"idempotencyKey\":\"k2\"}"))));
        assertEquals(0, call(admin, get(url(mine, ""))).data().path("items").size());
        assertEquals("BAD_REQUEST", code(call(admin, get(url(mine, "?before=" + b)))));
        UUID none = UUID.randomUUID();
        assertEquals("CONTENT_NOT_FOUND", code(call(admin, create(none, createBody(theirBundle, "k")))));
        assertEquals("CONTENT_NOT_FOUND", code(call(admin, get(url(none, "")))));
        assertEquals("CONTENT_NOT_FOUND", code(call(admin, status(none, b))));
        assertEquals("CONTENT_NOT_FOUND", code(call(admin, cancel(none, b))));
        assertEquals("INVALID_ID", code(call(admin, get(base + "/admin/content/" + mine + "/builds/not-a-uuid"))));
        assertEquals(1, builds());
        assertEquals("FAILED", state(b));
    }

    // ------------------------------------------------------------------ what a cross-site page could send

    @Test
    public void everyFormProducibleOrAbsentContentTypeIsRefusedOnCreateAndCancel() throws Exception {
        UUID c = content();
        UUID bundle = bundle(c, "VALIDATED");
        UUID b = newBuild(c, bundle);
        for (String type : new String[] {"application/x-www-form-urlencoded", "multipart/form-data; boundary=x",
                "text/plain", null}) {
            Reply r = call(admin, post(url(c, ""), type, createBody(bundle, "form")));
            assertEquals(415, r.status(), String.valueOf(type));
            assertEquals("UNSUPPORTED_MEDIA_TYPE", code(r), String.valueOf(type));
            Reply x = call(admin, post(url(c, "/" + b + "/cancel"), type, "{}"));
            assertEquals(415, x.status(), String.valueOf(type));
            assertEquals("UNSUPPORTED_MEDIA_TYPE", code(x), String.valueOf(type));
        }
        assertEquals(1, builds(), "nothing was created");
        assertEquals("QUEUED", state(b), "nothing was cancelled");
    }

    @Test
    public void createAndCancelBodiesAreStrict() throws Exception {
        UUID c = content();
        UUID bundle = bundle(c, "VALIDATED");
        assertEquals("BAD_REQUEST", code(call(admin, create(c, "{\"bundleId\":\"" + bundle
            + "\",\"idempotencyKey\":\"k\",\"recipe\":{}}"))));
        assertEquals("BAD_REQUEST", code(call(admin, create(c, "{\"bundleId\":7,\"idempotencyKey\":\"k\"}"))));
        assertEquals("BAD_REQUEST", code(call(admin, create(c, "{\"bundleId\":\"" + bundle
            + "\",\"idempotencyKey\":7}"))));
        assertEquals("INVALID_ID", code(call(admin, create(c, "{\"bundleId\":\"1-2-3-4-5\",\"idempotencyKey\":\"k\"}"))));
        assertEquals("BAD_REQUEST", code(call(admin, create(c, "{\"idempotencyKey\":\"k\"}"))),
            "neither a bundle nor an attempt to retry");
        assertEquals("KEY_INVALID", code(call(admin, create(c, "{\"bundleId\":\"" + bundle + "\"}"))));
        assertEquals("KEY_INVALID", code(call(admin, create(c, createBody(bundle, "has space")))));
        assertEquals("BAD_REQUEST", code(call(admin, create(c, createBody(bundle, "k") + " {}"))));
        assertEquals(0, builds(), "nothing was created");
        UUID b = newBuild(c, bundle);
        assertEquals("BAD_REQUEST", code(call(admin, post(url(c, "/" + b + "/cancel"), "application/json", ""))));
        assertEquals("BAD_REQUEST", code(call(admin, post(url(c, "/" + b + "/cancel"), "application/json",
            "{\"force\":true}"))));
        assertEquals("QUEUED", state(b), "nothing was cancelled");
    }

    // ------------------------------------------------------------------ build.create

    @Test
    public void aCreateIsAcceptedNotAwaitedAndItsReplayIsTheSameAttempt() throws Exception {
        UUID c = content();
        UUID bundle = bundle(c, "VALIDATED");
        Reply first = call(admin, create(c, createBody(bundle, "k1")));
        assertEquals(202, first.status(), String.valueOf(first.body()));
        assertEquals("success", first.body().path("status").asText());
        JsonNode d = first.data();
        List<String> expected = new ArrayList<>(STATUS_FIELDS);
        expected.add("created");
        expected.add("supersededBuildId");
        assertEquals(expected, fields(d));
        UUID id = UUID.fromString(d.path("id").asText());
        assertTrue(d.path("created").asBoolean());
        assertTrue(d.path("supersededBuildId").isNull());
        assertEquals("QUEUED", d.path("state").asText());
        assertEquals(bundle.toString(), d.path("bundleId").asText());
        assertEquals("adminuser", d.path("createdBy").asText());
        assertEquals(base + "/admin/content/" + c + "/builds/" + id, first.raw().header("Location"));
        assertEquals(first.raw().header("Location"), d.path("links").path("self").asText());

        Reply again = call(admin, create(c, createBody(bundle, "k1")));
        assertEquals(202, again.status());
        assertFalse(again.data().path("created").asBoolean(true));
        assertEquals(id.toString(), again.data().path("id").asText());

        // A second request supersedes the queued one, and its replay names it again.
        Reply second = call(admin, create(c, createBody(bundle, "k2")));
        assertEquals(id.toString(), second.data().path("supersededBuildId").asText());
        Reply secondAgain = call(admin, create(c, createBody(bundle, "k2")));
        assertFalse(secondAgain.data().path("created").asBoolean(true));
        assertEquals(id.toString(), secondAgain.data().path("supersededBuildId").asText());
        assertEquals("CANCELLED", state(id));

        Reply reused = call(admin, create(c, createBody(bundle(c, "VALIDATED"), "k1")));
        assertEquals(409, reused.status());
        assertEquals("KEY_REUSED_WITH_DIFFERENT_INPUTS", code(reused));
        Reply notValidated = call(admin, create(c, createBody(bundle(c, "VALIDATING"), "k3")));
        assertEquals(409, notValidated.status());
        assertEquals("BUNDLE_NOT_VALIDATED", code(notValidated));
        assertEquals("VALIDATING", notValidated.data().path("bundleState").asText());
        assertEquals(2, builds());
        assertEquals("adminuser", jdbc.queryForObject("SELECT actor FROM skald.audit_event WHERE action ="
            + " 'build.admitted' AND subject_id = ?", String.class, id.toString()));
    }

    @Test
    public void aRetryReusesItsAttemptsBundle() throws Exception {
        UUID c = content();
        UUID bundle = bundle(c, "VALIDATED");
        UUID failed = newBuild(c, bundle);
        Reply early = call(admin, create(c, "{\"retryOf\":\"" + failed + "\",\"idempotencyKey\":\"r\"}"));
        assertEquals(409, early.status());
        assertEquals("RETRY_NOT_TERMINAL", code(early));
        assertEquals("QUEUED", early.data().path("retryOfState").asText());
        place(failed, "FAILED", false);
        Reply other = call(admin, create(c, "{\"bundleId\":\"" + bundle(c, "VALIDATED") + "\",\"retryOf\":\""
            + failed + "\",\"idempotencyKey\":\"r\"}"));
        assertEquals(400, other.status());
        assertEquals("RETRY_BUNDLE_MISMATCH", code(other));
        Reply retry = call(admin, create(c, "{\"retryOf\":\"" + failed + "\",\"idempotencyKey\":\"r\"}"));
        assertEquals(202, retry.status(), String.valueOf(retry.body()));
        assertEquals(bundle.toString(), retry.data().path("bundleId").asText());
        assertEquals(failed.toString(), retry.data().path("retryOf").asText());
        Reply named = call(admin, create(c, "{\"bundleId\":\"" + bundle + "\",\"retryOf\":\"" + failed
            + "\",\"idempotencyKey\":\"r\"}"));
        assertFalse(named.data().path("created").asBoolean(true));
        assertEquals(retry.data().path("id").asText(), named.data().path("id").asText());
        assertEquals(2, builds());
    }

    // ------------------------------------------------------------------ build.status

    @Test
    public void theStatusShowsWhatAPublisherNeedsAndNoInternals() throws Exception {
        UUID[] cb = at("PUBLISHING", false);
        Reply r = call(admin, status(cb[0], cb[1]));
        assertEquals(200, r.status());
        JsonNode d = r.data();
        assertEquals(STATUS_FIELDS, fields(d));
        String raw = r.body().toString();
        for (String internal : new String[] {"worker-7", "container-abc", "lease", "Generation", "workerHandle"}) {
            assertFalse(raw.contains(internal), internal + " in " + raw);
        }
        assertTrue(d.path("image").isNull(), "an image is the output only once SUCCEEDED");
        assertTrue(d.path("error").isNull());
        assertTrue(d.path("cancel").isNull());
        assertNotNull(d.path("startedAt").textValue());
        String self = base + "/admin/content/" + cb[0] + "/builds/" + cb[1];
        assertEquals(self, d.path("links").path("self").asText());
        assertEquals(self + "/logs", d.path("links").path("logs").asText());
        assertEquals(self + "/logs/stream", d.path("links").path("stream").asText());
        assertEquals(self + "/cancel", d.path("links").path("cancel").asText());
        assertTrue(d.path("recipe").isObject());
        assertTrue(d.path("effectiveLimits").isObject());

        jdbc.update("UPDATE skald.build SET state = 'SUCCEEDED', finished_at = now(), lease_owner = NULL,"
            + " lease_expires_at = NULL WHERE id = ?", cb[1]);
        assertEquals(IMAGE, call(admin, status(cb[0], cb[1])).data().path("image").asText());

        UUID[] failed = at("FAILED", false);
        jdbc.update("UPDATE skald.build SET error_code = 'BUILD_FAILED', error_detail = ? WHERE id = ?",
            "restore failed \u202e here", failed[1]);
        Reply f = call(admin, status(failed[0], failed[1]));
        assertEquals(List.of("code", "detail"), fields(f.data().path("error")));
        assertEquals("BUILD_FAILED", f.data().path("error").path("code").asText());
        assertEquals("restore failed \u202e here", f.data().path("error").path("detail").asText());
        assertFalse(f.text().chars().anyMatch(ch -> ch > 0x7e), "the wire is ASCII: " + f.text());
        assertTrue(f.text().contains("\\u202E") || f.text().contains("\\u202e"), f.text());
    }

    @Test
    public void cancelOutcomeNamesWhatBecameOfTheRequest() throws Exception {
        String[][] table = {
            {"RUNNING", "requested"}, {"CANCELLED", "cancelled"}, {"PUBLISHING", "too_late"},
            {"SUCCEEDED", "too_late"}, {"FAILED", "too_late"}, {"TIMED_OUT", "overtaken"}, {"INTERRUPTED", "overtaken"},
        };
        for (String[] row : table) {
            UUID[] cb = at(row[0], true);
            JsonNode cancel = call(admin, status(cb[0], cb[1])).data().path("cancel");
            assertEquals(List.of("requestedAt", "outcome"), fields(cancel), row[0]);
            assertEquals(row[1], cancel.path("outcome").asText(), row[0]);
            assertNotNull(cancel.path("requestedAt").textValue(), row[0]);
        }
        // Superseded: CANCELLED with no request of its own.
        UUID[] superseded = at("CANCELLED", false);
        assertTrue(call(admin, status(superseded[0], superseded[1])).data().path("cancel").isNull());
    }

    private static String logState(UUID c, UUID b) throws IOException {
        return call(admin, status(c, b)).data().path("log").path("state").asText();
    }

    @Test
    public void theLogStateIsReadFromTheLogItself() throws Exception {
        UUID c = content();
        UUID queued = newBuild(c, bundle(c, "VALIDATED"));
        assertEquals("pending", logState(c, queued));
        assertTrue(call(admin, status(c, queued)).data().path("log").path("final").isNull());

        UUID[] neverRan = at("QUEUED", false);
        call(admin, cancel(neverRan[0], neverRan[1]));
        assertEquals("none", logState(neverRan[0], neverRan[1]));

        UUID[] running = at("RUNNING", false);
        assertEquals("pending", logState(running[0], running[1]), "claimed, nothing written yet");
        logs.appendChunk(running[0], running[1], 1, "{\"n\":1,\"t\":\"x\"}\n".getBytes(StandardCharsets.UTF_8));
        logs.publishIndex(running[0], running[1], 1);
        assertEquals("live", logState(running[0], running[1]));

        place(running[1], "FAILED", false);
        assertEquals("finishing", logState(running[0], running[1]), "settled, no final record yet");
        logs.finalise(running[0], running[1], 1, "FAILED:BUILD_FAILED", true);
        JsonNode log = call(admin, status(running[0], running[1])).data().path("log");
        assertEquals("final", log.path("state").asText());
        assertEquals(List.of("outcome", "complete", "truncated"), fields(log.path("final")));
        assertEquals("FAILED:BUILD_FAILED", log.path("final").path("outcome").asText());
        assertTrue(log.path("final").path("complete").asBoolean());
        assertTrue(log.path("final").path("truncated").asBoolean());

        UUID[] old = at("SUCCEEDED", false);
        jdbc.update("UPDATE skald.build SET finished_at = now() - interval '91 days', created_at = now() - interval"
            + " '92 days', started_at = now() - interval '92 days', publishing_at = now() - interval '91 days'"
            + " WHERE id = ?", old[1]);
        assertEquals("expired", logState(old[0], old[1]));
        jdbc.update("UPDATE skald.build SET finished_at = now() - interval '89 days' WHERE id = ?", old[1]);
        assertEquals("finishing", logState(old[0], old[1]), "within retention");
    }

    @Test
    public void aLogStoreThatCannotBeReadLeavesTheStatusAnswering() throws Exception {
        UUID[] running = at("RUNNING", false);
        boolean[] down = {true};
        ForwardingObjectStore failing = new ForwardingObjectStore(objects.store) {
            @Override
            public Optional<StoredObject> head(String bucket, String key) {
                if (down[0]) {
                    throw new ObjectStoreException("injected: the store is down");
                }
                return super.head(bucket, key);
            }
        };
        BuildService service = new BuildService(jdbc, app.getBean(BuildAdmission.class),
            app.getBean(BuildCoordinator.class), new BuildLogWriter(failing, LOGS));
        Map<String, Object> s = service.status(running[0], running[1], p -> p);
        assertEquals("RUNNING", s.get("state"));
        assertEquals("storage_unavailable", ((Map<?, ?>) s.get("log")).get("state"));
        down[0] = false;
        assertEquals("pending", ((Map<?, ?>) service.status(running[0], running[1], p -> p).get("log")).get("state"));
    }

    // ------------------------------------------------------------------ build.list

    @Test
    public void theListIsNewestFirstAndPagesByKeyset() throws Exception {
        UUID c = content();
        UUID bundle = bundle(c, "VALIDATED");
        List<UUID> made = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            UUID b = newBuild(c, bundle);
            place(b, i % 2 == 0 ? "FAILED" : "SUCCEEDED", false);
            made.add(b);
        }
        // Two attempts with one created_at, at positions 4 and 5 newest first, so pages of two
        // split them: only the keyset's id tie-break carries the second across the boundary.
        jdbc.update("UPDATE skald.build SET created_at = (SELECT created_at FROM skald.build WHERE id = ?) WHERE id = ?",
            made.get(1), made.get(2));
        List<String> expected = jdbc.queryForList("SELECT id::text FROM skald.build WHERE content_id = ?"
            + " ORDER BY created_at DESC, id DESC", String.class, c);
        List<String> seen = new ArrayList<>();
        String next = null;
        int pages = 0;
        do {
            Reply page = call(admin, get(url(c, "?limit=2" + (next == null ? "" : "&before=" + next))));
            assertEquals(200, page.status(), String.valueOf(page.body()));
            assertEquals(List.of("items", "next"), fields(page.data()));
            for (JsonNode item : page.data().path("items")) {
                assertEquals(STATUS_FIELDS, fields(item));
                seen.add(item.path("id").asText());
            }
            next = page.data().path("next").isNull() ? null : page.data().path("next").asText();
            pages++;
        } while (next != null && pages < 10);
        assertEquals(expected, seen);
        assertEquals(3, pages, "a full last page has no next");

        Reply all = call(admin, get(url(c, "")));
        assertEquals(6, all.data().path("items").size(), "default limit");
        assertTrue(all.data().path("next").isNull());
        Reply failedOnly = call(admin, get(url(c, "?state=FAILED")));
        assertEquals(3, failedOnly.data().path("items").size());
        Reply both = call(admin, get(url(c, "?state=FAILED&state=SUCCEEDED")));
        assertEquals(6, both.data().path("items").size());
        Reply one = call(admin, get(url(c, "?limit=1&state=SUCCEEDED")));
        assertEquals(1, one.data().path("items").size());
        assertEquals("SUCCEEDED", one.data().path("items").get(0).path("state").asText());
        assertEquals(one.data().path("items").get(0).path("id").asText(), one.data().path("next").asText());
    }

    /** Every attempt at one created_at: the order, and the paging, are the ids' alone. */
    @Test
    public void attemptsCreatedAtOneInstantAreOrderedAndPagedById() throws Exception {
        UUID c = content();
        UUID bundle = bundle(c, "VALIDATED");
        for (int i = 0; i < 7; i++) {
            place(newBuild(c, bundle), "FAILED", false);
        }
        jdbc.update("UPDATE skald.build SET created_at = '2026-01-01T00:00:00Z' WHERE content_id = ?", c);
        List<String> expected = jdbc.queryForList("SELECT id::text FROM skald.build WHERE content_id = ?"
            + " ORDER BY id DESC", String.class, c);
        List<String> seen = new ArrayList<>();
        for (JsonNode item : call(admin, get(url(c, ""))).data().path("items")) {
            seen.add(item.path("id").asText());
        }
        assertEquals(expected, seen);
        seen.clear();
        String next = null;
        do {
            Reply page = call(admin, get(url(c, "?limit=3" + (next == null ? "" : "&before=" + next))));
            for (JsonNode item : page.data().path("items")) {
                seen.add(item.path("id").asText());
            }
            next = page.data().path("next").isNull() ? null : page.data().path("next").asText();
        } while (next != null && seen.size() < 20);
        assertEquals(expected, seen);
    }

    @Test
    public void listParametersAreChecked() throws Exception {
        UUID c = content();
        UUID b = newBuild(c, bundle(c, "VALIDATED"));
        Reply twice = call(admin, get(url(c, "?before=" + b + "&before=" + b)));
        assertEquals(400, twice.status(), "before given twice, both naming a real attempt");
        assertEquals("BAD_REQUEST", code(twice));
        for (String q : new String[] {"?limit=0", "?limit=201", "?limit=abc", "?limit=1&limit=2", "?limit=-1",
                "?state=queued", "?state=DONE", "?status=FAILED", "?before=" + UUID.randomUUID(),
                "?before=" + UUID.randomUUID() + "&before=" + UUID.randomUUID()}) {
            Reply r = call(admin, get(url(c, q)));
            assertEquals(400, r.status(), q);
            assertEquals("BAD_REQUEST", code(r), q);
        }
        assertEquals("INVALID_ID", code(call(admin, get(url(c, "?before=1-2-3-4-5")))));
        assertEquals(200, call(admin, get(url(c, "?limit=200"))).status());
        assertEquals(200, call(admin, get(url(c, "?limit=1"))).status());
    }

    // ------------------------------------------------------------------ build.cancel

    /** Every row of shapes.cancel_replies over HTTP: status, code or outcome, and the row unchanged. */
    @Test
    public void everyRowOfTheCancelTableOverHttp() throws Exception {
        Object[][] table = {
            {"RUNNING", false, 202, "requested"},
            {"RUNNING", true, 202, "requested"},
            {"PUBLISHING", false, 409, "CANCEL_REFUSED_PUBLISHING"},
            {"PUBLISHING", true, 409, "CANCEL_TOO_LATE"},
            {"CANCELLED", true, 202, "cancelled"},
            {"CANCELLED", false, 409, "ALREADY_FINISHED"},
            {"SUCCEEDED", true, 409, "CANCEL_TOO_LATE"},
            {"SUCCEEDED", false, 409, "ALREADY_FINISHED"},
            {"FAILED", true, 409, "CANCEL_TOO_LATE"},
            {"FAILED", false, 409, "ALREADY_FINISHED"},
            {"TIMED_OUT", true, 409, "ALREADY_FINISHED"},
            {"TIMED_OUT", false, 409, "ALREADY_FINISHED"},
            {"INTERRUPTED", true, 409, "ALREADY_FINISHED"},
            {"INTERRUPTED", false, 409, "ALREADY_FINISHED"},
        };
        for (Object[] row : table) {
            String state = (String) row[0];
            String what = state + " requested=" + row[1];
            UUID[] cb = at(state, (Boolean) row[1]);
            Reply r = call(admin, cancel(cb[0], cb[1]));
            assertEquals(row[2], r.status(), what + " " + r.body());
            if (r.status() == 202) {
                assertEquals(List.of("outcome", "build"), fields(r.data()), what);
                assertEquals(row[3], r.data().path("outcome").asText(), what);
                assertEquals(STATUS_FIELDS, fields(r.data().path("build")), what);
                assertEquals(state, r.data().path("build").path("state").asText(), what);
            } else {
                assertEquals(row[3], code(r), what);
                assertEquals(state, r.data().path("state").asText(), what);
                assertFalse(r.data().path("retryable").asBoolean(true), what);
            }
            assertEquals(state, state(cb[1]), what);
        }
        UUID c = content();
        UUID queued = newBuild(c, bundle(c, "VALIDATED"));
        Reply first = call(admin, cancel(c, queued));
        assertEquals(202, first.status());
        assertEquals("cancelled", first.data().path("outcome").asText());
        assertEquals("CANCELLED", first.data().path("build").path("state").asText());
        assertEquals("cancelled", first.data().path("build").path("cancel").path("outcome").asText());
        Reply again = call(admin, cancel(c, queued));
        assertEquals(202, again.status(), "a retried cancel reports the same outcome");
        assertEquals("cancelled", again.data().path("outcome").asText());
        assertEquals(new TreeSet<>(Set.of("build.cancel.cancelled")), new TreeSet<>(jdbc.queryForList(
            "SELECT action FROM skald.audit_event WHERE subject_id = ? AND actor = 'adminuser' AND action LIKE"
                + " 'build.cancel.%'", String.class, queued.toString())));
    }
}
