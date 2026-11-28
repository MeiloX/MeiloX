/*
 * Copyright 2025 6xingyv
 * SPDX-License-Identifier: Apache-2.0
 *
 * Adapted from accompanist-lyrics-ui 1.0.19's KaraokeLineText.
 * MeiloX changes the vertical easing and uses the upstream text layout helpers.
 */
package com.ljyh.mei.ui.component.player.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onLayoutRectChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ljyh.mei.utils.lyric.KaraokeSyllableLiftSettleMs
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeAlignment
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import com.mocharealm.accompanist.lyrics.ui.composable.lyrics.LyricsLineItem
import com.mocharealm.accompanist.lyrics.ui.composable.lyrics.RowRenderData
import com.mocharealm.accompanist.lyrics.ui.composable.lyrics.SyllableLayout
import com.mocharealm.accompanist.lyrics.ui.composable.lyrics.calculateBalancedLines
import com.mocharealm.accompanist.lyrics.ui.composable.lyrics.calculateRowRenderData
import com.mocharealm.accompanist.lyrics.ui.composable.lyrics.calculateStaticLineLayout
import com.mocharealm.accompanist.lyrics.ui.composable.lyrics.measureSyllablesAndDetermineAnimation
import com.mocharealm.accompanist.lyrics.ui.utils.LayerPaint
import com.mocharealm.accompanist.lyrics.ui.utils.easing.Bounce
import com.mocharealm.accompanist.lyrics.ui.utils.easing.Swell
import com.mocharealm.accompanist.lyrics.ui.utils.isPunctuation
import com.mocharealm.accompanist.lyrics.ui.utils.isRtl
import kotlin.math.exp
import kotlin.math.roundToInt

private const val SyllableLiftPx = 4f
private const val SyllableLiftSpringRate = 6f
private const val SyllableLiftTailStart = 0.75f
private const val InactiveLyricAlpha = 0.2f
private const val GlowBlurStepsPerPx = 2
private const val MaxGlowBlurPx = 10

private val activeLyricGradient = Color.White.let { color ->
    Brush.horizontalGradient(listOf(color, color))
}

private val inactiveLyricGradient = Color.White.copy(alpha = InactiveLyricAlpha).let { color ->
    Brush.horizontalGradient(listOf(color, color))
}

private val defaultGlowColor = Color.White.copy(alpha = 0.4f)
private val defaultGlowShadows by lazy {
    Array(MaxGlowBlurPx * GlowBlurStepsPerPx + 1) { index ->
        if (index == 0) Shadow.None
        else Shadow(color = defaultGlowColor, offset = Offset.Zero, blurRadius = index.toFloat() / GlowBlurStepsPerPx)
    }
}

private fun glowShadow(color: Color, blurRadius: Float): Shadow {
    val step = (blurRadius * GlowBlurStepsPerPx).roundToInt()
    if (step <= 0) return Shadow.None
    if (color == defaultGlowColor && step < defaultGlowShadows.size) {
        return defaultGlowShadows[step]
    }
    return Shadow(color = color, offset = Offset.Zero, blurRadius = step.toFloat() / GlowBlurStepsPerPx)
}

// Sample the critically damped step response from playback time so seeks are stateless.
internal fun karaokeLiftOffset(progress: Float): Float {
    if (progress <= 0f) return 0f
    if (progress >= 1f) return -SyllableLiftPx

    val springTime = progress * SyllableLiftSpringRate
    val remaining = (1f + springTime) * exp(-springTime)
    // Taper the residual to zero at the render window boundary with continuous velocity.
    val tail = ((progress - SyllableLiftTailStart) / (1f - SyllableLiftTailStart)).coerceAtLeast(0f)
    val envelope = 1f - tail * tail * (3f - 2f * tail)
    return -SyllableLiftPx * (1f - remaining * envelope)
}

