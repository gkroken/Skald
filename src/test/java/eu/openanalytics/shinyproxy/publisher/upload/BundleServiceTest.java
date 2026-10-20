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

import eu.openanalytics.shinyproxy.publisher.bundle.ExtractionLimits;
import eu.openanalytics.shinyproxy.publisher.bundle.TarArchives;
import eu.openanalytics.shinyproxy.publisher.db.TestPostgres;
import eu.openanalytics.shinyproxy.publisher.storage.ForwardingObjectStore;
import eu.openanalytics.shinyproxy.publisher.storage.ObjectKeys;
import eu.openanalytics.shinyproxy.publisher.storage.ObjectStoreException;
import eu.openanalytics.shinyproxy.publisher.storage.StoredObject;
import eu.openanalytics.shinyproxy.publisher.storage.TestObjectStore;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The upload's commit point and its replay stages (spec shapes.upload_commit_point,
 * shapes.upload_replay), and whose failure is whose (the Q4 risk row), against a real
 * PostgreSQL and MinIO, with the store's failures injected where they land.
 */
public class BundleServiceTest {

    @SuppressWarnings("resource")
    private static final PostgreSQLContainer<?> POSTGRES = TestPostgres.create();
    private static final String BUCKET = "skald-bundles";
    private static final ExtractionLimits LIMITS = ExtractionLimits.fromOverrides(Map.of());

    private static JdbcTemplate jdbc;
    private static TestObjectStore objects;

    @BeforeAll
    public static void beforeAll() {
        POSTGRES.start();
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .schemas("skald").defaultSchema("skald").locations("classpath:db/migration")
            .load().migrate();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        objects = new TestObjectStore(BUCKET);
    }

