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
package eu.openanalytics.shinyproxy.publisher.build;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.openanalytics.shinyproxy.publisher.registry.VersionAllocator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

/**
 * The durable coordinator: claims QUEUED attempts under a PostgreSQL lease, fences every
 * worker update by lease generation, handles cancellation, and reaps what a lost worker left
 * behind (WORKPLAN-BUNDLES.md T6, "V2 and lifecycle" step 3; decisions 3 and 4 of
 * 2026-10-01). It holds no state in memory: a coordinator in another process, or this one
 * after a restart, sees exactly the same thing, which is what makes several instances safe.
 *
 * <p>Every worker-side transition is one UPDATE whose WHERE clause carries the expected
 * state, the lease's generation, an unexpired lease and an unpassed wall deadline (past it
 * the attempt is TIMED_OUT's to the reaper, never a late success). Zero rows matched means the attempt
 * is no longer the caller's, and {@link Fenced} is thrown rather than anything being merged.
 * The transitions are spec/lifecycle-v1.json's build machine; none activates anything.
 */
public final class BuildCoordinator {

    /**
     * What a cancel request did: a function of the attempt's state and whether it already
     * carries a request, read under the row lock (spec/admin-transport-v1.json
     * shapes.cancel_replies). A retry of a cancel reports where that cancel has got to; it
     * never makes a second request or a different transition.
     */
    public enum CancelOutcome {
        /** QUEUED: cancelled at once, nothing was running. Or CANCELLED by an earlier request. */
        CANCELLED,
        /** RUNNING: recorded; the attempt is CANCELLED once the worker confirms it stopped. */
        REQUESTED,
        /** PUBLISHING with no request: refused (spec: cancel_refused_states); nothing recorded. */
        REFUSED_PUBLISHING,
        /**
         * A request was recorded while RUNNING, and the attempt went on to PUBLISHING,
         * SUCCEEDED or FAILED: the driver finished before the stop.
         */
        TOO_LATE,
        /**
         * Terminal with no request of its own (a superseded attempt among them), or TIMED_OUT
         * / INTERRUPTED whatever was requested; nothing changed.
         */
        ALREADY_FINISHED,
        /** No such build for this content item. */
        NOT_FOUND
    }

    // Distinct from admission's: claims serialise among themselves so the running count is
    // exact, and do not wait on admissions.
    private static final long CLAIM_LOCK = 0x5ca1dad0002L;
    private static final List<String> TERMINAL =
            List.of("SUCCEEDED", "FAILED", "CANCELLED", "TIMED_OUT", "INTERRUPTED");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final CoordinatorSettings settings;
    private final ObjectMapper json = new ObjectMapper();