private fun createLineGradientBrush(
    rowData: RowRenderData,
    currentTimeMs: Int,
    isRtl: Boolean
): Brush {
    val activeColor = Color.White
    val inactiveColor = Color.White.copy(alpha = 0.2f)
    val minFadeWidth = 100f

    val lineLayout = rowData.rowLayouts
    val totalMinX = rowData.totalMinX
    val totalMaxX = rowData.totalMaxX
    val totalWidth = rowData.totalWidth

    if (totalWidth <= 0f) {
        val isFinished = currentTimeMs >= lineLayout.last().syllable.end
        val color = if (isFinished) activeColor else inactiveColor
        return SolidColor(color)
    }

    val firstSyllableStart = rowData.firstSyllableStart
    val lastSyllableEnd = rowData.lastSyllableEnd

    val lineProgress = run {
        if (currentTimeMs <= firstSyllableStart) return inactiveLyricGradient
        if (currentTimeMs >= lastSyllableEnd) return activeLyricGradient

        val activeSyllableLayout = lineLayout.find {
            currentTimeMs in it.syllable.start until it.syllable.end
        }

        val currentPixelPosition = when {
            activeSyllableLayout != null -> {
                val syllableProgress = activeSyllableLayout.syllable.progress(currentTimeMs)
                if (isRtl) {
                    activeSyllableLayout.position.x + activeSyllableLayout.width * (1f - syllableProgress)
                } else {
                    activeSyllableLayout.position.x + activeSyllableLayout.width * syllableProgress
                }
            }

            else -> {
                val lastFinished = lineLayout.lastOrNull { currentTimeMs >= it.syllable.end }
                if (isRtl) {
                    lastFinished?.position?.x ?: totalMaxX
                } else {
                    lastFinished?.let { it.position.x + it.width } ?: totalMinX
                }
            }
        }
        ((currentPixelPosition - totalMinX) / totalWidth).coerceIn(0f, 1f)
    }

    val fadeRange = (minFadeWidth / totalWidth).coerceAtMost(1f)
    val fadeCenterStart = -fadeRange / 2f
    val fadeCenterEnd = 1f + fadeRange / 2f
    val fadeCenter = fadeCenterStart + (fadeCenterEnd - fadeCenterStart) * lineProgress
    val fadeStart = fadeCenter - fadeRange / 2f
    val fadeEnd = fadeCenter + fadeRange / 2f

    val colorStops = if (isRtl) {
        arrayOf(
            0.0f to inactiveColor,
            fadeStart.coerceIn(0f, 1f) to inactiveColor,
            fadeEnd.coerceIn(0f, 1f) to activeColor,
            1.0f to activeColor
        )
    } else {
        arrayOf(
            0.0f to activeColor,
            fadeStart.coerceIn(0f, 1f) to activeColor,
            fadeEnd.coerceIn(0f, 1f) to inactiveColor,
            1.0f to inactiveColor
        )
    }

    return Brush.horizontalGradient(
        colorStops = colorStops,
        startX = totalMinX,
        endX = totalMaxX
    )
}

private class PreparedLyricRow(
    val data: RowRenderData,
    val syllables: List<PreparedLyricSyllable>,
) {
    val liftedBounds = data.layerBounds.copy(top = data.layerBounds.top - SyllableLiftPx)
    val earliestAnimationStartMs: Long = syllables.fold(data.firstSyllableStart.toLong()) { earliest, syllable ->
        minOf(earliest, syllable.liftStartMs.toLong(), syllable.longTone?.wordStartMs ?: Long.MAX_VALUE)
    }
    private var cachedGradientTimeMs = Int.MIN_VALUE
    private var cachedGradientRtl = false
    private var cachedGradient: Brush? = null

    fun gradientBrush(currentTimeMs: Int, isRtl: Boolean): Brush {
        val existing = cachedGradient
        if (existing != null && cachedGradientTimeMs == currentTimeMs && cachedGradientRtl == isRtl) {
            return existing
        }
        return createLineGradientBrush(data, currentTimeMs, isRtl).also {
            cachedGradientTimeMs = currentTimeMs
            cachedGradientRtl = isRtl
            cachedGradient = it
        }
    }
}

private class PreparedLyricSyllable(
    val layout: SyllableLayout,
    val liftStartMs: Int,
    val longTone: PreparedLongTone?,
)

