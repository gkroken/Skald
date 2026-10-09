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

import eu.openanalytics.shinyproxy.publisher.build.BuildCoordinator.LostAttempt;
import eu.openanalytics.shinyproxy.publisher.storage.BuildLogWriter;
import eu.openanalytics.shinyproxy.publisher.storage.LogFinal;
import eu.openanalytics.shinyproxy.publisher.storage.TestObjectStore;
import eu.openanalytics.shinyproxy.publisher.worker.DockerWorkerLauncher.Egress;
import eu.openanalytics.shinyproxy.publisher.worker.DockerWorkerLauncher.Handle;
import eu.openanalytics.shinyproxy.publisher.worker.DockerWorkerLauncher.Images;
import eu.openanalytics.shinyproxy.publisher.worker.DockerWorkerLauncher.Request;
import eu.openanalytics.shinyproxy.publisher.worker.WorkerProfile.Settings;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mandas.docker.client.DockerClient;
import org.mandas.docker.client.DockerClient.RemoveContainerParam;
import org.mandas.docker.client.builder.jersey.JerseyDockerClientBuilder;
import org.mandas.docker.client.exceptions.VolumeNotFoundException;
import org.mandas.docker.client.messages.ContainerConfig;
import org.mandas.docker.client.messages.HostConfig;
import org.mandas.docker.client.messages.Volume;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The reconciliation sweep against real Docker and MinIO: what dead attempts left goes,
 * a live attempt never does, and lost logs are finished once. It sweeps only the ids it
 * made, since other runs on this host use build-shaped attempt ids too.
 */
public class AttemptSweeperTest {

    private static final String WORKER = "skald-buildkit-worker:test";
    private static final String GATEWAY = "skald-egress-gateway:test";
    private static DockerClient docker;
    private static DockerWorkerLauncher launcher;
    private static TestObjectStore logStore;
    private static BuildLogWriter logs;

    @BeforeAll
    public static void start() throws Exception {
        docker = new JerseyDockerClientBuilder().fromEnv().build();
        for (String image : List.of(WORKER, GATEWAY, Images.HELPER, Images.SETUP)) {
            assertNotNull(docker.inspectImage(image), image + " is missing; run `make test`");
        }
        launcher = new DockerWorkerLauncher(docker, WorkerProfile.load("runc-rootless"), Images.of(WORKER, GATEWAY));
        logStore = new TestObjectStore("skald-sweep-logs");
        logs = new BuildLogWriter(logStore.store, logStore.bucket);
    }

    @AfterAll
    public static void stop() {
        try {
            if (logStore != null) {
                logStore.close();
            }
        } finally {
            if (docker != null) {
                docker.close();
            }
        }
    }

    private static String buildId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static UUID uuid(String id) {
        return UUID.fromString(id.replaceFirst("(.{8})(.{4})(.{4})(.{4})(.{12})", "$1-$2-$3-$4-$5"));
    }

    private static Request request(String attempt) {
        return new Request(attempt, Settings.defaults(), 256,
                new Egress(List.of(), "registry", List.of("pypi.org"), List.of("forge"), List.of(), List.of()));
    }

    /** A quota image file alone, as a launch that died after creating it leaves. */
    private static void quotaImageOnly(String attempt) throws Exception {
        String name = "skald-sweep-make-" + attempt.substring(0, 8);
        docker.createContainer(ContainerConfig.builder().image(Images.HELPER)
                .cmd("sh", "-c", "truncate -s 1M /s/skald-quota-" + attempt + ".img")
                .hostConfig(HostConfig.builder().binds(DockerWorkerLauncher.QUOTA_IMAGES_VOLUME + ":/s")
                        .networkMode("none").build()).build(), name);
        try {
            docker.startContainer(name);
            docker.waitContainer(name);
        } finally {
            docker.removeContainer(name, RemoveContainerParam.forceKill());
        }
    }

