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

import com.sun.net.httpserver.HttpServer;
import eu.openanalytics.shinyproxy.publisher.build.BuildsConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The trusted side's readers, without a database: DCF, R dependency fields and versions, the
 * source tarball, the settings and the HTTP fetcher (T7b step 2b).
 */
public class SourceReadersTest {

    private static final SourceTarball.Limits ROOMY = new SourceTarball.Limits(1L << 30, 1L << 30);

    private static SourceTarball.Read read(byte[] tgz, String pkg) throws Exception {
        return SourceTarball.read(new ByteArrayInputStream(tgz), pkg, ROOMY);
    }

    private static void refused(String code, byte[] tgz, String pkg) {
        SourceRefusal r = assertThrows(SourceRefusal.class, () -> read(tgz, pkg), code);
        assertEquals(code, r.code(), r.getMessage());
    }

    private static String desc() {
        return TarBuilder.description("foo", "1.0", "");
    }

    // ------------------------------------------------------------------ DCF

    @Test
    public void dcfReadsFieldsContinuationsAndRecords() throws Exception {
        List<Map<String, String>> r = Dcf.parse("Package: a\nImports: x,\n    y (>= 1.0)\n\n\nPackage: b\n", 10);
        assertEquals(2, r.size());
        assertEquals("x,\ny (>= 1.0)", r.get(0).get("Imports"));
        assertEquals("b", r.get(1).get("Package"));
    }

    @Test
    public void dcfRefusesWhatTwoReadersCouldReadDifferently() {
        assertThrows(Dcf.Malformed.class, () -> Dcf.parse("Package: a\nPackage: b\n", 10), "a field twice");
        assertThrows(Dcf.Malformed.class, () -> Dcf.parse(" continued\nPackage: a\n", 10), "continuation first");
        assertThrows(Dcf.Malformed.class, () -> Dcf.parse("Package: a\nnot a field\n", 10), "a stray line");
        assertThrows(Dcf.Malformed.class, () -> Dcf.parse("A: 1\n\nA: 2\n\nA: 3\n", 2), "too many records");
    }

    // ------------------------------------------------------------------ R dependencies

    @Test
    public void dependencyFieldsReadAsRReadsThem() throws Exception {
        List<RDependencies.Requirement> r = RDependencies.parse("Imports",
            "R (>= 4.1.0), methods,\n  Rcpp(>=1.0.10) , data.table (== 1.15.4),");
        assertEquals(List.of(new RDependencies.Requirement("R", ">=", "4.1.0"),
            new RDependencies.Requirement("methods", null, null), new RDependencies.Requirement("Rcpp", ">=", "1.0.10"),
            new RDependencies.Requirement("data.table", "==", "1.15.4")), r);
        assertTrue(RDependencies.parse("Imports", null).isEmpty());
        for (String bad : new String[] {"a b", "Rcpp (>= x)", "Rcpp (~ 1.0)", "1abc", "Rcpp (>= 1.0", "../x"}) {
            assertThrows(RDependencies.Unreadable.class, () -> RDependencies.parse("Imports", bad), bad);
        }
    }

    @Test
    public void versionsCompareInROrder() {
        assertTrue(RDependencies.compare("1.0", "1.0.1") < 0);
        assertEquals(0, RDependencies.compare("1.0-1", "1.0.1"));
        assertTrue(RDependencies.compare("1.10", "1.9") > 0);
        assertTrue(RDependencies.compare("2.0", "1.99.99") > 0);
        assertTrue(RDependencies.compare("1.0." + "9".repeat(30), "1.0.1") > 0);
        RDependencies.Requirement ge = new RDependencies.Requirement("x", ">=", "1.2");
        assertTrue(ge.satisfiedBy("1.2"));
        assertFalse(ge.satisfiedBy("1.1.9"));
        assertTrue(new RDependencies.Requirement("x", "<", "2.0").satisfiedBy("1.9"));
        assertFalse(new RDependencies.Requirement("x", "<", "2.0").satisfiedBy("2.0"));
        assertTrue(new RDependencies.Requirement("x", "<=", "2.0").satisfiedBy("2.0"));
        assertFalse(new RDependencies.Requirement("x", ">", "2.0").satisfiedBy("2.0"));
        assertFalse(new RDependencies.Requirement("x", "==", "2.0").satisfiedBy("2.0.1"));
        assertTrue(new RDependencies.Requirement("x", "!=", "2.0").satisfiedBy("2.0.1"));
        assertTrue(new RDependencies.Requirement("x", null, null).satisfiedBy("0.1"));
        assertTrue(new RDependencies.Requirement("x", ">=", "1").satisfiedBy("1.0"), "a one-number constraint");
        assertTrue(RDependencies.compare("1.0", "1") > 0, "R: the longer of two equal prefixes is the larger");
    }

