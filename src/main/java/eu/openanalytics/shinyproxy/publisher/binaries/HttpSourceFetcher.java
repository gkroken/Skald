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
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * {@link SourceFetcher} over HTTP: no redirects, a connect timeout and a timeout until the
 * response headers arrive (the body is bounded by its reader's own deadline). No credential:
 * the public repository is read as the public reads it. A deployment whose proxy member needs
 * a token gets it wired with the forge credential in step 2d.
 */
public final class HttpSourceFetcher implements SourceFetcher {

    private final HttpClient client;
    private final Duration timeout;

    public HttpSourceFetcher(Duration timeout) {
        this.timeout = timeout;
        this.client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(timeout).build();
    }

    @Override
    public InputStream open(URI uri) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(timeout).GET()
                .header("User-Agent", "skald-binary-builder").build();
        HttpResponse<InputStream> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new java.io.InterruptedIOException("interrupted fetching " + uri);
        }
        int status = response.statusCode();
        if (status == 200) {
            return response.body();
        }
        response.body().close();
        if (status == 404 || status == 410) {
            throw new NotFound(uri);
        }
        throw new IOException("HTTP " + status + " from " + uri);
    }
}
