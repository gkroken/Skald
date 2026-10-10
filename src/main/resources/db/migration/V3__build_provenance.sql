-- Skald V3: the build record's provenance.
--
-- WORKPLAN-BUNDLES.md: "Full build provenance additionally binds the validated bundle
-- inventory, generated recipe and effective isolation/limit policy" and "provenance belongs
-- to the build record". Additive and forward-only: one nullable column and two checks.
--
-- What the document holds is the server's own record (schema "skald.build-provenance/v1",
-- written by the build driver): the bundle's receipt digests, the base by digest and its
-- catalog entry, the generated recipe's digests, the dependency cache, the mirrors, the
-- worker and gateway images by the id each container ran, the isolation profile and the
-- effective limits. Nothing in it is taken from the upload. The image itself also carries
-- BuildKit's SLSA provenance attestation; this column is what only the platform knows.
--
-- A build with an output image has provenance: an image whose inputs are not on record is
-- not an image this platform built. Rows from before V3 (development databases only: V2
-- has not shipped) are marked as such rather than left to fail the check.
ALTER TABLE skald.build ADD COLUMN provenance jsonb;

UPDATE skald.build SET provenance = '{"schema": "skald.build-provenance/unrecorded"}'::jsonb
    WHERE output_image IS NOT NULL;

ALTER TABLE skald.build
    ADD CONSTRAINT build_provenance_is_a_record CHECK (
        -- COALESCE: on a record without "schema" the comparison is NULL, and a CHECK that
        -- evaluates to NULL passes.
        provenance IS NULL OR COALESCE(jsonb_typeof(provenance) = 'object'
            AND jsonb_typeof(provenance -> 'schema') = 'string', false)),
    ADD CONSTRAINT build_image_has_provenance CHECK (
        output_image IS NULL OR provenance IS NOT NULL);
