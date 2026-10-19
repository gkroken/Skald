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

import org.springframework.boot.convert.DurationStyle;
import org.springframework.util.unit.DataSize;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * How a {@code skald.*} value is read (T7c step 0a). A duration carries its unit ("300s",
 * "20m", "3h", or ISO-8601 "PT5M"); a bare number is seconds. A size must carry its unit
 * ("64MB", "1GB", "512B"). A count is a whole number. Each error names the full key and
 * the value as given, so an operator can find it in the file.
 */
public final class Settings {

    private Settings() {
    }

    public static Duration duration(String key, Object value) {
        String text = text(key, value);
        try {
            return DurationStyle.detectAndParse(text, ChronoUnit.SECONDS);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("'" + key + "' is not a duration: '" + text
                    + "' (write it with a unit, such as 300s, 20m or 3h)");
        }
    }

    /**
     * A duration in whole seconds, as the build and binary leases, deadlines and timers are
     * kept: a database interval or a timer rounded to the second would not be the duration
     * the runner measures against (115f64b N1), so "1500ms" is refused rather than rounded.
     */
    public static Duration seconds(String key, Object value) {
        Duration d = duration(key, value);
        if (d.getNano() != 0) {
            throw new IllegalArgumentException("'" + key + "' must be whole seconds: '" + text(key, value) + "'");
        }
        return d;
    }

    public static DataSize size(String key, Object value) {
        String text = text(key, value);
        if (text.matches("[0-9]+")) {
            // A bare number reads as bytes, and "64" for a limit meant in megabytes would be a
            // silent 64-byte limit (c0c7145 N1): a size always carries its unit.
            throw new IllegalArgumentException("'" + key + "' has no unit: '" + text
                    + "' (write it with a unit, such as 64MB, 1GB or " + text + "B)");
        }
        try {
            return DataSize.parse(text);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("'" + key + "' is not a size: '" + text
                    + "' (write it with a unit, such as 64MB or 1GB)");
        }
    }

    public static int integer(String key, Object value) {
        String text = text(key, value);
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("'" + key + "' is not a whole number: '" + text + "'");
        }
    }

    public static boolean bool(String key, Object value) {
        String text = text(key, value);
        if (text.equals("true") || text.equals("false")) {
            return Boolean.parseBoolean(text);
        }
        throw new IllegalArgumentException("'" + key + "' is not true or false: '" + text + "'");
    }

    /**
     * A map section's names as its parser knows them (e195a86 N1). Spring binds a map's keys
     * as written: a file's {@code queueCapacity} or {@code queue_capacity}, and an environment
     * variable's {@code queuecapacity} (SKALD_..._QUEUECAPACITY) or {@code queue.capacity}
     * (SKALD_..._QUEUE_CAPACITY). Each is renamed to the known kebab-case key it spells,
     * ignoring case, dashes, underscores and dots, so every spelling ConfigurationCheck accepts
     * is the key the parser reads; a name that spells no known key is kept as written, for the
     * parser to refuse.
     */
    public static Map<String, String> canonical(Map<String, String> section, List<String> keys) {
        Map<String, String> out = new LinkedHashMap<>();
        section.forEach((name, value) -> {
            String uniform = uniform(name);
            out.put(keys.stream().filter(k -> uniform(k).equals(uniform)).findFirst().orElse(name), value);
        });
        return out;
    }

    private static String uniform(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT).replaceAll("[-_.]", "");
    }

    private static String text(String key, Object value) {
        if (value == null) {
            throw new IllegalArgumentException("'" + key + "' has no value");
        }
        return String.valueOf(value).trim();
    }
}