private class PreparedLongTone(
    val wordStartMs: Long,
    val wordEndMs: Long,
    val durationMs: Float,
    val swell: Easing,
    val glyphs: List<PreparedLyricGlyph>,
    val phonetic: PreparedLyricGlyph?,
)

private class PreparedLyricGlyph(
    val layout: TextLayoutResult,
    val startMs: Long,
    val x: Float,
    private val rowY: Float,
    private val glyphY: Float,
) {
    val size = Size(layout.size.width.toFloat(), layout.size.height.toFloat())
    private val pivotX = x + size.width / 2f

    fun position(lift: Float) = Offset(x, rowY + (glyphY + lift))
    fun pivot(position: Offset) = Offset(pivotX, position.y + size.height)
}

private fun RowRenderData.prepare(phoneticInset: Float, longToneEnabled: Boolean): PreparedLyricRow {
    var previousTextStart: Int? = null
    val syllables = rowLayouts.map { layout ->
        val ownStart = layout.syllable.start
        val liftStart = if (layout.syllable.content.trim().isPunctuation()) {
            previousTextStart ?: ownStart
        } else {
            previousTextStart = ownStart
            ownStart
        }
        PreparedLyricSyllable(
            layout = layout,
            liftStartMs = liftStart,
            longTone = if (longToneEnabled) layout.prepareLongTone(phoneticInset) else null,
        )
    }
    return PreparedLyricRow(this, syllables)
}

private fun SyllableLayout.prepareLongTone(phoneticInset: Float): PreparedLongTone? {
    val word = wordAnimInfo ?: return null
    val duration = word.wordDuration * 0.8f
    val charCount = word.wordContent.length
    val earliest = word.wordStartTime
    val latest = word.wordEndTime - duration
    fun startAt(index: Float): Long {
        val ratio = if (charCount > 1) index / (charCount - 1) else 0.5f
        return (earliest + (latest - earliest) * ratio).toLong()
    }

    val glyphs = syllable.content.indices.mapNotNull { index ->
        val layout = charLayouts?.getOrNull(index) ?: return@mapNotNull null
        val box = charOriginalBounds?.getOrNull(index) ?: return@mapNotNull null
        val centeredX = (box.width - layout.size.width.toFloat()) / 2f
        PreparedLyricGlyph(
            layout = layout,
            startMs = startAt((charOffsetInWord + index).toFloat()),
            x = position.x + (box.left + centeredX),
            rowY = position.y,
            glyphY = box.top,
        )
    }
    val phonetic = phoneticLayoutResult?.let { layout ->
        PreparedLyricGlyph(
            layout = layout,
            startMs = startAt(charOffsetInWord + (syllable.content.length - 1) / 2f),
            x = position.x,
            rowY = position.y,
            glyphY = -layout.size.height.toFloat() + phoneticInset,
        )
    }
    val intensity = (word.wordDuration - 200f * charCount) / 1000
    return PreparedLongTone(earliest, word.wordEndTime, duration,
        Swell((0.1 * intensity).coerceIn(0.0, 0.1)), glyphs, phonetic)
}

internal fun DrawScope.drawLyricsLine(
    rowRenderData: List<RowRenderData>,
    currentTimeMs: Int,
    color: Color,
    isRtl: Boolean,
    showDebugRectangles: Boolean = false,
    showPhonetic: Boolean = true,
    glowEnabled: Boolean = true,
    liftEnabled: Boolean = true,
    longToneEnabled: Boolean = true,
    hdrMaskOnly: Boolean = false,
) {
    val phoneticInset = 4.dp.toPx()
    drawPreparedLyricsLine(
        rowRenderData.map { it.prepare(phoneticInset, longToneEnabled) }, currentTimeMs, color,
        isRtl, phoneticInset, showDebugRectangles, showPhonetic, glowEnabled, liftEnabled, hdrMaskOnly,
    )
}

