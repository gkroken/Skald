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

import eu.openanalytics.shinyproxy.publisher.recipe.RecipeGenerator.Base;
import eu.openanalytics.shinyproxy.publisher.recipe.RecipeGenerator.Recipe;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The sandbox's recipe and the trusted side's check of what it produced (T7b step 2c, part
 * 1): pure, no Docker; the sandbox itself runs in part 2.
 */
public class PackageSandboxTest {

    private static final Base BASE = new Base("skald-registry:5000/skald/base/r@sha256:" + "a".repeat(64),
        "r", "4.6.1", "10001", "10001", "noble");
    private static final String BUILT = "R 4.6.1; x86_64-pc-linux-gnu; 2026-10-11 10:00:00 UTC; unix";

    private static PackageRecipe.HandedIn handed(String pkg, String version, byte[] bytes) {
        return new PackageRecipe.HandedIn(pkg, version, bytes, TarBuilder.digest("SHA-256", bytes));
    }

    private static String binaryDescription(String pkg, String version, String built) {
        return "Package: " + pkg + "\nVersion: " + version + "\nBuilt: " + built + "\n";
    }

    /** An installed package as R CMD INSTALL leaves it, packed. */
    private static TarBuilder installed(String pkg, String version, String built) {
        return new TarBuilder().dir(pkg + "/").file(pkg + "/DESCRIPTION", binaryDescription(pkg, version, built))
            .dir(pkg + "/Meta/").file(pkg + "/Meta/package.rds", "rds").dir(pkg + "/R/").file(pkg + "/R/" + pkg, "x");
    }

    private static BinaryArchive.Checked check(byte[] tgz) throws BinaryArchive.Refused {
        return BinaryArchive.check(tgz, "foo", "1.0", "4.6.1", "x86_64-pc-linux-gnu", BinaryArchive.Limits.DEFAULTS);
    }

    private static void refused(String code, byte[] tgz) {
        BinaryArchive.Refused r = assertThrows(BinaryArchive.Refused.class, () -> check(tgz));
        assertEquals(code, r.code(), r.getMessage());
    }

    // ------------------------------------------------------------------ the recipe

    @Test
    public void theRecipeHandsInTheSourceAndDependenciesAndInstallsWithoutNetwork() throws Exception {
        byte[] src = TarBuilder.source("foo", TarBuilder.description("foo", "1.0", ""));
        byte[] dep1 = installed("bar", "2.0", BUILT).tgz();
        byte[] dep2 = installed("baz", "3.0", BUILT).tgz();
        Recipe r = PackageRecipe.render(BASE, handed("foo", "1.0", src),
            List.of(handed("bar", "2.0", dep1), handed("baz", "3.0", dep2)));
        assertEquals("FROM " + BASE.reference() + " AS build\n"
            + "USER 10001:10001\n"
            + "COPY --chown=10001:10001 skald/ /tmp/skald-work/\n"
            + "RUN --network=none [\"sh\", \"-eu\", \"/tmp/skald-work/build.sh\"]\n"
            + "FROM scratch\n"
            + "COPY --from=build /tmp/skald-work/out/binary.tar.gz /binary.tar.gz\n", r.dockerfile());
        assertArrayEquals(src, r.files().get("skald/source.tar.gz"));
        assertArrayEquals(dep1, r.files().get("skald/dep-0001.tar.gz"));
        assertArrayEquals(dep2, r.files().get("skald/dep-0002.tar.gz"));
        assertEquals(4, r.files().size());
        String script = new String(r.files().get("skald/build.sh"), StandardCharsets.US_ASCII);
        assertTrue(script.contains("pkg='foo'"));
        assertTrue(script.contains("R_LIBS=\"$w/deps\" R CMD INSTALL --library=\"$w/lib\" \"$w/source.tar.gz\""));
        for (String line : r.dockerfile().split("\n")) {
            assertFalse(line.startsWith("ARG") || line.contains("--mount") || line.contains("secret"), line);
            assertTrue(!line.startsWith("RUN") || line.startsWith("RUN --network=none "), "every RUN has no network: " + line);
        }
    }

