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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.env.ConfigTreePropertySource;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T7c step 0b: the skald: section is checked as a whole before anything starts. */
public class ConfigurationCheckTest {

    private static final ConfigurationCheck CHECK = new ConfigurationCheck(ConfigurationSpec.load());

    /** An environment of a configuration file, the environment and system properties. */
    private static StandardEnvironment environment(Map<String, Object> file, Map<String, Object> env,
                                                   Map<String, Object> system) {
        StandardEnvironment e = new StandardEnvironment();
        MutablePropertySources s = e.getPropertySources();
        s.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        s.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        s.addLast(new MapPropertySource(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME, system));
        s.addLast(new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, env));
        s.addLast(new MapPropertySource("Config resource 'file [/opt/skald/application.yml]' via location 'x'", file));
        return e;
    }

    private static ConfigurationCheck.Result file(Object... keyValues) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            m.put((String) keyValues[i], keyValues[i + 1]);
        }
        return CHECK.check(environment(m, Map.of(), Map.of()));
    }

    @Test
    public void aValidFileHasNoProblemInAnyOfItsSpellings() {
        ConfigurationCheck.Result r = CHECK.check(environment(Map.of("skald.builds.max-running", "2",
                "skald.builds.coordinator.lease", "10m", "skald.builds.worker.repos[0]", "forge",
                "skald.builds.worker.poolSize", "2", "skald.builds.admission.queue_capacity", "12",
                "skald.bundle.limits.max-file-bytes", "1048576", "skald.storage.secret-key", "${S3_SECRET}",
                "proxy.title", "anything"), Map.of("S3_SECRET", "from-the-environment"), Map.of()));
        assertEquals(List.of(), r.problems());
        assertEquals(List.of(), r.warnings());
    }

    @Test
    public void anUnknownKeyIsRefusedWithWhereItWasSetAndTheLikelyKey() {
        ConfigurationCheck.Result r = file("skald.builds.worker.pool-sise", "2");
        assertEquals(List.of("skald.builds.worker.pool-sise (/opt/skald/application.yml): unknown key; did you mean"
                + " skald.builds.worker.pool-size?"), r.problems());
    }

    @Test
    public void aRenamedKeyNamesItsNewName() {
        ConfigurationCheck.Result r = file("skald.builds.worker.quota-megabytes", "4096",
                "skald.builds.binaries-enabled", "true");
        assertEquals(List.of("skald.builds.worker.quota-megabytes (/opt/skald/application.yml): unknown key; it was"
                + " renamed to skald.builds.worker.quota", "skald.builds.binaries-enabled (/opt/skald/application.yml):"
                + " unknown key; it was renamed to skald.builds.binaries.enabled"), r.problems());
    }

    @Test
    public void everyProblemIsReportedAtOnce() {
        ConfigurationCheck.Result r = file("skald.builds.coordinator.lease", "1500ms", "skald.builds.worker.quota", "64",
                "skald.builds.max-running", "0", "skald.builds.worker.pool-size", "9",
                "skald.builds.binaries.enabled", "yes", "skald.bulids.max-running", "1");
        assertEquals(6, r.problems().size(), String.join("\n", r.problems()));
        String all = r.message();
        for (String expected : List.of("'skald.builds.coordinator.lease' must be whole seconds",
                "'skald.builds.worker.quota' has no unit", "skald.builds.max-running: 0 is outside 1..",
                "skald.builds.worker.pool-size: 9 is outside 0..8", "'skald.builds.binaries.enabled' is not true or false",
                "skald.bulids.max-running (/opt/skald/application.yml): unknown key; did you mean skald.builds.max-running?")) {
            assertTrue(all.contains(expected), expected + " in\n" + all);
        }
        assertTrue(all.startsWith("Skald's configuration has 6 problems; nothing was started:"), all);
    }

    @Test
    public void theEnvironmentIsStrictInSkaldsSectionsAndOnlyWarnedAboutElsewhere() {
        // 8144a20 N1: the environment's names are shared with other tools.
        ConfigurationCheck.Result r = CHECK.check(environment(Map.of(), Map.of(
                "SKALD_BUILDS_WORKER_POOLSIZE", "2", "SKALD_BUILDS_MAX_RUNNING", "3",
                "SKALD_BUILDS_WORKER_REPOS_0_", "forge", "SKALD_BUILDS_WORKER_POOLSISE", "2",
                "SKALD_DOCKER_CONFIG", "{}", "SKALD_T7B_PASSWORD", "x", "PATH", "/bin"), Map.of(
                "skald.t7b.out", "target/t7b", "skald.builds.max-runing", "3")));
        assertEquals(List.of("skald.builds.max-runing (systemProperties): unknown key; did you mean"
                + " skald.builds.max-running?", "skald.builds.worker.poolsise (the environment variable"
                + " SKALD_BUILDS_WORKER_POOLSISE): unknown key; did you mean skald.builds.worker.pool-size?"), r.problems());
        assertEquals(3, r.warnings().size(), String.valueOf(r.warnings()));
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("SKALD_DOCKER_CONFIG")), String.valueOf(r.warnings()));
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("skald.t7b.out")), String.valueOf(r.warnings()));
        assertEquals(List.of(), CHECK.check(environment(Map.of(), Map.of("SKALD_BUILDS_MAX_RUNNING", "0"), Map.of()))
                .problems().stream().filter(p -> p.contains("unknown")).toList(), "a known variable is known");
        assertEquals(List.of("skald.builds.max-running: 0 is outside 1.."), CHECK.check(environment(Map.of(),
                Map.of("SKALD_BUILDS_MAX_RUNNING", "0"), Map.of())).problems(), "and its value is checked");
    }

    @Test
    public void aRenamedKeyGivenAsAnEnvironmentVariableNamesItsNewName() {
        // e195a86 N2.
        assertEquals(List.of("skald.builds.worker.quota-megabytes (the environment variable"
                + " SKALD_BUILDS_WORKER_QUOTA_MEGABYTES): unknown key; it was renamed to skald.builds.worker.quota"),
                CHECK.check(environment(Map.of(), Map.of("SKALD_BUILDS_WORKER_QUOTA_MEGABYTES", "4096"), Map.of()))
                        .problems());
    }

    @Test
    public void everySpellingTheCheckAcceptsIsTheKeyTheSectionsRead() {
        // e195a86 N1: the map sections bind names as written -- an environment variable's
        // queuecapacity or queue.capacity, a file's queueCapacity -- and must read each as the
        // key the check accepted, not refuse it afterwards under a name nobody wrote.
        Map<String, Object> env = Map.of("SKALD_BUILDS_ADMISSION_QUEUECAPACITY", "5",
                "SKALD_BUILDS_ADMISSION_PER_PUBLISHER_ACTIVE", "4", "SKALD_BUILDS_COORDINATOR_RENEW_EVERY", "20s",
                "SKALD_BUILDS_BINARIES_FORGE_TIMEOUT", "45s", "SKALD_BUILDS_BINARIES_SOURCEMAXSIZE", "1MB");
        Map<String, Object> file = Map.of("skald.builds.coordinator.deadLine", "2h",
                "skald.builds.binaries.maxAttempts", "3", "skald.builds.binaries.source_archived", "true");
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withConfiguration(org.springframework.boot.autoconfigure.AutoConfigurations.of(
                        org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration.class))
                .withInitializer(ctx -> {
                    MutablePropertySources s = ctx.getEnvironment().getPropertySources();
                    s.addFirst(new SystemEnvironmentPropertySource("test-systemEnvironment", env));
                    s.addFirst(new MapPropertySource("Config resource 'file [application.yml]'", file));
                })
                .withUserConfiguration(eu.openanalytics.shinyproxy.publisher.build.BuildsConfig.class)
                .run(ctx -> {
                    assertEquals(null, ctx.getStartupFailure());
                    assertEquals(List.of(), CHECK.check(ctx.getEnvironment()).problems());
                    var admission = ctx.getBean(eu.openanalytics.shinyproxy.publisher.build.AdmissionLimits.class);
                    assertEquals(5, admission.queueCapacity());
                    assertEquals(4, admission.perPublisherActive());
                    var coordinator = ctx.getBean(eu.openanalytics.shinyproxy.publisher.build.CoordinatorSettings.class);
                    assertEquals(java.time.Duration.ofSeconds(20), coordinator.renewEvery());
                    assertEquals(java.time.Duration.ofHours(2), coordinator.deadline());
                    assertEquals(java.time.Duration.ofSeconds(45), ctx.getBean(
                            eu.openanalytics.shinyproxy.publisher.binaries.ForgeSettings.class).timeout());
                    assertEquals(3, ctx.getBean(eu.openanalytics.shinyproxy.publisher.binaries.BinaryQueueSettings.class)
                            .maxAttempts());
                    var source = ctx.getBean(eu.openanalytics.shinyproxy.publisher.binaries.SourceSettings.class);
                    assertEquals(1L << 20, source.maxSourceBytes());
                    assertTrue(source.archived());
                });
    }

    @Test
    public void aSecretIsNeverPrintedNorItsUnresolvedPlaceholderValue() {
        ConfigurationCheck.Result r = file("skald.builds.binaries.forge-token", "s3cret-value",
                "skald.builds.worker.registry-password", "${NO_SUCH_VARIABLE_HERE}",
                "skald.builds.binaries.forge-timeout", "s3cret-value");
        String all = r.message();
        assertTrue(all.contains("skald.builds.worker.registry-password: its placeholder does not resolve"), all);
        assertTrue(all.contains("skald.builds.binaries.forge-token (/opt/skald/application.yml): a secret written in"
                + " plain text"), all);
        assertEquals(3, r.problems().size(), all);
        assertTrue(all.contains("'skald.builds.binaries.forge-timeout' is not a duration"), all);
        assertFalse(all.replace("'skald.builds.binaries.forge-timeout' is not a duration: 's3cret-value'", "")
                .contains("s3cret"), "only the non-secret key's own value is shown: " + all);
    }

    @Test
    public void aSecretComesFromTheEnvironmentAConfigTreeOrAPlaceholderWithoutDefault(@TempDir Path dir)
            throws Exception {
        // T7c step 0e, checked as written, before ${} resolution (8144a20 N2).
        Map<String, Object> env = Map.of("FORGE_TOKEN", "t0ken", "SKALD_STORAGE_SECRETKEY", "k3y");
        assertEquals(List.of(), CHECK.check(environment(Map.of("skald.builds.binaries.forge-token", "${FORGE_TOKEN}",
                "skald.storage.access-key", "", "skald.builds.worker.registry-password", "${UNSET_HERE:}"), env,
                Map.of())).problems(), "a placeholder, the environment, an empty value or an empty default");

        Files.writeString(dir.resolve("skald.builds.worker.registry-password"), "pw-from-a-mounted-file");
        StandardEnvironment tree = environment(Map.of(), Map.of(), Map.of());
        tree.getPropertySources().addFirst(new ConfigTreePropertySource("Config tree '" + dir + "'", dir));
        assertEquals(List.of(), CHECK.check(tree).problems(), "configtree: mounted files");

        ConfigurationCheck.Result r = CHECK.check(environment(Map.of("skald.builds.binaries.forge-token",
                "${FORGE_TOKEN:t0k3n-in-the-file}"), env, Map.of("skald.storage.secret-key", "sys-s3cret")));
        assertEquals(List.of("skald.storage.secret-key (systemProperties): a secret written in plain text; give it as"
                + " ${VARIABLE} from the environment, with no default, or through configtree:",
                "skald.builds.binaries.forge-token (/opt/skald/application.yml): a secret written in plain text; give"
                + " it as ${VARIABLE} from the environment, with no default, or through configtree:"), r.problems());
        assertFalse(r.message().contains("t0k3n") || r.message().contains("sys-s3cret"), r.message());

        // 462322d N1: the placeholder is followed to whatever supplies it.
        String plain = "a secret written in plain text";
        assertTrue(CHECK.check(environment(Map.of("skald.builds.binaries.forge-token", "${my.token}",
                "my.token", "in-the-file"), env, Map.of())).message().contains("forge-token (/opt/skald/application.yml): "
                + plain), "a placeholder for another property of the file");
        assertEquals(List.of(), CHECK.check(environment(Map.of("skald.builds.binaries.forge-token", "${my.token}",
                "my.token", "${FORGE_TOKEN}"), env, Map.of())).problems(), "a chain that ends in the environment");
        assertTrue(CHECK.check(environment(Map.of("skald.builds.binaries.forge-token", "${my.token}"), env,
                Map.of("my.token", "on-a-command-line"))).message().contains(plain), "one that ends in a system property");
    }

    @Configuration(proxyBeanMethods = false)
    static class Empty {
    }

    @Test
    public void theApplicationRefusesToStartListingEveryProblem() {
        // Through SpringApplication, so the post-processor's registration in spring.factories
        // is what runs the check.
        SpringApplication app = new SpringApplication(Empty.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> app.run("--skald.builds.max-runing=3", "--skald.builds.worker.quota=64").close());
        assertTrue(e.getMessage().contains("Skald's configuration has 2 problems")
                && e.getMessage().contains("skald.builds.max-runing (commandLineArgs): unknown key"), e.getMessage());
        app.run("--skald.builds.max-running=3", "--spring.main.banner-mode=off").close();
    }
}
