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
 * is written as {@code \x{HEX}} (shapes.untrusted_text). So is a backslash, and the final
 * {@code ]} of a line whose own text ends in {@code " [cut]"}, whitespace after it or not: then neither an escape nor the
 * cut mark can be printed by a build, and the view is unambiguous.
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
                String text = unmarked(neutralise(r.get("t").asText()));
                out.append(" ".repeat(width - n.length())).append(n).append(" | ").append(text);
                if (r.path("cut").asBoolean(false)) {
                    out.append(" [cut]");
                }
                out.append('\n');
            }
        }
        if (end != null && end.get("end").path("outcome").isNull()) {
            out.append("----- skald: no log: the attempt ended ").append(neutralise(end.get("end").path("buildState").asText()))
                    .append(" without running -----\n");
        } else if (end != null) {
            JsonNode e = end.get("end");
            out.append("----- skald: log final: ").append(neutralise(e.path("outcome").asText()))
                    .append(e.path("complete").asBoolean() ? ", complete" : ", incomplete")
                    .append(e.path("truncated").asBoolean() ? ", truncated" : "").append(" -----\n");
        }
        return out.toString();
    }

    /**
     * The build's own text never ends like the cut mark: a " [cut]" followed by nothing, or by
     * only whitespace that does not show (4606275 N1), has its "]" written \x{5D} (fbe1487 N1).
     */
    static String unmarked(String text) {
        int end = text.length();
        while (end > 0) {
            int cp = text.codePointBefore(end);
            if (!Character.isWhitespace(cp) && !Character.isSpaceChar(cp)) {
                break;
            }
            end -= Character.charCount(cp);
        }
        if (end < 6 || !text.startsWith(" [cut]", end - 6)) {
            return text;
        }
        return text.substring(0, end - 1) + "\\x{5D}" + text.substring(end);
    }

    /** Each code point that is not plain text, as {@code \x{HEX}}. */
    static String neutralise(String text) {
        StringBuilder out = new StringBuilder(text.length());
        text.codePoints().forEach(cp -> {
            int type = Character.getType(cp);
            // refused() is Cf, Zl, Zp and the default-ignorables (publisher.names).
            // A backslash too, so that "\x{1B}" printed by the build is not an escape (fbe1487 N1).
            boolean hidden = (type == Character.CONTROL && cp != '\t') || type == Character.SURROGATE
                    || InvisibleCharacters.refused(cp) || cp == '\\';
            if (hidden) {
                out.append("\\x{").append(Integer.toHexString(cp).toUpperCase(java.util.Locale.ROOT)).append('}');
            } else {
                out.appendCodePoint(cp);
            }
        });
        return out.toString();
    }
}
