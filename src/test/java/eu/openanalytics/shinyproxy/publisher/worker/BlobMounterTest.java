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

import eu.openanalytics.shinyproxy.publisher.worker.BuildKitClient.Credential;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mandas.docker.client.DockerClient;
import org.mandas.docker.client.DockerClient.ExecCreateParam;
import org.mandas.docker.client.builder.jersey.JerseyDockerClientBuilder;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The base's blobs mounted into a build's repositories before its push (3d18996-F1), against
 * a real registry: what is mounted is then in each repository, what is not is left to the
 * push and leaves no upload behind, and every refusal names its cause.
 */
public class BlobMounterTest {

    private static DockerClient docker;
    private static TestRegistry registry;
    private static URI api;
    private static HttpClient http;

    @BeforeAll
    public static void start() throws Exception {
        docker = new JerseyDockerClientBuilder().fromEnv().build();
        registry = new TestRegistry(docker, "skald-bmt", 15043);
        api = URI.create("http://localhost:" + registry.port);
        http = HttpClient.newHttpClient();
    }

    @AfterAll
    public static void stop() throws Exception {
        try {
            if (registry != null) {
                registry.close();
            }
        } finally {
            if (docker != null) {
                docker.close();
            }
        }
    }

    private static BlobMounter mounter(Credential cred) {
        return new BlobMounter(api, registry.name, "amd64", cred);
    }

