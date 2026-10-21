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
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Binds {@code skald.builds.log-stream.*} (build.logs.stream's bounds) as a free-form map, for
 * the reason BundleLimitsConfig gives: an unknown key must stop startup rather than be ignored.
 * Unconditional, so a bad value stops startup even before the database is configured.
 */
@Configuration
@ConfigurationProperties(prefix = "skald.builds")
public class LogStreamConfig {

    private Map<String, String> logStream = new LinkedHashMap<>();

    public Map<String, String> getLogStream() {
        return logStream;
    }

    public void setLogStream(Map<String, String> logStream) {
        this.logStream = logStream == null ? new LinkedHashMap<>() : logStream;
    }

    @Bean
    public StreamSettings streamSettings() {
        return StreamSettings.fromOverrides(Settings.canonical(logStream, StreamSettings.KEYS));
    }
}
