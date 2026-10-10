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
import eu.openanalytics.shinyproxy.publisher.worker.DockerWorkerLauncher.Egress;
import eu.openanalytics.shinyproxy.publisher.worker.DockerWorkerLauncher.Handle;
import eu.openanalytics.shinyproxy.publisher.worker.DockerWorkerLauncher.Images;
import eu.openanalytics.shinyproxy.publisher.worker.DockerWorkerLauncher.Request;
import eu.openanalytics.shinyproxy.publisher.worker.WorkerProfile.Settings;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mandas.docker.client.DockerClient;
import org.mandas.docker.client.DockerClient.ExecCreateParam;
import org.mandas.docker.client.builder.jersey.JerseyDockerClientBuilder;
import org.mandas.docker.client.messages.ContainerInfo;
import org.mandas.docker.client.messages.HostConfig;
import org.mandas.docker.client.messages.Volume;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The launcher against the real Docker daemon: the worker it starts is the measured one,
 * field by field as the daemon records it, and nothing of an attempt survives disposal.
 * `make test` builds the worker and gateway images from images/ first.
 */
public class DockerWorkerLauncherTest {

    private static final String WORKER = "skald-buildkit-worker:test";
    private static final String GATEWAY = "skald-egress-gateway:test";
    private static DockerClient docker;
    private static DockerWorkerLauncher launcher;

    @BeforeAll
    public static void connect() throws Exception {
        docker = new JerseyDockerClientBuilder().fromEnv().build();
        for (String image : List.of(WORKER, GATEWAY, Images.HELPER, Images.SETUP)) {
            assertNotNull(docker.inspectImage(image), image + " is missing; run `make test`");
        }
        launcher = new DockerWorkerLauncher(docker, WorkerProfile.load("runc-rootless"),
                Images.of(WORKER, GATEWAY));
    }

    @AfterAll
    public static void close() {
        if (docker != null) {
            docker.close();
        }
    }

    private static Request request(String attempt) {
        return new Request(attempt, Settings.defaults(), 256,
                new Egress(List.of(), "registry", List.of("pypi.org"), List.of("forge"), List.of(), List.of()));
    }