    private static void labelledVolume(String name, String attempt) throws Exception {
        docker.createVolume(Volume.builder().name(name).labels(Map.of(DockerWorkerLauncher.ATTEMPT_LABEL, attempt)).build());
    }

    @Test
    public void aSweepDisposesWhatDeadAttemptsLeftAndNeverALiveOne() throws Exception {
        String live = buildId();
        String dead = buildId();
        String imageOnly = buildId();
        String notABuild = "probe" + buildId().substring(0, 11);
        Set<String> mine = new HashSet<>(List.of(live, dead, imageOnly, notABuild));
        Handle hl = launcher.launch(request(live));
        Handle hd = launcher.launch(request(dead));
        quotaImageOnly(imageOnly);
        String foreign = "skald-b-" + notABuild + "-ctx";
        labelledVolume(foreign, notABuild);
        try {
            AttemptSweeper sweeper = new AttemptSweeper(launcher, () -> Set.of(uuid(live)), List::of, logs,
                    Duration.ofMinutes(10), mine::contains);
            AttemptSweeper.Report r = sweeper.sweep();
            assertEquals(Set.of(dead, imageOnly), new HashSet<>(r.disposed()), r.toString());
            assertEquals(List.of(), r.leftBehind());
            assertEquals(List.of(), launcher.leftovers(hd), "the dead attempt's worker, network, volumes and device");
            assertEquals(List.of(), launcher.leftovers(DockerWorkerLauncher.handle(imageOnly)), "the lone quota image");
            assertTrue(docker.inspectContainer(hl.worker()).state().running(), "the live attempt is untouched");
            assertNotNull(docker.inspectVolume(foreign), "an attempt id that is not a build id is never disposed of");
            assertEquals(List.of(), sweeper.sweep().disposed(), "a second sweep finds nothing more");
        } finally {
            launcher.dispose(hl);
            launcher.dispose(hd);
            launcher.dispose(DockerWorkerLauncher.handle(imageOnly));
            docker.removeVolume(foreign);
        }
        assertEquals(List.of(), launcher.leftovers(hl));
    }

