package com.ljyh.mei.playback

import androidx.media3.common.Player
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A skip that lands on Media3's post-failure STATE_IDLE must prepare() again. */
class PlayerSkipPrepareTest {
    @Test
    fun skipAfterFailedPlaybackPreparesTheIdlePlayer() {
        // onPlayerError parks the player in STATE_IDLE; a next/previous skip that
        // only switches the current item never resolves or starts playback again.
        assertTrue(shouldPrepareAfterSkip(Player.STATE_IDLE))
    }

    @Test
    fun skipOnLivePlaybackDoesNotRePrepare() {
        assertFalse(shouldPrepareAfterSkip(Player.STATE_BUFFERING))
        assertFalse(shouldPrepareAfterSkip(Player.STATE_READY))
    }
}
