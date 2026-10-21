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

import eu.openanalytics.shinyproxy.publisher.binaries.BinaryQueueSettings;
import eu.openanalytics.shinyproxy.publisher.binaries.ForgeSettings;
import eu.openanalytics.shinyproxy.publisher.binaries.SourceSettings;
import eu.openanalytics.shinyproxy.publisher.build.AdmissionLimits;
import eu.openanalytics.shinyproxy.publisher.upload.ValidationSettings;
import eu.openanalytics.shinyproxy.publisher.build.CoordinatorSettings;
import eu.openanalytics.shinyproxy.publisher.storage.ObjectStorageConfig;
import eu.openanalytics.shinyproxy.publisher.worker.WorkerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Holds the code to {@code spec/configuration-v1.json} (T7c step 0c): every key there is read
 * by the code with the same default, every key the code reads is there, and the generated
 * schema and reference match the file. {@code -Dskald.regenerate=true} rewrites them.
 */
public class ConfigurationSpecTest {

    private static final ConfigurationSpec SPEC = ConfigurationSpec.load();

    private static List<ConfigurationSpec.Key> under(String prefix) {
        return SPEC.keys().stream().filter(k -> k.key().startsWith(prefix)).toList();
    }

    private static Set<String> names(String prefix, java.util.function.Predicate<String> keep) {
        return under(prefix).stream().map(k -> k.key().substring(prefix.length())).filter(keep)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    /** The section's spec defaults, as an operator would write them (unset ones left out). */
    private static Map<String, String> defaults(String prefix, java.util.function.Predicate<String> keep) {
        Map<String, String> out = new LinkedHashMap<>();
        for (ConfigurationSpec.Key k : under(prefix)) {
            String name = k.key().substring(prefix.length());
            if (keep.test(name) && k.defaultText() != null) {
                out.put(name, k.defaultText());
            }
        }
        return out;
    }

    @Test
    public void theMapSectionsReadExactlyTheSpecsKeysWithItsDefaults() {
        assertEquals(new TreeSet<>(CoordinatorSettings.KEYS), names("skald.builds.coordinator.", n -> true));
        assertEquals(CoordinatorSettings.DEFAULTS, CoordinatorSettings.fromOverrides(
                defaults("skald.builds.coordinator.", n -> true), key("skald.builds.max-running").defaultText()));
        assertEquals(new TreeSet<>(ValidationSettings.KEYS), names("skald.bundle.validation.", n -> true));
        assertEquals(ValidationSettings.DEFAULTS, ValidationSettings.fromOverrides(
                defaults("skald.bundle.validation.", n -> true)));
        assertEquals(new TreeSet<>(eu.openanalytics.shinyproxy.publisher.upload.StreamSettings.KEYS),
                names("skald.builds.log-stream.", n -> true));
        assertEquals(eu.openanalytics.shinyproxy.publisher.upload.StreamSettings.DEFAULTS,
                eu.openanalytics.shinyproxy.publisher.upload.StreamSettings.fromOverrides(
                        defaults("skald.builds.log-stream.", n -> true)));
        assertEquals(new TreeSet<>(AdmissionLimits.KEYS), names("skald.builds.admission.", n -> true));
        assertEquals(AdmissionLimits.DEFAULTS, AdmissionLimits.fromOverrides(defaults("skald.builds.admission.", n -> true)));

        String binaries = "skald.builds.binaries.";
        java.util.function.Predicate<String> queue = n -> !n.equals("enabled") && !n.startsWith("forge-")
                && !n.startsWith("source-");
        assertEquals(new TreeSet<>(BinaryQueueSettings.KEYS), names(binaries, queue));
        assertEquals(BinaryQueueSettings.DEFAULTS, BinaryQueueSettings.fromOverrides(defaults(binaries, queue)));
        assertEquals(new TreeSet<>(ForgeSettings.KEYS), names(binaries, n -> n.startsWith("forge-")));
        assertEquals(ForgeSettings.DEFAULTS, ForgeSettings.fromOverrides(defaults(binaries, n -> n.startsWith("forge-"))));
        assertEquals(new TreeSet<>(SourceSettings.KEYS), names(binaries, n -> n.startsWith("source-")));
        assertEquals(SourceSettings.DEFAULTS, SourceSettings.fromOverrides(defaults(binaries, n -> n.startsWith("source-"))));
        assertEquals("false", key(binaries + "enabled").defaultText(), "binaries are off until the security review");
    }

    @Test
    public void theWorkerBindsExactlyTheSpecsKeysWithItsDefaultsAndRequiresWhatItMarks() throws Exception {
        String worker = "skald.builds.worker.";
        Set<String> setters = new TreeSet<>();
        for (Method m : WorkerConfig.class.getMethods()) {
            if (m.getName().startsWith("set") && m.getParameterCount() == 1 && m.getDeclaringClass() == WorkerConfig.class) {
                setters.add(kebab(m.getName().substring(3)));
            }
        }
        // enabled is read by the configuration's condition, not bound.
        assertEquals(names(worker, n -> !n.equals("enabled")), setters);

        WorkerConfig fresh = new WorkerConfig();
        for (ConfigurationSpec.Key k : under(worker)) {
            String name = k.key().substring(worker.length());
            if (name.equals("enabled")) {
                continue;
            }
            Field f = WorkerConfig.class.getDeclaredField(name.equals("quota") ? "quotaMegabytes" : camel(name));
            f.setAccessible(true);
            Object expected;
            if (k.defaultText() == null) {
                expected = null;
            } else if (k.type().equals("list")) {
                expected = List.of();
            } else {
                WorkerConfig bound = new Binder(new MapConfigurationPropertySource(Map.of(k.key(), k.defaultText())))
                        .bind("skald.builds.worker", Bindable.ofInstance(new WorkerConfig())).get();
                expected = f.get(bound);
            }
            assertEquals(expected, f.get(fresh), k.key() + ": the spec's default is the code's");
        }

        Method problems = WorkerConfig.class.getDeclaredMethod("problems", CoordinatorSettings.class);
        problems.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<String> unset = ((List<String>) problems.invoke(fresh, CoordinatorSettings.DEFAULTS)).stream()
                .filter(p -> p.endsWith(" is not set")).map(p -> p.substring(0, p.length() - " is not set".length()))
                .toList();
        assertEquals(under(worker).stream().filter(ConfigurationSpec.Key::requiredWithWorker)
                .map(ConfigurationSpec.Key::key).collect(Collectors.toCollection(TreeSet::new)), new TreeSet<>(unset));
    }

    @Test
    public void theSingleValuesAreReadUnderTheSpecsNamesWithItsDefaults() throws Exception {
        Method s3 = Stream.of(ObjectStorageConfig.class.getMethods()).filter(m -> m.getName().equals("skaldS3Client"))
                .findFirst().orElseThrow();
        Map<String, String> read = new LinkedHashMap<>();
        Pattern placeholder = Pattern.compile("\\$\\{([^:}]+)(?::([^}]*))?}");
        for (Parameter p : s3.getParameters()) {
            Matcher m = placeholder.matcher(p.getAnnotation(Value.class).value());
            assertTrue(m.matches(), p.toString());
            read.put(m.group(1), m.group(2));
        }
        Map<String, String> spec = new LinkedHashMap<>();
        under("skald.storage.").forEach(k -> spec.put(k.key(), k.defaultText()));
        assertEquals(spec, read);

        Field require = Class.forName("eu.openanalytics.shinyproxy.publisher.dispatch.DispatcherOverrideRegistrar")
                .getDeclaredField("PROP_REQUIRE_OVERRIDE");
        require.setAccessible(true);
        assertEquals(List.of("skald.dispatcher.require-override"), under("skald.dispatcher.").stream()
                .map(ConfigurationSpec.Key::key).toList());
        assertEquals("skald.dispatcher.require-override", require.get(null));
    }

    @Test
    public void everyKeyTheSourcesNameIsInTheSpec() throws Exception {
        // A key added in code without the spec fails here, not in an operator's file: any
        // "skald.<x>" string literal, ${skald.<x>} placeholder or @ConfigurationProperties
        // prefix, in any namespace (c07ba8f N1), must be a key or the prefix of keys.
        Pattern literal = Pattern.compile("(?:\"|\\$\\{)(skald\\.[a-z][a-z0-9.-]*[a-z0-9])(?:\"|[:}])");
        Set<String> missing = new TreeSet<>();
        Set<String> all = SPEC.allKeys();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                Matcher m = literal.matcher(Files.readString(f, StandardCharsets.UTF_8));
                while (m.find()) {
                    String k = m.group(1);
                    if (!all.contains(k) && all.stream().noneMatch(a -> a.startsWith(k + "."))) {
                        missing.add(k + " (" + f.getFileName() + ")");
                    }
                }
            }
        }
        assertEquals(Set.of(), missing);
        for (Map.Entry<String, String> r : SPEC.renamed().entrySet()) {
            assertTrue(!all.contains(r.getKey()) && all.contains(r.getValue()), "renamed: " + r);
        }
        for (ConfigurationSpec.Key k : SPEC.keys()) {
            assertTrue(Set.of("integer", "boolean", "duration", "size", "list", "url", "string", "image", "path")
                    .contains(k.type()), k.key() + ": " + k.type());
            assertTrue(Set.of("settings", "settings-seconds", "spring", "value").contains(k.parser()), k.key());
            assertTrue(Set.of("deployment", "instance").contains(k.scope()), k.key());
            assertTrue(!k.description().isBlank() && k.key().matches("skald(\\.[a-z0-9]+(-[a-z0-9]+)*)+"), k.key());
            assertTrue(!k.secret() || k.defaultText() == null, k.key() + ": a secret has no default");
            boolean unset = k.defaultValue() == null || k.defaultValue().isNull();
            assertTrue(!(unset || k.type().equals("list")) || k.example() != null, k.key() + ": an example to show");
            assertTrue(!k.secret() || k.example().asText().matches("\\$\\{[A-Z0-9_]+}"),
                    k.key() + ": a secret's example is a placeholder");
        }
    }

    @Test
    public void theGeneratedSchemaAndReferenceMatchTheSpec() throws Exception {
        boolean regenerate = Boolean.getBoolean("skald.regenerate");
        Path schema = Path.of(ConfigurationReference.SCHEMA_PATH);
        String expected = ConfigurationReference.schema(SPEC);
        if (regenerate) {
            Files.createDirectories(schema.getParent());
            Files.writeString(schema, expected);
        }
        assertEquals(expected, Files.exists(schema) ? Files.readString(schema) : "", ConfigurationReference.SCHEMA_PATH
                + " has drifted from spec/configuration-v1.json: rerun with -Dskald.regenerate=true");

        Path doc = Path.of(ConfigurationReference.DOC_PATH);
        String block = ConfigurationReference.reference(SPEC);
        String text = Files.exists(doc) ? Files.readString(doc) : "";
        int begin = text.indexOf(ConfigurationReference.BEGIN);
        int end = text.indexOf(ConfigurationReference.END);
        String current = begin < 0 || end < begin ? "" : text.substring(begin, end + ConfigurationReference.END.length() + 1);
        if (regenerate && !current.equals(block)) {
            Files.writeString(doc, begin < 0 ? text + block : text.substring(0, begin) + block
                    + text.substring(end + ConfigurationReference.END.length() + 1));
            current = block;
        }
        assertEquals(block, current, ConfigurationReference.DOC_PATH
                + "'s reference has drifted from spec/configuration-v1.json: rerun with -Dskald.regenerate=true");
    }

    @Test
    public void theExampleIsGeneratedValidAndMatchesTheSchemaAsDoItsExamples() throws Exception {
        // T7c step 0f: every key at its default (or commented out with an example), loading
        // clean through --validate-config and the generated JSON Schema; with every example
        // set too, so a wrong example fails here rather than in an operator's file.
        String example = ConfigurationReference.example(SPEC, false);
        Path file = Path.of(ConfigurationReference.EXAMPLE_PATH);
        if (Boolean.getBoolean("skald.regenerate")) {
            Files.createDirectories(file.getParent());
            Files.writeString(file, example);
        }
        assertEquals(example, Files.exists(file) ? Files.readString(file) : "", ConfigurationReference.EXAMPLE_PATH
                + " has drifted from spec/configuration-v1.json: rerun with -Dskald.regenerate=true");
        for (ConfigurationSpec.Key k : SPEC.keys()) {
            String name = k.key().substring(k.key().lastIndexOf('.') + 1);
            assertTrue(example.contains("\n" + "  ".repeat(k.key().split("\\.").length - 1) + name + ":")
                    || example.contains("# " + name + ":"), k.key() + " is in the example");
        }
        String id = "https://skald.invalid/schemas/config/skald-config.schema.json";
        Map<String, String> documents = Map.of(id, Files.readString(Path.of(ConfigurationReference.SCHEMA_PATH)));
        com.networknt.schema.Schema schema = com.networknt.schema.SchemaRegistry.withDefaultDialect(
                com.networknt.schema.SpecificationVersion.DRAFT_2020_12, builder -> builder.schemas(documents)
                        .schemaLoader(loader -> loader.fetchRemoteResources(false)
                                .block(iri -> !documents.containsKey(iri.toString()))))
                .getSchema(com.networknt.schema.SchemaLocation.of(id));
        for (boolean filled : new boolean[] {false, true}) {
            String yaml = ConfigurationReference.example(SPEC, filled);
            Object tree = new org.yaml.snakeyaml.Yaml().load(yaml);
            List<com.networknt.schema.Error> errors = schema.validate(new com.fasterxml.jackson.databind.ObjectMapper()
                    .valueToTree(tree));
            assertEquals(List.of(), errors, (filled ? "filled" : "shipped") + " example against the schema");

            org.springframework.core.env.StandardEnvironment env = new org.springframework.core.env.StandardEnvironment();
            env.getPropertySources().remove("systemEnvironment");
            env.getPropertySources().remove("systemProperties");
            env.getPropertySources().addFirst(new org.springframework.core.env.SystemEnvironmentPropertySource(
                    "systemEnvironment", filled ? Map.of("S3_ACCESS_KEY", "a", "S3_SECRET_KEY", "b",
                            "FORGE_TOKEN", "c", "REGISTRY_PASSWORD", "d", "SKALD_BUILDS_WORKER_ENABLED", "true")
                            : Map.of()));
            Path copy = Files.createTempFile("skald-example", ".yml");
            Files.writeString(copy, yaml);
            for (var source : new org.springframework.boot.env.YamlPropertySourceLoader().load(
                    "Config resource 'file [" + EXAMPLE + "]'", new org.springframework.core.io.FileSystemResource(copy))) {
                env.getPropertySources().addLast(source);
            }
            Files.delete(copy);
            ConfigurationCheck.Result r = ConfigurationValidator.validate(env);
            // Filled, the worker is switched on too, so it checks the examples against each
            // other (mirror hosts in repos, ports the gateway serves); only the two paths that
            // exist on a build host and not here may be refused.
            List<String> left = r.problems().stream().filter(p -> !(filled
                    && (p.equals("workspace /var/lib/skald/workspace is not a writable directory")
                    || p.startsWith("published-bases /opt/skald/published-bases.json: ")))).toList();
            assertEquals(List.of(), left, (filled ? "filled" : "shipped") + " example");
            assertEquals(filled ? 2 : 0, r.problems().size(), String.valueOf(r.problems()));
            assertEquals(List.of(), r.warnings(), (filled ? "filled" : "shipped") + " example");
        }
    }

    private static final String EXAMPLE = "application.example.yml";

    private static ConfigurationSpec.Key key(String name) {
        return SPEC.keys().stream().filter(k -> k.key().equals(name)).findFirst().orElseThrow(() -> new AssertionError(name));
    }

    private static String kebab(String camel) {
        return camel.replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase(java.util.Locale.ROOT);
    }

    private static String camel(String kebab) {
        StringBuilder b = new StringBuilder();
        boolean up = false;
        for (char c : kebab.toCharArray()) {
            if (c == '-') {
                up = true;
            } else {
                b.append(up ? Character.toUpperCase(c) : c);
                up = false;
            }
        }
        return b.toString();
    }
}
