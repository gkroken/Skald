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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The build workspace, held by one process at a time (T7 part 4e-4).
 *
 * <p>The workspace is where a build's bundle is extracted (BundleContextSource), each into an
 * {@code extract-<uuid>} tree that is frozen read-only and deleted when the attempt ends. A
 * process that dies mid-build never deletes its tree, and nothing else did: the T7 pass
 * probe's crash row left one behind, and every crash would have added another. So the
 * process takes an exclusive lock on {@value #LOCK_NAME} in the workspace for as long as it
 * runs, and only while holding it removes the {@code extract-*} trees it finds: with the
 * lock held, no live process can own them. A second process on the same workspace is
 * refused at startup rather than having its trees removed under it.
 *
 * <p>The lock is an OS file lock (fcntl on Linux), released by the kernel when the process
 * dies, however it dies, so a crash never leaves the workspace locked.
 */
public final class WorkspaceLock implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceLock.class);

    static final String LOCK_NAME = ".skald-workspace.lock";
    /** ExtractionRoot.createUnder's names, and nothing else: unknown entries are left alone. */
    private static final Pattern EXTRACTION =
            Pattern.compile("extract-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    private final FileChannel channel;
    private final FileLock lock;
    private final List<String> removed;

    private WorkspaceLock(FileChannel channel, FileLock lock, List<String> removed) {
        this.channel = channel;
        this.lock = lock;
        this.removed = removed;
    }

    /**
     * Locks {@code workspace} for this process and removes what a dead one left in it.
     *
     * @throws IllegalStateException if another live process (or this one) holds it
     */
    public static WorkspaceLock claim(Path workspace) throws IOException {
        FileChannel channel = FileChannel.open(workspace.resolve(LOCK_NAME), StandardOpenOption.CREATE,
                StandardOpenOption.WRITE);
        FileLock lock;
        try {
            lock = channel.tryLock();
        } catch (OverlappingFileLockException ours) {
            lock = null;
        } catch (IOException | RuntimeException e) {
            channel.close();
            throw e;
        }
        if (lock == null) {
            channel.close();
            throw new IllegalStateException("workspace " + workspace + " is in use by another process: each"
                    + " build worker process needs a workspace of its own");
        }
        List<String> removed = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(workspace)) {
            for (Path entry : entries) {
                String name = entry.getFileName().toString();
                if (EXTRACTION.matcher(name).matches()
                        && Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
                    removeTree(entry);
                    removed.add(name);
                }
            }
        } catch (IOException | RuntimeException e) {
            lock.release();
            channel.close();
            throw e;
        }
        if (!removed.isEmpty()) {
            log.info("workspace {}: removed {} extraction tree(s) a previous process left", workspace,
                    removed.size());
        }
        return new WorkspaceLock(channel, lock, removed);
    }

    /** The extraction trees this claim removed, by name. */
    public List<String> removed() {
        return removed;
    }

    /**
     * Deletes a tree without following any link: directories are made writable (a frozen
     * tree's are not) before their entries are removed, and a symbolic link is removed as
     * the link it is, never its target.
     */
    static void removeTree(Path root) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException e) throws IOException {
                if (e != null) {
                    throw e;
                }
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    @Override
    public void close() throws IOException {
        try {
            lock.release();
        } finally {
            channel.close();
        }
    }
}
