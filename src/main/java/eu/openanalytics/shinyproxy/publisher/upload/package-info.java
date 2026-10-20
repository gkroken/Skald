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
 * The upload side of publishing (WORKPLAN-BUNDLES.md T8): bundles arrive over the admin
 * transport and are validated as claimed, leased work that survives the instance that
 * accepted them. Every state change is a transition of {@code spec/lifecycle-v1.json}'s bundle
 * machine; the shapes are {@code spec/admin-transport-v1.json}'s {@code shapes}.
 */
package eu.openanalytics.shinyproxy.publisher.upload;
