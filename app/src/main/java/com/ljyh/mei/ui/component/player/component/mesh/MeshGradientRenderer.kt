package com.ljyh.mei.ui.component.player.component.mesh

import android.os.Build
import androidx.annotation.RequiresApi
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color as BitmapColor
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.graphics.Shader
import androidx.annotation.MainThread
import androidx.annotation.WorkerThread
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.IntSize
import com.ljyh.mei.playback.PlaybackSpectrum
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin

/** Prepared on a worker, then owned by one renderer, including its mutable shader. */
internal class MeshAlbum internal constructor(
    internal val bitmap: Bitmap,
    internal val source: MeshTextures,
    internal val geometry: PlayerMeshGeometry,
    internal val shader: RuntimeShader,
)

internal class MeshTextures(
    internal val regular: MeshTexture,
    internal val lyrics: MeshTexture,
) {
    internal fun endpoint(lyricsMix: Float): MeshTexture = if (lyricsMix >= 1f) lyrics else regular

    internal fun sample(index: Int, progress: Float): Float =
        regular.samples[index] * (1f - progress) + lyrics.samples[index] * progress
}

/** Premultiplied sRGB texels, with a bound sampler for an original cover or cached snapshot. */
internal class MeshTexture(
    internal val samples: FloatArray,
    sampler: BitmapShader? = null,
) {
    internal var sampler: BitmapShader? = sampler
        private set

    internal fun requireSampler(reference: Bitmap, pixelsScratch: IntArray): BitmapShader {
        sampler?.let { return it }
        return createSamplerFromSamples(samples, reference.width, reference.height, pixelsScratch).also {
            sampler = it
        }
    }
}

private fun textureSampler(bitmap: Bitmap): BitmapShader =
    BitmapShader(bitmap, Shader.TileMode.MIRROR, Shader.TileMode.MIRROR).apply {
        setFilterMode(BitmapShader.FILTER_MODE_LINEAR)
    }

private fun createSamplerFromSamples(
    samples: FloatArray,
    width: Int,
    height: Int,
    pixels: IntArray,
): BitmapShader {
    for (i in pixels.indices) {
        val index = i * 4
        val alpha = samples[index + 3]
        pixels[i] = if (alpha < .5f) 0 else {
            val unpremultiply = 255f / alpha
            BitmapColor.argb(
                alpha.roundToInt(),
                (samples[index] * unpremultiply).roundToInt().coerceIn(0, 255),
                (samples[index + 1] * unpremultiply).roundToInt().coerceIn(0, 255),
                (samples[index + 2] * unpremultiply).roundToInt().coerceIn(0, 255),
            )
        }
    }
    // HWUI can replay a prior display list. Give each immutable texture its own pixels.
    return textureSampler(Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888))
}

