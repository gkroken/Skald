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

import org.springframework.boot.env.ConfigTreePropertySource;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The {@code skald:} section checked as a whole before anything starts (T7c step 0b): every
 * name an operator set is a key of {@link ConfigurationSpec}, and every value it gives one of
 * those keys has the spec's type and bounds. Every problem is collected, with its key and
 * where it was set, so one failed start lists them all.
 *
 * <p>Which names are refused (8144a20 N1). The sections the spec owns ({@code skald.storage},
 * {@code skald.dispatcher}, {@code skald.builds}, {@code skald.bundle}: the second element of
 * its keys) are Skald's alone: an unknown name there is refused from any source, the
 * environment and system properties included. Any other {@code skald.*} name is refused in
 * configuration files and on the command line, where an operator writes Skald's settings, but
 * only warned about in the environment and in system properties, whose names tools share
 * ({@code SKALD_DOCKER_CONFIG} inside the worker, the probes' {@code skald.t7b.*}).
 *
 * <p>A secret's value is never type-checked or printed; a placeholder that resolves to nothing
 * is reported by its key alone. A secret may come from the environment or a {@code configtree:}
 * import of mounted files; anywhere else -- a file, the command line, a system property, both
 * visible in a process listing -- it must be a ${VARIABLE} placeholder with no default (T7c
 * step 0e).
 */
public final class ConfigurationCheck {

    /** What was found: problems stop startup, warnings are logged. */
    public record Result(List<String> problems, List<String> warnings) {

        public boolean ok() {
            return problems.isEmpty();
        }

        /** The problems as one message, for a refused start. */
        public String message() {
            StringBuilder b = new StringBuilder("Skald's configuration has ").append(problems.size())
                    .append(problems.size() == 1 ? " problem" : " problems").append("; nothing was started:");
            problems.forEach(p -> b.append("\n  ").append(p));
            return b.append("\n(every key is described in docs/CONFIGURATION.md)").toString();
        }
    }

    private static final String PREFIX = "skald.";
    private static final String ENV_PREFIX = "SKALD_";

    private final ConfigurationSpec spec;
    private final Set<String> known;
    private final Set<String> owned = new TreeSet<>();
    private final Set<String> uniformKnown = new java.util.HashSet<>();
    private final Set<String> uniformSecrets = new java.util.HashSet<>();

    public ConfigurationCheck(ConfigurationSpec spec) {
        this.spec = spec;
        this.known = spec.allKeys();
        for (String k : known) {
            owned.add(section(k));
            uniformKnown.add(uniform(k));
        }
        spec.keys().stream().filter(ConfigurationSpec.Key::secret).forEach(k -> uniformSecrets.add(uniform(k.key())));
    }

    public Result check(ConfigurableEnvironment environment) {
        Set<String> problems = new LinkedHashSet<>();
        Set<String> warnings = new LinkedHashSet<>();
        for (PropertySource<?> source : environment.getPropertySources()) {
            if (!(source instanceof EnumerablePropertySource<?> names) || source.getName().equals("configurationProperties")) {
                continue;
            }
            boolean env = source instanceof SystemEnvironmentPropertySource;
            boolean shared = env || source.getName().equals(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
            boolean secretsAllowed = env || source instanceof ConfigTreePropertySource;
            for (String name : names.getPropertyNames()) {
                String key = env ? fromEnvironment(name) : normal(name);
                if (key != null && !secretsAllowed && uniformSecrets.contains(uniform(key))
                        && !isPlaceholder(source.getProperty(name))) {
                    // As written, before ${} resolution (8144a20 N2); the value is never shown.
                    problems.add(key + " (" + where(source) + "): a secret written in plain text; give it as"
                            + " ${VARIABLE} from the environment, with no default, or through configtree:");
                }
                if (key == null || uniformKnown.contains(uniform(key))) {
                    continue;
                }
                String where = env ? "the environment variable " + name : where(source);
                String finding = key + " (" + where + "): unknown key" + hint(key);
                if (owned.contains(section(key)) || !shared) {
                    problems.add(finding);
                } else {
                    warnings.add(finding + "; not a Skald setting, ignored");
                }
            }
        }
        for (ConfigurationSpec.Key k : spec.keys()) {
            check(environment, k, problems);
        }
        return new Result(List.copyOf(problems), List.copyOf(warnings));
    }

    private void check(ConfigurableEnvironment environment, ConfigurationSpec.Key k, Set<String> problems) {
        String value;
        try {
            if (!environment.containsProperty(k.key())) {
                return;
            }
            value = environment.getProperty(k.key());
        } catch (IllegalArgumentException unresolved) {
            // Spring names the placeholder, never a value; a secret's message is ours alone.
            problems.add(k.key() + ": " + (k.secret() ? "its placeholder does not resolve (is the variable set?)"
                    : unresolved.getMessage()));
            return;
        }
        if (value == null || k.secret()) {
            return;
        }
        try {
            switch (k.type()) {
                case "integer" -> {
                    long n = Settings.integer(k.key(), value);
                    if (k.min() != null && n < k.min() || k.max() != null && n > k.max()) {
                        problems.add(k.key() + ": " + n + " is outside " + (k.min() == null ? "" : k.min()) + ".."
                                + (k.max() == null ? "" : k.max()));
                    }
                }
                case "boolean" -> Settings.bool(k.key(), value);
                case "duration" -> {
                    if (k.parser().equals("settings-seconds")) {
                        Settings.seconds(k.key(), value);
                    } else {
                        Settings.duration(k.key(), value);
                    }
                }
                case "size" -> Settings.size(k.key(), value);
                default -> {
                    // strings, URLs, paths and lists: their own section checks what they hold
                }
            }
        } catch (IllegalArgumentException bad) {
            problems.add(bad.getMessage());
        }
    }

    /**
     * A name compared as Spring's relaxed binding and the map sections' parsers compare it
     * (e195a86 N1): case, dashes and underscores ignored within each element, so every name
     * that one of them reads as a key is that key here too.
     */
    private static String uniform(String name) {
        return name.toLowerCase(Locale.ROOT).replaceAll("[-_]", "");
    }

    /**
     * A secret's raw value that keeps the secret out of the source (T7c step 0e): exactly one
     * placeholder, with no default -- "${TOKEN:abc123}" writes the secret into the file after
     * all -- or an empty default, which leaves the secret unset; an empty value is unset too.
     */
    private static boolean isPlaceholder(Object raw) {
        // Empty is unset, not a secret.
        return raw == null || raw.toString().isBlank() || raw.toString().trim().matches("\\$\\{[^:}\\s]+:?}");
    }

    /** The element after "skald.": the section a key belongs to. */
    private static String section(String key) {
        String[] parts = key.split("\\.");
        return parts.length > 1 ? parts[1] : "";
    }

    /** A property name as the spec writes it: kebab-case, list indices dropped. */
    static String normal(String name) {
        if (name == null || !name.regionMatches(true, 0, PREFIX, 0, PREFIX.length())) {
            return null;
        }
        String n = name.replaceAll("\\[[^]]*]", "").replaceAll("([a-z0-9])([A-Z])", "$1-$2");
        return n.toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /**
     * An environment variable's key: a known key whose variable it is, in Spring's form
     * (SKALD_BUILDS_MAXRUNNING) or the legacy one (SKALD_BUILDS_MAX_RUNNING), list indices
     * (_0_) dropped; else its best guess, dots for underscores. Null when not a Skald name.
     */
    String fromEnvironment(String variable) {
        if (!variable.startsWith(ENV_PREFIX)) {
            return null;
        }
        String v = variable.replaceAll("_[0-9]+_?$", "");
        // A renamed key's old name is matched too, so its refusal names the new one (e195a86 N2).
        Set<String> names = new LinkedHashSet<>(known);
        names.addAll(spec.renamed().keySet());
        for (String k : names) {
            String upper = k.toUpperCase(Locale.ROOT).replace('.', '_');
            if (v.equals(upper.replace("-", "")) || v.equals(upper.replace('-', '_'))) {
                return k;
            }
        }
        return v.toLowerCase(Locale.ROOT).replace('_', '.');
    }

    private String hint(String key) {
        String to = spec.renamed().get(key);
        if (to != null) {
            return "; it was renamed to " + to;
        }
        String best = null;
        int bestDistance = 4;
        for (String k : known) {
            int d = distance(key, k);
            if (d < bestDistance) {
                best = k;
                bestDistance = d;
            }
        }
        return best == null ? "" : "; did you mean " + best + "?";
    }

    /** Where a file-like source is: its resource, not Spring's whole description. */
    private static String where(PropertySource<?> source) {
        String name = source.getName();
        int open = name.indexOf('[');
        int close = name.indexOf(']', open + 1);
        return open >= 0 && close > open ? name.substring(open + 1, close) : name;
    }

    private static int distance(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1),
                        prev[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }
}