    @Test
    public void basePackagesAreExactlyRsFourteen() {
        assertEquals(14, RDependencies.BASE.size());
        assertFalse(RDependencies.BASE.contains("Matrix"), "recommended packages are CRAN's");
    }

    // ------------------------------------------------------------------ the tarball

    @Test
    public void aSourceTarballGivesItsDigestsOverEveryByteAndItsDescription() throws Exception {
        byte[] tgz = TarBuilder.source("foo", desc());
        byte[] trailing = Arrays.copyOf(tgz, tgz.length + 7);
        SourceTarball.Read r = read(tgz, "foo");
        assertEquals(TarBuilder.digest("SHA-256", tgz), r.sha256());
        assertEquals(TarBuilder.digest("MD5", tgz), r.md5());
        assertEquals(tgz.length, r.bytes());
        assertEquals(desc(), r.description());
        SourceTarball.Read t = read(trailing, "foo");
        assertEquals(TarBuilder.digest("SHA-256", trailing), t.sha256(), "bytes after the gzip stream are digested");
    }

    @Test
    public void bytesAfterTheGzipStreamAreDigestedOnASlowStreamToo() throws Exception {
        // GZIPInputStream looks for a further member only in bytes it already buffered (64 KiB
        // here) or that are available; over a network available() is usually 0, and then only
        // the final drain digests what lies beyond the buffer.
        byte[] tgz = TarBuilder.source("foo", desc());
        byte[] trailing = Arrays.copyOf(tgz, tgz.length + 200 * 1024);
        Arrays.fill(trailing, tgz.length, trailing.length, (byte) 'x');
        InputStream slow = new ByteArrayInputStream(trailing) {
            @Override
            public synchronized int available() {
                return 0;
            }
        };
        SourceTarball.Read r = SourceTarball.read(slow, "foo", ROOMY);
        assertEquals(trailing.length, r.bytes());
        assertEquals(TarBuilder.digest("SHA-256", trailing), r.sha256());
        assertEquals(TarBuilder.digest("MD5", trailing), r.md5());
    }

    @Test
    public void notGzipIsRefused() {
        refused("TARBALL_UNREADABLE", "not a tarball".getBytes(StandardCharsets.US_ASCII), "foo");
    }

    @Test
    public void aMemberOutsideThePackagesDirectoryIsRefused() {
        refused("NOT_ONE_TOP_DIRECTORY", new TarBuilder().file("foo/DESCRIPTION", desc()).file("bar/x", "1").tgz(), "foo");
        refused("NOT_ONE_TOP_DIRECTORY", new TarBuilder().file("foo/DESCRIPTION", desc()).file("/foo/x", "1").tgz(), "foo");
        refused("NOT_ONE_TOP_DIRECTORY", new TarBuilder().file("foo/DESCRIPTION", desc()).file("foobar/x", "1").tgz(), "foo");
    }

    @Test
    public void aDotDotOrDotSegmentIsRefused() {
        refused("NOT_ONE_TOP_DIRECTORY", new TarBuilder().file("foo/../foo/DESCRIPTION", desc()).tgz(), "foo");
        refused("NOT_ONE_TOP_DIRECTORY", new TarBuilder().file("foo/./DESCRIPTION", desc()).tgz(), "foo");
    }

    @Test
    public void theDescriptionIsOneRegularFile() {
        refused("DESCRIPTION_UNREADABLE", new TarBuilder().file("foo/R/a.R", "1").tgz(), "foo");
        refused("DESCRIPTION_UNREADABLE", new TarBuilder().file("foo/DESCRIPTION", desc())
            .file("foo/DESCRIPTION", desc()).tgz(), "foo");
        refused("DESCRIPTION_UNREADABLE", new TarBuilder().symlink("foo/DESCRIPTION").tgz(), "foo");
        refused("DESCRIPTION_UNREADABLE", new TarBuilder().file("foo/DESCRIPTION",
            "X".repeat(SourceTarball.MAX_DESCRIPTION + 1)).tgz(), "foo");
    }

