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

import eu.openanalytics.shinyproxy.publisher.build.BuildsConfig;
import eu.openanalytics.shinyproxy.publisher.build.CoordinatorSettings;
import eu.openanalytics.shinyproxy.publisher.bundle.BundleLimitsConfig;
import eu.openanalytics.shinyproxy.publisher.upload.LogStreamConfig;
import eu.openanalytics.shinyproxy.publisher.upload.UploadsConfig;
import eu.openanalytics.shinyproxy.publisher.worker.WorkerConfig;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.ConfigurableEnvironment;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * {@code --validate-config} (T7c step 0d): the configuration loaded exactly as startup loads
 * it, then every check that needs no database, Docker or network -- {@link ConfigurationCheck},
 * each section's own parser (the bounds and relations between keys a type cannot express,
 * such as renew-every at most half the lease), and the worker's own refusals when it is
 * enabled -- with every problem listed. A section whose keys the check already refused is not
 * parsed again, so each problem is listed once.
 */
public final class ConfigurationValidator {

    private ConfigurationValidator() {
    }

    public static ConfigurationCheck.Result validate(ConfigurableEnvironment environment) {
        ConfigurationCheck.Result checked = new ConfigurationCheck(ConfigurationSpec.load()).check(environment);
        Set<String> problems = new LinkedHashSet<>(checked.problems());
        Binder binder = Binder.get(environment);

        BuildsConfig builds = bind(binder, "skald.builds", new BuildsConfig(), problems);
        CoordinatorSettings coordinator = CoordinatorSettings.DEFAULTS;
        if (builds != null) {
            section(problems, "skald.builds.admission", builds::admissionLimits);
            // The coordinator also reads the deployment's limit, skald.builds.max-running.
            CoordinatorSettings c = section(problems, "skald.builds.coordinator", builds::coordinatorSettings,
                    "skald.builds.max-running");
            coordinator = c != null ? c : coordinator;
            section(problems, "skald.builds.binaries", builds::binaryQueueSettings);
            section(problems, "skald.builds.binaries", builds::binaryForgeSettings);
            section(problems, "skald.builds.binaries", builds::binarySourceSettings);
        }
        LogStreamConfig streams = bind(binder, "skald.builds", new LogStreamConfig(), problems);
        if (streams != null) {
            section(problems, "skald.builds.log-stream", streams::streamSettings);
        }
        BundleLimitsConfig bundle = bind(binder, "skald.bundle", new BundleLimitsConfig(), problems);
        if (bundle != null) {
            section(problems, "skald.bundle.limits", bundle::extractionLimits);
        }
        UploadsConfig uploads = bind(binder, "skald.bundle", new UploadsConfig(), problems);
        if (uploads != null) {
            section(problems, "skald.bundle.validation", uploads::validationSettings);
        }
        if ("true".equals(environment.getProperty("skald.builds.worker.enabled"))) {
            WorkerConfig worker = bind(binder, "skald.builds.worker", new WorkerConfig(), problems);
            if (worker != null) {
                problems.addAll(worker.staticProblems(coordinator));
            }
        }
        return new ConfigurationCheck.Result(List.copyOf(problems), checked.warnings());
    }

    private static <T> T bind(Binder binder, String prefix, T target, Set<String> problems) {
        try {
            return binder.bind(prefix, Bindable.ofInstance(target)).orElse(target);
        } catch (RuntimeException e) {
            if (problems.stream().noneMatch(p -> p.startsWith(prefix + ".") || p.startsWith("'" + prefix + "."))) {
                problems.add(rootMessage(e));
            }
            return null;
        }
    }

    /** A section's parser, unless the check already refused one of its keys (or {@code alsoReads}). */
    private static <T> T section(Set<String> problems, String prefix, Supplier<T> parser, String... alsoReads) {
        List<String> owns = new java.util.ArrayList<>(List.of(prefix + "."));
        owns.addAll(List.of(alsoReads));
        // A problem is about the key it starts with ("key: ...", "'key' ...", "key (source)");
        // a hint naming a key further on ("did you mean ...") is not.
        if (problems.stream().anyMatch(p -> owns.stream().anyMatch(o -> p.startsWith(o) || p.startsWith("'" + o)))) {
            return null;
        }
        try {
            return parser.get();
        } catch (RuntimeException e) {
            problems.add(rootMessage(e));
            return null;
        }
    }

    private static String rootMessage(Throwable t) {
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t.getMessage();
    }
}
