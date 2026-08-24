package com.roktober.samsunghealthbridge.health

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class AggregateChunkPlannerTest {
    @Test
    fun `each logical chunk reads one preceding context day`() {
        val chunks =
            AggregateChunkPlanner.plan(
                firstDate = LocalDate.parse("2026-01-01"),
                endDateExclusive = LocalDate.parse("2026-01-06"),
                chunkDays = 3,
            )

        assertEquals(
            listOf(
                AggregateChunk(
                    logicalStart = LocalDate.parse("2026-01-01"),
                    queryStart = LocalDate.parse("2025-12-31"),
                    endDateExclusive = LocalDate.parse("2026-01-04"),
                ),
                AggregateChunk(
                    logicalStart = LocalDate.parse("2026-01-04"),
                    queryStart = LocalDate.parse("2026-01-03"),
                    endDateExclusive = LocalDate.parse("2026-01-06"),
                ),
            ),
            chunks,
        )
    }
}
