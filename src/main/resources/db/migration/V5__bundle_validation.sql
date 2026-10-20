-- Skald V5: a VALIDATING bundle is claimed, leased work (WORKPLAN-BUNDLES.md T8 plan,
-- 6a23bcb-F1).
--
-- Validation runs after the upload has returned, so it must survive the instance that
-- accepted the upload: a deploy, a crash or an OOM. A row in VALIDATING is therefore
-- claimed like a build attempt (T6): FOR UPDATE SKIP LOCKED, a generation that fences a
-- validator which lost its lease, and a lease any instance may take over once it lapses.
-- Additive and forward-only: new columns on skald.bundle, nothing in V1-V4 is altered.
--
--   validating_at               when the row entered VALIDATING; the validation deadline
--                               runs from here, not from updated_at, which every renewal moves
--   validation_owner            the validator holding the lease, or null
--   validation_lease_expires_at the lease; null exactly when there is no owner
--   validation_generation       bumped by every claim; the final transition names it
--   validation_claims           claims that ended by LEASE LAPSE only (claimed, never released,
--                               expired: the validator died). A release after a recorded
--                               storage error is not one (95e2185-F1)
--   validation_storage_error    the last storage failure a validator recorded, bounded and
--                               printable; a deadline rejection names the store when set
--   validation_retry_at         not claimable before this: the backoff after a storage
--                               failure released the claim. Its own column, so that the lease
--                               column means one thing
ALTER TABLE skald.bundle
    ADD COLUMN validating_at               timestamptz,
    ADD COLUMN validation_owner            text,
    ADD COLUMN validation_lease_expires_at timestamptz,
    ADD COLUMN validation_generation       bigint  NOT NULL DEFAULT 0,
    ADD COLUMN validation_claims           integer NOT NULL DEFAULT 0,
    ADD COLUMN validation_storage_error    text,
    ADD COLUMN validation_retry_at         timestamptz;

ALTER TABLE skald.bundle
    -- A lease exists only while validating; leaving VALIDATING releases it in the same UPDATE.
    ADD CONSTRAINT bundle_lease_only_validating CHECK (
        state = 'VALIDATING'
            OR (validation_owner IS NULL AND validation_lease_expires_at IS NULL)),
    ADD CONSTRAINT bundle_owner_has_lease CHECK (
        (validation_owner IS NULL) = (validation_lease_expires_at IS NULL)),
    ADD CONSTRAINT bundle_validating_has_time CHECK (
        state <> 'VALIDATING' OR validating_at IS NOT NULL),
    -- The PUT's commit point (c50e4fa-F1): the row enters VALIDATING carrying the archive's
    -- key, digest and size, so a validator on any instance has what it needs to check the
    -- stored bytes before it trusts them.
    ADD CONSTRAINT bundle_validating_has_archive CHECK (
        state NOT IN ('VALIDATING', 'VALIDATED')
            OR (object_key IS NOT NULL AND object_sha256 IS NOT NULL AND object_bytes IS NOT NULL)),
    ADD CONSTRAINT bundle_validation_counts_nonnegative CHECK (
        validation_generation >= 0 AND validation_claims >= 0);

CREATE INDEX bundle_validation_queue ON skald.bundle (validating_at, id)
    WHERE state = 'VALIDATING';
CREATE INDEX bundle_upload_deadline ON skald.bundle (upload_deadline)
    WHERE state = 'UPLOADING';
