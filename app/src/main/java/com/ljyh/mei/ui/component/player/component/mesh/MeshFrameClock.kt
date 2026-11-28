package com.ljyh.mei.ui.component.player.component.mesh

import androidx.compose.runtime.withFrameNanos
import kotlin.coroutines.coroutineContext
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

internal const val DefaultMeshFlowSpeed = .25f
internal const val PrimaryArtworkOrbitBaseSeconds = 22.5
internal const val LowerArtworkOrbitBaseSeconds = 180.0
private const val CreaseCycleSeconds = 22.0
private const val ArtworkOrbitRate = 1.5

internal fun meshMotionSeconds(playingNanos: Long, flowSpeed: Float): Double =
    playingNanos / 1_000_000_000.0 * (flowSpeed / DefaultMeshFlowSpeed)

internal fun meshArtworkOrbitAngle(motionSeconds: Double, periodSeconds: Double): Float =
    (2.0 * PI * ((motionSeconds * ArtworkOrbitRate / periodSeconds) % 1.0)).toFloat()

internal fun meshCreasePhase(playingNanos: Long, flowSpeed: Float): Float {
    val cycles = meshMotionSeconds(playingNanos, flowSpeed) / CreaseCycleSeconds
    return (.5 + .5 * sin(2.0 * PI * (cycles % 1.0))).toFloat()
}

/** Request a vsync only when the mesh is due, rather than polling every display frame. */
internal suspend fun runMeshFrames(
    intervalNanos: Long,
    needsAnimation: () -> Boolean,
    nanoTime: () -> Long = System::nanoTime,
    onFrame: (Long) -> Unit,
) {
    require(intervalNanos > 0L)
    var nextFrameNanos: Long? = null
    while (coroutineContext.isActive && needsAnimation()) {
        withFrameNanos { frameTime ->
            // Frame clocks can have a different epoch from System.nanoTime. Use their
            // timestamps only for animation, and the monotonic clock only for pacing.
            val now = nanoTime()
            var deadline = (nextFrameNanos ?: now) + intervalNanos
            // A callback can arrive just after its normalized vsync. Allow one late
            // deadline so 60 Hz animation on a 60 Hz display does not fall to 30 Hz.
            // Larger stalls discard missed periods instead of replaying them.
            if (now - deadline >= intervalNanos) {
                deadline += ((now - deadline) / intervalNanos + 1L) * intervalNanos
            }
            nextFrameNanos = deadline
            onFrame(frameTime)
        }
        if (!coroutineContext.isActive || !needsAnimation()) return
        // Absolute deadlines prevent vsync wait time accumulating into cadence drift.
        // Millisecond truncation lets us register just before a deadline's vsync.
        val remainingNanos = checkNotNull(nextFrameNanos) - nanoTime()
        delay((remainingNanos / 1_000_000L).coerceAtLeast(0L))
    }
}