    @Test
    public void aLongNameOrPaxPathNamesTheNextMember() throws Exception {
        assertEquals(desc(), read(new TarBuilder().longName("foo/DESCRIPTION").file("foo/x", desc()).tgz(), "foo")
            .description());
        assertEquals(desc(), read(new TarBuilder().paxPath("foo/DESCRIPTION").file("foo/x", desc()).tgz(), "foo")
            .description());
        refused("NOT_ONE_TOP_DIRECTORY", new TarBuilder().file("foo/DESCRIPTION", desc()).paxPath("../x")
            .file("foo/x", "1").tgz(), "foo");
    }

    @Test
    public void twoMetadataHeadersForOneMemberAreRefused() {
        refused("TARBALL_UNREADABLE", new TarBuilder().longName("foo/DESCRIPTION").paxPath("foo/x")
            .file("foo/y", desc()).tgz(), "foo");
    }

    @Test
    public void aGlobalHeaderNamingAPathIsRefused() throws Exception {
        refused("TARBALL_UNREADABLE", new TarBuilder().globalPax("path", "foo/DESCRIPTION")
            .file("foo/DESCRIPTION", desc()).tgz(), "foo");
        assertEquals(desc(), read(new TarBuilder().globalPax("comment", "x").file("foo/DESCRIPTION", desc()).tgz(),
            "foo").description());
    }

    @Test
    public void headersRCmdBuildNeverWritesAreRefused() {
        byte[] badSum = TarBuilder.header("foo/DESCRIPTION", '0', 0);
        badSum[0] ^= 1;
        refused("TARBALL_UNREADABLE", TarBuilder.gzip(new TarBuilder().raw(badSum).tar()), "foo");
        byte[] base256 = TarBuilder.header("foo/DESCRIPTION", '0', 0);
        base256[124] = (byte) 0x80;
        TarBuilder.checksum(base256);
        refused("TARBALL_UNREADABLE", TarBuilder.gzip(new TarBuilder().raw(base256).tar()), "foo");
        refused("TARBALL_UNREADABLE", new TarBuilder().file("foo/DESCRIPTION", desc())
            .entry("foo/sparse", 'S', new byte[0]).tgz(), "foo");
        refused("TARBALL_UNREADABLE", TarBuilder.gzip(new TarBuilder().file("foo/DESCRIPTION", desc()).tarWithoutEnd()),
            "foo");
    }

    @Test
    public void aTransferCutShortIsAFailureNotARefusal() {
        byte[] tgz = TarBuilder.source("foo", desc());
        byte[] cut = Arrays.copyOf(tgz, tgz.length / 2);
        IOException ex = assertThrows(IOException.class, () -> read(cut, "foo"));
        assertTrue(ex instanceof EOFException, ex.toString());
    }

    @Test
    public void sizesAreBounded() {
        byte[] tgz = TarBuilder.source("foo", desc());
        SourceRefusal raw = assertThrows(SourceRefusal.class, () -> SourceTarball.read(new ByteArrayInputStream(tgz),
            "foo", new SourceTarball.Limits(tgz.length - 1, 1L << 30)));
        assertEquals("TOO_LARGE", raw.code());
        byte[] bomb = new TarBuilder().file("foo/DESCRIPTION", desc()).file("foo/zeros", "\0".repeat(1 << 20)).tgz();
        assertTrue(bomb.length < 10_000);
        SourceRefusal expanded = assertThrows(SourceRefusal.class, () -> SourceTarball.read(new ByteArrayInputStream(bomb),
            "foo", new SourceTarball.Limits(1L << 30, 512 * 1024)));
        assertEquals("TOO_LARGE", expanded.code());
    }

