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

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;

/**
 * Opens a URL on the trusted side. An implementation never follows a redirect (a repository
 * that answers elsewhere is not the repository configured) and sends no credential it was not
 * given for exactly this purpose.
 */
public interface SourceFetcher {

    /** The resource does not exist there (404 or 410): a fact about the repository, not a failure. */
    final class NotFound extends IOException {
        public NotFound(URI uri) {
            super("not found: " + uri);
        }
    }

    /**
     * The body of a 200 response. The caller closes it.
     *
     * @throws NotFound    on 404 or 410
     * @throws IOException on anything else: a transport error or another status (transient)
     */
    InputStream open(URI uri) throws IOException;
}
