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

import java.io.IOException;
import java.util.Optional;

/**
 * Whether a base gets forge's shared binaries (WORKPLAN-BUNDLES T7b step 2, point 1): only if
 * forge registers the base's platform key for THIS base's image ID and R platform. forge binds
 * a key to one builder image and refuses uploads that report another, and the __linux__ URL
 * names only the distro, so a second R base of the same minor release -- or a base the
 * operator re-registered away -- restores from the plain mirror (and nothing of it is
 * uploaded), with the reason. Read before each R build and each upload; any doubt (no image ID
 * on record, an unreadable registration) is "off".
 */
public final class BinaryGate {

    /** The decision and why, in words for the log. */
    public record Decision(boolean binaries, String reason) { }

    private final ForgePlatforms platforms;

    public BinaryGate(ForgePlatforms platforms) {
        this.platforms = platforms;
    }

    public Decision decide(BaseCatalog.Entry base, Optional<String> imageId) {
        Optional<String> key = base.platformKey();
        if (key.isEmpty() || base.rPlatform() == null) {
            return new Decision(false, "base " + base.id() + " has no forge platform key");
        }
        if (imageId.isEmpty()) {
            return new Decision(false, "the published bases record no image id for " + base.id());
        }
        Optional<ForgePlatforms.Registration> reg;
        try {
            reg = platforms.registration(key.get());
        } catch (IOException e) {
            return new Decision(false, "forge's registration of " + key.get() + " could not be read: "
                    + RDependencies.printable(e.getMessage()));
        }
        if (reg.isEmpty()) {
            return new Decision(false, "forge has no registration for " + key.get());
        }
        if (!reg.get().builderDigest().equals(imageId.get())) {
            return new Decision(false, "forge registers " + key.get() + " for builder " + reg.get().builderDigest()
                    + " (" + RDependencies.printable(reg.get().builderImage()) + "), not base " + base.id() + "'s "
                    + imageId.get());
        }
        if (!reg.get().rPlatform().equals(base.rPlatform())) {
            return new Decision(false, "forge registers " + key.get() + " for R platform "
                    + RDependencies.printable(reg.get().rPlatform()) + ", not " + base.rPlatform());
        }
        return new Decision(true, "forge registers " + key.get() + " for base " + base.id() + " (" + imageId.get() + ")");
    }
}
