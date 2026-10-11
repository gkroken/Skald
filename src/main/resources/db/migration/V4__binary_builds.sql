-- Skald V4: the queue of shared Linux R binaries (WORKPLAN-BUNDLES.md T7b, step 2, point 3).
--
-- One row per platform key, package and version: a public CRAN package version Skald
-- compiles ONCE for a platform, in its own sandbox, and uploads to forge from outside it
-- (option 3b, user 2026-10-10). Additive and forward-only: two new tables, nothing in V1-V3
-- is altered.
--
-- The row's life:
--   PENDING_SOURCE  queued; the trusted side has not yet fetched and verified its source.
--   VERIFYING       leased: the trusted side is fetching and checking the source tarball.
--   QUEUED          the source is verified (its digests recorded) and its dependency edges
--                   are known; claimable once every dependency is UPLOADED.
--   RUNNING         leased: compiling in a sandbox, then uploading from outside it.
--   UPLOADED        a Skald attempt uploaded this binary; binary_sha256 is ITS digest.
--   PRESENT_FOREIGN forge already held a binary Skald did not produce (a 409, or found
--                   before compiling): its digest is recorded, never claimed as Skald's
--                   (c365d95 N2), and never handed into another package's sandbox (89a33dc N1).
--   REFUSED         a check said no (refusal_code names it): not a public source, a changed
--                   tarball, a dependency that is not Skald-built, a dependency cycle.
--   FAILED          out of attempts.
-- A lost lease is not a state: the reaper returns the row to the state it was claimed from,
-- or FAILED once its attempts are used up.
--
-- What is enforced HERE, and why:
--   * the identity (key, package, version) is unique and never changes, and the source
--     digests, once recorded, never change (a trigger): a tarball republished under the same
--     version can only be refused, never re-recorded (604d63b N1);
--   * a binary's digest, once recorded, never changes;
--   * UPLOADED has a digest and provenance; QUEUED onwards to UPLOADED has source digests; a
--     leased state has a lease; a finished row has a finish time; a refusal names its code;
--   * a dependency edge joins two rows of the SAME platform key, never a row to itself.
-- What is NOT here: the transition graph (fenced UPDATEs in BinaryBuildQueue, as for builds),
-- and cycle detection (a CHECK cannot walk a graph; the queue refuses a cycle when it
-- records the edges).

CREATE TABLE skald.binary_build (
    id                 uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    -- forge's platform key, <codename>-<arch>/<rminor>. The codename is as strict as
    -- images/catalog.json's (a subset of forge's), the arches are forge's two.
    platform_key       text        NOT NULL,
    package            text        NOT NULL,
    version            text        NOT NULL,
    state              text        NOT NULL DEFAULT 'PENDING_SOURCE',
    -- The app build whose successful lock first asked for it. A plain value, not a foreign
    -- key: the shared binary outlives the content item that happened to ask first.
    requested_by_build uuid,
    -- Claims of the current phase (verifying, or compiling); reset when the source is
    -- recorded, so a flaky fetch does not use up the compile's attempts.
    attempts           integer     NOT NULL DEFAULT 0,
    lease_owner        text,
    lease_expires_at   timestamptz,
    lease_generation   bigint      NOT NULL DEFAULT 0,
    deadline_at        timestamptz,
    -- The tarball as first verified: forge's SHA-256 and the public index's MD5sum.
    source_sha256      text,
    source_md5         text,
    -- UPLOADED: the digest Skald uploaded. PRESENT_FOREIGN: the digest forge holds.
    binary_sha256      text,
    provenance         jsonb,
    refusal_code       text,
    refusal_detail     text,
    error_code         text,
    error_detail       text,
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now(),
    started_at         timestamptz,
    finished_at        timestamptz,
    CONSTRAINT binary_build_identity UNIQUE (platform_key, package, version),
    -- What the dependency edges' composite foreign keys reference.
    CONSTRAINT binary_build_id_key UNIQUE (id, platform_key),
    CONSTRAINT binary_build_key_form CHECK (
        platform_key ~ '^[a-z][a-z0-9]{1,31}-(x86_64|aarch64)/[0-9]{1,3}\.[0-9]{1,3}$'),
    -- Writing R Extensions 1.1.1: letters, digits and '.', at least two characters, starting
    -- with a letter and not ending in '.'; a version is two or more integers joined by '.'
    -- or '-'. Both become a forge path segment, so nothing else gets in.
    CONSTRAINT binary_build_package_form CHECK (
        length(package) <= 100 AND package ~ '^[A-Za-z][A-Za-z0-9.]*[A-Za-z0-9]$'),
    CONSTRAINT binary_build_version_form CHECK (
        length(version) <= 64 AND version ~ '^[0-9]+([.-][0-9]+)+$'),
    CONSTRAINT binary_build_state_known CHECK (state IN (
        'PENDING_SOURCE', 'VERIFYING', 'QUEUED', 'RUNNING',
        'UPLOADED', 'PRESENT_FOREIGN', 'REFUSED', 'FAILED')),
    CONSTRAINT binary_build_terminal_has_time CHECK (
        (state IN ('UPLOADED', 'PRESENT_FOREIGN', 'REFUSED', 'FAILED')) = (finished_at IS NOT NULL)),
    -- A leased state carries a whole lease; any other state none, so a stale owner can never
    -- be mistaken for a live one.
    CONSTRAINT binary_build_leased CHECK (CASE WHEN state IN ('VERIFYING', 'RUNNING')
        THEN lease_owner IS NOT NULL AND lease_expires_at IS NOT NULL AND deadline_at IS NOT NULL
            AND lease_generation > 0 AND started_at IS NOT NULL
        ELSE lease_owner IS NULL AND lease_expires_at IS NULL END),
    CONSTRAINT binary_build_verified_has_source CHECK (
        state NOT IN ('QUEUED', 'RUNNING', 'UPLOADED')
            OR (source_sha256 IS NOT NULL AND source_md5 IS NOT NULL)),
    CONSTRAINT binary_build_uploaded_is_recorded CHECK (
        state <> 'UPLOADED' OR (binary_sha256 IS NOT NULL AND provenance IS NOT NULL
            AND COALESCE(jsonb_typeof(provenance) = 'object'
                AND jsonb_typeof(provenance -> 'schema') = 'string', false))),
    CONSTRAINT binary_build_foreign_has_digest CHECK (
        state <> 'PRESENT_FOREIGN' OR binary_sha256 IS NOT NULL),
    CONSTRAINT binary_build_refused_names_code CHECK ((state = 'REFUSED') = (refusal_code IS NOT NULL)),
    CONSTRAINT binary_build_failed_names_code CHECK (state <> 'FAILED' OR error_code IS NOT NULL),
    CONSTRAINT binary_build_digests_hex CHECK (
        (source_sha256 IS NULL OR source_sha256 ~ '^[0-9a-f]{64}$')
        AND (source_md5 IS NULL OR source_md5 ~ '^[0-9a-f]{32}$')
        AND (binary_sha256 IS NULL OR binary_sha256 ~ '^[0-9a-f]{64}$')),
    CONSTRAINT binary_build_attempts_nonnegative CHECK (attempts >= 0)
);
CREATE INDEX binary_build_claim_order ON skald.binary_build (created_at, id)
    WHERE state IN ('PENDING_SOURCE', 'QUEUED');