    @Test
    public void aPaxRecordThatChangesFramingIsRefused() {
        refused("TARBALL_UNREADABLE", new TarBuilder().file("foo/DESCRIPTION", desc())
            .entry("PaxHeader", 'x', TarBuilder.pax("size", "0")).file("foo/x", "abc").tgz(), "foo");
        refused("TARBALL_UNREADABLE", new TarBuilder().file("foo/DESCRIPTION", desc())
            .entry("PaxHeader", 'x', TarBuilder.pax("GNU.sparse.map", "0,1")).file("foo/x", "abc").tgz(), "foo");
        refused("TARBALL_UNREADABLE", new TarBuilder().globalPax("size", "0").file("foo/DESCRIPTION", desc()).tgz(),
            "foo");
    }

    @Test
    public void anOldGnuHeadersTimesAreNotReadAsAPrefix() throws Exception {
        // Old GNU: magic "ustar  \0", and bytes 345+ hold atime/ctime, not a name prefix.
        byte[] h = TarBuilder.header("foo/DESCRIPTION", '0', desc().length());
        byte[] magic = "ustar  \0".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(magic, 0, h, 257, magic.length);
        byte[] times = "14672035401".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(times, 0, h, 345, times.length);
        TarBuilder.checksum(h);
        byte[] content = Arrays.copyOf(desc().getBytes(StandardCharsets.UTF_8), 512);
        assertEquals(desc(), read(TarBuilder.gzip(new TarBuilder().raw(h).raw(content).tar()), "foo").description());
    }

    // ------------------------------------------------------------------ the watchdog

    private static java.util.concurrent.ScheduledExecutorService timer() {
        return java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
    }

    @Test
    @Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void aReadThatBlocksIsStoppedAtTheDeadlineWhileTheHeartbeatBeats() throws Exception {
        var timer = timer();
        var timer2 = timer();
        try (java.io.PipedOutputStream never = new java.io.PipedOutputStream();
             InputStream blocked = new java.io.PipedInputStream(never)) {
            AtomicInteger beats = new AtomicInteger();
            long start = System.nanoTime();
            IOException failure;
            try (TransferGuard guard = new TransferGuard(timer, timer2, Duration.ofMillis(1500), Duration.ofMillis(100), () -> {
                beats.incrementAndGet();
                return true;
            })) {
                InputStream in = guard.watch(blocked);
                IOException ex = assertThrows(IOException.class, in::read);
                failure = guard.failure(ex);
            }
            long ms = (System.nanoTime() - start) / 1_000_000;
            assertTrue(failure instanceof java.net.SocketTimeoutException, failure.toString());
            assertTrue(ms >= 1400 && ms < 5000, "stopped at the deadline: " + ms + " ms");
            assertTrue(beats.get() >= 8, "the heartbeat beat during the stall: " + beats.get());
            assertFalse(Thread.currentThread().isInterrupted(), "no interrupt leaks past close()");
        } finally {
            timer.shutdownNow();
            timer2.shutdownNow();
        }
    }

    @Test
    @Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void aLostLeaseStopsABlockedRead() throws Exception {
        var timer = timer();
        var timer2 = timer();
        try (java.io.PipedOutputStream never = new java.io.PipedOutputStream();
             InputStream blocked = new java.io.PipedInputStream(never)) {
            long start = System.nanoTime();
            IOException failure;
            try (TransferGuard guard = new TransferGuard(timer, timer2, Duration.ofMinutes(5), Duration.ofMillis(200),
                    () -> false)) {
                InputStream in = guard.watch(blocked);
                failure = guard.failure(assertThrows(IOException.class, in::read));
                assertEquals(TransferGuard.Reason.LEASE_LOST, guard.reason());
            }
            assertTrue(failure instanceof TransferGuard.LeaseLost, failure.toString());
            assertTrue((System.nanoTime() - start) / 1_000_000 < 5000);
            assertFalse(Thread.currentThread().isInterrupted());
        } finally {
            timer.shutdownNow();
            timer2.shutdownNow();
        }
    }

    @Test
    @Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void aReadThatIgnoresInterruptsIsStoppedByClosingIt() throws Exception {
        var timer = timer();
        var timer2 = timer();
        try {
            long start = System.nanoTime();
            IOException failure;
            try (TransferGuard guard = new TransferGuard(timer, timer2, Duration.ofMillis(1000), Duration.ofMillis(100),
                    () -> true)) {
                InputStream in = guard.watch(new Uninterruptible());
                failure = guard.failure(assertThrows(IOException.class, in::read));
            }
            assertTrue(failure instanceof java.net.SocketTimeoutException, failure.toString());
            assertTrue((System.nanoTime() - start) / 1_000_000 < 5000);
            assertFalse(Thread.currentThread().isInterrupted());
        } finally {
            timer.shutdownNow();
            timer2.shutdownNow();
        }
    }

