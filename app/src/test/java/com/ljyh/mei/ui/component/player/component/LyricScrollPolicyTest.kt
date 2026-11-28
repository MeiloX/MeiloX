package com.ljyh.mei.ui.component.player.component

import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.ui.component.player.AutoMixVisuals
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeAlignment
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import com.mocharealm.accompanist.lyrics.core.model.synced.SyncedLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricScrollPolicyTest {
    private val lines = listOf(
        SyncedLine("First", null, 10_000, 15_000),
        SyncedLine("Second", null, 15_000, 20_000),
        SyncedLine("After the interlude", null, 30_000, 35_000),
    )

    @Test
    fun openingDuringPlaybackStartsAtTheCurrentLine() {
        assertEquals(2, lyricFocusLineIndex(lines, 32_000))
    }

    @Test
    fun openingBeforeTheFirstLineStartsAtTheIntro() {
        assertEquals(0, lyricFocusLineIndex(lines, 5_000))
    }

    @Test
    fun openingAtALineBoundaryUsesTheNewLine() {
        assertEquals(1, lyricFocusLineIndex(lines, 15_000))
    }

    @Test
    fun openingDuringAnInterludeUsesTheUpcomingLine() {
        assertEquals(2, lyricFocusLineIndex(lines, 20_000))
        assertEquals(2, lyricFocusLineIndex(lines, 25_000))
    }

    @Test
    fun openingAfterTheLyricsEndStaysOnTheLastLine() {
        assertEquals(2, lyricFocusLineIndex(lines, 40_000))
    }

    @Test
    fun overlappingVocalsReplaceOnlyTheSameSide() {
        val duet = listOf(
            mainLine(KaraokeAlignment.Start, 10_000, 20_000),
            mainLine(KaraokeAlignment.End, 15_000, 25_000),
            mainLine(KaraokeAlignment.Start, 17_000, 22_000),
        )
        assertEquals(listOf(0, 1), latestMainLyricLineIndices(duet, 16_000))
        assertEquals(listOf(0, 1), activeMainLyricLineIndices(duet, 16_000))
        assertEquals(0, lyricFocusLineIndex(duet, 16_000))
        assertEquals(listOf(1, 2), latestMainLyricLineIndices(duet, 18_000))
        assertEquals(listOf(1, 2), activeMainLyricLineIndices(duet, 18_000))
        assertEquals(1, lyricFocusLineIndex(duet, 18_000))
    }

    @Test
    fun interludeStartsWhenBothSingersFinishAndEndsAtTheNextVocal() {
        val duet = listOf(
            mainLine(KaraokeAlignment.Start, 0, 20_000),
            mainLine(KaraokeAlignment.End, 12_000, 13_000),
            mainLine(KaraokeAlignment.Start, 26_000, 35_000),
        )

        val gaps = lyricQuietIntervals(duet)
        assertEquals(listOf(20_000 until 26_000), gaps)
        assertEquals(null, lyricQuietIntervalAt(gaps, 13_000))
        assertEquals(gaps.single(), lyricQuietIntervalAt(gaps, 20_000))
        assertEquals(null, lyricQuietIntervalAt(gaps, 26_000))
    }

    @Test
    fun accompanimentSplitsSilenceAndContributesMotionBoundaries() {
        val attached = KaraokeLine.AccompanimentKaraokeLine(
            syllables = emptyList(),
            translation = null,
            alignment = KaraokeAlignment.Start,
            start = 10_000,
            end = 16_000,
        )
        val vocals = listOf(
            mainLine(KaraokeAlignment.Start, 0, 10_000, listOf(attached)),
            attached.copy(start = 21_000, end = 24_000),
            mainLine(KaraokeAlignment.End, 29_000, 35_000),
        )
        val boundaries = lyricMotionBoundaries(vocals)

        assertEquals(emptyList<IntRange>(), lyricQuietIntervals(vocals, mapOf(1 to 0)))
        assertEquals(listOf(0, 10_000, 16_000, 21_000, 24_000, 29_000, 35_000), boundaries)
        assertEquals(1, lyricMotionBoundaryIndex(boundaries, 15_999))
        assertEquals(2, lyricMotionBoundaryIndex(boundaries, 16_000))
    }

    @Test
    fun delayedAccompanimentLeavesQuietIntervalsOnBothSides() {
        val harmony = KaraokeLine.AccompanimentKaraokeLine(
            syllables = emptyList(),
            translation = null,
            alignment = KaraokeAlignment.Start,
            start = 7_000,
            end = 8_000,
        )
        val vocals = listOf(
            mainLine(KaraokeAlignment.Start, 0, 1_000, listOf(harmony)),
            mainLine(KaraokeAlignment.Start, 14_000, 15_000),
        )

        val gaps = lyricQuietIntervals(vocals)
        assertEquals(listOf(1_000 until 7_000, 8_000 until 14_000), gaps)
        assertEquals(gaps[0], lyricQuietIntervalAt(gaps, 6_999))
        assertEquals(null, lyricQuietIntervalAt(gaps, 7_000))
        assertEquals(gaps[1], lyricQuietIntervalAt(gaps, 8_000))
        assertEquals(listOf(0, 1_000, 7_000, 8_000, 14_000, 15_000), lyricMotionBoundaries(vocals))
    }

    @Test
    fun attachedVocalsCountBeforeTheirLeadButStopAtItsReplacement() {
        val earlyHarmony = KaraokeLine.AccompanimentKaraokeLine(
            syllables = emptyList(),
            translation = null,
            alignment = KaraokeAlignment.Start,
            start = 6_000,
            end = 7_000,
        )
        val vocals = listOf(
            mainLine(
                KaraokeAlignment.Start,
                14_000,
                15_000,
                listOf(earlyHarmony, earlyHarmony.copy(start = 15_500, end = 27_000)),
            ),
            mainLine(KaraokeAlignment.Start, 16_000, 17_000),
            mainLine(KaraokeAlignment.Start, 30_000, 31_000),
        )

        assertEquals(
            listOf(0 until 6_000, 7_000 until 14_000, 17_000 until 30_000),
            lyricQuietIntervals(vocals),
        )
    }

    @Test
    fun supersededTimingDoesNotHideTheIntroOrInterlude() {
        val vocals = listOf(
            mainLine(KaraokeAlignment.End, 6_000, 35_000),
            mainLine(KaraokeAlignment.End, 10_000, 12_000),
            mainLine(KaraokeAlignment.Start, 20_000, 25_000),
        )

        assertEquals(listOf(0 until 6_000, 12_000 until 20_000), lyricQuietIntervals(vocals))
    }

    @Test
    fun anOverlongRightLineIsReplacedByTheNextRightLineWithoutReturning() {
        val duet = listOf(
            mainLine(KaraokeAlignment.End, 16_770, 35_040),
            mainLine(KaraokeAlignment.Start, 18_360, 20_000),
            mainLine(KaraokeAlignment.End, 18_760, 20_000),
            mainLine(KaraokeAlignment.Start, 30_000, 35_000),
        )

        assertEquals(listOf(0, 1), latestMainLyricLineIndices(duet, 18_360))
        assertEquals(listOf(0, 1), activeMainLyricLineIndices(duet, 18_360))
        assertEquals(0, lyricFocusLineIndex(duet, 18_360))
        assertEquals(listOf(1, 2), latestMainLyricLineIndices(duet, 18_760))
        assertEquals(listOf(1, 2), activeMainLyricLineIndices(duet, 18_760))
        assertEquals(1, lyricFocusLineIndex(duet, 18_760))
        assertEquals(listOf(1, 2), latestMainLyricLineIndices(duet, 20_000))
        assertEquals(emptyList<Int>(), activeMainLyricLineIndices(duet, 20_000))
        assertEquals(3, lyricFocusLineIndex(duet, 20_000))
        assertEquals(3, lyricFocusLineIndex(duet, 40_000))

        // Seeking backward recomputes each singer's latest line at the new position.
        assertEquals(listOf(0), latestMainLyricLineIndices(duet, 18_000))
        assertEquals(listOf(0), activeMainLyricLineIndices(duet, 18_000))
        assertEquals(0, lyricFocusLineIndex(duet, 18_000))
        assertEquals(emptyList<Int>(), latestMainLyricLineIndices(duet, 16_000))
        assertEquals(emptyList<Int>(), activeMainLyricLineIndices(duet, 16_000))
    }

    @Test
    fun unspecifiedAndSyncedLinesShareTheLeftSinger() {
        val mixed = listOf(
            SyncedLine("First left line", null, 10_000, 30_000),
            mainLine(KaraokeAlignment.Unspecified, 12_000, 13_000),
            mainLine(KaraokeAlignment.Start, 14_000, 15_000),
            mainLine(KaraokeAlignment.End, 15_000, 16_000),
            SyncedLine("Latest left line", null, 16_000, 17_000),
        )

        assertEquals(listOf(1), latestMainLyricLineIndices(mixed, 12_000))
        assertEquals(1, lyricFocusLineIndex(mixed, 12_000))
        assertEquals(listOf(2), latestMainLyricLineIndices(mixed, 14_000))
        assertEquals(2, lyricFocusLineIndex(mixed, 14_000))
        assertEquals(listOf(2, 3), latestMainLyricLineIndices(mixed, 15_000))
        assertEquals(listOf(3), activeMainLyricLineIndices(mixed, 15_000))
        assertEquals(3, lyricFocusLineIndex(mixed, 15_000))
        assertEquals(listOf(3, 4), latestMainLyricLineIndices(mixed, 16_000))
        assertEquals(listOf(4), activeMainLyricLineIndices(mixed, 16_000))
        assertEquals(4, lyricFocusLineIndex(mixed, 16_000))
    }

    @Test
    fun accompanimentExtendsTheMainLinesFocus() {
        val accompaniment = KaraokeLine.AccompanimentKaraokeLine(
            syllables = emptyList(),
            translation = null,
            alignment = KaraokeAlignment.Start,
            start = 15_000,
            end = 25_000,
        )
        val main = KaraokeLine.MainKaraokeLine(
            syllables = emptyList(),
            translation = null,
            alignment = KaraokeAlignment.Start,
            start = 10_000,
            end = 20_000,
            accompanimentLines = listOf(accompaniment),
        )
        assertEquals(
            listOf(0),
            latestMainLyricLineIndices(listOf(main, accompaniment, lines.last()), 23_000),
        )
        assertEquals(
            listOf(0),
            activeMainLyricLineIndices(listOf(main, accompaniment, lines.last()), 23_000),
        )
        assertEquals(0, lyricFocusLineIndex(listOf(main, accompaniment, lines.last()), 23_000))
        assertEquals(
            false,
            shouldSnapLyricScroll(listOf(main, accompaniment, lines.last()), 0, 2, 2),
        )
    }

    @Test
    fun standaloneAccompanimentExtendsTheMainLinesFocus() {
        val vocals = listOf(
            mainLine(KaraokeAlignment.Start, 10_000, 20_000),
            KaraokeLine.AccompanimentKaraokeLine(
                syllables = emptyList(),
                translation = null,
                alignment = KaraokeAlignment.Start,
                start = 18_000,
                end = 25_000,
            ),
            mainLine(KaraokeAlignment.Start, 30_000, 35_000),
        )

        assertEquals(listOf(0), activeMainLyricLineIndices(vocals, 23_000))
        assertEquals(0, lyricFocusLineIndex(vocals, 23_000))
        assertEquals(emptyList<Int>(), activeMainLyricLineIndices(vocals, 25_000))
        assertEquals(2, lyricFocusLineIndex(vocals, 25_000))
    }

    @Test
    fun accompanimentDoesNotReplaceMainVocalsOrReviveASupersededLine() {
        val nested = KaraokeLine.AccompanimentKaraokeLine(
            syllables = emptyList(),
            translation = null,
            alignment = KaraokeAlignment.Start,
            start = 11_000,
            end = 25_000,
        )
        val standalone = nested.copy(alignment = KaraokeAlignment.End, start = 10_500)
        val vocals = listOf(
            mainLine(KaraokeAlignment.Start, 10_000, 11_000, listOf(nested)),
            standalone,
            mainLine(KaraokeAlignment.Start, 12_000, 13_000),
            mainLine(KaraokeAlignment.End, 30_000, 35_000),
            standalone.copy(start = 36_000, end = 40_000),
        )

        assertEquals(listOf(0), latestMainLyricLineIndices(vocals, 11_500))
        assertEquals(listOf(0), activeMainLyricLineIndices(vocals, 11_500))
        assertEquals(0, lyricFocusLineIndex(vocals, 11_500))
        assertEquals(listOf(2), latestMainLyricLineIndices(vocals, 15_000))
        assertEquals(emptyList<Int>(), activeMainLyricLineIndices(vocals, 15_000))
        assertEquals(3, lyricFocusLineIndex(vocals, 15_000))
        assertEquals(listOf(2, 3), latestMainLyricLineIndices(vocals, 37_000))
        assertEquals(listOf(3), activeMainLyricLineIndices(vocals, 37_000))
        assertEquals(3, lyricFocusLineIndex(vocals, 37_000))
    }

    @Test
    fun jumpsWithinOneScreenKeepTheirAnimation() {
        assertEquals(false, shouldSnapLyricScroll(lines, 0, 1, 2))
        assertEquals(false, shouldSnapLyricScroll(lines, 0, 2, 3))
    }

    @Test
    fun theFirstTwoLinesOutsideTheScreenKeepTheirAnimation() {
        val extendedLines = lines + lines
        for (target in 2..3) {
            assertFalse(shouldSnapLyricScroll(extendedLines, 0, target, 2))
            assertFalse(shouldSnapLyricScroll(extendedLines, target, 0, 2))
        }
    }

    @Test
    fun jumpsBeyondTheScreenAndTwoExtraLinesSnapInBothDirections() {
        val extendedLines = lines + lines
        assertTrue(shouldSnapLyricScroll(extendedLines, 0, 4, 2))
        assertTrue(shouldSnapLyricScroll(extendedLines, 4, 0, 2))
    }

    @Test
    fun scrollingDoesNotSnapUntilTheViewportHasBeenMeasured() {
        assertEquals(false, shouldSnapLyricScroll(lines, 0, 2, 0))
    }

    @Test
    fun emptyLyricsHaveASafeInitialIndex() {
        assertEquals(0, lyricFocusLineIndex(emptyList(), 32_000))
    }

    @Test
    fun prelayoutRowsOutsideTheActualViewportAreNotVisible() {
        assertFalse(isLyricLineVisible(50, 100, 0, 1_000, 200))
        assertFalse(isLyricLineVisible(850, 100, 0, 1_000, 200))
    }

    @Test
    fun rowsTouchingViewportEdgesWithoutOverlapAreNotVisible() {
        assertFalse(isLyricLineVisible(100, 100, 0, 1_000, 200))
        assertFalse(isLyricLineVisible(800, 100, 0, 1_000, 200))
    }

    @Test
    fun evenOneVisiblePixelCountsAsAPartiallyVisibleLine() {
        assertTrue(isLyricLineVisible(101, 100, 0, 1_000, 200))
        assertTrue(isLyricLineVisible(799, 100, 0, 1_000, 200))
    }

    @Test
    fun aLineLargerThanTheViewportStillCountsAsVisible() {
        assertTrue(isLyricLineVisible(0, 1_000, 0, 1_000, 200))
    }

    @Test
    fun viewportOffsetsAreIncludedWhenExcludingKeepAliveRows() {
        assertFalse(isLyricLineVisible(-100, 100, -200, 1_200, 200))
        assertTrue(isLyricLineVisible(-99, 100, -200, 1_200, 200))
        assertTrue(isLyricLineVisible(999, 100, -200, 1_200, 200))
        assertFalse(isLyricLineVisible(1_000, 100, -200, 1_200, 200))
    }

    @Test
    fun zeroHeightRowsDoNotCountAsVisible() {
        assertFalse(isLyricLineVisible(500, 0, 0, 1_000, 200))
    }

    @Test
    fun autoMixLyricProgressMatchesArtworkProgressAcrossLongAndShortMixes() {
        val incoming = MediaMetadata(
            id = 2L,
            title = "Next",
            coverUrl = "",
            artists = emptyList(),
            duration = 180_000,
            album = MediaMetadata.Album(2L, "Album"),
        )
        for (durationMs in listOf(2_000L, 8_000L, 12_000L)) {
            var audioProgress = 0f
            val visuals = AutoMixVisuals(
                incoming = incoming,
                audioProgress = { audioProgress },
                durationMs = durationMs,
                incomingPositionMs = { 0L },
            )
            for (step in 0..20) {
                audioProgress = step / 20f
                assertEquals(
                    "duration=$durationMs step=$step",
                    visuals.artworkProgress(),
                    visuals.lyricProgress(),
                    0f,
                )
            }
            audioProgress = 0.5f
            assertEquals(0f, visuals.lyricProgress(), 0f)
            audioProgress = 1f
            assertEquals(1f, visuals.lyricProgress(), 0f)
        }
    }

    private fun mainLine(
        alignment: KaraokeAlignment,
        start: Int,
        end: Int,
        accompanimentLines: List<KaraokeLine.AccompanimentKaraokeLine>? = null,
    ) = KaraokeLine.MainKaraokeLine(
        syllables = emptyList(),
        translation = null,
        alignment = alignment,
        start = start,
        end = end,
        accompanimentLines = accompanimentLines,
    )
}
