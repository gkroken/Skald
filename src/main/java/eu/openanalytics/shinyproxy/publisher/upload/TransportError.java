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

/**
 * The refusal codes of the T8 admin transport, each with its HTTP status and whether a client
 * may retry. The list is {@code spec/admin-transport-v1.json} {@code shapes.errors}, and
 * TransportErrorTest holds the two equal: a code added here without the spec, or there without
 * this, fails the build.
 */
public enum TransportError {
    BAD_REQUEST(400, false),
    INVALID_ID(400, false),
    UPLOAD_HEADER_MISSING(400, false),
    UPLOAD_INCOMPLETE(400, true),
    KEY_INVALID(400, false),
    CURSOR_INVALID(400, false),
    CONTENT_NOT_FOUND(404, false),
    BUNDLE_NOT_FOUND(404, false),
    BUILD_NOT_FOUND(404, false),
    BUNDLE_NOT_UPLOADING(409, false),
    BUNDLE_ARCHIVE_CONFLICT(409, false),
    BUNDLE_NOT_VALIDATED(409, false),
    KEY_REUSED_WITH_DIFFERENT_INPUTS(409, false),
    RETRY_BUNDLE_MISMATCH(400, false),
    RETRY_NOT_TERMINAL(409, false),
    CANCEL_REFUSED_PUBLISHING(409, false),
    CANCEL_TOO_LATE(409, false),
    ALREADY_FINISHED(409, false),
    LOG_EXPIRED(410, false),
    LENGTH_REQUIRED(411, false),
    BUNDLE_TOO_LARGE(413, false),
    UNSUPPORTED_MEDIA_TYPE(415, false),
    PUBLISHER_LIMIT(429, true),
    STREAM_LIMIT(429, true),
    INTERNAL(500, false),
    QUEUE_FULL(503, true),
    STORAGE_UNAVAILABLE(503, true);

    private final int status;
    private final boolean retryable;

    TransportError(int status, boolean retryable) {
        this.status = status;
        this.retryable = retryable;
    }

    public int status() {
        return status;
    }

    public boolean retryable() {
        return retryable;
    }
}
