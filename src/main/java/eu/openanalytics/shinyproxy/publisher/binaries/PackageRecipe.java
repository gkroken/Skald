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

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The recipe of one package's sandbox (WORKPLAN-BUNDLES T7b step 2, point 4): the verified
 * source tarball and each dependency's Skald-built binary, handed in by the trusted side,
 * installed with {@code R CMD INSTALL} into an empty library with NO network, and packed as
 * the one file the sandbox exports.
 *
 * <ul>
 *   <li>The install RUN step is {@code --network=none} (89a33dc N2): nothing it runs reaches
 *       any repository, so everything it installs is what this recipe handed in. The worker's
 *       daemon still pulls the base through its gateway; that is the daemon, not the RUN.</li>
 *   <li>Every handed-in byte is checked here, last, against the digest the queue recorded:
 *       the source against the row's first fetch, each dependency against the digest its
 *       own row UPLOADED (89a33dc N1). A mismatch renders nothing.</li>
 *   <li>The RUN step runs as the base's own non-root user, in a work directory that user
 *       owns; dependencies go into a library of their own, ahead of the base's on R_LIBS,
 *       and the package into an empty one, which must hold exactly that package when the
 *       install ends.</li>
 *   <li>The export is a {@code FROM scratch} stage holding only {@code /binary.tar.gz}: no
 *       other file of the sandbox leaves it. The trusted side checks it (BinaryArchive)
 *       before anything is uploaded.</li>
 *   <li>The dependencies are the claimed row's whole closure (0fd04e4-F1), each package
 *       once: two versions of one package (two rows resolved against different locks) would
 *       unpack into one directory and the last would win, so they are refused rather than
 *       ordered.</li>
 *   <li>No build argument, secret or credential appears: there is none to give.</li>
 * </ul>
 */
public final class PackageRecipe {

    /** A file handed in, with the digest it must have. */
    public record HandedIn(String pkg, String version, byte[] bytes, String sha256) {
        public HandedIn {
            bytes = bytes.clone();
        }
    }

    /** A handed-in file is not what the queue recorded: nothing is rendered. */
    public static final class DigestMismatch extends Exception {
        DigestMismatch(String message) {
            super(message);
        }
    }

    static final String WORK = "/tmp/skald-work";
    static final String OUTPUT = "binary.tar.gz";
    private static final Pattern PACKAGE = Pattern.compile("[A-Za-z][A-Za-z0-9.]*[A-Za-z0-9]");
    private static final Pattern VERSION = Pattern.compile("[0-9]+([.-][0-9]+)+");
    private static final int MAX_DEPENDENCIES = 9_999;

    private PackageRecipe() {
    }

    public static Recipe render(Base base, HandedIn source, List<HandedIn> dependencies) throws DigestMismatch {
        if (!"r".equals(base.language())) {
            throw new IllegalArgumentException("a package sandbox needs an R base");
        }
        requireForm(source);
        if (dependencies.size() > MAX_DEPENDENCIES) {
            throw new IllegalArgumentException("more than " + MAX_DEPENDENCIES + " dependencies");
        }
        requireDigest("the source of " + source.pkg(), source);
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("skald/source.tar.gz", source.bytes());
        int n = 0;
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (HandedIn d : dependencies) {
            requireForm(d);
            if (!seen.add(d.pkg())) {
                throw new IllegalArgumentException("two versions of " + d.pkg() + " in one dependency closure");
            }
            if (d.pkg().equals(source.pkg())) {
                throw new IllegalArgumentException(d.pkg() + " is handed in as its own dependency");
            }
            requireDigest("dependency " + d.pkg() + " " + d.version(), d);
            files.put(String.format("skald/dep-%04d.tar.gz", ++n), d.bytes());
        }
        files.put("skald/build.sh", script(source.pkg()).getBytes(StandardCharsets.US_ASCII));
        String user = base.uid() + ":" + base.gid();
        String dockerfile = "FROM " + base.reference() + " AS build\n"
                + "USER " + user + "\n"
                + "COPY --chown=" + user + " skald/ " + WORK + "/\n"
                + "RUN --network=none [\"sh\", \"-eu\", \"" + WORK + "/build.sh\"]\n"
                + "FROM scratch\n"
                + "COPY --from=build " + WORK + "/out/" + OUTPUT + " /" + OUTPUT + "\n";
        return new Recipe(dockerfile, files);
    }

    /** The install script. The package name is checked to R's form, so it is a plain word here. */
    static String script(String pkg) {
        return "w=" + WORK + "\n"
                + "pkg='" + pkg + "'\n"
                + "mkdir -p \"$w/deps\" \"$w/lib\" \"$w/out\"\n"
                + "for f in \"$w\"/dep-*.tar.gz; do\n"
                + "  [ -e \"$f\" ] || continue\n"
                + "  tar -xzf \"$f\" -C \"$w/deps\" --no-same-owner\n"
                + "done\n"
                + "R_LIBS=\"$w/deps\" R CMD INSTALL --library=\"$w/lib\" \"$w/source.tar.gz\"\n"
                + "cd \"$w/lib\"\n"
                + "set -- *\n"
                + "if [ \"$#\" -ne 1 ] || [ \"$1\" != \"$pkg\" ]; then\n"
                + "  echo \"skald: the library holds '$*', not $pkg\" >&2\n"
                + "  exit 1\n"
                + "fi\n"
                + "tar -czf \"$w/out/" + OUTPUT + "\" --owner=0 --group=0 --numeric-owner -C \"$w/lib\" \"$pkg\"\n";
    }

    private static void requireForm(HandedIn h) {
        if (h.pkg() == null || !PACKAGE.matcher(h.pkg()).matches() || h.pkg().length() > 100
                || h.version() == null || !VERSION.matcher(h.version()).matches()) {
            throw new IllegalArgumentException("not an R package name and version: " + h.pkg() + " " + h.version());
        }
    }

    private static void requireDigest(String what, HandedIn h) throws DigestMismatch {
        String got = BinaryArchive.sha256(h.bytes());
        if (!got.equals(h.sha256())) {
            throw new DigestMismatch(what + " has sha256 " + got + ", not the recorded " + h.sha256());
        }
    }
}
