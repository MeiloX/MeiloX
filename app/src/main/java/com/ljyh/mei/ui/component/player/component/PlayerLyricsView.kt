/*
 * Copyright 2025 6xingyv
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Adapted from KaraokeLyricsView and LyricsLineItem in com.mocharealm.accompanist:lyrics-ui:1.0.19.
 * Upstream: https://github.com/6xingyv/accompanist-lyrics-ui
 * Modified for Mei: scrolling is owned by the player, which supplies manual
 * browsing state, while karaoke rendering can use a separate display clock.
 */

package com.ljyh.mei.ui.component.player.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LookaheadScope
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextMotion
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.kyant.capsule.ContinuousRoundedRectangle
import com.ljyh.mei.utils.lyric.KaraokeRenderTimeWindow
import com.ljyh.mei.utils.lyric.karaokeRenderTimeWindow
import com.mocharealm.accompanist.lyrics.core.model.ISyncedLine
import com.mocharealm.accompanist.lyrics.core.model.SyncedLyrics
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeAlignment
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import com.mocharealm.accompanist.lyrics.core.model.synced.SyncedLine
import com.mocharealm.accompanist.lyrics.ui.composable.lyrics.KaraokeBreathingDots
import com.mocharealm.accompanist.lyrics.ui.composable.lyrics.KaraokeBreathingDotsDefaults
import com.mocharealm.accompanist.lyrics.ui.composable.lyrics.SyllableLayout
import com.mocharealm.accompanist.lyrics.ui.composable.lyrics.SyncedLineText
import com.mocharealm.accompanist.lyrics.ui.composable.lyrics.measureSyllablesAndDetermineAnimation
import com.mocharealm.accompanist.lyrics.ui.utils.isRtl
import com.mocharealm.accompanist.lyrics.ui.utils.modifier.springPlacement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private data class FocusState(
    val firstIndex: Int,
    val allIndices: List<Int>,
    val quietInterval: IntRange?,
)

private const val DimLyricBrightness = 0.4f
private const val BrowsingLyricBrightness = 0.72f
private const val LyricBrightnessSteps = 96
private const val MaxLyricBlurDistance = 4
private const val LyricBlurStepsPerPx = 2
private const val MaxCachedLyricBlurPx = 24
private val lyricFocusTransition = tween<Float>(300)
private val lyricRowShape = ContinuousRoundedRectangle(8.dp)

private val lyricBrightnessFilters by lazy {
    Array(LyricBrightnessSteps) { index ->
        val gain = DimLyricBrightness + (1f - DimLyricBrightness) * index / LyricBrightnessSteps
        ColorFilter.colorMatrix(ColorMatrix().apply { setToScale(gain, gain, gain, 1f) })
    }
}

private val lyricBlurEffects by lazy {
    Array(MaxCachedLyricBlurPx * LyricBlurStepsPerPx) { index ->
        val radius = (index + 1).toFloat() / LyricBlurStepsPerPx
        BlurEffect(radius, radius, TileMode.Clamp)
    }
}

private fun cachedLyricBlurEffect(radius: Float): BlurEffect? {
    if (radius <= 0f) return null
    val step = (radius * LyricBlurStepsPerPx).roundToInt()
    if (step <= 0) return null
    return if (step <= lyricBlurEffects.size) {
        lyricBlurEffects[step - 1]
    } else {
        val quantized = step.toFloat() / LyricBlurStepsPerPx
        BlurEffect(quantized, quantized, TileMode.Clamp)
    }
}

internal fun lyricEdgeFadeBrush(height: Float, top: Float, bottom: Float): Brush? {
    if (height <= 0f || (top <= 0f && bottom <= 0f)) return null
    val topFraction = (top / height).coerceIn(0f, 1f)
    val bottomFraction = (bottom / height).coerceIn(0f, 1f - topFraction)
    return Brush.verticalGradient(
        0f to if (topFraction > 0f) Color.Transparent else Color.Black,
        topFraction to Color.Black,
        1f - bottomFraction to Color.Black,
        1f to if (bottomFraction > 0f) Color.Transparent else Color.Black,
        endY = height,
    )
}