    @Test
    public void aHandedInFileThatIsNotWhatTheQueueRecordedRendersNothing() {
        byte[] src = TarBuilder.source("foo", TarBuilder.description("foo", "1.0", ""));
        byte[] dep = installed("bar", "2.0", BUILT).tgz();
        assertThrows(PackageRecipe.DigestMismatch.class, () -> PackageRecipe.render(BASE,
            new PackageRecipe.HandedIn("foo", "1.0", src, "0".repeat(64)), List.of()));
        assertThrows(PackageRecipe.DigestMismatch.class, () -> PackageRecipe.render(BASE, handed("foo", "1.0", src),
            List.of(new PackageRecipe.HandedIn("bar", "2.0", dep, TarBuilder.digest("SHA-256", src)))));
    }

    @Test
    public void namesAreRsAndAPackageIsNotItsOwnDependency() {
        byte[] src = TarBuilder.source("foo", TarBuilder.description("foo", "1.0", ""));
        for (String bad : new String[] {"foo'; rm -rf /; '", "a", "foo bar", "../foo"}) {
            assertThrows(IllegalArgumentException.class, () -> PackageRecipe.render(BASE, handed(bad, "1.0", src),
                List.of()), bad);
        }
        assertThrows(IllegalArgumentException.class, () -> PackageRecipe.render(BASE, handed("foo", "1.0;x", src),
            List.of()));
        assertThrows(IllegalArgumentException.class, () -> PackageRecipe.render(BASE, handed("foo", "1.0", src),
            List.of(handed("foo", "1.0", src))));
        assertThrows(IllegalArgumentException.class, () -> PackageRecipe.render(new Base(BASE.reference(),
            "python", "3.13.16", "10001", "10001", "trixie"), handed("foo", "1.0", src), List.of()));
    }

    @Test
    public void aClosureWithTwoVersionsOfOnePackageIsRefused() {
        byte[] src = TarBuilder.source("foo", TarBuilder.description("foo", "1.0", ""));
        byte[] one = installed("bar", "1.0", BUILT).tgz();
        byte[] two = installed("bar", "2.0", BUILT).tgz();
        assertThrows(IllegalArgumentException.class, () -> PackageRecipe.render(BASE, handed("foo", "1.0", src),
            List.of(handed("bar", "1.0", one), handed("bar", "2.0", two))));
    }

    // ------------------------------------------------------------------ the archive check

    @Test
    public void anInstalledPackageForThisBaseIsAccepted() throws Exception {
        byte[] tgz = installed("foo", "1.0", BUILT).tgz();
        BinaryArchive.Checked c = check(tgz);
        assertEquals(TarBuilder.digest("SHA-256", tgz), c.sha256());
        assertEquals(tgz.length, c.bytes());
        assertEquals("1.0", c.description().get("Version"));
        check(installed("foo", "1.0", "R 4.6.1; ; 2026-10-11 10:00:00 UTC; unix").tgz());
    }

    @Test
    public void gnuTarsOwnHeadersAreRead() throws Exception {
        // The sandbox packs with GNU tar: old-GNU magic and a "./"-free layout; long names as 'L'.
        byte[] desc = binaryDescription("foo", "1.0", BUILT).getBytes(StandardCharsets.UTF_8);
        byte[] h = TarBuilder.header("foo/DESCRIPTION", '0', desc.length);
        System.arraycopy("ustar  \0".getBytes(StandardCharsets.US_ASCII), 0, h, 257, 8);
        TarBuilder.checksum(h);
        String longName = "foo/R/" + "x".repeat(150);
        byte[] tgz = new TarBuilder().dir("foo/").raw(h).raw(java.util.Arrays.copyOf(desc, 512))
            .file("foo/Meta/package.rds", "rds").longName(longName).file("foo/ignored", "y").tgz();
        check(tgz);
    }

    @Test
    public void anythingOutsideThePackagesDirectoryIsRefused() {
        refused("BINARY_LAYOUT", installed("foo", "1.0", BUILT).file("bar/x", "1").tgz());
        refused("BINARY_LAYOUT", installed("foo", "1.0", BUILT).file("foobar/x", "1").tgz());
        refused("BINARY_LAYOUT", installed("foo", "1.0", BUILT).file("foo/../x", "1").tgz());
        refused("BINARY_LAYOUT", installed("foo", "1.0", BUILT).file("foo//x", "1").tgz());
        refused("BINARY_LAYOUT", installed("foo", "1.0", BUILT).longName("../x").file("foo/y", "1").tgz());
    }

    @Test
    public void linksAreRefused() {
        refused("BINARY_LAYOUT", installed("foo", "1.0", BUILT).symlink("foo/libs/x.so").tgz());
        refused("BINARY_LAYOUT", installed("foo", "1.0", BUILT).entry("foo/hard", '1', new byte[0]).tgz());
        refused("BINARY_UNREADABLE", installed("foo", "1.0", BUILT).entry("foo/dev", '3', new byte[0]).tgz());
    }

