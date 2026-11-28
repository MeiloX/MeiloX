package com.ljyh.mei.ui.component.player.component

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest

internal class LyricManualScrollState {
    var isBrowsing by mutableStateOf(false)
        private set
    var isDragging by mutableStateOf(false)
        private set
    var returnToPlaybackPending = false
        private set
    private val followRequest = MutableStateFlow<Any?>(null)

    fun onDragStarted() {
        followRequest.value = null
        isDragging = true
        onUserScroll()
    }

    fun onUserScroll() {
        isBrowsing = true
        returnToPlaybackPending = true
        if (!isDragging) followRequest.value = Any()
    }

    fun onDragStopped() {
        if (!isDragging) return
        isDragging = false
        onUserScroll()
    }

    suspend fun followAfterManualScroll(waitForScrollToSettle: suspend () -> Unit) {
        followRequest.collectLatest { request ->
            if (request == null) return@collectLatest
            delay(5_000)
            // The grace period runs during the fling. Wait only for motion left at expiry.
            waitForScrollToSettle()
            if (!isDragging && followRequest.value === request) {
                isBrowsing = false
                followRequest.value = null
            }
        }
    }

    fun onLineSelected() {
        followRequest.value = null
        isBrowsing = false
        returnToPlaybackPending = true
    }

    fun onHidden() {
        followRequest.value = null
        isBrowsing = false
        isDragging = false
        returnToPlaybackPending = true
    }

    fun onPlaybackPositioned() {
        // A new drag may start while the previous scroll's child coroutine finishes.
        if (!isBrowsing) returnToPlaybackPending = false
    }
}