internal fun lyricControlsFadeBrush(
    height: Float,
    fadeRange: (Float) -> ClosedFloatingPointRange<Float>?,
): Brush? {
    val range = fadeRange(height) ?: return null
    if (range.start >= height || range.endInclusive <= range.start) return null
    return Brush.verticalGradient(
        colors = listOf(Color.Black, Color.Transparent),
        startY = range.start,
        endY = range.endInclusive,
    )
}

/** Renders lyrics while the caller owns automatic scrolling and manual browsing state. */
@OptIn(ExperimentalAnimationApi::class, ExperimentalSharedTransitionApi::class)
@Composable
internal fun PlayerLyricsView(
    listState: LazyListState,
    lyrics: SyncedLyrics,
    currentPosition: () -> Int,
    onLineClicked: (ISyncedLine) -> Unit,
    onLinePressed: (ISyncedLine) -> Unit,
    isManualScrolling: Boolean,
    modifier: Modifier = Modifier,
    listModifier: Modifier = modifier,
    normalLineTextStyle: TextStyle = LocalTextStyle.current.copy(
        fontSize = 34.sp,
        fontWeight = FontWeight.Bold,
        textMotion = TextMotion.Animated,
    ),
    accompanimentLineTextStyle: TextStyle = LocalTextStyle.current.copy(
        fontSize = 20.sp,
        fontWeight = FontWeight.Bold,
        textMotion = TextMotion.Animated,
    ),
    textColor: Color = Color.White,
    breathingDotsDefaults: KaraokeBreathingDotsDefaults = KaraokeBreathingDotsDefaults(),
    phoneticTextStyle: TextStyle = normalLineTextStyle.copy(
        fontSize = if (normalLineTextStyle.fontSize == androidx.compose.ui.unit.TextUnit.Unspecified) 13.sp
            else (normalLineTextStyle.fontSize.value * 0.5f).coerceAtLeast(12f).sp,
        fontWeight = FontWeight.Normal,
    ),
    blendMode: BlendMode = BlendMode.Plus,
    useBlurEffect: Boolean = true,
    showTranslation: Boolean = true,
    showPhonetic: Boolean = true,
    isVerbatim: Boolean = true,
    offset: Dp = 32.dp,
    keepAliveZone: Dp = 100.dp,
    blurDelta: Float = 3f,
    showDebugRectangles: Boolean = false,
    placementGeneration: Int = 0,
    translationLineTextStyle: TextStyle = LocalTextStyle.current,
    topFadeHeight: Dp = 20.dp,
    bottomFadeHeight: Dp = 100.dp,
    contentModifier: Modifier = Modifier,
    controlsFadeRange: (Float) -> ClosedFloatingPointRange<Float>? = { null },
    isInteractive: Boolean = true,
    precomputeLineLayouts: Boolean = true,
    isolateFadeLayer: Boolean = true,
    renderPosition: () -> Int = currentPosition,
    quietIntervals: List<IntRange> = remember(lyrics.lines) { lyricQuietIntervals(lyrics.lines) },
    glowEnabled: Boolean = true,
    liftEnabled: Boolean = true,
    longToneEnabled: Boolean = true,
    isBrowsing: Boolean = false,
    hdrAccent: PlayerLyricHdrAccent? = null,
    renderWindows: List<KaraokeRenderTimeWindow?>? = null,
) {
    val stableNormalTextStyle = remember(normalLineTextStyle) { normalLineTextStyle }
    val stableAccompanimentTextStyle =
        remember(accompanimentLineTextStyle) { accompanimentLineTextStyle }
    val stablePhoneticTextStyle = remember(phoneticTextStyle) { phoneticTextStyle }
    // Theme line heights are fixed in sp and can be smaller than the selected font.
    val translationTextStyle = translationLineTextStyle.copy(lineHeight = 1.2.em)
    val stableOffset = remember(offset) { offset }
    val stableBlendMode = remember(blendMode) { blendMode }
    val inactiveRowBrightness = if (isBrowsing) BrowsingLyricBrightness else DimLyricBrightness

    val textMeasurer = rememberTextMeasurer()
    val layoutCache = remember(
        lyrics,
        isVerbatim,
        stableNormalTextStyle,
        stablePhoneticTextStyle,
        textMeasurer,
        precomputeLineLayouts,
    ) { mutableStateOf(emptyMap<Int, List<SyllableLayout>>()) }

    LaunchedEffect(layoutCache) {
        // The incoming mix layer only measures the rows it needs to show.
        if (!isVerbatim || !precomputeLineLayouts) return@LaunchedEffect
        layoutCache.value = withContext(Dispatchers.Default) {
            val normalStyle = stableNormalTextStyle.copy(textDirection = TextDirection.Content)
            val phoneticStyle = stablePhoneticTextStyle.copy(textDirection = TextDirection.Content)
            val spaceWidth = textMeasurer.measure(" ", normalStyle).size.width.toFloat()
            buildMap {
                lyrics.lines.forEachIndexed { index, line ->
                    ensureActive()
                    if (line !is KaraokeLine.MainKaraokeLine) return@forEachIndexed
                    val syllables = if (line.alignment == KaraokeAlignment.End) {
                        line.syllables.dropLastWhile { it.content.isBlank() }
                    } else line.syllables
                    put(
                        index,
                        measureSyllablesAndDetermineAnimation(
                            syllables = syllables,
                            textMeasurer = textMeasurer,
                            style = normalStyle,
                            phoneticStyle = phoneticStyle,
                            isAccompanimentLine = false,
                            spaceWidth = spaceWidth,
                        ),
                    )
                }
            }
        }
    }

    val focusPosition by rememberUpdatedState(currentPosition)
    val drawPosition by rememberUpdatedState(renderPosition)
    val timeProvider = remember { { focusPosition() } }
    val renderTimeProvider = remember { { drawPosition() } }

    val accompanimentToMainMap = remember(lyrics.lines) {
        lyricAccompanimentOwners(lyrics.lines)
    }

    val lyricsFocusState = remember(lyrics, accompanimentToMainMap, quietIntervals) {
        derivedStateOf {
            val time = timeProvider()
            val activeIndices = activeMainLyricLineIndices(lyrics.lines, time, accompanimentToMainMap)
            val first = activeIndices.firstOrNull() ?: upcomingMainLyricLineIndex(lyrics.lines, time)
            FocusState(first, activeIndices, lyricQuietIntervalAt(quietIntervals, time))
        }
    }

    val currentLyrics = lyrics
    val lyricsTransition = updateTransition(lyrics to isVerbatim, label = "Lyrics content")
    val settledAccent = hdrAccent?.takeIf {
        !lyricsTransition.isRunning && lyricsTransition.currentState == lyricsTransition.targetState
    }
    LookaheadScope {
        lyricsTransition.Crossfade { (lyrics, isVerbatim) ->
            Box(modifier = modifier.clipToBounds()) {
                LazyColumn(
                    state = listState,
                    userScrollEnabled = isInteractive,
                    modifier = listModifier
                        .fillMaxSize()
                        .then(contentModifier)
                        .then(
                            if (settledAccent != null) {
                                Modifier.onGloballyPositioned(settledAccent::positionViewport)
                            } else Modifier,
                        )
                        .then(
                            if (isolateFadeLayer) Modifier.graphicsLayer {
                                compositingStrategy = CompositingStrategy.Offscreen
                            } else Modifier,
                        )
                        .drawWithCache {
                            val edgeBrush = lyricEdgeFadeBrush(
                                size.height, topFadeHeight.toPx(), bottomFadeHeight.toPx(),
                            )
                            var cachedControlsStops = Offset.Unspecified
                            var cachedControlsBrush: Brush? = null
                            onDrawWithContent {
                                if (size.width <= 0f || size.height <= 0f) return@onDrawWithContent
                                // Bound keep-alive rows to the fade rect even when reusing an ancestor
                                // offscreen layer so contentModifier translations cannot expose unmasked rows.
                                clipRect {
                                    this@onDrawWithContent.drawContent()
                                    if (edgeBrush != null) {
                                        drawRect(
                                            brush = edgeBrush,
                                            blendMode = BlendMode.DstIn
                                        )
                                    }
                                    val controlsRange = controlsFadeRange(size.height)
                                    if (controlsRange != null && controlsRange.start < size.height &&
                                        controlsRange.endInclusive > controlsRange.start
                                    ) {
                                        val stops = Offset(controlsRange.start, controlsRange.endInclusive)
                                        if (cachedControlsStops != stops) {
                                            cachedControlsStops = stops
                                            cachedControlsBrush = Brush.verticalGradient(
                                                colors = listOf(Color.Black, Color.Transparent),
                                                startY = controlsRange.start,
                                                endY = controlsRange.endInclusive,
                                            )
                                        }
                                        cachedControlsBrush?.let { brush ->
                                            drawRect(brush = brush, blendMode = BlendMode.DstIn)
                                        }
                                    }
                                }
                            }
                        }
                        .layout { measurable, constraints ->
                            val extraHeightPx = (keepAliveZone * 2).roundToPx()

                            val placeable = measurable.measure(
                                constraints.copy(
                                    maxHeight = constraints.maxHeight + extraHeightPx
                                )
                            )

                            layout(constraints.maxWidth, constraints.maxHeight) {
                                placeable.place(0, -(keepAliveZone.roundToPx()))
                            }
                        },
                    contentPadding = PaddingValues(vertical = stableOffset + keepAliveZone)
                ) {
                    itemsIndexed(
                        items = lyrics.lines,
                        key = { index, line -> "${line.start}-${line.end}-$index" }
                    ) { index, line ->
                        val isCurrentFocusLine by remember(lyricsFocusState, index) {
                            derivedStateOf { index in lyricsFocusState.value.allIndices }
                        }
                        val isLineRtl =
                            when (line) {
                                is KaraokeLine -> {
                                    remember(line.syllables) { line.syllables.any { it.content.isRtl() } }
                                }

                                else -> false
                            }
                        val isLineRightAligned = when (line) {
                            is KaraokeLine -> {
                                remember { line.alignment == KaraokeAlignment.End }
                            }

                            else -> false
                        }
                        val isVisualRightAligned = remember(isLineRightAligned, isLineRtl) {
                            if (isLineRightAligned) !isLineRtl
                            else isLineRtl
                        }

                        val distanceWeightState = remember(lyricsFocusState, index) {
                            derivedStateOf {
                                val focus = lyricsFocusState.value
                                val start = focus.allIndices.firstOrNull() ?: focus.firstIndex
                                val end = focus.allIndices.lastOrNull() ?: focus.firstIndex
                                maxOf(0, start - index, index - end)
                            }
                        }

                        val dynamicStiffness by remember(distanceWeightState) {
                            derivedStateOf {
                                (240f - (distanceWeightState.value * 26f)).coerceAtLeast(65f)
                            }
                        }

                        val blurDistance by remember(distanceWeightState) {
                            derivedStateOf {
                                distanceWeightState.value.coerceAtMost(MaxLyricBlurDistance)
                            }
                        }

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .springPlacement(
                                    this@LookaheadScope,
                                    // A new key resets this node's spring without recreating the renderer.
                                    "${line.start}-${line.end}-$index-$placementGeneration",
                                    isManualScrolling,
                                    stiffness = dynamicStiffness
                                ),
                            horizontalAlignment = if (isVisualRightAligned) Alignment.End else Alignment.Start
                        ) {
                            val quietInterval by remember(lyricsFocusState, index, lyrics, currentLyrics) {
                                derivedStateOf {
                                    val focus = lyricsFocusState.value
                                    focus.quietInterval.takeIf { index == focus.firstIndex && lyrics == currentLyrics }
                                }
                            }
                            AnimatedVisibility(quietInterval != null) {
                                val lastInterval = remember { mutableStateOf(quietInterval) }
                                SideEffect { if (quietInterval != null) lastInterval.value = quietInterval }
                                val interval = quietInterval ?: lastInterval.value ?: return@AnimatedVisibility
                                KaraokeBreathingDots(
                                    alignment = when (val previous = lyrics.lines.getOrNull(index - 1) ?: line) {
                                        is KaraokeLine -> previous.alignment
                                        is SyncedLine -> if (previous.content.isRtl()) KaraokeAlignment.End
                                            else KaraokeAlignment.Start
                                        else -> KaraokeAlignment.Start
                                    },
                                    startTimeMs = interval.first,
                                    endTimeMs = interval.last + 1,
                                    currentTimeProvider = renderTimeProvider,
                                    defaults = breathingDotsDefaults,
                                    modifier = Modifier.padding(vertical = 12.dp)
                                )
                            }


                            val targetBlurRadius = if (useBlurEffect && !isBrowsing && !isManualScrolling) {
                                blurDistance * blurDelta
                            } else 0f

                            when (line) {
                                is KaraokeLine -> {
                                    if (line is KaraokeLine.MainKaraokeLine) {
                                        PlayerLyricsLineItem(
                                            isFocused = isCurrentFocusLine,
                                            isRightAligned = isVisualRightAligned,
                                            onLineClicked = { onLineClicked(line) },
                                            onLinePressed = { onLinePressed(line) },
                                            blurRadius = targetBlurRadius,
                                            inactiveBrightness = inactiveRowBrightness,
                                            blendMode = stableBlendMode,
                                            isInteractive = isInteractive,
                                        ) {
                                            if (!isVerbatim) {
                                                // Pronunciation wraps LRC in KaraokeLine without adding word timing.
                                                LineSyncedPhoneticText(
                                                    line = line,
                                                    isRightAligned = isVisualRightAligned,
                                                    textStyle = stableNormalTextStyle,
                                                    translationTextStyle = translationTextStyle,
                                                    phoneticTextStyle = stablePhoneticTextStyle,
                                                    textColor = textColor,
                                                    blendMode = stableBlendMode,
                                                    showTranslation = showTranslation,
                                                    showPhonetic = showPhonetic,
                                                )
                                            } else {
                                                val window = remember(line, renderWindows, lyrics == currentLyrics) {
                                                    if (renderWindows != null && lyrics == currentLyrics) {
                                                        renderWindows.getOrNull(index)
                                                    } else karaokeRenderTimeWindow(line)
                                                }
                                                val lineTimeProvider = remember(window, renderTimeProvider) {
                                                    if (window == null) renderTimeProvider else {
                                                        // Reuse static row pixels; every active animation
                                                        // still receives the original playback clock.
                                                        val renderTime = derivedStateOf { window.sample(renderTimeProvider()) }
                                                        val provider: () -> Int = { renderTime.value }
                                                        provider
                                                    }
                                                }
                                                // Each row keeps the layout it entered with. Warmup supplies
                                                // future rows instead of remeasuring already-visible text.
                                                val precalculatedLayouts = remember(layoutCache, lyrics == currentLyrics, index) {
                                                    if (lyrics == currentLyrics) {
                                                        Snapshot.withoutReadObservation { layoutCache.value[index] }
                                                    } else null
                                                }
                                                CompositionLocalProvider(LocalTextStyle provides translationTextStyle) {
                                                    PlayerKaraokeLineText(
                                                        line = line,
                                                        currentTimeProvider = lineTimeProvider,
                                                        normalLineTextStyle = stableNormalTextStyle,
                                                        accompanimentLineTextStyle = stableAccompanimentTextStyle,
                                                        phoneticTextStyle = stablePhoneticTextStyle,
                                                        activeColor = textColor,
                                                        blendMode = stableBlendMode,
                                                        showDebugRectangles = showDebugRectangles,
                                                        showTranslation = showTranslation,
                                                        showPhonetic = showPhonetic,
                                                        glowEnabled = glowEnabled,
                                                        liftEnabled = liftEnabled,
                                                        longToneEnabled = longToneEnabled,
                                                        precalculatedLayouts = precalculatedLayouts,
                                                        textMeasurer = textMeasurer,
                                                        hdrAccent = settledAccent?.takeIf {
                                                            isCurrentFocusLine && lyrics == currentLyrics
                                                        },
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }

                                is SyncedLine -> {
                                    val isLineRtl = remember(line.content) { line.content.isRtl() }
                                    PlayerLyricsLineItem(
                                        isFocused = isCurrentFocusLine,
                                        isRightAligned = isLineRtl,
                                        onLineClicked = { onLineClicked(line) },
                                        onLinePressed = { onLinePressed(line) },
                                        blurRadius = targetBlurRadius,
                                        inactiveBrightness = inactiveRowBrightness,
                                        blendMode = stableBlendMode,
                                        isInteractive = isInteractive,
                                    ) {
                                        // Translation Text inherits this style; primary and phonetic text use explicit styles.
                                        CompositionLocalProvider(LocalTextStyle provides translationTextStyle) {
                                            SyncedLineText(
                                                line = line,
                                                isLineRtl = isLineRtl,
                                                textStyle = stableNormalTextStyle.copy(lineHeight = 1.2.em),
                                                textColor = textColor,
                                                showTranslation = showTranslation,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    item("BottomSpacing") {
                        Spacer(
                            modifier = Modifier.fillMaxWidth().height(2000.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayerLyricsLineItem(
    isFocused: Boolean,
    isRightAligned: Boolean,
    onLineClicked: () -> Unit,
    onLinePressed: () -> Unit,
    blurRadius: Float,
    inactiveBrightness: Float,
    blendMode: BlendMode,
    isInteractive: Boolean,
    content: @Composable () -> Unit,
) {
    val scale by animateFloatAsState(
        if (isFocused) 1f else 0.98f,
        if (isFocused) tween(600, easing = LinearOutSlowInEasing) else tween(300, easing = EaseInOut),
        label = "Lyric row scale",
    )
    val brightness by animateFloatAsState(
        if (isFocused) 1f else inactiveBrightness,
        lyricFocusTransition,
        label = "Lyric row brightness",
    )
    val blur = animateFloatAsState(blurRadius, lyricFocusTransition, label = "Lyric row blur")
    Box(
        modifier = Modifier.fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(if (isRightAligned) 1f else 0f, 1f)
                this.blendMode = blendMode
                compositingStrategy = CompositingStrategy.Offscreen
                renderEffect = cachedLyricBlurEffect(blur.value)
                // Attenuate neutral RGB after rendering the row and its karaoke mask.
                // Coverage stays intact, and gains never exceed the original source.
                val step = ((brightness - DimLyricBrightness) * LyricBrightnessSteps /
                    (1f - DimLyricBrightness)).roundToInt()
                colorFilter = if (step >= LyricBrightnessSteps) null
                    else lyricBrightnessFilters[step.coerceAtLeast(0)]
            }
            .then(
                if (isInteractive) Modifier.clip(lyricRowShape).combinedClickable(
                    onClick = onLineClicked,
                    onLongClick = onLinePressed,
                ) else Modifier,
            )
    ) {
        content()
    }
}

@Composable
private fun LineSyncedPhoneticText(
    line: KaraokeLine.MainKaraokeLine,
    isRightAligned: Boolean,
    textStyle: TextStyle,
    translationTextStyle: TextStyle,
    phoneticTextStyle: TextStyle,
    textColor: Color,
    blendMode: BlendMode,
    showTranslation: Boolean,
    showPhonetic: Boolean,
) {
    val content = remember(line.syllables) { line.syllables.joinToString("") { it.content } }
    val textAlign = if (isRightAligned) TextAlign.End else TextAlign.Start
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp, horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        horizontalAlignment = if (isRightAligned) Alignment.End else Alignment.Start,
    ) {
        Text(
            text = content,
            style = textStyle.copy(lineHeight = 1.2.em),
            color = textColor,
            textAlign = textAlign,
        )
        if (showTranslation) {
            line.translation?.let {
                Text(
                    text = it,
                    style = translationTextStyle,
                    color = textColor.copy(alpha = 0.4f),
                    textAlign = textAlign,
                )
            }
        }
        if (showPhonetic) {
            line.phonetic?.let {
                Text(
                    text = it,
                    style = phoneticTextStyle.copy(lineHeight = 1.2.em),
                    color = textColor.copy(alpha = 0.6f),
                    textAlign = textAlign,
                )
            }
        }
    }
}
