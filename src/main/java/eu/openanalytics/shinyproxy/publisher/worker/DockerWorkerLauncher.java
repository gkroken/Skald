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

import eu.openanalytics.shinyproxy.publisher.worker.WorkerProfile.Launch;
import eu.openanalytics.shinyproxy.publisher.worker.WorkerProfile.Settings;
import org.mandas.docker.client.DockerClient;
import org.mandas.docker.client.DockerClient.ListContainersParam;
import org.mandas.docker.client.DockerClient.ListNetworksParam;
import org.mandas.docker.client.DockerClient.ListVolumesParam;
import org.mandas.docker.client.DockerClient.LogsParam;
import org.mandas.docker.client.DockerClient.RemoveContainerParam;
import org.mandas.docker.client.LogStream;
import org.mandas.docker.client.exceptions.DockerException;
import org.mandas.docker.client.exceptions.NotFoundException;
import org.mandas.docker.client.exceptions.VolumeNotFoundException;
import org.mandas.docker.client.messages.ContainerConfig;
import org.mandas.docker.client.messages.ContainerExit;
import org.mandas.docker.client.messages.ContainerInfo;
import org.mandas.docker.client.messages.HostConfig;
import org.mandas.docker.client.messages.NetworkConfig;
import org.mandas.docker.client.messages.Volume;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * The trusted launcher: starts one rootless BuildKit worker for one attempt, with
 * everything it needs and nothing it should not have, and disposes of all of it
 * (WORKPLAN-BUNDLES.md "Build sandbox and dependency network"; decision 6; T7 part 3).
 *
 * <p>Per attempt, every object labelled {@value #ATTEMPT_LABEL}=&lt;attempt&gt;:
 * <ul>
 *   <li>an INTERNAL network, the worker's only one: no route out except the gateway;</li>
 *   <li>the egress gateway (squid, {@link GatewayConfig}'s rule) on that network and on the
 *       operator's outer networks, where the registry and mirrors live;</li>
 *   <li>a config volume holding squid.conf and buildkitd.toml, written by a helper and
 *       mounted read-only. Files reach containers through volumes, never host paths: the
 *       platform itself runs in a container, and decision 6 forbids host bind mounts;</li>
 *   <li>the socket volume, shared only with the trusted client (decision 2026-09-26);</li>
 *   <li>the workspace: a loop-backed ext4 volume of a fixed size, mounted nosuid,nodev, made
 *       by a privileged setup container. That privilege is the launcher's (decision 6
 *       allows it); the worker never sees the device;</li>
 *   <li>the worker, launched with {@link WorkerProfile}'s fields exactly, plus the
 *       read-only-root environment, the entrypoint and the daemon's arguments the probes
 *       measured (dev/shipping_worker.py: ENVIRONMENT, SOCKET_MOUNT, ENTRYPOINT,
 *       DAEMON_ARGS). It is refused unless its daemon runs under no_new_privs.</li>
 * </ul>
 *
 * <p>A launch that fails part-way disposes of what it made before rethrowing. Disposal is
 * idempotent, never stops at the first error, and reports what it could not remove: an
 * object it reports was left behind is the caller's to escalate, not to forget.
 */
public final class DockerWorkerLauncher {

    public static final String ATTEMPT_LABEL = "eu.skald.build-attempt";
    /** The worker's uid, owner of its workspace and socket (dev/shipping_worker.py). */
    static final String WORKER_UID = "2401";
    static final String SOCKET_MOUNT = "/skald-sock";
    public static final String SOCKET_ADDRESS = "unix://" + SOCKET_MOUNT + "/bk.sock";
    static final String CONFIG_MOUNT = "/skald-conf";
    static final List<String> ENVIRONMENT = List.of("HOME=/tmp/home", "TMPDIR=/tmp",
            "XDG_RUNTIME_DIR=/tmp/run");
    /**
     * The worker's entrypoint, set by the launcher whatever the configured image's own is
     * (61635b7-F1): rootlesskit keeps newuidmap's file capability (the 2026-09-26 waiver), and
     * its child buildkitd, with runc and every RUN step under it, runs under no_new_privs. On
     * the overlayfs snapshotter that is the only thing between a setuid file in a build's
     * snapshot and its exec, because a RUN's root does not inherit the workspace's nosuid.
     * Mirrors dev/shipping_worker.py ENTRYPOINT, checked equal by DockerWorkerLauncherTest.
     */
    static final List<String> ENTRYPOINT = List.of("rootlesskit", "setpriv", "--nnp", "buildkitd");
    /**
     * Prints "&lt;comm&gt; &lt;NoNewPrivs&gt;" for every buildkitd in the worker. Run once, before
     * any build has run, so nothing untrusted can have touched what it reads.
     */
    static final List<String> DAEMON_NNP = List.of("sh", "-c", "for p in $(pidof buildkitd); do"
            + " echo \"$(cat /proc/$p/comm) $(awk '$1==\"NoNewPrivs:\"{print $2}' /proc/$p/status)\"; done");
    /**
     * buildkitd's arguments; the isolation probes run the same daemon (dev/shipping_worker.py
     * DAEMON_ARGS, checked equal by DockerWorkerLauncherTest). The snapshotter is kernel
     * overlayfs, mounted by buildkitd inside its rootless user namespace: no device (T5 began
     * with native to avoid fuse-overlayfs's /dev/fuse), no seccomp or capability change. native
     * stores every layer and step as a FULL copy of its parent, and the r-shiny base's layers
     * alone exhausted the 8 GiB workspace before the first RUN (the T7 pass probe); overlayfs
     * stores differences only. RUN steps still cannot mount (BuildKit's default seccomp, no
     * capabilities), and the upper directories stay on the nosuid,nodev workspace.
     */
    static final List<String> DAEMON_ARGS = List.of("--oci-worker-snapshotter=overlayfs", "--root",
            "/workspace/buildkit", "--addr", SOCKET_ADDRESS, "--config", CONFIG_MOUNT + "/buildkitd.toml");
    /** Where quota images live: one shared named volume, one file per attempt. */
    static final String QUOTA_IMAGES_VOLUME = "skald-quota-images";
    private static final Pattern ATTEMPT = Pattern.compile("[a-z0-9]{8,40}");
    /**
     * One of an attempt's volume names (handle(), volumeNames()), with the attempt id as group 1.
     * A test holds it to volumeNames(), so a new volume cannot drift out of the sweep's
     * by-name inventory (9159de6-F1: the output volume did).
     */
    static final Pattern ATTEMPT_VOLUME = Pattern.compile("skald-b-([a-z0-9]{8,40})-(ctx|ws|sock|conf|out)");
    /** Marks the sweep's own inventory helper, so one a dead JVM left can be found. */
    static final String SWEEP_LABEL = "eu.skald.sweep-helper";
    /**
     * A stopped sweep helper older than this is a dead process's. The helper lists a
     * directory and reads the loop devices: seconds, and well under a minute on this host's
     * slowest measured daemon (a 52.5 s container kill).
     */
    static final java.time.Duration STALE_SWEEP_HELPER = java.time.Duration.ofMinutes(10);
    static final long GATEWAY_MEMORY = 256L << 20;
    static final int REMOVAL_ATTEMPTS = 12;
    static final long REMOVAL_PAUSE_MILLIS = 5_000;
    private static final Pattern LOOP = Pattern.compile("/dev/loop[0-9]{1,4}");

    /** The images the launcher runs, each by digest: the derived worker and gateway, a
     *  small helper (busybox) and the privileged setup image (mkfs.ext4, losetup). */
    public record Images(String worker, String gateway, String helper, String setup) {

        /** The static busybox the probes use (dev/shipping_worker.py BUSYBOX_IMAGE). */
        public static final String HELPER = "busybox@sha256:"
                + "ea2b9914a16a4ac1981994af97b318f7c7d4db76b580c56177f08bf76f4a0be8";
        /** debian:12-slim, pinned: the probes' quota setup image (dev/quota_volume.py). */
        public static final String SETUP = "debian@sha256:"
                + "88200866dfff7ea7f5cbcb6ec7c8a701889efe6fe859fe64d6990e4b07ea4171";

        /** The operator-built worker and gateway with the pinned helper and setup images. */
        public static Images of(String worker, String gateway) {
            return new Images(worker, gateway, HELPER, SETUP);
        }
    }

    /**
     * Where the gateway may let the worker go. {@code gatewayDns}, when non-empty, REPLACES
     * Docker's embedded resolver for squid, so those servers must also serve the registry's
     * and mirrors' names; production passes none (f5015b6 N2).
     */
    public record Egress(List<String> outerNetworks, String registry, List<String> repos,
                         List<String> privateMirrors, List<String> gatewayDns,
                         List<String> gatewayExtraHosts) {

        public Egress {
            outerNetworks = List.copyOf(outerNetworks);
            repos = List.copyOf(repos);
            privateMirrors = List.copyOf(privateMirrors);
            gatewayDns = List.copyOf(gatewayDns);
            gatewayExtraHosts = List.copyOf(gatewayExtraHosts);
        }
    }

    /**
     * One attempt's launch. {@code output}: the attempt exports files rather than pushing an
     * image (T7b: a package's binary), so its output volume is made, owned by the worker's uid,
     * with the rest.
     */
    public record Request(String attemptId, Settings settings, int quotaMegabytes, Egress egress, boolean output) {

        /** An attempt that pushes an image: no output volume. */
        public Request(String attemptId, Settings settings, int quotaMegabytes, Egress egress) {
            this(attemptId, settings, quotaMegabytes, egress, false);
        }

        public Request {
            if (!ATTEMPT.matcher(attemptId).matches()) {
                throw new IllegalArgumentException("an attempt id is 8-40 of [a-z0-9]");
            }
            if (quotaMegabytes < 64 || quotaMegabytes > 1 << 20) {
                throw new IllegalArgumentException("quota " + quotaMegabytes + " MiB");
            }
        }
    }

    /** One launched worker, by the names of everything it owns. Opaque to callers. */
    public record Handle(String attemptId, String network, String gateway, String worker,
                         String socketVolume, String configVolume, String workspaceVolume,
                         String quotaImage, String client, String contextVolume, String outputVolume) {
    }

    private final DockerClient docker;
    private final WorkerProfile profile;
    private final Images images;

    public DockerWorkerLauncher(DockerClient docker, WorkerProfile profile, Images images) {
        this.docker = docker;
        this.profile = profile;
        this.images = images;
    }

    /**
     * A pooled worker's id (T7b, the fixed cost): "w", its launch time as 8 hex digits of epoch
     * seconds, and 24 random hex digits. Never an app build's 32 hex nor a package attempt's
     * "p" id, so neither sweeper takes one for its own; its age is in its name, so any
     * instance can tell a dead process's entry from a live one without asking the daemon.
     */
    static final Pattern POOL_ENTRY = Pattern.compile("w[0-9a-f]{32}");
    /** On a taken entry's marker: the entry's id (the attempt's is the attempt label). */
    static final String POOL_LABEL = "eu.skald.pool-entry";

    static String poolEntryId(long epochSeconds, java.util.random.RandomGenerator random) {
        StringBuilder b = new StringBuilder("w").append(String.format("%08x", epochSeconds & 0xffffffffL));
        for (int i = 0; i < 24; i++) {
            b.append(Character.forDigit(random.nextInt(16), 16));
        }
        return b.toString();
    }

    /** A pool entry's launch time, from its id. */
    static long poolEntryEpochSeconds(String entry) {
        if (!POOL_ENTRY.matcher(entry).matches()) {
            throw new IllegalArgumentException("not a pool entry: " + entry);
        }
        return Long.parseLong(entry.substring(1, 9), 16);
    }

    /** The volume that says a pool entry is an attempt's. Outside ATTEMPT_VOLUME's names. */
    static String marker(String entry) {
        return "skald-taken-" + entry;
    }

    /**
     * Makes the pooled worker {@code entry} the attempt's, before the attempt uses it: a marker
     * volume labelled with the attempt (so the attempt's sweeper finds it, and every disposal
     * of the attempt follows it to the entry) and the entry. Returns the entry's handle, by
     * whose names the attempt then stages and runs its client. Refuses an entry already taken
     * by another attempt (the marker exists with its label), and a taker that is itself a pool
     * entry (markers are followed one level only).
     */
    public Handle take(String entry, String attemptId) throws DockerException, InterruptedException {
        if (!POOL_ENTRY.matcher(entry).matches() || !ATTEMPT.matcher(attemptId).matches()
                || POOL_ENTRY.matcher(attemptId).matches()) {
            throw new IllegalArgumentException("take " + entry + " for " + attemptId);
        }
        createOwnedVolume(marker(entry), Map.of(ATTEMPT_LABEL, attemptId, POOL_LABEL, entry), null);
        return handle(entry);
    }

    /** The pool entries the attempt has taken: its markers, by label and name. */
    List<String> takenBy(Handle h) throws DockerException, InterruptedException {
        if (POOL_ENTRY.matcher(h.attemptId()).matches()) {
            return List.of();
        }
        List<String> entries = new ArrayList<>();
        List<Volume> markers = docker.listVolumes(ListVolumesParam.filter("label", ATTEMPT_LABEL + "=" + h.attemptId()),
                ListVolumesParam.filter("label", POOL_LABEL)).volumes();
        if (markers != null) {
            for (Volume v : markers) {
                String entry = v.labels() == null ? null : v.labels().get(POOL_LABEL);
                if (entry != null && POOL_ENTRY.matcher(entry).matches() && marker(entry).equals(v.name())) {
                    entries.add(entry);
                }
            }
        }
        return entries;
    }

    /** Whether a pool entry carries a marker, whoever took it. */
    boolean taken(String entry) throws DockerException, InterruptedException {
        try {
            Volume v = docker.inspectVolume(marker(entry));
            return v.labels() != null && entry.equals(v.labels().get(POOL_LABEL));
        } catch (NotFoundException | VolumeNotFoundException e) {
            return false;
        }
    }

    static Handle handle(String attemptId) {
        String p = "skald-b-" + attemptId;
        return new Handle(attemptId, p + "-net", p + "-gw", p + "-worker", p + "-sock",
                p + "-conf", p + "-ws", "skald-quota-" + attemptId + ".img", p + "-client", p + "-ctx", p + "-out");
    }

    /**
     * Starts the attempt's worker and returns once its daemon reports a worker, or throws
     * after disposing of everything it made.
     */
    public Handle launch(Request request) throws InterruptedException {
        return launch(request, new PhaseClock());
    }

    /** As above, lapping each launch phase on {@code clock} ("launch.*"). */
    Handle launch(Request request, PhaseClock clock) throws InterruptedException {
        Handle h = handle(request.attemptId());
        Map<String, String> labels = Map.of(ATTEMPT_LABEL, request.attemptId());
        // Outside the try: a refusal has made nothing, so there is nothing to dispose, and
        // disposing would tear down whatever holds the names -- which, when it carries this
        // attempt's label, is a LIVE launch of the same attempt (67b7c17-F1). A stale one is
        // the reconciliation's to sweep: it knows from the lease that the attempt is dead.
        try {
            refuseTakenNames(h);
        } catch (DockerException e) {
            throw new IllegalStateException("could not check the attempt's names: " + e.getMessage(), e);
        }
        clock.lap("launch.names");
        try {
            String squid = GatewayConfig.squidConf(request.egress().registry(),
                    request.egress().repos(), request.egress().privateMirrors(),
                    request.egress().gatewayDns());
            Launch launch = profile.launch(request.settings(), h.network(), h.workspaceVolume());

            docker.createNetwork(NetworkConfig.builder().name(h.network()).internal(true)
                    .checkDuplicate(true).labels(labels).build());
            List<String> volumes = new ArrayList<>(List.of(h.socketVolume(), h.configVolume()));
            if (request.output()) {
                volumes.add(h.outputVolume());
            }
            for (String v : volumes) {
                createOwnedVolume(v, labels, null);
            }
            clock.lap("launch.network");
            setUp(h, squid, buildkitdToml(request.egress().registry()), request.quotaMegabytes(),
                    request.output(), labels);
            clock.lap("launch.setup");
            // The gateway and the worker need only what setUp made, not each other: the worker
            // reports a worker without any request through the gateway (its first pull comes
            // with the client, after launch returns), so they start side by side.
            inParallel(() -> startGateway(h, request.egress(), labels), () -> startWorker(h, launch, labels));
            clock.lap("launch.start");
            requireDaemonNoNewPrivs(h);
            clock.lap("launch.nnp");
            return h;
        } catch (InterruptedException e) {
            dispose(h);
            throw e;
        } catch (DockerException | RuntimeException e) {
            List<String> left = dispose(h);
            throw new IllegalStateException("could not launch the worker for attempt "
                    + request.attemptId() + ": " + e.getMessage()
                    + (left.isEmpty() ? "" : "; LEFT BEHIND: " + left), e);
        }
    }

    /**
     * Refuses to launch over an object that already carries one of the attempt's names.
     * Docker's volume create is idempotent by name and returns an existing volume whatever
     * its driver and options, so a stale or foreign volume would otherwise become the
     * worker's workspace or the gateway's configuration (9a2658d-F1). Containers and the
     * network would refuse a duplicate themselves; they are checked here too, so the
     * refusal comes before anything is made.
     */
    private void refuseTakenNames(Handle h) throws DockerException, InterruptedException {
        List<String> taken = new ArrayList<>();
        for (String v : List.of(h.workspaceVolume(), h.socketVolume(), h.configVolume(),
                h.contextVolume(), h.outputVolume())) {
            try {
                docker.inspectVolume(v);
                taken.add("volume " + v);
            } catch (NotFoundException | VolumeNotFoundException e) {
                // free
            }
        }
        for (String c : List.of(h.worker(), h.gateway(), h.client())) {
            try {
                docker.inspectContainer(c);
                taken.add("container " + c);
            } catch (NotFoundException | VolumeNotFoundException e) {
                // free
            }
        }
        try {
            docker.inspectNetwork(h.network());
            taken.add("network " + h.network());
        } catch (NotFoundException | VolumeNotFoundException e) {
            // free
        }
        if (!taken.isEmpty()) {
            throw new IllegalStateException("the attempt's names are already taken, refusing to "
                    + "adopt what is there: " + taken);
        }
    }

    /**
     * Creates a volume and then checks the daemon made THIS one: our labels, and when
     * {@code options} is given, exactly those driver options. A name taken between the
     * check above and this call would otherwise be adopted silently.
     */
    void createOwnedVolume(String name, Map<String, String> labels, Map<String, String> options)
            throws DockerException, InterruptedException {
        Volume.Builder b = Volume.builder().name(name).labels(labels);
        if (options != null) {
            b.driver("local").driverOpts(options);
        }
        docker.createVolume(b.build());
        Volume made = docker.inspectVolume(name);
        Map<String, String> got = made.labels() == null ? Map.of() : made.labels();
        if (!labels.entrySet().stream().allMatch(e -> e.getValue().equals(got.get(e.getKey())))
                || (options != null && !options.equals(made.options()))) {
            throw new IllegalStateException("volume " + name + " is not the one this launch made");
        }
    }

    /** buildkitd.toml: the registry speaks plain HTTP, reached only through the gateway. */
    static String buildkitdToml(String registry) {
        return "[registry.\"" + registry + ":5000\"]\n  http = true\n";
    }

    private void startGateway(Handle h, Egress egress, Map<String, String> labels)
            throws DockerException, InterruptedException {
        // Hardened (9a2658d N1): squid parses every request build code sends and sits on the
        // operator's outer networks. It runs as its own user already; it needs nothing
        // writable but /tmp (measured: with pid_filename none, stdio logs and no cache_dir it
        // starts read-only, all capabilities dropped, under no-new-privileges).
        HostConfig.Builder hc = HostConfig.builder()
                .networkMode(h.network())
                .binds(h.configVolume() + ":" + CONFIG_MOUNT + ":ro")
                .readonlyRootfs(true)
                .tmpfs(Map.of("/tmp", "rw,noexec,nosuid,nodev,size=16m"))
                .capDrop("ALL")
                .securityOpt("no-new-privileges")
                .memory(GATEWAY_MEMORY)
                .memorySwap(GATEWAY_MEMORY)
                .pidsLimit(128)
                .nanoCpus(1_000_000_000L)
                .privileged(false);
        if (!egress.gatewayExtraHosts().isEmpty()) {
            hc.extraHosts(egress.gatewayExtraHosts());
        }
        docker.createContainer(ContainerConfig.builder().image(images.gateway())
                .cmd("squid", "-N", "-f", CONFIG_MOUNT + "/squid.conf")
                .labels(labels).hostConfig(hc.build()).build(), h.gateway());
        for (String outer : egress.outerNetworks()) {
            docker.connectToNetwork(h.gateway(), outer);
        }
        docker.startContainer(h.gateway());
        waitForLog(h.gateway(), "Accepting HTTP", 30);
    }

    /**
     * The workspace filesystem: ext4 without a journal, mounted without write barriers.
     * Measured on the T7 pass probe's host, an fsync on the loop device cost about 460 ms (300
     * fsynced files: 138 s) because every flush reaches the backing file's own disk, and an
     * image extraction syncs file by file: a cold python base did not finish its second layer
     * inside the 20-minute deadline. Without the journal and barriers the same 300 files took
     * 0.24 s. Nothing on it needs to survive a crash: it is one attempt's scratch, an attempt
     * does not outlive its host, and reconciliation disposes of what a dead one left. The
     * quota (the filesystem's size), nosuid and nodev are unchanged.
     */
    static final String WORKSPACE_MKFS = "mkfs.ext4 -q -F -O ^has_journal";
    static final String WORKSPACE_MOUNT = "nosuid,nodev,barrier=0";

    /**
     * The attempt's one setup helper (T7b, the fixed cost: each container costs 3-4 s on the
     * measured host, and four helpers ran here one after another). Privileged, as the quota's
     * losetup needs; network none; nothing in it is interpreted: the two configuration texts
     * travel as environment values of a fixed command and are written with printf %s, as
     * before, and both are the platform's own (GatewayConfig from the deployment's egress).
     * It writes squid.conf and buildkitd.toml, gives the socket volume (and the output
     * volume, when asked) to the worker's uid, and makes the workspace: an image file of the
     * quota's size, ext4 whose root directory the worker's uid owns from mkfs on (so no chown
     * of the mounted workspace), attached to a loop device it prints last.
     */
    private void setUp(Handle h, String squid, String toml, int megabytes, boolean output,
                       Map<String, String> labels) throws DockerException, InterruptedException {
        try {
            docker.inspectVolume(QUOTA_IMAGES_VOLUME);
        } catch (NotFoundException | VolumeNotFoundException e) {
            // mandas reports a missing volume as VolumeNotFoundException, which is NOT a
            // NotFoundException (it extends DockerException directly).
            docker.createVolume(Volume.builder().name(QUOTA_IMAGES_VOLUME).build());
        }
        List<String> binds = new ArrayList<>(List.of(h.configVolume() + ":/c", h.socketVolume() + ":/s",
                QUOTA_IMAGES_VOLUME + ":/q"));
        if (output) {
            binds.add(h.outputVolume() + ":/o");
        }
        String owner = WORKER_UID + ":" + WORKER_UID;
        String out = runHelper("setup-" + h.attemptId(), images.setup(), true, binds,
                List.of("SQUID_CONF=" + squid, "BUILDKITD_TOML=" + toml),
                List.of("sh", "-c", "printf %s \"$SQUID_CONF\" > /c/squid.conf"
                        + " && printf %s \"$BUILDKITD_TOML\" > /c/buildkitd.toml"
                        + " && chmod 0644 /c/squid.conf /c/buildkitd.toml"
                        + " && chown " + owner + " /s" + (output ? " /o" : "")
                        + " && truncate -s " + megabytes + "M /q/" + h.quotaImage()
                        + " && " + WORKSPACE_MKFS + " -E root_owner=" + owner + " /q/" + h.quotaImage()
                        + " && losetup --find --show /q/" + h.quotaImage()), labels);
        String loop = out.strip().lines().reduce((a, b) -> b).orElse("");
        if (!LOOP.matcher(loop).matches()) {
            throw new IllegalStateException("the quota image did not attach: " + out.strip());
        }
        Map<String, String> volumeLabels = new LinkedHashMap<>(labels);
        volumeLabels.put("eu.skald.loop-device", loop);
        createOwnedVolume(h.workspaceVolume(), volumeLabels,
                Map.of("type", "ext4", "device", loop, "o", WORKSPACE_MOUNT));
    }

    /**
     * Runs {@code side} on its own thread and {@code main} on this one, and returns only once
     * BOTH have ended, whatever either did: a Docker call ignores an interrupt, and a create
     * landing after the caller's disposal would make Docker re-create a volume unlabelled
     * (0c71146-F1). An interrupt of this thread is passed to the side thread and restored
     * afterwards. The first failure propagates, main's before side's, the other suppressed.
     */
    static void inParallel(Step side, Step main) throws DockerException, InterruptedException {
        AtomicReference<Throwable> sideFailed = new AtomicReference<>();
        Thread t = new Thread(() -> {
            try {
                side.run();
            } catch (Throwable e) {
                sideFailed.set(e);
            }
        }, Thread.currentThread().getName() + "-side");
        t.setDaemon(true);
        t.start();
        Throwable failed = null;
        try {
            main.run();
        } catch (Throwable e) {
            failed = e;
        }
        boolean interrupted = failed instanceof InterruptedException;
        if (interrupted) {
            t.interrupt();
        }
        while (true) {
            try {
                t.join();
                break;
            } catch (InterruptedException e) {
                interrupted = true;
                t.interrupt();
            }
        }
        if (interrupted && !(failed instanceof InterruptedException)) {
            Thread.currentThread().interrupt();
        }
        Throwable other = sideFailed.get();
        if (failed == null) {
            failed = other;
        } else if (other != null) {
            failed.addSuppressed(other);
        }
        if (failed == null) {
            return;
        }
        if (failed instanceof DockerException d) {
            throw d;
        }
        if (failed instanceof InterruptedException i) {
            throw i;
        }
        if (failed instanceof RuntimeException r) {
            throw r;
        }
        if (failed instanceof Error e) {
            throw e;
        }
        throw new IllegalStateException(failed);
    }

    /**
     * Refuses a client exporting files unless the attempt's output volume is the one its
     * launch made (Request.output): labelled for this attempt, and still empty is the
     * launch's guarantee, since the launch refused any volume already under its name.
     */
    public void requireOutput(Handle h) throws DockerException, InterruptedException {
        Volume v;
        try {
            v = docker.inspectVolume(h.outputVolume());
        } catch (NotFoundException | VolumeNotFoundException e) {
            throw new IllegalStateException("the attempt was launched without an output volume");
        }
        if (!ours(v.labels(), h)) {
            throw new IllegalStateException("volume " + h.outputVolume() + " is not this attempt's");
        }
    }

    /** The attempt's egress gateway as a forward proxy, by name: the daemon's, for pulls. */
    static String proxy(Handle h) {
        return "http://" + h.gateway() + ":" + GatewayConfig.PORT;
    }

    private static final java.util.regex.Pattern IPV4 =
            java.util.regex.Pattern.compile("(?:25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])(?:\\.(?:25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])){3}");

    /**
     * The gateway as a forward proxy for RUN steps: by its address on the attempt's network,
     * not its name. BuildKit writes a RUN step's resolv.conf without the daemon's localhost
     * resolver (Docker's embedded DNS), so the name did not resolve in a step (the T7 pass
     * probe); by address a step needs no DNS at all, and the gateway resolves the mirror. The
     * container must be the attempt's own (labels), on the attempt's network.
     */
    public String gatewayProxy(Handle h) throws DockerException, InterruptedException {
        ContainerInfo gw = docker.inspectContainer(h.gateway());
        if (!ours(gw.config().labels(), h)) {
            throw new IllegalStateException("container " + h.gateway() + " is not this attempt's");
        }
        var networks = gw.networkSettings() == null ? null : gw.networkSettings().networks();
        var attached = networks == null ? null : networks.get(h.network());
        String ip = attached == null ? null : attached.ipAddress();
        if (ip == null || !IPV4.matcher(ip).matches()) {
            throw new IllegalStateException("the gateway has no IPv4 address on " + h.network() + ": " + ip);
        }
        return "http://" + ip + ":" + GatewayConfig.PORT;
    }

    private void startWorker(Handle h, Launch launch, Map<String, String> labels)
            throws DockerException, InterruptedException {
        List<String> binds = new ArrayList<>(launch.binds());
        binds.add(h.socketVolume() + ":" + SOCKET_MOUNT);
        binds.add(h.configVolume() + ":" + CONFIG_MOUNT + ":ro");
        List<String> env = new ArrayList<>(launch.env());
        env.addAll(ENVIRONMENT);
        String proxy = proxy(h);
        env.add("http_proxy=" + proxy);
        env.add("HTTP_PROXY=" + proxy);
        HostConfig hc = HostConfig.builder()
                .nanoCpus(launch.nanoCpus())
                .memory(launch.memory())
                .memorySwap(launch.memorySwap())
                .pidsLimit(launch.pidsLimit())
                .readonlyRootfs(launch.readOnlyRootfs())
                .tmpfs(launch.tmpfs())
                .securityOpt(launch.securityOpt())
                .networkMode(launch.networkMode())
                .binds(binds)
                .privileged(false)
                .build();
        docker.createContainer(ContainerConfig.builder().image(images.worker())
                .entrypoint(ENTRYPOINT).cmd(DAEMON_ARGS).env(env).labels(labels).hostConfig(hc).build(), h.worker());
        docker.startContainer(h.worker());
        // 120 s: a 60 s wait was missed once under load (the daemon stalled after its fsverity
        // check and reported a worker only later); a healthy start takes a few seconds.
        waitForLog(h.worker(), "found worker", 120);
    }

    /**
     * Refuses the attempt unless every buildkitd in the worker runs under no_new_privs. The
     * entrypoint sets it, but an image whose setpriv is missing or wrapped, or a future change
     * to either, would otherwise run every RUN step with neither nosuid nor no_new_privs, and
     * say nothing (61635b7-F1). The daemon has reported a worker, so it is running.
     */
    private void requireDaemonNoNewPrivs(Handle h) throws DockerException, InterruptedException {
        String id = docker.execCreate(h.worker(), DAEMON_NNP.toArray(String[]::new),
                DockerClient.ExecCreateParam.attachStdout(), DockerClient.ExecCreateParam.attachStderr()).id();
        LogStream stream = docker.execStart(id);
        String out;
        try {
            out = stream.readFully();
        } finally {
            try {
                stream.close();
            } catch (IOException e) {
                // the output is already read; a failed close leaks nothing of ours
            }
        }
        List<String> lines = out.strip().lines().toList();
        if (lines.isEmpty() || !lines.stream().allMatch("buildkitd 1"::equals)) {
            throw new IllegalStateException("the worker's daemon does not run under no_new_privs, so RUN steps"
                    + " would honour setuid files; refusing it (expected 'buildkitd 1' for every buildkitd): "
                    + tail(out));
        }
    }

    /**
     * Removes everything the attempt owns, in dependency order, and returns what is still
     * there afterwards (empty when clean). Never throws for a missing object; every step is
     * attempted whatever an earlier one did.
     */
    public List<String> dispose(Handle h) throws InterruptedException {
        return dispose(h, new PhaseClock());
    }

    /** As above, lapping each disposal phase on {@code clock} ("dispose.*"). */
    List<String> dispose(Handle h, PhaseClock clock) throws InterruptedException {
        // A pool entry the attempt took is disposed of first, with everything the attempt made
        // under its names; its marker goes only once the entry is gone, so a disposal that
        // left something is found again by the attempt's sweeper.
        List<String> pooled = new ArrayList<>();
        boolean tookAny = false;
        try {
            for (String entry : takenBy(h)) {
                tookAny = true;
                List<String> left = dispose(handle(entry), new PhaseClock());
                if (left.isEmpty()) {
                    quietly(pooled, () -> retrying(() -> docker.removeVolume(marker(entry))));
                } else {
                    pooled.add("pool entry " + entry + ": " + left);
                }
            }
        } catch (DockerException e) {
            pooled.add("could not list the attempt's pool entries: " + e.getMessage());
        }
        clock.lap("dispose.pool");
        List<String> own = disposeOwn(h, clock, tookAny && !hasNetwork(h));
        List<String> all = new ArrayList<>(pooled);
        all.addAll(own);
        return all;
    }

    /** Whether the attempt's own network exists (any labels): the first thing a launch makes. */
    private boolean hasNetwork(Handle h) throws InterruptedException {
        try {
            docker.inspectNetwork(h.network());
            return true;
        } catch (DockerException e) {
            // Unsure is "yes": the cleanup then runs, as it always did.
            return !(e instanceof NotFoundException
                    || e instanceof org.mandas.docker.client.exceptions.NetworkNotFoundException);
        }
    }

    /**
     * {@code noOwnLaunch}: the attempt took a pool entry and has no network of its own. A launch
     * makes the network before its setup helper makes the quota image, and a disposal removes
     * the network only after its cleanup, so such an attempt has no quota image or device:
     * the cleanup helper (3-4 s of privileged container) is skipped and the quota state is
     * empty. Only for an attempt that took an entry: the drivers take OR launch, never both.
     */
    private List<String> disposeOwn(Handle h, PhaseClock clock, boolean noOwnLaunch) throws InterruptedException {
        // Only what carries THIS attempt's label is removed: an object that merely has one
        // of its names (a refused launch over a foreign volume, 9a2658d-F1) is not ours.
        // Also a volume the daemon made under one of the attempt's names for a bind (no labels,
        // the local driver, no options): a work thread whose call was still in flight when
        // the volume was removed re-creates it that way, beyond any label (2fc53d9-F1).
        List<String> errors = new ArrayList<>(removeContainers(h));
        clock.lap("dispose.containers");
        for (String v : volumeNames(h)) {
            quietly(errors, () -> {
                Volume found = docker.inspectVolume(v);
                if (ours(found.labels(), h) || madeForABind(found)) {
                    retrying(() -> docker.removeVolume(v));
                }
            });
        }
        clock.lap("dispose.volumes");
        // Detach EVERY device still backed by this attempt's image, found by its backing file
        // as leftovers() finds it -- not through the volume's label, which is missing when
        // the launch failed before the volume existed (9a2658d-F2). The file-name match is the
        // guard against a reused device number (dev/quota_volume.py). Then delete the image.
        // The same helper then reports what is still attached or present (QUOTA_STATE's
        // command, after a marker), so no second privileged helper runs to check it.
        String image = h.quotaImage();
        AtomicReference<String> quotaState = new AtomicReference<>(noOwnLaunch ? "" : null);
        quietly(errors, () -> {
            if (noOwnLaunch) {
                return;
            }
            String out = runHelper("cleanup-" + h.attemptId(), images.setup(), true,
                    List.of(QUOTA_IMAGES_VOLUME + ":/s"), List.of(),
                    List.of("sh", "-c", "for f in /sys/block/loop*/loop/backing_file; do"
                            + " case \"$(cat \"$f\" 2>/dev/null)\" in"
                            + " */" + image + "|*/" + image + "\" (deleted)\")"
                            + " d=${f#/sys/block/}; losetup -d /dev/${d%%/*} ;; esac; done;"
                            + " rm -f /s/" + image + "; echo " + STATE_MARKER + "; " + quotaState(h)),
                    Map.of(ATTEMPT_LABEL, h.attemptId()));
            int at = out.indexOf(STATE_MARKER + "\n");
            if (at >= 0) {
                quotaState.set(out.substring(at + STATE_MARKER.length() + 1));
            }
        });
        clock.lap("dispose.loop");
        quietly(errors, () -> {
            if (ours(docker.inspectNetwork(h.network()).labels(), h)) {
                docker.removeNetwork(h.network());
            }
        });
        clock.lap("dispose.network");
        List<String> left = leftovers(h, quotaState.get());
        clock.lap("dispose.check");
        left.addAll(errors.stream().filter(e -> !e.contains("No such") && !e.contains("not found"))
                .map(e -> "error: " + e).toList());
        return left;
    }

    /**
     * Removes every container of the attempt (label-checked) and nothing else: every process
     * of the attempt, untrusted code included, ends here. Volumes, the network and the loop
     * device are left in place, so a caller that may race the attempt's own work thread can
     * stop all code without removing a volume that thread might still bind (Docker would
     * re-create a missing named volume for a bind, UNLABELLED: 0c71146-F1. dispose() now
     * removes such a volume too, but only a disposal after that thread's last call can be
     * sure there is none to come, 2fc53d9-F1). Returns the errors met.
     */
    public List<String> removeContainers(Handle h) throws InterruptedException {
        List<String> errors = java.util.Collections.synchronizedList(new ArrayList<>());
        try {
            for (String entry : takenBy(h)) {
                errors.addAll(removeContainers(handle(entry)));
            }
        } catch (DockerException e) {
            errors.add("could not list the attempt's pool entries: " + e.getMessage());
        }
        // The worker first: it runs the untrusted code. In dispose() volumes go only after
        // every container, so the client holding the socket and context volumes may go after
        // it. Each removal on its own thread (T7b, the fixed cost: one after another they took
        // 4.6 s), the worker's started first, and this returns only once every one has ended.
        // Every helper name too, dispose()'s own cleanup and check helpers included: a process
        // killed while one ran leaves it behind, and its fixed name would then refuse every
        // later disposal's helper ("already exists"), so the loop device could never be
        // detached (found in 3d-2a, after a JUnit timeout killed the JVM mid-disposal).
        List<Thread> removals = new ArrayList<>();
        // Named after the calling thread, so each removal reads as that thread's own call.
        String caller = Thread.currentThread().getName();
        for (String c : List.of(h.worker(), h.client(), BuildKitClient.stageHelper(h), BuildKitClient.warmClient(h),
                h.gateway(),
                "skald-helper-cleanup-" + h.attemptId(), "skald-helper-check-" + h.attemptId(),
                "skald-helper-setup-" + h.attemptId(),
                // the helpers of launches before the setup helper, a dead process's included
                "skald-helper-conf-" + h.attemptId(),
                "skald-helper-quota-" + h.attemptId(), "skald-helper-chown-" + h.attemptId(),
                "skald-helper-outchown-" + h.attemptId())) {
            Thread t = new Thread(() -> {
                try {
                    quietly(errors, () -> {
                        if (ours(docker.inspectContainer(c).config().labels(), h)) {
                            retrying(() -> docker.removeContainer(c, RemoveContainerParam.forceKill(),
                                    RemoveContainerParam.removeVolumes()));
                        }
                    });
                } catch (InterruptedException e) {
                    errors.add("interrupted removing " + c);
                }
            }, caller + "-remove-" + c);
            t.setDaemon(true);
            t.start();
            removals.add(t);
        }
        // As inParallel: every removal is waited for; an interrupt is passed on and restored.
        boolean interrupted = false;
        for (Thread t : removals) {
            while (true) {
                try {
                    t.join();
                    break;
                } catch (InterruptedException e) {
                    interrupted = true;
                    removals.forEach(Thread::interrupt);
                }
            }
        }
        if (interrupted) {
            throw new InterruptedException("interrupted while the attempt's containers were removed");
        }
        return new ArrayList<>(errors);
    }

    /**
     * The launched attempt's side of its provenance record: the isolation profile
     * ({@link WorkerProfile#identity()}) and the worker and gateway images, each by the
     * reference configured AND the image id its container actually runs (a tag that moves
     * later does not change what this attempt ran). Inspects the attempt's own containers,
     * by name and label; one that is not the attempt's is a refusal.
     */
    public Map<String, Object> provenance(Handle h) throws DockerException, InterruptedException {
        Map<String, Object> ran = new LinkedHashMap<>();
        for (String[] c : new String[][] {{"worker", h.worker(), images.worker()},
                {"gateway", h.gateway(), images.gateway()}}) {
            ContainerInfo info = docker.inspectContainer(c[1]);
            if (!ours(info.config().labels(), h)) {
                throw new IllegalStateException("container " + c[1] + " is not this attempt's");
            }
            Map<String, Object> image = new LinkedHashMap<>();
            image.put("reference", c[2]);
            image.put("id", info.image());
            ran.put(c[0], image);
        }
        Map<String, Object> worker = new LinkedHashMap<>();
        worker.put("profile", profile.identity());
        worker.put("images", ran);
        return worker;
    }

    /**
     * SIGKILLs the attempt's worker if it is ours: one call, so a stop ends the untrusted
     * code before {@link #dispose} walks everything else (measured under load: a full
     * removal pass reached the worker 15 s after a stop, against a 10 s bound). Returns
     * what went wrong, if anything; a worker that is gone or not running is not an error.
     */
    public List<String> killWorker(Handle h) throws InterruptedException {
        List<String> errors = new ArrayList<>();
        try {
            for (String entry : takenBy(h)) {
                errors.addAll(killWorker(handle(entry)));
            }
        } catch (DockerException e) {
            errors.add("could not list the attempt's pool entries: " + e.getMessage());
        }
        quietly(errors, () -> {
            var info = docker.inspectContainer(h.worker());
            if (ours(info.config().labels(), h) && info.state().running()) {
                docker.killContainer(h.worker());
            }
        });
        return errors.stream().filter(e -> !e.contains("is not running")).toList();
    }

    /**
     * Runs a removal, retrying for up to a minute while the daemon answers with anything
     * but "not found". Measured: a force-remove of a worker whose process was stuck returned
     * HTTP 500 while the kill was still in flight; the container exited (137) shortly
     * after, but the disposal had already given up, and its three volumes stayed "in use".
     */
    private static void retrying(Step removal) throws Exception {
        for (int attempt = 1; ; attempt++) {
            try {
                removal.run();
                return;
            } catch (NotFoundException | VolumeNotFoundException e) {
                return;
            } catch (DockerException e) {
                if (attempt >= REMOVAL_ATTEMPTS) {
                    throw e;
                }
                Thread.sleep(REMOVAL_PAUSE_MILLIS);
            }
        }
    }

    private static boolean ours(Map<String, String> labels, Handle h) {
        return labels != null && h.attemptId().equals(labels.get(ATTEMPT_LABEL));
    }

    static List<String> volumeNames(Handle h) {
        return List.of(h.contextVolume(), h.workspaceVolume(), h.socketVolume(), h.configVolume(), h.outputVolume());
    }

    /**
     * A volume as the daemon makes one for a bind of a name that does not exist: no labels,
     * the local driver, no options. Every volume the launcher creates carries the attempt
     * label, so under one of an attempt's names (a random build id) this is a late bind's
     * re-creation, not something an operator made (2fc53d9-F1). A volume with options or
     * labels (a stale or foreign one, 9a2658d-F1) is never taken for one.
     */
    private static boolean madeForABind(Volume v) {
        return (v.labels() == null || v.labels().isEmpty()) && "local".equals(v.driver())
                && (v.options() == null || v.options().isEmpty());
    }

    /** The attempt's volumes the daemon re-created for a late bind (see madeForABind). */
    private List<String> madeForABind(Handle h) throws DockerException, InterruptedException {
        List<String> found = new ArrayList<>();
        for (String v : volumeNames(h)) {
            try {
                if (madeForABind(docker.inspectVolume(v))) {
                    found.add(v);
                }
            } catch (NotFoundException | VolumeNotFoundException e) {
                // gone
            }
        }
        return found;
    }

    /**
     * Every attempt id with something on this host: a container, volume or network carrying
     * the attempt label, a volume the daemon re-created under one of its names for a late
     * bind, a quota image on {@value #QUOTA_IMAGES_VOLUME}, or a loop device backed by one.
     * The reconciliation sweep's inventory: what it finds here and the database no longer
     * counts as unsettled, it disposes of.
     */
    public java.util.Set<String> attemptsPresent() throws InterruptedException, DockerException {
        return attemptsPresent(STALE_SWEEP_HELPER);
    }

    /** As above, taking a stopped sweep helper older than {@code staleAfter} for a dead one. */
    java.util.Set<String> attemptsPresent(java.time.Duration staleAfter) throws InterruptedException, DockerException {
        java.util.Set<String> ids = new java.util.TreeSet<>();
        docker.listContainers(ListContainersParam.allContainers(), ListContainersParam.withLabel(ATTEMPT_LABEL))
                .forEach(c -> add(ids, c.labels()));
        List<Volume> volumes = docker.listVolumes(ListVolumesParam.filter("label", ATTEMPT_LABEL)).volumes();
        if (volumes != null) {
            volumes.forEach(v -> add(ids, v.labels()));
        }
        docker.listNetworks(ListNetworksParam.withLabel(ATTEMPT_LABEL)).forEach(n -> add(ids, n.labels()));
        // By NAME too: a volume the daemon re-created for a late bind carries no label
        // (2fc53d9-F1), and after a crash it may be all that is left of the attempt.
        List<Volume> all = docker.listVolumes().volumes();
        if (all != null) {
            for (Volume v : all) {
                java.util.regex.Matcher n = ATTEMPT_VOLUME.matcher(v.name());
                if (n.matches() && madeForABind(handle(n.group(1))).contains(v.name())) {
                    ids.add(n.group(1));
                }
            }
        }
        // A sweep helper whose JVM died while it ran is left stopped, privileged and under a
        // name nothing else knows: removed here, before this sweep makes its own (2fc53d9 N1).
        // Another instance's sweep in progress has its helper running, or "created" before
        // its start and "exited" before its logs are read (e2d6480 N1): only a stopped one
        // older than any sweep could still be using it is taken for a dead one.
        long staleBefore = java.time.Instant.now().minus(staleAfter).getEpochSecond();
        for (var c : docker.listContainers(ListContainersParam.allContainers(),
                ListContainersParam.withLabel(SWEEP_LABEL))) {
            if (!"running".equals(c.state()) && c.created() != null && c.created() <= staleBefore) {
                // A failure here does not stop this sweep; the next one tries again.
                quietly(new ArrayList<>(), () -> retrying(() -> docker.removeContainer(c.id(),
                        RemoveContainerParam.forceKill())));
            }
        }
        // Quota images and the loop devices backed by them: a launch that died between losetup
        // and its labelled volume leaves only these.
        String sweep = Long.toHexString(System.nanoTime());
        String out = runHelper("sweep-" + sweep, images.setup(), true, List.of(QUOTA_IMAGES_VOLUME + ":/s"), List.of(),
                List.of("sh", "-c", "ls /s; cat /sys/block/loop*/loop/backing_file 2>/dev/null; true"),
                Map.of(SWEEP_LABEL, "true"));
        java.util.regex.Matcher m = Pattern.compile("skald-quota-([a-z0-9]{8,40})\\.img").matcher(out);
        while (m.find()) {
            ids.add(m.group(1));
        }
        return ids;
    }

    private static void add(java.util.Set<String> ids, Map<String, String> labels) {
        String id = labels == null ? null : labels.get(ATTEMPT_LABEL);
        if (id != null && ATTEMPT.matcher(id).matches()) {
            ids.add(id);
        }
    }

    /**
     * What still exists for this attempt: containers, volumes (labelled, or re-created by the
     * daemon for a late bind), network, attached image.
     */
    public List<String> leftovers(Handle h) throws InterruptedException {
        return leftovers(h, null);
    }

    /** Ends what the cleanup prints of its own work, before what is left. */
    static final String STATE_MARKER = "--skald-quota-state--";

    /**
     * The shell that prints this attempt's quota state: every loop device still backed by its
     * image (by the backing file's sysfs path) and the image itself, if present.
     */
    private static String quotaState(Handle h) {
        return "grep -l '" + h.quotaImage() + "' /sys/block/loop*/loop/backing_file"
                + " 2>/dev/null; ls /s/" + h.quotaImage() + " 2>/dev/null; true";
    }

    /**
     * As above; {@code quotaState} is what quotaState's command printed, just now, in a helper
     * this caller ran (dispose's cleanup), or null to run the check helper for it.
     */
    private List<String> leftovers(Handle h, String quotaState) throws InterruptedException {
        List<String> left = new ArrayList<>();
        try {
            for (String entry : takenBy(h)) {
                left.add("marker " + marker(entry));
                leftovers(handle(entry), null).forEach(l -> left.add("pool entry " + entry + ": " + l));
            }
            docker.listContainers(ListContainersParam.allContainers(),
                    ListContainersParam.withLabel(ATTEMPT_LABEL, h.attemptId()))
                    .forEach(c -> left.add("container " + c.names()));
            List<Volume> volumes = docker.listVolumes(ListVolumesParam.filter("label",
                    ATTEMPT_LABEL + "=" + h.attemptId())).volumes();
            if (volumes != null) {
                volumes.forEach(v -> left.add("volume " + v.name()));
            }
            madeForABind(h).forEach(v -> left.add("volume " + v + " (unlabelled, made by the daemon for a bind)"));
            docker.listNetworks(ListNetworksParam.withLabel(ATTEMPT_LABEL, h.attemptId()))
                    .forEach(n -> left.add("network " + n.name()));
            String attached = quotaState != null ? quotaState : runHelper("check-" + h.attemptId(),
                    images.setup(), true, List.of(QUOTA_IMAGES_VOLUME + ":/s"), List.of(),
                    List.of("sh", "-c", quotaState(h)), Map.of(ATTEMPT_LABEL, h.attemptId()));
            attached.strip().lines().filter(l -> !l.isBlank()).forEach(l -> left.add("quota " + l));
        } catch (DockerException e) {
            left.add("could not list: " + e.getMessage());
        }
        return left;
    }

    /** Runs a one-shot container to completion, removes it, and returns its output. */
    private String runHelper(String name, String image, boolean privileged, List<String> binds,
                             List<String> env, List<String> cmd, Map<String, String> labels)
            throws DockerException, InterruptedException {
        String container = "skald-helper-" + name;
        docker.createContainer(ContainerConfig.builder().image(image).cmd(cmd).env(env)
                .labels(labels).hostConfig(HostConfig.builder().binds(binds)
                        .networkMode("none").privileged(privileged).build()).build(), container);
        Throwable failed = null;
        try {
            docker.startContainer(container);
            ContainerExit exit = docker.waitContainer(container);
            String out = logs(container);
            if (exit.statusCode() != 0) {
                throw new IllegalStateException(name + " exited " + exit.statusCode() + ": " + out.strip());
            }
            return out;
        } catch (DockerException | InterruptedException | RuntimeException e) {
            failed = e;
            throw e;
        } finally {
            // Through retrying(): measured, the daemon can answer "is still running" to a
            // force-remove issued right after the helper's wait returned (e4306c6 review N2).
            // After a stop, the work thread's disposal is the only pass, so a removal that
            // gives up on the first answer leaves the helper -- whose fixed name then refuses
            // the next helper of the same attempt. If the helper itself failed, that failure
            // is what propagates, with a failed removal attached to it (650f18c review N1).
            // An interrupt (a sweeper stopping, the process shutting down) does not stop the
            // removal: the helper is privileged and ours, and an interrupted removal left one
            // behind (the T7 pass probe's restart row). The flag is restored afterwards.
            boolean interrupted = Thread.interrupted();
            try {
                retrying(() -> docker.removeContainer(container, RemoveContainerParam.forceKill()));
            } catch (Exception e) {
                if (failed != null) {
                    failed.addSuppressed(e);
                } else if (e instanceof DockerException d) {
                    throw d;
                } else if (e instanceof InterruptedException i) {
                    throw i;
                } else if (e instanceof RuntimeException r) {
                    throw r;
                } else {
                    throw new IllegalStateException(e);
                }
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private void waitForLog(String container, String marker, int seconds)
            throws DockerException, InterruptedException {
        String seen = "";
        for (int i = 0; i < seconds * 2; i++) {
            seen = logs(container);
            if (seen.contains(marker)) {
                return;
            }
            if (!Boolean.TRUE.equals(docker.inspectContainer(container).state().running())) {
                break;
            }
            Thread.sleep(500);
        }
        throw new IllegalStateException(container + " did not report '" + marker + "': "
                + tail(seen));
    }

    /** A container's whole output so far, stdout and stderr. */
    String logs(String container) throws DockerException, InterruptedException {
        LogStream stream = docker.logs(container, LogsParam.stdout(), LogsParam.stderr());
        try {
            return stream.readFully();
        } finally {
            try {
                stream.close();
            } catch (IOException e) {
                // the output is already read; a failed close leaks nothing of ours
            }
        }
    }

    private static String tail(String s) {
        String t = s.strip();
        return t.length() > 400 ? t.substring(t.length() - 400) : t;
    }

    interface Step {
        void run() throws Exception;
    }

    private static void quietly(List<String> errors, Step step) throws InterruptedException {
        try {
            step.run();
        } catch (InterruptedException e) {
            throw e;
        } catch (NotFoundException | VolumeNotFoundException e) {
            // already gone
        } catch (Exception e) {
            errors.add(String.valueOf(e.getMessage()));
        }
    }
}