private fun DrawScope.drawPreparedLyricsLine(
    rows: List<PreparedLyricRow>,
    currentTimeMs: Int,
    color: Color,
    isRtl: Boolean,
    phoneticInset: Float,
    showDebugRectangles: Boolean = false,
    showPhonetic: Boolean = true,
    glowEnabled: Boolean = true,
    liftEnabled: Boolean = true,
    hdrMaskOnly: Boolean = false,
) {
    val phoneticDrawColor = color.copy(alpha = 0.4f)
    for (row in rows) {
        val rowData = row.data
        val blendProgress = currentTimeMs < rowData.lastSyllableEnd
        // Rows that have not begun any fill, lift, or long-tone motion render uniformly at 20% alpha
        // without allocating an offscreen saveLayer + DstIn gradient pass.
        val unstarted = !hdrMaskOnly && currentTimeMs <= row.earliestAnimationStartMs
        // HDR mask rendering already runs inside Overlay's per-row transparent saveLayer.
        val useRowSaveLayer = blendProgress && !unstarted && !hdrMaskOnly
        val applyProgressMask = blendProgress && !unstarted
        val rowColor = if (unstarted) color.copy(alpha = color.alpha * InactiveLyricAlpha) else color
        val rowPhoneticColor = if (unstarted) phoneticDrawColor.copy(alpha = phoneticDrawColor.alpha * InactiveLyricAlpha) else phoneticDrawColor
        val layerBounds = if (liftEnabled) row.liftedBounds else rowData.layerBounds

        drawIntoCanvas { canvas ->
            if (useRowSaveLayer) canvas.saveLayer(layerBounds, LayerPaint)

            for (syllable in row.syllables) {
                val longTone = syllable.longTone
                if (hdrMaskOnly && (longTone == null || currentTimeMs <= longTone.wordStartMs ||
                        currentTimeMs >= longTone.wordEndMs)
                ) continue
                if (longTone != null) {
                    drawLongTone(
                        longTone, currentTimeMs, rowColor, rowPhoneticColor, showDebugRectangles,
                        showPhonetic, glowEnabled && !unstarted, liftEnabled && !unstarted, hdrMaskOnly,
                    )
                } else {
                    val layout = syllable.layout
                    val lift = if (liftEnabled && !unstarted) {
                        karaokeLiftOffset((currentTimeMs - syllable.liftStartMs).toFloat() / KaraokeSyllableLiftSettleMs)
                    } else 0f
                    val position = layout.position.copy(y = layout.position.y + lift)
                    drawText(layout.textLayoutResult, color = rowColor, topLeft = position)
                    if (showPhonetic) {
                        layout.phoneticLayoutResult?.let { phonetic ->
                            val y = position.y - phonetic.size.height + phoneticInset
                            drawText(phonetic, color = rowPhoneticColor, topLeft = Offset(position.x, y))
                        }
                    }
                    if (showDebugRectangles) {
                        val size = layout.textLayoutResult.size
                        drawRect(Color.Green, position, Size(size.width.toFloat(), size.height.toFloat()), style = Stroke(2f))
                    }
                }
            }

            if (applyProgressMask) {
                drawRect(
                    brush = row.gradientBrush(currentTimeMs, isRtl),
                    topLeft = layerBounds.topLeft,
                    size = layerBounds.size,
                    blendMode = BlendMode.DstIn,
                )
            }
            if (useRowSaveLayer) {
                canvas.restore()
            }
        }
    }
}

