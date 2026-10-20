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

import eu.openanalytics.shinyproxy.publisher.bundle.ExtractionLimits;
import eu.openanalytics.shinyproxy.publisher.config.Settings;
import eu.openanalytics.shinyproxy.publisher.storage.ObjectStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The upload side of publishing (T8): bundle validation, on every instance with a database and
 * object storage. {@code skald.bundle.validation.*} tunes it.
 *
 * <p>The bundle bucket is the one the build worker reads, {@code skald.builds.worker.bundle-bucket}:
 * one key, so the instance that validates and the one that builds cannot be pointed at
 * different buckets.
 */
@Configuration
@ConfigurationProperties(prefix = "skald.bundle")
public class UploadsConfig {

    private Map<String, String> validation = new LinkedHashMap<>();

    public Map<String, String> getValidation() {
        return validation;
    }

    public void setValidation(Map<String, String> validation) {
        this.validation = validation == null ? new LinkedHashMap<>() : validation;
    }

    @Bean
    public ValidationSettings validationSettings() {
        return ValidationSettings.fromOverrides(Settings.canonical(validation, ValidationSettings.KEYS));
    }

    @Configuration
    @ConditionalOnProperty(name = {"spring.datasource.url", "skald.storage.endpoint"})
    public static class WithDatabaseAndStorage {

        @Bean
        public ValidationQueue validationQueue(JdbcTemplate jdbc, ValidationSettings settings) {
            return new ValidationQueue(jdbc, settings);
        }

        @Bean
        public BundleValidation bundleValidation(ValidationQueue queue, ObjectStore store, ExtractionLimits limits,
                                                 ValidationSettings settings,
                                                 @Value("${skald.builds.worker.bundle-bucket:skald-bundles}") String bucket) {
            return new BundleValidation(queue, store, bucket, limits, settings.workspaceOrDefault());
        }

        @Bean(initMethod = "start", destroyMethod = "stop")
        public BundleValidator bundleValidator(ValidationQueue queue, BundleValidation validation,
                                               ValidationSettings settings) {
            return new BundleValidator(queue, validation, settings);
        }
    }
}
