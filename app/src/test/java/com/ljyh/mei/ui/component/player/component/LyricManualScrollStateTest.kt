package com.ljyh.mei.ui.component.player.component

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LyricManualScrollStateTest {
    @Test
    fun hidingCancelsFollowAndClearsDragState() = runTest {
        val state = followState()
        state.onDragStarted()
        state.onDragStopped()
        advanceBy(4_999)

        state.onHidden()
        advanceBy(1)

        assertFalse(state.isDragging)
        assertFalse(state.isBrowsing)
        assertTrue(state.returnToPlaybackPending)
        state.onPlaybackPositioned()
        assertFalse(state.returnToPlaybackPending)

        state.onDragStarted()
        state.onHidden()
        assertFalse(state.isDragging)
        assertFalse(state.isBrowsing)
    }

    @Test
    fun initialPlaybackDoesNotBrowseOrRequestRepositioning() = runTest {
        val state = followState()
        advanceBy(10_000)

        assertFalse(state.isBrowsing)
        assertFalse(state.isDragging)
        assertFalse(state.returnToPlaybackPending)
    }

    @Test
    fun dragReleaseAllowsFiveFullSecondsBeforeReturning() = runTest {
        val state = followState()
        state.onDragStarted()
        state.onDragStopped()

        advanceBy(4_999)
        assertTrue(state.isBrowsing)
        assertFalse(state.isDragging)

        advanceBy(1)
        assertFalse(state.isBrowsing)
    }

    @Test
    fun anotherDragReplacesThePreviousFollowRequest() = runTest {
        val state = followState()
        state.onDragStarted()
        state.onDragStopped()
        advanceBy(4_000)
        state.onDragStarted()

        advanceBy(1_000)
        assertTrue(state.isBrowsing)
        assertTrue(state.isDragging)

        state.onDragStopped()
        advanceBy(4_999)
        assertTrue(state.isBrowsing)
        advanceBy(1)
        assertFalse(state.isBrowsing)
    }

    @Test
    fun holdingADragDoesNotReturnEvenWithoutFurtherScrollDeltas() = runTest {
        val state = followState()
        state.onDragStarted()
        state.onUserScroll()

        advanceBy(60_000)
        assertTrue(state.isDragging)
        assertTrue(state.isBrowsing)

        state.onDragStopped()
        advanceBy(5_000)
        assertFalse(state.isBrowsing)
    }

    @Test
    fun latestNonTouchInputRestartsTheFollowDelay() = runTest {
        val state = followState()
        state.onUserScroll()
        advanceBy(4_000)
        state.onUserScroll()

        advanceBy(1_000)
        assertTrue(state.isBrowsing)
        advanceBy(4_000)
        assertFalse(state.isBrowsing)
    }

    @Test
    fun aFlingThatOutlastsTheDeadlineReturnsWhenItEnds() = runTest {
        val isScrolling = MutableStateFlow(true)
        val state = followState(isScrolling)
        state.onDragStarted()
        state.onDragStopped()

        advanceBy(8_000)
        assertTrue(state.isBrowsing)

        isScrolling.value = false
        runCurrent()
        assertFalse(state.isBrowsing)
    }

    @Test
    fun aNewDragCancelsTheWaitForAnOldFling() = runTest {
        val isScrolling = MutableStateFlow(true)
        val state = followState(isScrolling)
        state.onDragStarted()
        state.onDragStopped()
        advanceBy(5_000)
        assertTrue(state.isBrowsing)

        state.onDragStarted()
        isScrolling.value = false
        runCurrent()
        assertTrue(state.isDragging)
        assertTrue(state.isBrowsing)

        state.onDragStopped()
        advanceBy(5_000)
        assertFalse(state.isBrowsing)
    }

    @Test
    fun aShortFlingDoesNotRestartTheGracePeriod() = runTest {
        val isScrolling = MutableStateFlow(true)
        val state = followState(isScrolling)
        state.onDragStarted()
        state.onDragStopped()
        advanceBy(2_000)
        isScrolling.value = false

        advanceBy(2_999)
        assertTrue(state.isBrowsing)
        advanceBy(1)
        assertFalse(state.isBrowsing)
    }

    @Test
    fun returningStillRequestsPositioningWithoutAPlaybackLineChange() = runTest {
        val state = followState()
        state.onDragStarted()
        state.onDragStopped()

        advanceBy(5_000)
        assertFalse(state.isBrowsing)
        assertTrue(state.returnToPlaybackPending)
        advanceBy(1_000)
        assertTrue(state.returnToPlaybackPending)

        state.onPlaybackPositioned()
        assertFalse(state.returnToPlaybackPending)
    }

    @Test
    fun stalePositioningCompletionDoesNotClearANewerDragsReturnRequest() = runTest {
        val state = followState()
        state.onDragStarted()
        state.onDragStopped()
        advanceBy(5_000)
        assertFalse(state.isBrowsing)

        state.onDragStarted()
        state.onPlaybackPositioned()
        assertTrue(state.isBrowsing)
        assertTrue(state.returnToPlaybackPending)

        state.onDragStopped()
        advanceBy(5_000)
        assertFalse(state.isBrowsing)
        assertTrue(state.returnToPlaybackPending)

        state.onPlaybackPositioned()
        assertFalse(state.returnToPlaybackPending)
    }

    @Test
    fun selectingALineResumesPlaybackAndCancelsTheOldFollowRequest() = runTest {
        val state = followState()
        state.onDragStarted()
        state.onDragStopped()
        advanceBy(1_000)
        assertTrue(state.isBrowsing)

        state.onLineSelected()
        assertFalse(state.isBrowsing)
        assertTrue(state.returnToPlaybackPending)
        state.onPlaybackPositioned()
        advanceBy(4_000)
        assertFalse(state.returnToPlaybackPending)
    }

    private fun TestScope.followState(
        isScrolling: StateFlow<Boolean> = MutableStateFlow(false),
    ): LyricManualScrollState {
        val state = LyricManualScrollState()
        backgroundScope.launch {
            state.followAfterManualScroll { isScrolling.first { !it } }
        }
        runCurrent()
        return state
    }

    private fun TestScope.advanceBy(milliseconds: Long) {
        advanceTimeBy(milliseconds)
        runCurrent()
    }
}
