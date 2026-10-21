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
package eu.openanalytics.shinyproxy.publisher.upload;

import com.fasterxml.jackson.databind.JsonNode;
import eu.openanalytics.shinyproxy.publisher.names.InvisibleCharacters;

import java.util.List;

/**
 * The text view of a build's log (shapes.log_records): the curl view, so an admin can read a
 * failing build before any UI does.
 *
 * <p>A build line is {@code <n right-aligned> | <text>}, with {@code " [cut]"} after a
 * shortened one. The platform's own lines are {@code ----- skald: ... -----}, which have no
 * {@code " | "}, so nothing a build prints can pass for one. The text is the build's, so
 * every code point that changes how a terminal or a reader sees the rest -- Cc but tab (ESC
 * starts a terminal sequence), Cf, Zl, Zp, the default-ignorables, and a lone surrogate --
 * is written as {@code \x{HEX}} (shapes.untrusted_text).
 */
final class LogText {

    private LogText() {
    }

    static String render(List<? extends JsonNode> records, JsonNode end) {
        int width = 1;
        for (JsonNode r : records) {
            if (r.has("n")) {
                width = Math.max(width, Long.toString(r.get("n").asLong()).length());
            }
        }
        StringBuilder out = new StringBuilder();
        for (JsonNode r : records) {
            if (r.has("gap")) {
                out.append("----- skald: ").append(r.get("gap").path("lines").asLong()).append(" lines (")
                        .append(r.get("gap").path("bytes").asLong()).append(" bytes) dropped here; head and tail kept -----\n");
            } else {
                String n = Long.toString(r.get("n").asLong());
                out.append(" ".repeat(width - n.length())).append(n).append(" | ").append(neutralise(r.get("t").asText()));
                if (r.path("cut").asBoolean(false)) {
                    out.append(" [cut]");
                }
                out.append('\n');
            }
        }
        if (end != null) {
            JsonNode e = end.get("end");
            out.append("----- skald: log final: ").append(neutralise(e.path("outcome").asText()))
                    .append(e.path("complete").asBoolean() ? ", complete" : ", incomplete")
                    .append(e.path("truncated").asBoolean() ? ", truncated" : "").append(" -----\n");
        }
        return out.toString();
    }

    /** Each code point that is not plain text, as {@code \x{HEX}}. */
    static String neutralise(String text) {
        StringBuilder out = new StringBuilder(text.length());
        text.codePoints().forEach(cp -> {
            int type = Character.getType(cp);
            // refused() is Cf, Zl, Zp and the default-ignorables (publisher.names).
            boolean hidden = (type == Character.CONTROL && cp != '\t') || type == Character.SURROGATE
                    || InvisibleCharacters.refused(cp);
            if (hidden) {
                out.append("\\x{").append(Integer.toHexString(cp).toUpperCase(java.util.Locale.ROOT)).append('}');
            } else {
                out.appendCodePoint(cp);
            }
        });
        return out.toString();
    }
}
