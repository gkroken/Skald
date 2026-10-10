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
package eu.openanalytics.shinyproxy.publisher.worker;

import eu.openanalytics.shinyproxy.publisher.build.BuildDriver.Claimed;
import eu.openanalytics.shinyproxy.publisher.bundle.BundleExtractor;
import eu.openanalytics.shinyproxy.publisher.bundle.ExtractionLimits;
import eu.openanalytics.shinyproxy.publisher.bundle.ManifestValidator.Manifest;
import eu.openanalytics.shinyproxy.publisher.recipe.BaseCatalog;
import eu.openanalytics.shinyproxy.publisher.recipe.DependencyCacheKey;
import eu.openanalytics.shinyproxy.publisher.recipe.RecipeGenerator;
import eu.openanalytics.shinyproxy.publisher.recipe.RecipeGenerator.Base;
import eu.openanalytics.shinyproxy.publisher.recipe.RecipeGenerator.Mirrors;
import eu.openanalytics.shinyproxy.publisher.recipe.RecipeGenerator.Recipe;
import eu.openanalytics.shinyproxy.publisher.storage.BundleReceipt;
import eu.openanalytics.shinyproxy.publisher.storage.BundleWriter;
import eu.openanalytics.shinyproxy.publisher.storage.ObjectKeys;
import eu.openanalytics.shinyproxy.publisher.storage.ObjectStore;
import eu.openanalytics.shinyproxy.publisher.worker.BuildKitDriver.ContextSource;
import eu.openanalytics.shinyproxy.publisher.worker.BuildKitDriver.Prepared;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * A claimed attempt's build context, from the bundle store: the validated payload on disk
 * and the server-written recipe for it.
 *
 * <p><b>The bundle is extracted again, under the same limits, at build time.</b> It was
 * validated at upload, and the store keeps it immutable behind its receipt; extracting it
 * again re-checks every rule against the bytes actually read, so nothing reaches the build
 * worker on the strength of an earlier verdict alone. The archive is read through a
 * SHA-256 that must equal the receipt's. The extractor reads to the end of its input (it
 * refuses any bytes after the gzip member), so the digest covers every stored byte; were it
 * ever to stop early, the digest of a prefix would not match and the bundle is refused.
 *
 * <p><b>The recipe is the server's.</b> The base is the catalog's newest revision for the
 * manifest's type, language, exact version and this architecture, by the digest the
 * operator published (BaseCatalog). The lock file is read from the extracted payload at
 * the manifest's dependency path and rendered by its language's policy
 * (RecipeGenerator.generate); the build installs that rendering, never the upload.
 *
 * <p>Any refusal throws, and the driver reports it as {@code FAILED:CONTEXT} with its
 * message. The extracted tree is deleted on every path that does not hand it over, and by
 * {@link Prepared#release()} on the one that does.
 */
public final class BundleContextSource implements ContextSource {

    private final BundleWriter bundles;
    private final ObjectStore store;
    private final String bucket;
    private final ExtractionLimits limits;
    private final Path workspace;
    private final BaseCatalog bases;
    private final Mirrors mirrors;
    private final String architecture;

    public BundleContextSource(BundleWriter bundles, ObjectStore store, String bucket, ExtractionLimits limits,
                               Path workspace, BaseCatalog bases, Mirrors mirrors, String architecture) {
        this.bundles = bundles;
        this.store = store;
        this.bucket = bucket;
        this.limits = limits;
        this.workspace = workspace;
        this.bases = bases;
        this.mirrors = mirrors;
        this.architecture = architecture;
    }

    @Override
    public Prepared prepare(Claimed build) throws Exception {
        BundleReceipt receipt = bundles.readReceipt(build.contentId(), build.bundleId())
                .orElseThrow(() -> new IllegalStateException("bundle " + build.bundleId() + " has no receipt"));
        MessageDigest sha = sha256();
        BundleExtractor.Extracted extracted;
        try (InputStream raw = store.open(bucket, ObjectKeys.bundleObject(build.contentId(), build.bundleId(),
                ObjectKeys.BUNDLE_ARCHIVE));
             DigestInputStream in = new DigestInputStream(raw, sha)) {
            extracted = BundleExtractor.extract(in, receipt.archiveBytes(), workspace, limits);
        }
        try {
            String actual = HexFormat.of().formatHex(sha.digest());
            if (!actual.equals(receipt.archiveSha256())) {
                throw new IllegalStateException("bundle " + build.bundleId() + ": the stored archive's SHA-256 is "
                        + actual + ", its receipt says " + receipt.archiveSha256());
            }
            Manifest manifest = extracted.validated();
            BaseCatalog.Resolved resolved = bases.resolveEntry(manifest.type(), manifest.language(),
                    manifest.runtimeVersion(), architecture)
                    .orElseThrow(() -> new IllegalStateException("no trusted base for " + manifest.type() + " "
                            + manifest.language() + " " + manifest.runtimeVersion() + " on " + architecture));
            Base base = resolved.base();
            Path lock = extracted.root().path().resolve(manifest.dependencyPath());
            if (!Files.isRegularFile(lock, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException("the manifest's lock file " + manifest.dependencyPath()
                        + " is not in the payload");
            }
            byte[] uploadedLock = Files.readAllBytes(lock);
            Recipe recipe = RecipeGenerator.generate(base, mirrors, manifest, uploadedLock);
            String cacheKey = DependencyCacheKey.of(resolved.entry(), base, mirrors, recipe);
            return new Prepared(extracted.root().path(), recipe, "content/" + build.contentId(),
                    () -> deleteQuietly(extracted), cacheKey,
                    inputs(receipt, manifest, resolved, recipe, uploadedLock, cacheKey));
        } catch (Exception e) {
            deleteQuietly(extracted);
            throw e;
        }
    }

    /**
     * The attempt's inputs for its provenance record: everything here is the server's own
     * reading (the receipt, the re-validated manifest, the catalog, the generated recipe),
     * by digest where there is one. The driver adds the worker's side.
     */
    Map<String, Object> inputs(BundleReceipt receipt, Manifest manifest, BaseCatalog.Resolved resolved,
                               Recipe recipe, byte[] uploadedLock, String cacheKey) {
        Map<String, Object> bundle = new LinkedHashMap<>();
        bundle.put("archive_sha256", receipt.archiveSha256());
        bundle.put("archive_bytes", receipt.archiveBytes());
        bundle.put("manifest_sha256", receipt.manifestSha256());
        bundle.put("inventory_sha256", receipt.inventorySha256());
        bundle.put("layout_version", receipt.layoutVersion());
        bundle.put("schema_version", receipt.schemaVersion());
        Map<String, Object> declared = new LinkedHashMap<>();
        declared.put("type", manifest.type());
        declared.put("language", manifest.language());
        declared.put("runtime_version", manifest.runtimeVersion());
        declared.put("entrypoint", manifest.entrypoint());
        declared.put("dependency_format", manifest.dependencyFormat());
        declared.put("dependency_path", manifest.dependencyPath());
        BaseCatalog.Entry entry = resolved.entry();
        Map<String, Object> base = new LinkedHashMap<>();
        base.put("catalog_id", entry.id());
        base.put("reference", resolved.base().reference());
        base.put("language", entry.language());
        base.put("version", entry.version());
        base.put("architecture", entry.architecture());
        base.put("recipe_revision", entry.recipeRevision());
        base.put("uid", entry.uid());
        base.put("gid", entry.gid());
        base.put("package_manager", new TreeMap<>(entry.packageManager()));
        Map<String, Object> files = new TreeMap<>();
        recipe.files().forEach((name, bytes) -> files.put(name, sha256Hex(bytes)));
        Map<String, Object> generated = new LinkedHashMap<>();
        generated.put("dockerfile_sha256", sha256Hex(recipe.dockerfile().getBytes(StandardCharsets.UTF_8)));
        generated.put("files_sha256", files);
        Map<String, Object> dependencies = new LinkedHashMap<>();
        dependencies.put("uploaded_lock_sha256", sha256Hex(uploadedLock));
        dependencies.put("cache_key", cacheKey);
        Map<String, Object> repositories = new LinkedHashMap<>();
        repositories.put("cran", mirrors.cran().toString());
        repositories.put("pypi_index", mirrors.pypiIndex().toString());
        dependencies.put("mirrors", repositories);
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("bundle", bundle);
        inputs.put("manifest", declared);
        inputs.put("base", base);
        inputs.put("recipe", generated);
        inputs.put("dependencies", dependencies);
        return inputs;
    }

    private static String sha256Hex(byte[] bytes) {
        return HexFormat.of().formatHex(sha256().digest(bytes));
    }

    private static void deleteQuietly(BundleExtractor.Extracted extracted) {
        try {
            extracted.root().deleteTree();
        } catch (IOException e) {
            // The workspace is the attempt's scratch; a tree that could not be deleted is
            // the operator's to see in the workspace, and is not a build outcome.
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
