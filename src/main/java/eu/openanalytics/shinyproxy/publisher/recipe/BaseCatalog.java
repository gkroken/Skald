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
package eu.openanalytics.shinyproxy.publisher.recipe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.openanalytics.shinyproxy.publisher.recipe.RecipeGenerator.Base;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The trusted bases a build may start from, each by the digest the OPERATOR built and pushed
 * (4dd3f76 review N1: "a build can be FROM it by digest rather than by tag").
 *
 * <p>Two documents, because they have two owners. {@code images/catalog.json} is reviewed and
 * shipped: what each base is (type, language, exact version, architecture, recipe revision,
 * the unprivileged user), FROM which upstream digest, and what it contains. The published
 * bases file is the deployment's: built bases are not byte-reproducible (timestamps, package
 * state), so their digests exist only once an operator has built and pushed them, with
 * {@code dev/publish-bases.py}. It names the catalog it was built from by SHA-256, so a
 * catalog that changed since is refused rather than mixed with old images.
 *
 * <p>Published bases file, strict (unknown keys refused):
 * <pre>
 * {"layout_version": 1,
 *  "catalog_sha256": "&lt;64 hex: sha256 of the catalog.json bytes it was built from&gt;",
 *  "registry": "&lt;host[:port] as the build worker reaches it&gt;",
 *  "bases": {"&lt;catalog id&gt;": "&lt;registry&gt;/skald/base/&lt;id&gt;@sha256:&lt;64 hex&gt;"}}
 * </pre>
 * Each reference must be exactly that repository for that id, so a published file cannot
 * point one base's id at another image. A catalog base that is not published cannot be
 * resolved; a published id that the catalog does not have is refused.
 */
public final class BaseCatalog {

    /** The shipped catalog (pom.xml packages images/catalog.json here). */
    public static final String CATALOG_RESOURCE = "skald/images/catalog.json";

    /** One catalog entry, as far as a build needs it. */
    public record Entry(String id, String type, String language, String version, String architecture,
                        int recipeRevision, String uid, String gid, Map<String, String> packageManager,
                        String osCodename, String rPlatform, Map<String, String> rRecommended) {

        public Entry {
            packageManager = Map.copyOf(packageManager);
            rRecommended = Map.copyOf(rRecommended);
        }

        /** An entry without R's facts (a base whose recipe needs none, or before T7b). */
        public Entry(String id, String type, String language, String version, String architecture,
                     int recipeRevision, String uid, String gid, Map<String, String> packageManager,
                     String osCodename) {
            this(id, type, language, version, architecture, recipeRevision, uid, gid, packageManager, osCodename,
                    null, Map.of());
        }

        /**
         * forge's platform key for an R base, {@code <codename>-<arch>/<R minor>} (T7b): the
         * tree its shared binaries live in. Empty for a base that is not R or lacks a fact.
         */
        public java.util.Optional<String> platformKey() {
            String arch = ARCHES.get(architecture);
            if (!"r".equals(language) || osCodename == null || arch == null) {
                return java.util.Optional.empty();
            }
            var m = R_VERSION.matcher(version);
            return m.matches() ? java.util.Optional.of(osCodename + "-" + arch + "/" + m.group(1))
                    : java.util.Optional.empty();
        }

        /** An entry with no OS codename (a base whose recipe needs none). */
        public Entry(String id, String type, String language, String version, String architecture,
                     int recipeRevision, String uid, String gid, Map<String, String> packageManager) {
            this(id, type, language, version, architecture, recipeRevision, uid, gid, packageManager, null);
        }
    }

    /** A resolved base: its catalog entry and the digest-pinned base a recipe is written on. */
    public record Resolved(Entry entry, Base base) { }

    private static final ObjectMapper JSON = new ObjectMapper();
    /** host[:port], lower case: the same name the build worker's gateway allows. */
    private static final Pattern REGISTRY = Pattern.compile(
            "[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)*(?::[0-9]{1,5})?");
    private static final Pattern HEX64 = Pattern.compile("[0-9a-f]{64}");
    /**
     * uid:gid, both non-zero with no leading zero: "00" is root to Docker (1ffdb20-F1).
     * RecipeGenerator.Base checks the same again, as the last gate before the Dockerfile.
     */
    private static final Pattern USER = Pattern.compile("([1-9][0-9]{0,8}):([1-9][0-9]{0,8})");
    /** A distribution release's codename, as /etc/os-release spells it (noble, trixie). */
    private static final Pattern CODENAME = Pattern.compile("[a-z][a-z0-9]{1,31}");
    /** The catalog's architectures as forge's platform keys and R's platform strings name them. */
    private static final Map<String, String> ARCHES = Map.of("amd64", "x86_64", "arm64", "aarch64");
    private static final Pattern R_VERSION = Pattern.compile("([0-9]+\\.[0-9]+)\\.[0-9]+");
    private static final Pattern R_PACKAGE = Pattern.compile("[A-Za-z][A-Za-z0-9.]*[A-Za-z0-9]");
    private static final Pattern R_PACKAGE_VERSION = Pattern.compile("[0-9]+([.-][0-9]+)+");

