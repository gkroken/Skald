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
import eu.openanalytics.shinyproxy.publisher.build.AdmissionRefusal;
import eu.openanalytics.shinyproxy.publisher.build.BuildAdmission;
import eu.openanalytics.shinyproxy.publisher.build.BuildCoordinator;
import eu.openanalytics.shinyproxy.publisher.storage.BuildLogWriter;
import eu.openanalytics.shinyproxy.publisher.storage.LogFinal;
import eu.openanalytics.shinyproxy.publisher.storage.ObjectStoreException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;

/**
 * build.create, build.status, build.list and build.cancel (spec/admin-transport-v1.json,
 * WORKPLAN-BUNDLES.md T8 (c)). Admission and cancellation are the build package's; this turns
 * their outcomes into the transport's shapes and refusals, and adds what only a reader needs:
 * the log's state, read from the log objects themselves.
 *
 * <p><b>Ids are looked up as a pair</b>, (content, build), so another content item's build is
 * BUILD_NOT_FOUND exactly like a missing one.
 */
public class BuildService {

    static final int DEFAULT_LIMIT = 50;
    static final int MAX_LIMIT = 200;
    /** Build logs are kept 90 days whatever the outcome (WORKPLAN-BUNDLES.md retention table). */
    static final Duration LOG_RETENTION = Duration.ofDays(90);
    static final List<String> STATES = List.of("QUEUED", "RUNNING", "PUBLISHING",
            "SUCCEEDED", "FAILED", "CANCELLED", "TIMED_OUT", "INTERRUPTED");
    private static final Set<String> TERMINAL = Set.of("SUCCEEDED", "FAILED", "CANCELLED", "TIMED_OUT", "INTERRUPTED");
    /** final.json is create-only and immutable, so a record once read is the record. Bounded. */
    private static final int FINALS_CACHED = 10_000;
    private static final String COLUMNS = "id, content_id, bundle_id, retry_of, superseded_by, state, created_by,"
            + " created_at, started_at, publishing_at, finished_at, deadline_at, error_code, error_detail,"
            + " output_image, recipe::text AS recipe, effective_limits::text AS effective_limits, cancel_requested_at";

    private static final Logger log = LoggerFactory.getLogger(BuildService.class);

