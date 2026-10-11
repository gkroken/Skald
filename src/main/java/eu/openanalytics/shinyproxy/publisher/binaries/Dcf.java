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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Debian control format as R writes it: a package's DESCRIPTION and a repository's PACKAGES
 * index. A record is a run of "Field: value" lines, a line starting with whitespace continues
 * the previous field, and records are separated by blank lines.
 *
 * <p>Strict where leniency would let two readers disagree: a line that is neither a field nor
 * a continuation, a continuation before any field, or a field named twice in one record is
 * refused rather than resolved, so what the verifier reads is what R would read or nothing.
 */
final class Dcf {

    // R's own read.dcf takes a field name up to the first ':'; these are the names it writes.
    private static final Pattern FIELD = Pattern.compile("([A-Za-z][A-Za-z0-9/@._-]*):[ \\t]*(.*)");

    private Dcf() {
    }

    /** Thrown for text that is not a DCF document. */
    static final class Malformed extends Exception {
        Malformed(String message) {
            super(message);
        }
    }

    /**
     * The records of {@code text}, each an insertion-ordered field map; continuation lines are
     * joined with a newline, each stripped of its leading whitespace.
     *
     * @param maxRecords refuse a document with more records than this
     */
    static List<Map<String, String>> parse(String text, int maxRecords) throws Malformed {
        List<Map<String, String>> records = new ArrayList<>();
        Map<String, String> current = null;
        String field = null;
        int lineNo = 0;
        for (String line : text.split("\r?\n", -1)) {
            lineNo++;
            if (line.isBlank()) {
                if (current != null) {
                    records.add(Collections.unmodifiableMap(current));
                    current = null;
                    field = null;
                }
                continue;
            }
            if (line.charAt(0) == ' ' || line.charAt(0) == '\t') {
                if (field == null) {
                    throw new Malformed("line " + lineNo + " continues a field, but no field came before it");
                }
                current.put(field, current.get(field) + "\n" + line.strip());
                continue;
            }
            Matcher m = FIELD.matcher(line);
            if (!m.matches()) {
                throw new Malformed("line " + lineNo + " is neither a field nor a continuation");
            }
            if (current == null) {
                if (records.size() >= maxRecords) {
                    throw new Malformed("more than " + maxRecords + " records");
                }
                current = new LinkedHashMap<>();
            }
            field = m.group(1);
            if (current.containsKey(field)) {
                throw new Malformed("line " + lineNo + " names field " + field + " a second time in one record");
            }
            current.put(field, m.group(2).strip());
        }
        if (current != null) {
            records.add(Collections.unmodifiableMap(current));
        }
        return records;
    }
}
