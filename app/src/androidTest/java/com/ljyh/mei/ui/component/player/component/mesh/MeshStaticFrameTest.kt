package com.ljyh.mei.ui.component.player.component.mesh

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas.VertexMode
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.os.Build
import android.util.Log
import android.view.ViewGroup
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import io.github.neoruaa.glasmio.glass.render.backdrops.rememberLayerBackdrop
import io.github.neoruaa.glasmio.glass.render.backdrops.layerBackdrop
import com.ljyh.mei.playback.PlaybackBeatMeter
import com.ljyh.mei.MainActivity
import com.ljyh.mei.ui.component.player.LocalPlayerBackgroundReadyChanged
import com.ljyh.mei.ui.component.player.component.FluidBackground
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.concurrent.thread
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MeshStaticFrameTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Before
    fun replaceActivityContentWithTheRenderingFixture() {
        compose.runOnUiThread {
            val content = compose.activity.findViewById<ViewGroup>(android.R.id.content)
            repeat(content.childCount) { index ->
                (content.getChildAt(index) as? AbstractComposeView)?.disposeComposition()
            }
            content.removeAllViews()
        }
    }

    @Test
    fun modulatedMeshPresentationPreservesCrossfadesAndUnfadedBackdropSampling() {
        val firstAlbum = meshTestAlbum()
        val secondAlbum = meshTestAlbum(Color.rgb(40, 70, 210))
        val renderer = compose.runOnIdle {
            MeshGradientRenderer().apply {
                isPlaying = false
                setAlbum(firstAlbum)
                advanceFrame(StartNanos)
            }
        }
        val opacity = mutableFloatStateOf(1f)
        val backing = mutableStateOf(ComposeColor.Black)
        val redrawGeneration = mutableIntStateOf(0)
        compose.setContent {
            // Both presentation paths replay this exact renderer frame and flattened
            // texture. Drawing either side must never advance the album transition.
            val meshLayer = rememberGraphicsLayer()
            val backdrops = listOf(rememberLayerBackdrop(), rememberLayerBackdrop())
            val coordinates = remember { List(2) { mutableStateOf<LayoutCoordinates?>(null) } }
            Row {
                listOf(CompositingStrategy.Auto, CompositingStrategy.ModulateAlpha)
                    .forEachIndexed { index, strategy ->
                        Column {
                            Box(
                                Modifier.size(96.dp).background(backing.value)
                                    .testTag("meshPresentation$index"),
                            ) {
                                Box(Modifier.fillMaxSize().graphicsLayer {
                                    compositingStrategy = strategy
                                    alpha = opacity.floatValue
                                }) {
                                    Canvas(
                                        Modifier.fillMaxSize().layerBackdrop(backdrops[index])
                                            .onGloballyPositioned { coordinates[index].value = it },
                                    ) {
                                        assertTrue("Mesh presentation requires GPU rendering", drawContext.canvas.nativeCanvas.isHardwareAccelerated)
                                        redrawGeneration.intValue
                                        renderer.draw(this, meshLayer, 0.75f)
                                    }
                                }
                            }
                            Canvas(Modifier.size(96.dp).testTag("meshBackdrop$index")) {
                                redrawGeneration.intValue
                                val sourceCoordinates = coordinates[index].value ?: return@Canvas
                                // Sample the actual layerBackdrop recording at its source
                                // coordinates, independently of the outer presentation fade.
                                with(backdrops[index]) {
                                    drawBackdrop(this@Canvas, sourceCoordinates, null)
                                }
                            }
                        }
                    }
            }
        }
        fun capture(tag: String): IntArray {
            val image = compose.onNodeWithTag(tag).captureToImage()
            return IntArray(image.width * image.height).also { image.readPixels(it) }
        }
        fun assertPixels(label: String, expected: IntArray, actual: IntArray) {
            assertEquals("$label pixel count", expected.size, actual.size)
            var maximum = 0
            var total = 0L
            expected.indices.forEach { pixel ->
                val differences = intArrayOf(
                    abs(Color.red(expected[pixel]) - Color.red(actual[pixel])),
                    abs(Color.green(expected[pixel]) - Color.green(actual[pixel])),
                    abs(Color.blue(expected[pixel]) - Color.blue(actual[pixel])),
                    abs(Color.alpha(expected[pixel]) - Color.alpha(actual[pixel])),
                )
                differences.forEach { error ->
                    maximum = maxOf(maximum, error)
                    total += error
                }
            }
            val mean = total.toDouble() / (expected.size * 4)
            assertTrue("$label maximum RGBA error: $maximum", maximum <= 2)
            assertTrue("$label mean RGBA error: $mean", mean <= 1.0)
        }

        val restingSource = capture("meshBackdrop0")
        compose.runOnIdle {
            assertFalse("The settled artwork clock must be stopped", renderer.needsAnimation)
            renderer.lyricsPinchProgress = 1f
            redrawGeneration.intValue++
        }
        val lyricSource = capture("meshBackdrop0")
        assertTrue("Lyrics mode must deform the artwork with the playback clock stopped",
            lyricSource.indices.any { colorDistance(restingSource[it], lyricSource[it]) > 3 })
        assertPixels("Lyrics mode backdrop", lyricSource, capture("meshBackdrop1"))
        repeat(2) { output ->
            assertPixels("Lyrics mode presentation $output", lyricSource, capture("meshPresentation$output"))
        }
        compose.runOnIdle {
            assertFalse("Pinch must not start continuous flow", renderer.needsAnimation)
            renderer.lyricsPinchProgress = 0f
            redrawGeneration.intValue++
        }
        assertPixels("Returning from lyrics mode", restingSource, capture("meshBackdrop0"))

        var frameNanos = StartNanos
        var previousSource: IntArray? = null
        repeat(3) { frame ->
            compose.runOnIdle {
                opacity.floatValue = 1f
                if (frame == 1) renderer.setAlbum(secondAlbum)
                repeat(if (frame == 1) 3 else if (frame == 2) 5 else 0) {
                    frameNanos += FrameNanos
                    renderer.advanceFrame(frameNanos)
                }
                redrawGeneration.intValue++
            }
            compose.waitForIdle()
            val unfadedSource = capture("meshBackdrop0")
            assertTrue("Frame $frame must contain an opaque mesh source", unfadedSource.all { Color.alpha(it) == 255 })
            assertTrue("Frame $frame must contain album colors", unfadedSource.any {
                Color.red(it) > 24 || Color.green(it) > 24 || Color.blue(it) > 24
            })
            previousSource?.let { previous ->
                assertTrue("The real album crossfade must advance between frames", previous.indices.any {
                    colorDistance(previous[it], unfadedSource[it]) > 5
                })
            }
            previousSource = unfadedSource
            listOf(ComposeColor.Black, ComposeColor(0xFF74519B)).forEach { color ->
                listOf(0f, 0.15f, 0.5f, 1f).forEach { alpha ->
                    compose.runOnIdle {
                        backing.value = color
                        opacity.floatValue = alpha
                        redrawGeneration.intValue++
                    }
                    compose.waitForIdle()
                    val label = "Mesh frame=$frame, alpha=$alpha, backing=$color"
                    assertPixels(label, capture("meshPresentation0"), capture("meshPresentation1"))
                    repeat(2) { output ->
                        assertPixels("$label source=$output", unfadedSource, capture("meshBackdrop$output"))
                    }
                }
            }
        }
    }

    @Test
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    fun lyricsMeshMovesFixedUvInPortraitAndWideOnBothDrawPaths() {
        val subdivision = 20
        assertLocalizedFoldVariants(subdivision)
        val variant = mutableIntStateOf(0)
        val warp = mutableFloatStateOf(0f)
        val pinch = mutableFloatStateOf(0f)
        val viewports = listOf(72.dp to 108.dp, 108.dp to 72.dp)
        compose.setContent {
            val density = LocalDensity.current
            val geometry = remember(variant.intValue) {
                PlayerMeshGeometry(
                    subdivision,
                    compactVariant = variant.intValue % CompactMeshVariantCount,
                    wideVariant = variant.intValue,
                )
            }
            val paint = remember {
                Paint().apply {
                    blendMode = BlendMode.SRC
                    // An unchanging UV ramp isolates vertex motion from artwork rotation.
                    shader = RuntimeShader("half4 main(float2 uv) { return half4(uv, 0.5, 1.0); }")
                }
            }
            Column {
                viewports.forEachIndexed { index, (width, height) ->
                    val size = with(density) { IntSize(width.roundToPx(), height.roundToPx()) }
                    val viewport = remember(size, geometry) { PlayerMeshViewport(geometry, size) }
                    val retained = remember(viewport) { checkNotNull(RetainedPlayerMesh.prepare(viewport)) }
                    Row {
                        listOf(false, true).forEach { useRetained ->
                            Canvas(Modifier.size(width, height).clipToBounds().testTag("warp-$index-$useRetained")) {
                                val canvas = drawContext.canvas.nativeCanvas
                                assertTrue("The mesh comparison requires a hardware canvas", canvas.isHardwareAccelerated)
                                if (useRetained) {
                                    retained.draw(canvas, paint, warp.floatValue, pinch.floatValue)
                                } else {
                                    val positions = viewport.positions(warp.floatValue, pinch.floatValue)
                                    canvas.drawVertices(
                                        VertexMode.TRIANGLES,
                                        positions.size,
                                        positions,
                                        0,
                                        viewport.textureCoordinates,
                                        0,
                                        null,
                                        0,
                                        geometry.indices,
                                        0,
                                        geometry.indices.size,
                                        paint,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        fun capture(geometryIndex: Int, retained: Boolean): IntArray {
            val image = compose.onNodeWithTag("warp-$geometryIndex-$retained").captureToImage()
            return IntArray(image.width * image.height).also { image.readPixels(it) }
        }

        fun captureMatchingDrawPaths(pinchMix: Float, warpMix: Float): List<IntArray> {
            compose.runOnIdle {
                pinch.floatValue = pinchMix
                warp.floatValue = warpMix
            }
            return viewports.indices.map { index ->
                val fallback = capture(index, retained = false)
                val retained = capture(index, retained = true)
                val error = fallback.indices.maxOf { colorDistance(fallback[it], retained[it]) }
                val label = "Variant ${variant.intValue}, viewport $index at ($pinchMix, $warpMix)"
                assertTrue("$label path difference: $error", error <= 3)
                assertTrue("$label must be fully covered",
                    retained.all { Color.blue(it) in 125..131 })
                retained
            }
        }

        val flat = captureMatchingDrawPaths(pinchMix = 0f, warpMix = 0f)
        repeat(WideMeshVariantCount) { id ->
            compose.runOnIdle { variant.intValue = id }
            captureMatchingDrawPaths(pinchMix = .5f, warpMix = .5f)
            captureMatchingDrawPaths(pinchMix = 1f, warpMix = .5f)
            val first = captureMatchingDrawPaths(pinchMix = 1f, warpMix = 0f)
            val second = captureMatchingDrawPaths(pinchMix = 1f, warpMix = 1f)
            val flattened = captureMatchingDrawPaths(pinchMix = 0f, warpMix = 1f)
            viewports.indices.forEach { index ->
                val changed = first[index].indices.count { pixel ->
                    colorDistance(first[index][pixel], second[index][pixel]) > 5
                }
                assertTrue("Variant $id, viewport $index must move the fixed UV ramp",
                    changed > first[index].size / 20)
                assertArrayEquals("Variant $id, viewport $index must return to the resting grid",
                    flat[index], flattened[index])
            }
        }
    }

    private fun assertLocalizedFoldVariants(subdivision: Int) {
        assertEquals("The compact draw must give family 2 two slots",
            listOf(0, 1, 2, 2, 3), (0..4).map(::compactMeshVariantForDraw))
        for (size in listOf(IntSize(240, 480), IntSize(480, 240))) {
            val wide = size.width > size.height
            val count = if (wide) WideMeshVariantCount else CompactMeshVariantCount
            val sampled = mutableListOf<PlayerMeshViewport>()
            for (variant in 0 until count) {
                val geometry = if (wide) {
                    PlayerMeshGeometry(subdivision, wideVariant = variant)
                } else {
                    PlayerMeshGeometry(subdivision, compactVariant = variant)
                }
                val viewport = PlayerMeshViewport(geometry, size)
                assertLocalizedFoldGeometry(geometry, viewport, "$size $variant")
                if (wide) {
                    sampled.firstOrNull()?.let { baseline ->
                        assertArrayEquals("Wide family $variant must share the first field",
                            baseline.firstOffset, viewport.firstOffset, 0f)
                    }
                }
                sampled.forEachIndexed { previousVariant, previous ->
                    assertDistinctFoldPaths(viewport, previous, geometry.cells, "$size $variant versus $previousVariant")
                }
                sampled += viewport
            }
        }
    }

    private fun assertDistinctFoldPaths(
        viewport: PlayerMeshViewport,
        previous: PlayerMeshViewport,
        cells: Int,
        label: String,
    ) {
        val side = cells + 1
        val reflections = listOf(false to false, true to false, false to true, true to true)
        for ((flipX, flipY) in reflections) {
            fun matchingOffsets(actual: FloatArray, expected: FloatArray): Boolean =
                actual.indices.all { index ->
                    val row = index / 2 / side
                    val column = index / 2 % side
                    val previousRow = if (flipY) cells - row else row
                    val previousColumn = if (flipX) cells - column else column
                    val component = index % 2
                    val previousIndex = 2 * (previousRow * side + previousColumn) + component
                    val axisExtent = if (component == 0) viewport.size.width else viewport.size.height
                    val reflected = if (component == 0) flipX else flipY
                    val expectedOffset = if (reflected) -expected[previousIndex] else expected[previousIndex]
                    abs(actual[index] - expectedOffset) <= axisExtent * .025f
                }

            val sameOrder = matchingOffsets(viewport.firstOffset, previous.firstOffset) &&
                matchingOffsets(viewport.lastOffset, previous.lastOffset)
            val reverseOrder = matchingOffsets(viewport.firstOffset, previous.lastOffset) &&
                matchingOffsets(viewport.lastOffset, previous.firstOffset)
            assertFalse("$label duplicates a fold pair after reflection ($flipX, $flipY)", sameOrder || reverseOrder)
        }
    }

    private fun assertLocalizedFoldGeometry(
        geometry: PlayerMeshGeometry,
        viewport: PlayerMeshViewport,
        label: String,
    ) {
        val cells = geometry.cells
        val edgeRows = (cells / 10).coerceAtLeast(1)
        val neighborSpan = (cells / 5).coerceAtLeast(1)
        val flatCellArea = (viewport.size.width.toFloat() / cells) * (viewport.size.height.toFloat() / cells)
        for (phase in listOf(0f, .25f, .5f, .75f, 1f)) {
            val positions = viewport.positions(warpMix = phase, pinchMix = 1f)
            var minAreaRatio = 1f
            val cellAreas = FloatArray(cells * cells)
            for (index in geometry.indices.indices step 3) {
                val a = geometry.indices[index].toInt() * 2
                val b = geometry.indices[index + 1].toInt() * 2
                val c = geometry.indices[index + 2].toInt() * 2
                val abX = positions[b] - positions[a]
                val abY = positions[b + 1] - positions[a + 1]
                val acX = positions[c] - positions[a]
                val acY = positions[c + 1] - positions[a + 1]
                val areaRatio = (abX * acY - abY * acX) / flatCellArea
                minAreaRatio = minOf(minAreaRatio, areaRatio)
                cellAreas[index / 6] += areaRatio * .5f
            }
            assertTrue("$label phase $phase inverts triangles: $minAreaRatio", minAreaRatio > 0f)
            var strongRows = 0
            for (row in 0 until cells) {
                val rowStart = row * cells
                val pinched = (neighborSpan until cells - neighborSpan).any { column ->
                    val index = rowStart + column
                    // Monotone redistribution does not form a local trough between these neighbors.
                    val neighborArea = minOf(cellAreas[index - neighborSpan], cellAreas[index + neighborSpan])
                    cellAreas[index] < neighborArea * .75f
                }
                if (pinched) {
                    assertTrue("$label phase $phase has a strong crease at edge row $row",
                        row in edgeRows until cells - edgeRows)
                    strongRows++
                }
            }
            assertTrue("$label phase $phase should pinch a local region, found $strongRows of $cells rows",
                strongRows in 2..cells * 3 / 4)
        }
    }

    @Test
    fun settledStaticLayerSurvivesExplicitRedrawsWithoutAnimating() {
        val album = meshTestAlbum()
        val renderer = compose.runOnIdle {
            MeshGradientRenderer().apply {
                staticMode = true
                setAlbum(album)
                advanceFrame(StartNanos)
            }
        }
        val host = MeshRenderTestHost(compose, renderer)
        host.show()
        val expected = host.capture()
        assertTrue("The first album must be ready", renderer.hasAlbum)
        assertTrue("The static frame must contain artwork", expected.any {
            Color.red(it) > 24 || Color.green(it) > 24 || Color.blue(it) > 24
        })
        assertFalse("A settled static frame needs no continuous rendering", renderer.needsAnimation)

        repeat(3) { redraw ->
            val actual = host.capture {
                renderer.advanceFrame(StartNanos + (redraw + 1) * 60_000_000_000L)
            }
            assertArrayEquals("An explicit redraw must retain the complete static frame", expected, actual)
            assertFalse("Redrawing a static layer must remain on demand", renderer.needsAnimation)
        }
    }

    @Test
    fun pausedPlaybackCompletesAlbumCrossfadeThenStops() {
        assertAlbumCrossfadeSettles(staticMode = false)
    }

    @Test
    fun staticModeCompletesAlbumCrossfadeThenStops() {
        assertAlbumCrossfadeSettles(staticMode = true)
    }

    @Test
    fun resumingPlaybackExcludesTimeSpentPaused() {
        val album = meshTestAlbum()
        val renderer = compose.runOnIdle {
            MeshGradientRenderer().apply {
                setAlbum(album)
                advanceFrame(StartNanos)
                advanceFrame(StartNanos + FrameNanos)
            }
        }
        val host = MeshRenderTestHost(compose, renderer)
        host.show()
        val playing = host.capture()
        val paused = host.capture {
            renderer.isPlaying = false
            renderer.advanceFrame(PausedNanos)
        }
        assertArrayEquals("Pausing must preserve the current flow phase", playing, paused)
        assertFalse("Paused flow needs no continuous rendering", renderer.needsAnimation)

        val resumed = host.capture {
            renderer.isPlaying = true
            renderer.advanceFrame(PausedNanos + 60_000_000_000L)
        }
        assertArrayEquals("The first resumed frame must exclude the paused interval", paused, resumed)
        val advanced = host.capture {
            renderer.advanceFrame(PausedNanos + 63_000_000_000L)
        }
        assertTrue("Flow must advance after playback resumes", advanced.indices.any {
            colorDistance(advanced[it], resumed[it]) > 2
        })
    }

    @Test
    fun realFrameClockPacesRequestsAndStopsWithoutPollingDisplayFrames() {
        compose.setContent { Spacer(Modifier.size(1.dp)) }
        val uiContext = AndroidUiDispatcher.Main
        val realClock = checkNotNull(uiContext[MonotonicFrameClock])
        var requests = 0
        var callbacks = 0
        val countingClock = object : MonotonicFrameClock {
            override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
                requests++
                return realClock.withFrameNanos {
                    callbacks++
                    onFrame(it)
                }
            }
        }

        runBlocking {
            withContext(uiContext + countingClock) {
                withTimeout(10_000) {
                    for (targetHz in listOf(30, 60)) {
                        requests = 0
                        callbacks = 0
                        var animating = true
                        val drawTimes = mutableListOf<Long>()
                        runMeshFrames(1_000_000_000L / targetHz, { animating }) { frameNanos ->
                            drawTimes += frameNanos
                            if (frameNanos - drawTimes.first() >= 1_200_000_000L) animating = false
                        }
                        val elapsedSeconds = (drawTimes.last() - drawTimes.first()) / 1_000_000_000.0
                        val observedHz = (drawTimes.size - 1) / elapsedSeconds
                        Log.i("MeshFrameClockTest",
                            "targetHz=$targetHz observedHz=$observedHz requests=$requests callbacks=$callbacks")
                        assertEquals("Every registered frame must advance the mesh", requests, drawTimes.size)
                        assertEquals(drawTimes.size, callbacks)
                        assertTrue("The real frame clock must retain $targetHz Hz cadence: $observedHz",
                            observedHz in (targetHz * 0.75)..(targetHz * 1.1))
                        val stoppedRequests = requests
                        delay(100)
                        assertEquals("A settled animation must stop requesting frames", stoppedRequests, requests)
                    }

                    requests = 0
                    callbacks = 0
                    val firstFrame = CompletableDeferred<Unit>()
                    val delayedWork = launch {
                        runMeshFrames(100_000_000L, { true }) { firstFrame.complete(Unit) }
                    }
                    firstFrame.await()
                    delay(20)
                    delayedWork.cancelAndJoin()
                    assertEquals("Cancellation during pacing must retain just the first request", 1, requests)
                    assertEquals(1, callbacks)
                    delay(150)
                    assertEquals("Cancelled pacing must stay idle", 1, requests)

                    val awaitingWork = launch(start = CoroutineStart.UNDISPATCHED) {
                        runMeshFrames(100_000_000L, { true }) {
                            throw AssertionError("A cancelled frame wait must never advance the mesh")
                        }
                    }
                    assertEquals("The cancellation case must suspend in a real frame wait", 2, requests)
                    awaitingWork.cancelAndJoin()
                    delay(100)
                    assertEquals("Cancelled frame waits must not invoke their callback", 1, callbacks)
                }
            }
        }
    }

    @Test
    fun fluidBackgroundPublishesReadinessAcrossAlbumChangesAndDisposal() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        fun cover(color: Int): File {
            val file = File.createTempFile("mesh-readiness-", ".png", context.cacheDir)
            val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(color)
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } finally {
                bitmap.recycle()
            }
            return file
        }
        val first = cover(Color.rgb(210, 80, 40))
        val second = cover(Color.rgb(40, 80, 210))
        val server = ServerSocket(0)
        val requestStarted = CountDownLatch(1)
        val releaseCover = CountDownLatch(1)
        val responseFailure = AtomicReference<Exception?>()
        val activeSocket = AtomicReference<Socket?>()
        val responseThread = thread(isDaemon = true, name = "mesh-cover-response") {
            try {
                server.accept().use { socket ->
                    activeSocket.set(socket)
                    socket.soTimeout = 10_000
                    val reader = socket.getInputStream().bufferedReader()
                    while (!reader.readLine().isNullOrEmpty()) { }
                    requestStarted.countDown()
                    check(releaseCover.await(10, TimeUnit.SECONDS))
                    val bytes = first.readBytes()
                    socket.getOutputStream().apply {
                        write(("HTTP/1.1 200 OK\r\nContent-Type: image/png\r\n" +
                            "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray())
                        write(bytes)
                        flush()
                    }
                }
            } catch (error: Exception) {
                if (!server.isClosed) responseFailure.set(error)
            }
        }
        val firstUrl = "http://127.0.0.1:${server.localPort}/cover.png"
        val imageUrl = mutableStateOf<String?>(firstUrl)
        val visible = mutableStateOf(true)
        val events = CopyOnWriteArrayList<Boolean>()
        val beatMeter = PlaybackBeatMeter()
        fun redBlueBalance(): Double {
            val image = compose.onNodeWithTag("fluidBackground").captureToImage()
            val pixels = IntArray(image.width * image.height).also { image.readPixels(it) }
            return pixels.sumOf { Color.red(it) - Color.blue(it) }.toDouble() / pixels.size
        }
        fun assertGrayPlaceholder() {
            val image = compose.onNodeWithTag("fluidBackground").captureToImage()
            val pixels = IntArray(image.width * image.height).also { image.readPixels(it) }
            assertTrue("The gray album must completely cover the underlying page", pixels.all {
                Color.alpha(it) == 255 && abs(Color.red(it) - Color.green(it)) <= 2 &&
                    abs(Color.red(it) - Color.blue(it)) <= 2 && Color.red(it) in 24..220
            })
        }
        compose.mainClock.autoAdvance = false
        try {
            compose.setContent {
                val backdrop = rememberLayerBackdrop()
                CompositionLocalProvider(
                    LocalPlayerBackgroundReadyChanged provides { events += it },
                ) {
                    if (visible.value) {
                        Box(Modifier.size(96.dp).background(ComposeColor.Green).testTag("fluidBackground")) {
                            FluidBackground(
                                imageUrl = imageUrl.value,
                                beatMeter = beatMeter,
                                isPlaying = false,
                                backdrop = backdrop,
                            )
                        }
                    }
                }
            }
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.mainClock.advanceTimeByFrame()
                requestStarted.count == 0L && events.any { it }
            }
            assertGrayPlaceholder()
            val notReadyCount = events.count { !it }
            releaseCover.countDown()
            var enteringBalance = 0.0
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.mainClock.advanceTimeByFrame()
                enteringBalance = redBlueBalance()
                enteringBalance > 5
            }
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.mainClock.advanceTimeBy(100)
                redBlueBalance() > 40
            }
            val settledBalance = redBlueBalance()
            assertTrue("The real cover must crossfade from the gray album",
                settledBalance > enteringBalance + 5)
            assertTrue("The ready background must display the first cover", settledBalance > 40)
            compose.runOnIdle { imageUrl.value = second.toURI().toString() }
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.mainClock.advanceTimeBy(100)
                redBlueBalance() < -40
            }
            assertEquals("Replacing an album must retain the ready background", notReadyCount,
                events.count { !it })

            compose.runOnIdle {
                imageUrl.value = File(context.cacheDir, "missing-mesh-cover.png").toURI().toString()
            }
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.mainClock.advanceTimeBy(100)
                abs(redBlueBalance()) < 1
            }
            assertGrayPlaceholder()
            compose.runOnIdle { imageUrl.value = "" }
            compose.mainClock.advanceTimeBy(1_000)
            assertGrayPlaceholder()
            assertEquals("Missing and failed covers must retain the ready gray background", notReadyCount,
                events.count { !it })
            compose.runOnIdle { imageUrl.value = null }
            compose.mainClock.advanceTimeByFrame()
            assertGrayPlaceholder()

            compose.runOnIdle { visible.value = false }
            compose.mainClock.advanceTimeByFrame()
            compose.runOnIdle {
                assertEquals("Disposal must publish not ready", false, events.last())
                assertEquals(notReadyCount + 1, events.count { !it })
            }
            events.clear()
            compose.runOnIdle {
                imageUrl.value = firstUrl
                visible.value = true
            }
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.mainClock.advanceTimeByFrame()
                events.any { it }
            }
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.mainClock.advanceTimeBy(100)
                redBlueBalance() > 40
            }
            responseThread.join(1_000)
            responseFailure.get()?.let { throw AssertionError("Could not serve the delayed cover", it) }
        } finally {
            releaseCover.countDown()
            server.close()
            activeSocket.get()?.close()
            responseThread.join(1_000)
            first.delete()
            second.delete()
        }
    }

    private fun assertAlbumCrossfadeSettles(staticMode: Boolean) {
        val album = meshTestAlbum(Color.rgb(220, 50, 50))
        val nextAlbum = meshTestAlbum(Color.rgb(50, 50, 220))
        val renderer = compose.runOnIdle {
            MeshGradientRenderer().apply {
                this.staticMode = staticMode
                isPlaying = false
                setAlbum(album)
                advanceFrame(StartNanos)
            }
        }
        val host = MeshRenderTestHost(compose, renderer)
        host.show()
        val initial = host.capture()
        val transitioning = host.capture {
            renderer.setAlbum(nextAlbum)
            assertTrue("An album change must schedule its crossfade", renderer.needsAnimation)
            repeat(3) { renderer.advanceFrame(StartNanos + (it + 1) * FrameNanos) }
            assertTrue("The crossfade must remain active before it settles", renderer.needsAnimation)
        }
        val final = host.capture {
            repeat(5) { renderer.advanceFrame(StartNanos + (it + 4) * FrameNanos) }
        }
        assertTrue("The crossfade must change the displayed album", initial.indices.any {
            colorDistance(initial[it], final[it]) > 24
        })
        assertTrue("The intermediate frame must differ from the settled album", final.indices.any {
            colorDistance(transitioning[it], final[it]) > 5
        })
        assertFalse("Paused or static flow must stop after the crossfade", renderer.needsAnimation)
        assertArrayEquals("Settled crossfades must survive later redraws", final, host.capture {
            renderer.advanceFrame(PausedNanos)
        })
    }

    private companion object {
        const val PausedNanos = 3_600_000_000_000L
    }
}
