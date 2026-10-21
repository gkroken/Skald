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
package eu.openanalytics.shinyproxy.publisher.registry;

import eu.openanalytics.shinyproxy.publisher.config.Settings;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Binds {@code skald.runtime.*} as a free-form map, so an unknown key stops startup rather
 * than being ignored (BundleLimitsConfig's reason). Unconditional: a bad value stops startup
 * even before a database is configured.
 */
@Configuration
@ConfigurationProperties(prefix = "skald")
public class RuntimeConfig {

    private Map<String, String> runtime = new LinkedHashMap<>();

    public Map<String, String> getRuntime() {
        return runtime;
    }

    public void setRuntime(Map<String, String> runtime) {
        this.runtime = runtime == null ? new LinkedHashMap<>() : runtime;
    }

    @Bean
    public RuntimeSettings runtimeSettings() {
        return RuntimeSettings.fromOverrides(Settings.canonical(runtime, RuntimeSettings.KEYS));
    }
}
