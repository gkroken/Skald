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
package eu.openanalytics.shinyproxy.publisher.db;

import org.testcontainers.containers.PostgreSQLContainer;

import java.time.Duration;

/**
 * The tests' PostgreSQL, one definition for every class that needs one. initdb skips its
 * final sync to disk (--no-sync) and the startup wait is five minutes, not testcontainers'
 * one: on a loaded host initdb's sync alone outlasted the minute and failed a whole class
 * in beforeAll (3d18996 N1; seen in a full suite run and again in the T7b probe). The data
 * is thrown away with the container, so neither changes what a test proves.
 */
public final class TestPostgres {

    private TestPostgres() {
    }

    public static PostgreSQLContainer<?> create() {
        return new PostgreSQLContainer<>("postgres:16").withDatabaseName("skald")
                .withEnv("POSTGRES_INITDB_ARGS", "--no-sync")
                .withStartupTimeout(Duration.ofMinutes(5));
    }
}
