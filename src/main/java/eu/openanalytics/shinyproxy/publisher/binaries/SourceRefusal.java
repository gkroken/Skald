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

/**
 * A source check said no: the row is REFUSED with {@link #code} and a printable detail, and its
 * apps keep restoring the package from source. Not a transient failure: retrying would read
 * the same bytes and say the same thing.
 */
final class SourceRefusal extends Exception {

    private final String code;

    SourceRefusal(String code, String detail) {
        super(RDependencies.printable(detail));
        this.code = code;
    }

    String code() {
        return code;
    }
}