CREATE INDEX binary_build_leased_idx ON skald.binary_build (lease_expires_at)
    WHERE state IN ('VERIFYING', 'RUNNING');

-- What once recorded never changes. A trigger, because a CHECK cannot see the old row.
CREATE FUNCTION skald.binary_build_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.platform_key <> OLD.platform_key OR NEW.package <> OLD.package
            OR NEW.version <> OLD.version THEN
        RAISE EXCEPTION 'binary_build_identity_immutable: a row''s key, package and version never change'
            USING ERRCODE = 'check_violation';
    END IF;
    IF (OLD.source_sha256 IS NOT NULL AND NEW.source_sha256 IS DISTINCT FROM OLD.source_sha256)
            OR (OLD.source_md5 IS NOT NULL AND NEW.source_md5 IS DISTINCT FROM OLD.source_md5) THEN
        RAISE EXCEPTION 'binary_build_source_immutable: the source digests recorded at first fetch never change'
            USING ERRCODE = 'check_violation';
    END IF;
    IF OLD.binary_sha256 IS NOT NULL AND NEW.binary_sha256 IS DISTINCT FROM OLD.binary_sha256 THEN
        RAISE EXCEPTION 'binary_build_binary_immutable: a recorded binary digest never changes'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER binary_build_immutable BEFORE UPDATE ON skald.binary_build
    FOR EACH ROW EXECUTE FUNCTION skald.binary_build_immutable();

-- "binary_build needs dependency": read from the verified tarball's DESCRIPTION (Depends,
-- Imports, LinkingTo; base packages excluded) on the trusted side. Same platform key, held
-- by composite foreign keys; rows are never deleted, so RESTRICT.
CREATE TABLE skald.binary_build_dep (
    binary_build_id uuid NOT NULL,
    dependency_id   uuid NOT NULL,
    platform_key    text NOT NULL,
    PRIMARY KEY (binary_build_id, dependency_id),
    CONSTRAINT binary_build_dep_from FOREIGN KEY (binary_build_id, platform_key)
        REFERENCES skald.binary_build (id, platform_key),
    CONSTRAINT binary_build_dep_to FOREIGN KEY (dependency_id, platform_key)
        REFERENCES skald.binary_build (id, platform_key),
    CONSTRAINT binary_build_dep_not_self CHECK (binary_build_id <> dependency_id)
);
CREATE INDEX binary_build_dep_reverse ON skald.binary_build_dep (dependency_id);
