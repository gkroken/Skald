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

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A refusal with its code and operator-facing message, and any fields the shape adds (a
 * bundle's current state, for one). Carried as an exception so the reason stays beside the rule
 * that produced it, as ContentAdminException does for spine #1.
 */
public class TransportException extends RuntimeException {

    private final TransportError code;
    private final Map<String, Object> extra = new LinkedHashMap<>();

    public TransportException(TransportError code, String message) {
        super(message);
        this.code = code;
    }

    public TransportError code() {
        return code;
    }

    /** Adds a field to the refusal's {@code data}, after code, message and retryable. */
    public TransportException with(String field, Object value) {
        extra.put(field, value);
        return this;
    }

    public Map<String, Object> extra() {
        return extra;
    }
}
