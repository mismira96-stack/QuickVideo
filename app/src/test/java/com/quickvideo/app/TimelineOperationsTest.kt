package com.quickvideo.app

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineOperationsTest {

    private val dummyUri: Uri? = null

    private fun sampleClip(
        durationMs: Long = 10_000L,
        trimStartMs: Long = 0L,
        trimEndMs: Long = durationMs,
        speed: Float = 1.0f
    ): VideoClip = VideoClip(
        uri = dummyUri,
        title = "sample.mp4",
        sourceDurationMs = durationMs,
        trimStartMs = trimStartMs,
        trimEndMs = trimEndMs,
        speed = speed
    )

    @Test
    fun splitAtDividesClipIntoTwoContiguousClips() {
        val initial = listOf(sampleClip(10_000L))
        val split = TimelineOperations.splitAt(initial, 4_000L)
        assertNotNull(split)
        val (clips, selectedIdx) = split!!
        assertEquals(2, clips.size)
        assertEquals(1, selectedIdx)
        assertEquals(0L, clips[0].trimStartMs)
        assertEquals(4_000L, clips[0].trimEndMs)
        assertEquals(4_000L, clips[1].trimStartMs)
        assertEquals(10_000L, clips[1].trimEndMs)
        assertEquals(10_000L, TimelineOperations.totalDurationMs(clips))
    }

    @Test
    fun splitNearClipEdgesIsRejected() {
        val initial = listOf(sampleClip(5_000L))
        assertNull(TimelineOperations.splitAt(initial, 100L))
        assertNull(TimelineOperations.splitAt(initial, 4_900L))
    }

    @Test
    fun trimFromHereCutsBeforePlayheadAndUpdatesPlayhead() {
        val initial = listOf(
            sampleClip(6_000L),
            sampleClip(8_000L)
        )
        // At 8_500ms => 2_500ms inside second clip
        val res = TimelineOperations.trimFromHere(initial, 8_500L)
        assertNotNull(res)
        val (updated, newPlayhead) = res!!
        assertEquals(2_500L, updated[1].trimStartMs)
        assertEquals(8_000L, updated[1].trimEndMs)
        assertEquals(6_000L, newPlayhead)
        assertEquals(11_500L, TimelineOperations.totalDurationMs(updated))
    }

    @Test
    fun trimToHereCutsAfterPlayheadAndMovesPlayheadToClipEnd() {
        val initial = listOf(sampleClip(10_000L))
        val res = TimelineOperations.trimToHere(initial, 6_200L)
        assertNotNull(res)
        val (updated, newPlayhead) = res!!
        assertEquals(0L, updated[0].trimStartMs)
        assertEquals(6_200L, updated[0].trimEndMs)
        assertEquals(6_200L, newPlayhead)
    }

    @Test
    fun speedAdjustmentScalesTimelineDurationUpTo2x() {
        val initial = listOf(sampleClip(10_000L))
        val at2x = TimelineOperations.updateClipSpeed(initial, 0, 2.0f)
        assertEquals(2.0f, at2x[0].speed, 0.001f)
        assertEquals(5_000L, TimelineOperations.totalDurationMs(at2x))

        // Splitting a 2.0x clip at timeline 2_000ms should split source at 4_000ms
        val split = TimelineOperations.splitAt(at2x, 2_000L)
        assertNotNull(split)
        val (parts, _) = split!!
        assertEquals(4_000L, parts[0].trimEndMs)
        assertEquals(4_000L, parts[1].trimStartMs)
    }

    @Test
    fun dateGroupingCreatesHeadersAndVideoEntriesInOrder() {
        val now = 1_760_000_000_000L
        val videos = listOf(
            GalleryVideoItem(1L, dummyUri, "a.mp4", 3000L, now / 1000L, 1080, 1920, 100L),
            GalleryVideoItem(2L, dummyUri, "b.mp4", 5000L, (now - 86_400_000L) / 1000L, 1080, 1920, 100L)
        )
        val grouped = VideoRepository.groupByDate(videos, now)
        assertEquals(4, grouped.size)
        assertTrue(grouped[0] is GalleryListItem.DateHeader)
        assertEquals("오늘", (grouped[0] as GalleryListItem.DateHeader).dateLabel)
        assertTrue(grouped[2] is GalleryListItem.DateHeader)
        assertEquals("어제", (grouped[2] as GalleryListItem.DateHeader).dateLabel)
    }

    @Test
    fun muteAndRotateClipOperationsUpdateStateAndPortraitDimensions() {
        val initial = listOf(sampleClip(5_000L))
        val muted = TimelineOperations.toggleClipMute(initial, 0)
        assertTrue(muted[0].isMuted)

        val rotated90 = TimelineOperations.rotateClip90(muted, 0)
        assertEquals(90f, rotated90[0].rotationDegrees, 0.01f)
        assertEquals(1920, rotated90[0].effectiveWidth)
        assertEquals(1080, rotated90[0].effectiveHeight)

        // Free-angle drag rotate, pinch scale, and horizontal mirror flip
        val transformed = TimelineOperations.updateClipTransform(rotated90, 0, rotationDegrees = -32.5f, scaleFactor = 1.45f)
        assertEquals(-32.5f, transformed[0].rotationDegrees, 0.01f)
        assertEquals(1.45f, transformed[0].scaleFactor, 0.01f)

        val mirrored = TimelineOperations.toggleClipMirror(transformed, 0)
        assertTrue(mirrored[0].isMirrored)

        val reset = TimelineOperations.resetClipTransform(mirrored, 0)
        assertEquals(0f, reset[0].rotationDegrees, 0.01f)
        assertEquals(1.0f, reset[0].scaleFactor, 0.01f)
        assertEquals(false, reset[0].isMirrored)

        // Trim restore to very start (첨부터) and very end (끝까지)
        val trimmed = listOf(sampleClip(10_000L, trimStartMs = 2_000L, trimEndMs = 7_000L))
        val restoredStart = TimelineOperations.trimToVeryStart(trimmed, 0)
        assertNotNull(restoredStart)
        assertEquals(0L, restoredStart!!.first[0].trimStartMs)
        val restoredEnd = TimelineOperations.trimToVeryEnd(restoredStart.first, 0)
        assertNotNull(restoredEnd)
        assertEquals(10_000L, restoredEnd!!.first[0].trimEndMs)

        // Portrait 1080x1920 export at 1080p preserves 1080x1920
        val (portW, portH) = VideoExporter.computeOutputDimensions(
            initial[0],
            ExportResolution.FHD_1080P,
            AspectRatioOption.AUTO
        )
        assertEquals(1080, portW)
        assertEquals(1920, portH)

        // Rotated 90° (now landscape 1920x1080) export at 1080p outputs 1920x1080
        val (landW, landH) = VideoExporter.computeOutputDimensions(
            rotated90[0],
            ExportResolution.FHD_1080P,
            AspectRatioOption.AUTO
        )
        assertEquals(1920, landW)
        assertEquals(1080, landH)
    }
}
