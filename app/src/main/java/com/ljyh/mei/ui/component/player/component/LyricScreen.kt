package com.ljyh.mei.ui.component.player.component


import android.os.Build
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextMotion
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.ljyh.mei.R
import com.ljyh.mei.constants.TranslationLyricTextWeightKey
import com.ljyh.mei.constants.AccompanimentLyricTextSizeKey
import com.ljyh.mei.constants.LyricGlowEnabledKey
import com.ljyh.mei.constants.LyricLiftEnabledKey
import com.ljyh.mei.constants.LyricLongToneEnabledKey
import com.ljyh.mei.constants.LyricRomanizationEnabledKey
import com.ljyh.mei.constants.LyricTextSize
import com.ljyh.mei.constants.LyricTranslationEnabledKey
import com.ljyh.mei.constants.NormalLyricTextWeightKey
import com.ljyh.mei.constants.NormalLyricTextSizeKey
import com.ljyh.mei.data.model.metadata
import com.ljyh.mei.playback.PlayerConnection
import com.ljyh.mei.ui.component.player.LocalAutoMixVisuals
import com.ljyh.mei.ui.component.player.component.mesh.runMeshFrames
import com.ljyh.mei.ui.component.sheet.LocalPlayerSheet
import com.ljyh.mei.ui.component.utils.LifecycleStartedEffect
import com.ljyh.mei.ui.model.LyricData
import com.ljyh.mei.ui.model.LyricSource
import com.ljyh.mei.utils.lyric.KaraokeRenderTimeWindow
import com.ljyh.mei.utils.lyric.karaokeRenderTimeWindow
import com.ljyh.mei.utils.lyric.lyricStorageKey
import com.ljyh.mei.utils.rememberEnumPreference
import com.ljyh.mei.utils.rememberPreference
import com.ljyh.mei.utils.setClipboard
import com.mocharealm.accompanist.lyrics.core.model.ISyncedLine
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeAlignment
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import com.mocharealm.accompanist.lyrics.core.model.synced.SyncedLine
import com.mocharealm.accompanist.lyrics.ui.composable.lyrics.calculateBalancedLines
import com.mocharealm.accompanist.lyrics.ui.composable.lyrics.calculateStaticLineLayout
import com.mocharealm.accompanist.lyrics.ui.composable.lyrics.measureSyllablesAndDetermineAnimation
import com.mocharealm.accompanist.lyrics.ui.utils.isRtl
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.absoluteValue
import kotlin.math.roundToInt

private const val LyricRenderFrameIntervalNanos = 16_666_667L

