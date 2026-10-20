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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.openanalytics.shinyproxy.publisher.bundle.ExtractionLimits;
import eu.openanalytics.shinyproxy.publisher.storage.BundleWriter;
import eu.openanalytics.shinyproxy.publisher.storage.ObjectKeys;
import eu.openanalytics.shinyproxy.publisher.storage.ObjectStore;
import eu.openanalytics.shinyproxy.publisher.storage.ObjectStoreException;
import eu.openanalytics.shinyproxy.publisher.storage.StoredObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

/**
 * bundle.create, bundle.upload and bundle.status (WORKPLAN-BUNDLES.md T8 plan;
 * spec/admin-transport-v1.json).
 *
 * <p><b>The upload's commit point is the row</b> (c50e4fa-F1): the archive is streamed to its
 * fixed, create-only key, and the bundle moves UPLOADING -> VALIDATING carrying the archive's
 * key, digest and size. Validation -- and receipt.json -- come after, on whatever instance
 * claims the row. Nothing here holds a transaction or a lock while bytes move.
 *
 * <p><b>The request body is counted and digested here, around whatever the store does with
 * it</b>, because a create-only streamed put that finds the archive already there returns
 * nothing about the bytes it was given, and may have read some or all of them. Draining the
 * rest through the same counter gives this request's digest in every case, which is what the
 * replay stages compare (spec shapes.upload_replay):
 * <ul>
 *   <li>no archive yet: this request's bytes are the archive;</li>
 *   <li>an archive already there and the row still UPLOADING (a reply lost after the put, or
 *       a concurrent PUT that got there first): the stored archive is read back once and
 *       hashed; the same bytes go on, different ones are BUNDLE_ARCHIVE_CONFLICT;</li>
 *   <li>the row has left UPLOADING: BUNDLE_NOT_UPLOADING, as T1 froze it.</li>
 * </ul>
 *
 * <p><b>Whose failure is whose</b> (the Q4 risk row). Fewer bytes than Content-Length from the
 * client, or a client that went away, is UPLOAD_INCOMPLETE; every failure of the store --
 * "outcome unknown", a 400 IncompleteBody, throttling past the SDK's retries -- is
 * STORAGE_UNAVAILABLE. Neither is a verdict on the bundle, and both leave it UPLOADING for the
 * same PUT again.
 */
public class BundleService {

    private static final Logger log = LoggerFactory.getLogger(BundleService.class);

    /** The incomplete-upload rule of the retention policy: one hour. */
    static final Duration UPLOAD_DEADLINE = Duration.ofHours(1);

    private final JdbcTemplate jdbc;
    private final ObjectStore store;
    private final BundleWriter writer;
    private final String bucket;
    private final ExtractionLimits limits;
    private final Runnable wake;
    private final ObjectMapper json = new ObjectMapper();

    public BundleService(JdbcTemplate jdbc, ObjectStore store, String bucket, ExtractionLimits limits,
                         Runnable wake) {
        this.jdbc = jdbc;
        this.store = store;
        this.writer = new BundleWriter(store, bucket);
        this.bucket = bucket;
        this.limits = limits;
        this.wake = wake;
    }

    public long maxBytes() {
        return limits.maxCompressedBytes();
    }

    /** A new bundle, UPLOADING, for an existing content item. */
    public Map<String, Object> create(UUID contentId, String actor) {
        List<UUID> made = jdbc.queryForList("INSERT INTO skald.bundle (content_id, created_by, upload_deadline)"
                        + " SELECT id, ?, now() + make_interval(secs => ?) FROM skald.content WHERE id = ?"
                        + " RETURNING id", UUID.class,
                actor, UPLOAD_DEADLINE.toSeconds(), contentId);
        if (made.isEmpty()) {
            throw contentNotFound(contentId);
        }
        audit(actor, "bundle.create", made.get(0), Map.of("content", contentId.toString()));
        return status(contentId, made.get(0));
    }