    @Test
    @Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void aRenewalThatBlocksDoesNotDelayTheDeadline() throws Exception {
        var timer = timer();
        var timer2 = timer();
        try (java.io.PipedOutputStream never = new java.io.PipedOutputStream();
             InputStream blocked = new java.io.PipedInputStream(never)) {
            long start = System.nanoTime();
            IOException failure;
            try (TransferGuard guard = new TransferGuard(timer, timer2, Duration.ofMillis(1000), Duration.ofMillis(50),
                    () -> {
                        try {
                            Thread.sleep(20_000);
                        } catch (InterruptedException ignored) {
                            Thread.currentThread().interrupt();
                        }
                        return true;
                    })) {
                InputStream in = guard.watch(blocked);
                failure = guard.failure(assertThrows(IOException.class, in::read));
            }
            long ms = (System.nanoTime() - start) / 1_000_000;
            assertTrue(failure instanceof java.net.SocketTimeoutException, failure.toString());
            assertTrue(ms < 5000, "the deadline fired on time while a renewal was blocked: " + ms + " ms");
        } finally {
            timer.shutdownNow();
            timer2.shutdownNow();
        }
    }

    @Test
    public void aTransferThatEndsInTimeIsLeftAlone() throws Exception {
        var timer = timer();
        var timer2 = timer();
        try (TransferGuard guard = new TransferGuard(timer, timer2, Duration.ofSeconds(30), Duration.ofSeconds(10), () -> true)) {
            InputStream in = guard.watch(new ByteArrayInputStream("abc".getBytes(StandardCharsets.US_ASCII)));
            assertEquals("abc", new String(in.readAllBytes(), StandardCharsets.US_ASCII));
            assertEquals(TransferGuard.Reason.NONE, guard.reason());
            IOException own = new IOException("own");
            assertTrue(guard.failure(own) == own, "an unrelated failure is itself");
        } finally {
            timer.shutdownNow();
            timer2.shutdownNow();
        }
    }

    // ------------------------------------------------------------------ settings