    private final JdbcTemplate jdbc;
    private final BuildAdmission admission;
    private final Canceller canceller;
    private final BuildLogWriter logs;
    private final Map<UUID, LogFinal> finals = Collections.synchronizedMap(
            new LinkedHashMap<>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<UUID, LogFinal> eldest) {
                    return size() > FINALS_CACHED;
                }
            });

    public BuildService(JdbcTemplate jdbc, BuildAdmission admission, BuildCoordinator coordinator,
                        BuildLogWriter logs) {
        this(jdbc, admission, coordinator::decideCancel, logs);
    }

    /** The coordinator's cancel decision; a seam so a test can move the row on after it commits. */
    interface Canceller {
        BuildCoordinator.CancelDecision decide(UUID contentId, UUID buildId, String actor);
    }

    BuildService(JdbcTemplate jdbc, BuildAdmission admission, Canceller canceller, BuildLogWriter logs) {
        this.jdbc = jdbc;
        this.admission = admission;
        this.canceller = canceller;
        this.logs = logs;
    }

    /** What a create resolved to: the attempt's status and whether this request made it. */
    public record Created(Map<String, Object> status, boolean created, UUID superseded) { }

    /**
     * Admits a build. {@code bundleId} may be null only with {@code retryOf}, whose bundle the
     * retry reuses; the admission resolves it before the request's fingerprint is taken, so a
     * replay matches whether or not it names the bundle.
     */
    public Created create(UUID contentId, String actor, UUID bundleId, String idempotencyKey, UUID retryOf,
                          UnaryOperator<String> url) {
        if (bundleId == null && retryOf == null) {
            throw new TransportException(TransportError.BAD_REQUEST,
                    "a build names its bundleId, or the retryOf attempt whose bundle it reuses");
        }
        BuildAdmission.Admitted admitted;
        try {
            admitted = admission.request(contentId, actor, idempotencyKey,
                    new BuildAdmission.Inputs(bundleId, Map.of(), retryOf));
        } catch (AdmissionRefusal r) {
            TransportException e = new TransportException(TransportError.valueOf(r.reason().name()), r.getMessage());
            r.detail().forEach(e::with);
            throw e;
        }
        // From the row, not from the admission's reply: a replay names what the first request
        // superseded, as the first reply did.
        UUID superseded = jdbc.queryForList("SELECT id FROM skald.build WHERE superseded_by = ? AND content_id = ?",
                UUID.class, admitted.buildId(), contentId).stream().findFirst().orElse(null);
        return new Created(status(contentId, admitted.buildId(), url), admitted.created(), superseded);
    }

    /** The attempt as build.status shows it. */
    public Map<String, Object> status(UUID contentId, UUID buildId, UnaryOperator<String> url) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT " + COLUMNS
                + " FROM skald.build WHERE id = ? AND content_id = ?", buildId, contentId);
        if (rows.isEmpty()) {
            throw notFound(contentId, buildId);
        }
        return item(rows.get(0), url, new StoreReads());
    }

    /** A page of build.list: newest first, keyset on (created_at, id). */
    public Map<String, Object> list(UUID contentId, List<String> states, UUID before, int limit,
                                    UnaryOperator<String> url) {
        requireContent(contentId);
        for (String s : states) {
            if (!STATES.contains(s)) {
                throw new TransportException(TransportError.BAD_REQUEST, "'" + printable(s)
                        + "' is not a build state; the states are " + STATES);
            }
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new TransportException(TransportError.BAD_REQUEST, "limit is 1 to " + MAX_LIMIT);
        }
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM skald.build WHERE content_id = ?");
        List<Object> args = new ArrayList<>(List.of(contentId));
        if (!states.isEmpty()) {
            sql.append(" AND state = ANY (?)");
            args.add(states.toArray(new String[0]));
        }
        if (before != null) {
            if (jdbc.queryForObject("SELECT count(*) FROM skald.build WHERE id = ? AND content_id = ?",
                    Integer.class, before, contentId) == 0) {
                throw new TransportException(TransportError.BAD_REQUEST,
                        "before names no build of this content item; it is the next value of a previous page");
            }
            // The position is read in the same statement, so the comparison is at the row's own
            // precision rather than a timestamp's round trip through Java.
            sql.append(" AND (created_at, id) < (SELECT created_at, id FROM skald.build WHERE id = ? AND content_id = ?)");
            args.add(before);
            args.add(contentId);
        }
        sql.append(" ORDER BY created_at DESC, id DESC LIMIT ?");
        args.add(limit + 1);
        List<Map<String, Object>> rows = jdbc.queryForList(sql.toString(), args.toArray());
        List<Map<String, Object>> items = new ArrayList<>();
        StoreReads reads = new StoreReads();
        for (Map<String, Object> row : rows.subList(0, Math.min(limit, rows.size()))) {
            items.add(item(row, url, reads));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("next", rows.size() > limit ? items.get(limit - 1).get("id") : null);
        return out;
    }

    /** What a cancel did: the outcome word and the attempt after it. A refusal is thrown. */
    public Map<String, Object> cancel(UUID contentId, UUID buildId, String actor, UnaryOperator<String> url) {
        BuildCoordinator.CancelDecision decision = canceller.decide(contentId, buildId, actor);
        BuildCoordinator.CancelOutcome outcome = decision.outcome();
        if (outcome == BuildCoordinator.CancelOutcome.NOT_FOUND) {
            throw notFound(contentId, buildId);
        }
        // A refusal names the state it was decided on, read under the row lock; a read now
        // may already see the attempt moved on (9baa932-F1). A 202 reports the attempt as it
        // is after the cancel.
        String state = decision.state();
        switch (outcome) {
            case CANCELLED, REQUESTED -> {
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("outcome", outcome == BuildCoordinator.CancelOutcome.CANCELLED ? "cancelled" : "requested");
                out.put("build", status(contentId, buildId, url));
                return out;
            }
            case REFUSED_PUBLISHING -> throw new TransportException(TransportError.CANCEL_REFUSED_PUBLISHING,
                    "build " + buildId + " is PUBLISHING: its image may already exist, so it is not stopped now;"
                            + " it ends SUCCEEDED or FAILED on its own").with("state", state);
            case TOO_LATE -> throw new TransportException(TransportError.CANCEL_TOO_LATE,
                    "cancel requested; the build had already finished (it is " + state + ")").with("state", state);
            default -> throw new TransportException(TransportError.ALREADY_FINISHED,
                    "build " + buildId + " is " + state + "; nothing was changed").with("state", state);
        }
    }

    // ------------------------------------------------------------------ the shape

    /** Storage reads within one request: after the first failure, no more are made. */
    private static final class StoreReads {
        boolean down;
    }

    private Map<String, Object> item(Map<String, Object> r, UnaryOperator<String> url, StoreReads reads) {
        UUID id = (UUID) r.get("id");
        UUID contentId = (UUID) r.get("content_id");
        String state = (String) r.get("state");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id.toString());
        out.put("contentId", contentId.toString());
        out.put("bundleId", String.valueOf(r.get("bundle_id")));
        out.put("retryOf", text(r.get("retry_of")));
        out.put("supersededBy", text(r.get("superseded_by")));
        out.put("state", state);
        out.put("createdBy", r.get("created_by"));
        out.put("createdAt", time(r.get("created_at")));
        out.put("startedAt", time(r.get("started_at")));
        out.put("publishingAt", time(r.get("publishing_at")));
        out.put("finishedAt", time(r.get("finished_at")));
        out.put("deadlineAt", time(r.get("deadline_at")));
        Map<String, Object> error = null;
        if (r.get("error_code") != null) {
            error = new LinkedHashMap<>();
            error.put("code", r.get("error_code"));
            error.put("detail", r.get("error_detail"));
        }
        out.put("error", error);
        // An image is the build's output only once it is SUCCEEDED; before that (PUBLISHING) or
        // after a failed publication it is not a result anyone should run.
        out.put("image", "SUCCEEDED".equals(state) ? r.get("output_image") : null);
        out.put("recipe", json((String) r.get("recipe")));
        out.put("effectiveLimits", json((String) r.get("effective_limits")));
        out.put("cancel", cancel(state, r.get("cancel_requested_at")));
        out.put("log", log(contentId, id, state, r.get("started_at"), r.get("finished_at"), reads));
        String self = "/admin/content/" + contentId + "/builds/" + id;
        Map<String, Object> links = new LinkedHashMap<>();
        links.put("self", url.apply(self));
        links.put("logs", url.apply(self + "/logs"));
        links.put("stream", url.apply(self + "/logs/stream"));
        links.put("cancel", url.apply(self + "/cancel"));
        out.put("links", links);
        return out;
    }

    /**
     * cancel.outcome: what became of a request, by the state the attempt reached. Null without
     * one -- a superseded attempt is CANCELLED with no request of its own.
     */
    static Map<String, Object> cancel(String state, Object requestedAt) {
        if (requestedAt == null) {
            return null;
        }
        String outcome = switch (state) {
            case "CANCELLED" -> "cancelled";
            // The driver finished before the stop and the attempt went on: too late.
            case "PUBLISHING", "SUCCEEDED", "FAILED" -> "too_late";
            // A timeout or a lost worker is not a finished build.
            case "TIMED_OUT", "INTERRUPTED" -> "overtaken";
            default -> "requested";
        };
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("requestedAt", time(requestedAt));
        out.put("outcome", outcome);
        return out;
    }

    /**
     * log.state, from the log objects. {@code final} once final.json exists, with its
     * outcome and flags. Before that: {@code pending} with nothing written, {@code live} with
     * an index, {@code finishing} once the attempt has settled (the sweep may yet write the
     * record); {@code none} for an attempt that never ran, and {@code expired} past retention.
     * A store that cannot be read is {@code storage_unavailable}: the status still answers.
     */
    private Map<String, Object> log(UUID contentId, UUID buildId, String state, Object startedAt, Object finishedAt,
                                    StoreReads reads) {
        boolean terminal = TERMINAL.contains(state);
        String logState;
        LogFinal fin = null;
        if (startedAt == null) {
            // Nothing is written before a claim: QUEUED is pending, and an attempt that ended
            // without running (cancelled or superseded while queued) never has a log.
            logState = terminal ? "none" : "pending";
        } else if (reads.down) {
            // One failed read already this request: the rest would each wait out the SDK's
            // retries for the same answer (9baa932 N2).
            logState = "storage_unavailable";
        } else {
            boolean pastRetention = terminal
                    && ((Timestamp) finishedAt).toInstant().isBefore(Instant.now().minus(LOG_RETENTION));
            try {
                fin = finalOf(contentId, buildId, pastRetention).orElse(null);
                if (fin != null) {
                    logState = "final";
                } else if (pastRetention) {
                    logState = "expired";
                } else if (terminal) {
                    logState = "finishing";
                } else {
                    logState = logs.readIndex(contentId, buildId).isPresent() ? "live" : "pending";
                }
            } catch (ObjectStoreException e) {
                log.warn("build {}: the log store could not be read: {}", buildId, e.getMessage());
                reads.down = true;
                logState = "storage_unavailable";
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("state", logState);
        Map<String, Object> f = null;
        if (fin != null) {
            f = new LinkedHashMap<>();
            f.put("outcome", fin.outcome());
            f.put("complete", fin.complete());
            f.put("truncated", fin.truncated());
        }
        out.put("final", f);
        return out;
    }

    /**
     * Past retention the store is asked, never the cache: retention may have deleted the
     * record, and the status must then say expired, as replay does (9baa932 N1).
     */
    private Optional<LogFinal> finalOf(UUID contentId, UUID buildId, boolean pastRetention) {
        LogFinal cached = pastRetention ? null : finals.get(buildId);
        if (cached != null) {
            return Optional.of(cached);
        }
        Optional<LogFinal> read = logs.readFinal(contentId, buildId);
        read.ifPresent(f -> finals.put(buildId, f));
        return read;
    }

    // ------------------------------------------------------------------ refusals and values

    private TransportException notFound(UUID contentId, UUID buildId) {
        requireContent(contentId);
        return new TransportException(TransportError.BUILD_NOT_FOUND,
                "no build " + buildId + " in content " + contentId);
    }

    private void requireContent(UUID contentId) {
        Integer content = jdbc.queryForObject("SELECT count(*) FROM skald.content WHERE id = ?", Integer.class,
                contentId);
        if (content == null || content == 0) {
            throw new TransportException(TransportError.CONTENT_NOT_FOUND, "no content " + contentId);
        }
    }

    private static Object json(String text) {
        try {
            return text == null ? null : TransportJson.JSON.readTree(text);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("a stored jsonb column did not parse", e);
        }
    }

    private static String printable(String s) {
        return eu.openanalytics.shinyproxy.publisher.bundle.BundleRejection.printable(s);
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }

    private static String time(Object value) {
        return value == null ? null : ((Timestamp) value).toInstant().toString();
    }
}
