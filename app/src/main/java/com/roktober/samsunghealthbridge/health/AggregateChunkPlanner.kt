package com.roktober.samsunghealthbridge.health

import java.time.LocalDate

data class AggregateChunk(
    val logicalStart: LocalDate,
    val queryStart: LocalDate,
    val endDateExclusive: LocalDate,
)

object AggregateChunkPlanner {
    fun plan(
        firstDate: LocalDate,
        endDateExclusive: LocalDate,
        chunkDays: Long,
    ): List<AggregateChunk> {
        require(firstDate < endDateExclusive) { "date range must not be empty" }
        require(chunkDays > 0) { "chunkDays must be positive" }
        return buildList {
            var logicalStart = firstDate
            while (logicalStart < endDateExclusive) {
                val logicalEnd = minOf(logicalStart.plusDays(chunkDays), endDateExclusive)
                add(
                    AggregateChunk(
                        logicalStart = logicalStart,
                        queryStart = logicalStart.minusDays(1),
                        endDateExclusive = logicalEnd,
                    ),
                )
                logicalStart = logicalEnd
            }
        }
    }
}
