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
package eu.openanalytics.shinyproxy.publisher.build;

import eu.openanalytics.shinyproxy.publisher.binaries.BinaryQueueSettings;
import eu.openanalytics.shinyproxy.publisher.binaries.SourceSettings;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Binds {@code skald.builds.admission.*}, {@code skald.builds.coordinator.*} and
 * {@code skald.builds.binaries.*} (T7b's binary queue), each as a
 * free-form map for the reason {@code BundleLimitsConfig} gives (an unknown key must stop
 * startup rather than be ignored), and wires the build package when a database is configured.
 *
 * <p>The limits and settings are unconditional beans: validating them costs nothing, and a
 * bad value should stop startup even on a deployment that has not enabled the database yet.
 * Admission, the coordinator and the reaper exist only with {@code spring.datasource.url},
 * like the rest of the publishing layer ({@code DataSourceConfig}).
 */
@Configuration
@ConfigurationProperties(prefix = "skald.builds")
public class BuildsConfig {

    private Map<String, String> admission = new LinkedHashMap<>();
    private Map<String, String> coordinator = new LinkedHashMap<>();
    private Map<String, String> binaries = new LinkedHashMap<>();

    public Map<String, String> getAdmission() {
        return admission;
    }

    public void setAdmission(Map<String, String> admission) {
        this.admission = admission == null ? new LinkedHashMap<>() : admission;
    }

    public Map<String, String> getCoordinator() {
        return coordinator;
    }

    public void setCoordinator(Map<String, String> coordinator) {
        this.coordinator = coordinator == null ? new LinkedHashMap<>() : coordinator;
    }

    public Map<String, String> getBinaries() {
        return binaries;
    }

    public void setBinaries(Map<String, String> binaries) {
        this.binaries = binaries == null ? new LinkedHashMap<>() : binaries;
    }

    @Bean
    public AdmissionLimits admissionLimits() {
        return AdmissionLimits.fromOverrides(admission);
    }

    @Bean
    public CoordinatorSettings coordinatorSettings() {
        return CoordinatorSettings.fromOverrides(coordinator);
    }

    /** Validated at startup; the queue itself is wired with the builder (T7b step 2e). */
    @Bean
    public BinaryQueueSettings binaryQueueSettings() {
        return BinaryQueueSettings.fromOverrides(partition(false));
    }

    /** The {@code skald.builds.binaries.source-*} keys; validated at startup, wired in 2e. */
    @Bean
    public SourceSettings binarySourceSettings() {
        return SourceSettings.fromOverrides(partition(true));
    }

    private Map<String, String> partition(boolean source) {
        Map<String, String> out = new LinkedHashMap<>();
        binaries.forEach((k, v) -> {
            if (SourceSettings.owns(k) == source) {
                out.put(k, v);
            }
        });
        return out;
    }

    /** The database-backed half, present only when a database is configured. */
    @Configuration
    @ConditionalOnProperty(name = "spring.datasource.url")
    public static class WithDatabase {

        @Bean
        public BuildAdmission buildAdmission(JdbcTemplate jdbc, AdmissionLimits limits) {
            return new BuildAdmission(jdbc, limits);
        }

        @Bean
        public BuildCoordinator buildCoordinator(JdbcTemplate jdbc, CoordinatorSettings settings) {
            return new BuildCoordinator(jdbc, settings);
        }

        /**
         * Reaps on a timer, so a lost worker's attempt and a passed deadline are settled
         * without anyone calling reap() (071ef50 review N1). Every instance runs one; reaping
         * is a fenced, idempotent UPDATE, so two instances reaping at once is harmless.
         */
        @Bean(initMethod = "start", destroyMethod = "stop")
        public BuildReaper buildReaper(BuildCoordinator coordinator, CoordinatorSettings settings) {
            return new BuildReaper(coordinator, settings.renewEvery());
        }
    }
}
