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

import eu.openanalytics.shinyproxy.publisher.bundle.BundleExtractor;
import eu.openanalytics.shinyproxy.publisher.bundle.BundleRejection;
import eu.openanalytics.shinyproxy.publisher.bundle.ExtractionLimits;
import eu.openanalytics.shinyproxy.publisher.bundle.ExtractionRoot;
import eu.openanalytics.shinyproxy.publisher.storage.BundleReceipt;
import eu.openanalytics.shinyproxy.publisher.storage.BundleWriter;
import eu.openanalytics.shinyproxy.publisher.storage.ObjectKeys;
import eu.openanalytics.shinyproxy.publisher.storage.ObjectStore;
import eu.openanalytics.shinyproxy.publisher.storage.ObjectStoreException;
import eu.openanalytics.shinyproxy.publisher.storage.StoredObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Validates one claimed bundle from its stored archive, and records the verdict through the
 * queue (WORKPLAN-BUNDLES.md T8 plan).
 *
 * <p>In order: the stored archive is streamed through the extractor (the schema, S1-S11, and
 * S9 against the content item's type), counted and digested as it passes; the bytes are then
 * held to the digest and size the PUT recorded on the row, so a store that returns other bytes
 * is a storage failure and never a verdict on the bundle; then {@code manifest.json},
 * {@code inventory.json} and {@code receipt.json} are written, create-only, in T4's order; then
 * the row becomes VALIDATED. The extracted tree is deleted on every path: the build re-extracts
 * from the archive under its own checks, and this copy exists only to be judged.
 *
 * <p><b>Re-running is safe</b> (6a23bcb-F1), and that is what makes a lapsed lease
 * recoverable: every write is create-only, and a write that finds its object already there
 * compares it. {@code manifest.json} is the manifest exactly as it arrived in the archive, so
 * it is the same bytes on every run and its digest is checkable against the archive by anyone;
 * the plan's "sorted keys" (95e2185 N1) asked for byte-identical output, which the document's
 * own bytes give without re-serialising -- and re-serialising would make the stored manifest
 * differ from the one the publisher wrote. {@code inventory.json} is {@link CanonicalInventory}.
 *
 * <p><b>Which failure is whose.</b> A {@link BundleRejection} is the bundle's, and names its
 * rule; it is logged as the rule and its message, never as a throwable with a chain, because a
 * cleanup failure attached to it can name a hostile path raw (T5 carry, 18368a9 N1). A failure
 * reading or writing the store is the store's: it is recorded and retried, and only the
 * deadline turns it into VALIDATION_STORAGE. Anything else is the platform's (a bug, a full
 * workspace disk): the claim is released and nothing is recorded, because nothing was observed
 * that a rule could name.
 */
public class BundleValidation {

    private static final Logger log = LoggerFactory.getLogger(BundleValidation.class);

    /** What one run ended in. */
    public enum Result { VALIDATED, REJECTED, STORAGE_FAILED, RELEASED, FENCED }

    private final ValidationQueue queue;
    private final ObjectStore store;
    private final BundleWriter writer;
    private final String bucket;
    private final ExtractionLimits limits;
    private final Path workspace;

    public BundleValidation(ValidationQueue queue, ObjectStore store, String bucket,
                            ExtractionLimits limits, Path workspace) {
        this.queue = queue;
        this.store = store;
        this.writer = new BundleWriter(store, bucket);
        this.bucket = bucket;
        this.limits = limits;
        this.workspace = workspace;
    }

    /**
     * @param lost   true once the claim's lease was lost; checked before any verdict is written
     * @param source receives the archive stream, so a renewal that finds the lease lost can
     *               close it and end the extraction early
     */
    public Result run(ValidationQueue.Claimed c, BooleanSupplier lost, Consumer<Closeable> source) {
        BundleExtractor.Extracted extracted = null;
        try {
            CountingDigestStream archive;
            try {
                archive = new CountingDigestStream(new StoreReads(store.open(bucket, c.objectKey())));
            } catch (ObjectStoreException e) {
                return storage(c, "could not open the stored archive: " + e.getMessage());
            }
            source.accept(archive);
            BundleRejection rejection = null;
            try (archive) {
                try {
                    extracted = BundleExtractor.extract(archive, c.objectBytes(), workspace, limits,
                            Optional.of(c.contentType()));
                } catch (BundleRejection r) {
                    rejection = r;
                }
                // A rejection can stop reading early. The rest is read too -- raw, not
                // inflated -- so the digest covers every byte and a store that returned other
                // bytes is caught before the bundle is blamed for them.
                archive.drain(c.objectBytes() + 1);
            } catch (StoreReadFailed e) {
                return lost.getAsBoolean() ? Result.FENCED
                        : storage(c, "the stored archive could not be read: " + e.getMessage());
            }
            if (lost.getAsBoolean()) {
                return Result.FENCED;
            }
            String digest = archive.sha256();
            if (archive.count() != c.objectBytes() || !digest.equals(c.objectSha256())) {
                return storage(c, "the stored archive is " + archive.count() + " bytes with SHA-256 "
                        + digest + ", not the " + c.objectBytes() + " bytes with SHA-256 "
                        + c.objectSha256() + " the upload recorded");
            }
            if (rejection != null) {
                log.info("bundle {} rejected: {}: {}", c.bundleId(), rejection.rule().ruleName(),
                        rejection.getMessage());
                return queue.rejected(c, rejection.rule(), rejection.getMessage())
                        ? Result.REJECTED : Result.FENCED;
            }
            return record(c, extracted, lost);
        } catch (ObjectStoreException e) {
            return lost.getAsBoolean() ? Result.FENCED : storage(c, e.getMessage());
        } catch (IOException | RuntimeException e) {
            if (lost.getAsBoolean()) {
                return Result.FENCED;
            }
            log.warn("validating bundle {} failed for a reason that is neither the bundle's nor the"
                    + " store's; releasing it for another attempt", c.bundleId(), e);
            return queue.released(c) ? Result.RELEASED : Result.FENCED;
        } finally {
            if (extracted != null) {
                deleteQuietly(c, extracted);
            }
        }
    }