    private static String attempt() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    @Test
    public void theLaunchedWorkerIsTheMeasuredOneAndDisposalLeavesNothing() throws Exception {
        String id = attempt();
        Handle h = launcher.launch(request(id));
        try {
            ContainerInfo worker = docker.inspectContainer(h.worker());
            assertTrue(worker.state().running(), "the worker is running");
            HostConfig hc = worker.hostConfig();
            // The record of what the docker CLI set for the profile at these values
            // (18553b8 N2: Privileged and CapAdd included).
            JsonNode rec = new ObjectMapper().readTree(Path.of("dev/fixtures/worker/cli-hostconfig.json").toFile())
                    .path("host_config");
            assertEquals(rec.path("NanoCpus").asLong(), hc.nanoCpus());
            assertEquals(rec.path("Memory").asLong(), hc.memory());
            assertEquals(rec.path("MemorySwap").asLong(), hc.memorySwap());
            assertEquals(rec.path("PidsLimit").asInt(), hc.pidsLimit());
            assertEquals(rec.path("ReadonlyRootfs").asBoolean(), hc.readonlyRootfs());
            assertEquals(Map.of("/tmp", rec.path("Tmpfs").path("/tmp").asText()), hc.tmpfs());
            assertFalse(Boolean.TRUE.equals(hc.privileged()), "not privileged");
            assertTrue(hc.capAdd() == null || hc.capAdd().isEmpty(), "no capability added");
            assertEquals(List.of(rec.path("SecurityOpt").get(0).asText()), hc.securityOpt().stream()
                    .map(o -> o.startsWith("seccomp=") ? "seccomp=sha256:" + sha256(o.substring(8)) : o).toList());
            assertEquals(h.network(), hc.networkMode());
            assertEquals(List.of(h.workspaceVolume() + ":/workspace", h.socketVolume() + ":/skald-sock",
                    h.configVolume() + ":/skald-conf:ro"), hc.binds());
            assertTrue(hc.binds().stream().noneMatch(b -> b.contains("docker.sock") || b.startsWith("/")),
                    "no socket and no host path reaches the worker");
            assertEquals("2401:2401", worker.config().user(), "the image's dedicated identity");
            assertTrue(docker.inspectNetwork(h.network()).internal(), "the worker's network is internal");
            assertEquals(1, docker.inspectNetwork(h.network()).containers().size() - 1,
                    "only the worker and the gateway are on it");

            Volume ws = docker.inspectVolume(h.workspaceVolume());
            assertEquals("ext4", ws.options().get("type"));
            assertEquals("nosuid,nodev,barrier=0", ws.options().get("o"));
            assertTrue(ws.options().get("device").startsWith("/dev/loop"));
            // What the worker has mounted, not only what the volume asks for: no barriers and no
            // journal, or every fsync of an extraction reaches the backing file's disk (the T7
            // pass probe: about 460 ms each, and a cold base did not extract within the deadline).
            String mount = exec(h.worker(), "sh", "-c", "grep ' /workspace ' /proc/mounts").strip();
            List<String> options = List.of(mount.split(" ")[3].split(","));
            assertTrue(mount.startsWith(ws.options().get("device") + " /workspace ext4 "), mount);
            assertTrue(options.containsAll(List.of("nosuid", "nodev", "nobarrier")), mount);
            // buildkitd, and so everything it starts (runc, every RUN step), runs under
            // no_new_privs; rootlesskit, which needs newuidmap's file capability, does not
            // (the image's ENTRYPOINT: rootlesskit setpriv --nnp buildkitd; T7 part 4e-1c).
            String nnp = exec(h.worker(), "sh", "-c", "for p in $(pidof buildkitd) $(pidof rootlesskit); do"
                    + " echo \"$(cat /proc/$p/comm) $(awk '$1==\"NoNewPrivs:\"{print $2}' /proc/$p/status)\"; done");
            assertTrue(nnp.lines().anyMatch(l -> l.equals("buildkitd 1")), nnp);
            assertTrue(nnp.lines().filter(l -> l.startsWith("buildkitd ")).allMatch(l -> l.equals("buildkitd 1")), nnp);
            assertTrue(nnp.lines().anyMatch(l -> l.equals("rootlesskit 0")), nnp);
            // jbd2 lists every journal on the kernel as <device>-8, the worker's /proc included.
            String journals = exec(h.worker(), "ls", "/proc/fs/jbd2");
            assertFalse(List.of(journals.split("\\s+")).contains(ws.options().get("device").substring(5) + "-8"),
                    "the workspace has no journal: " + journals);

            // The rule the gateway runs is the one GatewayConfig writes (543ed35 N2's other
            // half: the conf the driver actually mounts).
            // The gateway is hardened (9a2658d N1).
            HostConfig gw = docker.inspectContainer(h.gateway()).hostConfig();
            assertTrue(gw.readonlyRootfs(), "gateway root read-only");
            assertEquals(List.of("ALL"), gw.capDrop());
            assertEquals(List.of("no-new-privileges"), gw.securityOpt());
            assertEquals(DockerWorkerLauncher.GATEWAY_MEMORY, gw.memory());
            assertEquals(DockerWorkerLauncher.GATEWAY_MEMORY, gw.memorySwap());
            assertEquals(128, gw.pidsLimit());
            assertFalse(Boolean.TRUE.equals(gw.privileged()));

            String mounted = exec(h.gateway(), "cat", "/skald-conf/squid.conf");
            assertEquals(GatewayConfig.squidConf("registry", List.of("pypi.org"), List.of("forge")), mounted);

            // The provenance states what ran: each image by the id its container runs.
            // RUN steps reach the gateway by its address on the attempt's network (a step cannot
            // resolve its name: the T7 pass probe).
            String ip = docker.inspectContainer(h.gateway()).networkSettings().networks().get(h.network()).ipAddress();
            assertTrue(ip.matches("[0-9]+(\\.[0-9]+){3}"), ip);
            assertEquals("http://" + ip + ":" + GatewayConfig.PORT, launcher.gatewayProxy(h));
            Map<String, Object> ran = launcher.provenance(h);
            Map<?, ?> images = (Map<?, ?>) ran.get("images");
            assertEquals(worker.image(), ((Map<?, ?>) images.get("worker")).get("id"));
            assertEquals(docker.inspectContainer(h.gateway()).image(), ((Map<?, ?>) images.get("gateway")).get("id"));
            assertEquals(WorkerProfile.load("runc-rootless").identity(), ran.get("profile"));
        } finally {
            assertEquals(List.of(), launcher.dispose(h), "everything the attempt owned is gone");
        }
        assertEquals(List.of(), launcher.leftovers(h));
        assertEquals(List.of(), launcher.dispose(h), "disposal is idempotent");
    }

