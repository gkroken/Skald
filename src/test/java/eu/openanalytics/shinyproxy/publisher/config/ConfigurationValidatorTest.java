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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T7c step 0d: --validate-config lists every static problem and exits 0 or 1. */
public class ConfigurationValidatorTest {

    private static ConfigurationCheck.Result validate(Object... keyValues) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            m.put((String) keyValues[i], keyValues[i + 1]);
        }
        StandardEnvironment e = new StandardEnvironment();
        e.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        e.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        e.getPropertySources().addLast(new MapPropertySource("Config resource 'file [application.yml]'", m));
        return ConfigurationValidator.validate(e);
    }

    @Test
    public void aValidConfigurationHasNoProblem() {
        assertEquals(List.of(), validate("skald.builds.max-running", "2", "skald.builds.coordinator.lease", "10m",
                "skald.bundle.limits.max-entries", "1000").problems());
    }

    @Test
    public void whatOnlyASectionCanSeeIsListedToo() {
        // Each value is a valid duration, so the key check passes; only the coordinator's own
        // rule (renew-every at most half the lease) refuses the pair.
        List<String> p = validate("skald.builds.coordinator.lease", "30s", "skald.builds.coordinator.renew-every", "20s",
                "skald.bundle.limits.max-entries", "999999999999").problems();
        assertEquals(2, p.size(), String.valueOf(p));
        assertTrue(p.get(0).contains("must be at most half the lease"), p.get(0));
        assertTrue(p.get(1).contains("max_entries") || p.get(1).contains("max-entries"), p.get(1));
    }

    @Test
    public void theValidationSectionsOwnRulesAreListed() {
        // Valid values each; only ValidationSettings sees that three lapsed leases of 20 min
        // would outlast the 30-min deadline, so a poison bundle would be called stalled.
        List<String> p = validate("skald.bundle.validation.lease", "20m").problems();
        assertEquals(1, p.size(), String.valueOf(p));
        assertTrue(p.get(0).contains("must exceed max-claims"), p.get(0));
        assertEquals(List.of(), validate("skald.bundle.validation.lease", "5m", "skald.bundle.validation.parallel", "4")
                .problems());
    }

    @Test
    public void theRuntimeSectionIsChecked() {
        List<String> p = validate("skald.runtime.memory-limit", "5m").problems();
        assertEquals(1, p.size(), String.valueOf(p));
        assertTrue(p.get(0).contains("below Docker's minimum"), p.get(0));
        assertEquals(1, validate("skald.runtime.cpu-limit", "0").problems().size());
        assertEquals(List.of(), validate("skald.runtime.memory-limit", "4g", "skald.runtime.cpu-limit", "2").problems());
    }

    @Test
    public void theLogStreamSectionsOwnRuleIsListed() {
        // Valid values each; only StreamSettings sees that a user's share exceeds the instance's.
        List<String> p = validate("skald.builds.log-stream.per-user", "10", "skald.builds.log-stream.per-instance", "4")
                .problems();
        assertEquals(1, p.size(), String.valueOf(p));
        assertTrue(p.get(0).contains("must not exceed per-instance"), p.get(0));
        assertEquals(List.of(), validate("skald.builds.log-stream.per-user", "4", "skald.builds.log-stream.connection",
                "10m").problems());
    }

    @Test
    public void aSectionTheCheckRefusedIsListedOnce() {
        // The check and the sections word a bound differently ("0 is outside 1.." against
        // "must be positive"), so a section the check refused is not parsed again; the
        // coordinator owns the deployment's max-running too.
        List<String> p = validate("skald.builds.admission.queue-capacity", "0", "skald.builds.max-running", "0",
                "skald.builds.binaries.lease", "30s", "skald.builds.binaries.renew-every", "20s").problems();
        assertEquals(List.of("skald.builds.max-running: 0 is outside 1..",
                "skald.builds.admission.queue-capacity: 0 is outside 1.."), p.subList(0, 2));
        assertEquals(3, p.size(), String.valueOf(p));
        assertTrue(p.get(2).contains("must be at most half the lease"), p.get(2));
    }

    @Test
    public void anEnabledWorkerListsEverythingItWouldRefuse(@TempDir Path dir) {
        List<String> p = validate("skald.builds.worker.enabled", "true",
                "skald.builds.worker.workspace", dir.resolve("absent").toString(),
                "skald.builds.worker.published-bases", dir.resolve("absent.json").toString()).problems();
        String all = String.join("\n", p);
        for (String key : List.of("worker-image", "gateway-image", "registry", "registry-api", "registry-username",
                "registry-password", "cran-mirror", "pypi-mirror")) {
            assertTrue(all.contains("skald.builds.worker." + key + " is not set"), key + " in\n" + all);
        }
        assertTrue(all.contains("is not a writable directory"), all);
        assertTrue(all.contains("published-bases " + dir.resolve("absent.json")), all);
    }

    @Configuration(proxyBeanMethods = false)
    static class Empty {
    }

    /** Stops SpringApplication where the real flag exits the JVM. */
    static final class Exited extends RuntimeException {
    }

    private final PrintStream originalOut = SkaldConfigurationPostProcessor.out;

    @AfterEach
    public void restore() {
        SkaldConfigurationPostProcessor.exit = System::exit;
        SkaldConfigurationPostProcessor.out = originalOut;
    }

    private static String run(AtomicInteger code, String... args) {
        ByteArrayOutputStream printed = new ByteArrayOutputStream();
        SkaldConfigurationPostProcessor.out = new PrintStream(printed, true, StandardCharsets.UTF_8);
        SkaldConfigurationPostProcessor.exit = c -> {
            code.set(c);
            throw new Exited();
        };
        SpringApplication app = new SpringApplication(Empty.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        assertThrows(Exited.class, () -> app.run(args).close(), "the flag exits before anything starts");
        return printed.toString(StandardCharsets.UTF_8);
    }

    @Test
    public void onlyTheCommandLineCanAskToValidate() {
        // d8f6bcf-F1: read from every source, a VALIDATE_CONFIG in the environment, a system
        // property or a file entry would turn a server start into "valid, exit 0".
        SkaldConfigurationPostProcessor.exit = c -> {
            throw new Exited();
        };
        List<java.util.function.Consumer<SpringApplication>> elsewhere = List.of(
                app -> {
                    StandardEnvironment e = new StandardEnvironment();
                    e.getPropertySources().addFirst(new org.springframework.core.env.SystemEnvironmentPropertySource(
                            "test-systemEnvironment", Map.of("VALIDATE_CONFIG", "1", "VALIDATECONFIG", "x")));
                    app.setEnvironment(e);
                },
                app -> {
                    StandardEnvironment e = new StandardEnvironment();
                    e.getPropertySources().replace(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME,
                            new MapPropertySource(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME,
                                    Map.of("validate-config", "true")));
                    app.setEnvironment(e);
                },
                app -> app.setDefaultProperties(Map.of("validate-config", "true")));
        for (var source : elsewhere) {
            SpringApplication app = new SpringApplication(Empty.class);
            app.setWebApplicationType(WebApplicationType.NONE);
            source.accept(app);
            app.run().close();
        }
        SpringApplication app = new SpringApplication(Empty.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> app.run("--validate-config=false"));
        assertTrue(e.getMessage().contains("--validate-config takes no value"), e.getMessage());
        AtomicInteger code = new AtomicInteger(-1);
        assertEquals("Skald's configuration is valid.", run(code, "--validate-config=true").strip());
        assertEquals(0, code.get());
    }

    @Test
    public void aMirrorsCredentialsAreNeverPrinted(@TempDir Path dir) {
        // d8f6bcf N1: the output is meant to be pasted into a ticket.
        String all = String.join("\n", validate("skald.builds.worker.enabled", "true",
                "skald.builds.worker.cran-mirror", "https://skald:t0ken@forge.example/repository/cran/?k=q4ery",
                "skald.builds.worker.registry-api", "https://u:p4ss@registry.example").problems());
        assertTrue(all.contains("mirror https://forge.example/repository/cran/ (credentials or query not shown)"), all);
        assertFalse(all.contains("t0ken") || all.contains("q4ery") || all.contains("p4ss"), all);
    }

    @Test
    public void theFlagPrintsEveryProblemAndExitsOneOrPrintsValidAndExitsZero() {
        AtomicInteger code = new AtomicInteger(-1);
        String bad = run(code, "--validate-config", "--skald.builds.max-runing=3",
                "--skald.builds.coordinator.renew-every=200s", "--skald.builds.worker.registry-password=s3cret");
        assertEquals(1, code.get());
        assertTrue(bad.contains("Skald's configuration has 3 problems") && bad.contains("skald.builds.max-runing")
                && bad.contains("registry-password (commandLineArgs): a secret written in plain text")
                && bad.contains("must be at most half the lease"), bad);
        assertFalse(bad.contains("s3cret"), bad);

        String good = run(code, "--validate-config", "--skald.builds.max-running=3");
        assertEquals(0, code.get());
        assertEquals("Skald's configuration is valid.", good.strip());
    }
}
