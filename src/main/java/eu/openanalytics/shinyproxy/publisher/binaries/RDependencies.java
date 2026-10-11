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
package eu.openanalytics.shinyproxy.publisher.binaries;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The dependency fields of a DESCRIPTION (Depends, Imports, LinkingTo) as R reads them:
 * comma-separated package names, each with an optional version constraint, and R's version
 * order (Writing R Extensions 1.1.1; {@code utils::compareVersion}).
 */
final class RDependencies {

    /**
     * R's base packages (installed.packages(priority = "base")): part of R itself, never on
     * CRAN, never queued. Recommended packages (Matrix, MASS, ...) ARE on CRAN and are queued
     * like any other: a lock may pin a version other than the one R ships.
     */
    static final Set<String> BASE = Set.of("base", "compiler", "datasets", "graphics", "grDevices",
            "grid", "methods", "parallel", "splines", "stats", "stats4", "tcltk", "tools", "utils");

    /** The fields whose packages must be present when this package compiles or loads. */
    static final List<String> FIELDS = List.of("Depends", "Imports", "LinkingTo");

    // A package name has two characters or more; "R" itself is the one-letter entry.
    private static final Pattern ENTRY = Pattern.compile(
            "(R|[A-Za-z][A-Za-z0-9.]*[A-Za-z0-9])\\s*(?:\\(\\s*(>=|>|<=|<|==|!=)\\s*([0-9]+(?:[.-][0-9]+)*)\\s*\\))?");
    // A constraint may name one number ("Rcpp (>= 1)"); a lock's versions always have two.
    private static final Pattern VERSION = Pattern.compile("[0-9]+([.-][0-9]+)*");

    /** One entry: a package, and its constraint if it has one (op and version null otherwise). */
    record Requirement(String pkg, String op, String version) {

        /** Does {@code have} satisfy this requirement's constraint? */
        boolean satisfiedBy(String have) {
            if (op == null) {
                return true;
            }
            int c = compare(have, version);
            return switch (op) {
                case ">=" -> c >= 0;
                case ">" -> c > 0;
                case "<=" -> c <= 0;
                case "<" -> c < 0;
                case "==" -> c == 0;
                case "!=" -> c != 0;
                default -> throw new IllegalStateException(op);
            };
        }

        @Override
        public String toString() {
            return op == null ? pkg : pkg + " (" + op + " " + version + ")";
        }
    }

    /** Thrown for a field R would not read as a dependency list. */
    static final class Unreadable extends Exception {
        Unreadable(String message) {
            super(message);
        }
    }

    private RDependencies() {
    }

    /** The entries of one field's value; empty entries (a trailing comma) are skipped, as R does. */
    static List<Requirement> parse(String field, String value) throws Unreadable {
        List<Requirement> out = new ArrayList<>();
        if (value == null) {
            return out;
        }
        for (String raw : value.split(",", -1)) {
            String entry = raw.strip();
            if (entry.isEmpty()) {
                continue;
            }
            Matcher m = ENTRY.matcher(entry);
            if (!m.matches()) {
                throw new Unreadable(field + " has an entry R would not read: '" + printable(entry) + "'");
            }
            out.add(new Requirement(m.group(1), m.group(2), m.group(3)));
        }
        return out;
    }

    /**
     * R's version order: the numbers between '.' and '-' compared in turn, a version that runs
     * out first being the smaller (1.0 < 1.0.1; 1.0-1 == 1.0.1).
     */
    static int compare(String a, String b) {
        if (!VERSION.matcher(a).matches() || !VERSION.matcher(b).matches()) {
            throw new IllegalArgumentException("not R versions: " + a + ", " + b);
        }
        String[] x = a.split("[.-]");
        String[] y = b.split("[.-]");
        for (int i = 0; i < Math.min(x.length, y.length); i++) {
            int c = new java.math.BigInteger(x[i]).compareTo(new java.math.BigInteger(y[i]));
            if (c != 0) {
                return c;
            }
        }
        return Integer.compare(x.length, y.length);
    }

    /** Text for a refusal: printable ASCII, bounded. */
    static String printable(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length() && b.length() < 120; i++) {
            char c = s.charAt(i);
            b.append(c >= 0x20 && c < 0x7f ? c : '?');
        }
        return b.toString();
    }
}
