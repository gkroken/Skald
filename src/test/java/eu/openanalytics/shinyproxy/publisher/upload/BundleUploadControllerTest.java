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
import eu.openanalytics.shinyproxy.publisher.bundle.TarArchives;
import eu.openanalytics.shinyproxy.publisher.db.TestPostgres;
import eu.openanalytics.shinyproxy.publisher.storage.TestObjectStore;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.BufferedSink;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * bundle.create, bundle.upload and bundle.status over real HTTP, against the whole
 * application with a real PostgreSQL and MinIO (T8 (b)).
 *
 * <p>Deny cases first, for every endpoint: a non-admin (403), a signed-out caller (sent to log
 * in), another content item's ids (404, no oracle), and each request a cross-site page could
 * make -- a form-producible or absent Content-Type, an upload without its header. Then the
 * refusals of the upload itself, then the authorised path to VALIDATED and to a named rule.
 */
public class BundleUploadControllerTest {

    private static final int PORT = 7595;
    private static final long MAX_BYTES = 1024 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper();

    @SuppressWarnings("resource")
    private static final PostgreSQLContainer<?> POSTGRES = TestPostgres.create();

    private static TestObjectStore objects;
    private static ConfigurableApplicationContext app;
    private static JdbcTemplate jdbc;
    private static ShinyProxyClient admin;
    private static ShinyProxyClient plain;
    private static OkHttpClient anonymous;
    private static String base;

