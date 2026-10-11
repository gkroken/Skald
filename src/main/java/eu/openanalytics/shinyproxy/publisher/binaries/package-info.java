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
/**
 * Shared Linux R binaries (WORKPLAN-BUNDLES.md T7b, step 2; option 3b, user 2026-10-10): the
 * queue of public CRAN package versions Skald compiles once per platform, each in its own
 * sandbox, and uploads to forge from outside it. V4's constraints hold the facts a row can
 * carry; this package holds the ones that need more than one row (dependency order, the
 * yield to app builds, cycles).
 */
package eu.openanalytics.shinyproxy.publisher.binaries;
