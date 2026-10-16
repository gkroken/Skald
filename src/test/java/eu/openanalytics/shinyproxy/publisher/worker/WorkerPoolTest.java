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

import eu.openanalytics.shinyproxy.publisher.recipe.RecipeGenerator.Recipe;
import eu.openanalytics.shinyproxy.publisher.worker.BuildKitClient.Push;
import eu.openanalytics.shinyproxy.publisher.worker.DockerWorkerLauncher.Egress;
import eu.openanalytics.shinyproxy.publisher.worker.DockerWorkerLauncher.Handle;
import eu.openanalytics.shinyproxy.publisher.worker.DockerWorkerLauncher.Images;
import eu.openanalytics.shinyproxy.publisher.worker.DockerWorkerLauncher.Request;
import eu.openanalytics.shinyproxy.publisher.worker.WorkerProfile.Settings;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mandas.docker.client.DockerClient;
import org.mandas.docker.client.builder.jersey.JerseyDockerClientBuilder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The warm pool (T7b, the fixed cost) against real workers and a real registry: an entry is
 * warmed with its base, an attempt that takes it builds without pulling or extracting the
 * base, and its disposal reaches the entry; entries expire, a dead instance's are swept, a
 * taken one is not, and one too old is never handed out.
 */
public class WorkerPoolTest {

    private static final String WORKER = "skald-buildkit-worker:test";
    private static final String GATEWAY = "skald-egress-gateway:test";
    private static TestRegistry registry;
    private static DockerClient docker;
    private static DockerWorkerLauncher launcher;
    private static BuildKitClient client;

    @BeforeAll
    public static void start() throws Exception {
        docker = new JerseyDockerClientBuilder().fromEnv().build();
        for (String image : List.of(WORKER, GATEWAY, Images.HELPER, Images.SETUP, TestRegistry.IMAGE)) {
            assertNotNull(docker.inspectImage(image), image + " is missing; run `make test`");
        }
        Images images = Images.of(WORKER, GATEWAY);
        launcher = new DockerWorkerLauncher(docker, WorkerProfile.load("runc-rootless"), images);
        client = new BuildKitClient(docker, launcher, images);
        registry = new TestRegistry(docker, "skald-wpt", 15042);
    }

    @AfterAll
    public static void stop() throws Exception {
        if (docker != null) {
            try {
                if (registry != null) {
                    registry.close();
                }
            } finally {
                docker.close();
            }
        }
    }

    private static Request request(String id) {
        return new Request(id, Settings.defaults(), 256,
                new Egress(List.of(registry.outer), registry.name, List.of(), List.of(), List.of(), List.of()));
    }

    private static WorkerPool pool(String base, AtomicLong now) {
        return new WorkerPool(launcher, client, WorkerPoolTest::request, List.of(base), registry.name,
                TestRegistry.CRED, new WorkerPool.Settings(1, Duration.ofMinutes(30), Duration.ofMinutes(5),
                Duration.ofSeconds(1)), now::get);
    }

    private static String attempt() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /** Builds FROM the base with one step of its own; returns buildctl's log. */
    private static String build(Handle h, Path dir) throws Exception {
        Path payload = Files.createDirectories(dir.resolve(h.attemptId()));
        client.stage(h, payload, new Recipe("FROM " + registry.baseByDigest() + "\nRUN [\"sh\", \"-c\", \"echo built\"]\n",
                Map.of()));
        client.start(h, new Push(registry.name, "content/wpt", UUID.randomUUID(), TestRegistry.CRED));
        long deadline = System.nanoTime() + Duration.ofMinutes(10).toNanos();
        while (client.exitCode(h).isEmpty()) {
            assertTrue(System.nanoTime() < deadline, "the build finished within 10 minutes");
            Thread.sleep(500);
        }
        String log = client.log(h);
        assertEquals(0L, client.exitCode(h).orElseThrow(), log);
        return log;
    }

    private static boolean pullsTheBase(String log) {
        return log.lines().anyMatch(l -> l.matches("#\\d+ (extracting|sha256:)[0-9a-f:]* .*"));
    }

    @Test
    public void aTakenEntryBuildsWithoutPullingItsBaseAndItsAttemptsDisposalReachesIt(@TempDir Path dir)
            throws Exception {
        // The control first: a freshly launched worker pulls and extracts the base, and the log
        // says so; without it the warmed case below would prove nothing.
        String cold = attempt();
        Handle hc = launcher.launch(request(cold));
        try {
            assertTrue(pullsTheBase(build(hc, dir)), "a cold worker pulls and extracts the base");
        } finally {
            assertEquals(List.of(), launcher.dispose(hc));
        }
        AtomicLong now = new AtomicLong(java.time.Instant.now().getEpochSecond());
        WorkerPool pool = pool(registry.baseByDigest(), now);
        pool.tick();
        List<String> ready = pool.ready().get(registry.baseByDigest());
        assertEquals(1, ready.size(), "one entry, warmed");
        String entry = ready.get(0);
        String a = attempt();
        Handle attempt = DockerWorkerLauncher.handle(a);
        Optional<Handle> taken = pool.take(registry.baseByDigest(), a);
        try {
            assertTrue(taken.isPresent());
            assertEquals(entry, taken.get().attemptId());
            assertEquals(List.of(entry), launcher.takenBy(attempt), "the marker names the attempt and the entry");
            assertEquals(List.of(), pool.ready().get(registry.baseByDigest()), "taken entries leave the pool");
            String log = build(taken.get(), dir);
            assertFalse(pullsTheBase(log), "the warmed worker had the base: " + log);
        } finally {
            assertEquals(List.of(), launcher.dispose(attempt), "the attempt's disposal took the entry with it");
        }
        assertEquals(List.of(), launcher.leftovers(DockerWorkerLauncher.handle(entry)));
        assertEquals(List.of(), launcher.leftovers(attempt), "the marker is gone too");
    }

