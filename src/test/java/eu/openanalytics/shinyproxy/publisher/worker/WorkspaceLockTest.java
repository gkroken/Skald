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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The workspace lock: one process per workspace, and what a dead one left is removed once
 * the lock is held (T7 part 4e-4: the pass probe's crash row left a frozen extraction tree).
 */
public class WorkspaceLockTest {

    private static Path frozenTree(Path workspace) throws Exception {
        Path root = workspace.resolve("extract-" + UUID.randomUUID());
        Path deep = Files.createDirectories(root.resolve("app/sub"));
        Files.writeString(deep.resolve("app.R"), "x");
        Files.writeString(root.resolve("renv.lock"), "{}");
        // As ExtractionRoot freezes a tree: files r--, directories r-x.
        for (Path f : List.of(deep.resolve("app.R"), root.resolve("renv.lock"))) {
            Files.setPosixFilePermissions(f, PosixFilePermissions.fromString("r--------"));
        }
        for (Path d : List.of(deep, root.resolve("app"), root)) {
            Files.setPosixFilePermissions(d, PosixFilePermissions.fromString("r-x------"));
        }
        return root;
    }

    @Test
    public void aFrozenTreeADeadProcessLeftIsRemovedOnceTheLockIsHeld(@TempDir Path workspace) throws Exception {
        Path left = frozenTree(workspace);
        try (WorkspaceLock lock = WorkspaceLock.claim(workspace)) {
            assertFalse(Files.exists(left), "the dead process's tree is gone");
            assertEquals(List.of(left.getFileName().toString()), lock.removed());
        }
    }

    @Test
    public void aWorkspaceAlreadyHeldIsRefusedAndFreeAgainOnceClosed(@TempDir Path workspace) throws Exception {
        Path live;
        try (WorkspaceLock first = WorkspaceLock.claim(workspace)) {
            live = frozenTree(workspace);
            IllegalStateException e = assertThrows(IllegalStateException.class, () -> WorkspaceLock.claim(workspace));
            assertTrue(e.getMessage().contains("in use by another process"), e.getMessage());
            assertTrue(Files.exists(live), "a refused claim removes nothing: the tree is the holder's");
        }
        try (WorkspaceLock again = WorkspaceLock.claim(workspace)) {
            assertFalse(Files.exists(live));
        }
    }

    /**
     * Holds the workspace lock from another process: what a second build worker on the same
     * workspace looks like. Prints "held" once it has the lock and keeps it until its stdin
     * closes.
     */
    public static final class Holder {
        public static void main(String[] args) throws Exception {
            try (var channel = java.nio.channels.FileChannel.open(Path.of(args[0]).resolve(WorkspaceLock.LOCK_NAME),
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE);
                 var lock = channel.lock()) {
                System.out.println("held");
                System.out.flush();
                while (System.in.read() >= 0) {
                    // until the test closes our stdin
                }
            }
        }
    }

    @Test
    public void aWorkspaceAnotherProcessHoldsIsRefused(@TempDir Path workspace) throws Exception {
        // 6890924 N1: the production branch -- another process holds the lock, so tryLock
        // answers null -- rather than the same-JVM overlap the test above takes.
        Path live = frozenTree(workspace);
        Process holder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), Holder.class.getName(), workspace.toString())
                .redirectErrorStream(true).start();
        try {
            var out = new java.io.BufferedReader(new java.io.InputStreamReader(holder.getInputStream(),
                    java.nio.charset.StandardCharsets.UTF_8));
            assertEquals("held", out.readLine(), "the other process holds the lock");
            IllegalStateException e = assertThrows(IllegalStateException.class, () -> WorkspaceLock.claim(workspace));
            assertTrue(e.getMessage().contains("in use by another process"), e.getMessage());
            assertTrue(Files.exists(live), "a refused claim removes nothing: the tree is the holder's");
        } finally {
            holder.getOutputStream().close();
            assertTrue(holder.waitFor(30, java.util.concurrent.TimeUnit.SECONDS), "the holder exited");
        }
        // Its death releases the lock, as a crash would.
        try (WorkspaceLock lock = WorkspaceLock.claim(workspace)) {
            assertFalse(Files.exists(live));
        }
    }

    @Test
    public void entriesThatAreNotExtractionTreesAreLeftAlone(@TempDir Path workspace) throws Exception {
        Path upload = Files.createDirectories(workspace.resolve("upload"));
        Path odd = Files.createDirectories(workspace.resolve("extract-not-a-uuid"));
        Path file = Files.writeString(workspace.resolve("extract-" + UUID.randomUUID()), "a file, not a tree");
        try (WorkspaceLock lock = WorkspaceLock.claim(workspace)) {
            assertTrue(Files.exists(upload) && Files.exists(odd) && Files.exists(file));
            assertEquals(List.of(), lock.removed());
        }
    }

    @Test
    public void aLinkInsideALeftoverTreeIsRemovedAsALinkNeverFollowed(@TempDir Path workspace, @TempDir Path outside)
            throws Exception {
        Path target = Files.writeString(Files.createDirectories(outside.resolve("keep")).resolve("data"), "keep");
        Path root = Files.createDirectories(workspace.resolve("extract-" + UUID.randomUUID()));
        Files.createSymbolicLink(root.resolve("escape"), outside.resolve("keep"));
        try (WorkspaceLock lock = WorkspaceLock.claim(workspace)) {
            assertFalse(Files.exists(root));
            assertTrue(Files.exists(target), "the link's target is untouched");
        }
    }

    @Test
    public void aLinkNamedLikeAnExtractionTreeIsNotFollowed(@TempDir Path workspace, @TempDir Path outside)
            throws Exception {
        Path target = Files.writeString(outside.resolve("data"), "keep");
        Path link = Files.createSymbolicLink(workspace.resolve("extract-" + UUID.randomUUID()), outside);
        try (WorkspaceLock lock = WorkspaceLock.claim(workspace)) {
            assertTrue(Files.exists(target), "the directory it points at is untouched");
            assertTrue(Files.isSymbolicLink(link), "and the link is not taken for a tree");
            assertEquals(List.of(), lock.removed());
        }
    }
}