    public BuildCoordinator(JdbcTemplate jdbc, CoordinatorSettings settings) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
    }

    // ------------------------------------------------------------------ claim and renew

    /**
     * Claims the oldest QUEUED attempt for {@code owner}, if fewer than max_running attempts
     * are running. QUEUED -> RUNNING with a fresh lease, the generation bumped and the wall
     * deadline set. SKIP LOCKED: an attempt an admission is superseding right now is skipped
     * rather than waited for.
     */
    public Optional<BuildDriver.Claimed> claim(String owner) {
        return tx.execute(status -> {
            jdbc.queryForObject("SELECT pg_advisory_xact_lock(?)", Object.class, CLAIM_LOCK);
            int running = jdbc.queryForObject("SELECT count(*) FROM skald.build"
                    + " WHERE state IN ('RUNNING', 'PUBLISHING')", Integer.class);
            if (running >= settings.maxRunning()) {
                return Optional.<BuildDriver.Claimed>empty();
            }
            List<Map<String, Object>> rows = jdbc.queryForList("UPDATE skald.build SET"
                    + " state = 'RUNNING', lease_owner = ?, lease_generation = lease_generation + 1,"
                    + " lease_expires_at = now() + make_interval(secs => ?),"
                    + " deadline_at = now() + make_interval(secs => ?),"
                    + " started_at = now(), updated_at = now()"
                    + " WHERE id = (SELECT id FROM skald.build WHERE state = 'QUEUED'"
                    + "   ORDER BY created_at, id LIMIT 1 FOR UPDATE SKIP LOCKED)"
                    // Redundant, and kept as a statement of intent: the subquery's FOR UPDATE
                    // already holds the chosen row for the rest of this statement, so nothing
                    // can supersede it between the choice and this update (a mutant without it
                    // passes every test, which is why this says "redundant" and not "fix").
                    + " AND state = 'QUEUED'"
                    + " RETURNING id, content_id, bundle_id, recipe::text AS recipe, lease_generation",
                    owner, settings.lease().toSeconds(), settings.deadline().toSeconds());
            if (rows.isEmpty()) {
                return Optional.<BuildDriver.Claimed>empty();
            }
            Map<String, Object> row = rows.get(0);
            UUID id = (UUID) row.get("id");
            Lease lease = new Lease(id, ((Number) row.get("lease_generation")).longValue(), owner);
            audit(owner, "build.claimed", id, Map.of("generation", String.valueOf(lease.generation())));
            return Optional.of(new BuildDriver.Claimed(id, (UUID) row.get("content_id"),
                    (UUID) row.get("bundle_id"), recipe((String) row.get("recipe")), lease));
        });
    }

    /** Extends a live lease. False when the lease is no longer the caller's: stop. */
    public boolean renew(Lease lease) {
        return renewAndReadCancel(lease).isPresent();
    }

    /**
     * Extends a live lease and, in the same statement, reads whether a cancellation was
     * requested. Empty when the lease is no longer the caller's. One statement, so the
     * heartbeat brings the cancel flag to the worker and the driver's own thread never has
     * to touch the database (071ef50-F1: a driver blocked in a database read cannot be told
     * to stop).
     */
    public Optional<Boolean> renewAndReadCancel(Lease lease) {
        List<Boolean> rows = jdbc.queryForList("UPDATE skald.build SET lease_expires_at = now()"
                + " + make_interval(secs => ?),"
                + " updated_at = now() WHERE id = ? AND lease_generation = ? AND lease_owner = ?"
                + " AND state IN ('RUNNING', 'PUBLISHING') AND lease_expires_at > now()"
                // Past the wall deadline the lease is not renewed, so the heartbeat stops the
                // worker without waiting for a reaper (071ef50 review N2).
                + " AND deadline_at > now() RETURNING cancel_requested_at IS NOT NULL", Boolean.class,
                settings.lease().toSeconds(), lease.buildId(), lease.generation(), lease.owner());
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** Has a cancellation been requested for the attempt this lease holds? */
    public boolean cancelRequested(Lease lease) {
        List<Boolean> rows = jdbc.queryForList("SELECT cancel_requested_at IS NOT NULL FROM skald.build"
                + " WHERE id = ? AND lease_generation = ?", Boolean.class, lease.buildId(), lease.generation());
        return !rows.isEmpty() && rows.get(0);
    }

    // ------------------------------------------------------------------ fenced worker transitions

    /**
     * RUNNING -> PUBLISHING: the driver produced an image (a digest reference). The image is
     * recorded in the artifact ledger in the same transaction, so from this moment it can
     * never become an orphan nobody knows about: if the attempt is then interrupted, times
     * out or its content is deleted, the record survives and cleanup can find the image (the
     * plan: "Crash after push but before the transaction leaves an orphan image" -- an
     * orphan in the registry, never one missing from the ledger).
     *
     * <p>One record per ATTEMPT (decision 5: "Record tag and digest in the artifact ledger"):
     * ref is the attempt's own tag reference, {@code <repository>:build-<build id>}, unique
     * per build, and the digest is a column. Two attempts that produce the SAME digest -- a
     * retry with a warm cache, any reproducible build -- get two records, so the one that
     * succeeds is pinned to its version and the failed one stays its own unpinned record.
     * Keyed by digest, the second was silently dropped and a published version's image looked
     * like a failed build's (6ae3868-F1). Retention is therefore by reference: a digest may be
     * collected only when no pinned record carries it (T10's collector).
     *
     * <p>The provenance is written in the same fenced UPDATE (V3: a build with an image has
     * provenance), and its "limits", when present, become the row's effective_limits.
     */
    public void toPublishing(Lease lease, String image, Map<String, Object> provenance) {
        if (provenance == null) {
            throw new IllegalArgumentException("an output image is recorded with its provenance");
        }
        String record = provenance(provenance);
        int at = image.indexOf("@sha256:");
        if (at <= 0) {
            throw new IllegalArgumentException("an output image is a digest reference: " + image);
        }
        String tagRef = image.substring(0, at) + ":build-" + lease.buildId();
        String digest = image.substring(at + 1);
        tx.executeWithoutResult(status -> {
            fenced(lease, "RUNNING", "state = 'PUBLISHING', publishing_at = now(), output_image = ?,"
                    + " provenance = ?::jsonb, effective_limits = COALESCE(?::jsonb -> 'limits', effective_limits)",
                    List.of(image, record, record), "build.publishing");
            jdbc.update("INSERT INTO skald.artifact (kind, ref, digest, subject_content_id,"
                    + " subject_bundle_id, subject_build_id) SELECT 'image', ?, ?, content_id,"
                    + " bundle_id, id FROM skald.build WHERE id = ?", tagRef, digest, lease.buildId());
        });
    }

    /** RUNNING or PUBLISHING -> FAILED. */
    public void fail(Lease lease, String code, String detail) {
        fail(lease, code, detail, null);
    }

    /**
     * RUNNING or PUBLISHING -> FAILED, recording the provenance the attempt got as far as
     * (null: none, and whatever the row holds is kept -- a PUBLISHING attempt's is its
     * image's, and is never replaced).
     */
    public void fail(Lease lease, String code, String detail, Map<String, Object> provenance) {
        String record = provenance == null ? null : provenance(provenance);
        int changed = jdbc.update("UPDATE skald.build SET state = 'FAILED', finished_at = now(),"
                + " updated_at = now(), error_code = ?, error_detail = ?,"
                + " provenance = CASE WHEN output_image IS NULL THEN COALESCE(?::jsonb, provenance)"
                + " ELSE provenance END,"
                + " effective_limits = CASE WHEN output_image IS NULL"
                + " THEN COALESCE(?::jsonb -> 'limits', effective_limits) ELSE effective_limits END"
                + " WHERE id = ? AND lease_generation = ? AND lease_owner = ?"
                + " AND state IN ('RUNNING', 'PUBLISHING') AND lease_expires_at > now()"
                + " AND deadline_at > now()",
                code, detail, record, record, lease.buildId(), lease.generation(), lease.owner());
        requireOne(changed, lease, "FAILED");
        audit(lease.owner(), "build.failed", lease.buildId(), Map.of("code", code));
    }

    /** RUNNING -> CANCELLED, only after a requested cancellation and the worker's confirmed stop. */
    public void confirmCancelled(Lease lease) {
        int changed = jdbc.update("UPDATE skald.build SET state = 'CANCELLED', finished_at = now(),"
                + " updated_at = now() WHERE id = ? AND lease_generation = ? AND lease_owner = ?"
                + " AND state = 'RUNNING' AND cancel_requested_at IS NOT NULL AND lease_expires_at > now()"
                + " AND deadline_at > now()",
                lease.buildId(), lease.generation(), lease.owner());
        requireOne(changed, lease, "CANCELLED");
        audit(lease.owner(), "build.cancelled", lease.buildId(), Map.of("confirmed", "true"));
    }

    /**
     * The attempt's log is complete and persisted (step 5: "Publish a complete log
     * descriptor"). Fenced like every worker write. {@link #succeed} requires it: a success
     * whose log could still be partial is not one (decision 4: "final log persistence").
     */
    public void markLogComplete(Lease lease, long cursor) {
        int changed = jdbc.update("UPDATE skald.build SET log_complete = true, log_cursor = ?,"
                + " updated_at = now() WHERE id = ? AND lease_generation = ? AND lease_owner = ?"
                + " AND state IN ('RUNNING', 'PUBLISHING') AND lease_expires_at > now()"
                + " AND deadline_at > now()", cursor, lease.buildId(), lease.generation(), lease.owner());
        requireOne(changed, lease, "log completion");
    }

    /**
     * PUBLISHING -> SUCCEEDED, and the version, in ONE transaction (step 5; decision 4). The
     * content row is locked first -- the coordination lock admission, version allocation and
     * deletion share, always taken before a build row so lock order never inverts -- then the
     * build row is re-read FOR UPDATE under the full fence. Then a version is allocated through
     * the shared allocator with build_id set, the build is marked SUCCEEDED, and both commit
     * together. Nothing is activated: activation stays a separate, explicit operation.
     *
     * <p>Exactly once: a second call finds the build SUCCEEDED and is fenced. A content item
     * deleted meanwhile took the build row with it (cascade), so the fence refuses rather than
     * allocate a version for nothing.
     *
     * @param specJson server-generated runtime metadata for the version (never the uploaded
     *                 manifest)
     * @throws Fenced if the attempt is no longer this worker's, or its log is not complete
     */
    public VersionAllocator.Allocated succeed(Lease lease, String specJson) {
        return tx.execute(status -> {
            List<UUID> content = jdbc.queryForList("SELECT content_id FROM skald.build WHERE id = ?",
                    UUID.class, lease.buildId());
            if (content.isEmpty()) {
                throw new Fenced("build " + lease.buildId() + " no longer exists (its content was"
                        + " deleted); no version is allocated");
            }
            jdbc.queryForList("SELECT id FROM skald.content WHERE id = ? FOR UPDATE", content.get(0));
            List<Map<String, Object>> rows = jdbc.queryForList("SELECT output_image, log_complete,"
                    + " created_by FROM skald.build WHERE id = ? AND lease_generation = ?"
                    + " AND lease_owner = ? AND state = 'PUBLISHING' AND lease_expires_at > now()"
                    + " AND deadline_at > now() FOR UPDATE",
                    lease.buildId(), lease.generation(), lease.owner());
            if (rows.isEmpty()) {
                throw new Fenced("build " + lease.buildId() + " (generation " + lease.generation()
                        + ") is not this worker's PUBLISHING attempt; success refused");
            }
            Map<String, Object> row = rows.get(0);
            if (!Boolean.TRUE.equals(row.get("log_complete"))) {
                throw new Fenced("build " + lease.buildId() + " has no complete log; success refused");
            }
            VersionAllocator.Allocated version = new VersionAllocator(jdbc).allocate(content.get(0),
                    (String) row.get("output_image"), specJson, (String) row.get("created_by"),
                    lease.buildId()).orElseThrow(() -> new Fenced("content of build "
                    + lease.buildId() + " is gone"));
            jdbc.update("UPDATE skald.build SET state = 'SUCCEEDED', finished_at = now(),"
                    + " updated_at = now() WHERE id = ?", lease.buildId());
            // A published version's image is pinned until its content is deleted (Q3's
            // retention, "Status, logs, admin transport and cleanup").
            int pinned = jdbc.update("UPDATE skald.artifact SET subject_version_id = ?, pinned = true"
                    + " WHERE kind = 'image' AND subject_build_id = ?", version.id(), lease.buildId());
            if (pinned != 1) {
                // Fail closed: a version whose image the ledger does not pin is one a collector
                // could delete. Nothing commits (6ae3868-F1: this update matched 0 rows silently).
                throw new IllegalStateException("build " + lease.buildId() + " has " + pinned
                        + " image record(s) in the ledger, not 1; success refused");
            }
            audit(lease.owner(), "build.succeeded", lease.buildId(),
                    Map.of("version", String.valueOf(version.version())));
            return version;
        });
    }

    /** The provenance as the JSON the column holds: a record with a string "schema". */
    private String provenance(Map<String, Object> provenance) {
        if (!(provenance.get("schema") instanceof String)) {
            throw new IllegalArgumentException("provenance is a record with a string schema");
        }
        try {
            return json.writeValueAsString(provenance);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("provenance is not JSON: " + ex.getOriginalMessage());
        }
    }

    private void fenced(Lease lease, String from, String set, List<Object> setArgs, String action) {
        List<Object> args = new java.util.ArrayList<>(setArgs);
        args.addAll(List.of(lease.buildId(), lease.generation(), lease.owner(), from));
        int changed = jdbc.update("UPDATE skald.build SET " + set + ", updated_at = now()"
                + " WHERE id = ? AND lease_generation = ? AND lease_owner = ? AND state = ?"
                + " AND lease_expires_at > now() AND deadline_at > now()", args.toArray());
        requireOne(changed, lease, from + " -> next");
        audit(lease.owner(), action, lease.buildId(), Map.of("generation", String.valueOf(lease.generation())));
    }

    private static void requireOne(int changed, Lease lease, String what) {
        if (changed != 1) {
            throw new Fenced("build " + lease.buildId() + " (generation " + lease.generation()
                    + ") is no longer this worker's; " + what + " refused");
        }
    }

    // ------------------------------------------------------------------ cancel and reap

    /**
     * What a cancel decided, and the state it was decided on: the one read under the row
     * lock. A refusal names this state, not a later read's, which the driver may already have
     * moved on (9baa932-F1). Null state for NOT_FOUND.
     */
    public record CancelDecision(CancelOutcome outcome, String state) { }

    /** A user's cancel request, scoped to the content item the build belongs to. */
    public CancelOutcome cancel(UUID contentId, UUID buildId, String actor) {
        return decideCancel(contentId, buildId, actor).outcome();
    }

    /** As {@link #cancel}, with the state the outcome was decided on. */
    public CancelDecision decideCancel(UUID contentId, UUID buildId, String actor) {
        return tx.execute(status -> {
            List<Map<String, Object>> rows = jdbc.queryForList("SELECT state,"
                    + " cancel_requested_at IS NOT NULL AS requested FROM skald.build WHERE id = ?"
                    + " AND content_id = ? FOR UPDATE", buildId, contentId);
            if (rows.isEmpty()) {
                return new CancelDecision(CancelOutcome.NOT_FOUND, null);
            }
            String state = (String) rows.get(0).get("state");
            boolean requested = (Boolean) rows.get(0).get("requested");
            CancelOutcome outcome = switch (state) {
                case "QUEUED" -> {
                    jdbc.update("UPDATE skald.build SET state = 'CANCELLED', finished_at = now(),"
                            + " cancel_requested_at = now(), updated_at = now() WHERE id = ?", buildId);
                    yield CancelOutcome.CANCELLED;
                }
                case "RUNNING" -> {
                    jdbc.update("UPDATE skald.build SET cancel_requested_at = coalesce(cancel_requested_at,"
                            + " now()), updated_at = now() WHERE id = ?", buildId);
                    yield CancelOutcome.REQUESTED;
                }
                case "PUBLISHING" -> requested ? CancelOutcome.TOO_LATE : CancelOutcome.REFUSED_PUBLISHING;
                case "CANCELLED" -> requested ? CancelOutcome.CANCELLED : CancelOutcome.ALREADY_FINISHED;
                case "SUCCEEDED", "FAILED" -> requested ? CancelOutcome.TOO_LATE : CancelOutcome.ALREADY_FINISHED;
                default -> CancelOutcome.ALREADY_FINISHED;
            };
            audit(actor, "build.cancel." + outcome.name().toLowerCase(java.util.Locale.ROOT), buildId, Map.of());
            return new CancelDecision(outcome, state);
        });
    }

    /**
     * What a lost worker left behind. A wall deadline passed -> TIMED_OUT; otherwise a lease
     * expired -> INTERRUPTED. No retry is scheduled (decision 4): a retry is a new attempt a
     * user starts. A PUBLISHING attempt is reaped the same way here; reconciliation of what it
     * may already have published is the finalization part's (T6, later). Its version
     * transaction is fenced by this very state change, so it cannot succeed late.
     *
     * @return how many attempts were reaped
     */
    public int reap() {
        List<UUID> timedOut = jdbc.queryForList("UPDATE skald.build SET state = 'TIMED_OUT',"
                + " finished_at = now(), updated_at = now(), error_code = 'DEADLINE_EXCEEDED'"
                + " WHERE state IN ('RUNNING', 'PUBLISHING') AND deadline_at <= now() RETURNING id",
                UUID.class);
        List<UUID> interrupted = jdbc.queryForList("UPDATE skald.build SET state = 'INTERRUPTED',"
                + " finished_at = now(), updated_at = now(), error_code = 'LEASE_EXPIRED'"
                + " WHERE state IN ('RUNNING', 'PUBLISHING') AND lease_expires_at <= now() RETURNING id",
                UUID.class);
        for (UUID id : timedOut) {
            audit("coordinator", "build.timed_out", id, Map.of());
        }
        for (UUID id : interrupted) {
            audit("coordinator", "build.interrupted", id, Map.of());
        }
        return timedOut.size() + interrupted.size();
    }

    /**
     * The builds whose attempts the reconciliation sweep must leave alone: every build that
     * is not finished (QUEUED, RUNNING or PUBLISHING), whatever its lease says, and every
     * build that finished within {@code grace}. An expired lease the reaper has not settled
     * yet may still belong to a worker that is stopping itself, here or on another instance;
     * and a build that has just finished may still have a work thread in a daemon call (the
     * driver returns after its settle while that thread disposes later, 2fc53d9-F1). The
     * grace is not a bound on that thread: the launcher's disposal also removes what a late
     * call re-creates. It keeps the sweep from racing a work thread as a matter of routine.
     */
    public java.util.Set<UUID> unsettledBuildIds(java.time.Duration grace) {
        return new java.util.HashSet<>(jdbc.queryForList(
                "SELECT id FROM skald.build WHERE state IN ('QUEUED', 'RUNNING', 'PUBLISHING')"
                        + " OR finished_at > now() - ?::interval", UUID.class, grace.toSeconds() + " seconds"));
    }

    /** A build the platform lost, whose log its own writer may never have finished. */
    public record LostAttempt(UUID buildId, UUID contentId, long generation) { }

    /**
     * Builds that ended INTERRUPTED or TIMED_OUT within {@code window} but longer ago than
     * {@code grace}: the platform settled them, so their own writer may never have finished
     * their log (0adc78e N2, e4306c6 N1). Bounded by the window, so a sweep does not revisit
     * every lost build forever. Not before the grace (01683f9-F1): a settled build's writer
     * is often still alive -- on a deadline always, since the reaper settles TIMED_OUT while
     * the worker is still being stopped -- and a create-only INTERRUPTED written first would
     * win over the writer's own finish and hide the log's tail and its reason.
     */
    public List<LostAttempt> recentlyLost(java.time.Duration window, java.time.Duration grace) {
        return jdbc.query("SELECT id, content_id, lease_generation FROM skald.build"
                        + " WHERE state IN ('INTERRUPTED', 'TIMED_OUT') AND finished_at > now() - ?::interval"
                        + " AND finished_at <= now() - ?::interval",
                (rs, i) -> new LostAttempt(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getLong(3)),
                window.toSeconds() + " seconds", grace.toSeconds() + " seconds");
    }

    /**
     * Why this lease's renewal was refused, in words for the build's log: the wall deadline
     * passed (whether or not the reaper has yet settled it TIMED_OUT), or the platform no
     * longer holds the attempt for this worker. Fixed text and numbers only: printable ASCII.
     */
    public String whyRefused(Lease lease) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT state, deadline_at <= now() AS past,"
                + " ceil(extract(epoch FROM (deadline_at - started_at)) / 60)::bigint AS minutes,"
                + " lease_generation = ? AS ours, lease_expires_at <= now() AS expired"
                + " FROM skald.build WHERE id = ?", lease.generation(), lease.buildId());
        if (rows.isEmpty()) {
            return "the platform no longer has this build";
        }
        Map<String, Object> r = rows.get(0);
        if ("TIMED_OUT".equals(r.get("state")) || Boolean.TRUE.equals(r.get("past"))) {
            return "the build passed its deadline of " + r.get("minutes") + " minute(s)";
        }
        if (!Boolean.TRUE.equals(r.get("ours"))) {
            return "the attempt was handed to another worker";
        }
        if (Boolean.TRUE.equals(r.get("expired")) || "INTERRUPTED".equals(r.get("state"))) {
            return "the attempt's lease expired before it was renewed, and the platform reclaims it";
        }
        return "the platform no longer holds this attempt (state " + r.get("state") + ")";
    }

    /** True if {@code state} is one of the build machine's terminal states. */
    static boolean terminal(String state) {
        return TERMINAL.contains(state);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> recipe(String text) {
        try {
            return text == null ? Map.of() : json.readValue(text, Map.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("unreadable recipe", ex);
        }
    }

    private void audit(String actor, String action, UUID buildId, Map<String, String> detail) {
        String body;
        try {
            body = json.writeValueAsString(new TreeMap<>(detail));
        } catch (JsonProcessingException ex) {
            body = "{}";
        }
        jdbc.update("INSERT INTO skald.audit_event (actor, action, subject_type, subject_id,"
                + " detail_json) VALUES (?, ?, 'build', ?, ?::jsonb)", actor, action,
                buildId.toString(), body);
    }
}
