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
import eu.openanalytics.shinyproxy.publisher.worker.BuildKitClient.Credential;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Links a published base's blobs into the repositories a build pushes to, before it pushes
 * (3d18996-F1: an R first publish took 138 s, half of it the push and the cache export).
 * BuildKit never asked the registry for a cross-repository mount: the registry's log showed
 * every base layer uploaded again, in full, to the content repository and again to the cache
 * repository, about 1 GB for the R base. Mounted first, the push finds each base blob already
 * in its repository and sends only the layers the build made.
 *
 * <p>The registry API's own mount (POST /v2/&lt;repo&gt;/blobs/uploads/?mount=&lt;digest&gt;&amp;from=&lt;base
 * repo&gt;): 201 is mounted; 202 means the registry would not mount and opened an upload
 * instead, which is cancelled, and the push then uploads that blob as it always did. So a
 * registry without mounts costs one request per blob and nothing else. Basic authentication
 * with the build credential, as {@link PublishVerifier}.
 */
public final class BlobMounter {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern REPOSITORY = Pattern.compile(
            "[a-z0-9]+(?:(?:[._]|__|-+)[a-z0-9]+)*(?:/[a-z0-9]+(?:(?:[._]|__|-+)[a-z0-9]+)*)*");
    private static final Pattern DIGEST = Pattern.compile("sha256:[0-9a-f]{64}");
    /** Mount requests in flight at once. */
    static final int PARALLEL = 8;

    /** What a mount did: blobs now in place (mounted or already there), and blobs the push will upload. */
    public record Result(int mounted, int notMounted) {
    }

    private final URI registryApi;
    private final String registry;
    private final String architecture;
    private final Credential credential;
    private final HttpClient http;

    public BlobMounter(URI registryApi, String registry, String architecture, Credential credential) {
        this(registryApi, registry, architecture, credential, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build());
    }

    BlobMounter(URI registryApi, String registry, String architecture, Credential credential, HttpClient http) {
        String api = registryApi.toString();
        if (!(api.startsWith("http://") || api.startsWith("https://")) || api.endsWith("/")) {
            throw new IllegalArgumentException("registry-api is http(s)://host[:port] with no trailing '/': " + api);
        }
        this.registryApi = registryApi;
        this.registry = registry;
        this.architecture = architecture;
        this.credential = credential;
        this.http = http;
    }

    /**
     * Mounts every blob of {@code base} (a published base by digest, as the recipe names it)
     * into each of {@code repositories}. Throws if the base cannot be read; a blob the registry
     * will not mount is counted, not thrown.
     */
    public Result mount(String base, List<String> repositories) throws Exception {
        Matcher m = PublishVerifier.IMAGE.matcher(base);
        if (!m.matches() || !m.group(1).equals(registry + ":5000")) {
            throw new IllegalArgumentException("not a base on " + registry + ":5000 by digest: " + base);
        }
        for (String r : repositories) {
            if (!REPOSITORY.matcher(r).matches()) {
                throw new IllegalArgumentException("not a repository name: " + r);
            }
        }
        String from = m.group(2);
        Set<String> blobs = blobs(from, m.group(3));
        // Side by side, at most PARALLEL at a time: on a loaded host one mount took about 10 s
        // (the T7b probe), and two dozen of them one after another were four minutes.
        List<java.util.concurrent.Callable<Boolean>> mounts = new ArrayList<>();
        for (String repository : repositories) {
            for (String digest : blobs) {
                mounts.add(() -> mountOne(repository, digest, from));
            }
        }
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(
                Math.max(1, Math.min(PARALLEL, mounts.size())), r -> {
                    Thread t = new Thread(r, "skald-blob-mount");
                    t.setDaemon(true);
                    return t;
                });
        int mounted = 0;
        int notMounted = 0;
        try {
            for (java.util.concurrent.Future<Boolean> f : pool.invokeAll(mounts)) {
                try {
                    if (f.get()) {
                        mounted++;
                    } else {
                        notMounted++;
                    }
                } catch (java.util.concurrent.ExecutionException e) {
                    throw e.getCause() instanceof Exception x ? x : e;
                }
            }
        } finally {
            pool.shutdownNow();
        }
        return new Result(mounted, notMounted);
    }

    /** The config and layer digests of the base's manifest; an index resolves to this architecture's linux manifest. */
    Set<String> blobs(String repository, String digest) throws Exception {
        JsonNode manifest = manifest(repository, digest);
        if (manifest.has("manifests")) {
            String child = null;
            for (JsonNode d : manifest.get("manifests")) {
                JsonNode p = d.path("platform");
                if ("linux".equals(p.path("os").asText()) && architecture.equals(p.path("architecture").asText())) {
                    child = d.path("digest").asText();
                    break;
                }
            }
            if (child == null || !DIGEST.matcher(child).matches()) {
                throw new IllegalStateException("the base's index has no linux/" + architecture + " manifest");
            }
            manifest = manifest(repository, child);
        }
        Set<String> out = new LinkedHashSet<>();
        List<JsonNode> descriptors = new ArrayList<>();
        descriptors.add(manifest.path("config"));
        manifest.path("layers").forEach(descriptors::add);
        for (JsonNode d : descriptors) {
            String b = d.path("digest").asText();
            if (!DIGEST.matcher(b).matches()) {
                throw new IllegalStateException("the base's manifest names a blob that is not a sha256 digest: " + b);
            }
            out.add(b);
        }
        return out;
    }

    private JsonNode manifest(String repository, String digest) throws Exception {
        HttpResponse<byte[]> r = http.send(request("/v2/" + repository + "/manifests/" + digest)
                .header("Accept", PublishVerifier.ACCEPT).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        refuseBearer(r);
        if (r.statusCode() != 200) {
            throw new IllegalStateException("the registry answered " + r.statusCode() + " for " + repository + "@" + digest);
        }
        return JSON.readTree(r.body());
    }

    boolean mountOne(String repository, String digest, String from) throws Exception {
        HttpResponse<Void> r = http.send(request("/v2/" + repository + "/blobs/uploads/?mount="
                + URLEncoder.encode(digest, StandardCharsets.UTF_8) + "&from=" + URLEncoder.encode(from, StandardCharsets.UTF_8))
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.discarding());
        refuseBearer(r);
        if (r.statusCode() == 201) {
            return true;
        }
        if (r.statusCode() == 202) {
            // Not mounted, and an upload session opened in its place: cancelled, so the
            // registry is left as it was. A cancel that fails leaves a session the
            // registry's own upload purge removes.
            String location = r.headers().firstValue("Location").orElse(null);
            // Only its path, sent to the registry this was configured with: the credential
            // never follows a host the response names.
            if (location != null) {
                URI loc = URI.create(location);
                String path = loc.getRawPath() + (loc.getRawQuery() == null ? "" : "?" + loc.getRawQuery());
                if (path.startsWith("/v2/" + repository + "/blobs/uploads/")) {
                    http.send(request(path).DELETE().build(), HttpResponse.BodyHandlers.discarding());
                }
            }
        }
        return false;
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create(registryApi + path)).header("Authorization", "Basic " + Base64.getEncoder().encodeToString(
                        (credential.username() + ":" + credential.password()).getBytes(StandardCharsets.UTF_8)))
                .timeout(Duration.ofSeconds(30));
    }

    private static void refuseBearer(HttpResponse<?> r) {
        if (r.statusCode() == 401 && r.headers().firstValue("WWW-Authenticate").orElse("").startsWith("Bearer")) {
            throw new IllegalStateException("the registry wants bearer tokens; only basic authentication is supported");
        }
    }
}
