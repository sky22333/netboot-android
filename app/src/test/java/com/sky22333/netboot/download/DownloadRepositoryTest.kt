package com.sky22333.netboot.download

import com.sky22333.netboot.data.DownloadSegmentEntity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DownloadRepositoryTest {
    @Test
    fun segmentsCoverFileWithoutGaps() {
        val segments = DownloadRepository.newSegments("task", 10, 4)

        assertEquals(listOf(0L..2L, 3L..5L, 6L..7L, 8L..9L), segments.map { it.startByte..it.endByte })
        assertTrue(DownloadRepository.validSegments(segments, 10))
    }

    @Test
    fun segmentCountDoesNotExceedTinyFile() {
        val segments = DownloadRepository.newSegments("task", 2, 8)

        assertEquals(2, segments.size)
        assertEquals(listOf(0L..0L, 1L..1L), segments.map { it.startByte..it.endByte })
    }

    @Test
    fun everySegmentBoundaryIsContiguousForLargeFiles() {
        val total = 5L * 1024 * 1024 * 1024 + 12345
        val segments = DownloadRepository.newSegments("task", total, 8)

        assertEquals(8, segments.size)
        assertEquals(0L, segments.first().startByte)
        assertEquals(total - 1, segments.last().endByte)
        assertTrue(DownloadRepository.validSegments(segments, total))
    }

    @Test
    fun invalidResumeStateIsRejected() {
        val currentBeyondEnd = listOf(DownloadSegmentEntity("task", 0, 0, 9, 11))
        assertFalse(DownloadRepository.validSegments(currentBeyondEnd, 10))

        val gap = listOf(
            DownloadSegmentEntity("task", 0, 0, 4, 0),
            DownloadSegmentEntity("task", 1, 6, 9, 6),
        )
        assertFalse(DownloadRepository.validSegments(gap, 10))

        val reordered = listOf(
            DownloadSegmentEntity("task", 1, 5, 9, 5),
            DownloadSegmentEntity("task", 0, 0, 4, 0),
        )
        assertFalse(DownloadRepository.validSegments(reordered, 10))
    }

    @Test
    fun aFullyDownloadedSegmentStaysValidSoResumeIsIdempotent() {
        // currentByte == endByte + 1 is how a finished segment is persisted.
        val finished = listOf(DownloadSegmentEntity("task", 0, 0, 4, 5))
        assertTrue(DownloadRepository.validSegments(finished, 5))
    }
}