    @Test
    public void sourceSettingsTakeOnlyAPlainHttpRepositoryEndingInASlash() {
        assertNull(SourceSettings.DEFAULTS.repository(), "off by default");
        assertEquals(URI.create("http://forge:8080/repository/cran-proxy/"), SourceSettings.fromOverrides(
            Map.of("source-repository", "http://forge:8080/repository/cran-proxy/")).repository());
        for (String bad : new String[] {"http://forge/repository/cran-proxy", "ftp://forge/x/", "http://u:p@forge/x/",
                "http://forge/x/?a=1", "http://forge/x/#f", "forge/x/", "file:///x/"}) {
            assertThrows(IllegalArgumentException.class, () -> SourceSettings.fromOverrides(
                Map.of("source-repository", bad)), bad);
        }
        assertThrows(IllegalArgumentException.class, () -> SourceSettings.fromOverrides(Map.of("source-max-siz", "1")));
        assertThrows(IllegalArgumentException.class, () -> SourceSettings.fromOverrides(Map.of("source-max-size", "0")));
        assertEquals(1234, SourceSettings.fromOverrides(Map.of("source-max-size", "1234")).maxSourceBytes(),
            "a bare number is bytes");
        assertEquals(64L << 20, SourceSettings.fromOverrides(Map.of("source-max-index-size", "64MB")).maxIndexBytes());
        assertEquals(2L << 30, SourceSettings.fromOverrides(Map.of("source-max-expanded-size", "2GB"))
            .maxExpandedBytes());
        assertThrows(IllegalArgumentException.class, () -> SourceSettings.fromOverrides(
            Map.of("source-max-size", "lots")));
        SourceSettings behind = SourceSettings.fromOverrides(Map.of("source-behind-retry", "60",
            "source-behind-max-retry", "10m", "source-behind-bound", "72h"));
        assertEquals(Duration.ofSeconds(60), behind.behindRetry());
        assertEquals(Duration.ofSeconds(600), behind.behindMaxRetry());
        assertThrows(IllegalArgumentException.class, () -> SourceSettings.fromOverrides(
            Map.of("source-fetch-timeout", "2500ms")), "not whole seconds");
        // Renamed outright (T7c 0a): the old names are refused, and the error names the new ones.
        for (String old : List.of("source-max-bytes", "source-max-index-bytes", "source-max-expanded-bytes",
                "source-fetch-timeout-seconds", "source-transfer-deadline-seconds", "source-index-ttl-seconds",
                "source-behind-retry-seconds", "source-behind-max-retry-seconds", "source-behind-bound-hours")) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> SourceSettings.fromOverrides(Map.of(old, "1")), old);
            assertTrue(e.getMessage().startsWith("unknown setting 'skald.builds.binaries." + old + "'")
                && e.getMessage().contains("source-max-size") && e.getMessage().contains("source-behind-bound,"),
                e.getMessage());
        }
        assertEquals(Duration.ofHours(72), behind.behindBound());
        assertEquals(Duration.ofHours(48), SourceSettings.DEFAULTS.behindBound(), "a day past forge's 24 h index cache");
        assertFalse(SourceSettings.DEFAULTS.archived(), "archived versions are opt-in");
        assertTrue(SourceSettings.fromOverrides(Map.of("source-archived", "true")).archived());
        assertFalse(SourceSettings.fromOverrides(Map.of("source-archived", "false")).archived());
        assertThrows(IllegalArgumentException.class, () -> SourceSettings.fromOverrides(Map.of("source-archived", "yes")));
        assertThrows(IllegalArgumentException.class, () -> SourceSettings.fromOverrides(Map.of(
            "source-behind-retry", "600", "source-behind-max-retry", "60")), "max below first");
        assertThrows(IllegalArgumentException.class, () -> SourceSettings.fromOverrides(Map.of(
            "source-behind-retry", "0")));
    }

    @Test
    public void theApplicationSplitsSourceKeysFromQueueKeys() {
        ApplicationContextRunner context = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(BuildsConfig.class);
        context.withPropertyValues("skald.builds.binaries.max-running=2",
            "skald.builds.binaries.source-repository=https://forge.example/repository/cran-proxy/").run(ctx -> {
                assertEquals(2, ctx.getBean(BinaryQueueSettings.class).maxRunning());
                assertEquals(URI.create("https://forge.example/repository/cran-proxy/"),
                    ctx.getBean(SourceSettings.class).repository());
            });
        context.withPropertyValues("skald.builds.binaries.source-repositry=https://x/").run(ctx ->
            assertTrue(ctx.getStartupFailure() != null, "a typo stops startup"));
    }

    // ------------------------------------------------------------------ the HTTP fetcher

    @Test
    public void theFetcherReadsA200AndNeverFollowsARedirect() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            int status = switch (path) {
                case "/ok" -> 200;
                case "/gone" -> 410;
                case "/moved" -> 302;
                case "/broken" -> 500;
                default -> 404;
            };
            if (status == 302) {
                exchange.getResponseHeaders().add("Location", "/ok");
            }
            byte[] body = (status == 200 ? "hello" : "no").getBytes(StandardCharsets.US_ASCII);
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            HttpSourceFetcher fetcher = new HttpSourceFetcher(Duration.ofSeconds(5));
            try (InputStream in = fetcher.open(URI.create(base + "/ok"))) {
                assertEquals("hello", new String(in.readAllBytes(), StandardCharsets.US_ASCII));
            }
            assertThrows(SourceFetcher.NotFound.class, () -> fetcher.open(URI.create(base + "/missing")));
            assertThrows(SourceFetcher.NotFound.class, () -> fetcher.open(URI.create(base + "/gone")));
            IOException moved = assertThrows(IOException.class, () -> fetcher.open(URI.create(base + "/moved")));
            assertFalse(moved instanceof SourceFetcher.NotFound);
            assertTrue(moved.getMessage().contains("302"), moved.getMessage());
            assertThrows(IOException.class, () -> fetcher.open(URI.create(base + "/broken")));
        } finally {
            server.stop(0);
        }
    }
}
