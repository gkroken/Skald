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
import eu.openanalytics.shinyproxy.publisher.bundle.BundleRejection;
import eu.openanalytics.shinyproxy.publisher.bundle.BundleRule;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

/**
 * The database half of bundle validation: VALIDATING rows as claimed, leased work
 * (WORKPLAN-BUNDLES.md T8 plan, 6a23bcb-F1; V5).
 *
 * <p>The pattern is the build coordinator's (T6): a claim takes the oldest claimable row with
 * {@code FOR UPDATE SKIP LOCKED} and bumps its generation, and every later change a validator
 * makes is conditional on that generation and on the row still being VALIDATING. A validator
 * that lost its lease -- to a stall, or to the reaper -- is therefore fenced: its writes are
 * refused rather than merged, and it learns so from a false return.
 *
 * <p><b>What counts as a lapse.</b> A claim that finds an owner still recorded is taking over
 * an expired lease: that owner never released it, so it died (or stalled past the lease).
 * Only that increments {@code validation_claims}. A validator that hit a storage failure
 * releases its claim -- owner null -- so its retry is not a lapse, and an outage cannot read
 * as a poison bundle (95e2185-F1).
 *
 * <p><b>The reaper names what it observed</b>, with a recorded storage error first
 * (spec/admin-transport-v1.json shapes.validation_claims): an UPLOADING row past its upload
 * deadline is UPLOAD_ABANDONED; a VALIDATING row whose lapses reach max-claims is
 * VALIDATION_INTERRUPTED, and one past its deadline is VALIDATION_STALLED -- either becomes
 * VALIDATION_STORAGE when a storage failure is recorded.
 */
public class ValidationQueue {

    /** A claimed VALIDATING bundle: what a validator needs, and the generation that fences it. */
    public record Claimed(UUID bundleId, UUID contentId, String contentType, String objectKey,
                          String objectSha256, long objectBytes, long generation, String owner) { }

    private static final int DETAIL_BUDGET = 1000;

    private final JdbcTemplate jdbc;
    private final ValidationSettings settings;
    private final TransactionTemplate tx;
    private final ObjectMapper json = new ObjectMapper();

