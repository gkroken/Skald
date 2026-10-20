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
import eu.openanalytics.shinyproxy.publisher.storage.BundleReceipt;
import eu.openanalytics.shinyproxy.publisher.storage.BundleWriter;
import eu.openanalytics.shinyproxy.publisher.storage.ForwardingObjectStore;
import eu.openanalytics.shinyproxy.publisher.storage.ObjectKeys;
import eu.openanalytics.shinyproxy.publisher.storage.ObjectStoreException;
import eu.openanalytics.shinyproxy.publisher.storage.TestObjectStore;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bundle validation as claimed, leased work (WORKPLAN-BUNDLES.md T8 plan; V5), against a real
 * PostgreSQL and a real MinIO.
 *
 * <p>Weighted towards what must NOT happen, because each of these was a review finding before
 * it was code: a valid bundle rejected for an outage (95e2185-F1), a verdict written by a
 * validator that lost its lease, a store's wrong bytes blamed on the bundle, a second run
 * that cannot reproduce the first one's objects (95e2185 N1), and a bundle lost with the
 * instance that accepted it (6a23bcb-F1).
 */
public class BundleValidatorTest {

    @SuppressWarnings("resource")
    private static final PostgreSQLContainer<?> POSTGRES = TestPostgres.create();
    private static final String BUCKET = "skald-bundles";
    private static final ExtractionLimits LIMITS = ExtractionLimits.fromOverrides(Map.of());

    private static JdbcTemplate jdbc;
    private static TestObjectStore objects;

    @TempDir
    Path workspace;

    /** Fails what a test asks it to fail, so a fault lands exactly where it is meant to. */
    private static class Faults extends ForwardingObjectStore {
        final AtomicInteger openFailures = new AtomicInteger();
        final AtomicInteger writeFailures = new AtomicInteger();
        volatile byte[] substitute;

        Faults() {
            super(objects.store);
        }

        @Override
        public InputStream open(String bucket, String key) {
            if (openFailures.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                throw new ObjectStoreException("injected: 503 SlowDown on GET " + key);
            }
            if (substitute != null) {
                return new ByteArrayInputStream(substitute);
            }
            return super.open(bucket, key);
        }

        @Override
        public Optional<eu.openanalytics.shinyproxy.publisher.storage.StoredObject> putIfAbsent(
                String bucket, String key, byte[] content, String contentType) {
            if (writeFailures.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                throw new ObjectStoreException("injected: outcome unknown on PUT " + key);
            }
            return super.putIfAbsent(bucket, key, content, contentType);
        }
    }

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

    // ------------------------------------------------------------------ fixtures

    private static ValidationSettings settings(int maxClaims) {
        return new ValidationSettings(1, Duration.ofSeconds(300), Duration.ofSeconds(30),
                Duration.ofMinutes(30), maxClaims, Duration.ofSeconds(15), Optional.empty());
    }