    @Test
    public void theDescriptionMustNameThisPackageForThisBase() {
        refused("BINARY_DESCRIPTION", new TarBuilder().file("foo/DESCRIPTION", binaryDescription("bar", "1.0", BUILT))
            .file("foo/Meta/package.rds", "rds").tgz());
        refused("BINARY_DESCRIPTION", installed("foo", "1.1", BUILT).tgz());
        refused("BINARY_BUILT", installed("foo", "1.0", "R 4.5.2; x86_64-pc-linux-gnu; 2026-10-11; unix").tgz());
        refused("BINARY_BUILT", installed("foo", "1.0", "R 4.6.1; aarch64-unknown-linux-gnu; 2026-10-11; unix").tgz());
        refused("BINARY_BUILT", installed("foo", "1.0", "R 4.6.1; x86_64-pc-linux-gnu; 2026-10-11; windows").tgz());
        refused("BINARY_BUILT", installed("foo", "1.0", "R 4.6.1; x86_64-pc-linux-gnu").tgz());
        refused("BINARY_BUILT", new TarBuilder().file("foo/DESCRIPTION", "Package: foo\nVersion: 1.0\n")
            .file("foo/Meta/package.rds", "rds").tgz());
    }

    @Test
    public void anInstalledPackageHasOneDescriptionAndItsMeta() {
        refused("BINARY_LAYOUT", new TarBuilder().file("foo/Meta/package.rds", "rds").tgz());
        refused("BINARY_LAYOUT", new TarBuilder().file("foo/DESCRIPTION", binaryDescription("foo", "1.0", BUILT)).tgz());
        refused("BINARY_LAYOUT", installed("foo", "1.0", BUILT).file("foo/DESCRIPTION",
            binaryDescription("foo", "1.0", BUILT)).tgz());
    }

    @Test
    public void malformedArchivesAreRefused() {
        refused("BINARY_UNREADABLE", "not gzip".getBytes(StandardCharsets.US_ASCII));
        refused("BINARY_UNREADABLE", TarBuilder.gzip(installed("foo", "1.0", BUILT).tarWithoutEnd()));
        byte[] bad = TarBuilder.header("foo/DESCRIPTION", '0', 0);
        bad[0] ^= 1;
        refused("BINARY_UNREADABLE", TarBuilder.gzip(new TarBuilder().raw(bad).tar()));
        refused("BINARY_UNREADABLE", installed("foo", "1.0", BUILT)
            .entry("PaxHeader", 'x', TarBuilder.pax("size", "1")).file("foo/z", "1").tgz());
        refused("BINARY_UNREADABLE", installed("foo", "1.0", BUILT).longName("foo/a").paxPath("foo/b")
            .file("foo/c", "1").tgz());
    }

    @Test
    public void sizesAreBounded() throws Exception {
        byte[] tgz = installed("foo", "1.0", BUILT).tgz();
        BinaryArchive.Refused big = assertThrows(BinaryArchive.Refused.class, () -> BinaryArchive.check(tgz, "foo",
            "1.0", "4.6.1", "x86_64-pc-linux-gnu", new BinaryArchive.Limits(tgz.length - 1, 1L << 30, 1000)));
        assertEquals("BINARY_TOO_LARGE", big.code());
        BinaryArchive.Refused wide = assertThrows(BinaryArchive.Refused.class, () -> BinaryArchive.check(tgz, "foo",
            "1.0", "4.6.1", "x86_64-pc-linux-gnu", new BinaryArchive.Limits(1L << 30, 1L << 30, 3)));
        assertEquals("BINARY_TOO_LARGE", wide.code());
        BinaryArchive.Refused deep = assertThrows(BinaryArchive.Refused.class, () -> BinaryArchive.check(tgz, "foo",
            "1.0", "4.6.1", "x86_64-pc-linux-gnu", new BinaryArchive.Limits(1L << 30, 2048, 1000)));
        assertEquals("BINARY_TOO_LARGE", deep.code());
        assertEquals("foo", BinaryArchive.check(tgz, "foo", "1.0", "4.6.1", "x86_64-pc-linux-gnu",
            new BinaryArchive.Limits(tgz.length, 1L << 30, 1000)).description().get("Package"));
    }
}
