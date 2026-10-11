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

import eu.openanalytics.shinyproxy.publisher.recipe.BaseCatalog;

import java.util.Map;
import java.util.UUID;

/**
 * Where an app build meets the shared binaries (T7b step 2e): before an R build, whether its
 * restore may ask forge for binaries at all (the gate, read per build: c365d95 N1); after it
 * succeeds, its rendered lock queues the packages a binary does not exist for yet.
 */
public interface BinaryHook {

    /** For an R base: binaries on, or off with the reason (logged and recorded in provenance). */
    BinaryGate.Decision binariesFor(BaseCatalog.Resolved base);

    /**
     * An app build on {@code base} succeeded with {@code lock} (package -> exact version). Called
     * only for a build whose {@link #binariesFor} said on at its preparation (a16cf2a N2: the
     * upload rechecks the registration before anything leaves), after its outcome is settled,
     * off the build's thread (a16cf2a-F1). Never throws for the build's sake.
     */
    void built(UUID buildId, BaseCatalog.Resolved base, Map<String, String> lock);
}