/** Main-thread renderer shared by the player and its glass backdrop. */
@MainThread
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal class MeshGradientRenderer {
    /** Two-triangle quad matching the unpinched overscan grid with zero interior edges. */
    private class RestingMeshQuad(val size: IntSize) {
        val positions: FloatArray
        val textureCoordinates: FloatArray

        init {
            val width = size.width.toFloat()
            val height = size.height.toFloat()
            val padX = width * MeshRestOverscan * .5f
            val padY = height * MeshRestOverscan * .5f
            val left = -padX
            val top = -padY
            val right = width + padX
            val bottom = height + padY
            positions = floatArrayOf(
                left, top,
                right, top,
                left, bottom,
                right, bottom,
            )
            val textureSide = maxOf(width, height)
            val halfTexW = width / textureSide * .5f
            val halfTexH = height / textureSide * .5f
            val texLeft = .5f - halfTexW
            val texRight = .5f + halfTexW
            val texTop = .5f - halfTexH
            val texBottom = .5f + halfTexH
            textureCoordinates = floatArrayOf(
                texLeft, texTop,
                texRight, texTop,
                texLeft, texBottom,
                texRight, texBottom,
            )
        }

        companion object {
            val Indices = shortArrayOf(0, 1, 2, 1, 3, 2)
        }
    }

    private class DisplayMesh(val geometry: PlayerMeshGeometry) {
        private var canvasViewport: PlayerMeshViewport? = null
        private var cachedRestingQuad: RestingMeshQuad? = null
        var retainedSize = IntSize.Zero
        var retainedMesh: RetainedPlayerMesh? = null

        fun restingQuad(size: IntSize): RestingMeshQuad = cachedRestingQuad?.takeIf { it.size == size }
            ?: RestingMeshQuad(size).also { cachedRestingQuad = it }

        fun viewport(size: IntSize): PlayerMeshViewport = canvasViewport?.takeIf { it.size == size }
            ?: PlayerMeshViewport(geometry, size).also { canvasViewport = it }
    }

    private class SourceBlend(
        outgoing: MeshTextures?,
        private val destination: MeshTextures,
        incoming: MeshTextures?,
        progress: Float,
        private val incomingMix: Float,
    ) {
        private val previous = outgoing ?: destination
        private val next = incoming ?: destination
        private val destinationMix = (1f - cos(progress * Math.PI.toFloat())) * .5f
        private val single = when {
            incomingMix == 1f -> next
            incomingMix == 0f && outgoing == null -> destination
            incomingMix == 0f && progress == 0f -> previous
            else -> null
        }

        fun snapshot(): MeshTextures = single ?: MeshTextures(texture(0f), texture(1f))

        fun texture(lyricsMix: Float, scratch: FloatArray? = null): MeshTexture {
            if (single != null) {
                if (lyricsMix == 0f) return single.regular
                if (lyricsMix == 1f) return single.lyrics
            }
            val samples = scratch ?: FloatArray(destination.regular.samples.size)
            val oldWeight = (1f - destinationMix) * (1f - incomingMix)
            val destinationWeight = destinationMix * (1f - incomingMix)
            for (i in samples.indices) {
                samples[i] = previous.sample(i, lyricsMix) * oldWeight +
                    destination.sample(i, lyricsMix) * destinationWeight +
                    next.sample(i, lyricsMix) * incomingMix
            }
            return MeshTexture(samples)
        }
    }

    private var currentAlbum: MeshAlbum? = null
    private var outgoingTexture: MeshTextures? = null
    private var albumProgress = 1f
    private var autoMixAlbum: MeshAlbum? = null
    private var autoMixProgress = 0f
    private var displayMesh: DisplayMesh? = null
    private val meshPaint = Paint().apply { blendMode = BlendMode.SRC }
    private var sourceRevision = 0L
    private var recordedSourceRevision = -1L
    private var boundShader: RuntimeShader? = null
    private var boundPrimarySampler: BitmapShader? = null
    private var boundSecondarySampler: BitmapShader? = null
    private var boundTextureMix = Float.NaN
    private var blendSamples = FloatArray(0)
    private var blendPixels = IntArray(0)
    private var accumulatedPlayingNanos = 0L
    private var lastFrameNanos: Long? = null
    private var revision = 0L
    private var recordedRevision = -1L
    private var recordedLayer: GraphicsLayer? = null
    private var recordedSize = IntSize.Zero
    private var recordedRetainedAllowed = false
    private val meshPreparation = Channel<Unit>(Channel.CONFLATED)
    private var retainedTarget = IntSize.Zero

    var flowSpeed: Float = DefaultMeshFlowSpeed
        set(value) {
            if (field == value) return
            field = value
            revision++
        }

    var lyricsPinchProgress: Float = 0f
        set(value) {
            if (field == value) return
            field = value
            invalidateSource()
        }

    var staticMode: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            resetFrameClock()
            revision++
        }

    var isPlaying: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            resetFrameClock()
        }

    var audioStrength: Float = 0f
        set(value) {
            if (field == value) return
            field = value
            // Paused/static layers may not receive another animation tick.
            if (value <= 0f) updateAudioResponse(0f)
            revision++
        }

    var spectrum: PlaybackSpectrum = PlaybackSpectrum.Zero

    val hasAlbum: Boolean get() = currentAlbum != null

    val needsAnimation: Boolean
        get() = hasAlbum && ((!staticMode && isPlaying) || outgoingTexture != null)

    private var smoothedLow = 0f
    private var smoothedHigh = 0f
    private var smoothedZoom = 0f
    private var audioScale = 1f
    private var audioContrast = 1f
    private var audioSaturation = 1f

    fun setAlbum(album: MeshAlbum) {
        if (currentAlbum === album) return
        val promotingAutoMix = autoMixAlbum === album
        // Capture current source weights at full precision on interrupted fades.
        // An immutable snapshot also bounds the number of textures retained by repeated switches.
        val previous = currentAlbum?.let {
            sourceBlend(it.source, includeAutoMix = promotingAutoMix).snapshot()
        }
        currentAlbum = album
        outgoingTexture = previous?.takeUnless { it === album.source }
        albumProgress = if (outgoingTexture == null) 1f else 0f
        if (promotingAutoMix) {
            autoMixAlbum = null
            autoMixProgress = 0f
        }
        // A background keeps its field across artwork changes. Only a subdivision
        // change requires a different tessellation and a new retained mesh.
        if (displayMesh?.geometry?.cells != album.geometry.cells) {
            displayMesh = DisplayMesh(album.geometry)
            requestRetainedMeshes()
        }
        invalidateSource()
    }

    fun setAutoMixAlbum(album: MeshAlbum?) {
        val target = album?.takeUnless { it === currentAlbum }
        if (autoMixAlbum === target) return
        autoMixAlbum = target
        autoMixProgress = 0f
        invalidateSource()
    }

    fun setAutoMixProgress(progress: Float): Boolean {
        if (autoMixAlbum == null) return false
        val alpha = progress.coerceIn(0f, 1f)
        if (autoMixProgress == alpha) return false
        autoMixProgress = alpha
        invalidateSource()
        return true
    }

    /** Advance once per frame, even when the resulting layer is drawn more than once. */
    fun advanceFrame(frameNanos: Long) {
        val previousFrame = lastFrameNanos
        lastFrameNanos = frameNanos
        val elapsedNanos = if (previousFrame == null) 0L else (frameNanos - previousFrame).coerceAtLeast(0L)
        if (!needsAnimation) return
        if (isPlaying && !staticMode) accumulatedPlayingNanos += elapsedNanos
        val elapsedSeconds = elapsedNanos / 1e9f
        updateAudioResponse(elapsedSeconds)

        if (outgoingTexture != null && elapsedSeconds > 0f) {
            val fadeDelta = elapsedSeconds.coerceIn(0f, .1f) * 1.5f
            albumProgress = (albumProgress + fadeDelta).coerceAtMost(1f)
            if (albumProgress == 1f) outgoingTexture = null
            sourceRevision++
        }
        revision++
    }

    private fun invalidateSource() {
        sourceRevision++
        revision++
    }

    fun resetFrameClock() {
        lastFrameNanos = null
    }

    private fun requestRetainedMeshes() {
        if (retainedTarget != IntSize.Zero) meshPreparation.trySend(Unit)
    }

    /** The caller keeps one collector for the lifetime of this renderer's visible window. */
    suspend fun prepareRetainedMeshes(onReady: () -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        requestRetainedMeshes()
        while (true) {
            meshPreparation.receive()
            val state = displayMesh ?: continue
            val size = retainedTarget
            if (size == IntSize.Zero || state.retainedSize == size) continue
            val prepared = withContext(Dispatchers.Default) {
                RetainedPlayerMesh.prepare(PlayerMeshViewport(state.geometry, size))
            }
            if (retainedTarget != size || displayMesh !== state) continue
            state.retainedSize = size
            state.retainedMesh = prepared
            if (prepared != null) {
                revision++
                onReady()
            }
        }
    }

    fun draw(scope: DrawScope, layer: GraphicsLayer, renderScale: Float) = with(scope) {
        if (size.width <= 0f || size.height <= 0f) return@with
        // Keep the same bounded working resolution as the former Surface buffer.
        val scale = minOf(
            renderScale.coerceIn(.1f, 1f),
            720f / minOf(size.width, size.height),
            1440f / maxOf(size.width, size.height),
        )
        val bufferSize = IntSize(
            (size.width * scale).toInt().coerceAtLeast(1),
            (size.height * scale).toInt().coerceAtLeast(1),
        )
        val allowRetained = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            drawContext.canvas.nativeCanvas.isHardwareAccelerated
        if (allowRetained && retainedTarget != bufferSize) {
            retainedTarget = bufferSize
            requestRetainedMeshes()
        }
        if (recordedLayer !== layer || recordedSize != bufferSize || recordedRevision != revision ||
            recordedRetainedAllowed != allowRetained
        ) {
            layer.clip = true
            layer.compositingStrategy = CompositingStrategy.Offscreen
            layer.record(bufferSize) {
                drawRect(Color.Black)
                drawIntoCanvas { composeCanvas -> drawMesh(composeCanvas.nativeCanvas, bufferSize, allowRetained) }
            }
            recordedLayer = layer
            recordedSize = bufferSize
            recordedRevision = revision
            recordedRetainedAllowed = allowRetained
        }
        withTransform({
            scale(size.width / bufferSize.width, size.height / bufferSize.height, Offset.Zero)
        }) { drawLayer(layer) }
    }

    private fun sourceBlend(destination: MeshTextures, includeAutoMix: Boolean): SourceBlend =
        SourceBlend(
            outgoing = outgoingTexture,
            destination = destination,
            incoming = autoMixAlbum?.source?.takeIf { includeAutoMix },
            progress = albumProgress,
            incomingMix = if (includeAutoMix) autoMixProgress else 0f,
        )

    private fun ensureBlendPixels(reference: Bitmap): IntArray {
        val pixelCount = reference.width * reference.height
        if (blendPixels.size != pixelCount) blendPixels = IntArray(pixelCount)
        return blendPixels
    }

    private fun applySourceShaders(
        shader: RuntimeShader,
        primary: BitmapShader,
        secondary: BitmapShader,
        mix: Float,
    ) {
        val shaderChanged = boundShader !== shader
        if (shaderChanged || boundPrimarySampler !== primary) {
            shader.setInputShader("albumTexture", primary)
            boundPrimarySampler = primary
        }
        if (shaderChanged || boundSecondarySampler !== secondary) {
            shader.setInputShader("blendTexture", secondary)
            boundSecondarySampler = secondary
        }
        if (shaderChanged || boundTextureMix != mix) {
            shader.setFloatUniform("textureMix", mix)
            boundTextureMix = mix
        }
        boundShader = shader
    }

    private fun updateAlbumSampler(album: MeshAlbum) {
        if (recordedSourceRevision == sourceRevision && boundShader === album.shader) return
        val shader = album.shader
        val pinch = lyricsPinchProgress
        val outgoing = outgoingTexture
        val destination = album.source
        val incoming = autoMixAlbum?.source
        val incomingMix = if (incoming != null) autoMixProgress else 0f
        val reference = album.bitmap

        // Blend two immutable samplers on the GPU for all common transitions:
        // cover<->lyrics pinch, song crossfade, and AutoMix — zero per-frame Bitmap uploads.
        val single = when {
            incomingMix == 1f && incoming != null -> incoming
            incomingMix == 0f && outgoing == null -> destination
            incomingMix == 0f && albumProgress == 0f && outgoing != null -> outgoing
            else -> null
        }

        if (single != null) {
            val pixels = ensureBlendPixels(reference)
            if (pinch == 0f) {
                val sampler = single.regular.requireSampler(reference, pixels)
                applySourceShaders(shader, sampler, sampler, 0f)
            } else if (pinch == 1f) {
                val sampler = single.lyrics.requireSampler(reference, pixels)
                applySourceShaders(shader, sampler, sampler, 0f)
            } else {
                val regularSampler = single.regular.requireSampler(reference, pixels)
                val lyricsSampler = single.lyrics.requireSampler(reference, pixels)
                applySourceShaders(shader, regularSampler, lyricsSampler, pinch)
            }
            recordedSourceRevision = sourceRevision
            return
        }

        if (pinch == 0f || pinch == 1f) {
            val pixels = ensureBlendPixels(reference)
            if (incomingMix == 0f && outgoing != null) {
                val destinationMix = (1f - cos(albumProgress * Math.PI.toFloat())) * .5f
                val primary = outgoing.endpoint(pinch).requireSampler(reference, pixels)
                val secondary = destination.endpoint(pinch).requireSampler(reference, pixels)
                applySourceShaders(shader, primary, secondary, destinationMix)
                recordedSourceRevision = sourceRevision
                return
            }
            if (outgoing == null && incoming != null) {
                val primary = destination.endpoint(pinch).requireSampler(reference, pixels)
                val secondary = incoming.endpoint(pinch).requireSampler(reference, pixels)
                applySourceShaders(shader, primary, secondary, incomingMix)
                recordedSourceRevision = sourceRevision
                return
            }
            if (albumProgress == 0f && outgoing != null && incoming != null) {
                val primary = outgoing.endpoint(pinch).requireSampler(reference, pixels)
                val secondary = incoming.endpoint(pinch).requireSampler(reference, pixels)
                applySourceShaders(shader, primary, secondary, incomingMix)
                recordedSourceRevision = sourceRevision
                return
            }
        }

        // Rare simultaneous compound transition (e.g., cover<->lyrics pinch mid-crossfade).
        val sampleCount = destination.regular.samples.size
        if (blendSamples.size != sampleCount) blendSamples = FloatArray(sampleCount)
        val source = sourceBlend(destination, includeAutoMix = true).texture(pinch, blendSamples)
        val pixels = ensureBlendPixels(reference)
        val sampler = source.sampler ?: createSamplerFromSamples(
            source.samples, reference.width, reference.height, pixels,
        )
        applySourceShaders(shader, sampler, sampler, 0f)
        recordedSourceRevision = sourceRevision
    }

    private fun drawMesh(canvas: Canvas, size: IntSize, allowRetained: Boolean) {
        val album = currentAlbum ?: return
        val state = displayMesh ?: return
        updateAlbumSampler(album)
        val shader = album.shader
        val time = meshMotionSeconds(accumulatedPlayingNanos, flowSpeed)
        val pinchMix = lyricsPinchProgress
        val warpMix = if (pinchMix == 0f) 0f else meshCreasePhase(accumulatedPlayingNanos, flowSpeed)
        updateArtworkUniforms(shader, time, pinchMix)
        meshPaint.shader = shader

        // Unpinched cover/queue views are an exact affine overscan of the viewport:
        // draw 2 triangles instead of 5,000 to avoid vertex shading and quad overdraw.
        if (pinchMix == 0f) {
            val quad = state.restingQuad(size)
            canvas.drawVertices(
                Canvas.VertexMode.TRIANGLES,
                quad.positions.size,
                quad.positions,
                0,
                quad.textureCoordinates,
                0,
                null,
                0,
                RestingMeshQuad.Indices,
                0,
                RestingMeshQuad.Indices.size,
                meshPaint,
            )
            return
        }

        val retained = allowRetained && canvas.isHardwareAccelerated
        val mesh = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            retained && state.retainedSize == size
        ) state.retainedMesh else null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && mesh != null) {
            mesh.draw(canvas, meshPaint, warpMix, pinchMix)
        } else {
            val viewport = state.viewport(size)
            val positions = viewport.positions(warpMix, pinchMix)
            canvas.drawVertices(
                Canvas.VertexMode.TRIANGLES,
                positions.size,
                positions,
                0,
                viewport.textureCoordinates,
                0,
                null,
                0,
                viewport.indices,
                0,
                viewport.indices.size,
                meshPaint,
            )
        }
    }

    private fun updateArtworkUniforms(shader: RuntimeShader, time: Double, lyrics: Float) {
        val primaryAngle = meshArtworkOrbitAngle(time, PrimaryArtworkOrbitBaseSeconds)
        val upperAngle = meshArtworkOrbitAngle(time, 70.0) + .64f
        val lowerAngle = primaryAngle + meshArtworkOrbitAngle(time, LowerArtworkOrbitBaseSeconds) - .41f
        val primaryCos = cos(primaryAngle)
        val primarySin = sin(primaryAngle)
        shader.setFloatUniform(
            "primaryAndUpperOrbits",
            primaryCos, primarySin, cos(upperAngle), sin(upperAngle),
        )
        // The lower layer spins locally while its sampling center travels with the primary layer.
        shader.setFloatUniform(
            "lowerOrbitAndAnchor",
            cos(lowerAngle), sin(lowerAngle),
            -.10f * primaryCos + .13f * primarySin, .10f * primarySin + .13f * primaryCos,
        )

        shader.setFloatUniform("toneAndZoom", audioContrast, audioSaturation * (1f - 0.12f * lyrics), 1f / audioScale)
    }

    private fun updateAudioResponse(seconds: Float) {
        val strength = audioStrength
        val target = spectrum
        if (strength <= 0f) {
            smoothedLow = 0f
            smoothedHigh = 0f
            smoothedZoom = 0f
        } else {
            val clamped = seconds.coerceIn(0f, .1f)
            val blend = 1f - exp(-clamped / .06f)
            smoothedLow += (target.low - smoothedLow) * blend
            smoothedHigh += (target.high - smoothedHigh) * blend
            val zoomBlend = 1f - exp(-clamped / .035f)
            smoothedZoom += (target.zoomEnergy - smoothedZoom) * zoomBlend
        }
        // The default sensitivity gives unity gain. Higher settings strengthen quiet beats
        // while keeping the same maximum expansion.
        val zoomEnergy = smoothedZoom
        audioScale = 1f + (.33f * zoomEnergy * zoomEnergy * (strength / .2f)).coerceIn(0f, .33f)
        audioContrast = 1f + smoothedLow * .076f * strength
        audioSaturation = 1f + smoothedHigh * .166f * strength
    }

    companion object {
        @WorkerThread
        fun prepareAlbum(bitmap: Bitmap, geometry: PlayerMeshGeometry): MeshAlbum {
            // Normalize loader-owned artwork to a CPU-readable ARGB texture.
            val source = if (bitmap.isRecycled) bitmap else checkNotNull(bitmap.copy(Bitmap.Config.ARGB_8888, false))
            val (processed, forLyrics) = try {
                AlbumTextureProcessor.processVariants(source)
            } finally {
                if (source !== bitmap && !source.isRecycled) source.recycle()
            }
            val regular = prepareTexture(processed)
            // RuntimeShader compiles synchronously. Complete compilation and texture
            // binding here, before the prepared album is handed to the main thread.
            val regularSampler = checkNotNull(regular.sampler)
            val shader = RuntimeShader(ShaderSource.MESH_SHADER).apply {
                setInputShader("albumTexture", regularSampler)
                setInputShader("blendTexture", regularSampler)
                setFloatUniform("textureMix", 0f)
                setFloatUniform("textureSize", processed.width.toFloat(), processed.height.toFloat())
            }
            return MeshAlbum(
                processed,
                MeshTextures(regular, prepareTexture(forLyrics)),
                geometry,
                shader,
            )
        }

        private fun prepareTexture(bitmap: Bitmap): MeshTexture {
            val width = bitmap.width
            val height = bitmap.height
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            val samples = FloatArray(pixels.size * 4)
            for (i in pixels.indices) {
                val pixel = pixels[i]
                val alpha = BitmapColor.alpha(pixel)
                val scale = alpha / 255f
                val index = i * 4
                samples[index] = BitmapColor.red(pixel) * scale
                samples[index + 1] = BitmapColor.green(pixel) * scale
                samples[index + 2] = BitmapColor.blue(pixel) * scale
                samples[index + 3] = alpha.toFloat()
            }
            return MeshTexture(samples, textureSampler(bitmap))
        }
    }
}
