package com.ljyh.mei.ui.component.player.component

import android.content.pm.ActivityInfo
import android.graphics.Paint
import android.graphics.RuntimeXfermode
import android.os.Build
import android.view.Display
import androidx.activity.compose.LocalActivity
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.toSize
import io.github.neoruaa.glasmio.glass.render.readGlassDynamicRange
import java.util.function.Consumer

private const val LyricAccentGain = 0.18f
private const val LyricAccentPeak = 1f + LyricAccentGain

@Composable
internal fun rememberPlayerLyricHdrAccent(enabled: Boolean): PlayerLyricHdrAccent? {
    val view = LocalView.current
    val window = LocalActivity.current?.window ?: return null
    if (!enabled || Build.VERSION.SDK_INT < 36 || window.colorMode != ActivityInfo.COLOR_MODE_HDR) {
        return null
    }
    val display = view.display ?: return null
    val sufficientHeadroom = remember(window, display) { mutableStateOf(false) }
    DisposableEffect(window, display) {
        val update = {
            val state = readGlassDynamicRange(window)
            sufficientHeadroom.value = state.hdrRequested && (state.availableHeadroom ?: 1f) >= LyricAccentPeak
        }
        val listener = Consumer<Display> { update() }
        val supportsRatio = display.isHdrSdrRatioAvailable
        if (supportsRatio) display.registerHdrSdrRatioChangedListener(view.context.mainExecutor, listener)
        update()
        onDispose {
            if (supportsRatio) display.unregisterHdrSdrRatioChangedListener(listener)
        }
    }
    return if (sufficientHeadroom.value) remember { PlayerLyricHdrAccent() } else null
}

/** Composites only explicit sustained-glyph masks after the ordinary capped lyric layer. */
internal class PlayerLyricHdrAccent {
    class Mask(
        val bounds: Rect,
        val isActive: () -> Boolean,
        val draw: DrawScope.() -> Unit,
    ) {
        var coordinates: LayoutCoordinates? = null
    }

    private val masks = mutableStateMapOf<Mask, Unit>()
    private val geometryVersion = mutableIntStateOf(0)
    private var viewport: LayoutCoordinates? = null
    private var overlay: LayoutCoordinates? = null
    private val transform = Matrix()
    private var edgeFadeHeight = Float.NaN
    private var edgeFadeInsets = Offset.Unspecified
    private var edgeFade: Brush? = null
    private var controlsFadeStops = Offset.Unspecified
    private var controlsFade: Brush? = null

    fun add(mask: Mask) {
        masks[mask] = Unit
    }

    fun remove(mask: Mask) {
        masks.remove(mask)
    }

    fun positionViewport(coordinates: LayoutCoordinates) {
        viewport = coordinates
        geometryChanged()
    }

    fun geometryChanged() {
        // Avoid invalidating the HDR overlay on scroll/scale frames when no sustained glyph is active.
        if (masks.keys.any { it.isActive() }) {
            geometryVersion.intValue++
        }
    }

    private fun edgeFade(height: Float, top: Float, bottom: Float): Brush? {
        val insets = Offset(top, bottom)
        if (edgeFadeHeight != height || edgeFadeInsets != insets) {
            edgeFadeHeight = height
            edgeFadeInsets = insets
            edgeFade = lyricEdgeFadeBrush(height, top, bottom)
        }
        return edgeFade
    }

    private fun controlsFade(height: Float, range: ClosedFloatingPointRange<Float>?): Brush? {
        if (range == null || range.start >= height || range.endInclusive <= range.start) return null
        val stops = Offset(range.start, range.endInclusive)
        if (controlsFadeStops != stops) {
            controlsFadeStops = stops
            controlsFade = lyricControlsFadeBrush(height) { range }
        }
        return controlsFade
    }

    @RequiresApi(36)
    @Composable
    fun Overlay(
        modifier: Modifier,
        topFadeHeight: Dp,
        bottomFadeHeight: Dp,
        controlsFadeRange: (Float) -> ClosedFloatingPointRange<Float>?,
        isVisible: () -> Boolean,
    ) {
        val paint = remember { Paint().apply { xfermode = PlayerLyricHdrApi36.accentXfermode } }
        Canvas(modifier.onGloballyPositioned {
            overlay = it
            geometryChanged()
        }) {
            if (!isVisible() || masks.isEmpty() || !drawContext.canvas.nativeCanvas.isHardwareAccelerated) {
                return@Canvas
            }
            @Suppress("UNUSED_VARIABLE")
            val version = geometryVersion.intValue
            if (masks.keys.none { it.coordinates?.isAttached == true && it.isActive() }) return@Canvas

            val viewport = viewport?.takeIf { it.isAttached } ?: return@Canvas
            val overlay = overlay?.takeIf { it.isAttached } ?: return@Canvas
            val clippedViewport = overlay.localBoundingBoxOf(viewport, clipBounds = true)
            if (clippedViewport.isEmpty) return@Canvas
            val viewportOrigin = overlay.localPositionOf(viewport, Offset.Zero)
            val viewportSize = viewport.size.toSize()
            val edgeFade = edgeFade(viewportSize.height, topFadeHeight.toPx(), bottomFadeHeight.toPx())
            val controlsFade = controlsFade(viewportSize.height, controlsFadeRange(viewportSize.height))
            for (mask in masks.keys) {
                val coordinates = mask.coordinates?.takeIf { it.isAttached } ?: continue
                if (!mask.isActive()) continue
                transform.reset()
                overlay.transformFrom(coordinates, transform)
                val bounds = transform.map(mask.bounds).intersect(clippedViewport)
                if (bounds.isEmpty) continue
                clipRect(bounds.left, bounds.top, bounds.right, bounds.bottom) {
                    val canvas = drawContext.canvas.nativeCanvas
                    val checkpoint = canvas.saveLayer(bounds.left, bounds.top, bounds.right, bounds.bottom, paint)
                    try {
                        withTransform({ transform(this@PlayerLyricHdrAccent.transform) }, mask.draw)
                        translate(viewportOrigin.x, viewportOrigin.y) {
                            edgeFade?.let { drawRect(it, size = viewportSize, blendMode = BlendMode.DstIn) }
                            controlsFade?.let { drawRect(it, size = viewportSize, blendMode = BlendMode.DstIn) }
                        }
                    } finally {
                        canvas.restoreToCount(checkpoint)
                    }
                }
            }
        }
    }
}

@RequiresApi(36)
internal object PlayerLyricHdrApi36 {
    val accentXfermode = RuntimeXfermode(
        """
        half4 main(half4 mask, half4 destination) {
            if (mask.a <= 0.0 || destination.a <= 0.0) return destination;
            half3 rgb = toLinearSrgb(destination.rgb / destination.a);
            half3 gain = min(half3($LyricAccentGain * mask.a), max(half3($LyricAccentPeak) - rgb, 0.0));
            if (gain.r + gain.g + gain.b <= 0.0) return destination;
            return half4(fromLinearSrgb(rgb + gain) * destination.a, destination.a);
        }
        """.trimIndent(),
    )
}
