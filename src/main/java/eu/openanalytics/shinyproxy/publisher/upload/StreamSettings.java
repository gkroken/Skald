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
package eu.openanalytics.shinyproxy.publisher.upload;

import eu.openanalytics.shinyproxy.publisher.config.Settings;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * The bounds of build.logs.stream (spec shapes.stream.limits): {@code skald.builds.log-stream.*}.
 *
 * <p>A stream is pull-based and holds no queue, so what it costs is a connection and a
 * thread waiting on storage polls. These bound that: open streams per instance, per user
 * (429 STREAM_LIMIT past either), and how long one connection lasts before the server closes
 * it without {@code end} and the client resumes by cursor.
 */
public record StreamSettings(int perInstance, int perUser, Duration connection) {

    public static final StreamSettings DEFAULTS = new StreamSettings(64, 8, Duration.ofMinutes(30));

    /** The keys of {@code skald.builds.log-stream}. */
    public static final List<String> KEYS = List.of("per-instance", "per-user", "connection");

    public StreamSettings {
        if (perInstance < 1 || perUser < 1 || connection == null || connection.isNegative() || connection.isZero()) {
            throw new IllegalArgumentException("skald.builds.log-stream settings must be positive");
        }
        if (perUser > perInstance) {
            throw new IllegalArgumentException("skald.builds.log-stream.per-user (" + perUser
                    + ") must not exceed per-instance (" + perInstance + ")");
        }
    }

    /**
     * Defaults with overrides, kebab-case or Spring-relaxed spelling; an unknown key stops
     * startup, naming the known ones.
     */
    public static StreamSettings fromOverrides(Map<String, ?> overrides) {
        int perInstance = DEFAULTS.perInstance;
        int perUser = DEFAULTS.perUser;
        Duration connection = DEFAULTS.connection;
        for (Map.Entry<String, ?> e : overrides.entrySet()) {
            String key = e.getKey() == null ? "" : e.getKey().trim().replace('_', '-')
                    .toLowerCase(Locale.ROOT);
            String name = "skald.builds.log-stream." + key;
            switch (key) {
                case "per-instance" -> perInstance = Settings.integer(name, e.getValue());
                case "per-user" -> perUser = Settings.integer(name, e.getValue());
                case "connection" -> connection = Settings.seconds(name, e.getValue());
                default -> throw new IllegalArgumentException("unknown setting 'skald.builds.log-stream."
                        + e.getKey() + "'; known settings are " + new TreeSet<>(KEYS));
            }
        }
        return new StreamSettings(perInstance, perUser, connection);
    }
}
