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

import java.net.URI;

/**
 * Configured base URLs, checked without ever repeating them (be34ce0-F1). A URL is refused
 * exactly when it carries userinfo, a query or a fragment -- where operators put secrets -- so
 * a refusal names WHAT is wrong and shows at most {@code scheme://host[:port]}, never the
 * value; a value that does not parse is refused without quoting it.
 */
final class SafeUrls {

    private SafeUrls() {
    }

    /** {@code value} as a URI, or a refusal that does not repeat it. */
    static URI parse(String setting, String value) {
        try {
            return URI.create(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(setting + " is not a URL (the value is not shown: it may hold a secret)");
        }
    }

    /**
     * Requires an http(s) base URL with a host, a path ending in '/', and no userinfo, query or
     * fragment; a refusal names the setting, the part that is wrong and the safe part.
     */
    static void requireBase(String setting, URI url) {
        String scheme = url.getScheme();
        String problem = null;
        if (scheme == null || !(scheme.equals("http") || scheme.equals("https"))) {
            problem = "its scheme is not http or https";
        } else if (url.getHost() == null) {
            problem = "it has no host";
        } else if (url.getRawUserInfo() != null) {
            problem = "it carries credentials (user information)";
        } else if (url.getRawQuery() != null) {
            problem = "it has a query";
        } else if (url.getRawFragment() != null) {
            problem = "it has a fragment";
        } else if (url.getRawPath() == null || !url.getRawPath().endsWith("/")) {
            problem = "its path does not end in '/'";
        }
        if (problem != null) {
            throw new IllegalArgumentException(setting + " must be an http(s) URL ending in '/', without credentials,"
                    + " query or fragment: " + problem + " (" + safe(url) + ")");
        }
    }

    /** {@code scheme://host[:port]}, or a placeholder: never userinfo, path, query or fragment. */
    static String safe(URI url) {
        if (url.getScheme() == null || url.getHost() == null) {
            return "the value is not shown";
        }
        return url.getScheme() + "://" + url.getHost() + (url.getPort() < 0 ? "" : ":" + url.getPort());
    }
}