private fun DrawScope.drawLongTone(
    longTone: PreparedLongTone,
    currentTimeMs: Int,
    color: Color,
    phoneticDrawColor: Color,
    showDebugRectangles: Boolean,
    showPhonetic: Boolean,
    glowEnabled: Boolean,
    liftEnabled: Boolean,
    hdrMaskOnly: Boolean,
) {
    for (glyph in longTone.glyphs) {
        val progress = ((currentTimeMs - glyph.startMs).toFloat() / longTone.durationMs).coerceIn(0f, 1f)
        val coverage = if (hdrMaskOnly) 4f * progress * (1f - progress) else 1f
        if (hdrMaskOnly && !(coverage > 0f) && !showDebugRectangles) continue

        val lift = if (liftEnabled) karaokeLiftOffset(progress) else 0f
        val position = glyph.position(lift)
        scale(scale = 1f + longTone.swell.transform(progress), pivot = glyph.pivot(position)) {
            if (hdrMaskOnly) {
                if (coverage > 0f) drawText(glyph.layout, color = Color.White.copy(alpha = coverage), topLeft = position)
            } else {
                val shadow = if (glowEnabled) {
                    glowShadow(phoneticDrawColor, 10f * Bounce.transform(progress))
                } else Shadow.None
                drawText(glyph.layout, color = color, topLeft = position, shadow = shadow)
            }
            if (showDebugRectangles) drawRect(Color.Red, position, glyph.size, style = Stroke(1f))
        }
    }
    if (showPhonetic) {
        longTone.phonetic?.let { glyph ->
            val progress = ((currentTimeMs - glyph.startMs).toFloat() / longTone.durationMs).coerceIn(0f, 1f)
            val lift = if (liftEnabled) karaokeLiftOffset(progress) else 0f
            val position = glyph.position(lift)
            scale(scale = 1f + longTone.swell.transform(progress), pivot = glyph.pivot(position)) {
                drawText(glyph.layout, color = phoneticDrawColor, topLeft = position)
            }
        }
    }
}

