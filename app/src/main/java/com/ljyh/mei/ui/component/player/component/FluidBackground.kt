package com.ljyh.mei.ui.component.player.component

import android.os.Build
import androidx.annotation.RequiresApi
import io.github.neoruaa.glasmio.glass.render.isRuntimeShaderSupported
import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import io.github.neoruaa.glasmio.glass.render.backdrops.LayerBackdrop
import io.github.neoruaa.glasmio.glass.render.backdrops.layerBackdrop
import com.ljyh.mei.constants.MeshFlowSpeedKey
import com.ljyh.mei.constants.MeshLowFreqVolumeKey
import com.ljyh.mei.constants.MeshPlayingKey
import com.ljyh.mei.constants.MeshRenderScaleKey
import com.ljyh.mei.constants.MeshStaticModeKey
import com.ljyh.mei.constants.MeshSubdivisionKey
import com.ljyh.mei.playback.PlaybackBeatMeter
import com.ljyh.mei.playback.PlaybackSpectrum
import com.ljyh.mei.source.api.TrackRef
import com.ljyh.mei.source.script.sourceTrackArtwork
import com.ljyh.mei.ui.component.player.LocalAutoMixUpcomingArtwork
import com.ljyh.mei.ui.component.player.LocalAutoMixVisuals
import com.ljyh.mei.ui.component.player.LocalPlayerBackgroundReadyChanged
import com.ljyh.mei.ui.component.player.component.mesh.CompactMeshDrawCount
import com.ljyh.mei.ui.component.player.component.mesh.DefaultMeshFlowSpeed
import com.ljyh.mei.ui.component.player.component.mesh.MeshAlbum
import com.ljyh.mei.ui.component.player.component.mesh.MeshGradientRenderer
import com.ljyh.mei.ui.component.player.component.mesh.PlayerMeshGeometry
import com.ljyh.mei.ui.component.player.component.mesh.WideMeshVariantCount
import com.ljyh.mei.ui.component.player.component.mesh.compactMeshVariantForDraw
import com.ljyh.mei.ui.component.player.component.mesh.runMeshFrames
import com.ljyh.mei.ui.component.sheet.LocalPlayerSheet
import com.ljyh.mei.ui.component.sheet.playerBackgroundAlpha
import com.ljyh.mei.ui.component.utils.LifecycleStartedEffect
import io.github.neoruaa.glasmio.glass.trackBackdropPosition
import com.ljyh.mei.utils.rememberPreference
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun FluidBackground(
    imageUrl: String?,
    beatMeter: PlaybackBeatMeter,
    isPlaying: Boolean = true,
    alpha: Float = 1f,
    backdrop: LayerBackdrop,
    modifier: Modifier = Modifier,
    trackRef: TrackRef? = null,
    behindLyrics: Boolean = false,
) {
    if (isRuntimeShaderSupported()) {
        MeshBackground(imageUrl, beatMeter, isPlaying, alpha, backdrop, modifier, trackRef, behindLyrics)
        return
    }
    val sheet = LocalPlayerSheet.current
    val onBackgroundReadyChanged by rememberUpdatedState(LocalPlayerBackgroundReadyChanged.current)
    DisposableEffect(Unit) {
        onBackgroundReadyChanged(true)
        onDispose { onBackgroundReadyChanged(false) }
    }
    Box(
        modifier.fillMaxSize().graphicsLayer {
            compositingStrategy = CompositingStrategy.ModulateAlpha
            this.alpha = alpha.coerceIn(0f, 1f) * (sheet?.motionProgress?.let(::playerBackgroundAlpha) ?: 1f)
        },
    ) {
        AmbientBackground(
            imageUrl,
            Modifier.layerBackdrop(backdrop).trackBackdropPosition(backdrop),
            animated = false,
            trackRef = trackRef,
        )
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun MeshBackground(
    imageUrl: String?,
    beatMeter: PlaybackBeatMeter,
    isPlaying: Boolean,
    alpha: Float,
    backdrop: LayerBackdrop,
    modifier: Modifier,
    trackRef: TrackRef?,
    behindLyrics: Boolean,
) {
    val sheet = LocalPlayerSheet.current
    val onBackgroundReadyChanged by rememberUpdatedState(LocalPlayerBackgroundReadyChanged.current)
    val expanded by remember(sheet) {
        derivedStateOf { sheet == null || sheet.state.isExpanded }
    }
    val sheetVisible by remember(sheet) {
        derivedStateOf { sheet == null || sheet.state.isExpanded || sheet.isTransitioning }
    }
    val backgroundActive = alpha > 0.01f && sheetVisible
    val autoMix = LocalAutoMixVisuals.current
    val upcomingArtwork = autoMix?.incoming ?: LocalAutoMixUpcomingArtwork.current
    val latestAutoMix by rememberUpdatedState(autoMix)
    val (flowSpeed) = rememberPreference(MeshFlowSpeedKey, defaultValue = DefaultMeshFlowSpeed)
    val (renderScale) = rememberPreference(MeshRenderScaleKey, defaultValue = 0.75f)
    val (staticMode) = rememberPreference(MeshStaticModeKey, defaultValue = false)
    val (meshPlaying) = rememberPreference(MeshPlayingKey, defaultValue = true)
    val (sensitivity) = rememberPreference(MeshLowFreqVolumeKey, defaultValue = 0.1f)
    val (subdivision) = rememberPreference(MeshSubdivisionKey, defaultValue = 50)
    val clampedSubdivision = subdivision.coerceIn(4, 50)
    val lyricsPinch = remember { Animatable(if (behindLyrics) 1f else 0f) }
    val renderer = remember {
        MeshGradientRenderer().apply { lyricsPinchProgress = lyricsPinch.value }
    }
    // Choose each orientation's field once so all covers share it through a transition.
    val (compactVariant, wideVariant) = remember(renderer) {
        compactMeshVariantForDraw(Random.nextInt(CompactMeshDrawCount)) to Random.nextInt(WideMeshVariantCount)
    }
    val meshGeometry = remember(clampedSubdivision, compactVariant, wideVariant) {
        lazy { PlayerMeshGeometry(clampedSubdivision, compactVariant, wideVariant) }
    }
    val meshLayer = rememberGraphicsLayer()
    val frameVersion = remember { mutableIntStateOf(0) }
    val hardwareAccelerated = LocalView.current.isHardwareAccelerated
    val reuseMeshCapture = sheet != null && hardwareAccelerated
    val shouldAnimate = !meshPlaying || isPlaying
    val audioReactive = backgroundActive && expanded && isPlaying && !staticMode && sensitivity > 0f
    // Regular cover view clamps zoom intensity to 0.2 (<0.6% scale), so only lyrics view needs 60 Hz.
    val fastAudioFrames = audioReactive && behindLyrics
    val zoomIntensity by rememberUpdatedState(if (behindLyrics) 1f else .2f)

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && hardwareAccelerated) {
        LifecycleStartedEffect(renderer) {
            renderer.prepareRetainedMeshes { frameVersion.intValue++ }
        }
    }

    // Each renderer owns its gray album's mutable shader. Prepare it once, off main,
    // so loading and failed artwork use the same mesh pipeline as real covers.
    val placeholderAlbum by produceState<MeshAlbum?>(null, meshGeometry) {
        value = withContext(Dispatchers.Default) {
            val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply {
                eraseColor(0xFF808080.toInt())
            }
            try {
                MeshGradientRenderer.prepareAlbum(bitmap, meshGeometry.value)
            } finally {
                bitmap.recycle()
            }
        }
    }

    val outgoingUrl = imageUrl?.takeIf(String::isNotBlank)
    val incomingUrl = autoMix?.incoming?.coverUrl?.takeIf(String::isNotBlank)
    val prewarmUrl = upcomingArtwork?.coverUrl
        ?.takeIf { backgroundActive && it.isNotBlank() && it != outgoingUrl }
    // Keys retain the prepared incoming texture when it becomes the current cover.
    val loadedAlbums = listOfNotNull(outgoingUrl, prewarmUrl).associateWith { url ->
        val artworkRef = if (url == outgoingUrl) trackRef else upcomingArtwork?.trackRef
        key(url) { rememberMeshAlbum(url, artworkRef, meshGeometry) }
    }
    val loadedAlbum = outgoingUrl?.let(loadedAlbums::get)
    val album = loadedAlbum?.album ?: placeholderAlbum.takeIf {
        outgoingUrl == null || loadedAlbum != null || !renderer.hasAlbum
    }
    val autoMixAlbum = if (autoMix == null || incomingUrl == outgoingUrl) {
        null
    } else if (incomingUrl == null) {
        placeholderAlbum
    } else {
        loadedAlbums[incomingUrl]?.let { it.album ?: placeholderAlbum }
    }

    LaunchedEffect(renderer, flowSpeed, staticMode, shouldAnimate) {
        renderer.flowSpeed = flowSpeed
        renderer.staticMode = staticMode
        renderer.isPlaying = shouldAnimate
        frameVersion.intValue++
    }

    LifecycleStartedEffect(renderer, behindLyrics, backgroundActive) {
        val target = if (behindLyrics) 1f else 0f
        if (!backgroundActive) {
            if (lyricsPinch.value != target || renderer.lyricsPinchProgress != target) {
                lyricsPinch.snapTo(target)
                renderer.lyricsPinchProgress = target
                frameVersion.intValue++
            }
            return@LifecycleStartedEffect
        }
        val distance = abs(target - lyricsPinch.value)
        if (distance > 0f) {
            // Page changes also update the captured backdrop when the flow clock is stopped.
            // Animate the linear progress so reversing a transition also retraces its easing,
            // paced at <=60 Hz so 120/144 Hz panels do not double backdrop/glass GPU cost mid-transition.
            val duration = (400 * distance).roundToInt().coerceAtLeast(1)
            var lastEmitNanos = Long.MIN_VALUE
            lyricsPinch.animateTo(target, tween(durationMillis = duration, easing = LinearEasing)) {
                val now = System.nanoTime()
                if (value == target || lastEmitNanos == Long.MIN_VALUE || now - lastEmitNanos >= 15_000_000L) {
                    lastEmitNanos = now
                    renderer.lyricsPinchProgress = EaseInOut.transform(value)
                    frameVersion.intValue++
                }
            }
        }
    }

    LaunchedEffect(renderer, album, autoMixAlbum) {
        album?.let(renderer::setAlbum)
        renderer.setAutoMixAlbum(autoMixAlbum)
        renderer.setAutoMixProgress(latestAutoMix?.backgroundProgress() ?: 0f)
        frameVersion.intValue++
        // Publish readiness after a complete mesh is available, including the placeholder.
        onBackgroundReadyChanged(renderer.hasAlbum)
    }

    LifecycleStartedEffect(renderer, beatMeter, audioReactive, sensitivity) {
        renderer.audioStrength = if (audioReactive) (sensitivity * 2f).coerceIn(0f, 1f) else 0f
        try {
            if (audioReactive) beatMeter.observeSpectrum { zoomIntensity }.collect { renderer.spectrum = it }
        } finally {
            renderer.spectrum = PlaybackSpectrum.Zero
            renderer.audioStrength = 0f
            frameVersion.intValue++
        }
    }

    LifecycleStartedEffect(
        renderer, backgroundActive, album, autoMixAlbum, staticMode, isPlaying, shouldAnimate, fastAudioFrames,
    ) {
        // Never include elapsed hidden/stopped time in flow or crossfade progression.
        renderer.resetFrameClock()
        if (!backgroundActive) return@LifecycleStartedEffect
        runMeshFrames(
            intervalNanos = if (fastAudioFrames) 16_666_667L else 33_333_333L,
            needsAnimation = { renderer.needsAnimation || (autoMixAlbum != null && isPlaying) },
        ) { now ->
            val animatedMesh = renderer.needsAnimation
            renderer.advanceFrame(now)
            val mixChanged = renderer.setAutoMixProgress(latestAutoMix?.backgroundProgress() ?: 0f)
            if (animatedMesh || mixChanged) frameVersion.intValue++
        }
    }

    DisposableEffect(Unit) {
        onDispose { onBackgroundReadyChanged(false) }
    }

    Box(
        modifier
            .fillMaxSize()
            // Presentation fades outside the recording so glass samples the full mesh.
            .graphicsLayer {
                // The mesh renderer already flattens album crossfades into one texture.
                compositingStrategy = CompositingStrategy.ModulateAlpha
                this.alpha = alpha.coerceIn(0f, 1f) *
                    (sheet?.motionProgress?.let(::playerBackgroundAlpha) ?: 1f)
            },
    ) {
        Box(
            Modifier
                .fillMaxSize()
                // Replay the same mesh layer that was captured, through the existing
                // backdrop owner so both same-window and dialog consumers stay live.
                .layerBackdrop(backdrop, reuseContent = reuseMeshCapture)
                .trackBackdropPosition(backdrop, frameVersion)
                .drawBehind {
                    frameVersion.intValue
                    if (renderer.hasAlbum) {
                        renderer.draw(this, meshLayer, renderScale)
                    } else {
                        // Match the neutral cover after the texture processor's 0.75 brightness
                        // while its first shader is prepared, without exposing the page below.
                        drawRect(Color(0xFF606060))
                    }
                },
        )
    }
}

private class LoadedMeshAlbum(val album: MeshAlbum?)

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun rememberMeshAlbum(
    imageUrl: String,
    trackRef: TrackRef?,
    geometry: Lazy<PlayerMeshGeometry>,
): LoadedMeshAlbum? {
    val context = LocalContext.current
    val loaded by produceState<LoadedMeshAlbum?>(null, imageUrl, geometry) {
        val result = context.imageLoader.execute(
            ImageRequest.Builder(context)
                .data(imageUrl)
                .sourceTrackArtwork(trackRef)
                .size(256)
                .allowHardware(false)
                .build(),
        )
        val album = if (result is SuccessResult) {
            withContext(Dispatchers.Default) {
                MeshGradientRenderer.prepareAlbum(result.image.toBitmap(), geometry.value)
            }
        } else null
        value = LoadedMeshAlbum(album)
    }
    return loaded
}