    private static byte[] gzip(byte[] tar) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(tar);
        }
        return out.toByteArray();
    }

    private static byte[] validArchive() throws IOException {
        return gzip(TarArchives.bundle(TarArchives.shinyPayload()).end());
    }

    /** A content item of {@code type} and a bundle in VALIDATING whose archive is stored. */
    private static UUID[] validating(String type, byte[] archive) {
        UUID c = UUID.randomUUID();
        jdbc.update("INSERT INTO skald.content (id, title, owner, type) VALUES (?, 't', 'alice', ?)", c, type);
        UUID b = UUID.randomUUID();
        String key = ObjectKeys.bundleObject(c, b, ObjectKeys.BUNDLE_ARCHIVE);
        new BundleWriter(objects.store, BUCKET).writeArchive(c, b, new ByteArrayInputStream(archive), archive.length)
            .orElseThrow();
        jdbc.update("INSERT INTO skald.bundle (id, content_id, created_by, state, upload_deadline, object_key,"
            + " object_sha256, object_bytes, validating_at) VALUES (?, ?, 'alice', 'VALIDATING',"
            + " now() + interval '1 hour', ?, ?, ?, now())", b, c, key, TarArchives.sha256(archive), archive.length);
        return new UUID[] {c, b};
    }

    private static Map<String, Object> row(UUID bundle) {
        return jdbc.queryForMap("SELECT * FROM skald.bundle WHERE id = ?", bundle);
    }

    private static void lapse(UUID bundle) {
        jdbc.update("UPDATE skald.bundle SET validation_lease_expires_at = now() - interval '1 second'"
            + " WHERE id = ?", bundle);
    }

    private static void due(UUID bundle) {
        jdbc.update("UPDATE skald.bundle SET validation_retry_at = now() - interval '1 second' WHERE id = ?", bundle);
    }

    private BundleValidation validation(ValidationQueue queue, ForwardingObjectStore store) {
        return new BundleValidation(queue, store, BUCKET, LIMITS, workspace);
    }

    private static BundleValidation.Result run(BundleValidation v, ValidationQueue.Claimed c) {
        return v.run(c, () -> false, s -> { });
    }

    // ------------------------------------------------------------------ the platform's verdicts

    @Test
    public void anOutageLongerThanMaxClaimsRetriesIsNeverAPoisonVerdict() throws Exception {
        // 95e2185-F1: every storage failure released its claim, so none was a lapse. Five
        // failures against max-claims 3, then the store comes back: VALIDATED.
        ValidationQueue queue = new ValidationQueue(jdbc, settings(3));
        Faults store = new Faults();
        store.openFailures.set(5);
        UUID b = validating("shiny", validArchive())[1];
        BundleValidation v = validation(queue, store);
        for (int i = 0; i < 5; i++) {
            ValidationQueue.Claimed c = queue.claim("v").orElseThrow(() -> new AssertionError("claimable"));
            assertEquals(BundleValidation.Result.STORAGE_FAILED, run(v, c));
            assertEquals(0, ((Number) row(b).get("validation_claims")).intValue(), "a release is not a lapse");
            assertTrue(((String) row(b).get("validation_storage_error")).contains("503 SlowDown"));
            assertTrue(queue.claim("v").isEmpty(), "held back for retry-after");
            assertEquals(0, queue.reap());
            due(b);
        }
        assertEquals(BundleValidation.Result.VALIDATED, run(v, queue.claim("v").orElseThrow()));
        Map<String, Object> r = row(b);
        assertEquals("VALIDATED", r.get("state"));
        assertNull(r.get("validation_storage_error"), "a success clears the recorded failure");
    }

    @Test
    public void lapsedLeasesUpToMaxClaimsAreInterruptedAndTheBundleIsNeverRunAgain() throws Exception {
        ValidationQueue queue = new ValidationQueue(jdbc, settings(3));
        UUID b = validating("shiny", validArchive())[1];
        for (int i = 0; i < 2; i++) {
            queue.claim("dies-" + i).orElseThrow();
            lapse(b);
            assertEquals(0, queue.reap(), "lapse " + (i + 1) + " of 3 is not yet the verdict");
        }
        queue.claim("dies-2").orElseThrow();
        lapse(b);
        assertTrue(queue.claim("v").isEmpty(), "the third lapse leaves it to the reaper, not to a fourth run");
        assertEquals(1, queue.reap());
        Map<String, Object> r = row(b);
        assertEquals("REJECTED", r.get("state"));
        assertEquals("VALIDATION_INTERRUPTED", r.get("rejection_rule"));
        assertTrue(((String) r.get("rejection_detail")).contains("interrupted 3 times"), (String) r.get("rejection_detail"));
        assertNull(r.get("validation_owner"));
    }

    @Test
    public void aRecordedStorageErrorWinsOverInterrupted() throws Exception {
        ValidationQueue queue = new ValidationQueue(jdbc, settings(1));
        Faults store = new Faults();
        store.openFailures.set(1);
        UUID b = validating("shiny", validArchive())[1];
        assertEquals(BundleValidation.Result.STORAGE_FAILED, run(validation(queue, store), queue.claim("v").orElseThrow()));
        due(b);
        queue.claim("dies").orElseThrow();
        lapse(b);
        assertEquals(1, queue.reap());
        assertEquals("VALIDATION_STORAGE", row(b).get("rejection_rule"));
    }

    @Test
    public void theDeadlineSaysStalledWhenNothingWasRecordedAndStorageWhenSomethingWas() throws Exception {
        ValidationQueue queue = new ValidationQueue(jdbc, settings(3));
        UUID quiet = validating("shiny", validArchive())[1];
        UUID failing = validating("shiny", validArchive())[1];
        jdbc.update("UPDATE skald.bundle SET validation_storage_error = 'injected: 500' WHERE id = ?", failing);
        assertEquals(0, queue.reap(), "inside the deadline nothing is judged");
        jdbc.update("UPDATE skald.bundle SET validating_at = now() - interval '31 minutes'");
        assertEquals(2, queue.reap());
        assertEquals("VALIDATION_STALLED", row(quiet).get("rejection_rule"));
        assertEquals("VALIDATION_STORAGE", row(failing).get("rejection_rule"));
        assertTrue(((String) row(failing).get("rejection_detail")).contains("injected: 500"));
    }

    @Test
    public void anAbandonedUploadIsRejectedOnlyAfterItsDeadline() {
        ValidationQueue queue = new ValidationQueue(jdbc, settings(3));
        UUID c = UUID.randomUUID();
        jdbc.update("INSERT INTO skald.content (id, title, owner, type) VALUES (?, 't', 'alice', 'shiny')", c);
        UUID live = UUID.randomUUID();
        UUID gone = UUID.randomUUID();
        jdbc.update("INSERT INTO skald.bundle (id, content_id, created_by, upload_deadline) VALUES"
            + " (?, ?, 'alice', now() + interval '1 hour'), (?, ?, 'alice', now() - interval '1 second')",
            live, c, gone, c);
        assertEquals(1, queue.reap());
        assertEquals("UPLOADING", row(live).get("state"));
        assertEquals("REJECTED", row(gone).get("state"));
        assertEquals("UPLOAD_ABANDONED", row(gone).get("rejection_rule"));
    }

    // ------------------------------------------------------------------ fencing

    @Test
    public void aValidatorThatLostItsLeaseWritesNoVerdict() throws Exception {
        ValidationQueue queue = new ValidationQueue(jdbc, settings(3));
        UUID b = validating("shiny", validArchive())[1];
        ValidationQueue.Claimed stale = queue.claim("stalled").orElseThrow();
        lapse(b);
        ValidationQueue.Claimed fresh = queue.claim("takes-over").orElseThrow();
        assertEquals(1, ((Number) row(b).get("validation_claims")).intValue(), "the takeover was a lapse");
        assertFalse(queue.renew(stale));
        assertFalse(queue.validated(stale, "a".repeat(64), 1, 1));
        assertFalse(queue.rejected(stale, eu.openanalytics.shinyproxy.publisher.bundle.BundleRule.MANIFEST_MISSING, "x"));
        assertFalse(queue.storageFailed(stale, "x"));
        assertFalse(queue.released(stale));
        assertEquals("VALIDATING", row(b).get("state"));
        assertNull(row(b).get("validation_storage_error"));
        assertEquals(BundleValidation.Result.FENCED, run(validation(queue, new Faults()), stale));
        assertEquals("VALIDATING", row(b).get("state"), "a fenced run changed nothing");
        assertEquals(BundleValidation.Result.VALIDATED, run(validation(queue, new Faults()), fresh));
    }

    @Test
    public void aRelinquishedClaimFencesItsOwnLaterWrites() throws Exception {
        // Held by the database, not only by the run's lost flag: a run that outlives an orderly
        // stop cannot write a verdict on a claim it gave back.
        ValidationQueue queue = new ValidationQueue(jdbc, settings(3));
        UUID b = validating("shiny", validArchive())[1];
        ValidationQueue.Claimed given = queue.claim("stopping").orElseThrow();
        assertTrue(queue.relinquished(given));
        assertFalse(queue.validated(given, "a".repeat(64), 1, 1));
        assertFalse(queue.rejected(given, eu.openanalytics.shinyproxy.publisher.bundle.BundleRule.MANIFEST_MISSING, "x"));
        assertFalse(queue.relinquished(given), "given back once");
        assertEquals("VALIDATING", row(b).get("state"));
    }

    @Test
    public void aLostLeaseClosesTheSourceAndTheRunEndsFenced() throws Exception {
        ValidationQueue queue = new ValidationQueue(jdbc, settings(3));
        UUID b = validating("shiny", validArchive())[1];
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch lost = new CountDownLatch(1);
        AtomicBoolean closed = new AtomicBoolean();
        Faults slow = new Faults() {
            @Override
            public InputStream open(String bucket, String key) {
                InputStream real = super.open(bucket, key);
                return new java.io.FilterInputStream(real) {
                    @Override
                    public int read(byte[] buf, int off, int len) throws IOException {
                        inside.countDown();
                        try {
                            lost.await();
                        } catch (InterruptedException e) {
                            throw new IOException(e);
                        }
                        if (closed.get()) {
                            throw new IOException("closed");
                        }
                        return super.read(buf, off, len);
                    }

                    @Override
                    public void close() throws IOException {
                        closed.set(true);
                        super.close();
                    }
                };
            }
        };
        BundleValidator slowValidator = new BundleValidator(queue, validation(queue, slow), settings(3));
        java.util.concurrent.atomic.AtomicReference<Optional<BundleValidation.Result>> result =
            new java.util.concurrent.atomic.AtomicReference<>();
        Thread t = new Thread(() -> result.set(slowValidator.runOnce("v")));
        t.start();
        assertTrue(inside.await(30, java.util.concurrent.TimeUnit.SECONDS));
        // Another validator takes the lease over while this one is mid-read.
        lapse(b);
        queue.claim("other").orElseThrow();
        slowValidator.renewAll();
        assertTrue(closed.get(), "the renewal that found the lease lost closed the source");
        lost.countDown();
        t.join(30_000);
        assertEquals(Optional.of(BundleValidation.Result.FENCED), result.get());
        assertEquals("VALIDATING", row(b).get("state"));
    }

    // ------------------------------------------------------------------ verdicts on the bundle

    @Test
    public void aValidBundleIsValidatedWithItsObjectsInT4sOrder() throws Exception {
        ValidationQueue queue = new ValidationQueue(jdbc, settings(3));
        byte[] archive = validArchive();
        UUID[] cb = validating("shiny", archive);
        assertEquals(BundleValidation.Result.VALIDATED, run(validation(queue, new Faults()), queue.claim("v").orElseThrow()));
        Map<String, Object> r = row(cb[1]);
        assertEquals("VALIDATED", r.get("state"));
        assertNull(r.get("validation_owner"));
        assertNull(r.get("validation_lease_expires_at"));
        assertEquals(2, ((Number) r.get("inventory_files")).intValue());
        BundleReceipt receipt = new BundleWriter(objects.store, BUCKET).readReceipt(cb[0], cb[1]).orElseThrow();
        assertEquals(TarArchives.sha256(archive), receipt.archiveSha256());
        assertEquals(archive.length, receipt.archiveBytes());
        assertEquals(r.get("manifest_sha256"), receipt.manifestSha256());
        byte[] manifest = TarArchives.manifestFor(TarArchives.shinyPayload());
        assertEquals(TarArchives.sha256(manifest), receipt.manifestSha256(), "manifest.json is the manifest as it arrived");
        byte[] inventory = read(cb, ObjectKeys.BUNDLE_INVENTORY);
        assertEquals("{\"files\":[{\"executable\":false,\"path\":\"app.R\",\"sha256\":\""
            + TarArchives.sha256(TarArchives.shinyPayload().get("app.R")) + "\",\"size\":15},"
            + "{\"executable\":false,\"path\":\"renv.lock\",\"sha256\":\""
            + TarArchives.sha256(TarArchives.shinyPayload().get("renv.lock")) + "\",\"size\":3}],"
            + "\"layout_version\":1}\n", new String(inventory, StandardCharsets.UTF_8));
        try (Stream<Path> left = Files.list(workspace)) {
            assertEquals(List.of(), left.toList(), "the extracted tree is deleted");
        }
    }

    @Test
    public void aRejectedBundleNamesItsRuleAndWritesNoReceipt() throws Exception {
        ValidationQueue queue = new ValidationQueue(jdbc, settings(3));
        Map<String, byte[]> payload = TarArchives.shinyPayload();
        byte[] manifest = TarArchives.manifestFor(payload);
        Map<String, byte[]> lying = new LinkedHashMap<>(payload);
        lying.put("app.R", "library(shinx)\n".getBytes(StandardCharsets.UTF_8));
        TarArchives tar = TarArchives.archive().file("manifest.json", manifest);
        lying.forEach((p, bytes) -> tar.file("app/" + p, bytes));
        UUID[] cb = validating("shiny", gzip(tar.end()));
        assertEquals(BundleValidation.Result.REJECTED, run(validation(queue, new Faults()), queue.claim("v").orElseThrow()));
        assertEquals("INVENTORY_HASH_MISMATCH", row(cb[1]).get("rejection_rule"));
        assertTrue(new BundleWriter(objects.store, BUCKET).readReceipt(cb[0], cb[1]).isEmpty());
    }

    @Test
    public void aRejectionEarlyInALargeArchiveStillNamesItsRuleNotTheStore() throws Exception {
        // The extractor stops at the first bad member; the rest of a large archive is then
        // unread. The digest must still cover every stored byte, or a rejection would be
        // misreported as the store returning other bytes (M6).
        ValidationQueue queue = new ValidationQueue(jdbc, settings(3));
        Map<String, byte[]> payload = TarArchives.shinyPayload();
        byte[] noise = new byte[4 * 1024 * 1024];
        new java.util.Random(7).nextBytes(noise);
        payload.put("data.bin", noise);
        byte[] manifest = TarArchives.manifestFor(payload);
        TarArchives tar = TarArchives.archive().file("manifest.json", manifest)
            .file("app/app.R", "library(shinx)\n".getBytes(StandardCharsets.UTF_8))
            .file("app/renv.lock", payload.get("renv.lock"))
            .file("app/data.bin", noise);
        byte[] archive = gzip(tar.end());
        assertTrue(archive.length > 4 * 1024 * 1024, "incompressible, so most of it follows the bad member");
        UUID[] cb = validating("shiny", archive);
        assertEquals(BundleValidation.Result.REJECTED, run(validation(queue, new Faults()), queue.claim("v").orElseThrow()));
        assertEquals("INVENTORY_HASH_MISMATCH", row(cb[1]).get("rejection_rule"));
    }

    @Test
    public void aBundleOfAnotherTypeIsRefusedUnderS9() throws Exception {
        ValidationQueue queue = new ValidationQueue(jdbc, settings(3));
        UUID[] cb = validating("plumber", validArchive());
        assertEquals(BundleValidation.Result.REJECTED, run(validation(queue, new Faults()), queue.claim("v").orElseThrow()));
        assertEquals("MANIFEST_TYPE_MISMATCH", row(cb[1]).get("rejection_rule"));
        assertTrue(((String) row(cb[1]).get("rejection_detail")).contains("S9:"), (String) row(cb[1]).get("rejection_detail"));
    }

    @Test
    public void bytesTheStoreReturnsThatAreNotTheUploadsAreTheStoresFailureNotTheBundles() throws Exception {
        ValidationQueue queue = new ValidationQueue(jdbc, settings(3));
        UUID b = validating("shiny", validArchive())[1];
        Faults store = new Faults();
        // A different, equally valid bundle: extraction would accept it, so only the digest
        // check can tell; and a corrupt one, which extraction would REJECT -- blaming the
        // bundle for the store's bytes unless the digest is checked first.
        Map<String, byte[]> other = TarArchives.shinyPayload();
        other.put("app.R", "library(shiny) # other\n".getBytes(StandardCharsets.UTF_8));
        for (byte[] substitute : List.of(gzip(TarArchives.bundle(other).end()), "not gzip".getBytes(StandardCharsets.UTF_8))) {
            store.substitute = substitute;
            assertEquals(BundleValidation.Result.STORAGE_FAILED, run(validation(queue, store), queue.claim("v").orElseThrow()));
            Map<String, Object> r = row(b);
            assertEquals("VALIDATING", r.get("state"));
            assertTrue(((String) r.get("validation_storage_error")).contains("not the "), (String) r.get("validation_storage_error"));
            due(b);
        }
    }

    /** A stored stream that throws once at {@code at} bytes, then goes on: a socket read timeout. */
    private static Faults throwsOnceAt(long at) {
        return new Faults() {
            @Override
            public InputStream open(String bucket, String key) {
                InputStream real = super.open(bucket, key);
                return new java.io.FilterInputStream(real) {
                    long read;
                    boolean thrown;

                    @Override
                    public int read() throws IOException {
                        byte[] one = new byte[1];
                        int n = read(one, 0, 1);
                        return n == -1 ? -1 : one[0] & 0xFF;
                    }

                    @Override
                    public int read(byte[] b, int off, int len) throws IOException {
                        if (!thrown && read + len > at) {
                            if (read < at) {
                                int n = super.read(b, off, (int) (at - read));
                                read += Math.max(0, n);
                                return n;
                            }
                            thrown = true;
                            throw new java.net.SocketTimeoutException("Read timed out");
                        }
                        int n = super.read(b, off, len);
                        read += Math.max(0, n);
                        return n;
                    }
                };
            }
        };
    }

    @Test
    public void aReadThatFailsOnceDuringExtractionIsTheStoresFailureNotTheBundles() throws Exception {
        // 2a2186f-F1: the extractor reports a failed source read as ARCHIVE_GZIP_TRUNCATED, and
        // a read that then goes on leaves the digest intact; only the remembered failure tells.
        ValidationQueue queue = new ValidationQueue(jdbc, settings(3));
        UUID b = validating("shiny", validArchive())[1];
        assertEquals(BundleValidation.Result.STORAGE_FAILED,
            run(validation(queue, throwsOnceAt(20)), queue.claim("v").orElseThrow()));
        Map<String, Object> r = row(b);
        assertEquals("VALIDATING", r.get("state"));
        assertNull(r.get("rejection_rule"));
        assertTrue(((String) r.get("validation_storage_error")).contains("Read timed out"),
            (String) r.get("validation_storage_error"));
        due(b);
        assertEquals(BundleValidation.Result.VALIDATED, run(validation(queue, new Faults()), queue.claim("v").orElseThrow()));
    }

    @Test
    public void aReadThatFailsOnceWhileDrainingAfterARejectionIsTheStoresFailure() throws Exception {
        ValidationQueue queue = new ValidationQueue(jdbc, settings(3));
        Map<String, byte[]> payload = TarArchives.shinyPayload();
        byte[] noise = new byte[4 * 1024 * 1024];
        new java.util.Random(11).nextBytes(noise);
        payload.put("data.bin", noise);
        TarArchives tar = TarArchives.archive().file("manifest.json", TarArchives.manifestFor(payload))
            .file("app/app.R", "library(shinx)\n".getBytes(StandardCharsets.UTF_8))
            .file("app/renv.lock", payload.get("renv.lock"))
            .file("app/data.bin", noise);
        UUID b = validating("shiny", gzip(tar.end()))[1];
        // Far past the bad member, so only the drain reaches it.
        assertEquals(BundleValidation.Result.STORAGE_FAILED,
            run(validation(queue, throwsOnceAt(3L * 1024 * 1024)), queue.claim("v").orElseThrow()));
        assertEquals("VALIDATING", row(b).get("state"));
        assertTrue(((String) row(b).get("validation_storage_error")).contains("Read timed out"));
    }

    @Test
    public void anOrderlyStopGivesItsClaimsBackWithNoLapseAndFencesTheRunItLeaves() throws Exception {
        // 2a2186f-F2: a read that ignores interrupts, so only closing the source ends it.
        ValidationQueue queue = new ValidationQueue(jdbc, settings(3));
        UUID b = validating("shiny", validArchive())[1];
        CountDownLatch inside = new CountDownLatch(1);
        Faults blocking = new Faults() {
            @Override
            public InputStream open(String bucket, String key) {
                return new InputStream() {
                    final CountDownLatch closed = new CountDownLatch(1);

                    @Override
                    public int read() throws IOException {
                        return read(new byte[1], 0, 1);
                    }

                    @Override
                    public int read(byte[] buf, int off, int len) throws IOException {
                        inside.countDown();
                        while (true) {
                            try {
                                closed.await();
                                throw new IOException("closed");
                            } catch (InterruptedException ignored) {
                                // A blocking socket read does not answer an interrupt either.
                            }
                        }
                    }

                    @Override
                    public void close() {
                        closed.countDown();
                    }
                };
            }
        };
        ValidationSettings one = new ValidationSettings(1, Duration.ofSeconds(300), Duration.ofSeconds(30),
            Duration.ofMinutes(30), 3, Duration.ofSeconds(15), Optional.of(workspace));
        BundleValidator validator = new BundleValidator(queue, validation(queue, blocking), one);
        validator.start();
        validator.wake();
        assertTrue(inside.await(30, java.util.concurrent.TimeUnit.SECONDS), "the validator is mid-read");
        long started = System.nanoTime();
        validator.stop();
        assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(20)) < 0,
            "closing the source ended the blocked read");
        Map<String, Object> r = row(b);
        assertEquals("VALIDATING", r.get("state"));
        assertNull(r.get("validation_owner"), "given back, not left to lapse");
        assertNull(r.get("validation_storage_error"), "a shutdown is not a storage failure");
        ValidationQueue.Claimed next = queue.claim("next").orElseThrow(() -> new AssertionError("claimable at once"));
        assertEquals(0, ((Number) row(b).get("validation_claims")).intValue(), "the takeover is not a lapse");
        assertEquals(BundleValidation.Result.VALIDATED, run(validation(queue, new Faults()), next));
    }

    // ------------------------------------------------------------------ re-running

    @Test
    public void aSecondRunAfterAFailedWriteReproducesTheFirstRunsObjects() throws Exception {
        // The first run writes manifest.json, then the store fails inventory.json; the retry
        // finds manifest.json already there, compares it, and goes on (95e2185 N1).
        ValidationQueue queue = new ValidationQueue(jdbc, settings(3));
        UUID[] cb = validating("shiny", validArchive());
        Faults store = new Faults() {
            final AtomicInteger puts = new AtomicInteger();

            @Override
            public Optional<eu.openanalytics.shinyproxy.publisher.storage.StoredObject> putIfAbsent(
                    String bucket, String key, byte[] content, String contentType) {
                if (puts.incrementAndGet() == 2) {
                    throw new ObjectStoreException("injected: outcome unknown on PUT " + key);
                }
                return super.putIfAbsent(bucket, key, content, contentType);
            }
        };
        BundleValidation v = validation(queue, store);
        assertEquals(BundleValidation.Result.STORAGE_FAILED, run(v, queue.claim("v").orElseThrow()));
        byte[] manifest = read(cb, ObjectKeys.BUNDLE_MANIFEST);
        due(cb[1]);
        assertEquals(BundleValidation.Result.VALIDATED, run(v, queue.claim("v").orElseThrow()));
        assertArrayEquals(manifest, read(cb, ObjectKeys.BUNDLE_MANIFEST));
    }

    @Test
    public void aDifferentManifestAlreadyStoredIsAStorageFailureNotAdopted() throws Exception {
        ValidationQueue queue = new ValidationQueue(jdbc, settings(3));
        UUID[] cb = validating("shiny", validArchive());
        objects.store.putIfAbsent(BUCKET, ObjectKeys.bundleObject(cb[0], cb[1], ObjectKeys.BUNDLE_MANIFEST),
            "{\"other\":true}".getBytes(StandardCharsets.UTF_8), "application/json").orElseThrow();
        assertEquals(BundleValidation.Result.STORAGE_FAILED, run(validation(queue, new Faults()), queue.claim("v").orElseThrow()));
        assertTrue(((String) row(cb[1]).get("validation_storage_error")).contains("a different manifest.json"));
        assertTrue(new BundleWriter(objects.store, BUCKET).readReceipt(cb[0], cb[1]).isEmpty());
    }

    @Test
    public void aDifferentReceiptAlreadyStoredIsAStorageFailureNotAdopted() throws Exception {
        ValidationQueue queue = new ValidationQueue(jdbc, settings(3));
        UUID[] cb = validating("shiny", validArchive());
        objects.store.putIfAbsent(BUCKET, ObjectKeys.bundleObject(cb[0], cb[1], ObjectKeys.BUNDLE_RECEIPT),
            ("{\"layoutVersion\":1,\"schemaVersion\":1,\"contentId\":\"" + cb[0] + "\",\"bundleId\":\"" + cb[1]
                + "\",\"archiveSha256\":\"" + "0".repeat(64) + "\",\"archiveBytes\":1,\"manifestSha256\":\""
                + "0".repeat(64) + "\",\"inventorySha256\":\"" + "0".repeat(64) + "\"}").getBytes(StandardCharsets.UTF_8),
            "application/json").orElseThrow();
        assertEquals(BundleValidation.Result.STORAGE_FAILED, run(validation(queue, new Faults()), queue.claim("v").orElseThrow()));
        assertTrue(((String) row(cb[1]).get("validation_storage_error")).contains("a different receipt.json"));
        assertEquals("VALIDATING", row(cb[1]).get("state"));
    }

    @Test
    public void twoConcurrentValidationsOfOneBundleWriteIdenticalBytes() throws Exception {
        // A stall past the lease makes two validators of one bundle run at once: both write
        // create-only, the second finds the first's objects identical, and only the current
        // generation's verdict lands.
        ValidationQueue queue = new ValidationQueue(jdbc, settings(3));
        UUID[] cb = validating("shiny", validArchive());
        ValidationQueue.Claimed stalled = queue.claim("stalled").orElseThrow();
        lapse(cb[1]);
        ValidationQueue.Claimed current = queue.claim("current").orElseThrow();
        BundleValidation v = validation(queue, new Faults());
        BundleValidation.Result[] results = new BundleValidation.Result[2];
        Thread a = new Thread(() -> results[0] = run(v, stalled));
        Thread b = new Thread(() -> results[1] = run(v, current));
        a.start();
        b.start();
        a.join(60_000);
        b.join(60_000);
        assertEquals(BundleValidation.Result.FENCED, results[0]);
        assertEquals(BundleValidation.Result.VALIDATED, results[1]);
        assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM skald.audit_event WHERE action = 'bundle.validated'",
            Long.class));
    }

    @Test
    public void aValidatorThatDiedMidValidationIsReplacedWithNoReupload() throws Exception {
        // The instance that claimed it is gone (its lease simply lapses); any other instance's
        // validator picks the row up.
        ValidationQueue queue = new ValidationQueue(jdbc, settings(3));
        UUID b = validating("shiny", validArchive())[1];
        queue.claim("validator:dead-instance/0").orElseThrow();
        lapse(b);
        BundleValidator survivor = new BundleValidator(queue, validation(queue, new Faults()), settings(3));
        assertEquals(Optional.of(BundleValidation.Result.VALIDATED), survivor.runOnce("validator:other/0"));
        assertEquals(1, ((Number) row(b).get("validation_claims")).intValue());
    }

    // ------------------------------------------------------------------ the stream

    @Test
    public void aZeroByteReadFailsClosed() {
        InputStream zero = new InputStream() {
            @Override
            public int read() {
                return 'x';
            }

            @Override
            public int read(byte[] b, int off, int len) {
                return 0;
            }
        };
        CountingDigestStream s = new CountingDigestStream(zero);
        IOException e = assertThrows(IOException.class, () -> s.read(new byte[8], 0, 8));
        assertTrue(e.getMessage().contains("0 bytes"), e.getMessage());
    }

    private static byte[] read(UUID[] cb, String name) throws IOException {
        try (InputStream in = objects.store.open(BUCKET, ObjectKeys.bundleObject(cb[0], cb[1], name))) {
            return in.readAllBytes();
        }
    }
}
