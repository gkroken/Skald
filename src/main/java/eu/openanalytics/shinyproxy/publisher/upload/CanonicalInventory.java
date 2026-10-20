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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.openanalytics.shinyproxy.publisher.bundle.ManifestValidator;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;

/**
 * {@code inventory.json}: what validation established about the payload, in one canonical
 * form (95e2185 N1).
 *
 * <p>Two validations of one archive must write the same bytes, because the second one's
 * create-only put finds the first one's object and compares digests: a lease that lapsed
 * during a host stall makes two validators of one bundle concurrent, not merely sequential.
 * So: members sorted by path (UTF-16 order of the NFC path, which the validator has already
 * made canonical), fields in a fixed alphabetical order, no whitespace, a final newline, and
 * nothing that varies between runs -- no time, host, validator or generation.
 *
 * <pre>{"files":[{"executable":false,"path":"app.R","sha256":"...","size":12}],"layout_version":1}</pre>
 */
final class CanonicalInventory {

    static final int LAYOUT_VERSION = 1;

    private static final ObjectMapper JSON = new ObjectMapper();

    private CanonicalInventory() {
    }

    static byte[] of(Map<String, ManifestValidator.Declared> files) {
        ObjectNode root = JSON.createObjectNode();
        ArrayNode list = root.putArray("files");
        for (ManifestValidator.Declared d : new TreeMap<>(files).values()) {
            list.addObject()
                    .put("executable", d.executable())
                    .put("path", d.path())
                    .put("sha256", d.sha256())
                    .put("size", d.size());
        }
        root.put("layout_version", LAYOUT_VERSION);
        return (root.toString() + "\n").getBytes(StandardCharsets.UTF_8);
    }
}
