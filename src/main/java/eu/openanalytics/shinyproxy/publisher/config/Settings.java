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
import org.springframework.util.unit.DataUnit;

import java.time.Duration;
import java.time.temporal.ChronoUnit;

/**
 * How a {@code skald.*} value is read (T7c step 0a). A duration carries its unit ("300s",
 * "20m", "3h", or ISO-8601 "PT5M"); a bare number is seconds. A size carries its unit ("64MB",
 * "1GB"); a bare number is bytes. A count is a whole number. Each error names the full key and
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

    public static DataSize size(String key, Object value) {
        String text = text(key, value);
        try {
            return DataSize.parse(text, DataUnit.BYTES);
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

    private static String text(String key, Object value) {
        if (value == null) {
            throw new IllegalArgumentException("'" + key + "' has no value");
        }
        return String.valueOf(value).trim();
    }
}