    public ValidationQueue(JdbcTemplate jdbc, ValidationSettings settings) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
    }

    /**
     * Claims the oldest VALIDATING bundle with no live lease and no pending backoff, whose
     * lapses (counting this takeover, if it is one) stay below max-claims. A row at the limit is
     * left for the reaper, which rejects it; a validator never runs it again.
     */
    public Optional<Claimed> claim(String owner) {
        return tx.execute(status -> {
            List<Map<String, Object>> rows = jdbc.queryForList("UPDATE skald.bundle b SET"
                            + " validation_claims = b.validation_claims"
                            + "   + CASE WHEN b.validation_owner IS NOT NULL THEN 1 ELSE 0 END,"
                            + " validation_owner = ?,"
                            + " validation_lease_expires_at = now() + make_interval(secs => ?),"
                            + " validation_generation = b.validation_generation + 1,"
                            + " validation_retry_at = NULL, updated_at = now()"
                            + " FROM skald.content c"
                            + " WHERE b.id = (SELECT id FROM skald.bundle WHERE state = 'VALIDATING'"
                            + "   AND (validation_lease_expires_at IS NULL OR validation_lease_expires_at < now())"
                            + "   AND (validation_retry_at IS NULL OR validation_retry_at <= now())"
                            + "   AND validation_claims + CASE WHEN validation_owner IS NOT NULL THEN 1 ELSE 0 END < ?"
                            + "   ORDER BY validating_at, id LIMIT 1 FOR UPDATE SKIP LOCKED)"
                            + " AND c.id = b.content_id AND b.state = 'VALIDATING'"
                            + " RETURNING b.id, b.content_id, c.type, b.object_key, b.object_sha256,"
                            + " b.object_bytes, b.validation_generation",
                    owner, settings.lease().toSeconds(), settings.maxClaims());
            if (rows.isEmpty()) {
                return Optional.<Claimed>empty();
            }
            Map<String, Object> r = rows.get(0);
            Claimed claimed = new Claimed((UUID) r.get("id"), (UUID) r.get("content_id"),
                    (String) r.get("type"), (String) r.get("object_key"), (String) r.get("object_sha256"),
                    ((Number) r.get("object_bytes")).longValue(),
                    ((Number) r.get("validation_generation")).longValue(), owner);
            audit(owner, "bundle.validation.claimed", claimed.bundleId(),
                    Map.of("generation", String.valueOf(claimed.generation())));
            return Optional.of(claimed);
        });
    }

    /** Extends a live lease. False when the claim is no longer the caller's: stop. */
    public boolean renew(Claimed c) {
        return jdbc.update("UPDATE skald.bundle SET validation_lease_expires_at = now()"
                        + " + make_interval(secs => ?), updated_at = now()"
                        + " WHERE id = ? AND state = 'VALIDATING' AND validation_generation = ?"
                        + " AND validation_owner = ?",
                settings.lease().toSeconds(), c.bundleId(), c.generation(), c.owner()) == 1;
    }

    /** Every rule passed. False when fenced. */
    public boolean validated(Claimed c, String manifestSha256, long files, long bytes) {
        return tx.execute(status -> {
            int changed = jdbc.update("UPDATE skald.bundle SET state = 'VALIDATED',"
                            + " manifest_sha256 = ?, inventory_files = ?, inventory_bytes = ?,"
                            + " validation_owner = NULL, validation_lease_expires_at = NULL,"
                            + " validation_storage_error = NULL, validation_retry_at = NULL,"
                            + " finished_at = now(), updated_at = now()"
                            + " WHERE id = ? AND state = 'VALIDATING' AND validation_generation = ?",
                    manifestSha256, files, bytes, c.bundleId(), c.generation());
            if (changed == 1) {
                audit(c.owner(), "bundle.validated", c.bundleId(), Map.of("files", String.valueOf(files),
                        "bytes", String.valueOf(bytes)));
            }
            return changed == 1;
        });
    }

    /** A named rule failed. False when fenced. */
    public boolean rejected(Claimed c, BundleRule rule, String detail) {
        return tx.execute(status -> {
            int changed = jdbc.update("UPDATE skald.bundle SET state = 'REJECTED',"
                            + " rejection_rule = ?, rejection_detail = ?,"
                            + " validation_owner = NULL, validation_lease_expires_at = NULL,"
                            + " validation_retry_at = NULL, finished_at = now(), updated_at = now()"
                            + " WHERE id = ? AND state = 'VALIDATING' AND validation_generation = ?",
                    rule.ruleName(), bounded(detail), c.bundleId(), c.generation());
            if (changed == 1) {
                audit(c.owner(), "bundle.rejected", c.bundleId(), Map.of("rule", rule.ruleName()));
            }
            return changed == 1;
        });
    }

    /**
     * The store failed this attempt: record what it said, release the claim (so the retry is
     * not a lapse) and hold the row back for retry-after. False when fenced.
     */
    public boolean storageFailed(Claimed c, String error) {
        return jdbc.update("UPDATE skald.bundle SET validation_storage_error = ?,"
                        + " validation_owner = NULL, validation_lease_expires_at = NULL,"
                        + " validation_retry_at = now() + make_interval(secs => ?), updated_at = now()"
                        + " WHERE id = ? AND state = 'VALIDATING' AND validation_generation = ?",
                bounded(error), settings.retryAfter().toSeconds(), c.bundleId(), c.generation()) == 1;
    }

    /**
     * Something that is neither the bundle's fault nor the store's (a bug, a full workspace
     * disk): release without recording anything, so the deadline, if it comes, says STALLED --
     * nothing finished -- and not a cause nobody observed. False when fenced.
     */
    public boolean released(Claimed c) {
        return jdbc.update("UPDATE skald.bundle SET validation_owner = NULL,"
                        + " validation_lease_expires_at = NULL,"
                        + " validation_retry_at = now() + make_interval(secs => ?), updated_at = now()"
                        + " WHERE id = ? AND state = 'VALIDATING' AND validation_generation = ?",
                settings.retryAfter().toSeconds(), c.bundleId(), c.generation()) == 1;
    }

    /**
     * The three platform verdicts, in the precedence the spec records. Each UPDATE is one
     * statement, so a row is judged once; a row a validator still holds is fenced by the state
     * change itself.
     *
     * @return how many bundles were rejected
     */
    public int reap() {
        int n = 0;
        n += reject("'UPLOAD_ABANDONED'", "state = 'UPLOADING' AND upload_deadline < now()",
                "'no complete upload arrived before the upload deadline (' || upload_deadline || ');"
                        + " upload the bundle again under a new bundle id'");
        n += reject(storageOr("VALIDATION_INTERRUPTED"),
                "state = 'VALIDATING' AND validation_owner IS NOT NULL AND validation_lease_expires_at < now()"
                        + " AND validation_claims + 1 >= " + settings.maxClaims(),
                storageDetailOr("'validation of this bundle was interrupted ' || (validation_claims + 1)"
                        + " || ' times (its validator stopped renewing its lease each time): something in"
                        + " the bundle ends validators, such as exhausting their memory'"));
        n += reject(storageOr("VALIDATION_STALLED"),
                "state = 'VALIDATING' AND validating_at + make_interval(secs => "
                        + settings.deadline().toSeconds() + ") < now()",
                storageDetailOr("'no validator finished this bundle within " + settings.deadline().toMinutes()
                        + " minutes of its upload, and none recorded a failure'"));
        return n;
    }

    private static String storageOr(String rule) {
        return "CASE WHEN validation_storage_error IS NOT NULL THEN 'VALIDATION_STORAGE' ELSE '" + rule + "' END";
    }

    private static String storageDetailOr(String detail) {
        return "CASE WHEN validation_storage_error IS NOT NULL THEN 'the object store kept failing while"
                + " this bundle was validated: ' || validation_storage_error ELSE " + detail + " END";
    }

    private int reject(String ruleSql, String where, String detailSql) {
        Integer[] count = {0};
        tx.executeWithoutResult(status -> {
            List<Map<String, Object>> rows = jdbc.queryForList("UPDATE skald.bundle SET state = 'REJECTED',"
                    + " rejection_rule = " + ruleSql + ", rejection_detail = left(" + detailSql + ", "
                    + DETAIL_BUDGET + "),"
                    + " validation_owner = NULL, validation_lease_expires_at = NULL,"
                    + " validation_retry_at = NULL, finished_at = now(), updated_at = now()"
                    + " WHERE " + where + " RETURNING id, rejection_rule");
            for (Map<String, Object> r : rows) {
                audit("skald-reaper", "bundle.rejected", (UUID) r.get("id"),
                        Map.of("rule", (String) r.get("rejection_rule")));
            }
            count[0] = rows.size();
        });
        return count[0];
    }

    /** Bounded and printable: these strings reach an admin's terminal and the audit log. */
    static String bounded(String text) {
        String printable = BundleRejection.printable(text == null ? "" : text);
        return printable.length() <= DETAIL_BUDGET ? printable
                : printable.substring(0, DETAIL_BUDGET - BundleRejection.ELLIPSIS.length())
                        + BundleRejection.ELLIPSIS;
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
}
