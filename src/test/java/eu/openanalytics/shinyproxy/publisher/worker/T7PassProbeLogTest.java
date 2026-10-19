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
package eu.openanalytics.shinyproxy.publisher.worker;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The pass probe's reading of a two-solve build log (9cc2d58 N1), without the probe: each solve
 * numbers its steps afresh, so a restore is judged within its own solve.
 */
public class T7PassProbeLogTest {

    private static final String RESTORE = "renv::restore";

    private static T7PassProbeTest.Restore read(String... lines) {
        return T7PassProbeTest.restore(RESTORE, List.of(lines), "test");
    }

    @Test
    public void aColdBuildRanItsRestoreInTheFirstSolve() {
        assertEquals(T7PassProbeTest.Restore.RAN, read(
                "#1 [internal] load build definition from Dockerfile",
                "#6 [5/7] RUN [\"Rscript\", \"-e\", \"renv::restore()\"]",
                "#6 1.204 Installing shiny",
                "#6 DONE 20.9s",
                "#1 [internal] load build definition from Dockerfile",
                "#8 [5/7] RUN [\"Rscript\", \"-e\", \"renv::restore()\"]",
                "#8 CACHED"));
    }

    @Test
    public void aWarmBuildIsReusedEvenWhereAnotherSolvesStepSharesTheRestoresNumber() {
        // The first solve's restore is #6; its #8 is the COPY that printed. The second solve's
        // restore is #8. Read across solves, #8 printed and the warm build looked cold.
        assertEquals(T7PassProbeTest.Restore.REUSED, read(
                "#1 [internal] load build definition from Dockerfile",
                "#6 [5/7] RUN [\"Rscript\", \"-e\", \"renv::restore()\"]",
                "#6 CACHED",
                "#8 [7/7] COPY app/ /app/",
                "#8 0.512 copying",
                "#8 DONE 0.6s",
                "#1 [internal] load build definition from Dockerfile",
                "#8 [5/7] RUN [\"Rscript\", \"-e\", \"renv::restore()\"]",
                "#8 CACHED"));
    }

    @Test
    public void aSingleSolveLogIsReadAsBefore() {
        assertEquals(T7PassProbeTest.Restore.REUSED, read(
                "#4 [5/7] RUN [\"Rscript\", \"-e\", \"renv::restore()\"]",
                "#4 DONE 3.1s"));
    }
}
