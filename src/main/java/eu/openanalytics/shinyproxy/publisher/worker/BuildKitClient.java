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

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.openanalytics.shinyproxy.publisher.recipe.RecipeGenerator.Recipe;
import eu.openanalytics.shinyproxy.publisher.worker.DockerWorkerLauncher.Handle;
import eu.openanalytics.shinyproxy.publisher.worker.DockerWorkerLauncher.Images;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.mandas.docker.client.DockerClient;
import org.mandas.docker.client.DockerClient.LogsParam;
import org.mandas.docker.client.DockerClient.RemoveContainerParam;
import org.mandas.docker.client.LogStream;
import org.mandas.docker.client.exceptions.DockerException;
import org.mandas.docker.client.exceptions.NotFoundException;
import org.mandas.docker.client.exceptions.VolumeNotFoundException;
import org.mandas.docker.client.messages.ContainerState;
import org.mandas.docker.client.messages.ContainerConfig;
import org.mandas.docker.client.messages.HostConfig;
import org.mandas.docker.client.messages.LogConfig;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The trusted side of one build: it stages the build context into the attempt's context
 * volume, then runs buildctl in a client container on the worker's socket volume, which
 * pushes through the gateway and reports the pushed digest.
 *
 * <p><b>No host path reaches any container.</b> The context travels as a tar stream
 * through the Docker API into a stopped helper that mounts the context volume; the client
 * mounts that volume read-only. Its layout is {@code ctx/app/**} (the verified payload),
 * {@code ctx/skald/*} (the recipe's server-written files) and {@code df/Dockerfile}, which
 * is OUTSIDE the context, so no COPY can read it.
 *
 * <p><b>The registry credential is the client's alone.</b> The worker pulls and pushes
 * with what buildctl's session hands it per request; build code never holds it (the T5
 * attack matrix's "registry write 401"). The credential reaches the client as an
 * environment value, which a shell writes to a 0600 file on the client's tmpfs and then
 * unsets. It is visible to Docker API holders through inspect until the client is removed,
 * and to nobody else; the client container is never reachable by build code.
 *
 * <p><b>Nothing build code says is parsed as the result.</b> buildctl's progress, which
 * carries every RUN step's output, goes to the client's stderr; the client's stdout carries
 * only the metadata file, printed after buildctl succeeded. buildctl cannot write the
 * metadata to /dev/stdout directly (measured: it renames a temporary file next to it).
 */
public final class BuildKitClient {

    static final String CONTEXT_MOUNT = "/c";
    static final String METADATA = "/tmp/meta.json";
    /** What the client may use: its tmpfs holds one small config file and the metadata. */
    static final long CLIENT_MEMORY = 256L << 20;
    private static final String HOST_LABEL = "[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?";
    private static final Pattern REGISTRY = Pattern.compile(
            "(?=.{1,253}$)" + HOST_LABEL + "(?:\\." + HOST_LABEL + ")*");
    /** The distribution spec's repository name, lower case: no ',' can reach --output. */
    private static final Pattern REPOSITORY = Pattern.compile(
            "(?=.{1,200}$)[a-z0-9]+(?:(?:[._]|__|-+)[a-z0-9]+)*(?:/[a-z0-9]+(?:(?:[._]|__|-+)[a-z0-9]+)*)*");
    private static final Pattern DIGEST = Pattern.compile("sha256:[0-9a-f]{64}");
    private static final Pattern CACHE_KEY = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern RECIPE_FILE = Pattern.compile("skald/[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
    private static final ObjectMapper JSON = new ObjectMapper();

    /** The registry login the client hands buildctl. Never printed. */
    public record Credential(String username, String password) {

        public Credential {
            if (username == null || username.isEmpty() || password == null || password.isEmpty()) {
                throw new IllegalArgumentException("a registry credential needs a user and a password");
            }
        }

        @Override
        public String toString() {
            return "Credential[" + username + ", ****]";
        }
    }

    /**
     * Where the image goes: {@code <registry>:5000/<repository>:build-<buildId>}, the tag
     * the coordinator ledgers. {@code registry} is the host name the gateway allows.
     */
    /**
     * The dependency cache of one content item (WORKPLAN-BUNDLES.md decision 7): BuildKit's
     * registry cache at {@code <registry>:5000/skald/cache/<content UUID>:<key>}. Scoped to the
     * content item AND the key, both chosen by the server, so another content item's build
     * never reads or writes it; the key is recipe.DependencyCacheKey.
     */
    public record Cache(UUID scope, String key) {

        public Cache {
            if (scope == null || key == null || !CACHE_KEY.matcher(key).matches()) {
                throw new IllegalArgumentException("a cache is a content UUID and a 64-hex key");
            }
        }
    }

    public record Push(String registry, String repository, UUID buildId, Credential credential, Cache cache) {

        /** A push without a dependency cache. */
        public Push(String registry, String repository, UUID buildId, Credential credential) {
            this(registry, repository, buildId, credential, null);
        }

        public Push {
            if (!REGISTRY.matcher(registry).matches()) {
                throw new IllegalArgumentException("not a registry host name: " + registry);
            }
            if (!REPOSITORY.matcher(repository).matches()) {
                throw new IllegalArgumentException("not a repository name: " + repository);
            }
            if (buildId == null || credential == null) {
                throw new IllegalArgumentException("a push needs a build id and a credential");
            }
        }

        String registryAddress() {
            return registry + ":5000";
        }

        String name() {
            return registryAddress() + "/" + repository + ":build-" + buildId;
        }

        /** The cache reference, or null when the push has no cache. */
        String cacheRef() {
            return cache == null ? null : registryAddress() + "/skald/cache/" + cache.scope() + ":" + cache.key();
        }

        /** The reference the attempt reports: by digest, never by tag. */
        public String byDigest(String digest) {
            return registryAddress() + "/" + repository + "@" + digest;
        }
    }

    private final DockerClient docker;
    private final DockerWorkerLauncher launcher;
    private final Images images;

    public BuildKitClient(DockerClient docker, DockerWorkerLauncher launcher, Images images) {
        this.docker = docker;
        this.launcher = launcher;
        this.images = images;
    }

    static String stageHelper(Handle h) {
        return "skald-helper-stage-" + h.attemptId();
    }

    /**
     * Fills the attempt's context volume. Refuses if the volume already exists: a stale one
     * of the same attempt would carry an earlier context, and a foreign one is not ours.
     */
    public void stage(Handle h, Path payload, Recipe recipe) throws DockerException, InterruptedException, IOException {
        for (String key : recipe.files().keySet()) {
            if (!RECIPE_FILE.matcher(key).matches()) {
                throw new IllegalArgumentException("a recipe file is skald/<name>: " + key);
            }
        }
        if (!Files.isDirectory(payload, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("the payload is not a directory: " + payload);
        }
        try {
            docker.inspectVolume(h.contextVolume());
            throw new IllegalStateException("volume " + h.contextVolume() + " is already taken, refusing to adopt it");
        } catch (NotFoundException | VolumeNotFoundException e) {
            // free
        }
        Map<String, String> labels = Map.of(DockerWorkerLauncher.ATTEMPT_LABEL, h.attemptId());
        launcher.createOwnedVolume(h.contextVolume(), labels, null);
        String helper = stageHelper(h);
        docker.createContainer(ContainerConfig.builder().image(images.helper()).cmd("true").labels(labels)
                .hostConfig(HostConfig.builder().networkMode("none")
                        .binds(h.contextVolume() + ":" + CONTEXT_MOUNT).build()).build(), helper);
        try {
            copyTar(helper, out -> writeContext(out, payload, recipe));
        } finally {
            docker.removeContainer(helper, RemoveContainerParam.forceKill());
        }
    }

    interface TarBody {
        void write(OutputStream out) throws IOException;
    }

    interface TarSink {
        void accept(java.io.InputStream in) throws DockerException, InterruptedException, IOException;
    }

    /** Streams a tar into a stopped container's {@link #CONTEXT_MOUNT}, without a file on disk. */
    private void copyTar(String container, TarBody body) throws DockerException, InterruptedException, IOException {
        streamTar(body, in -> docker.copyToContainer(in, container, CONTEXT_MOUNT), "skald-stage-" + container);
    }

    /**
     * Runs {@code body} on a writer thread into {@code sink}, and succeeds only if the body
     * RETURNED -- writeContext's last act is tar.finish(). A writer that dies of anything
     * still closes the pipe, and Docker accepts a tar cut at an entry boundary with no
     * end-of-archive blocks as complete (measured by the reviewer: rc 0, the directories
     * created). So the sink's success says nothing, and every Throwable of the writer is the
     * stage's failure (94b4dae-F1: an unreadable directory surfaced from Files.walk as an
     * UncheckedIOException, which the old IOException-only catch let through).
     */
    static void streamTar(TarBody body, TarSink sink, String threadName)
            throws DockerException, InterruptedException, IOException {
        PipedInputStream in = new PipedInputStream(1 << 16);
        PipedOutputStream pipe = new PipedOutputStream(in);
        AtomicReference<Throwable> failed = new AtomicReference<>();
        java.util.concurrent.atomic.AtomicBoolean finished = new java.util.concurrent.atomic.AtomicBoolean();
        Thread writer = new Thread(() -> {
            try {
                body.write(pipe);
                pipe.close();
                // Only here: the body returned AND the end of the stream was delivered.
                finished.set(true);
            } catch (Throwable e) {
                failed.set(e);
            } finally {
                try {
                    pipe.close();
                } catch (IOException e) {
                    // the failure that matters is already recorded, or finished is false
                }
            }
        }, threadName);
        writer.setDaemon(true);
        writer.start();
        try {
            sink.accept(in);
        } finally {
            // Unblocks a writer the sink stopped reading from.
            in.close();
            writer.join();
        }
        if (!finished.get()) {
            throw new IOException("the build context was not written in full: " + failed.get(), failed.get());
        }
    }

    /** The context tar. Owners are root, modes are set here, and only files and directories pass. */
    static void writeContext(OutputStream out, Path payload, Recipe recipe) throws IOException {
        TarArchiveOutputStream tar = new TarArchiveOutputStream(out, StandardCharsets.UTF_8.name());
        tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
        tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX);
        tar.setAddPaxHeadersForNonAsciiNames(true);
        for (String dir : List.of("ctx/", "ctx/app/", "ctx/skald/", "df/")) {
            directory(tar, dir);
        }
        List<Path> entries;
        try (Stream<Path> walk = Files.walk(payload)) {
            entries = walk.filter(p -> !p.equals(payload)).sorted().toList();
        }
        for (Path p : entries) {
            String name = "ctx/app/" + payload.relativize(p).toString().replace(java.io.File.separatorChar, '/');
            BasicFileAttributes a = Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (a.isDirectory()) {
                directory(tar, name + "/");
            } else if (a.isRegularFile()) {
                boolean exec = Files.getPosixFilePermissions(p, LinkOption.NOFOLLOW_LINKS)
                        .contains(PosixFilePermission.OWNER_EXECUTE);
                TarArchiveEntry e = entry(name, exec ? 0755 : 0644);
                e.setSize(a.size());
                tar.putArchiveEntry(e);
                try (var in = Files.newInputStream(p, LinkOption.NOFOLLOW_LINKS)) {
                    long copied = in.transferTo(tar);
                    if (copied != a.size()) {
                        throw new IOException("the payload changed while it was staged: " + name);
                    }
                }
                tar.closeArchiveEntry();
            } else {
                // The extractor writes only files and directories; anything else here did not
                // come from it.
                throw new IOException("not a file or directory in the payload: " + name);
            }
        }
        for (Map.Entry<String, byte[]> f : recipe.files().entrySet()) {
            file(tar, "ctx/" + f.getKey(), f.getValue());
        }
        file(tar, "df/Dockerfile", recipe.dockerfile().getBytes(StandardCharsets.UTF_8));
        tar.finish();
    }

    private static TarArchiveEntry entry(String name, int mode) {
        TarArchiveEntry e = new TarArchiveEntry(name, true);
        e.setMode(mode);
        e.setIds(0, 0);
        e.setNames("root", "root");
        e.setModTime(0);
        return e;
    }

    private static void directory(TarArchiveOutputStream tar, String name) throws IOException {
        tar.putArchiveEntry(entry(name, 0755));
        tar.closeArchiveEntry();
    }

    private static void file(TarArchiveOutputStream tar, String name, byte[] bytes) throws IOException {
        TarArchiveEntry e = entry(name, 0644);
        e.setSize(bytes.length);
        tar.putArchiveEntry(e);
        tar.write(bytes);
        tar.closeArchiveEntry();
    }

    /**
     * The client's entrypoint: a shell that writes the credential and then runs its
     * arguments, so buildctl's argv ({@link #buildctl}) never passes through the shell's
     * parser.
     */
    static final List<String> CLIENT_ENTRYPOINT = List.of("sh", "-c",
            "umask 077 && mkdir /tmp/cfg && printf %s \"$SKALD_DOCKER_CONFIG\" > /tmp/cfg/config.json"
                    + " && unset SKALD_DOCKER_CONFIG && DOCKER_CONFIG=/tmp/cfg \"$@\" && cat " + METADATA,
            "sh");

    /**
     * BuildKit's SLSA provenance attestation, attached to every pushed image: an attestation
     * manifest beside the image in an OCI index, so the pushed digest is the index's and
     * anyone holding the image can read what built it (buildx imagetools inspect). Unsigned,
     * and written inside the worker that runs the bundle's steps: portable, not proof; the
     * build record is the trustworthy statement (ADR-0012, b45c0a9 N1). mode=max records the
     * Dockerfile and every build argument's VALUE; that is safe here, and must stay safe: the
     * Dockerfile is the server's recipe, the only build arguments are the gateway proxy's
     * ({@link #proxyArgs}: an internal address), and the one secret (the registry
     * credential) is the client's docker config, which is not a build input. version pinned,
     * so an upstream default change cannot alter the format unnoticed.
     */
    static final String PROVENANCE_ATTESTATION = "attest:provenance=mode=max,version=v1";

    /** The attestation, as the provenance record states it. */
    static Map<String, Object> attestation() {
        Map<String, Object> a = new java.util.LinkedHashMap<>();
        a.put("type", "https://slsa.dev/provenance/v1");
        a.put("generator", "buildkit");
        a.put("mode", "max");
        a.put("stored", "attestation manifest in the pushed image index");
        return a;
    }

    /** The gateway as a proxy URL, http://ipv4:port: nothing else may reach a build-arg. */
    private static final java.util.regex.Pattern PROXY =
            java.util.regex.Pattern.compile("http://[0-9]{1,3}(?:\\.[0-9]{1,3}){3}:[0-9]{1,5}");

    /**
     * The proxy, for RUN steps. The worker's network reaches nothing but the gateway, and
     * buildkitd's own environment (http_proxy, for pulls) is not a RUN step's: without these
     * every restore resolved its mirror directly and failed (the T7 pass probe). By address
     * ({@link DockerWorkerLauncher#gatewayProxy}): a RUN step cannot resolve the gateway's
     * name. BuildKit's
     * predefined proxy build arguments, as docker build passes a client's proxy: the Dockerfile
     * frontend gives them to RUN steps outside the cache key (the gateway's name is per attempt,
     * so anything else would make every dependency layer a miss) and leaves them out of the
     * image's config and history. Upper and lower case, as tools read either; https too, for an
     * https mirror reached by CONNECT. The value is an internal address, not a secret.
     */
    static List<String> proxyArgs(String proxy) {
        if (proxy == null || !PROXY.matcher(proxy).matches()) {
            throw new IllegalArgumentException("not a gateway proxy URL: " + proxy);
        }
        List<String> args = new java.util.ArrayList<>();
        for (String name : List.of("HTTP_PROXY", "http_proxy", "HTTPS_PROXY", "https_proxy")) {
            args.addAll(List.of("--opt", "build-arg:" + name + "=" + proxy));
        }
        return args;
    }

    static List<String> buildctl(Push push, String proxy) {
        List<String> args = new java.util.ArrayList<>(List.of("buildctl", "--addr", DockerWorkerLauncher.SOCKET_ADDRESS,
                "build", "--progress", "plain", "--frontend", "dockerfile.v0",
                "--local", "context=" + CONTEXT_MOUNT + "/ctx", "--local", "dockerfile=" + CONTEXT_MOUNT + "/df",
                "--opt", PROVENANCE_ATTESTATION));
        args.addAll(proxyArgs(proxy));
        args.addAll(List.of(
                "--output", "type=image,name=" + push.name() + ",push=true",
                "--metadata-file", METADATA));
        if (push.cache() != null) {
            // A missing cache is a cold build; a cache that cannot be exported is not a build
            // failure (ignore-error). mode=min: the dependency layers are layers of the image.
            args.addAll(List.of("--import-cache", "type=registry,ref=" + push.cacheRef(),
                    "--export-cache", "type=registry,ref=" + push.cacheRef() + ",mode=min,ignore-error=true"));
        }
        return List.copyOf(args);
    }

    static String dockerConfig(Push push) {
        return dockerConfig(push.registryAddress(), push.credential());
    }

    static String dockerConfig(String registryAddress, Credential credential) {
        String auth = Base64.getEncoder().encodeToString((credential.username() + ":"
                + credential.password()).getBytes(StandardCharsets.UTF_8));
        return JSON.createObjectNode().set("auths", JSON.createObjectNode().set(registryAddress,
                JSON.createObjectNode().put("auth", auth))).toString();
    }

    // ------------------------------------------------------------------ local output (T7b)

    /** Where a local-output client writes the build's exported files: the attempt's output volume. */
    static final String OUTPUT_MOUNT = "/out";

    /**
     * The local-output client's entrypoint: the credential written as for a push (the worker
     * pulls the base with it), then buildctl; no metadata is printed, since nothing is pushed.
     */
    static final List<String> LOCAL_ENTRYPOINT = List.of("sh", "-c",
            "umask 077 && mkdir /tmp/cfg && printf %s \"$SKALD_DOCKER_CONFIG\" > /tmp/cfg/config.json"
                    + " && unset SKALD_DOCKER_CONFIG && DOCKER_CONFIG=/tmp/cfg \"$@\"",
            "sh");

    /**
     * buildctl for a build whose result is FILES, not an image (a package's binary): the
     * final stage's files exported to {@link #OUTPUT_MOUNT}. No proxy build arguments (the
     * recipe's RUN steps have no network; the daemon pulls the base through the gateway
     * itself), no attestation (no image), no cache.
     */
    static List<String> buildctlLocal() {
        return List.of("buildctl", "--addr", DockerWorkerLauncher.SOCKET_ADDRESS,
                "build", "--progress", "plain", "--frontend", "dockerfile.v0",
                "--local", "context=" + CONTEXT_MOUNT + "/ctx", "--local", "dockerfile=" + CONTEXT_MOUNT + "/df",
                "--output", "type=local,dest=" + OUTPUT_MOUNT);
    }

    /**
     * Starts a local-output client; returns at once. The context must be staged, and the
     * attempt launched with its output volume (Request.output). {@code registry} is the host the gateway allows; the
     * credential lets the worker pull the base from it and is the client's alone, as for a
     * push.
     */
    public void startLocal(Handle h, String registry, Credential credential) throws DockerException, InterruptedException {
        if (!REGISTRY.matcher(registry).matches() || credential == null) {
            throw new IllegalArgumentException("a local build needs the registry host and its credential");
        }
        launcher.requireOutput(h);
        Map<String, String> labels = Map.of(DockerWorkerLauncher.ATTEMPT_LABEL, h.attemptId());
        HostConfig hc = HostConfig.builder()
                .networkMode("none")
                .binds(h.socketVolume() + ":" + DockerWorkerLauncher.SOCKET_MOUNT,
                        h.contextVolume() + ":" + CONTEXT_MOUNT + ":ro",
                        h.outputVolume() + ":" + OUTPUT_MOUNT)
                .readonlyRootfs(true)
                .tmpfs(Map.of("/tmp", "rw,noexec,nosuid,nodev,size=1m,mode=1777"))
                .capDrop("ALL")
                .securityOpt("no-new-privileges")
                .memory(CLIENT_MEMORY)
                .memorySwap(CLIENT_MEMORY)
                .pidsLimit(64)
                .nanoCpus(1_000_000_000L)
                .privileged(false)
                .logConfig(LogConfig.create("json-file", Map.of("max-size", "16m", "max-file", "2")))
                .build();
        docker.createContainer(ContainerConfig.builder().image(images.worker())
                .user(DockerWorkerLauncher.WORKER_UID + ":" + DockerWorkerLauncher.WORKER_UID)
                .entrypoint(LOCAL_ENTRYPOINT)
                .cmd(buildctlLocal())
                .env("SKALD_DOCKER_CONFIG=" + dockerConfig(registry + ":5000", credential))
                .labels(labels).hostConfig(hc).build(), h.client());
        docker.startContainer(h.client());
    }

    /**
     * After a successful exit: the one file the build exported, {@code /out/<file>}. The
     * output volume is read through the Docker API from the stopped client, never a host
     * path, as a tar the trusted side bounds: exactly the output directory and that one
     * regular file, at most {@code maxBytes}. Anything else the build exported is a refusal:
     * a package's sandbox exports one file, and nothing else leaves it.
     */
    public byte[] collect(Handle h, String file, long maxBytes) throws DockerException, InterruptedException, IOException {
        if (!RECIPE_FILE.matcher("skald/" + file).matches()) {
            throw new IllegalArgumentException("not an output file name: " + file);
        }
        byte[] found = null;
        try (var in = docker.archiveContainer(h.client(), OUTPUT_MOUNT + "/");
             var tar = new org.apache.commons.compress.archivers.tar.TarArchiveInputStream(in)) {
            TarArchiveEntry e;
            while ((e = tar.getNextEntry()) != null) {
                String name = e.getName();
                if (e.isDirectory() && (name.equals("out/") || name.equals("out"))) {
                    continue;
                }
                if (!name.equals("out/" + file) || !e.isFile() || found != null) {
                    throw new IllegalStateException("the build exported more than " + file + ": "
                            + BuildKitDriver.detail(name));
                }
                if (e.getSize() > maxBytes) {
                    throw new IllegalStateException(file + " is " + e.getSize() + " bytes, over " + maxBytes);
                }
                found = tar.readNBytes((int) e.getSize());
                if (found.length != e.getSize()) {
                    throw new IOException(file + " ended early");
                }
            }
        }
        if (found == null) {
            throw new IllegalStateException("the build exported no " + file);
        }
        return found;
    }

    /** Creates and starts the client; returns at once. The context must be staged. */
    public void start(Handle h, Push push) throws DockerException, InterruptedException {
        Map<String, String> labels = Map.of(DockerWorkerLauncher.ATTEMPT_LABEL, h.attemptId());
        HostConfig hc = HostConfig.builder()
                .networkMode("none")
                .binds(h.socketVolume() + ":" + DockerWorkerLauncher.SOCKET_MOUNT,
                        h.contextVolume() + ":" + CONTEXT_MOUNT + ":ro")
                .readonlyRootfs(true)
                .tmpfs(Map.of("/tmp", "rw,noexec,nosuid,nodev,size=1m,mode=1777"))
                .capDrop("ALL")
                .securityOpt("no-new-privileges")
                .memory(CLIENT_MEMORY)
                .memorySwap(CLIENT_MEMORY)
                .pidsLimit(64)
                .nanoCpus(1_000_000_000L)
                .privileged(false)
                // The build log is read from here; bounded so a chatty build cannot fill the
                // host's disk through the client's log file.
                .logConfig(LogConfig.create("json-file", Map.of("max-size", "16m", "max-file", "2")))
                .build();
        docker.createContainer(ContainerConfig.builder().image(images.worker())
                .user(DockerWorkerLauncher.WORKER_UID + ":" + DockerWorkerLauncher.WORKER_UID)
                .entrypoint(CLIENT_ENTRYPOINT)
                .cmd(buildctl(push, launcher.gatewayProxy(h)))
                .env("SKALD_DOCKER_CONFIG=" + dockerConfig(push))
                .labels(labels).hostConfig(hc).build(), h.client());
        docker.startContainer(h.client());
    }

    /** The client's exit code once it has exited. */
    public Optional<Long> exitCode(Handle h) throws DockerException, InterruptedException {
        ContainerState s = docker.inspectContainer(h.client()).state();
        return s.running() ? Optional.empty() : Optional.ofNullable(s.exitCode());
    }

    /** The build log so far: the client's stderr. */
    public String log(Handle h) throws DockerException, InterruptedException {
        return read(h.client(), LogsParam.stderr());
    }

    /**
     * After a successful exit: the pushed image by digest, from the metadata file the client
     * printed. Anything else on stdout, or a digest that is not one, is refused.
     */
    public String pushed(Handle h, Push push) throws DockerException, InterruptedException {
        return push.byDigest(digestFrom(read(h.client(), LogsParam.stdout())));
    }

    /** The image digest in buildctl's metadata: the whole of stdout, one JSON object. */
    static String digestFrom(String stdout) {
        JsonNode meta;
        try {
            // Trailing tokens refused: stdout must be the metadata and nothing else.
            meta = JSON.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(stdout.strip());
        } catch (IOException e) {
            throw new IllegalStateException("the client did not print buildctl's metadata");
        }
        String digest = meta == null ? null : meta.path("containerimage.digest").asText(null);
        if (digest == null || !DIGEST.matcher(digest).matches()) {
            throw new IllegalStateException("buildctl's metadata names no image digest");
        }
        return digest;
    }

    private String read(String container, LogsParam which) throws DockerException, InterruptedException {
        LogStream stream = docker.logs(container, which);
        try {
            return stream.readFully();
        } finally {
            try {
                stream.close();
            } catch (IOException e) {
                // already read
            }
        }
    }
}