    @Test
    public void anAttemptLaunchedWhileTheSweepRanIsNotDisposedOf() throws Exception {
        // The inventory is taken BEFORE the unfinished set is read. An attempt whose claim and
        // launch fall between the two is not in the inventory, so it is never judged by a set
        // read before it existed. Here the "claim and launch" happen inside the set's read.
        String late = buildId();
        Handle h = DockerWorkerLauncher.handle(late);
        try {
            AttemptSweeper sweeper = new AttemptSweeper(launcher, () -> {
                try {
                    labelledVolume(h.contextVolume(), late);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                return Set.of();
            }, List::of, logs, Duration.ofMinutes(10), late::equals);
            assertEquals(List.of(), sweeper.sweep().disposed());
            assertNotNull(docker.inspectVolume(h.contextVolume()), "the attempt that started meanwhile is untouched");
        } finally {
            launcher.dispose(h);
        }
        assertThrows(VolumeNotFoundException.class, () -> docker.inspectVolume(h.contextVolume()));
    }

    @Test
    public void aLostAttemptsUnfinishedLogIsFinishedOnceAsInterrupted() throws Exception {
        UUID content = UUID.randomUUID();
        LostAttempt unfinished = new LostAttempt(UUID.randomUUID(), content, 3);
        LostAttempt finished = new LostAttempt(UUID.randomUUID(), content, 3);
        byte[] chunk = "{\"n\":1,\"t\":\"head\"}\n".getBytes(StandardCharsets.UTF_8);
        logs.appendChunk(content, unfinished.buildId(), 1, chunk);
        logs.appendChunk(content, finished.buildId(), 1, chunk);
        logs.finalise(content, finished.buildId(), 3, "BUILT", false).orElseThrow();
        AttemptSweeper sweeper = new AttemptSweeper(launcher, Set::of, () -> List.of(unfinished, finished), logs,
                Duration.ofMinutes(10), id -> false);

        assertEquals(List.of(unfinished.buildId()), sweeper.sweep().logsFinished());
        LogFinal fin = logs.readFinal(content, unfinished.buildId()).orElseThrow();
        assertEquals("INTERRUPTED", fin.outcome());
        assertEquals(3, fin.generation());
        assertEquals(1, fin.lastSequence(), "the head that was written stays readable");
        assertEquals("BUILT", logs.readFinal(content, finished.buildId()).orElseThrow().outcome(), "untouched");
        assertEquals(List.of(), sweeper.sweep().logsFinished(), "finished once");
    }

    /** Every volume on the host whose name carries the attempt id, labelled or not. */
    private static List<String> volumesNaming(String attempt) throws Exception {
        List<Volume> all = docker.listVolumes().volumes();
        return all == null ? List.of() : all.stream().map(Volume::name).filter(n -> n.contains(attempt)).sorted().toList();
    }

    /** A volume as the daemon makes one for a bind of a missing name: a container binds it. */
    private static void boundIntoExistence(String volume) throws Exception {
        String name = "skald-sweep-bind-" + UUID.randomUUID().toString().substring(0, 8);
        docker.createContainer(ContainerConfig.builder().image(Images.HELPER).cmd("true")
                .hostConfig(HostConfig.builder().binds(volume + ":/v").networkMode("none").build()).build(), name);
        docker.removeContainer(name, RemoveContainerParam.forceKill());
    }

    @Test
    public void aLateDaemonCallOfAFinishedAttemptLeavesNothingBehind() throws Exception {
        // 2fc53d9-F1, the reviewer's reproduction. The build has finished (CANCELLED, say)
        // while its work thread is still inside a daemon call: here the launch's config
        // helper create, held. A sweep disposes of the attempt meanwhile. The call then goes
        // through and binds the conf and sock volumes the sweep removed, so the daemon
        // re-creates them UNLABELLED; the launch fails on the missing network and its work
        // thread disposes as its last act. Before the fix that disposal skipped the
        // unlabelled volumes, reported clean, and no later sweep could find them.
        String id = buildId();
        Handle h = DockerWorkerLauncher.handle(id);
        String held = "skald-helper-conf-" + id;
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        DockerClient holding = (DockerClient) Proxy.newProxyInstance(DockerClient.class.getClassLoader(),
                new Class<?>[] {DockerClient.class}, (proxy, m, args) -> {
                    if (m.getName().equals("createContainer") && args != null && args.length == 2
                            && held.equals(args[1])) {
                        entered.countDown();
                        release.await();
                    }
                    try {
                        return m.invoke(docker, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
        DockerWorkerLauncher late = new DockerWorkerLauncher(holding, WorkerProfile.load("runc-rootless"),
                Images.of(WORKER, GATEWAY));
        AtomicReference<Throwable> launchEnded = new AtomicReference<>();
        Thread work = new Thread(() -> {
            try {
                late.dispose(late.launch(request(id)));
            } catch (Throwable t) {
                launchEnded.set(t);
            }
        }, "late-work-" + id);
        work.start();
        try {
            assertTrue(entered.await(5, TimeUnit.MINUTES), "the launch reached the held call");
            AttemptSweeper sweeper = new AttemptSweeper(launcher, Set::of, List::of, logs, Duration.ofMinutes(10),
                    id::equals);
            assertEquals(List.of(id), sweeper.sweep().disposed(), "the finished attempt is disposed of meanwhile");
            release.countDown();
            work.join(TimeUnit.MINUTES.toMillis(10));
            assertFalse(work.isAlive(), "the work thread ended");
            assertNotNull(launchEnded.get(), "the launch failed: the sweep removed its network");
            assertEquals(List.of(), volumesNaming(id), "no volume of the attempt, labelled or not");
            assertEquals(List.of(), launcher.leftovers(h));
        } finally {
            release.countDown();
            work.join(TimeUnit.MINUTES.toMillis(10));
            launcher.dispose(h);
        }
    }

    @Test
    public void aVolumeReCreatedForABindIsFoundByNameAfterACrash() throws Exception {
        // The same late bind, but the process died before its work thread could dispose:
        // nothing of the attempt carries a label any more, only the volume's name is left.
        String dead = buildId();
        Handle h = DockerWorkerLauncher.handle(dead);
        boundIntoExistence(h.configVolume());
        // A volume under an attempt's name with options was made by someone, not by a bind:
        // never the sweep's (9a2658d-F1).
        String foreign = buildId();
        String foreignVolume = DockerWorkerLauncher.handle(foreign).configVolume();
        docker.createVolume(Volume.builder().name(foreignVolume).driver("local")
                .driverOpts(Map.of("type", "tmpfs", "device", "tmpfs", "o", "size=1m")).build());
        try {
            assertEquals(List.of("volume " + h.configVolume() + " (unlabelled, made by the daemon for a bind)"),
                    launcher.leftovers(h), "reported, not hidden");
            AttemptSweeper sweeper = new AttemptSweeper(launcher, Set::of, List::of, logs, Duration.ofMinutes(10),
                    Set.of(dead, foreign)::contains);
            assertEquals(List.of(dead), sweeper.sweep().disposed());
            assertEquals(List.of(), volumesNaming(dead));
            assertEquals("tmpfs", docker.inspectVolume(foreignVolume).options().get("type"), "left as it is");
        } finally {
            launcher.dispose(h);
            docker.removeVolume(foreignVolume);
        }
    }

    @Test
    public void aSweepHelperADeadProcessLeftIsRemovedAndARunningOneIsNot() throws Exception {
        // 2fc53d9 N1: the inventory helper is privileged and fresh-named; a JVM killed while
        // it ran leaves it. It is labelled, and the next inventory removes a stopped one. A
        // running one is another instance's sweep in progress.
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String stale = "skald-sweep-stale-" + suffix;
        String running = "skald-sweep-running-" + suffix;
        Map<String, String> label = Map.of(DockerWorkerLauncher.SWEEP_LABEL, "true");
        docker.createContainer(ContainerConfig.builder().image(Images.HELPER).cmd("true").labels(label)
                .hostConfig(HostConfig.builder().networkMode("none").build()).build(), stale);
        docker.createContainer(ContainerConfig.builder().image(Images.HELPER).cmd("sleep", "600").labels(label)
                .hostConfig(HostConfig.builder().networkMode("none").build()).build(), running);
        try {
            docker.startContainer(running);
            // The inventory's own helper carries the label, or a dead JVM's copy of it could
            // never be found: recorded as the launcher creates it.
            List<Map<String, String>> helperLabels = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
            DockerClient recording = (DockerClient) Proxy.newProxyInstance(DockerClient.class.getClassLoader(),
                    new Class<?>[] {DockerClient.class}, (proxy, m, args) -> {
                        if (m.getName().equals("createContainer") && args != null && args.length == 2
                                && String.valueOf(args[1]).startsWith("skald-helper-sweep-")) {
                            helperLabels.add(((ContainerConfig) args[0]).labels());
                        }
                        try {
                            return m.invoke(docker, args);
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
            new DockerWorkerLauncher(recording, WorkerProfile.load("runc-rootless"), Images.of(WORKER, GATEWAY))
                    .attemptsPresent();
            assertEquals(List.of(label), helperLabels);
            assertThrows(org.mandas.docker.client.exceptions.ContainerNotFoundException.class,
                    () -> docker.inspectContainer(stale), "the stale helper is gone");
            assertTrue(docker.inspectContainer(running).state().running(), "the running one is left alone");
        } finally {
            docker.removeContainer(running, RemoveContainerParam.forceKill());
            try {
                docker.removeContainer(stale, RemoveContainerParam.forceKill());
            } catch (org.mandas.docker.client.exceptions.ContainerNotFoundException e) {
                // removed by the inventory
            }
        }
    }
}