    private final List<Entry> entries;
    private final Map<String, String> published;
    private final String registry;
    private final Map<String, String> imageIds;

    private BaseCatalog(List<Entry> entries, Map<String, String> published, String registry,
                        Map<String, String> imageIds) {
        this.entries = List.copyOf(entries);
        this.published = Map.copyOf(published);
        this.registry = registry;
        this.imageIds = Map.copyOf(imageIds);
    }

    /** The registry the published bases name, host[:port] as the build worker reaches it. */
    public String registry() {
        return registry;
    }

    /** The shipped catalog with the deployment's published bases. */
    public static BaseCatalog load(InputStream publishedBases) throws IOException {
        try (InputStream in = BaseCatalog.class.getClassLoader().getResourceAsStream(CATALOG_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(CATALOG_RESOURCE + " is not on the classpath");
            }
            return load(in.readAllBytes(), publishedBases.readAllBytes());
        }
    }

    static BaseCatalog load(byte[] catalogBytes, byte[] publishedBytes) throws IOException {
        List<Entry> entries = new ArrayList<>();
        JsonNode catalog = JSON.readTree(catalogBytes);
        if (catalog.path("layout_version").asInt() != 1 || !catalog.path("bases").isArray()) {
            throw new IllegalArgumentException("catalog: not layout_version 1 with a bases list");
        }
        for (JsonNode b : catalog.path("bases")) {
            var user = USER.matcher(b.path("user").asText(""));
            if (!user.matches()) {
                throw new IllegalArgumentException("catalog: base " + b.path("id").asText() + " has no non-root user");
            }
            Map<String, String> packageManager = new java.util.TreeMap<>();
            b.path("package_manager").fields().forEachRemaining(f -> {
                // Strict like the rest of load(): a non-text version would feed the cache key
                // as "" (c939203 review N2).
                if (!f.getValue().isTextual() || f.getValue().asText().isEmpty()) {
                    throw new IllegalArgumentException("catalog: base " + b.path("id").asText() + "'s "
                            + f.getKey() + " version is not text");
                }
                packageManager.put(f.getKey(), f.getValue().asText());
            });
            // Required: it names forge's Linux binary tree for the base (T7b), and a URL is
            // built from it, so it is checked here, not trusted.
            String codename = b.path("os_codename").asText("");
            if (!CODENAME.matcher(codename).matches()) {
                throw new IllegalArgumentException("catalog: base " + b.path("id").asText()
                        + " has no valid os_codename: '" + codename + "'");
            }
            // R's facts (T7b): required for an R base, refused on any other. r_platform must be
            // the architecture's R.version$platform; r_recommended is R's system library of
            // recommended packages, by exact version (a dependency the base already carries at
            // the locked version is not queued for a shared binary).
            String rPlatform = null;
            Map<String, String> recommended = new java.util.TreeMap<>();
            if ("r".equals(b.path("language").asText())) {
                String arch = ARCHES.get(b.path("architecture").asText());
                rPlatform = b.path("r_platform").asText("");
                if (arch == null || !rPlatform.equals(arch + "-pc-linux-gnu")) {
                    throw new IllegalArgumentException("catalog: R base " + b.path("id").asText()
                            + " has no r_platform matching its architecture: '" + rPlatform + "'");
                }
                if (!b.path("r_recommended").isObject() || b.path("r_recommended").isEmpty()) {
                    throw new IllegalArgumentException("catalog: R base " + b.path("id").asText()
                            + " has no r_recommended");
                }
                b.path("r_recommended").fields().forEachRemaining(f -> {
                    if (!R_PACKAGE.matcher(f.getKey()).matches() || !f.getValue().isTextual()
                            || !R_PACKAGE_VERSION.matcher(f.getValue().asText()).matches()) {
                        throw new IllegalArgumentException("catalog: R base " + b.path("id").asText()
                                + " has a malformed r_recommended entry: " + f.getKey());
                    }
                    recommended.put(f.getKey(), f.getValue().asText());
                });
            } else if (b.has("r_platform") || b.has("r_recommended")) {
                throw new IllegalArgumentException("catalog: base " + b.path("id").asText()
                        + " is not R but records R's facts");
            }
            entries.add(new Entry(text(b, "id"), text(b, "type"), text(b, "language"), text(b, "version"),
                    text(b, "architecture"), b.path("recipe_revision").asInt(0), user.group(1), user.group(2),
                    packageManager, codename, rPlatform, recommended));
        }
        Set<String> ids = new java.util.HashSet<>();
        for (Entry e : entries) {
            if (!ids.add(e.id())) {
                throw new IllegalArgumentException("catalog: id " + e.id() + " twice");
            }
        }

        JsonNode p = JSON.readTree(publishedBytes);
        if (p == null || !p.isObject()) {
            throw new IllegalArgumentException("published bases: not a JSON object");
        }
        for (Iterator<String> it = p.fieldNames(); it.hasNext(); ) {
            String key = it.next();
            if (!Set.of("layout_version", "catalog_sha256", "registry", "bases", "image_ids").contains(key)) {
                throw new IllegalArgumentException("published bases: unknown key " + key);
            }
        }
        if (!p.path("layout_version").isInt() || p.path("layout_version").asInt() != 1) {
            throw new IllegalArgumentException("published bases: layout_version must be 1");
        }
        String want = sha256(catalogBytes);
        String got = p.path("catalog_sha256").asText("");
        if (!HEX64.matcher(got).matches() || !got.equals(want)) {
            throw new IllegalArgumentException("published bases were built from another catalog (catalog_sha256 "
                    + got + ", this catalog is " + want + "): publish the bases again");
        }
        String registry = p.path("registry").asText("");
        if (registry.length() > 253 || !REGISTRY.matcher(registry).matches()) {
            throw new IllegalArgumentException("published bases: not a registry host[:port]: " + registry);
        }
        if (!p.path("bases").isObject()) {
            throw new IllegalArgumentException("published bases: bases must be an object");
        }
        Map<String, String> published = new LinkedHashMap<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = p.path("bases").fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> f = it.next();
            if (!ids.contains(f.getKey())) {
                throw new IllegalArgumentException("published bases: " + f.getKey() + " is not in the catalog");
            }
            String ref = f.getValue().isTextual() ? f.getValue().asText() : "";
            String prefix = registry + "/skald/base/" + f.getKey() + "@sha256:";
            if (!ref.startsWith(prefix) || !HEX64.matcher(ref.substring(prefix.length())).matches()) {
                throw new IllegalArgumentException("published bases: " + f.getKey() + " must be " + prefix
                        + "<64 hex>, not " + ref);
            }
            published.put(f.getKey(), ref);
        }
        // Each published base's image ID (its config digest), which forge's platform
        // registration names (T7b). Optional: a deployment without shared binaries needs none.
        Map<String, String> imageIds = new LinkedHashMap<>();
        if (p.has("image_ids")) {
            if (!p.path("image_ids").isObject()) {
                throw new IllegalArgumentException("published bases: image_ids must be an object");
            }
            for (Iterator<Map.Entry<String, JsonNode>> it = p.path("image_ids").fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> f = it.next();
                if (!published.containsKey(f.getKey())) {
                    throw new IllegalArgumentException("published bases: an image id for " + f.getKey()
                            + ", which is not published");
                }
                String id = f.getValue().isTextual() ? f.getValue().asText() : "";
                if (!id.startsWith("sha256:") || !HEX64.matcher(id.substring(7)).matches()) {
                    throw new IllegalArgumentException("published bases: " + f.getKey()
                            + "'s image id must be sha256:<64 hex>, not " + id);
                }
                imageIds.put(f.getKey(), id);
            }
        }
        return new BaseCatalog(entries, published, registry, imageIds);
    }

    /** A published base's image ID (sha256:...), if the published bases record it. */
    public Optional<String> imageId(String id) {
        return Optional.ofNullable(imageIds.get(id));
    }

    /**
     * The base for a manifest's type, language and exact version on an architecture: the
     * catalog's newest recipe revision of it, by its published digest. Empty if the catalog
     * has no such base; an IllegalStateException if it has one that was not published.
     */
    public Optional<Base> resolve(String type, String language, String version, String architecture) {
        return resolveEntry(type, language, version, architecture).map(Resolved::base);
    }

    /** As {@link #resolve}, with the catalog entry the base came from. */
    public Optional<Resolved> resolveEntry(String type, String language, String version, String architecture) {
        Optional<Entry> entry = entries.stream()
                .filter(e -> e.type().equals(type) && e.language().equals(language)
                        && e.version().equals(version) && e.architecture().equals(architecture))
                .max(Comparator.comparingInt(Entry::recipeRevision));
        if (entry.isEmpty()) {
            return Optional.empty();
        }
        String ref = published.get(entry.get().id());
        if (ref == null) {
            throw new IllegalStateException("base " + entry.get().id() + " is in the catalog but was not published");
        }
        Entry e = entry.get();
        return Optional.of(new Resolved(e, new Base(ref, e.language(), e.version(), e.uid(), e.gid(),
                e.osCodename())));
    }

    /** The catalog's entries, in order. */
    public List<Entry> entries() {
        return entries;
    }

    private static String text(JsonNode node, String field) {
        String v = node.path(field).asText("");
        if (v.isEmpty()) {
            throw new IllegalArgumentException("catalog: a base has no " + field);
        }
        return v;
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