@Composable
internal fun PlayerKaraokeLineText(
    line: KaraokeLine,
    currentTimeProvider: () -> Int,
    modifier: Modifier = Modifier,
    normalLineTextStyle: TextStyle = LocalTextStyle.current,
    accompanimentLineTextStyle: TextStyle = LocalTextStyle.current,
    phoneticTextStyle: TextStyle = LocalTextStyle.current,
    activeColor: Color = Color.White,
    blendMode: BlendMode = BlendMode.SrcOver,
    showDebugRectangles: Boolean = false,
    showTranslation: Boolean = true,
    showPhonetic: Boolean = true,
    precalculatedLayouts: List<SyllableLayout>? = null,
    textMeasurer: TextMeasurer = rememberTextMeasurer(),
    glowEnabled: Boolean = true,
    liftEnabled: Boolean = true,
    longToneEnabled: Boolean = true,
    hdrAccent: PlayerLyricHdrAccent? = null,
) {
    val isLineRtl = remember(line.syllables) { line.syllables.any { it.content.isRtl() } }

    val isRightAligned = remember(line.alignment, isLineRtl) {
        when (line.alignment) {
            KaraokeAlignment.Start, KaraokeAlignment.Unspecified -> isLineRtl
            KaraokeAlignment.End -> !isLineRtl
        }
    }

    val translationTextAlign = remember(isRightAligned) {
        if (isRightAligned) TextAlign.End else TextAlign.Start
    }

    val columnHorizontalAlignment = remember(isRightAligned) {
        if (isRightAligned) Alignment.End else Alignment.Start
    }

    val mainLine = line as? KaraokeLine.MainKaraokeLine
    val accompanimentLinesBeforeMain = remember(mainLine) {
        mainLine?.accompanimentLines?.filter { it.start < line.start }.orEmpty()
    }
    val accompanimentLinesAfterMain = remember(mainLine) {
        mainLine?.accompanimentLines?.filter { it.start >= line.start }.orEmpty()
    }

    @Composable
    fun AccompanimentLines(isBefore: Boolean, accompanimentLines: List<KaraokeLine>) {
        accompanimentLines.forEach { bgLine ->
            val isAccompanimentVisible by remember(bgLine, currentTimeProvider) {
                derivedStateOf {
                    val currentTime = currentTimeProvider()
                    currentTime >= (bgLine.start - 600) && currentTime <= (bgLine.end + 600)
                }
            }

            AnimatedVisibility(
                visible = isAccompanimentVisible,
                enter = scaleIn(
                    tween(600), transformOrigin = TransformOrigin(
                        if (isRightAligned) 1f else 0f, if (isBefore) 1f else 0f
                    )
                ) + fadeIn(tween(600)) + slideInVertically(
                    tween(
                        600
                    )
                ) + expandVertically(tween(600), if (isBefore) Alignment.Top else Alignment.Bottom),
                exit = scaleOut(
                    tween(600), transformOrigin = TransformOrigin(
                        if (isRightAligned) 1f else 0f, if (isBefore) 1f else 0f
                    )
                ) + fadeOut(tween(600)) + slideOutVertically(
                    tween(
                        600
                    )
                ) + shrinkVertically(tween(600), if (isBefore) Alignment.Top else Alignment.Bottom),
            ) {
                LyricsLineItem(
                    isFocused = true,
                    isRightAligned = isRightAligned,
                    onLineClicked = { },
                    onLinePressed = { },
                    blurRadius = { 0f },
                    blendMode = blendMode,
                    activeAlpha = 0.6f,
                    inactiveAlpha = 0.2f
                ) {
                    PlayerKaraokeLineText(
                        line = bgLine,
                        currentTimeProvider = currentTimeProvider,
                        normalLineTextStyle = normalLineTextStyle,
                        accompanimentLineTextStyle = accompanimentLineTextStyle,
                        phoneticTextStyle = phoneticTextStyle,
                        activeColor = activeColor,
                        blendMode = blendMode,
                        showDebugRectangles = showDebugRectangles,
                        showTranslation = showTranslation,
                        showPhonetic = showPhonetic,
                        textMeasurer = textMeasurer,
                        glowEnabled = glowEnabled,
                        liftEnabled = liftEnabled,
                        longToneEnabled = longToneEnabled,
                    )
                }
            }
        }
    }

    Column(
        modifier = modifier.fillMaxWidth().padding(
            vertical = 8.dp, horizontal = if (line is KaraokeLine.AccompanimentKaraokeLine) 0.dp
            else 16.dp
        ),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        horizontalAlignment = columnHorizontalAlignment
    ) {
        AccompanimentLines(true, accompanimentLinesBeforeMain)

        BoxWithConstraints {
            val density = LocalDensity.current
            val availableWidthPx = with(density) { maxWidth.toPx() }

            val baseStyle = if (line is KaraokeLine.AccompanimentKaraokeLine) accompanimentLineTextStyle
                else normalLineTextStyle
            val textStyle = remember(baseStyle) { baseStyle.copy(textDirection = TextDirection.Content) }

            val spaceWidth = remember(textMeasurer, textStyle) {
                textMeasurer.measure(" ", textStyle).size.width.toFloat()
            }

            val processedSyllables = remember(line.syllables, line.alignment) {
                if (line.alignment == KaraokeAlignment.End) {
                    line.syllables.dropLastWhile { it.content.isBlank() }
                } else {
                    line.syllables
                }
            }

            val initialLayouts = remember(
                precalculatedLayouts, processedSyllables, textMeasurer, textStyle, phoneticTextStyle, spaceWidth,
            ) {
                precalculatedLayouts ?: measureSyllablesAndDetermineAnimation(
                    syllables = processedSyllables,
                    textMeasurer = textMeasurer,
                    style = textStyle,
                    phoneticStyle = phoneticTextStyle,
                    isAccompanimentLine = line is KaraokeLine.AccompanimentKaraokeLine,
                    spaceWidth = spaceWidth,
                )
            }

            val wrappedLines = remember(initialLayouts, availableWidthPx, textMeasurer, textStyle) {
                calculateBalancedLines(initialLayouts, availableWidthPx, textMeasurer, textStyle)
            }

            val lineHeight = remember(textMeasurer, textStyle) {
                textMeasurer.measure("M", textStyle).size.height.toFloat()
            }

            val phoneticHeight = remember(textMeasurer, phoneticTextStyle) {
                textMeasurer.measure("M", phoneticTextStyle).size.height.toFloat()
            }

            val finalLineLayouts = remember(
                wrappedLines, availableWidthPx, lineHeight, isLineRtl, isRightAligned, showPhonetic
            ) {
                calculateStaticLineLayout(
                    wrappedLines = wrappedLines,
                    isLineRightAligned = isRightAligned,
                    canvasWidth = availableWidthPx,
                    lineHeight = lineHeight,
                    phoneticHeight = if (showPhonetic) phoneticHeight else 0f,
                    isRtl = isLineRtl
                )
            }

            val rowRenderData = remember(finalLineLayouts, showPhonetic, density) {
                calculateRowRenderData(
                    lineLayouts = finalLineLayouts,
                    showPhonetic = showPhonetic,
                    density = density.density
                )
            }

            val phoneticInset = with(density) { 4.dp.toPx() }
            val preparedRows = remember(rowRenderData, phoneticInset, longToneEnabled) {
                rowRenderData.map { it.prepare(phoneticInset, longToneEnabled) }
            }

            val hasPhonetics = remember(initialLayouts, showPhonetic) {
                showPhonetic && initialLayouts.any { it.phoneticLayoutResult != null }
            }

            val totalHeight = remember(wrappedLines, lineHeight, hasPhonetics, phoneticHeight) {
                var height = lineHeight * wrappedLines.size
                if (hasPhonetics) {
                    height += phoneticHeight * wrappedLines.size
                }
                height
            }

            val masks = if (hdrAccent != null && glowEnabled && longToneEnabled) {
                remember(preparedRows, currentTimeProvider, isLineRtl, phoneticInset, liftEnabled) {
                    preparedRows.mapNotNull { row ->
                        val wordSpans = row.data.rowLayouts.mapNotNull { it.wordAnimInfo }.distinct()
                        if (wordSpans.isEmpty()) return@mapNotNull null
                        val maskRows = listOf(row)
                        val active = derivedStateOf(structuralEqualityPolicy()) {
                            val time = currentTimeProvider()
                            wordSpans.any { time > it.wordStartTime && time < it.wordEndTime }
                        }
                        PlayerLyricHdrAccent.Mask(
                            bounds = if (liftEnabled) row.liftedBounds else row.data.layerBounds,
                            isActive = { active.value },
                            draw = {
                                drawPreparedLyricsLine(
                                    rows = maskRows,
                                    currentTimeMs = currentTimeProvider(),
                                    color = Color.White,
                                    isRtl = isLineRtl,
                                    phoneticInset = phoneticInset,
                                    showPhonetic = false,
                                    glowEnabled = false,
                                    liftEnabled = liftEnabled,
                                    hdrMaskOnly = true,
                                )
                            },
                        )
                    }
                }
            } else emptyList()
            if (hdrAccent != null && masks.isNotEmpty()) {
                DisposableEffect(hdrAccent, masks) {
                    masks.forEach(hdrAccent::add)
                    onDispose { masks.forEach(hdrAccent::remove) }
                }
            }
            Canvas(
                modifier = Modifier.size(maxWidth, (totalHeight.roundToInt() + 8).toDp())
                    .then(
                        if (hdrAccent != null && masks.isNotEmpty()) {
                            Modifier.onGloballyPositioned { coordinates ->
                                masks.forEach { it.coordinates = coordinates }
                                hdrAccent.geometryChanged()
                            }.onLayoutRectChanged(throttleMillis = 0, debounceMillis = 0) {
                                hdrAccent.geometryChanged()
                            }
                        } else Modifier,
                    ),
            ) {
                val time = currentTimeProvider()
                drawPreparedLyricsLine(
                    rows = preparedRows,
                    currentTimeMs = time,
                    color = activeColor,
                    isRtl = isLineRtl,
                    phoneticInset = phoneticInset,
                    showDebugRectangles = showDebugRectangles,
                    showPhonetic = showPhonetic,
                    glowEnabled = glowEnabled,
                    liftEnabled = liftEnabled,
                )
            }
        }

        if (showTranslation) {
            line.translation?.let { translation ->
                Text(
                    text = translation,
                    color = activeColor.copy(0.4f),
                    textAlign = translationTextAlign
                )
            }
        }

        if (showPhonetic) {
            line.phonetic?.let { phonetic ->
                Text(
                    text = phonetic,
                    style = phoneticTextStyle,
                    color = activeColor.copy(alpha = 0.6f),
                    textAlign = translationTextAlign
                )
            }
        }
        AccompanimentLines(false, accompanimentLinesAfterMain)
    }
}

@Composable
private fun Int.toDp(): Dp = with(LocalDensity.current) { this@toDp.toDp() }