    private Result record(ValidationQueue.Claimed c, BundleExtractor.Extracted extracted,
                          BooleanSupplier lost) {
        byte[] manifest = extracted.manifest();
        byte[] inventory = CanonicalInventory.of(extracted.validated().files());
        String manifestSha = sha256(manifest);
        String inventorySha = sha256(inventory);
        createOrCompare(c, ObjectKeys.BUNDLE_MANIFEST, writer.writeManifest(c.contentId(), c.bundleId(), manifest),
                manifestSha);
        createOrCompare(c, ObjectKeys.BUNDLE_INVENTORY, writer.writeInventory(c.contentId(), c.bundleId(), inventory),
                inventorySha);
        BundleReceipt receipt = new BundleReceipt(1, 1, c.contentId(), c.bundleId(), c.objectSha256(),
                c.objectBytes(), manifestSha, inventorySha);
        if (writer.commit(receipt).isEmpty()) {
            Optional<BundleReceipt> stored = writer.readReceipt(c.contentId(), c.bundleId());
            if (stored.isEmpty() || !stored.get().equals(receipt)) {
                throw new ObjectStoreException("a different receipt.json is already stored for bundle "
                        + c.bundleId() + ": " + stored.orElse(null));
            }
        }
        if (lost.getAsBoolean()) {
            return Result.FENCED;
        }
        return queue.validated(c, manifestSha, extracted.files(), extracted.bytes())
                ? Result.VALIDATED : Result.FENCED;
    }

    /** A create-only write that found its object already there compares it, by digest. */
    private void createOrCompare(ValidationQueue.Claimed c, String name, Optional<StoredObject> written,
                                 String expected) {
        if (written.isPresent()) {
            return;
        }
        String key = ObjectKeys.bundleObject(c.contentId(), c.bundleId(), name);
        Optional<String> stored = store.head(bucket, key).flatMap(StoredObject::sha256);
        if (stored.isEmpty() || !stored.get().equals(expected)) {
            throw new ObjectStoreException("a different " + name + " is already stored for bundle "
                    + c.bundleId() + " (SHA-256 " + stored.orElse("unrecorded") + ", this run's is "
                    + expected + ")");
        }
    }

    private Result storage(ValidationQueue.Claimed c, String error) {
        log.warn("validating bundle {}: storage failure, will retry: {}", c.bundleId(), error);
        return queue.storageFailed(c, error) ? Result.STORAGE_FAILED : Result.FENCED;
    }

    private static void deleteQuietly(ValidationQueue.Claimed c, BundleExtractor.Extracted extracted) {
        try (ExtractionRoot root = extracted.root()) {
            root.deleteTree();
        } catch (IOException | RuntimeException e) {
            // The workspace is scratch: a tree that could not be deleted is the operator's to
            // see there, and changes nothing about the verdict already recorded.
            log.warn("could not delete the extracted tree of bundle {} under the validation workspace: {}",
                    c.bundleId(), e.toString());
        }
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    /** A read from the store failed: the store's problem, told apart from the workspace's. */
    static final class StoreReadFailed extends IOException {
        StoreReadFailed(IOException cause) {
            super(cause.getMessage(), cause);
        }
    }

    /** Tags every failure reading the stored archive as the store's. */
    private static final class StoreReads extends java.io.FilterInputStream {
        StoreReads(InputStream in) {
            super(in);
        }

        @Override
        public int read() throws IOException {
            try {
                return in.read();
            } catch (IOException e) {
                throw new StoreReadFailed(e);
            } catch (RuntimeException e) {
                throw new StoreReadFailed(new IOException(e.toString(), e));
            }
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            try {
                return in.read(b, off, len);
            } catch (IOException e) {
                throw new StoreReadFailed(e);
            } catch (RuntimeException e) {
                throw new StoreReadFailed(new IOException(e.toString(), e));
            }
        }
    }
}
