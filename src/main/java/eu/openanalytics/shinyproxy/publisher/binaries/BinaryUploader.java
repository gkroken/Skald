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
package eu.openanalytics.shinyproxy.publisher.binaries;

import eu.openanalytics.shinyproxy.publisher.binaries.BinaryBuildQueue.BuildClaim;
import eu.openanalytics.shinyproxy.publisher.binaries.BinaryBuildQueue.BuiltDependency;
import eu.openanalytics.shinyproxy.publisher.recipe.BaseCatalog;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The upload, outside the sandbox (WORKPLAN-BUNDLES T7b step 2, point 5; step 2d part 2): a
 * binary the sandbox produced and BinaryArchive accepted goes to forge's binary tree for its
 * platform, with the base's image ID, from the trusted side only -- the token is in no
 * container.
 * <ol>
 *   <li>The registration is read again (BinaryGate; c365d95 N1): a base forge no longer
 *       registers for this key gets no upload (a failed attempt, REGISTRATION).</li>
 *   <li>LinkingTo versions are what the trusted side handed in (or the base's library), never
 *       what the sandbox reports.</li>
 *   <li>The upload intent -- digest and provenance -- is recorded BEFORE the PUT
 *       (1fba61d-F1). Provenance names the source (digests and kind), the base by reference
 *       and image ID, the dependencies actually handed in after PackageClosure (9159de6 N4),
 *       the LinkingTo versions and the attempt.</li>
 *   <li>PUT with X-Forge-Builder-Digest and X-Forge-Linking-To; no redirects. 201: UPLOADED.
 *       409: forge holds one already; its digest is fetched and the queue decides whether it is
 *       Skald's own earlier upload or another's (c365d95 N2). 400: forge refused the binary
 *       itself (its static checks: layout, Built, source, ELF), final: REFUSED. Anything else
 *       (403: registration or token scope, 5xx, a transfer error): a failed attempt.</li>
 * </ol>
 * forge's own refusal text is stored, made printable and bounded; the token never is.
 */
public final class BinaryUploader {

    /** How an upload ended for the row. */
    public enum Uploaded { UPLOADED, UPLOADED_EARLIER, FOREIGN, REFUSED, FAILED }

    public record Result(Uploaded outcome, String detail) { }

    static final String PROVENANCE_SCHEMA = "skald.binary-provenance/v1";
    private static final int MAX_LINKING_TO = 4096;

    private final BinaryBuildQueue queue;
    private final BinaryGate gate;
    private final ForgeSettings forge;
    private final BinaryArchive.Limits limits;
    private final HttpClient http;

    public BinaryUploader(BinaryBuildQueue queue, BinaryGate gate, ForgeSettings forge, BinaryArchive.Limits limits) {
        if (forge.url() == null) {
            throw new IllegalStateException("no forge-url is configured");
        }
        this.queue = queue;
        this.gate = gate;
        this.forge = forge;
        this.limits = limits;
        this.http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(forge.timeout()).build();
    }

    /**
     * @param handedIn      the closure the sandbox was handed (after PackageClosure.resolve)
     * @param baseReference the published base, by digest
     */
    public Result upload(BuildClaim claim, byte[] binary, BinaryArchive.Checked checked, List<BuiltDependency> handedIn,
                         BaseCatalog.Entry base, String baseReference, Optional<String> imageId) {
        BinaryGate.Decision decision = gate.decide(base, imageId);
        if (!decision.binaries() || !base.platformKey().equals(Optional.of(claim.platformKey()))) {
            String why = decision.binaries() ? "the base's key is not the row's" : decision.reason();
            queue.fail(claim.lease(), "REGISTRATION", RDependencies.printable(why));
            return new Result(Uploaded.FAILED, why);
        }
        String linkingTo;
        try {
            linkingTo = linkingTo(checked.description().get("LinkingTo"), handedIn, base.rRecommended());
        } catch (RDependencies.Unreadable e) {
            queue.refuse(claim.lease(), "BINARY_DESCRIPTION", e.getMessage());
            return new Result(Uploaded.REFUSED, e.getMessage());
        }
        if (linkingTo.length() > MAX_LINKING_TO) {
            queue.refuse(claim.lease(), "LINKING_TO_TOO_LONG", "LinkingTo names more than forge's header holds");
            return new Result(Uploaded.REFUSED, "LinkingTo too long");
        }
        queue.intendUpload(claim.lease(), checked.sha256(), provenance(claim, checked, handedIn, base, baseReference,
                imageId.orElseThrow(), linkingTo));
        URI uri = ForgeTree.binary(forge, claim.platformKey(), claim.pkg(), claim.version());
        HttpRequest put = HttpRequest.newBuilder(uri).timeout(forge.timeout())
                .PUT(HttpRequest.BodyPublishers.ofByteArray(binary))
                .header("Authorization", "Bearer " + forge.token())
                .header("Content-Type", "application/gzip")
                .header("X-Forge-Builder-Digest", imageId.orElseThrow())
                .header("X-Forge-Linking-To", linkingTo).build();
        HttpResponse<String> response;
        try {
            response = http.send(put, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            return failed(claim, "UPLOAD", e.getClass().getSimpleName() + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failed(claim, "UPLOAD", "interrupted");
        }
        int status = response.statusCode();
        String said = RDependencies.printable(response.body() == null ? "" : response.body().strip());
        switch (status) {
            case 201 -> {
                queue.uploaded(claim.lease(), checked.sha256());
                return new Result(Uploaded.UPLOADED, uri.getPath());
            }
            case 409 -> {
                String existing;
                try {
                    existing = existingDigest(uri);
                } catch (IOException e) {
                    return failed(claim, "UPLOAD_409", "409, and the existing binary could not be read: "
                            + e.getMessage());
                }
                BinaryBuildQueue.Present present = queue.alreadyPresent(claim.lease(), existing, "409 on upload");
                return new Result(present == BinaryBuildQueue.Present.OURS ? Uploaded.UPLOADED_EARLIER : Uploaded.FOREIGN,
                        existing);
            }
            case 400 -> {
                queue.refuse(claim.lease(), "FORGE_REJECTED", said);
                return new Result(Uploaded.REFUSED, said);
            }
            default -> {
                return failed(claim, "UPLOAD", "forge answered " + status + ": " + said);
            }
        }
    }

    private Result failed(BuildClaim claim, String code, String detail) {
        String d = RDependencies.printable(detail);
        queue.fail(claim.lease(), code, d);
        return new Result(Uploaded.FAILED, d);
    }

    /** The SHA-256 of the binary forge holds at {@code uri} (after a 409). */
    private String existingDigest(URI uri) throws IOException {
        HttpRequest get = HttpRequest.newBuilder(uri).timeout(forge.timeout()).GET()
                .header("Authorization", "Bearer " + forge.token()).build();
        try {
            HttpResponse<InputStream> r = http.send(get, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = r.body()) {
                if (r.statusCode() != 200) {
                    throw new IOException("forge answered " + r.statusCode());
                }
                MessageDigest sha = MessageDigest.getInstance("SHA-256");
                byte[] buf = new byte[64 * 1024];
                long total = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > limits.maxBytes()) {
                        throw new IOException("the existing binary is over " + limits.maxBytes() + " bytes");
                    }
                    sha.update(buf, 0, n);
                }
                return HexFormat.of().formatHex(sha.digest());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new java.io.InterruptedIOException("interrupted");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * forge's X-Forge-Linking-To: "pkg version, pkg version" for each LinkingTo package that was
     * present at compile time, by what the trusted side handed in or the base carries.
     */
    static String linkingTo(String field, List<BuiltDependency> handedIn, Map<String, String> baseLibrary)
            throws RDependencies.Unreadable {
        Map<String, String> have = new LinkedHashMap<>(baseLibrary);
        for (BuiltDependency d : handedIn) {
            have.put(d.pkg(), d.version());
        }
        List<String> out = new ArrayList<>();
        for (RDependencies.Requirement r : RDependencies.parse("LinkingTo", field)) {
            String v = have.get(r.pkg());
            if (v != null && out.stream().noneMatch(e -> e.startsWith(r.pkg() + " "))) {
                out.add(r.pkg() + " " + v);
            }
        }
        return String.join(", ", out);
    }

    private Map<String, Object> provenance(BuildClaim claim, BinaryArchive.Checked checked,
                                           List<BuiltDependency> handedIn, BaseCatalog.Entry base, String baseReference,
                                           String imageId, String linkingTo) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("schema", PROVENANCE_SCHEMA);
        doc.put("package", claim.pkg());
        doc.put("version", claim.version());
        doc.put("platform_key", claim.platformKey());
        doc.put("binary_sha256", checked.sha256());
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("sha256", claim.sourceSha256());
        source.put("md5", claim.sourceMd5());
        source.put("kind", claim.sourceKind() == BinaryBuildQueue.SourceKind.ARCHIVE
                ? "archive: no published checksum, trusted on its first fetch" : "index: MD5 matched the public index");
        doc.put("source", source);
        Map<String, Object> builder = new LinkedHashMap<>();
        builder.put("base_id", base.id());
        builder.put("reference", baseReference);
        builder.put("image_id", imageId);
        builder.put("r_platform", base.rPlatform());
        doc.put("builder", builder);
        List<Map<String, String>> deps = new ArrayList<>();
        for (BuiltDependency d : handedIn) {
            deps.add(Map.of("package", d.pkg(), "version", d.version(), "binary_sha256", d.binarySha256()));
        }
        doc.put("dependencies", deps);
        doc.put("linking_to", linkingTo);
        Map<String, Object> attempt = new LinkedHashMap<>();
        attempt.put("row", claim.rowId().toString());
        attempt.put("generation", claim.lease().generation());
        attempt.put("sandbox", "one package, T5 worker profile, install RUN --network=none, no secrets");
        doc.put("attempt", attempt);
        doc.put("forge_repository", forge.binaryRepository());
        return doc;
    }
}