    @BeforeAll
    public static void beforeAll() {
        POSTGRES.start();
        objects = new TestObjectStore("skald-bundles");
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
        // The store's credentials as mounted files (configtree), one of the two ways Skald's
        // startup check accepts a secret; in plain text, in any other source, it refuses to start.
        try {
            java.nio.file.Path secrets = java.nio.file.Files.createTempDirectory("skald-test-secrets");
            java.nio.file.Files.writeString(secrets.resolve("skald.storage.access-key"), TestObjectStore.ACCESS_KEY);
            java.nio.file.Files.writeString(secrets.resolve("skald.storage.secret-key"), TestObjectStore.SECRET_KEY);
            org.springframework.core.env.StandardEnvironment environment = new org.springframework.core.env.StandardEnvironment();
            environment.getPropertySources().addFirst(
                new org.springframework.boot.env.ConfigTreePropertySource("test-secrets", secrets));
            application.setEnvironment(environment);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        p.put("skald.bundle.limits.max-compressed-bytes", String.valueOf(MAX_BYTES));
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
        jdbc.update("DELETE FROM skald.content_path");
    }

    // ------------------------------------------------------------------ helpers

    private record Reply(int status, JsonNode body, Response raw) { }

    private static Reply call(ShinyProxyClient who, Request request) throws IOException {
        try (Response r = who == null ? anonymous.newCall(request).execute() : who.newCall(request).execute()) {
            String text = r.body() == null ? "" : r.body().string();
            JsonNode body = null;
            if (text.startsWith("{")) {
                body = JSON.readTree(text);
            }
            return new Reply(r.code(), body, r);
        }
    }

    private static String code(Reply r) {
        return r.body() == null ? null : r.body().path("data").path("code").asText(null);
    }

    private static UUID content(String type) {
        UUID c = UUID.randomUUID();
        jdbc.update("INSERT INTO skald.content (id, title, owner, type) VALUES (?, 't', 'adminuser', ?)", c, type);
        return c;
    }

    private static Request create(UUID c, String mediaType, String body) {
        RequestBody b = mediaType == null ? RequestBody.create(body.getBytes(StandardCharsets.UTF_8))
            : RequestBody.create(body, MediaType.get(mediaType));
        return new Request.Builder().url(base + "/admin/content/" + c + "/bundles").post(b).build();
    }

    private static Request upload(UUID c, UUID b, byte[] bytes, boolean header, String mediaType) {
        Request.Builder r = new Request.Builder().url(base + "/admin/content/" + c + "/bundles/" + b + "/content")
            .put(RequestBody.create(bytes, MediaType.get(mediaType)));
        if (header) {
            r.header("X-Skald-Upload", "1");
        }
        return r.build();
    }

    private static Request status(UUID c, UUID b) {
        return new Request.Builder().url(base + "/admin/content/" + c + "/bundles/" + b).get().build();
    }

    private static UUID newBundle(UUID c) throws IOException {
        Reply r = call(admin, create(c, "application/json", "{}"));
        assertEquals(201, r.status(), String.valueOf(r.body()));
        return UUID.fromString(r.body().path("data").path("id").asText());
    }

    private static byte[] archive() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(TarArchives.bundle(TarArchives.shinyPayload()).end());
        }
        return out.toByteArray();
    }

    private static JsonNode awaitTerminal(UUID c, UUID b) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (System.nanoTime() < until) {
            JsonNode data = call(admin, status(c, b)).body().path("data");
            String state = data.path("state").asText();
            if (state.equals("VALIDATED") || state.equals("REJECTED")) {
                return data;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("bundle " + b + " did not finish validating");
    }

    // ------------------------------------------------------------------ who may (deny first)

    @Test
    public void aNonAdminIsDeniedEveryEndpoint() throws Exception {
        UUID c = content("shiny");
        UUID b = newBundle(c);
        assertEquals(403, call(plain, create(c, "application/json", "{}")).status());
        assertEquals(403, call(plain, upload(c, b, archive(), true, "application/gzip")).status());
        assertEquals(403, call(plain, status(c, b)).status());
        assertEquals("UPLOADING", jdbc.queryForObject("SELECT state FROM skald.bundle WHERE id = ?", String.class, b));
        assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM skald.bundle", Long.class));
    }

    @Test
    public void aSignedOutCallerIsSentToLogInFromEveryEndpoint() throws Exception {
        UUID c = content("shiny");
        UUID b = newBundle(c);
        for (Request r : List.of(create(c, "application/json", "{}"), upload(c, b, archive(), true, "application/gzip"),
                status(c, b))) {
            Reply reply = call(null, r);
            assertEquals(302, reply.status(), r.method() + " " + r.url());
            assertTrue(String.valueOf(reply.raw().header("Location")).contains("/login"), r.method() + " " + r.url());
        }
        assertEquals("UPLOADING", jdbc.queryForObject("SELECT state FROM skald.bundle WHERE id = ?", String.class, b));
    }

    @Test
    public void anotherContentItemsIdsAreNotFoundNotForbidden() throws Exception {
        UUID mine = content("shiny");
        UUID theirs = content("shiny");
        UUID b = newBundle(theirs);
        assertEquals("BUNDLE_NOT_FOUND", code(call(admin, status(mine, b))));
        assertEquals(404, call(admin, status(mine, b)).status());
        assertEquals("BUNDLE_NOT_FOUND", code(call(admin, upload(mine, b, archive(), true, "application/gzip"))));
        assertEquals("BUNDLE_NOT_FOUND", code(call(admin, status(mine, UUID.randomUUID()))));
        assertEquals("CONTENT_NOT_FOUND", code(call(admin, create(UUID.randomUUID(), "application/json", "{}"))));
        assertEquals("INVALID_ID", code(call(admin, new Request.Builder()
            .url(base + "/admin/content/not-a-uuid/bundles/" + b).get().build())));
    }

    // ------------------------------------------------------------------ what a cross-site page could send

    @Test
    public void everyFormProducibleOrAbsentContentTypeIsRefusedOnCreate() throws Exception {
        UUID c = content("shiny");
        for (String type : new String[] {"application/x-www-form-urlencoded", "multipart/form-data; boundary=x",
                "text/plain", null}) {
            Reply r = call(admin, create(c, type, "{}"));
            assertEquals(415, r.status(), String.valueOf(type));
            assertEquals("UNSUPPORTED_MEDIA_TYPE", code(r), String.valueOf(type));
        }
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM skald.bundle", Long.class), "nothing was created");
    }

    @Test
    public void anUploadWithoutItsHeaderOrWithAnotherTypeIsRefusedBeforeAnyByteIsStored() throws Exception {
        UUID c = content("shiny");
        UUID b = newBundle(c);
        Reply noHeader = call(admin, upload(c, b, archive(), false, "application/gzip"));
        assertEquals(400, noHeader.status());
        assertEquals("UPLOAD_HEADER_MISSING", code(noHeader));
        for (String type : new String[] {"text/plain", "multipart/form-data; boundary=x",
                "application/x-www-form-urlencoded", "application/octet-stream"}) {
            Reply r = call(admin, upload(c, b, archive(), true, type));
            assertEquals("UNSUPPORTED_MEDIA_TYPE", code(r), type);
        }
        assertEquals("UPLOADING", jdbc.queryForObject("SELECT state FROM skald.bundle WHERE id = ?", String.class, b));
        assertTrue(objects.store.list("skald-bundles", "v1/content/" + c + "/").isEmpty());
    }

    @Test
    public void aCreateBodyIsStrict() throws Exception {
        UUID c = content("shiny");
        assertEquals("BAD_REQUEST", code(call(admin, create(c, "application/json", "{\"label\":\"x\"}"))));
        assertEquals("BAD_REQUEST", code(call(admin, create(c, "application/json", "[]"))));
        assertEquals("BAD_REQUEST", code(call(admin, create(c, "application/json", "{\"a\":1,\"a\":2}"))));
        assertEquals("BAD_REQUEST", code(call(admin, create(c, "application/json", "{"))));
        assertEquals("BAD_REQUEST", code(call(admin, create(c, "application/json", "{} {\"x\":1}"))));
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM skald.bundle", Long.class), "nothing was created");
    }

    // ------------------------------------------------------------------ the upload's own refusals

    @Test
    public void anUploadWithNoLengthOrOverTheLimitIsRefusedBeforeItIsRead() throws Exception {
        UUID c = content("shiny");
        UUID b = newBundle(c);
        byte[] a = archive();
        Request chunked = new Request.Builder().url(base + "/admin/content/" + c + "/bundles/" + b + "/content")
            .header("X-Skald-Upload", "1")
            .put(new RequestBody() {
                @Override
                public MediaType contentType() {
                    return MediaType.get("application/gzip");
                }

                @Override
                public long contentLength() {
                    return -1;
                }

                @Override
                public void writeTo(BufferedSink sink) throws IOException {
                    sink.write(a);
                }
            }).build();
        Reply r = call(admin, chunked);
        assertEquals(411, r.status());
        assertEquals("LENGTH_REQUIRED", code(r));
        Reply big = call(admin, upload(c, b, new byte[(int) MAX_BYTES + 1], true, "application/gzip"));
        assertEquals(413, big.status());
        assertEquals("BUNDLE_TOO_LARGE", code(big));
        assertEquals(MAX_BYTES, big.body().path("data").path("maxBytes").asLong());
        assertEquals("UPLOADING", jdbc.queryForObject("SELECT state FROM skald.bundle WHERE id = ?", String.class, b));
    }

    // ------------------------------------------------------------------ the authorised path

    @Test
    public void anAdminUploadsABundleThatIsValidatedAndARepeatIsAConflictWithItsState() throws Exception {
        UUID c = content("shiny");
        Reply created = call(admin, create(c, "application/json", "{}"));
        assertEquals(201, created.status());
        JsonNode data = created.body().path("data");
        assertEquals("success", created.body().path("status").asText());
        UUID b = UUID.fromString(data.path("id").asText());
        assertEquals("UPLOADING", data.path("state").asText());
        assertEquals(MAX_BYTES, data.path("maxBytes").asLong());
        assertTrue(data.path("uploadUrl").asText().endsWith("/admin/content/" + c + "/bundles/" + b + "/content"));
        assertTrue(String.valueOf(created.raw().header("Location")).endsWith("/admin/content/" + c + "/bundles/" + b));

        byte[] a = archive();
        Reply put = call(admin, upload(c, b, a, true, "application/gzip"));
        assertEquals(200, put.status(), String.valueOf(put.body()));
        assertEquals("VALIDATING", put.body().path("data").path("state").asText());
        assertEquals(TarArchives.sha256(a), put.body().path("data").path("archive").path("sha256").asText());

        JsonNode done = awaitTerminal(c, b);
        assertEquals("VALIDATED", done.path("state").asText(), done.toString());
        assertEquals(2, done.path("inventory").path("files").asInt());
        assertEquals("adminuser", done.path("createdBy").asText());

        Reply again = call(admin, upload(c, b, a, true, "application/gzip"));
        assertEquals(409, again.status());
        assertEquals("BUNDLE_NOT_UPLOADING", code(again));
        assertEquals("VALIDATED", again.body().path("data").path("state").asText());
        assertEquals(false, again.body().path("data").path("retryable").asBoolean(true));
    }

    @Test
    public void aBundleForAnotherTypeIsRejectedNamingItsRule() throws Exception {
        UUID c = content("plumber");
        UUID b = newBundle(c);
        assertEquals(200, call(admin, upload(c, b, archive(), true, "application/gzip")).status());
        JsonNode done = awaitTerminal(c, b);
        assertEquals("REJECTED", done.path("state").asText());
        assertEquals("MANIFEST_TYPE_MISMATCH", done.path("rejection").path("rule").asText());
        assertTrue(done.path("rejection").path("detail").asText().contains("S9:"), done.toString());
    }

    @Test
    public void theUploadIsAudited() throws Exception {
        UUID c = content("shiny");
        UUID b = newBundle(c);
        call(admin, upload(c, b, archive(), true, "application/gzip"));
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT actor, action FROM skald.audit_event WHERE subject_id = ? AND action LIKE 'bundle.%' ORDER BY id",
            b.toString());
        assertEquals("adminuser", rows.get(0).get("actor"));
        assertEquals(List.of("bundle.create", "bundle.upload"), rows.stream().map(r -> r.get("action")).limit(2).toList());
    }
    /**
     * A refusal with most of the body unsent closes the connection (found probing a stopped
     * MinIO): otherwise the server neither reads the rest nor closes, and a client that writes
     * its whole body before reading the reply waits on its own timeout. The socket sends the
     * headers and 1 KiB of a body declared over the limit, then stops, and must read the
     * refusal and then the server's close.
     */
    @Test
    public void aRefusedUploadClosesTheConnectionSoAClientStillSendingIsNotLeftWaiting() throws Exception {
        UUID c = content("shiny");
        UUID b = newBundle(c);
        String auth = java.util.Base64.getEncoder().encodeToString("adminuser:adminuser".getBytes(StandardCharsets.UTF_8));
        try (java.net.Socket socket = new java.net.Socket("localhost", PORT)) {
            socket.setSoTimeout(10_000);
            java.io.OutputStream out = socket.getOutputStream();
            out.write(("PUT /admin/content/" + c + "/bundles/" + b + "/content HTTP/1.1\r\n"
                + "Host: localhost:" + PORT + "\r\n"
                + "Authorization: Basic " + auth + "\r\n"
                + "Content-Type: application/gzip\r\n"
                + "X-Skald-Upload: 1\r\n"
                + "Content-Length: " + (MAX_BYTES + 1) + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.write(new byte[1024]);
            out.flush();
            ByteArrayOutputStream reply = new ByteArrayOutputStream();
            java.io.InputStream in = socket.getInputStream();
            byte[] buffer = new byte[4096];
            int n;
            // Read to the end of the stream: a server that keeps the connection open makes this
            // time out instead.
            while ((n = in.read(buffer)) != -1) {
                reply.write(buffer, 0, n);
            }
            String text = reply.toString(StandardCharsets.US_ASCII);
            assertTrue(text.startsWith("HTTP/1.1 413"), text);
            assertTrue(text.toLowerCase(java.util.Locale.ROOT).contains("\r\nconnection: close\r\n"), text);
            assertTrue(text.contains("BUNDLE_TOO_LARGE"), text);
        }
        assertEquals("UPLOADING", jdbc.queryForObject("SELECT state FROM skald.bundle WHERE id = ?", String.class, b));
    }
}
