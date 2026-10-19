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
package eu.openanalytics.shinyproxy.publisher.config;

import org.apache.commons.logging.Log;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

import java.io.PrintStream;
import java.util.function.IntConsumer;

/**
 * Runs {@link ConfigurationCheck} once the configuration files are read and before any bean
 * exists (T7c step 0b): a refused configuration stops startup listing every problem, and a
 * warning is logged once logging is up. Registered in META-INF/spring.factories.
 */
public class SkaldConfigurationPostProcessor implements EnvironmentPostProcessor, Ordered {

    private final Log log;

    public SkaldConfigurationPostProcessor(DeferredLogFactory logs) {
        this.log = logs.getLog(SkaldConfigurationPostProcessor.class);
    }

    /** The command-line flag that validates the configuration and exits (T7c step 0d). */
    public static final String VALIDATE = "validate-config";

    // Replaced by tests, which cannot let the JVM exit.
    static IntConsumer exit = System::exit;
    static PrintStream out = System.out;

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (environment.containsProperty(VALIDATE)) {
            // java -jar skald.jar --validate-config: every static check, printed, then exit 0
            // or 1 before anything starts -- no database, Docker or network is touched.
            ConfigurationCheck.Result result = ConfigurationValidator.validate(environment);
            result.warnings().forEach(w -> out.println("warning: " + w));
            out.println(result.ok() ? "Skald's configuration is valid." : result.message());
            out.flush();
            exit.accept(result.ok() ? 0 : 1);
            return;
        }
        ConfigurationCheck.Result result = new ConfigurationCheck(ConfigurationSpec.load()).check(environment);
        result.warnings().forEach(log::warn);
        if (!result.ok()) {
            throw new IllegalStateException(result.message());
        }
    }

    @Override
    public int getOrder() {
        // After ConfigDataEnvironmentPostProcessor has read application.yml and its imports.
        return Ordered.LOWEST_PRECEDENCE;
    }
}
