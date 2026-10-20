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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.UUID;

/**
 * What every T8 transport controller shares: its refusals in the transport's envelope. A
 * subclass's handlers throw {@link TransportException}; this turns it, a malformed id in the
 * path and anything unexpected into the shapes' error object. The unexpected case carries a
 * correlation id and nothing else, because its message may name internals.
 */
abstract class TransportController {

    private static final Logger log = LoggerFactory.getLogger(TransportController.class);

    @ExceptionHandler(TransportException.class)
    ResponseEntity<byte[]> refused(TransportException e) {
        return TransportJson.refusal(e);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<byte[]> malformedId(MethodArgumentTypeMismatchException e) {
        return TransportJson.refusal(new TransportException(TransportError.INVALID_ID,
                "'" + eu.openanalytics.shinyproxy.publisher.bundle.BundleRejection.printable(String.valueOf(e.getValue()))
                        + "' is not an id"));
    }

    @ExceptionHandler(RuntimeException.class)
    ResponseEntity<byte[]> unexpected(RuntimeException e) {
        String id = UUID.randomUUID().toString();
        log.error("admin transport: unexpected failure {}", id, e);
        return TransportJson.refusal(new TransportException(TransportError.INTERNAL,
                "an unexpected failure; the server log names it " + id));
    }
}