    /** The bundle as bundle.status shows it. */
    public Map<String, Object> status(UUID contentId, UUID bundleId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM skald.bundle WHERE id = ? AND content_id = ?",
                bundleId, contentId);
        if (rows.isEmpty()) {
            throw notFound(contentId, bundleId);
        }
        Map<String, Object> r = rows.get(0);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", bundleId.toString());
        out.put("contentId", contentId.toString());
        out.put("state", r.get("state"));
        out.put("createdBy", r.get("created_by"));
        out.put("createdAt", time(r.get("created_at")));
        out.put("updatedAt", time(r.get("updated_at")));
        out.put("finishedAt", time(r.get("finished_at")));
        out.put("uploadDeadline", time(r.get("upload_deadline")));
        out.put("archive", r.get("object_sha256") == null ? null
                : Map.of("sha256", r.get("object_sha256"), "bytes", r.get("object_bytes")));
        out.put("manifestSha256", r.get("manifest_sha256"));
        out.put("inventory", r.get("inventory_files") == null ? null
                : Map.of("files", r.get("inventory_files"), "bytes", r.get("inventory_bytes")));
        Map<String, Object> rejection = null;
        if (r.get("rejection_rule") != null) {
            rejection = new LinkedHashMap<>();
            rejection.put("rule", r.get("rejection_rule"));
            rejection.put("detail", r.get("rejection_detail"));
        }
        out.put("rejection", rejection);
        return out;
    }

    /**
     * The bytes. {@code contentLength} has been checked by the caller (present, within
     * maxBytes); it is still a claim, counted here against what arrives.
     */
    public Map<String, Object> upload(UUID contentId, UUID bundleId, String actor, long contentLength,
                                      InputStream body) {
        requireUploading(contentId, bundleId);
        String key = ObjectKeys.bundleObject(contentId, bundleId, ObjectKeys.BUNDLE_ARCHIVE);
        ClientReads client = new ClientReads(body);
        CountingDigestStream counted = new CountingDigestStream(client);
        Optional<StoredObject> written;
        try {
            written = writer.writeArchive(contentId, bundleId, counted, contentLength);
        } catch (ObjectStoreException e) {
            if (client.failed() || counted.ended() && counted.count() < contentLength) {
                throw incomplete(counted.count(), contentLength);
            }
            log.warn("upload of bundle {}: the store failed: {}", bundleId, e.getMessage());
            throw storage("the object store did not take the upload: " + e.getMessage());
        }
        // Whatever the store read, the rest of this request goes through the same counter, so
        // its digest is known in every case.
        try {
            counted.drain(Math.max(0, contentLength - counted.count()) + 1);
        } catch (IOException e) {
            throw incomplete(counted.count(), contentLength);
        }
        if (counted.count() != contentLength) {
            throw incomplete(counted.count(), contentLength);
        }
        String sha256 = counted.sha256();
        if (written.isPresent()) {
            Optional<String> recorded = written.get().sha256();
            if (recorded.isPresent() && !recorded.get().equals(sha256)) {
                // The store's own count disagreeing with ours would be a bug in one of them;
                // refusing keeps an unverified archive from becoming a bundle.
                throw new IllegalStateException("the store recorded " + recorded.get() + " for bundle "
                        + bundleId + " and this request's bytes are " + sha256);
            }
        } else {
            requireSameArchive(key, bundleId, sha256, contentLength);
        }
        int moved = jdbc.update("UPDATE skald.bundle SET state = 'VALIDATING', object_key = ?, object_sha256 = ?,"
                        + " object_bytes = ?, validating_at = now(), updated_at = now()"
                        + " WHERE id = ? AND content_id = ? AND state = 'UPLOADING'",
                key, sha256, contentLength, bundleId, contentId);
        if (moved != 1) {
            // A concurrent PUT of the same bytes moved it first, or the reaper rejected it.
            throw notUploading(contentId, bundleId);
        }
        audit(actor, "bundle.upload", bundleId, Map.of("bytes", String.valueOf(contentLength), "sha256", sha256));
        wake.run();
        return status(contentId, bundleId);
    }

    /** Stage (ii): the archive is already stored; it must be these bytes. */
    private void requireSameArchive(String key, UUID bundleId, String sha256, long bytes) {
        CountingDigestStream stored;
        try (InputStream in = store.open(bucket, key)) {
            stored = new CountingDigestStream(in);
            stored.drain(maxBytes() + 1);
        } catch (IOException | ObjectStoreException e) {
            throw storage("the archive already stored for this bundle could not be read back to compare: "
                    + e.getMessage());
        }
        if (stored.count() != bytes || !stored.sha256().equals(sha256)) {
            throw new TransportException(TransportError.BUNDLE_ARCHIVE_CONFLICT,
                    "this bundle already holds a different archive (" + stored.count() + " bytes); a bundle id"
                            + " names particular bytes, so upload these to a new bundle");
        }
    }

    /** Refuses unless the row is UPLOADING and inside its deadline; past it, rejects it first. */
    private void requireUploading(UUID contentId, UUID bundleId) {
        jdbc.update("UPDATE skald.bundle SET state = 'REJECTED', rejection_rule = 'UPLOAD_ABANDONED',"
                + " rejection_detail = 'no complete upload arrived before the upload deadline; upload the bundle"
                + " again under a new bundle id', finished_at = now(), updated_at = now()"
                + " WHERE id = ? AND content_id = ? AND state = 'UPLOADING' AND upload_deadline < now()",
                bundleId, contentId);
        List<String> state = jdbc.queryForList("SELECT state FROM skald.bundle WHERE id = ? AND content_id = ?",
                String.class, bundleId, contentId);
        if (state.isEmpty()) {
            throw notFound(contentId, bundleId);
        }
        if (!state.get(0).equals("UPLOADING")) {
            throw notUploading(contentId, bundleId);
        }
    }

    private TransportException notUploading(UUID contentId, UUID bundleId) {
        String state = jdbc.queryForList("SELECT state FROM skald.bundle WHERE id = ? AND content_id = ?",
                String.class, bundleId, contentId).stream().findFirst().orElse("UNKNOWN");
        return new TransportException(TransportError.BUNDLE_NOT_UPLOADING,
                "bundle " + bundleId + " is " + state + ", not UPLOADING: its bytes are fixed once received. Read"
                        + " its status (GET) to recover from a lost reply; new bytes need a new bundle")
                .with("state", state);
    }

    private TransportException notFound(UUID contentId, UUID bundleId) {
        Integer content = jdbc.queryForObject("SELECT count(*) FROM skald.content WHERE id = ?", Integer.class,
                contentId);
        if (content == null || content == 0) {
            return contentNotFound(contentId);
        }
        return new TransportException(TransportError.BUNDLE_NOT_FOUND,
                "no bundle " + bundleId + " in content " + contentId);
    }

    private static TransportException contentNotFound(UUID contentId) {
        return new TransportException(TransportError.CONTENT_NOT_FOUND, "no content " + contentId);
    }

    private static TransportException incomplete(long got, long declared) {
        return new TransportException(TransportError.UPLOAD_INCOMPLETE,
                "the request carried " + got + " of the " + declared + " bytes its Content-Length declared; the"
                        + " bundle is still UPLOADING, so send the same PUT again");
    }

    private static TransportException storage(String message) {
        return new TransportException(TransportError.STORAGE_UNAVAILABLE, message
                + ". Nothing is decided about the bundle: it is still UPLOADING, so send the same PUT again later");
    }

    private static String time(Object value) {
        return value == null ? null : ((Timestamp) value).toInstant().toString();
    }

    private void audit(String actor, String action, UUID bundleId, Map<String, String> detail) {
        String body;
        try {
            body = json.writeValueAsString(new TreeMap<>(detail));
        } catch (JsonProcessingException ex) {
            body = "{}";
        }
        jdbc.update("INSERT INTO skald.audit_event (actor, action, subject_type, subject_id,"
                + " detail_json) VALUES (?, ?, 'bundle', ?, ?::jsonb)", actor, action,
                bundleId.toString(), body);
    }

    /** Remembers whether reading the client's body failed: a disconnect, not the store. */
    private static final class ClientReads extends java.io.FilterInputStream {
        private volatile boolean failed;

        ClientReads(InputStream in) {
            super(in);
        }

        boolean failed() {
            return failed;
        }

        @Override
        public int read() throws IOException {
            try {
                return in.read();
            } catch (IOException e) {
                failed = true;
                throw e;
            }
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            try {
                return in.read(b, off, len);
            } catch (IOException e) {
                failed = true;
                throw e;
            }
        }
    }
}
