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

    /**
     * How long one log-store read may take before it counts as the store being unavailable.
     * A hung store (paused, not refusing) otherwise holds a read for the S3 client's socket
     * timeout times its attempts, about 90 s: a status poll would hang and a stream go silent,
     * neither saying storage_unavailable (found probing f859b52).
     */
    static final Duration STORE_BUDGET = Duration.ofSeconds(5);
    private volatile Duration storeBudget = STORE_BUDGET;
    private static final java.util.concurrent.ExecutorService READS =
            java.util.concurrent.Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("skald-log-read-", 0).factory());

    /** The budget of one log-store read. */
    Duration storeBudget() {
        return storeBudget;
    }

    /** Test seam: a shorter budget. */
    BuildService withStoreBudget(Duration budget) {
        this.storeBudget = budget;
        return this;
    }

    /**
     * One store read within the budget. Past it the read is abandoned -- interrupted, and
     * left to end at the client's own timeout -- and the caller sees a storage failure.
     */
    private <T> T bounded(java.util.concurrent.Callable<T> read) {
        java.util.concurrent.Future<T> f = READS.submit(read);
        try {
            return f.get(storeBudget.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            f.cancel(true);
            throw new ObjectStoreException("the log store did not answer within " + storeBudget.toSeconds() + " s");
        } catch (java.util.concurrent.ExecutionException e) {
            if (e.getCause() instanceof RuntimeException r) {
                throw r;
            }
            throw new ObjectStoreException("the log store could not be read", e.getCause());
        } catch (InterruptedException e) {
            f.cancel(true);
            Thread.currentThread().interrupt();
            throw new ObjectStoreException("interrupted while reading the log store", e);
        }
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

    // ------------------------------------------------------------------ build.logs.replay

    /** Records a page may hold when the caller names no limit: ndjson's default and the max. */
    static final int REPLAY_DEFAULT = 1000;
    static final int REPLAY_MAX = 10_000;
    private static final java.util.regex.Pattern CURSOR =
            java.util.regex.Pattern.compile("(0|[1-9][0-9]{0,11})\\.(0|[1-9][0-9]{0,8})");

    /**
     * A position after one record: chunk {@code sequence}, record {@code record} of it,
     * counted from 1. {@code s.0} is before chunk s's first record, and 0.0 the start.
     */
    record Cursor(long sequence, long record) {
        static final Cursor START = new Cursor(0, 0);

        /** shapes.cursor: the whole string, matches() and never find() (dd46cac-F2). */
        static Cursor parse(String text) {
            if (text == null) {
                return START;
            }
            java.util.regex.Matcher m = CURSOR.matcher(text);
            if (!m.matches()) {
                throw new TransportException(TransportError.CURSOR_INVALID, "'" + printable(text)
                        + "' is not a cursor; use one a previous reply gave, or none to start");
            }
            return new Cursor(Long.parseLong(m.group(1)), Long.parseLong(m.group(2)));
        }

        @Override
        public String toString() {
            return sequence + "." + record;
        }
    }

    /**
     * One page of a build's log: its records as served (shapes.log_records), the end record
     * when this page reached the end of a final log, and the cursor to continue from when
     * more is advertised than the page held.
     */
    public record Replay(String logState, String buildState, List<com.fasterxml.jackson.databind.node.ObjectNode> records,
                         com.fasterxml.jackson.databind.node.ObjectNode end, String next) { }

    /**
     * Reads the log after {@code after}, at most {@code limit} records (negative: no limit).
     * Only what the log advertises is served -- final.json once it exists, else index.json --
     * so a cursor handed out is valid again later. A cursor ahead of that returns nothing.
     */
    public Replay replay(UUID contentId, UUID buildId, String after, int limit) {
        Cursor from = Cursor.parse(after);
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT state, started_at, finished_at FROM skald.build"
                + " WHERE id = ? AND content_id = ?", buildId, contentId);
        if (rows.isEmpty()) {
            throw notFound(contentId, buildId);
        }
        String buildState = (String) rows.get(0).get("state");
        String logState = (String) log(contentId, buildId, buildState, rows.get(0).get("started_at"),
                rows.get(0).get("finished_at"), new StoreReads()).get("state");
        switch (logState) {
            case "storage_unavailable" -> throw new TransportException(TransportError.STORAGE_UNAVAILABLE,
                    "the log store could not be read; try again shortly");
            case "expired" -> throw new TransportException(TransportError.LOG_EXPIRED,
                    "build " + buildId + "'s log was removed by retention (" + LOG_RETENTION.toDays() + " days)");
            case "pending" -> {
                return new Replay(logState, buildState, List.of(), null, null);
            }
            case "none" -> {
                // Ended without running: no log, and none will come. Said with the end record,
                // outcome null, so a stream ends and a reconnect is answered at once (8dc88f1-F1).
                com.fasterxml.jackson.databind.node.ObjectNode end = TransportJson.JSON.createObjectNode();
                end.putObject("end").putNull("outcome").put("complete", false).put("truncated", false)
                        .put("buildState", buildState);
                return new Replay(logState, buildState, List.of(), end, null);
            }
            default -> { }
        }
        List<com.fasterxml.jackson.databind.node.ObjectNode> out = new ArrayList<>();
        try {
            LogFinal fin = finalOf(contentId, buildId, false).orElse(null);
            long advertised = fin != null ? fin.lastSequence()
                    : bounded(() -> logs.readIndex(contentId, buildId)).map(i -> i.lastSequence()).orElse(0L);
            long sequence = Math.max(1, from.sequence());
            long skip = from.sequence() == 0 ? 0 : from.record();
            for (; sequence <= advertised; sequence++, skip = 0) {
                long at = sequence;
                byte[] chunk = bounded(() -> logs.readChunk(contentId, buildId, at)).orElseThrow(() ->
                        new ObjectStoreException("an advertised chunk is not in the store"));
                List<String> lines = chunkLines(chunk);
                for (int i = (int) Math.min(skip, lines.size()); i < lines.size(); i++) {
                    if (limit >= 0 && out.size() == limit) {
                        return new Replay(logState, buildState, out, null,
                                String.valueOf(out.get(out.size() - 1).get("cursor").asText()));
                    }
                    out.add(record(new Cursor(sequence, i + 1L), lines.get(i)));
                }
            }
            com.fasterxml.jackson.databind.node.ObjectNode end = null;
            if (fin != null) {
                end = TransportJson.JSON.createObjectNode();
                end.putObject("end").put("outcome", fin.outcome()).put("complete", fin.complete())
                        .put("truncated", fin.truncated()).put("buildState", buildState);
            }
            return new Replay(logState, buildState, out, end, null);
        } catch (ObjectStoreException e) {
            log.warn("build {}: the log store could not be read: {}", buildId, e.getMessage());
            throw new TransportException(TransportError.STORAGE_UNAVAILABLE,
                    "the log store could not be read; try again shortly from the same cursor");
        }
    }

    private static List<String> chunkLines(byte[] chunk) {
        List<String> lines = new ArrayList<>();
        for (String line : new String(chunk, java.nio.charset.StandardCharsets.UTF_8).split("\n")) {
            if (!line.isEmpty()) {
                lines.add(line);
            }
        }
        return lines;
    }

    /**
     * A stored record as served: the line with its cursor, or the head/tail marker, stored as
     * {@code {"cut":{...}}}, renamed {@code gap} so the two meanings of cut do not share a key.
     */
    private static com.fasterxml.jackson.databind.node.ObjectNode record(Cursor at, String stored) {
        com.fasterxml.jackson.databind.JsonNode node;
        try {
            node = TransportJson.JSON.readTree(stored);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("a stored log record did not parse", e);
        }
        com.fasterxml.jackson.databind.node.ObjectNode out = TransportJson.JSON.createObjectNode();
        out.put("cursor", at.toString());
        if (node.path("t").isTextual() && node.path("n").canConvertToLong()) {
            out.put("n", node.get("n").asLong());
            out.put("t", node.get("t").asText());
            if (node.path("cut").asBoolean(false)) {
                out.put("cut", true);
            }
        } else if (node.path("cut").isObject()) {
            out.putObject("gap").put("lines", node.get("cut").path("lines").asLong())
                    .put("bytes", node.get("cut").path("bytes").asLong());
        } else {
            throw new IllegalStateException("a stored log record is neither a line nor the marker");
        }
        return out;
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
        out.put("version", version(contentId, id));
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
     * The version a success became (T9 (b)): its number, its spec id, and whether it is the
     * active one. Null for every other attempt; a success is staged, and activating it is an
     * admin's explicit act.
     */
    private Map<String, Object> version(UUID contentId, UUID buildId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT v.version, (c.active_version_id = v.id) AS active"
                + " FROM skald.content_version v JOIN skald.content c ON c.id = v.content_id"
                + " WHERE v.build_id = ? AND v.content_id = ?", buildId, contentId);
        if (rows.isEmpty()) {
            return null;
        }
        int number = ((Number) rows.get(0).get("version")).intValue();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("number", number);
        out.put("specId", eu.openanalytics.shinyproxy.publisher.registry.ContentSpecRepository.specId(contentId, number));
        out.put("active", Boolean.TRUE.equals(rows.get(0).get("active")));
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
                    logState = bounded(() -> logs.readIndex(contentId, buildId)).isPresent() ? "live" : "pending";
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
        Optional<LogFinal> read = bounded(() -> logs.readFinal(contentId, buildId));
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