@OptIn(UnstableApi::class)
@Composable
fun LyricScreen(
    lyricData: LyricData,
    modifier: Modifier = Modifier,
    playerConnection: PlayerConnection,
    onClick: (LyricSource) -> Unit,
    onLongClick: (LyricSource) -> Unit,
    controlsVisible: Boolean,
    onToggleControls: ((Boolean) -> Unit)? = null,
    active: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    sourceBadgeEndOffset: Dp = 0.dp,
    sourceBadgeBottomPadding: Dp = 8.dp,
    sourceBadgeAnchor: Offset? = null,
    sourceBadgeAnchorProgress: () -> Float = { 1f },
    sourceBadgeHorizontalAnchorX: Float? = null,
    topFadeHeight: Dp = 20.dp,
    bottomFadeHeight: Dp = 100.dp,
    synchronizeEntryWithParent: Boolean = false,
    lyricContentModifier: Modifier = Modifier,
    rendererVerticalPadding: Dp = 8.dp,
    sourceBadgeBottomInset: () -> Dp = { 0.dp },
    controlsFadeRange: (Float) -> ClosedFloatingPointRange<Float>? = { null },
    showSourceBadge: Boolean = true,
    controlsAutoHideBlocked: Boolean = false,
    lyricOffset: Dp = 48.dp,
    centerMainLyric: Boolean = false,
    mainLyricAnchorFraction: Float? = null,
) {
    val context = LocalContext.current
    val layoutDirection = LocalLayoutDirection.current
    val sheet = LocalPlayerSheet.current
    val expanded by remember(sheet) { derivedStateOf { sheet == null || sheet.state.isExpanded } }
    val contentVisible by remember(sheet) { derivedStateOf { sheet == null || sheet.state.isContentVisible } }
    val isPlaying by playerConnection.isPlaying.collectAsStateWithLifecycle()
    val (normalLyricTextSize, _) = rememberEnumPreference(
        NormalLyricTextSizeKey,
        LyricTextSize.Size28
    )
    val (primaryWeight, _) = rememberPreference(NormalLyricTextWeightKey, 700)
    val (translationLyricTextSize, _) = rememberEnumPreference(
        AccompanimentLyricTextSizeKey,
        LyricTextSize.Size18
    )
    val (translationWeight, _) = rememberPreference(TranslationLyricTextWeightKey, 700)
    val (translationEnabled, _) = rememberPreference(LyricTranslationEnabledKey, true)
    val (romanizationEnabled, _) = rememberPreference(LyricRomanizationEnabledKey, true)
    val (glowEnabled, _) = rememberPreference(LyricGlowEnabledKey, true)
    val (liftEnabled, _) = rememberPreference(LyricLiftEnabledKey, true)
    val (longToneEnabled, _) = rememberPreference(LyricLongToneEnabledKey, true)
    val mainTextStyle = LocalTextStyle.current.copy(
        fontSize = normalLyricTextSize.text.coerceIn(16, 48).sp,
        fontWeight = FontWeight(primaryWeight.coerceIn(100, 900)),
        textMotion = TextMotion.Animated,
    )
    val translationTextStyle = LocalTextStyle.current.copy(
        fontSize = translationLyricTextSize.text.coerceIn(16, 36).sp,
        fontWeight = FontWeight(translationWeight.coerceIn(100, 900)),
        textMotion = TextMotion.Animated,
    )
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val mainLyricAnchor = mainLyricAnchorFraction ?: if (centerMainLyric) 0.5f else null
    val autoMix = LocalAutoMixVisuals.current?.takeIf { active && contentVisible }
    val lyricManager = playerConnection.service.lyricManager
    val incomingMetadata = autoMix?.incoming
    val incomingLyrics = if (incomingMetadata != null) {
        val preloaded = remember(lyricManager, incomingMetadata.trackRef) {
            lyricManager.preloadedLyrics(incomingMetadata)
        }
        preloaded.collectAsStateWithLifecycle(initialValue = null).value
    } else null
    var retainedIncoming by remember(playerConnection) { mutableStateOf<Pair<String, LyricData>?>(null) }
    val promotedIncoming = retainedIncoming?.takeIf {
        autoMix == null && playerConnection.player.currentMediaItem?.metadata?.lyricStorageKey() == it.first
    }
    val foregroundLyricsReady = promotedIncoming != null &&
        lyricManager.songId == promotedIncoming.first && lyricData == lyricManager.lyricData.value &&
        lyricData.source != LyricSource.Loading
    val displayedLyrics = when {
        promotedIncoming == null -> lyricData
        !foregroundLyricsReady -> promotedIncoming.second
        // Keep the promoted SyncedLyrics object identity when foreground resolves identical lines
        // (e.g. non-zero offset re-derivation or DB reload), avoiding a teardown/fade blink.
        lyricData.lyricLine == promotedIncoming.second.lyricLine ->
            lyricData.copy(lyricLine = promotedIncoming.second.lyricLine)
        else -> lyricData
    }
    val foregroundLyricKey = promotedIncoming?.first
        ?: lyricManager.songId.takeIf { displayedLyrics == lyricManager.lyricData.value }
    val incomingVisible by remember(autoMix, incomingLyrics) {
        derivedStateOf { autoMix != null && incomingLyrics != null && autoMix.lyricProgress() > 0f }
    }
    val incomingDominant by remember(autoMix, incomingLyrics) {
        derivedStateOf { autoMix != null && incomingLyrics != null && autoMix.lyricProgress() >= 0.5f }
    }
    val foregroundInteractive = foregroundLyricKey != null && !incomingDominant
    fun canActOnForegroundLyrics(): Boolean =
        foregroundLyricKey != null && !incomingDominant &&
            playerConnection.player.currentMediaItem?.metadata?.lyricStorageKey() == foregroundLyricKey

    SideEffect {
        if (incomingMetadata != null && incomingLyrics != null) {
            retainedIncoming = incomingMetadata.lyricStorageKey() to incomingLyrics
        } else if (promotedIncoming == null ||
            (foregroundLyricsReady && lyricData.lyricLine != promotedIncoming.second.lyricLine)
        ) {
            retainedIncoming = null
        }
    }
    var lyricWidthPx by remember { mutableIntStateOf(0) }
    val manualScroll = remember(displayedLyrics.lyricLine, playerConnection.player) {
        LyricManualScrollState()
    }
    val trackManualScroll by rememberUpdatedState(active && contentVisible)

    if (onToggleControls != null) {
        PlayerLyricsControlsAutoHide(
            controlsVisible, expanded, active, isPlaying, controlsAutoHideBlocked,
            onHide = { onToggleControls(false) },
        )
    }


    val nestedScrollConnection = remember(manualScroll, onToggleControls) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput) {
                    val delta = available.y
                    if (delta != 0f && trackManualScroll) {
                        manualScroll.onUserScroll()
                    }
                    if (delta < -10) {
                        onToggleControls?.invoke(false)
                    } else if (delta > 10) {
                        onToggleControls?.invoke(true)
                    }
                }
                return Offset.Zero
            }
        }
    }

    val revealControls = if (onToggleControls != null) {
        Modifier.clickable(
            indication = null,
            interactionSource = remember { MutableInteractionSource() },
        ) { onToggleControls(true) }
    } else Modifier
    Column(modifier.fillMaxSize().nestedScroll(nestedScrollConnection).then(revealControls)) {
        var badgeBounds by remember { mutableStateOf<Rect?>(null) }
        var badgeHostCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
        val controlsGesture = if (onToggleControls != null) {
            Modifier.pointerInput(controlsVisible, onToggleControls, showSourceBadge) {
                val badgeSlop = 12.dp.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown(
                        requireUnconsumed = false,
                        pass = PointerEventPass.Initial,
                    )
                    val lowerHalf = down.position.y >= size.height / 2f
                    val inBadge = showSourceBadge && badgeBounds?.inflate(badgeSlop)?.contains(down.position) == true
                    var moved = false
                    var released = false
                    var pressed = true
                    while (pressed && !released) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                            moved = true
                        }
                        if (change.changedToUpIgnoreConsumed()) {
                            released = true
                            if (lowerHalf && !moved && !inBadge) {
                                change.consume()
                                onToggleControls(!controlsVisible)
                            }
                        }
                        pressed = event.changes.any { it.pressed }
                    }
                }
            }
        } else Modifier
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .onGloballyPositioned { badgeHostCoordinates = it }
                .then(controlsGesture),
        ) {
            if (displayedLyrics.lyricLine.lines.isNotEmpty()) {
                key(System.identityHashCode(displayedLyrics.lyricLine)) {
                    val player = playerConnection.player
                    val lines = displayedLyrics.lyricLine.lines
                    val accompanimentToMainMap = remember(lines) { lyricAccompanimentOwners(lines) }
                    val quietIntervals = remember(lines) { lyricQuietIntervals(lines, accompanimentToMainMap) }
                    val motionBoundaries = remember(lines) { lyricMotionBoundaries(lines) }
                    val hasKaraoke = remember(lines, displayedLyrics.isVerbatim) {
                        displayedLyrics.isVerbatim && lines.any { it.hasTimedVocals() }
                    }
                    val renderWindows = remember(lines, hasKaraoke) { timedLyricRenderWindows(lines, hasKaraoke) }
                    val renderStarts = remember(renderWindows) {
                        renderWindows.mapNotNull { it?.first }.distinct().sorted()
                    }
                    val hasFrameMotion = hasKaraoke || quietIntervals.isNotEmpty()
                    // Warm the visible window near playback even before this host is shown.
                    val initialPosition = remember(player) { player.currentPosition.coerceAtLeast(0L) }
                    val listState = remember(player) {
                        val initialIndex = lyricFocusLineIndex(lines, initialPosition.toInt(), accompanimentToMainMap)
                        val initialScrollOffset = if (mainLyricAnchor == null || lyricWidthPx <= 0) 0 else {
                            mainLyricAnchorOffset(
                                lines[initialIndex], displayedLyrics.isVerbatim, mainTextStyle,
                                romanizationEnabled, lyricWidthPx, textMeasurer, density, mainLyricAnchor,
                            ).roundToInt()
                        }
                        LazyListState(
                            firstVisibleItemIndex = initialIndex,
                            firstVisibleItemScrollOffset = initialScrollOffset,
                        )
                    }
                    // Rows revealed by a within-screen jump need an existing placement
                    // to animate from. Keep a full window laid out beyond each edge.
                    val keepAliveZone = LocalConfiguration.current.screenHeightDp.dp
                    val keepAliveZonePx = with(LocalDensity.current) { keepAliveZone.roundToPx() }
                    val lyricOffsetPx = with(LocalDensity.current) { lyricOffset.roundToPx() }
                    var focusPosition by remember(player) { mutableIntStateOf(initialPosition.toInt()) }
                    var renderPosition by remember(player) { mutableIntStateOf(initialPosition.toInt()) }
                    var placementGeneration by remember(player) { mutableIntStateOf(0) }
                    var enterAtFullAlpha by remember(player) { mutableStateOf(promotedIncoming != null) }
                    val lyricAlpha = remember(player) { Animatable(if (enterAtFullAlpha) 1f else 0f) }
                    LifecycleStartedEffect(listState, manualScroll, active, contentVisible) {
                        if (!active || !contentVisible) return@LifecycleStartedEffect
                        try {
                            coroutineScope {
                                launch {
                                    listState.interactionSource.interactions.collect { interaction ->
                                        when (interaction) {
                                            is DragInteraction.Start -> manualScroll.onDragStarted()
                                            is DragInteraction.Stop, is DragInteraction.Cancel -> manualScroll.onDragStopped()
                                        }
                                    }
                                }
                                manualScroll.followAfterManualScroll {
                                    snapshotFlow { listState.isScrollInProgress }.first { !it }
                                }
                            }
                        } finally {
                            manualScroll.onHidden()
                        }
                    }
                    LifecycleStartedEffect(player, keepAliveZonePx, lyricOffsetPx, manualScroll, contentVisible, active, synchronizeEntryWithParent, hasKaraoke,
                        mainLyricAnchor, lyricWidthPx, mainTextStyle, romanizationEnabled, displayedLyrics.isVerbatim, textMeasurer, density) {
                        if (!contentVisible || !active) return@LifecycleStartedEffect
                        val skipEntryFade = enterAtFullAlpha
                        enterAtFullAlpha = false
                        var renderFrames: Job? = null
                        fun stopRenderFrames() {
                            renderFrames?.cancel()
                            renderFrames = null
                        }
                        val wakeSignal = Channel<Unit>(Channel.CONFLATED)
                        val playerListener = object : Player.Listener {
                            override fun onEvents(player: Player, events: Player.Events) {
                                // Keep the outgoing rows on their old clock until the jump commits.
                                if (hasFrameMotion && events.contains(Player.EVENT_POSITION_DISCONTINUITY)) {
                                    stopRenderFrames()
                                }
                                if (events.containsAny(
                                        Player.EVENT_IS_PLAYING_CHANGED,
                                        Player.EVENT_POSITION_DISCONTINUITY,
                                        Player.EVENT_PLAYBACK_PARAMETERS_CHANGED,
                                        Player.EVENT_PLAYBACK_STATE_CHANGED,
                                        Player.EVENT_TIMELINE_CHANGED,
                                    )
                                ) {
                                    wakeSignal.trySend(Unit)
                                }
                            }
                        }
                        player.addListener(playerListener)
                        try {
                            // A retained host must position before its old frame becomes visible.
                            if (!skipEntryFade) lyricAlpha.snapTo(0f)
                            // Measure the actual viewport before deciding whether entry may animate.
                            snapshotFlow { listState.layoutInfo.visibleItemsInfo.isNotEmpty() }
                                .first { it }
                            var lastFocusIndex: Int? = null
                            var sampledBoundary = Int.MIN_VALUE
                            var sampledFocusIndex = 0
                            fun focusAt(position: Int): Int {
                                val boundary = lyricMotionBoundaryIndex(motionBoundaries, position)
                                if (boundary != sampledBoundary) {
                                    sampledBoundary = boundary
                                    sampledFocusIndex = lyricFocusLineIndex(lines, position, accompanimentToMainMap)
                                }
                                return sampledFocusIndex
                            }
                            var publishedBoundary = lyricMotionBoundaryIndex(motionBoundaries, focusPosition)
                            fun publishFocus(position: Int) {
                                val boundary = lyricMotionBoundaryIndex(motionBoundaries, position)
                                if (boundary != publishedBoundary) {
                                    publishedBoundary = boundary
                                    focusPosition = position
                                }
                            }
                            val mainOffsets = mutableMapOf<Int, Int>()
                            fun anchorOffset(index: Int): Int = if (mainLyricAnchor == null || lyricWidthPx <= 0) 0 else {
                                mainOffsets.getOrPut(index) {
                                    mainLyricAnchorOffset(lines[index], displayedLyrics.isVerbatim, mainTextStyle,
                                        romanizationEnabled, lyricWidthPx, textMeasurer, density, mainLyricAnchor).roundToInt()
                                }
                            }
                            var initialPositionPending = true
                            while (true) {
                                var position = player.currentPosition.coerceAtLeast(0L).toInt()
                                var focusIndex = focusAt(position)
                                var jumped = false
                                if (!manualScroll.isBrowsing && !listState.isScrollInProgress &&
                                    (focusIndex != lastFocusIndex || manualScroll.returnToPlaybackPending)
                                ) {
                                    val layout = listState.layoutInfo
                                    val viewportStart = layout.viewportStartOffset + keepAliveZonePx
                                    val viewportEnd = layout.viewportEndOffset - keepAliveZonePx
                                    var firstVisibleIndex: Int? = null
                                    var firstFullIndex: Int? = null
                                    var fullLineCount = 0
                                    // Clipped rows and the keep-alive area do not count as full rows.
                                    for (item in layout.visibleItemsInfo) {
                                        if (item.index >= lines.size || !isLyricLineVisible(
                                            item.offset, item.size, layout.viewportStartOffset,
                                            layout.viewportEndOffset, keepAliveZonePx,
                                        )) continue
                                        if (firstVisibleIndex == null) firstVisibleIndex = item.index
                                        if (item.offset >= viewportStart && item.offset + item.size <= viewportEnd) {
                                            if (firstFullIndex == null) firstFullIndex = item.index
                                            fullLineCount++
                                        }
                                    }
                                    val firstIndex = firstFullIndex ?: firstVisibleIndex
                                    val snap = firstIndex == null || shouldSnapLyricScroll(
                                        lines, firstIndex, focusIndex, fullLineCount.coerceAtLeast(1),
                                    )
                                    val positioned = runInterruptibleLyricJump(
                                        restoreVisibility = { lyricAlpha.snapTo(1f) },
                                    ) {
                                        if (snap && lyricAlpha.value > 0f &&
                                            !(initialPositionPending && (synchronizeEntryWithParent || skipEntryFade))
                                        ) {
                                            stopRenderFrames()
                                            lyricAlpha.animateTo(0f, tween(120))
                                        }
                                        // Seeking may continue while the old lyrics fade out.
                                        position = player.currentPosition.coerceAtLeast(0L).toInt()
                                        focusIndex = focusAt(position)
                                        // A gesture may start during the fade. Leave it in control.
                                        if (!manualScroll.isBrowsing && !listState.isScrollInProgress) {
                                            if (snap) {
                                                // Reset placement springs, retaining the text measurement cache.
                                                listState.scrollToItem(focusIndex, anchorOffset(focusIndex))
                                                publishFocus(position)
                                                placementGeneration++
                                                jumped = true
                                            } else {
                                                val currentLayout = listState.layoutInfo
                                                val target = currentLayout.visibleItemsInfo.firstOrNull {
                                                    it.index == focusIndex
                                                }
                                                if (target != null) {
                                                    val anchor = currentLayout.viewportStartOffset +
                                                        lyricOffsetPx + keepAliveZonePx - anchorOffset(focusIndex)
                                                    listState.scrollBy((target.offset - anchor).toFloat())
                                                } else {
                                                    listState.scrollToItem(focusIndex, anchorOffset(focusIndex))
                                                }
                                            }
                                            true
                                        } else {
                                            false
                                        }
                                    }
                                    if (positioned) {
                                        lastFocusIndex = focusIndex
                                        manualScroll.onPlaybackPositioned()
                                    }
                                }
                                publishFocus(position)
                                if (hasFrameMotion) {
                                    val advancesOnFrames = player.isPlaying &&
                                        (lyricQuietIntervalAt(quietIntervals, position) != null ||
                                            (hasKaraoke && hasVisibleTimedVocals(
                                                lines, listState, keepAliveZonePx, renderWindows, position,
                                            )))
                                    if (!advancesOnFrames) stopRenderFrames()
                                    if (renderFrames == null) {
                                        renderPosition = position
                                        if (advancesOnFrames) {
                                            renderFrames = launch {
                                                // Cap karaoke/breathing-dot ticks at 60 Hz so 120/144 Hz panels
                                                // avoid doubling offscreen HDR layer and snapshot overhead.
                                                runMeshFrames(
                                                    intervalNanos = LyricRenderFrameIntervalNanos,
                                                    needsAnimation = { isActive },
                                                ) {
                                                    renderPosition = player.currentPosition.coerceAtLeast(0L).toInt()
                                                }
                                            }
                                        }
                                    }
                                }
                                if (initialPositionPending || jumped) {
                                    val parentOwnsEntry = initialPositionPending && (synchronizeEntryWithParent || skipEntryFade)
                                    initialPositionPending = false
                                    // Keep the positioning frame hidden, then fade in while time
                                    // updates continue so karaoke highlighting stays responsive.
                                    withFrameNanos { }
                                    if (parentOwnsEntry) lyricAlpha.snapTo(1f)
                                    else launch { lyricAlpha.animateTo(1f, tween(180)) }
                                }
                                awaitLyricMotionUpdate(
                                    motionBoundaries,
                                    if (renderFrames == null) renderStarts else emptyList(),
                                    position,
                                    if (player.isPlaying) player.playbackParameters.speed else 0f,
                                    wakeSignal,
                                )
                            }
                        } finally {
                            player.removeListener(playerListener)
                            // STOP or collapse can interrupt an entry or seek fade while
                            // the frame clock is paused. Restore the layer before resuming.
                            withContext(NonCancellable) { lyricAlpha.snapTo(1f) }
                        }
                    }
                    key(keepAliveZonePx) {
                        Box(Modifier.fillMaxSize().padding(contentPadding)) {
                            val hdrAccent = rememberPlayerLyricHdrAccent(
                                enabled = active && contentVisible && expanded && foregroundInteractive &&
                                    displayedLyrics.isVerbatim && glowEnabled && longToneEnabled && autoMix == null,
                            )
                            PlayerLyricsView(
                                showTranslation = translationEnabled,
                                showPhonetic = romanizationEnabled,
                                isVerbatim = displayedLyrics.isVerbatim,
                                listState = listState,
                                isManualScrolling = manualScroll.isBrowsing && listState.isScrollInProgress,
                                isBrowsing = manualScroll.isBrowsing,
                                lyrics = displayedLyrics.lyricLine,
                                currentPosition = { focusPosition },
                                renderPosition = { if (hasFrameMotion) renderPosition else focusPosition },
                                renderWindows = renderWindows,
                                quietIntervals = quietIntervals,
                                glowEnabled = glowEnabled,
                                liftEnabled = liftEnabled,
                                longToneEnabled = longToneEnabled,
                                onLineClicked = { line ->
                                    if (canActOnForegroundLyrics()) {
                                        manualScroll.onLineSelected()
                                        playerConnection.player.seekTo(line.start.toLong())
                                        onToggleControls?.invoke(true)
                                    }
                                },
                                onLinePressed = press@{ line ->
                                    if (!canActOnForegroundLyrics()) return@press
                                    val result = when (line) {
                                        is KaraokeLine -> {
                                            line.syllables.joinToString("") { it.content } +
                                                line.translation?.takeIf(String::isNotBlank)?.let { "\n$it" }.orEmpty()
                                        }
                                        is SyncedLine -> {
                                            line.content +
                                                line.translation?.takeIf(String::isNotBlank)?.let { "\n$it" }.orEmpty()
                                        }
                                        else -> {
                                            Toast.makeText(context, "未知的歌词类型", Toast.LENGTH_SHORT).show()
                                            null
                                        }
                                    }

                                    result?.let {
                                        try {
                                            setClipboard(context, it, "lyric")
                                        } catch (e: Exception) {
                                            e.printStackTrace()
                                            Toast.makeText(context, "复制失败", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                                modifier = Modifier
                                    .padding(vertical = rendererVerticalPadding)
                                    .then(
                                        if (foregroundInteractive) Modifier
                                        else Modifier.clearAndSetSemantics { hideFromAccessibility() }
                                    )
                                    .onSizeChanged { lyricWidthPx = it.width }
                                    .playerAdditiveLayer(
                                        opacity = {
                                            // The former nested layers applied this entry fade twice.
                                            val fraction = lyricAlpha.value
                                            val incomingAlpha = if (autoMix != null && incomingLyrics != null) {
                                                autoMix.lyricProgress()
                                            } else 0f
                                            fraction * fraction * (1f - incomingAlpha)
                                        },
                                    ),
                                // Keep the second inset and its former buffer clip, without
                                // another full-viewport additive offscreen layer.
                                listModifier = Modifier
                                    .padding(vertical = rendererVerticalPadding)
                                    .clipToBounds(),
                                normalLineTextStyle = mainTextStyle,
                                accompanimentLineTextStyle = mainTextStyle.copy(fontSize = 18.sp),
                                translationLineTextStyle = translationTextStyle,
                                offset = lyricOffset,
                                keepAliveZone = keepAliveZone,
                                placementGeneration = placementGeneration,
                                topFadeHeight = topFadeHeight,
                                bottomFadeHeight = bottomFadeHeight,
                                contentModifier = lyricContentModifier,
                                controlsFadeRange = controlsFadeRange,
                                isInteractive = foregroundInteractive,
                                isolateFadeLayer = false,
                                hdrAccent = hdrAccent,
                            )
                            if (hdrAccent != null && Build.VERSION.SDK_INT >= 36) {
                                hdrAccent.Overlay(
                                    modifier = Modifier.matchParentSize(),
                                    topFadeHeight = topFadeHeight,
                                    bottomFadeHeight = bottomFadeHeight,
                                    controlsFadeRange = controlsFadeRange,
                                    isVisible = { lyricAlpha.value == 1f && !manualScroll.isBrowsing },
                                )
                            }
                        }
                    }
                }
            }

            if (autoMix != null && incomingLyrics != null) {
                // Keep the container sizing outside PlayerLyricsView's Crossfade so incoming
                // lyrics measure to the full viewport (matchParentSize inside Crossfade collapses to 0x0).
                Box(Modifier.fillMaxSize().padding(contentPadding)) {
                    IncomingLyricLayer(
                        lyricData = incomingLyrics,
                        positionMs = autoMix.incomingPositionMs,
                        trackFocus = isPlaying,
                        animateFrames = incomingVisible && isPlaying,
                        initialWidthPx = lyricWidthPx,
                        modifier = Modifier.fillMaxSize()
                            .padding(vertical = rendererVerticalPadding)
                            .then(
                                if (incomingDominant) Modifier
                                else Modifier.clearAndSetSemantics { hideFromAccessibility() },
                            )
                            .playerAdditiveLayer(opacity = autoMix::lyricProgress),
                        contentModifier = lyricContentModifier,
                        normalTextStyle = mainTextStyle,
                        translationTextStyle = translationTextStyle,
                        translationEnabled = translationEnabled,
                        romanizationEnabled = romanizationEnabled,
                        glowEnabled = glowEnabled,
                        liftEnabled = liftEnabled,
                        longToneEnabled = longToneEnabled,
                        lyricOffset = lyricOffset,
                        mainLyricAnchor = mainLyricAnchor,
                        rendererVerticalPadding = rendererVerticalPadding,
                        topFadeHeight = topFadeHeight,
                        bottomFadeHeight = bottomFadeHeight,
                        controlsFadeRange = controlsFadeRange,
                    )
                }
            }

            if (showSourceBadge && foregroundInteractive && displayedLyrics.lyricLine.lines.isNotEmpty()) {
                LyricSourceBadge(
                    source = displayedLyrics.source,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(bottom = sourceBadgeBottomPadding)
                        .offset {
                            val defaultX = sourceBadgeEndOffset.roundToPx()
                            val defaultY = -sourceBadgeBottomInset().roundToPx()
                            val host = badgeHostCoordinates
                            val anchor = sourceBadgeAnchor
                            if (host == null || !host.isAttached) {
                                IntOffset(defaultX, defaultY)
                            } else {
                                val badgeSize = 34.dp.roundToPx()
                                val isLtr = layoutDirection == LayoutDirection.Ltr
                                val left = if (isLtr) host.size.width - badgeSize else 0
                                val top = host.size.height - sourceBadgeBottomPadding.roundToPx() - badgeSize
                                val hostOrigin = host.positionInRoot()
                                lyricSourceBadgeOffset(
                                    defaultX = defaultX,
                                    badgeOrigin = Offset(left.toFloat(), top.toFloat()),
                                    anchor = anchor?.minus(hostOrigin),
                                    progress = sourceBadgeAnchorProgress(),
                                    isLtr = isLtr,
                                    horizontalAnchorX = sourceBadgeHorizontalAnchorX?.minus(hostOrigin.x),
                                    defaultY = defaultY,
                                )
                            }
                        }
                        .onGloballyPositioned { badgeBounds = it.boundsInParent() },
                    onClick = { if (canActOnForegroundLyrics()) onClick(it) },
                    onLongClick = { if (canActOnForegroundLyrics()) onLongClick(it) },
                )
            }
        }
    }
}

@Composable
internal fun IncomingLyricLayer(
    lyricData: LyricData,
    positionMs: () -> Long,
    trackFocus: Boolean,
    animateFrames: Boolean,
    initialWidthPx: Int = 0,
    modifier: Modifier,
    contentModifier: Modifier,
    normalTextStyle: TextStyle,
    translationTextStyle: TextStyle,
    translationEnabled: Boolean,
    romanizationEnabled: Boolean,
    glowEnabled: Boolean,
    liftEnabled: Boolean,
    longToneEnabled: Boolean,
    lyricOffset: Dp,
    mainLyricAnchor: Float?,
    rendererVerticalPadding: Dp,
    topFadeHeight: Dp,
    bottomFadeHeight: Dp,
    controlsFadeRange: (Float) -> ClosedFloatingPointRange<Float>?,
) {
    val lines = lyricData.lyricLine.lines
    if (lines.isEmpty()) return

    val position by rememberUpdatedState(positionMs)
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val hasKaraoke = remember(lines, lyricData.isVerbatim) {
        lyricData.isVerbatim && lines.any { it.hasTimedVocals() }
    }
    val renderWindows = remember(lines, hasKaraoke) { timedLyricRenderWindows(lines, hasKaraoke) }
    val renderStarts = remember(renderWindows) { renderWindows.mapNotNull { it?.first }.distinct().sorted() }
    var lyricTime by remember(lines) { mutableIntStateOf(position().toInt()) }
    var renderTime by remember(lines) { mutableIntStateOf(lyricTime) }
    val accompanimentOwners = remember(lines) { lyricAccompanimentOwners(lines) }
    val quietIntervals = remember(lines) { lyricQuietIntervals(lines, accompanimentOwners) }
    val motionBoundaries = remember(lines) { lyricMotionBoundaries(lines) }
    var lyricWidthPx by remember(lines) { mutableIntStateOf(initialWidthPx) }
    val listState = remember(lines) {
        val initialIndex = lyricFocusLineIndex(lines, position().toInt(), accompanimentOwners)
        val initialScrollOffset = if (mainLyricAnchor == null || lyricWidthPx <= 0) 0 else {
            mainLyricAnchorOffset(
                lines[initialIndex], lyricData.isVerbatim, normalTextStyle, romanizationEnabled,
                lyricWidthPx, textMeasurer, density, mainLyricAnchor,
            ).roundToInt()
        }
        LazyListState(
            firstVisibleItemIndex = initialIndex,
            firstVisibleItemScrollOffset = initialScrollOffset,
        )
    }
    LifecycleStartedEffect(lines, listState, trackFocus, animateFrames, hasKaraoke) {
        var renderFrames: Job? = null
        var lastBoundary = lyricMotionBoundaryIndex(motionBoundaries, lyricTime)
        do {
            val time = position().toInt()
            val boundary = lyricMotionBoundaryIndex(motionBoundaries, time)
            if (boundary != lastBoundary) {
                lastBoundary = boundary
                lyricTime = time
            }
            val advancesOnFrames = animateFrames && (lyricQuietIntervalAt(quietIntervals, time) != null ||
                (hasKaraoke && hasVisibleTimedVocals(lines, listState, 0, renderWindows, time)))
            if (!advancesOnFrames) {
                renderFrames?.cancel()
                renderFrames = null
            }
            if (renderFrames == null) {
                renderTime = time
                if (advancesOnFrames) {
                    renderFrames = launch {
                        runMeshFrames(
                            intervalNanos = LyricRenderFrameIntervalNanos,
                            needsAnimation = { isActive },
                        ) {
                            renderTime = position().toInt()
                        }
                    }
                }
            }
            if (trackFocus || animateFrames) awaitLyricMotionUpdate(
                motionBoundaries,
                if (animateFrames && renderFrames == null) renderStarts else emptyList(),
                time,
                1f,
            )
        } while ((trackFocus || animateFrames) && isActive)
    }
    LifecycleStartedEffect(
        listState, mainLyricAnchor, density, textMeasurer, lyricData.isVerbatim,
        normalTextStyle, romanizationEnabled,
    ) {
        snapshotFlow { lyricFocusLineIndex(lines, lyricTime, accompanimentOwners) to lyricWidthPx }
            .collect { (index, width) ->
                val anchor = if (mainLyricAnchor == null || width <= 0) 0 else {
                    mainLyricAnchorOffset(
                        lines[index], lyricData.isVerbatim, normalTextStyle, romanizationEnabled,
                        width, textMeasurer, density, mainLyricAnchor,
                    ).roundToInt()
                }
                listState.scrollToItem(index, anchor)
            }
    }
    PlayerLyricsView(
        listState = listState,
        lyrics = lyricData.lyricLine,
        currentPosition = { lyricTime },
        renderPosition = { renderTime },
        quietIntervals = quietIntervals,
        glowEnabled = glowEnabled,
        liftEnabled = liftEnabled,
        longToneEnabled = longToneEnabled,
        onLineClicked = {},
        onLinePressed = {},
        isManualScrolling = false,
        modifier = modifier.onSizeChanged { lyricWidthPx = it.width },
        listModifier = Modifier.padding(vertical = rendererVerticalPadding).clipToBounds(),
        normalLineTextStyle = normalTextStyle,
        accompanimentLineTextStyle = normalTextStyle.copy(fontSize = 18.sp),
        translationLineTextStyle = translationTextStyle,
        offset = lyricOffset,
        keepAliveZone = 0.dp,
        useBlurEffect = true,
        showTranslation = translationEnabled,
        showPhonetic = romanizationEnabled,
        isVerbatim = lyricData.isVerbatim,
        topFadeHeight = topFadeHeight,
        bottomFadeHeight = bottomFadeHeight,
        contentModifier = contentModifier,
        controlsFadeRange = controlsFadeRange,
        isInteractive = false,
        precomputeLineLayouts = false,
        isolateFadeLayer = false,
        renderWindows = renderWindows,
    )
}

private fun ISyncedLine.hasTimedVocals(): Boolean = this is KaraokeLine.MainKaraokeLine &&
    (syllables.isNotEmpty() || accompanimentLines?.any { it.syllables.isNotEmpty() } == true)

private fun timedLyricRenderWindows(
    lines: List<ISyncedLine>,
    hasKaraoke: Boolean,
): List<KaraokeRenderTimeWindow?> = if (hasKaraoke) {
    lines.map { line -> (line as? KaraokeLine.MainKaraokeLine)?.let(::karaokeRenderTimeWindow) }
} else emptyList()

private fun hasVisibleTimedVocals(
    lines: List<ISyncedLine>,
    listState: LazyListState,
    keepAliveZonePx: Int,
    renderWindows: List<KaraokeRenderTimeWindow?>,
    positionMs: Int,
): Boolean {
    val layout = listState.layoutInfo
    return layout.visibleItemsInfo.any { item ->
        isLyricLineVisible(item.offset, item.size, layout.viewportStartOffset, layout.viewportEndOffset, keepAliveZonePx) &&
            (renderWindows.getOrNull(item.index)?.canChangeAt(positionMs)
                ?: (lines.getOrNull(item.index)?.hasTimedVocals() == true))
    }
}

/** Matches the renderer's primary text bounds, excluding translation and line-level phonetics. */
internal fun mainLyricAnchorOffset(
    line: ISyncedLine,
    isVerbatim: Boolean,
    style: TextStyle,
    showPhonetic: Boolean,
    widthPx: Int,
    textMeasurer: TextMeasurer,
    density: Density,
    anchorFraction: Float = 0.5f,
): Float = with(density) {
    val textWidth = (widthPx - 32.dp.roundToPx()).coerceAtLeast(1)
    if (line is KaraokeLine && isVerbatim) {
        val mainStyle = style.copy(textDirection = TextDirection.Content)
        val phoneticStyle = style.copy(
            fontSize = (style.fontSize.value * 0.5f).coerceAtLeast(12f).sp,
            fontWeight = FontWeight.Normal,
            textDirection = TextDirection.Content,
        )
        val syllables = if (line.alignment == KaraokeAlignment.End) {
            line.syllables.dropLastWhile { it.content.isBlank() }
        } else line.syllables
        val layouts = measureSyllablesAndDetermineAnimation(
            syllables = syllables, textMeasurer = textMeasurer, style = mainStyle,
            phoneticStyle = phoneticStyle, isAccompanimentLine = false,
            spaceWidth = textMeasurer.measure(" ", mainStyle).size.width.toFloat(),
        )
        val wrapped = calculateBalancedLines(layouts, textWidth.toFloat(), textMeasurer, mainStyle)
        val positioned = calculateStaticLineLayout(
            wrappedLines = wrapped, isLineRightAligned = false, canvasWidth = textWidth.toFloat(),
            lineHeight = textMeasurer.measure("M", mainStyle).size.height.toFloat(),
            phoneticHeight = if (showPhonetic) textMeasurer.measure("M", phoneticStyle).size.height.toFloat() else 0f,
            isRtl = syllables.any { it.content.isRtl() },
        ).flatten()
        val top = positioned.minOfOrNull { it.position.y } ?: 0f
        val bottom = positioned.maxOfOrNull { it.position.y + it.textLayoutResult.size.height } ?: 0f
        return 8.dp.toPx() + top + (bottom - top) * anchorFraction
    }
    val text = when (line) {
        is SyncedLine -> line.content
        is KaraokeLine -> line.syllables.joinToString("") { it.content }
        else -> ""
    }
    val height = textMeasurer.measure(text, style.copy(lineHeight = 1.2.em),
        constraints = Constraints(maxWidth = textWidth)).size.height
    val topPadding = if (line is SyncedLine) 12.dp else 8.dp
    topPadding.toPx() + height * anchorFraction
}

/** Coordinates are local to the badge host; horizontal alignment survives footer motion. */
internal fun lyricSourceBadgeOffset(
    defaultX: Int,
    badgeOrigin: Offset,
    anchor: Offset?,
    progress: Float,
    isLtr: Boolean,
    horizontalAnchorX: Float? = null,
    defaultY: Int = 0,
): IntOffset {
    val direction = if (isLtr) 1f else -1f
    val fraction = progress.coerceIn(0f, 1f)
    val targetX = ((horizontalAnchorX ?: anchor?.x) ?: badgeOrigin.x) - badgeOrigin.x
    val x = if (horizontalAnchorX != null) {
        targetX * direction
    } else if (anchor != null) {
        defaultX + (targetX * direction - defaultX) * fraction
    } else defaultX.toFloat()
    val y = if (anchor != null) {
        defaultY + (anchor.y - badgeOrigin.y - defaultY) * fraction
    } else defaultY.toFloat()
    return IntOffset(x.roundToInt(), y.roundToInt())
}

internal suspend fun runInterruptibleLyricJump(
    restoreVisibility: suspend () -> Unit,
    jump: suspend () -> Boolean,
): Boolean = coroutineScope {
    var completed = false
    // A scroll mutation cancels its calling Job when a gesture wins. Isolate
    // that cancellation so playback updates and subsequent fades keep running.
    launch {
        try {
            completed = jump()
        } finally {
            if (!completed) {
                withContext(NonCancellable) { restoreVisibility() }
            }
        }
    }.join()
    completed
}

internal fun latestMainLyricLineIndices(lines: List<ISyncedLine>, positionMs: Int): List<Int> {
    var leftIndex = -1
    var rightIndex = -1
    lines.forEachIndexed { index, line ->
        if (line.start <= positionMs && line !is KaraokeLine.AccompanimentKaraokeLine) {
            if ((line as? KaraokeLine)?.alignment == KaraokeAlignment.End) {
                rightIndex = index
            } else {
                leftIndex = index
            }
        }
    }
    // Select before testing end times so a superseded long line cannot return in a gap.
    return listOf(leftIndex, rightIndex).filter { it >= 0 }.sorted()
}

internal fun lyricAccompanimentOwners(lines: List<ISyncedLine>): Map<Int, Int> {
    val mainIndices = lines.indices.filter { lines[it] !is KaraokeLine.AccompanimentKaraokeLine }
    return buildMap {
        lines.forEachIndexed { index, line ->
            if (line !is KaraokeLine.AccompanimentKaraokeLine) return@forEachIndexed
            val before = mainIndices.lastOrNull { it < index }
            val after = mainIndices.firstOrNull { it > index }
            val owner = when {
                before == null -> after
                after == null -> before
                (line.start - lines[before].start).absoluteValue <=
                    (lines[after].start - line.start).absoluteValue -> before
                else -> after
            }
            if (owner != null) put(index, owner)
        }
    }
}

internal fun activeMainLyricLineIndices(
    lines: List<ISyncedLine>,
    positionMs: Int,
    accompanimentToMainMap: Map<Int, Int> = lyricAccompanimentOwners(lines),
): List<Int> = latestMainLyricLineIndices(lines, positionMs).filter { index ->
    positionMs < lines[index].effectiveMainEnd() || accompanimentToMainMap.any { (accompanimentIndex, owner) ->
        val accompaniment = lines[accompanimentIndex]
        owner == index && positionMs >= accompaniment.start && positionMs < accompaniment.end
    }
}

private fun ISyncedLine.effectiveMainEnd(): Int = if (this is KaraokeLine.MainKaraokeLine) {
    maxOf(end, accompanimentLines?.maxOfOrNull { it.end } ?: end)
} else {
    end
}

internal fun lyricQuietIntervals(
    lines: List<ISyncedLine>,
    accompanimentToMainMap: Map<Int, Int> = lyricAccompanimentOwners(lines),
): List<IntRange> {
    val vocals = ArrayList<IntRange>(lines.size)
    val replacedAt = IntArray(lines.size)
    var nextLeft = Int.MAX_VALUE
    var nextRight = Int.MAX_VALUE
    // Once a new line starts on a side, an overlong older line cannot resume.
    for (index in lines.indices.reversed()) {
        val line = lines[index]
        if (line is KaraokeLine.AccompanimentKaraokeLine) continue
        val rightAligned = (line as? KaraokeLine)?.alignment == KaraokeAlignment.End
        val replaced = if (rightAligned) nextRight else nextLeft
        replacedAt[index] = replaced
        if (rightAligned) nextRight = minOf(nextRight, line.start)
        else nextLeft = minOf(nextLeft, line.start)
        val end = minOf(line.end, replaced)
        if (line.start < end) vocals += (line.start until end)
        if (line is KaraokeLine.MainKaraokeLine) {
            for (accompaniment in line.accompanimentLines.orEmpty()) {
                // Attached voices may start before the lead or follow it after a gap.
                val accompanimentEnd = minOf(accompaniment.end, replaced)
                if (accompaniment.start < accompanimentEnd) {
                    vocals += (accompaniment.start until accompanimentEnd)
                }
            }
        }
    }
    for ((index, owner) in accompanimentToMainMap) {
        val start = maxOf(lines[index].start, lines[owner].start)
        val end = minOf(lines[index].end, replacedAt[owner])
        if (start < end) vocals += (start until end)
    }
    vocals.sortBy { it.first }
    return buildList {
        var vocalEnd = 0
        for (voice in vocals) {
            if (voice.first.toLong() - vocalEnd > 5_000L) add(vocalEnd until voice.first)
            vocalEnd = maxOf(vocalEnd, voice.last + 1)
        }
    }
}

internal fun lyricQuietIntervalAt(quietIntervals: List<IntRange>, positionMs: Int): IntRange? {
    val index = quietIntervals.binarySearch { interval ->
        when {
            positionMs < interval.first -> 1
            positionMs > interval.last -> -1
            else -> 0
        }
    }
    return quietIntervals.getOrNull(index)
}

internal fun lyricMotionBoundaries(lines: List<ISyncedLine>): List<Int> = buildSet {
    for (line in lines) {
        add(line.start)
        add(line.end)
        if (line is KaraokeLine.MainKaraokeLine) {
            for (accompaniment in line.accompanimentLines.orEmpty()) {
                add(accompaniment.start)
                add(accompaniment.end)
            }
        }
    }
}.sorted()

internal fun lyricMotionBoundaryIndex(boundaries: List<Int>, positionMs: Int): Int {
    val index = boundaries.binarySearch(positionMs)
    return if (index >= 0) index else -index - 2
}

private suspend fun awaitLyricMotionUpdate(
    boundaries: List<Int>,
    renderStarts: List<Int>,
    positionMs: Int,
    playbackSpeed: Float,
    wakeSignal: Channel<Unit>? = null,
) {
    val nextFocus = boundaries.getOrNull(lyricMotionBoundaryIndex(boundaries, positionMs) + 1)
    val nextRender = renderStarts.getOrNull(lyricMotionBoundaryIndex(renderStarts, positionMs) + 1)
    val nextBoundary = if (nextFocus == null) nextRender else minOf(nextFocus, nextRender ?: nextFocus)
    val nearLyricBoundary = playbackSpeed > 0f && nextBoundary != null &&
        nextBoundary.toLong() - positionMs <= 66 * playbackSpeed
    // Approach lyric boundaries on display frames without sampling every frame between lines.
    if (nearLyricBoundary) {
        withFrameNanos { }
    } else {
        val waitMs = if (playbackSpeed > 0f) 50L else 250L
        if (wakeSignal != null) {
            withTimeoutOrNull(waitMs) { wakeSignal.receive() }
        } else {
            delay(waitMs)
        }
    }
}

internal fun lyricFocusLineIndex(
    lines: List<ISyncedLine>,
    positionMs: Int,
    accompanimentToMainMap: Map<Int, Int> = lyricAccompanimentOwners(lines),
): Int = activeMainLyricLineIndices(lines, positionMs, accompanimentToMainMap).firstOrNull()
    ?: upcomingMainLyricLineIndex(lines, positionMs)

internal fun upcomingMainLyricLineIndex(lines: List<ISyncedLine>, positionMs: Int): Int {
    val nextIndex = lines.indexOfFirst {
        it !is KaraokeLine.AccompanimentKaraokeLine && it.start > positionMs
    }
    return if (nextIndex >= 0) nextIndex else lines.indexOfLast {
        it !is KaraokeLine.AccompanimentKaraokeLine
    }.coerceAtLeast(0)
}

internal fun shouldSnapLyricScroll(
    lines: List<ISyncedLine>,
    fromIndex: Int,
    toIndex: Int,
    visibleLineCount: Int,
): Boolean {
    if (visibleLineCount <= 0) return false
    // Keep two extra main lyric rows beyond the visible window within animation range.
    var remaining = visibleLineCount + 2
    for (index in minOf(fromIndex, toIndex) until maxOf(fromIndex, toIndex)) {
        if (lines[index] !is KaraokeLine.AccompanimentKaraokeLine && --remaining == 0) return true
    }
    return false
}

internal fun isLyricLineVisible(
    itemOffset: Int,
    itemSize: Int,
    viewportStart: Int,
    viewportEnd: Int,
    keepAliveZonePx: Int,
): Boolean = itemSize > 0 &&
    itemOffset + itemSize > viewportStart + keepAliveZonePx &&
    itemOffset < viewportEnd - keepAliveZonePx

@Composable
internal fun PlayerLyricsControlsAutoHide(
    controlsVisible: Boolean,
    expanded: Boolean,
    active: Boolean,
    isPlaying: Boolean,
    blocked: Boolean,
    onHide: () -> Unit,
) {
    LifecycleStartedEffect(controlsVisible, expanded, active, isPlaying, blocked) {
        if (controlsVisible && expanded && active && isPlaying && !blocked) {
            delay(5_000)
            onHide()
        }
    }
}

@Composable
private fun LyricSourceBadge(
    source: LyricSource,
    modifier: Modifier = Modifier,
    onClick: (LyricSource) -> Unit,
    onLongClick: (LyricSource) -> Unit
) {
    Box(
        modifier = modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.15f))
            .combinedClickable(
                onClick = { onClick(source) },
                onLongClick = { onLongClick(source) }
            ),
        contentAlignment = Alignment.Center,
    ) {

        Icon(
            painter = painterResource(
                when (source) {
                    LyricSource.Empty, LyricSource.Loading, LyricSource.Provider -> R.drawable.empty
                    LyricSource.NetEaseCloudMusic -> R.drawable.netease
                    LyricSource.QQMusic -> R.drawable.qq
                    LyricSource.AM -> R.drawable.am
                }
            ),
            modifier = Modifier.size(22.dp),
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.8f)
        )
    }
}
