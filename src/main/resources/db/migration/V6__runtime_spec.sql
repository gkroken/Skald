-- Skald V6: a built version carries its runtime document (WORKPLAN-BUNDLES.md T9 (a),
-- 29053ce-F2).
--
-- A version with a build is untrusted code a publisher uploaded, and it runs only as the
-- runtime document says: the base's non-root user, its port, the deployment's limits. A
-- version without a build is an admin's direct image and keeps '{}'. The repository already
-- refuses to run a built version without a valid skald.runtime/v1 document; this CHECK says
-- the same in the database, so no writer -- a null handed to the allocator, a bug, a
-- migration -- can store one that only the repository stands between and an unbounded
-- start. It pins the schema string only; the document's full validity, and the image being
-- pinned by digest, stay the repository's fail-closed judgement (spec/runtime-spec-v1.json).
--
-- Additive and forward-only. No row breaks it at the time of writing: no build had reached
-- SUCCEEDED before T9, so every existing version has build_id NULL.
ALTER TABLE skald.content_version
    -- COALESCE: '{}' ->> 'schema' is NULL, and a CHECK that evaluates to NULL passes, so
    -- without it the one document this exists to refuse would be let through (V3's lesson).
    ADD CONSTRAINT content_version_built_has_runtime_spec CHECK (
        build_id IS NULL OR COALESCE(spec_json ->> 'schema' = 'skald.runtime/v1', false));