    @Test
    public void entriesExpireADeadInstancesAreSweptATakenOneIsNotAndAnOldOneIsNeverHandedOut()
            throws Exception {
        String base = registry.baseByDigest();
        AtomicLong now = new AtomicLong(java.time.Instant.now().getEpochSecond());
        // "Dead": another instance's pool that made two entries and then stopped answering.
        WorkerPool dead = pool(base, now);
        dead.tick();
        String abandoned = dead.ready().get(base).get(0);
        dead.tick();
        assertEquals(List.of(abandoned), dead.ready().get(base), "full: no second entry");
        WorkerPool live = pool(base, now);
        String takenFromDead = null;
        String x = attempt();
        try {
            live.tick();
            String mine = live.ready().get(base).get(0);
            // An entry of the dead pool taken by an attempt before it died: its attempt's.
            WorkerPool deadTwo = pool(base, now);
            deadTwo.tick();
            takenFromDead = deadTwo.take(base, x).orElseThrow().attemptId();

            // Past ttl - MARGIN: never handed out, but not yet disposed of.
            now.addAndGet(Duration.ofMinutes(26).toSeconds());
            assertEquals(Optional.empty(), live.take(base, attempt()), "too old to hand out");
            assertEquals(List.of(mine), live.ready().get(base));
            // 4838310 N1: the round makes its replacement now, while it waits out its ttl.
            live.tick();
            List<String> both = live.ready().get(base);
            assertEquals(2, both.size(), "a takeable replacement beside the old one: " + both);
            String fresh = both.get(1);

            // Past the ttl: ours expired; the replacement stays.
            now.addAndGet(Duration.ofMinutes(5).toSeconds());
            live.tick();
            assertEquals(List.of(), launcher.leftovers(DockerWorkerLauncher.handle(mine)), "expired and disposed of");
            assertEquals(List.of(fresh), live.ready().get(base), "replaced");
            assertTrue(launcher.attemptsPresent().contains(abandoned), "not yet stale: ttl + MARGIN not passed");

            // Past ttl + MARGIN: the dead instance's untaken entry is swept, the taken one stays.
            now.addAndGet(Duration.ofMinutes(6).toSeconds());
            live.tick();
            assertEquals(List.of(), launcher.leftovers(DockerWorkerLauncher.handle(abandoned)), "a dead pool's entry");
            assertFalse(launcher.leftovers(DockerWorkerLauncher.handle(takenFromDead)).isEmpty(),
                    "a taken entry is its attempt's to dispose of");
        } finally {
            live.stop();
            for (String id : launcher.attemptsPresent()) {
                if (DockerWorkerLauncher.POOL_ENTRY.matcher(id).matches() && !launcher.taken(id)) {
                    launcher.dispose(DockerWorkerLauncher.handle(id));
                }
            }
            assertEquals(List.of(), launcher.dispose(DockerWorkerLauncher.handle(x)));
        }
        if (takenFromDead != null) {
            assertEquals(List.of(), launcher.leftovers(DockerWorkerLauncher.handle(takenFromDead)));
        }
    }

    @Test
    public void aFailedWarmUpLeavesNothingAndTheBaseWaits() throws Exception {
        // A base the registry does not hold: the warm-up fails, the entry is disposed of.
        String missing = registry.name + ":5000/base/busybox@sha256:" + "0".repeat(64);
        AtomicLong now = new AtomicLong(java.time.Instant.now().getEpochSecond());
        WorkerPool pool = pool(missing, now);
        java.util.Set<String> before = launcher.attemptsPresent();
        pool.tick();
        assertEquals(1, pool.fills.get(), "one entry was tried");
        assertEquals(List.of(), pool.ready().get(missing));
        java.util.Set<String> after = launcher.attemptsPresent();
        after.removeAll(before);
        assertEquals(java.util.Set.of(), after, "the failed entry is gone");
        // Backed off: the next round within the minute makes nothing.
        pool.tick();
        assertEquals(1, pool.fills.get(), "backed off");
        assertEquals(List.of(), pool.ready().get(missing));
        assertEquals(java.util.Set.of(), launcher.attemptsPresent().stream().filter(i -> !before.contains(i))
                .collect(java.util.stream.Collectors.toSet()));
    }
}
