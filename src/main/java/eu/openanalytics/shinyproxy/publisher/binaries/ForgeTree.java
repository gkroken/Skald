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

import java.net.URI;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Where a platform's binaries live in forge's binary repository:
 * {@code repository/<repo>/bin/linux/<codename>-<arch>/contrib/<rminor>/<pkg>_<ver>.tar.gz}
 * (forge's upload protocol, scripts/cran-linux-upload.sh). Every part is checked again here:
 * it becomes a URL path, and the token's scope is exactly this tree (c365d95 N3).
 */
final class ForgeTree {

    private static final Pattern KEY = Pattern.compile("([a-z][a-z0-9]{1,31}-(?:x86_64|aarch64))/([0-9]{1,3}\\.[0-9]{1,3})");
    private static final Pattern PACKAGE = Pattern.compile("[A-Za-z][A-Za-z0-9.]*[A-Za-z0-9]");
    private static final Pattern VERSION = Pattern.compile("[0-9]+([.-][0-9]+)+");

    private ForgeTree() {
    }

    /**
     * The binary's URL. An IllegalStateException for a malformed part: the queue's own checks
     * make that impossible, and it must never read as a refusal of the row (9159de6 N2).
     */
    static URI binary(ForgeSettings forge, String platformKey, String pkg, String version) {
        Matcher k = KEY.matcher(platformKey);
        if (!k.matches() || !PACKAGE.matcher(pkg).matches() || !VERSION.matcher(version).matches()) {
            throw new IllegalStateException("not a platform key, package and version: " + platformKey + " " + pkg + " "
                    + version);
        }
        return forge.url().resolve("repository/" + forge.binaryRepository() + "/bin/linux/" + k.group(1) + "/contrib/"
                + k.group(2) + "/" + pkg + "_" + version + ".tar.gz");
    }
}