    private static HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create(api + path)).header("Authorization", "Basic " + Base64.getEncoder()
                .encodeToString((TestRegistry.CRED.username() + ":" + TestRegistry.CRED.password())
                        .getBytes(StandardCharsets.UTF_8)));
    }

    private static int blobStatus(String repository, String digest) throws Exception {
        return http.send(request("/v2/" + repository + "/blobs/" + digest)
                .method("HEAD", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.discarding())
                .statusCode();
    }

    /**
     * The live upload sessions the registry holds for a repository, as its storage lists them:
     * each has a startedat file. A cancel removes it and the data, and registry:2 leaves only an
     * empty hash-state directory behind, as it does for any client's cancel.
     */
    private static String uploads(String repository) throws Exception {
        String id = docker.execCreate(registry.name, new String[] {"sh", "-c",
                "ls /var/lib/registry/docker/registry/v2/repositories/" + repository + "/_uploads/*/startedat 2>/dev/null; true"},
                ExecCreateParam.attachStdout(), ExecCreateParam.attachStderr()).id();
        try (var out = docker.execStart(id)) {
            return out.readFully().strip();
        }
    }

    private static Set<String> baseBlobs() throws Exception {
        String base = registry.baseByDigest();
        return mounter(TestRegistry.CRED).blobs("base/busybox", base.substring(base.indexOf('@') + 1));
    }

    @Test
    public void everyBlobOfTheBaseIsInEachRepositoryAfterAMount() throws Exception {
        Set<String> blobs = baseBlobs();
        assertTrue(blobs.size() >= 2, "a config and at least one layer: " + blobs);
        List<String> repos = List.of("content/m1", "skald/cache/m1");
        for (String r : repos) {
            for (String d : blobs) {
                assertEquals(404, blobStatus(r, d), "not there before: " + r + " " + d);
            }
        }
        BlobMounter.Result result = mounter(TestRegistry.CRED).mount(registry.baseByDigest(), repos);
        assertEquals(new BlobMounter.Result(2 * blobs.size(), 0), result);
        for (String r : repos) {
            for (String d : blobs) {
                assertEquals(200, blobStatus(r, d), "mounted: " + r + " " + d);
            }
        }
        // Again: already there is mounted, nothing to upload.
        assertEquals(new BlobMounter.Result(2 * blobs.size(), 0),
                mounter(TestRegistry.CRED).mount(registry.baseByDigest(), repos));
    }

    @Test
    public void anIndexResolvesToThisArchitecturesManifest() throws Exception {
        String base = registry.baseByDigest();
        String child = base.substring(base.indexOf('@') + 1);
        HttpResponse<byte[]> m = http.send(request("/v2/base/busybox/manifests/" + child)
                .header("Accept", PublishVerifier.ACCEPT).build(), HttpResponse.BodyHandlers.ofByteArray());
        String type = m.headers().firstValue("Content-Type").orElseThrow();
        String index = "{\"schemaVersion\":2,\"mediaType\":\"application/vnd.oci.image.index.v1+json\",\"manifests\":["
                + "{\"mediaType\":\"" + type + "\",\"digest\":\"" + child + "\",\"size\":" + m.body().length
                + ",\"platform\":{\"os\":\"linux\",\"architecture\":\"amd64\"}}]}";
        HttpResponse<Void> put = http.send(request("/v2/base/busybox/manifests/idx")
                .header("Content-Type", "application/vnd.oci.image.index.v1+json")
                .PUT(HttpRequest.BodyPublishers.ofString(index)).build(), HttpResponse.BodyHandlers.discarding());
        assertEquals(201, put.statusCode());
        String indexDigest = put.headers().firstValue("Docker-Content-Digest").orElseThrow();
        Set<String> blobs = baseBlobs();
        BlobMounter.Result result = mounter(TestRegistry.CRED).mount(
                registry.name + ":5000/base/busybox@" + indexDigest, List.of("content/m2"));
        assertEquals(new BlobMounter.Result(blobs.size(), 0), result);
        for (String d : blobs) {
            assertEquals(200, blobStatus("content/m2", d));
        }
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new BlobMounter(api, registry.name, "arm64", TestRegistry.CRED)
                        .mount(registry.name + ":5000/base/busybox@" + indexDigest, List.of("content/m3")));
        assertTrue(e.getMessage().contains("no linux/arm64 manifest"), e.getMessage());
    }

    @Test
    public void aBlobTheRegistryWillNotMountIsLeftToThePushWithNoUploadOpen() throws Exception {
        String missing = "sha256:" + "0".repeat(64);
        HttpResponse<Void> r = http.send(request("/v2/content/m4/blobs/uploads/?mount=" + missing + "&from=base/busybox")
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.discarding());
        assertEquals(202, r.statusCode(), "the registry opens an upload instead");
        assertTrue(!uploads("content/m4").isEmpty(), "and keeps it, as the registry's storage shows");
        URI opened = URI.create(r.headers().firstValue("Location").orElseThrow());
        assertEquals(204, http.send(request(opened.getRawPath() + "?" + opened.getRawQuery()).DELETE().build(),
                HttpResponse.BodyHandlers.discarding()).statusCode(), "an opened upload can be cancelled");

        // The mounter, for the same blob: not mounted, and the upload it opened is gone.
        BlobMounter mounter = mounter(TestRegistry.CRED);
        assertFalse(mounter.mountOne("content/m4", missing, "base/busybox"));
        assertEquals(404, blobStatus("content/m4", missing));
        assertEquals("", uploads("content/m4"), "the upload the refused mount opened was cancelled");
    }

    @Test
    public void refusals() throws Exception {
        BlobMounter mounter = mounter(TestRegistry.CRED);
        IllegalArgumentException other = assertThrows(IllegalArgumentException.class,
                () -> mounter.mount("elsewhere:5000/base/busybox@sha256:" + "a".repeat(64), List.of("content/m5")));
        assertTrue(other.getMessage().contains("not a base on " + registry.name + ":5000"), other.getMessage());
        IllegalArgumentException name = assertThrows(IllegalArgumentException.class,
                () -> mounter.mount(registry.baseByDigest(), List.of("content/../m5")));
        assertTrue(name.getMessage().contains("not a repository name"), name.getMessage());
        IllegalStateException wrong = assertThrows(IllegalStateException.class,
                () -> mounter(new Credential(TestRegistry.CRED.username(), "wrong")).mount(registry.baseByDigest(),
                        List.of("content/m5")));
        assertTrue(wrong.getMessage().contains("answered 401"), wrong.getMessage());
        assertThrows(IllegalArgumentException.class, () -> new BlobMounter(URI.create(api + "/"), registry.name, "amd64",
                TestRegistry.CRED));
    }
}
