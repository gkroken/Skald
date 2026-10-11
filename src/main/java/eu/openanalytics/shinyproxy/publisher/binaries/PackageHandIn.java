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

import eu.openanalytics.shinyproxy.publisher.binaries.BinaryBuildQueue.BuildClaim;
import eu.openanalytics.shinyproxy.publisher.binaries.BinaryBuildQueue.BuiltDependency;

import java.io.IOException;
import java.util.Map;

/**
 * What the trusted side hands into a package's sandbox for a claim (WORKPLAN-BUNDLES T7b step
 * 2, point 4): the source, every closure member's Skald-built binary, and the claimed row's
 * lock. The recipe checks every byte against the queue's recorded digests, so an
 * implementation only fetches. It throws IOException for anything that may pass (a transfer,
 * a repository's error) and never IllegalArgumentException, which the driver takes as a final
 * refusal (9159de6 N2).
 */
public interface PackageHandIn {

    /** The source tarball's bytes (checked against the row's recorded digest). */
    byte[] source(BuildClaim claim) throws IOException;

    /** A dependency's Skald-uploaded binary (checked against its recorded digest). */
    byte[] dependency(BuildClaim claim, BuiltDependency dependency) throws IOException;

    /** The claimed row's lock, package -> version (BinaryBuildQueue#lockOf); empty if none. */
    Map<String, String> lock(BuildClaim claim);
}