    @Test
    public void aLaunchThatFailsPartWayLeavesNothing() throws Exception {
        String id = attempt();
        DockerWorkerLauncher broken = new DockerWorkerLauncher(docker, WorkerProfile.load("runc-rootless"),
                Images.of(WORKER, "skald-egress-gateway:no-such-tag"));
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> broken.launch(request(id)));
        assertFalse(e.getMessage().contains("LEFT BEHIND"), e.getMessage());
        assertEquals(List.of(), launcher.leftovers(DockerWorkerLauncher.handle(id)));
    }

    @Test
    public void aLaunchRefusesToAdoptAVolumeItDidNotMake() throws Exception {
        // 9a2658d-F1's repro: a volume already holding the workspace's name, here a 1 MiB
        // tmpfs with no labels. Docker's volume create would have returned it as is.
        for (String role : List.of("ws", "conf", "ctx")) {
            String id = attempt();
            Handle h = DockerWorkerLauncher.handle(id);
            String name = switch (role) {
                case "ws" -> h.workspaceVolume();
                case "conf" -> h.configVolume();
                default -> h.contextVolume();
            };
            docker.createVolume(Volume.builder().name(name).driver("local")
                    .driverOpts(Map.of("type", "tmpfs", "device", "tmpfs", "o", "size=1m")).build());
            try {
                IllegalStateException e = assertThrows(IllegalStateException.class,
                        () -> launcher.launch(request(id)));
                assertTrue(e.getMessage().contains("already taken"), e.getMessage());
                // Not ours, so not removed, and not changed.
                assertEquals("tmpfs", docker.inspectVolume(name).options().get("type"));
                assertEquals(List.of(), launcher.leftovers(h), "nothing of the attempt was made");
            } finally {
                docker.removeVolume(name);
            }
        }
    }

    @Test
    public void aHelperRemovalTheDaemonFailsOnceIsRetried() throws Exception {
        // e4306c6 review N2: a force-remove right after a helper's wait returned can be
        // answered "is still running". The launch's first helper (conf) meets that once; the
        // removal is retried, so the launch goes on, and nothing is left.
        String id = attempt();
        java.util.concurrent.atomic.AtomicInteger failures = new java.util.concurrent.atomic.AtomicInteger(1);
        DockerClient flaky = (DockerClient) java.lang.reflect.Proxy.newProxyInstance(DockerClient.class.getClassLoader(),
                new Class<?>[] {DockerClient.class}, (proxy, m, args) -> {
                    if (m.getName().equals("removeContainer") && ("skald-helper-conf-" + id).equals(args[0])
                            && failures.getAndDecrement() > 0) {
                        throw new org.mandas.docker.client.exceptions.DockerException(
                                "simulated: container skald-helper-conf-" + id + " is still running");
                    }
                    try {
                        return m.invoke(docker, args);
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
        DockerWorkerLauncher onFlaky = new DockerWorkerLauncher(flaky, WorkerProfile.load("runc-rootless"),
                Images.of(WORKER, GATEWAY));
        Handle h = onFlaky.launch(request(id));
        try {
            // Decremented on every removal of the conf helper: -1 is exactly two calls, the
            // one that failed and its retry.
            assertEquals(-1, failures.get(), "the removal failed once and was retried once");
            assertTrue(docker.inspectContainer(h.worker()).state().running());
        } finally {
            assertEquals(List.of(), onFlaky.dispose(h));
        }
    }

    @Test
    public void theIsolationProbesRunTheDaemonThatShips() throws Exception {
        // The T5 isolation evidence is measured on dev/shipping_worker.py's daemon; nothing tied
        // it to the launcher's until the snapshotter changed (T7 part 4e-1c). Equal, but for the
        // launcher's --config (the probes pass their own).
        String py = Files.readString(Path.of("dev/shipping_worker.py"));
        java.util.regex.Matcher mount = java.util.regex.Pattern.compile("(?m)^SOCKET_MOUNT = \"([^\"]+)\"$").matcher(py);
        assertTrue(mount.find(), "SOCKET_MOUNT");
        assertTrue(py.contains("\nSOCKET_ADDR = \"unix://\" + SOCKET_MOUNT + \"/bk.sock\"\n"), "SOCKET_ADDR");
        java.util.regex.Matcher args = java.util.regex.Pattern.compile("(?ms)^DAEMON_ARGS = \\[(.*?)\\]$").matcher(py);
        assertTrue(args.find(), "DAEMON_ARGS");
        List<String> probe = new java.util.ArrayList<>();
        for (String item : args.group(1).split(",")) {
            String t = item.strip();
            if (t.equals("SOCKET_ADDR")) {
                probe.add("unix://" + mount.group(1) + "/bk.sock");
            } else {
                assertTrue(t.matches("\"[^\"]*\""), t);
                probe.add(t.substring(1, t.length() - 1));
            }
        }
        List<String> shipped = DockerWorkerLauncher.DAEMON_ARGS;
        int n = shipped.size();
        assertEquals(List.of("--config", DockerWorkerLauncher.CONFIG_MOUNT + "/buildkitd.toml"), shipped.subList(n - 2, n));
        assertEquals(shipped.subList(0, n - 2), probe);
        assertTrue(probe.contains("--oci-worker-snapshotter=overlayfs"), probe.toString());
        // And the entrypoint: the launcher sets the one the image is generated with (61635b7-F1).
        java.util.regex.Matcher entry = java.util.regex.Pattern.compile("(?m)^ENTRYPOINT = \\[(.*)\\]$").matcher(py);
        assertTrue(entry.find(), "ENTRYPOINT");
        assertEquals(DockerWorkerLauncher.ENTRYPOINT, java.util.Arrays.stream(entry.group(1).split(","))
                .map(s -> s.strip().replaceAll("^\"|\"$", "")).toList());
    }

    @Test
    public void aWorkerImageWithTheUpstreamEntrypointStillRunsItsDaemonUnderNoNewPrivs() throws Exception {
        // 61635b7-F1's repro: the worker image as built before 4e-1c, whose ENTRYPOINT is
        // upstream's. The launcher sets its own, so the image's does not decide.
        String image = variant("upstream-entrypoint", "ENTRYPOINT [\"rootlesskit\", \"buildkitd\"]\n");
        try {
            DockerWorkerLauncher old = new DockerWorkerLauncher(docker, WorkerProfile.load("runc-rootless"),
                    Images.of(image, GATEWAY));
            Handle h = old.launch(request(attempt()));
            try {
                assertEquals(DockerWorkerLauncher.ENTRYPOINT, docker.inspectContainer(h.worker()).config().entrypoint());
                String nnp = exec(h.worker(), DockerWorkerLauncher.DAEMON_NNP.toArray(String[]::new));
                assertTrue(!nnp.isBlank() && nnp.strip().lines().allMatch("buildkitd 1"::equals), nnp);
            } finally {
                assertEquals(List.of(), old.dispose(h));
            }
        } finally {
            docker.removeImage(image);
        }
    }

    @Test
    public void aWorkerWhoseDaemonLacksNoNewPrivsIsRefused() throws Exception {
        // The launcher's entrypoint is not trusted to have worked: here the image's setpriv
        // drops --nnp and runs the daemon anyway, as a wrapper or a later change might. The
        // daemon starts and reports a worker; the launch is refused, and nothing is left.
        String image = variant("setpriv-without-nnp", "USER root\n"
                + "RUN rm /bin/setpriv && printf '#!/bin/sh\\n[ \"$1\" = --nnp ] && shift\\nexec \"$@\"\\n'"
                + " > /bin/setpriv && chmod 755 /bin/setpriv\n"
                + "USER 2401:2401\n");
        try {
            DockerWorkerLauncher wrapped = new DockerWorkerLauncher(docker, WorkerProfile.load("runc-rootless"),
                    Images.of(image, GATEWAY));
            String id = attempt();
            Handle[] launched = new Handle[1];
            try {
                IllegalStateException e = assertThrows(IllegalStateException.class,
                        () -> launched[0] = wrapped.launch(request(id)));
                assertTrue(e.getMessage().contains("does not run under no_new_privs"), e.getMessage());
                assertTrue(e.getMessage().contains("buildkitd 0"), e.getMessage());
                assertFalse(e.getMessage().contains("LEFT BEHIND"), e.getMessage());
                assertEquals(List.of(), launcher.leftovers(DockerWorkerLauncher.handle(id)));
            } finally {
                // A launch that wrongly succeeds holds the image; dispose of it, or its
                // failure is hidden behind the image's removal and its worker outlives the test.
                if (launched[0] != null) {
                    wrapped.dispose(launched[0]);
                }
            }
        } finally {
            docker.removeImage(image);
        }
    }

    /** The test worker image with `lines` appended, under a tag of its own. */
    private static String variant(String what, String lines) throws Exception {
        Path dir = Files.createTempDirectory("skald-worker-" + what);
        try {
            Files.writeString(dir.resolve("Dockerfile"), "FROM " + WORKER + "\n" + lines);
            String image = "skald-buildkit-worker:test-" + what + "-" + attempt();
            docker.build(dir, image);
            return image;
        } finally {
            Files.delete(dir.resolve("Dockerfile"));
            Files.delete(dir);
        }
    }

    @Test
    public void theProvenanceRefusesContainersThatAreNotTheAttempts() throws Exception {
        // A container under the attempt's names that does not carry its label is not what the
        // attempt ran; its image is never put on record as the attempt's.
        String id = attempt();
        Handle h = DockerWorkerLauncher.handle(id);
        List<String> made = new java.util.ArrayList<>();
        try {
            for (String name : List.of(h.worker(), h.gateway())) {
                made.add(docker.createContainer(org.mandas.docker.client.messages.ContainerConfig.builder()
                        .image(Images.HELPER).cmd("true").build(), name).id());
            }
            IllegalStateException e = assertThrows(IllegalStateException.class, () -> launcher.provenance(h));
            assertTrue(e.getMessage().contains(h.worker()), e.getMessage());
            // Nor is it the proxy a RUN step is sent through.
            IllegalStateException p = assertThrows(IllegalStateException.class, () -> launcher.gatewayProxy(h));
            assertTrue(p.getMessage().contains(h.gateway()), p.getMessage());
        } finally {
            for (String c : made) {
                docker.removeContainer(c);
            }
        }
    }

    @Test
    public void aHelperLeftByAKilledDisposalDoesNotBlockTheNextOne() throws Exception {
        // A process killed while dispose() ran its own helper leaves that container; the next
        // dispose() must remove it rather than fail to start its helper by the same name.
        String id = attempt();
        Handle h = launcher.launch(request(id));
        try {
            for (String role : List.of("cleanup", "check")) {
                docker.createContainer(org.mandas.docker.client.messages.ContainerConfig.builder()
                        .image(Images.HELPER).cmd("true")
                        .labels(Map.of(DockerWorkerLauncher.ATTEMPT_LABEL, id)).build(),
                        "skald-helper-" + role + "-" + id);
            }
        } finally {
            assertEquals(List.of(), launcher.dispose(h), "disposal removed the stale helpers and finished");
        }
        assertEquals(List.of(), launcher.leftovers(h));
    }

    @Test
    public void aRefusedRelaunchLeavesTheLiveLaunchRunning() throws Exception {
        // 67b7c17-F1: the same attempt launched twice. The second launch refuses, and its
        // refusal must not dispose of the first, whose objects carry the same label.
        String id = attempt();
        Handle first = launcher.launch(request(id));
        try {
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> launcher.launch(request(id)));
            assertTrue(e.getMessage().contains("already taken"), e.getMessage());
            assertTrue(docker.inspectContainer(first.worker()).state().running(),
                    "the first launch's worker still runs");
            assertTrue(docker.inspectContainer(first.gateway()).state().running(),
                    "and its gateway");
            assertTrue(docker.inspectVolume(first.workspaceVolume()).options().get("device")
                    .startsWith("/dev/loop"), "and its workspace, still on its loop device");
        } finally {
            assertEquals(List.of(), launcher.dispose(first));
        }
    }

    @Test
    public void aVolumeTakenBetweenTheCheckAndTheCreateIsNotAdopted() throws Exception {
        // The upfront name check refuses first in every launch, so the re-check after
        // create (the race guard) is exercised on its own here: the name is taken, as it
        // would be by a create racing this launch's.
        String id = attempt();
        String name = DockerWorkerLauncher.handle(id).configVolume();
        Map<String, String> labels = Map.of(DockerWorkerLauncher.ATTEMPT_LABEL, id);
        docker.createVolume(Volume.builder().name(name).build());
        try {
            assertThrows(IllegalStateException.class, () -> launcher.createOwnedVolume(name, labels, null),
                    "an unlabelled volume of the name is not this launch's");
        } finally {
            docker.removeVolume(name);
        }
        docker.createVolume(Volume.builder().name(name).labels(labels).driver("local")
                .driverOpts(Map.of("type", "tmpfs", "device", "tmpfs", "o", "size=1m")).build());
        try {
            assertThrows(IllegalStateException.class, () -> launcher.createOwnedVolume(name, labels,
                    Map.of("type", "ext4", "device", "/dev/loop9", "o", "nosuid,nodev")),
                    "our label but other options is still not the volume asked for");
        } finally {
            docker.removeVolume(name);
        }
    }

    @Test
    public void disposalDetachesTheQuotaDeviceEvenWithNoVolumeToNameIt() throws Exception {
        // 9a2658d-F2: a launch that stopped after losetup and before the workspace volume
        // existed (a platform crash; reconciliation then disposes by attempt id). The image
        // is attached exactly as makeWorkspace attaches it, and no volume records the device.
        String id = attempt();
        Handle h = DockerWorkerLauncher.handle(id);
        String image = h.quotaImage();
        String setup = docker.createContainer(org.mandas.docker.client.messages.ContainerConfig.builder()
                .image(Images.SETUP).labels(Map.of(DockerWorkerLauncher.ATTEMPT_LABEL, id))
                .cmd("sh", "-c", "truncate -s 64M /s/" + image + " && " + DockerWorkerLauncher.WORKSPACE_MKFS + " /s/" + image
                        + " && losetup --find --show /s/" + image)
                .hostConfig(HostConfig.builder().privileged(true).networkMode("none")
                        .binds(DockerWorkerLauncher.QUOTA_IMAGES_VOLUME + ":/s").build()).build()).id();
        docker.startContainer(setup);
        assertEquals(0L, docker.waitContainer(setup).statusCode());
        docker.removeContainer(setup);
        List<String> before = launcher.leftovers(h);
        assertTrue(before.stream().anyMatch(l -> l.startsWith("quota /sys/block/loop")),
                "the device is attached before disposal: " + before);
        assertEquals(List.of(), launcher.dispose(h));
        assertEquals(List.of(), launcher.leftovers(h));
    }

    @Test
    public void theLeftoverCheckSeesWhatIsLeft() throws Exception {
        // A check that reports nothing whatever exists would make the two tests above
        // vacuous; a labelled volume made by hand must be reported.
        String id = attempt();
        Handle h = DockerWorkerLauncher.handle(id);
        docker.createVolume(Volume.builder().name(h.socketVolume())
                .labels(Map.of(DockerWorkerLauncher.ATTEMPT_LABEL, id)).build());
        try {
            assertEquals(List.of("volume " + h.socketVolume()), launcher.leftovers(h));
        } finally {
            docker.removeVolume(h.socketVolume());
        }
    }

    private static String exec(String container, String... cmd) throws Exception {
        String id = docker.execCreate(container, cmd, ExecCreateParam.attachStdout(),
                ExecCreateParam.attachStderr()).id();
        try (var out = docker.execStart(id)) {
            return out.readFully();
        }
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
