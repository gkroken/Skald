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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.openanalytics.shinyproxy.publisher.build.BuildDriver.Claimed;
import eu.openanalytics.shinyproxy.publisher.build.Lease;
import eu.openanalytics.shinyproxy.publisher.bundle.BundleRejection;
import eu.openanalytics.shinyproxy.publisher.bundle.ExtractionLimits;
import eu.openanalytics.shinyproxy.publisher.bundle.ManifestValidator;
import eu.openanalytics.shinyproxy.publisher.recipe.BaseCatalog;
import eu.openanalytics.shinyproxy.publisher.recipe.LockRejection;
import eu.openanalytics.shinyproxy.publisher.recipe.RecipeGenerator.Mirrors;
import eu.openanalytics.shinyproxy.publisher.storage.BundleReceipt;
import eu.openanalytics.shinyproxy.publisher.storage.BundleWriter;
import eu.openanalytics.shinyproxy.publisher.storage.TestObjectStore;
import eu.openanalytics.shinyproxy.publisher.worker.BuildKitDriver.Prepared;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A stored bundle to a build context, against real MinIO: what is produced, and what is refused. */
public class BundleContextSourceTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PY_ID = "python-shiny-3.13.16-amd64-r1";
    private static final String PY_REF = "skald-registry:5000/skald/base/" + PY_ID + "@sha256:" + "b".repeat(64);
    private static final String R_ID = "r-shiny-4.6.1-amd64-r1";
    private static final String R_REF = "skald-registry:5000/skald/base/" + R_ID + "@sha256:" + "c".repeat(64);
    private static final Mirrors MIRRORS = new Mirrors(URI.create("http://forge:8080/repository/cran-public/"),
            URI.create("http://forge:8080/repository/pypi-public/simple/"));
    private static TestObjectStore objects;
    private static BundleWriter bundles;
    private static BaseCatalog catalog;

    @BeforeAll
    public static void start() throws Exception {
        objects = new TestObjectStore("skald-bcs-bundles");
        bundles = new BundleWriter(objects.store, objects.bucket);
        byte[] catalogBytes;
        try (InputStream in = BaseCatalog.class.getClassLoader().getResourceAsStream(BaseCatalog.CATALOG_RESOURCE)) {
            catalogBytes = in.readAllBytes();
        }
        ObjectNode published = JSON.createObjectNode().put("layout_version", 1)
                .put("catalog_sha256", sha256(catalogBytes)).put("registry", "skald-registry:5000");
        published.putObject("bases").put(PY_ID, PY_REF).put(R_ID, R_REF);
        catalog = BaseCatalog.load(new ByteArrayInputStream(published.toString().getBytes(StandardCharsets.UTF_8)));
    }

    @AfterAll
    public static void stop() {
        if (objects != null) {
            objects.close();
        }
    }

    private static String sha256(byte[] b) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
    }

    private static Map<String, byte[]> pythonApp() throws Exception {
        Map<String, byte[]> payload = new LinkedHashMap<>();
        payload.put("app.py", Files.readAllBytes(Path.of("dev/fixtures/apps/python-shiny/app.py")));
        payload.put("requirements.lock", Files.readAllBytes(Path.of("dev/fixtures/apps/python-shiny/requirements.lock")));
        return payload;
    }

    private static byte[] manifest(Map<String, byte[]> payload, String version) throws Exception {
        ObjectNode doc = JSON.createObjectNode();
        doc.put("schema_version", 1).put("type", "shiny");
        doc.putObject("runtime").put("language", "python").put("version", version);
        doc.put("entrypoint", "app.py");
        doc.putObject("dependencies").put("format", "pip-hashed").put("path", "requirements.lock");
        ArrayNode files = doc.putArray("files");
        for (Map.Entry<String, byte[]> f : payload.entrySet()) {
            files.addObject().put("path", f.getKey()).put("size", f.getValue().length).put("sha256", sha256(f.getValue()));
        }
        return JSON.writeValueAsBytes(doc);
    }

    /** manifest.json first, then every payload file under app/, as one gzip member. */
    private static byte[] archive(byte[] manifest, Map<String, byte[]> payload) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(out); TarArchiveOutputStream tar = new TarArchiveOutputStream(gz)) {
            Map<String, byte[]> all = new LinkedHashMap<>();
            all.put("manifest.json", manifest);
            payload.forEach((k, v) -> all.put("app/" + k, v));
            for (Map.Entry<String, byte[]> f : all.entrySet()) {
                TarArchiveEntry e = new TarArchiveEntry(f.getKey());
                e.setSize(f.getValue().length);
                e.setMode(0644);
                tar.putArchiveEntry(e);
                tar.write(f.getValue());
                tar.closeArchiveEntry();
            }
        }
        return out.toByteArray();
    }

    /** Stores the bundle as the upload path does; {@code receiptSha} overrides the receipt's archive digest. */
    private static Claimed store(byte[] manifest, byte[] archive, String receiptSha) throws Exception {
        UUID content = UUID.randomUUID();
        UUID bundle = UUID.randomUUID();
        bundles.writeArchive(content, bundle, new ByteArrayInputStream(archive), archive.length);
        bundles.writeManifest(content, bundle, manifest);
        byte[] inventory = "{}".getBytes(StandardCharsets.UTF_8);
        bundles.writeInventory(content, bundle, inventory);
        bundles.commit(new BundleReceipt(1, 1, content, bundle, receiptSha == null ? sha256(archive) : receiptSha,
                archive.length, sha256(manifest), sha256(inventory))).orElseThrow();
        UUID build = UUID.randomUUID();
        return new Claimed(build, content, bundle, Map.of(), new Lease(build, 1, "test"));
    }

    private static BundleContextSource source(Path workspace) {
        return new BundleContextSource(bundles, objects.store, objects.bucket, ExtractionLimits.defaults(), workspace,
                catalog, MIRRORS, "amd64");
    }

    private static List<Path> left(Path workspace) throws Exception {
        try (Stream<Path> s = Files.list(workspace)) {
            return s.toList();
        }
    }

    @Test
    public void aStoredBundleBecomesItsPayloadAndTheServersRecipe(@TempDir Path workspace) throws Exception {
        Map<String, byte[]> app = pythonApp();
        byte[] manifest = manifest(app, "3.13.16");
        Claimed c = store(manifest, archive(manifest, app), null);
        Prepared p = source(workspace).prepare(c);
        try {
            assertEquals("content/" + c.contentId(), p.repository());
            assertTrue(Files.isRegularFile(p.payload().resolve("app.py")));
            assertTrue(java.util.Arrays.equals(app.get("app.py"), Files.readAllBytes(p.payload().resolve("app.py"))));
            assertTrue(p.recipe().dockerfile().contains("FROM " + PY_REF + "\n"), p.recipe().dockerfile());
            // The recipe the generator writes for this base, manifest and lock: nothing else.
            var expected = eu.openanalytics.shinyproxy.publisher.recipe.RecipeGenerator.generate(
                    catalog.resolve("shiny", "python", "3.13.16", "amd64").orElseThrow(), MIRRORS,
                    ManifestValidator.validate(manifest, ExtractionLimits.defaults()), app.get("requirements.lock"));
            assertEquals(expected.dockerfile(), p.recipe().dockerfile());
            assertEquals(expected.files().keySet(), p.recipe().files().keySet());
            for (String k : expected.files().keySet()) {
                assertTrue(java.util.Arrays.equals(expected.files().get(k), p.recipe().files().get(k)), k);
            }
            // Decision 7: the dependency cache key, from the catalog entry, the base, the
            // mirrors and the rendered lock.
            var resolved = catalog.resolveEntry("shiny", "python", "3.13.16", "amd64").orElseThrow();
            assertEquals(eu.openanalytics.shinyproxy.publisher.recipe.DependencyCacheKey.of(resolved.entry(),
                    resolved.base(), MIRRORS, expected), p.cacheKey());
            // The provenance inputs: the receipt's digests, the manifest as validated, the
            // base by digest, the generated recipe and the lock by digest, the cache key.
            var receipt = bundles.readReceipt(c.contentId(), c.bundleId()).orElseThrow();
            JsonNode in = JSON.valueToTree(p.inputs());
            assertEquals(receipt.archiveSha256(), in.path("bundle").path("archive_sha256").asText());
            assertEquals(receipt.manifestSha256(), in.path("bundle").path("manifest_sha256").asText());
            assertEquals(receipt.inventorySha256(), in.path("bundle").path("inventory_sha256").asText());
            assertEquals(receipt.archiveBytes(), in.path("bundle").path("archive_bytes").asLong());
            assertEquals("3.13.16", in.path("manifest").path("runtime_version").asText());
            assertEquals(PY_REF, in.path("base").path("reference").asText());
            assertEquals(resolved.entry().id(), in.path("base").path("catalog_id").asText());
            assertEquals(resolved.entry().recipeRevision(), in.path("base").path("recipe_revision").asInt());
            assertEquals(sha256(expected.dockerfile().getBytes(StandardCharsets.UTF_8)),
                    in.path("recipe").path("dockerfile_sha256").asText());
            assertEquals(expected.files().size(), in.path("recipe").path("files_sha256").size());
            for (var f : expected.files().entrySet()) {
                assertEquals(sha256(f.getValue()), in.path("recipe").path("files_sha256").path(f.getKey()).asText(),
                        f.getKey());
            }
            assertEquals(sha256(app.get("requirements.lock")),
                    in.path("dependencies").path("uploaded_lock_sha256").asText());
            assertEquals(p.cacheKey(), in.path("dependencies").path("cache_key").asText());
            assertEquals(MIRRORS.cran().toString(), in.path("dependencies").path("mirrors").path("cran").asText());
            assertEquals(MIRRORS.pypiIndex().toString(),
                    in.path("dependencies").path("mirrors").path("pypi_index").asText());
        } finally {
            p.release().run();
        }
        assertFalse(Files.exists(p.payload().resolve("app.py")), "release deleted the payload");
    }

    @Test
    public void aSourceOnlyEditKeepsTheCacheKeyAndOtherMirrorsMoveIt(@TempDir Path workspace) throws Exception {
        Map<String, byte[]> app = pythonApp();
        byte[] manifest = manifest(app, "3.13.16");
        Map<String, byte[]> edited = new LinkedHashMap<>(app);
        edited.put("app.py", "from shiny import App, ui\napp = App(ui.page_fluid('edited'), None)\n"
                .getBytes(StandardCharsets.UTF_8));
        byte[] editedManifest = manifest(edited, "3.13.16");
        Prepared first = source(workspace).prepare(store(manifest, archive(manifest, app), null));
        Prepared second = source(workspace).prepare(store(editedManifest, archive(editedManifest, edited), null));
        Prepared otherMirrors = new BundleContextSource(bundles, objects.store, objects.bucket, ExtractionLimits.defaults(),
                workspace, catalog, new Mirrors(URI.create("http://elsewhere:8080/cran/"),
                URI.create("http://elsewhere:8080/pypi/simple/")), "amd64")
                .prepare(store(manifest, archive(manifest, app), null));
        try {
            assertEquals(first.cacheKey(), second.cacheKey(), "an app-source edit is a cache hit");
            assertFalse(first.cacheKey().equals(otherMirrors.cacheKey()), "another mirror policy is a miss");
        } finally {
            first.release().run();
            second.release().run();
            otherMirrors.release().run();
        }
    }

    @Test
    public void everyRefusalLeavesTheWorkspaceEmpty(@TempDir Path workspace) throws Exception {
        Map<String, byte[]> app = pythonApp();
        byte[] manifest = manifest(app, "3.13.16");
        byte[] archive = archive(manifest, app);
        BundleContextSource s = source(workspace);

        UUID content = UUID.randomUUID();
        Claimed none = new Claimed(UUID.randomUUID(), content, UUID.randomUUID(), Map.of(),
                new Lease(UUID.randomUUID(), 1, "test"));
        Exception e = assertThrows(IllegalStateException.class, () -> s.prepare(none));
        assertTrue(e.getMessage().contains("no receipt"), e.getMessage());

        // The stored archive is not the one its receipt names.
        Claimed tampered = store(manifest, archive, "0".repeat(64));
        e = assertThrows(IllegalStateException.class, () -> s.prepare(tampered));
        assertTrue(e.getMessage().contains("SHA-256"), e.getMessage());
        assertEquals(List.of(), left(workspace));

        // A runtime the catalog has no base for.
        byte[] old = manifest(app, "3.12");
        e = assertThrows(IllegalStateException.class, () -> s.prepare(store(old, archive(old, app), null)));
        assertTrue(e.getMessage().contains("no trusted base"), e.getMessage());
        assertEquals(List.of(), left(workspace));

        // A lock the policy refuses (no hashes).
        Map<String, byte[]> unhashed = new LinkedHashMap<>(app);
        unhashed.put("requirements.lock", "shiny==1.0.0\n".getBytes(StandardCharsets.UTF_8));
        byte[] um = manifest(unhashed, "3.13.16");
        assertThrows(LockRejection.class, () -> s.prepare(store(um, archive(um, unhashed), null)));
        assertEquals(List.of(), left(workspace));

        // An archive the extractor refuses: a file whose bytes are not what the manifest says.
        Map<String, byte[]> lying = new LinkedHashMap<>(app);
        lying.put("app.py", "print('not what the manifest hashed')\n".getBytes(StandardCharsets.UTF_8));
        byte[] lm = manifest(app, "3.13.16");
        assertThrows(BundleRejection.class, () -> s.prepare(store(lm, archive(lm, lying), null)));
        assertEquals(List.of(), left(workspace));
    }

    // ------------------------------------------------------------------ T7b: the binary hook

    private static final Mirrors BINARIES = new Mirrors(MIRRORS.cran(), MIRRORS.pypiIndex(), true);

    private static Map<String, byte[]> rApp() throws Exception {
        Map<String, byte[]> payload = new LinkedHashMap<>();
        payload.put("app.R", Files.readAllBytes(Path.of("dev/fixtures/apps/r-shiny/app.R")));
        payload.put("renv.lock", Files.readAllBytes(Path.of("dev/fixtures/apps/r-shiny/renv.lock")));
        return payload;
    }

    private static byte[] rManifest(Map<String, byte[]> payload) throws Exception {
        ObjectNode doc = JSON.createObjectNode();
        doc.put("schema_version", 1).put("type", "shiny");
        doc.putObject("runtime").put("language", "r").put("version", "4.6.1");
        doc.put("entrypoint", ".");
        doc.putObject("dependencies").put("format", "renv").put("path", "renv.lock");
        ArrayNode files = doc.putArray("files");
        for (Map.Entry<String, byte[]> f : payload.entrySet()) {
            files.addObject().put("path", f.getKey()).put("size", f.getValue().length).put("sha256", sha256(f.getValue()));
        }
        return JSON.writeValueAsBytes(doc);
    }

    /** A hook that answers {@code on} and records what it was asked. */
    private static final class Hook implements eu.openanalytics.shinyproxy.publisher.binaries.BinaryHook {
        final boolean on;
        final List<String> asked = new java.util.ArrayList<>();
        final List<Map<String, String>> built = new java.util.ArrayList<>();
        UUID builtFor;

        Hook(boolean on) {
            this.on = on;
        }

        @Override
        public eu.openanalytics.shinyproxy.publisher.binaries.BinaryGate.Decision binariesFor(BaseCatalog.Resolved base) {
            asked.add(base.entry().id());
            return new eu.openanalytics.shinyproxy.publisher.binaries.BinaryGate.Decision(on, on ? "registered" : "not ours");
        }

        @Override
        public void built(UUID buildId, BaseCatalog.Resolved base, Map<String, String> lock) {
            builtFor = buildId;
            built.add(lock);
        }
    }

    private static BundleContextSource source(Path workspace, Mirrors mirrors, Hook hook) {
        return new BundleContextSource(bundles, objects.store, objects.bucket, ExtractionLimits.defaults(), workspace,
                catalog, mirrors, "amd64").withBinaryHook(hook);
    }

    @Test
    public void anRBuildAsksForBinariesOnlyWhenTheGateSaysThisBaseIsRegistered(@TempDir Path workspace) throws Exception {
        Map<String, byte[]> app = rApp();
        byte[] manifest = rManifest(app);
        Hook off = new Hook(false);
        Prepared p = source(workspace, BINARIES, off).prepare(store(manifest, archive(manifest, app), null));
        try {
            assertEquals(List.of(R_ID), off.asked);
            assertFalse(p.recipe().dockerfile().contains("__linux__"), "the plain mirror: " + p.recipe().dockerfile());
            JsonNode deps = JSON.valueToTree(p.inputs()).path("dependencies");
            assertFalse(deps.path("mirrors").path("cran_linux_binaries").asBoolean(true));
            assertEquals("not ours", deps.path("cran_linux_binaries_reason").asText());
            p.onBuilt().run();
            assertTrue(off.built.isEmpty(), "nothing queued for a base without binaries");
        } finally {
            p.release().run();
        }
        Hook on = new Hook(true);
        Claimed c = store(manifest, archive(manifest, app), null);
        Prepared q = source(workspace, BINARIES, on).prepare(c);
        try {
            assertTrue(q.recipe().dockerfile().contains("__linux__/noble/latest"), q.recipe().dockerfile());
            assertTrue(JSON.valueToTree(q.inputs()).path("dependencies").path("mirrors").path("cran_linux_binaries")
                    .asBoolean(false));
            assertTrue(on.built.isEmpty(), "queued only once the build succeeds");
            q.onBuilt().run();
            assertEquals(c.buildId(), on.builtFor);
            assertEquals(46, on.built.get(0).size(), "the rendered lock's packages and versions");
            assertTrue(on.built.get(0).containsKey("shiny"));
        } finally {
            q.release().run();
        }
    }

    @Test
    public void theGateIsNotAskedWhenTheSwitchIsOffOrForPython(@TempDir Path workspace) throws Exception {
        Map<String, byte[]> app = rApp();
        byte[] manifest = rManifest(app);
        Hook hook = new Hook(true);
        Prepared p = source(workspace, MIRRORS, hook).prepare(store(manifest, archive(manifest, app), null));
        p.onBuilt().run();
        p.release().run();
        Map<String, byte[]> py = pythonApp();
        byte[] pyManifest = manifest(py, "3.13.16");
        Prepared q = source(workspace, BINARIES, hook).prepare(store(pyManifest, archive(pyManifest, py), null));
        q.onBuilt().run();
        q.release().run();
        assertTrue(hook.asked.isEmpty(), "switch off, or not R: the gate is not consulted");
        assertTrue(hook.built.isEmpty());
    }
}