    @AfterAll
    public static void afterAll() {
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

    /** A store whose streamed put a test may replace, and whose reads it may fail. */
    private static class Faults extends ForwardingObjectStore {
        volatile Function<InputStream, Optional<StoredObject>> put;
        final AtomicInteger openFailures = new AtomicInteger();

        Faults() {
            super(objects.store);
        }

        @Override
        public Optional<StoredObject> putStreamingIfAbsent(String bucket, String key, InputStream content,
                                                           long declaredLength, String contentType) {
            if (put != null) {
                return put.apply(content);
            }
            return super.putStreamingIfAbsent(bucket, key, content, declaredLength, contentType);
        }

        @Override
        public InputStream open(String bucket, String key) {
            if (openFailures.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                throw new ObjectStoreException("injected: 503 SlowDown on GET " + key);
            }
            return super.open(bucket, key);
        }
    }

    private static final AtomicInteger WAKES = new AtomicInteger();

    private static BundleService service(Faults store) {
        return new BundleService(jdbc, store, BUCKET, LIMITS, WAKES::incrementAndGet);
    }

    private static byte[] archive(String marker) throws IOException {
        Map<String, byte[]> payload = TarArchives.shinyPayload();
        payload.put("app.R", ("library(shiny) # " + marker + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(TarArchives.bundle(payload).end());
        }
        return out.toByteArray();
    }

    private static UUID content() {
        UUID c = UUID.randomUUID();
        jdbc.update("INSERT INTO skald.content (id, title, owner, type) VALUES (?, 't', 'alice', 'shiny')", c);
        return c;
    }

    private static UUID bundle(BundleService s, UUID content) {
        return UUID.fromString((String) s.create(content, "alice").get("id"));
    }

    private static Map<String, Object> put(BundleService s, UUID c, UUID b, byte[] bytes) {
        return s.upload(c, b, "alice", bytes.length, new ByteArrayInputStream(bytes));
    }

    private static TransportError refusal(Runnable r) {
        return assertThrows(TransportException.class, r::run).code();
    }

    private static String state(UUID b) {
        return jdbc.queryForObject("SELECT state FROM skald.bundle WHERE id = ?", String.class, b);
    }

    // ------------------------------------------------------------------ refusals

    @Test
    public void anotherContentItemsBundleIsNotFoundAsIfItDidNotExist() throws Exception {
        BundleService s = service(new Faults());
        UUID mine = content();
        UUID theirs = content();
        UUID b = bundle(s, theirs);
        assertEquals(TransportError.BUNDLE_NOT_FOUND, refusal(() -> s.status(mine, b)));
        assertEquals(TransportError.BUNDLE_NOT_FOUND, refusal(() -> s.status(mine, UUID.randomUUID())));
        byte[] a = archive("x");
        assertEquals(TransportError.BUNDLE_NOT_FOUND, refusal(() -> put(s, mine, b, a)));
        assertEquals("UPLOADING", state(b), "nothing was written through the other item");
        assertTrue(objects.store.head(BUCKET, ObjectKeys.bundleObject(mine, b, ObjectKeys.BUNDLE_ARCHIVE)).isEmpty());
        UUID none = UUID.randomUUID();
        assertEquals(TransportError.CONTENT_NOT_FOUND, refusal(() -> s.create(none, "alice")));
        assertEquals(TransportError.CONTENT_NOT_FOUND, refusal(() -> s.status(none, b)));
    }

    @Test
    public void aBundleThatLeftUploadingRefusesNewBytesWithItsState() throws Exception {
        BundleService s = service(new Faults());
        UUID c = content();
        UUID b = bundle(s, c);
        byte[] a = archive("one");
        assertEquals("VALIDATING", put(s, c, b, a).get("state"));
        TransportException e = assertThrows(TransportException.class, () -> put(s, c, b, a));
        assertEquals(TransportError.BUNDLE_NOT_UPLOADING, e.code());
        assertEquals("VALIDATING", e.extra().get("state"));
        byte[] other = archive("two");
        assertEquals(TransportError.BUNDLE_NOT_UPLOADING, refusal(() -> put(s, c, b, other)));
    }

    @Test
    public void anUploadPastItsDeadlineIsRejectedAbandonedAndRefused() throws Exception {
        BundleService s = service(new Faults());
        UUID c = content();
        UUID b = bundle(s, c);
        jdbc.update("UPDATE skald.bundle SET upload_deadline = now() - interval '1 second' WHERE id = ?", b);
        byte[] a = archive("late");
        TransportException e = assertThrows(TransportException.class, () -> put(s, c, b, a));
        assertEquals(TransportError.BUNDLE_NOT_UPLOADING, e.code());
        assertEquals("REJECTED", e.extra().get("state"));
        assertEquals("UPLOAD_ABANDONED", s.status(c, b).get("rejection") instanceof Map<?, ?> m ? m.get("rule") : null);
    }

    // ------------------------------------------------------------------ the client's failures

    @Test
    public void aShortBodyIsIncompleteAndTheSamePutThenSucceeds() throws Exception {
        BundleService s = service(new Faults());
        UUID c = content();
        UUID b = bundle(s, c);
        byte[] a = archive("short");
        byte[] half = Arrays.copyOf(a, a.length / 2);
        assertEquals(TransportError.UPLOAD_INCOMPLETE,
            refusal(() -> s.upload(c, b, "alice", a.length, new ByteArrayInputStream(half))));
        assertEquals("UPLOADING", state(b));
        assertEquals("VALIDATING", put(s, c, b, a).get("state"));
    }

    @Test
    public void aClientThatGoesAwayIsIncompleteNotTheStore() throws Exception {
        BundleService s = service(new Faults());
        UUID c = content();
        UUID b = bundle(s, c);
        byte[] a = archive("gone");
        InputStream breaks = new InputStream() {
            int n;

            @Override
            public int read() throws IOException {
                if (n++ > 40) {
                    throw new IOException("connection reset by peer");
                }
                return a[n - 1] & 0xFF;
            }
        };
        assertEquals(TransportError.UPLOAD_INCOMPLETE, refusal(() -> s.upload(c, b, "alice", a.length, breaks)));
        assertEquals("UPLOADING", state(b));
    }

    // ------------------------------------------------------------------ the store's failures (Q4)

    @Test
    public void everyStoreFailureOnTheArchivePutIsRetryableAndNeverAVerdict() throws Exception {
        // "Outcome unknown" after the store DID apply it, a 400 IncompleteBody, and a 503
        // SlowDown that outlasted the SDK's retries; each ends in a correct replayed outcome.
        for (String failure : new String[] {"outcome unknown (applied)", "400 IncompleteBody", "503 SlowDown"}) {
            Faults store = new Faults();
            BundleService s = service(store);
            UUID c = content();
            UUID b = bundle(s, c);
            byte[] a = archive(failure);
            store.put = body -> {
                if (failure.startsWith("outcome unknown")) {
                    objects.store.putStreamingIfAbsent(BUCKET, ObjectKeys.bundleObject(c, b, ObjectKeys.BUNDLE_ARCHIVE),
                        body, a.length, "application/gzip");
                } else {
                    try {
                        body.readAllBytes();
                    } catch (IOException e) {
                        throw new AssertionError(e);
                    }
                }
                throw new ObjectStoreException("injected: " + failure);
            };
            TransportException e = assertThrows(TransportException.class, () -> put(s, c, b, a));
            assertEquals(TransportError.STORAGE_UNAVAILABLE, e.code(), failure);
            assertTrue(e.code().retryable());
            assertEquals("UPLOADING", state(b), failure + ": no verdict on the bundle");
            store.put = null;
            assertEquals("VALIDATING", put(s, c, b, a).get("state"), failure + ": the same PUT succeeds");
            assertEquals(archiveSha(a), jdbc.queryForObject("SELECT object_sha256 FROM skald.bundle WHERE id = ?",
                String.class, b));
        }
    }

    @Test
    public void aReplayWithDifferentBytesOverAStoredArchiveIsAConflictAndTheRowStaysUploading() throws Exception {
        BundleService s = service(new Faults());
        UUID c = content();
        UUID b = bundle(s, c);
        byte[] stored = archive("stored");
        objects.store.putStreamingIfAbsent(BUCKET, ObjectKeys.bundleObject(c, b, ObjectKeys.BUNDLE_ARCHIVE),
            new ByteArrayInputStream(stored), stored.length, "application/gzip").orElseThrow();
        byte[] other = archive("other");
        assertEquals(TransportError.BUNDLE_ARCHIVE_CONFLICT, refusal(() -> put(s, c, b, other)));
        assertEquals("UPLOADING", state(b));
        assertEquals("VALIDATING", put(s, c, b, stored).get("state"), "the stored bytes themselves go on");
        assertEquals(archiveSha(stored), jdbc.queryForObject("SELECT object_sha256 FROM skald.bundle WHERE id = ?",
            String.class, b));
    }

    @Test
    public void aShortReplayOverAStoredArchiveIsIncompleteNotAConflict() throws Exception {
        BundleService s = service(new Faults());
        UUID c = content();
        UUID b = bundle(s, c);
        byte[] a = archive("short-replay");
        objects.store.putStreamingIfAbsent(BUCKET, ObjectKeys.bundleObject(c, b, ObjectKeys.BUNDLE_ARCHIVE),
            new ByteArrayInputStream(a), a.length, "application/gzip").orElseThrow();
        byte[] half = Arrays.copyOf(a, a.length / 2);
        assertEquals(TransportError.UPLOAD_INCOMPLETE,
            refusal(() -> s.upload(c, b, "alice", a.length, new ByteArrayInputStream(half))));
        assertEquals("UPLOADING", state(b));
    }

    @Test
    public void aReadBackTheStoreCannotServeIsRetryable() throws Exception {
        Faults store = new Faults();
        BundleService s = service(store);
        UUID c = content();
        UUID b = bundle(s, c);
        byte[] a = archive("readback");
        objects.store.putStreamingIfAbsent(BUCKET, ObjectKeys.bundleObject(c, b, ObjectKeys.BUNDLE_ARCHIVE),
            new ByteArrayInputStream(a), a.length, "application/gzip").orElseThrow();
        store.openFailures.set(1);
        assertEquals(TransportError.STORAGE_UNAVAILABLE, refusal(() -> put(s, c, b, a)));
        assertEquals("UPLOADING", state(b));
        assertEquals("VALIDATING", put(s, c, b, a).get("state"));
    }

    @Test
    public void twoConcurrentPutsOfOneBundleGiveOneSuccessAndOneConflict() throws Exception {
        BundleService s = service(new Faults());
        UUID c = content();
        UUID b = bundle(s, c);
        byte[] a = archive("race");
        CountDownLatch go = new CountDownLatch(1);
        Object[] results = new Object[2];
        Thread[] threads = new Thread[2];
        for (int i = 0; i < 2; i++) {
            int k = i;
            threads[i] = new Thread(() -> {
                try {
                    go.await();
                    results[k] = put(s, c, b, a).get("state");
                } catch (TransportException e) {
                    results[k] = e.code();
                } catch (InterruptedException e) {
                    results[k] = e;
                }
            });
            threads[i].start();
        }
        go.countDown();
        for (Thread t : threads) {
            t.join(60_000);
        }
        // The loser either found the row already moved or moved it second; same bytes, so
        // never a conflict.
        assertEquals(new java.util.HashSet<>(java.util.List.of("VALIDATING", TransportError.BUNDLE_NOT_UPLOADING)),
            new java.util.HashSet<>(Arrays.asList(results)), Arrays.toString(results));
        assertEquals("VALIDATING", state(b));
    }

    @Test
    public void theCommitPointCarriesTheArchiveAndWakesTheValidators() throws Exception {
        BundleService s = service(new Faults());
        UUID c = content();
        UUID b = bundle(s, c);
        byte[] a = archive("commit");
        int before = WAKES.get();
        Map<String, Object> status = put(s, c, b, a);
        assertEquals("VALIDATING", status.get("state"));
        assertEquals(Map.of("sha256", archiveSha(a), "bytes", (long) a.length), status.get("archive"));
        Map<String, Object> r = jdbc.queryForMap("SELECT * FROM skald.bundle WHERE id = ?", b);
        assertEquals(ObjectKeys.bundleObject(c, b, ObjectKeys.BUNDLE_ARCHIVE), r.get("object_key"));
        assertTrue(r.get("validating_at") != null);
        assertTrue(objects.store.head(BUCKET, ObjectKeys.bundleObject(c, b, ObjectKeys.BUNDLE_RECEIPT)).isEmpty(),
            "the PUT writes no receipt (c50e4fa-F1)");
        assertEquals(before + 1, WAKES.get());
        assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM skald.audit_event WHERE action = 'bundle.upload'",
            Long.class));
    }

    private static String archiveSha(byte[] a) {
        return TarArchives.sha256(a);
    }
}
