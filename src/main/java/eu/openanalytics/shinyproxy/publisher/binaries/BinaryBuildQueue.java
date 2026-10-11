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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.openanalytics.shinyproxy.publisher.build.CoordinatorSettings;
import eu.openanalytics.shinyproxy.publisher.build.Fenced;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The queue of shared Linux R binaries (WORKPLAN-BUNDLES.md T7b, step 2, point 3): one V4 row
 * per platform key, package and version, claimed under a PostgreSQL lease like app builds, so
 * several Skald instances share it and a restart loses nothing. It holds no state in memory.
 *
 * <p>Two leased phases. The trusted side claims a PENDING_SOURCE row to fetch and verify its
 * source ({@link #claimSource}), and records the digests and the dependency edges it read from
 * the verified tarball ({@link #recordSource}); a compile claims a QUEUED row whose every
 * dependency a Skald attempt UPLOADED ({@link #claimBuild}), and is handed exactly those
 * dependencies' recorded digests (89a33dc N1: nothing else enters its sandbox); it records
 * what it is about to upload before uploading it ({@link #intendUpload}), so a 409 meeting
 * its own earlier upload is told from a foreign binary (1fba61d-F1). A dependency
 * that ends any other way -- refused, failed, or a binary Skald did not produce -- refuses its
 * dependents, with the reason, so they are not left waiting forever.
 *
 * <p>Compiles yield to app builds (c18c5ab N1): one is claimed only when no app build is
 * QUEUED and fewer than the app limit are running, and at most {@code maxRunning} compiles run.
 * App claims do not count compiles, so a compile never delays an app build; the host's worst
 * case is the app limit plus this one (a compile claimed in the instant before an app build
 * was admitted). Verification is a few HTTP requests on the trusted side, not a worker, and is
 * bounded by its caller's threads, not here.
 *
 * <p>Every holder's write is one UPDATE fenced by row, generation, owner, state, an unexpired
 * lease and an unpassed deadline; zero rows means the row is no longer the caller's, and
 * {@link Fenced} is thrown.
 */
public final class BinaryBuildQueue {

    /**
     * A row the trusted side must fetch and verify the source of; {@code requestedBy} is the
     * app build whose lock resolves its dependencies (null: none, and then it cannot be built).
     */
    public record SourceClaim(UUID rowId, String platformKey, String pkg, String version, UUID requestedBy,
                              BinaryLease lease) { }

    /** A dependency as the trusted side read it from the verified DESCRIPTION. */
    public record Dependency(String pkg, String version) { }

    /** A dependency handed into a sandbox: its Skald-uploaded binary, by recorded digest. */
    public record BuiltDependency(String pkg, String version, String binarySha256) { }

    /** A row to compile: its verified source and its dependencies' Skald-built binaries. */
    public record BuildClaim(UUID rowId, String platformKey, String pkg, String version,
                             String sourceSha256, String sourceMd5, List<BuiltDependency> dependencies,
                             BinaryLease lease) { }

    /** What {@link #alreadyPresent} found forge's binary to be. */
    public enum Present {
        /** One of this row's own upload intents: an earlier attempt's upload whose write was lost. UPLOADED. */
        OURS,
        /** No upload intent of this row: PRESENT_FOREIGN, never handed in. */
        FOREIGN
    }

    /** What {@link #recordSource} did. */
    public enum Recorded {
        /** QUEUED, its edges recorded; missing dependencies were enqueued. */
        QUEUED,
        /** REFUSED: one of its dependencies already depends on it. */
        REFUSED_CYCLE
    }

    // Serialises compile claims among themselves, so the running count is exact. Distinct
    // from the build coordinator's and admission's locks: a compile claim never waits on them.
    private static final long CLAIM_LOCK = 0x5ca1dad0004L;
    // The key space of the per-platform lock under which dependency edges are recorded.
    private static final int EDGE_LOCK = 0x5ca1d4;
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern MD5 = Pattern.compile("[0-9a-f]{32}");
    private static final String FENCE = " WHERE id = ? AND lease_generation = ? AND lease_owner = ?"
            + " AND lease_expires_at > now() AND deadline_at > now()";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final BinaryQueueSettings settings;
    private final CoordinatorSettings apps;
    private final ObjectMapper json = new ObjectMapper();

    public BinaryBuildQueue(JdbcTemplate jdbc, BinaryQueueSettings settings, CoordinatorSettings apps) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.apps = apps;
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
    }

    // ------------------------------------------------------------------ enqueue

    /**
     * Asks for a binary of {@code pkg} {@code version} on {@code platformKey}; idempotent. The
     * row's id, new or existing, whatever its state: a row is never re-opened, so a version
     * refused or failed once stays so (its apps restore it from source).
     *
     * @param requestedBy the app build whose lock asked for it, or null
     */
    public UUID enqueue(String platformKey, String pkg, String version, UUID requestedBy) {
        List<UUID> made = jdbc.queryForList("INSERT INTO skald.binary_build (platform_key, package,"
                + " version, requested_by_build) VALUES (?, ?, ?, ?)"
                + " ON CONFLICT (platform_key, package, version) DO NOTHING RETURNING id",
                UUID.class, platformKey, pkg, version, requestedBy);
        if (!made.isEmpty()) {
            audit("queue", "binary.enqueued", made.get(0), Map.of("key", platformKey,
                    "package", pkg, "version", version));
            return made.get(0);
        }
        return jdbc.queryForObject("SELECT id FROM skald.binary_build WHERE platform_key = ?"
                + " AND package = ? AND version = ?", UUID.class, platformKey, pkg, version);
    }

    /**
     * Records an app build's rendered lock (package -> exact version), once; the versions its
     * rows' dependencies resolve to. Idempotent: a build's lock never changes, and the first
     * record stands (V4 keeps it append-only).
     */
    public void recordLock(UUID buildId, Map<String, String> versions) {
        String body;
        try {
            body = json.writeValueAsString(new TreeMap<>(versions));
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("a lock is a map of package names to versions", ex);
        }
        jdbc.update("INSERT INTO skald.binary_lock (build_id, versions) VALUES (?, ?::jsonb)"
                + " ON CONFLICT (build_id) DO NOTHING", buildId, body);
    }

    /** The lock {@code buildId} recorded, or empty if none was. */
    public Optional<Map<String, String>> lockOf(UUID buildId) {
        if (buildId == null) {
            return Optional.empty();
        }
        List<String> rows = jdbc.queryForList("SELECT versions::text FROM skald.binary_lock WHERE build_id = ?",
                String.class, buildId);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        try {
            Map<String, String> versions = json.readValue(rows.get(0),
                    new com.fasterxml.jackson.core.type.TypeReference<TreeMap<String, String>>() { });
            return Optional.of(java.util.Collections.unmodifiableMap(versions));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("unreadable lock of build " + buildId, ex);
        }
    }

    /** The source digests the row recorded at its first fetch, [sha256, md5], if it has. */
    public Optional<List<String>> recordedSource(UUID rowId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT source_sha256, source_md5 FROM"
                + " skald.binary_build WHERE id = ? AND source_sha256 IS NOT NULL", rowId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(List.of((String) rows.get(0).get("source_sha256"),
                (String) rows.get(0).get("source_md5")));
    }

    // ------------------------------------------------------------------ source phase

    /** Claims the oldest PENDING_SOURCE row: PENDING_SOURCE -> VERIFYING with a fresh lease. */
    public Optional<SourceClaim> claimSource(String owner) {
        List<Map<String, Object>> rows = jdbc.queryForList("UPDATE skald.binary_build SET"
                + " state = 'VERIFYING'," + leaseSet() + ", attempts = attempts + 1"
                + " WHERE id = (SELECT id FROM skald.binary_build WHERE state = 'PENDING_SOURCE'"
                + "   ORDER BY created_at, id LIMIT 1 FOR UPDATE SKIP LOCKED)"
                + " RETURNING id, platform_key, package, version, requested_by_build, lease_generation",
                owner, settings.lease().toSeconds(), settings.deadline().toSeconds());
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> row = rows.get(0);
        BinaryLease lease = lease(row, owner);
        audit(owner, "binary.verifying", lease.rowId(), Map.of("generation", String.valueOf(lease.generation())));
        return Optional.of(new SourceClaim(lease.rowId(), (String) row.get("platform_key"),
                (String) row.get("package"), (String) row.get("version"),
                (UUID) row.get("requested_by_build"), lease));
    }

    /**
     * VERIFYING -> QUEUED: the source is verified, with these digests, and these are its
     * dependencies (Depends, Imports and LinkingTo of the verified DESCRIPTION, base packages
     * excluded: the caller's job). Missing dependencies are enqueued on the same platform, and
     * the attempts are reset for the compile. A dependency that already depends on this row,
     * directly or not, makes a cycle no order can build: REFUSED instead, and no edge is kept.
     *
     * <p>Edges are recorded under a per-platform lock, so two rows recording at once cannot
     * each miss the other's half of a cycle.
     */
    public Recorded recordSource(BinaryLease lease, String sourceSha256, String sourceMd5,
                                 List<Dependency> dependencies) {
        if (sourceSha256 == null || !SHA256.matcher(sourceSha256).matches()
                || sourceMd5 == null || !MD5.matcher(sourceMd5).matches()) {
            throw new IllegalArgumentException("source digests are lower-case hex SHA-256 and MD5");
        }
        return tx.execute(status -> {
            List<Map<String, Object>> rows = jdbc.queryForList("SELECT platform_key, requested_by_build"
                    + " FROM skald.binary_build" + FENCE + " AND state = 'VERIFYING' FOR UPDATE",
                    lease.rowId(), lease.generation(), lease.owner());
            if (rows.isEmpty()) {
                throw fenced(lease, "recording its source");
            }
            String key = (String) rows.get(0).get("platform_key");
            UUID requestedBy = (UUID) rows.get(0).get("requested_by_build");
            jdbc.queryForObject("SELECT pg_advisory_xact_lock(?, hashtext(?))", Object.class,
                    EDGE_LOCK, key);
            List<UUID> deps = new ArrayList<>();
            for (Dependency d : dependencies) {
                UUID id = enqueue(key, d.pkg(), d.version(), requestedBy);
                if (!deps.contains(id)) {
                    deps.add(id);
                }
            }
            boolean cycle = !deps.isEmpty() && Boolean.TRUE.equals(jdbc.queryForObject(
                    "WITH RECURSIVE reach(id) AS (SELECT unnest(?::text[]::uuid[])"
                    + " UNION SELECT d.dependency_id FROM skald.binary_build_dep d"
                    + "   JOIN reach r ON d.binary_build_id = r.id)"
                    + " SELECT EXISTS (SELECT 1 FROM reach WHERE id = ?)", Boolean.class,
                    deps.stream().map(UUID::toString).toArray(String[]::new), lease.rowId()));
            String digests = "source_sha256 = ?, source_md5 = ?";
            if (cycle) {
                int changed = jdbc.update("UPDATE skald.binary_build SET " + digests + ", state = 'REFUSED',"
                        + " refusal_code = 'DEPENDENCY_CYCLE', refusal_detail = ?," + release() + FENCE,
                        sourceSha256, sourceMd5, "a dependency already depends on this package",
                        lease.rowId(), lease.generation(), lease.owner());
                requireOne(changed, lease, "refusal");
                audit(lease.owner(), "binary.refused", lease.rowId(), Map.of("code", "DEPENDENCY_CYCLE"));
                return Recorded.REFUSED_CYCLE;
            }
            for (UUID dep : deps) {
                jdbc.update("INSERT INTO skald.binary_build_dep (binary_build_id, dependency_id,"
                        + " platform_key) VALUES (?, ?, ?)", lease.rowId(), dep, key);
            }
            int changed = jdbc.update("UPDATE skald.binary_build SET " + digests + ", state = 'QUEUED',"
                    + " attempts = 0, deadline_at = NULL," + idle() + FENCE,
                    sourceSha256, sourceMd5, lease.rowId(), lease.generation(), lease.owner());
            requireOne(changed, lease, "recording its source");
            audit(lease.owner(), "binary.queued", lease.rowId(), Map.of("source_sha256", sourceSha256,
                    "dependencies", String.valueOf(deps.size())));
            return Recorded.QUEUED;
        });
    }

    // ------------------------------------------------------------------ compile phase

    /**
     * Claims the oldest QUEUED row whose every dependency a Skald attempt UPLOADED, if the app
     * builds leave room (the class comment): QUEUED -> RUNNING with a fresh lease. First, rows
     * a dependency can no longer satisfy are refused. The claim carries each dependency's
     * recorded binary digest, the only thing the sandbox may be handed for it.
     */
    public Optional<BuildClaim> claimBuild(String owner) {
        return tx.execute(status -> {
            jdbc.queryForObject("SELECT pg_advisory_xact_lock(?)", Object.class, CLAIM_LOCK);
            refuseBlocked();
            Map<String, Object> load = jdbc.queryForMap("SELECT"
                    + " (SELECT count(*) FROM skald.binary_build WHERE state = 'RUNNING') AS compiling,"
                    + " (SELECT count(*) FROM skald.build WHERE state = 'QUEUED') AS apps_queued,"
                    + " (SELECT count(*) FROM skald.build WHERE state IN ('RUNNING', 'PUBLISHING'))"
                    + "   AS apps_running");
            if (((Number) load.get("compiling")).intValue() >= settings.maxRunning()
                    || ((Number) load.get("apps_queued")).intValue() > 0
                    || ((Number) load.get("apps_running")).intValue() >= apps.maxRunning()) {
                return Optional.<BuildClaim>empty();
            }
            List<Map<String, Object>> rows = jdbc.queryForList("UPDATE skald.binary_build SET"
                    + " state = 'RUNNING'," + leaseSet() + ", attempts = attempts + 1"
                    + " WHERE id = (SELECT b.id FROM skald.binary_build b WHERE b.state = 'QUEUED'"
                    + "   AND NOT EXISTS (SELECT 1 FROM skald.binary_build_dep d"
                    + "     JOIN skald.binary_build x ON x.id = d.dependency_id"
                    + "     WHERE d.binary_build_id = b.id AND x.state <> 'UPLOADED')"
                    + "   ORDER BY b.created_at, b.id LIMIT 1 FOR UPDATE SKIP LOCKED)"
                    + " RETURNING id, platform_key, package, version, source_sha256, source_md5,"
                    + " lease_generation",
                    owner, settings.lease().toSeconds(), settings.deadline().toSeconds());
            if (rows.isEmpty()) {
                return Optional.<BuildClaim>empty();
            }
            Map<String, Object> row = rows.get(0);
            BinaryLease lease = lease(row, owner);
            // Every edge's row is UPLOADED: the claim's own WHERE chose this row for that, in this
            // transaction, and UPLOADED is final with a digest that never changes (V4's trigger).
            List<BuiltDependency> deps = jdbc.query("SELECT x.package, x.version, x.binary_sha256"
                    + " FROM skald.binary_build_dep d JOIN skald.binary_build x ON x.id = d.dependency_id"
                    + " WHERE d.binary_build_id = ? ORDER BY x.package",
                    (rs, i) -> new BuiltDependency(rs.getString(1), rs.getString(2), rs.getString(3)),
                    lease.rowId());
            audit(owner, "binary.claimed", lease.rowId(), Map.of("generation", String.valueOf(lease.generation())));
            return Optional.of(new BuildClaim(lease.rowId(), (String) row.get("platform_key"),
                    (String) row.get("package"), (String) row.get("version"),
                    (String) row.get("source_sha256"), (String) row.get("source_md5"), deps, lease));
        });
    }

    /** Extends a live lease of either phase. False when it is no longer the caller's: stop. */
    public boolean renew(BinaryLease lease) {
        return jdbc.update("UPDATE skald.binary_build SET lease_expires_at = now()"
                + " + make_interval(secs => ?), updated_at = now()" + FENCE
                + " AND state IN ('VERIFYING', 'RUNNING')", settings.lease().toSeconds(),
                lease.rowId(), lease.generation(), lease.owner()) == 1;
    }

    /**
     * Records, BEFORE the upload, the digest this RUNNING attempt is about to send and the
     * provenance the row takes if it becomes the binary (1fba61d-F1). The PUT and the row's
     * write are not atomic: a restart, a lost lease or a passed deadline between them leaves
     * Skald's binary in forge with a row that says nothing of it, and the next attempt's PUT
     * then meets a 409. With the intent on record that 409 is recognised as Skald's own
     * ({@link #alreadyPresent}), rather than called foreign and refusing every dependent.
     * Intents are append-only (V4) and survive the reaper; one per attempt.
     */
    public void intendUpload(BinaryLease lease, String binarySha256, Map<String, Object> provenance) {
        requireSha256(binarySha256);
        String record = provenance(provenance);
        tx.executeWithoutResult(status -> {
            heldFor(lease, "'RUNNING'", "an upload intent");
            jdbc.update("INSERT INTO skald.binary_build_upload (binary_build_id, binary_sha256, generation,"
                    + " provenance) VALUES (?, ?, ?, ?::jsonb) ON CONFLICT (binary_build_id, binary_sha256)"
                    + " DO NOTHING", lease.rowId(), binarySha256, lease.generation(), record);
            audit(lease.owner(), "binary.upload_intended", lease.rowId(), Map.of("binary_sha256", binarySha256));
        });
    }

    /**
     * RUNNING -> UPLOADED: this attempt's upload of {@code binarySha256} succeeded. The digest
     * must be one of the row's upload intents, and the row takes that intent's provenance.
     * From now on it is what dependents' sandboxes are handed.
     */
    public void uploaded(BinaryLease lease, String binarySha256) {
        requireSha256(binarySha256);
        tx.executeWithoutResult(status -> {
            heldFor(lease, "'RUNNING'", "UPLOADED");
            becomeOurs(lease, binarySha256, "UPLOADED");
            audit(lease.owner(), "binary.uploaded", lease.rowId(), Map.of("binary_sha256", binarySha256));
        });
    }

    /**
     * VERIFYING or RUNNING: forge already holds a binary for this version (a 409, or found
     * before compiling), with digest {@code binarySha256}. If it is one of this row's upload
     * intents, an earlier attempt uploaded it and lost its write: UPLOADED, with that
     * intent's provenance. Otherwise PRESENT_FOREIGN: its digest is recorded so it is visible,
     * it is never taken as Skald's (c365d95 N2), and its dependents are refused rather than
     * handed it.
     *
     * @param how how it was found ("409 on upload", "present before compiling"), for the audit
     */
    public Present alreadyPresent(BinaryLease lease, String binarySha256, String how) {
        requireSha256(binarySha256);
        return tx.execute(status -> {
            heldFor(lease, "'VERIFYING', 'RUNNING'", "recording a present binary");
            boolean ours = Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM"
                    + " skald.binary_build_upload WHERE binary_build_id = ? AND binary_sha256 = ?)",
                    Boolean.class, lease.rowId(), binarySha256));
            if (ours) {
                becomeOurs(lease, binarySha256, "UPLOADED (an earlier attempt's upload)");
                audit(lease.owner(), "binary.uploaded", lease.rowId(), Map.of("binary_sha256", binarySha256,
                        "how", String.valueOf(how), "earlier_attempt", "true"));
                return Present.OURS;
            }
            int changed = jdbc.update("UPDATE skald.binary_build SET state = 'PRESENT_FOREIGN',"
                    + " binary_sha256 = ?," + release() + FENCE + " AND state IN ('VERIFYING', 'RUNNING')",
                    binarySha256, lease.rowId(), lease.generation(), lease.owner());
            requireOne(changed, lease, "PRESENT_FOREIGN");
            audit(lease.owner(), "binary.foreign", lease.rowId(), Map.of("binary_sha256", binarySha256,
                    "how", String.valueOf(how)));
            return Present.FOREIGN;
        });
    }

    /** Locks the row if {@code lease} still holds it in one of {@code states}; Fenced otherwise. */
    private void heldFor(BinaryLease lease, String states, String what) {
        if (jdbc.queryForList("SELECT id FROM skald.binary_build" + FENCE + " AND state IN (" + states
                + ") FOR UPDATE", UUID.class, lease.rowId(), lease.generation(), lease.owner()).isEmpty()) {
            throw fenced(lease, what);
        }
    }

    /** The held row becomes UPLOADED with {@code digest}, which must be one of its intents. */
    private void becomeOurs(BinaryLease lease, String digest, String what) {
        List<String> provenance = jdbc.queryForList("SELECT provenance::text FROM skald.binary_build_upload"
                + " WHERE binary_build_id = ? AND binary_sha256 = ?", String.class, lease.rowId(), digest);
        if (provenance.isEmpty()) {
            throw new IllegalStateException("binary row " + lease.rowId() + " never intended to upload "
                    + digest + "; record the intent before the upload");
        }
        int changed = jdbc.update("UPDATE skald.binary_build SET state = 'UPLOADED', binary_sha256 = ?,"
                + " provenance = ?::jsonb," + release() + FENCE, digest, provenance.get(0),
                lease.rowId(), lease.generation(), lease.owner());
        requireOne(changed, lease, what);
    }

    /**
     * VERIFYING or RUNNING -> REFUSED: a check said no (not a public source, a changed tarball,
     * a base the registration does not name). Final for this version on this platform.
     */
    public void refuse(BinaryLease lease, String code, String detail) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("a refusal names its code");
        }
        int changed = jdbc.update("UPDATE skald.binary_build SET state = 'REFUSED', refusal_code = ?,"
                + " refusal_detail = ?," + release() + FENCE + " AND state IN ('VERIFYING', 'RUNNING')",
                code, detail, lease.rowId(), lease.generation(), lease.owner());
        requireOne(changed, lease, "REFUSED");
        audit(lease.owner(), "binary.refused", lease.rowId(), Map.of("code", code));
    }

    /**
     * The attempt failed (a fetch, a compile, an upload): back to the phase's waiting state
     * for another claim, or FAILED once the phase has used {@code maxAttempts} claims.
     */
    public void fail(BinaryLease lease, String code, String detail) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("a failure names its code");
        }
        int changed = jdbc.update("UPDATE skald.binary_build SET" + settle() + ", error_code = ?,"
                + " error_detail = ?" + FENCE + " AND state IN ('VERIFYING', 'RUNNING')",
                settings.maxAttempts(), settings.maxAttempts(), code, detail,
                lease.rowId(), lease.generation(), lease.owner());
        requireOne(changed, lease, "failure");
        audit(lease.owner(), "binary.failed", lease.rowId(), Map.of("code", code));
    }

    // ------------------------------------------------------------------ reap

    /**
     * What a lost holder left: a row whose lease expired or whose deadline passed goes back to
     * its phase's waiting state, or FAILED once its attempts are used up. Then the rows a
     * dependency can no longer satisfy are refused. Idempotent; every instance may run it.
     *
     * @return how many leased rows were settled
     */
    public int reap() {
        return tx.execute(status -> {
            List<UUID> settled = jdbc.queryForList("UPDATE skald.binary_build SET" + settle() + ","
                    + " error_code = CASE WHEN deadline_at <= now() THEN 'DEADLINE_EXCEEDED'"
                    + "   ELSE 'LEASE_EXPIRED' END, error_detail = NULL"
                    + " WHERE state IN ('VERIFYING', 'RUNNING')"
                    + " AND (lease_expires_at <= now() OR deadline_at <= now()) RETURNING id",
                    UUID.class, settings.maxAttempts(), settings.maxAttempts());
            for (UUID id : settled) {
                audit("queue", "binary.reaped", id, Map.of());
            }
            refuseBlocked();
            return settled.size();
        });
    }

    /**
     * Refuses every QUEUED row with a dependency that ended other than UPLOADED, and repeats,
     * since a refusal can block the next row up. Its detail names the dependencies.
     */
    int refuseBlocked() {
        int total = 0;
        while (true) {
            List<UUID> refused = jdbc.queryForList("UPDATE skald.binary_build b SET state = 'REFUSED',"
                    + " refusal_code = 'DEPENDENCY_NOT_SKALD_BUILT', refusal_detail = (SELECT"
                    + "   string_agg(x.package || ' ' || x.version || ' is ' || x.state, ', '"
                    + "   ORDER BY x.package) FROM skald.binary_build_dep d"
                    + "   JOIN skald.binary_build x ON x.id = d.dependency_id"
                    + "   WHERE d.binary_build_id = b.id"
                    + "   AND x.state IN ('PRESENT_FOREIGN', 'REFUSED', 'FAILED')),"
                    + " finished_at = now(), updated_at = now()"
                    + " WHERE b.state = 'QUEUED' AND EXISTS (SELECT 1 FROM skald.binary_build_dep d"
                    + "   JOIN skald.binary_build x ON x.id = d.dependency_id"
                    + "   WHERE d.binary_build_id = b.id"
                    + "   AND x.state IN ('PRESENT_FOREIGN', 'REFUSED', 'FAILED')) RETURNING b.id",
                    UUID.class);
            for (UUID id : refused) {
                audit("queue", "binary.refused", id, Map.of("code", "DEPENDENCY_NOT_SKALD_BUILT"));
            }
            if (refused.isEmpty()) {
                return total;
            }
            total += refused.size();
        }
    }

    // ------------------------------------------------------------------ helpers

    /** A fresh lease: owner, generation bumped, expiry and deadline (params: owner, lease s, deadline s). */
    private static String leaseSet() {
        return " lease_owner = ?, lease_generation = lease_generation + 1,"
                + " lease_expires_at = now() + make_interval(secs => ?),"
                + " deadline_at = now() + make_interval(secs => ?), started_at = now(), updated_at = now()";
    }

    /** No lease held. */
    private static String idle() {
        return " lease_owner = NULL, lease_expires_at = NULL, updated_at = now()";
    }

    /** A terminal state's lease release and finish time. */
    private static String release() {
        return idle() + ", finished_at = now()";
    }

    /**
     * Back to the phase's waiting state, or FAILED when the phase's attempts are used up
     * (params: maxAttempts twice).
     */
    private static String settle() {
        return " state = CASE WHEN attempts >= ? THEN 'FAILED'"
                + "   WHEN state = 'VERIFYING' THEN 'PENDING_SOURCE' ELSE 'QUEUED' END,"
                + " finished_at = CASE WHEN attempts >= ? THEN now() END, deadline_at = NULL," + idle();
    }

    private static BinaryLease lease(Map<String, Object> row, String owner) {
        return new BinaryLease((UUID) row.get("id"), ((Number) row.get("lease_generation")).longValue(), owner);
    }

    private static void requireSha256(String digest) {
        if (digest == null || !SHA256.matcher(digest).matches()) {
            throw new IllegalArgumentException("a binary digest is lower-case hex SHA-256");
        }
    }

    private static void requireOne(int changed, BinaryLease lease, String what) {
        if (changed != 1) {
            throw fenced(lease, what);
        }
    }

    private static Fenced fenced(BinaryLease lease, String what) {
        return new Fenced("binary row " + lease.rowId() + " (generation " + lease.generation()
                + ") is no longer this holder's; " + what + " refused");
    }

    /** The provenance as the JSON the column holds: a record with a string "schema". */
    private String provenance(Map<String, Object> provenance) {
        if (provenance == null || !(provenance.get("schema") instanceof String)) {
            throw new IllegalArgumentException("provenance is a record with a string schema");
        }
        try {
            return json.writeValueAsString(provenance);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("provenance is not JSON: " + ex.getOriginalMessage());
        }
    }

    private void audit(String actor, String action, UUID rowId, Map<String, String> detail) {
        String body;
        try {
            body = json.writeValueAsString(new TreeMap<>(detail));
        } catch (JsonProcessingException ex) {
            body = "{}";
        }
        jdbc.update("INSERT INTO skald.audit_event (actor, action, subject_type, subject_id,"
                + " detail_json) VALUES (?, ?, 'binary_build', ?, ?::jsonb)", actor, action,
                rowId.toString(), body);
    }
}
