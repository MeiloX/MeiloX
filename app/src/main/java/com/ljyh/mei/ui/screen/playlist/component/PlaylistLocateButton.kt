package com.ljyh.mei.ui.screen.playlist.component

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.layout
import androidx.compose.ui.zIndex
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import android.graphics.Shader
import kotlin.math.roundToInt
import com.ljyh.mei.R
import com.ljyh.mei.source.api.TrackRef
import com.ljyh.mei.ui.component.sheet.LocalMiniPlayerVerticalOffset
import com.ljyh.mei.ui.component.sheet.LocalMiniPlayerSearchBounds
import io.github.neoruaa.glasmio.glass.GlassSurfaceStyle
import io.github.neoruaa.glasmio.glass.LocalGlassBackdrop
import io.github.neoruaa.glasmio.icons.SfIcon
import io.github.neoruaa.glasmio.icons.LocalSfToolbarSymbols
import io.github.neoruaa.glasmio.ui.CompactBottomControlSize
import io.github.neoruaa.glasmio.ui.GlasmioIconButton
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

private const val LocateButtonIdleMillis = 3_000L
private const val LocateLayoutTimeoutMillis = 10_000L

internal class PlaylistLocateRegistration(
    val listState: LazyListState,
) {
    var track by mutableStateOf<TrackRef?>(null)
    var leadingItems by mutableIntStateOf(0)
    var modifier: Modifier = Modifier
    var available by mutableStateOf(false)
    var visible by mutableStateOf(true)
    var locating by mutableStateOf(false)
    var resolveIndex: suspend () -> Int? = { null }
    val shouldShow: Boolean get() = (available && visible) || locating
}

internal class PlaylistLocateHostState {
    // Isolate each route's registration in its own MutableState slot so mounting or
    // updating a locator on one tab never invalidates retained sibling tabs.
    private val slots = mutableMapOf<Any, MutableState<PlaylistLocateRegistration?>>()
    var activeEntryKey by mutableStateOf<Any?>(null)
    var navigationVisible by mutableStateOf(true)

    private fun slot(entryKey: Any): MutableState<PlaylistLocateRegistration?> =
        slots.getOrPut(entryKey) { mutableStateOf(null) }

    val registration: PlaylistLocateRegistration?
        get() = registrationFor(activeEntryKey)

    fun registrationFor(entryKey: Any?): PlaylistLocateRegistration? =
        entryKey?.let { slot(it).value }

    fun register(entryKey: Any?, value: PlaylistLocateRegistration) {
        if (entryKey != null) {
            val state = slot(entryKey)
            if (state.value !== value) state.value = value
        }
    }

    fun unregister(entryKey: Any?, value: PlaylistLocateRegistration) {
        if (entryKey != null) {
            val state = slots[entryKey]
            if (state != null && state.value === value) state.value = null
        }
    }
}

internal val LocalPlaylistLocateHost =
    androidx.compose.runtime.staticCompositionLocalOf<PlaylistLocateHostState?> { null }

/** Retained navigation entries must never publish another entry's locator into their header. */
internal val LocalPlaylistLocateOwner =
    androidx.compose.runtime.staticCompositionLocalOf<Any?> { null }

@Composable
internal fun PlaylistLocateButton(
    listState: LazyListState,
    track: TrackRef?,
    leadingItems: Int,
    modifier: Modifier = Modifier,
    available: Boolean = track != null,
    findTrackIndex: suspend () -> Int?,
) {
    val context = LocalContext.current
    val host = LocalPlaylistLocateHost.current
    val owner = LocalPlaylistLocateOwner.current
    // Keep one stable registration across track switches so changing songs never tears down
    // the chrome layer, resets idle visibility, or causes a one-frame layout jump.
    val registration = remember(owner, listState) {
        PlaylistLocateRegistration(listState).apply {
            this.track = track
            this.leadingItems = leadingItems
            this.modifier = modifier
            this.available = available
        }
    }
    val resolveIndex by rememberUpdatedState(findTrackIndex)

    SideEffect {
        val trackChanged = registration.track != track
        registration.track = track
        registration.leadingItems = leadingItems
        registration.modifier = modifier
        registration.available = available
        registration.resolveIndex = resolveIndex
        if (trackChanged && registration.locating) {
            registration.locating = false
        }
        host?.register(owner, registration)
    }
    DisposableEffect(host, owner, registration) {
        onDispose { host?.unregister(owner, registration) }
    }

    LaunchedEffect(registration, available) {
        if (!available) return@LaunchedEffect
        registration.visible = true
        snapshotFlow { listState.isScrollInProgress }.collectLatest { scrolling ->
            if (scrolling) {
                registration.visible = true
            } else {
                delay(LocateButtonIdleMillis)
                registration.visible = false
            }
        }
    }

    LaunchedEffect(registration.locating, registration.track, listState, leadingItems) {
        if (!registration.locating) return@LaunchedEffect
        try {
            val index = registration.resolveIndex()?.plus(leadingItems)
            val layout = index?.let { targetIndex ->
                // The source can publish the row before the next lazy list layout.
                withTimeoutOrNull(LocateLayoutTimeoutMillis) {
                    snapshotFlow { listState.layoutInfo }.first { it.totalItemsCount > targetIndex }
                }
            }
            if (index == null || layout == null) {
                Toast.makeText(context, R.string.playlist_locate_track_failed, Toast.LENGTH_SHORT).show()
                return@LaunchedEffect
            }
            val usableHeight = (layout.viewportEndOffset - layout.afterContentPadding).coerceAtLeast(0)
            listState.animateScrollToItem(index, scrollOffset = -usableHeight / 3)
        } finally {
            registration.locating = false
        }
    }

    // The activity renders the registered control in the chrome layer. Keep a
    // fallback here for previews and isolated callers that do not provide a host.
    if (host == null || owner == null) {
        PlaylistLocateButtonChrome(registration, modifier, navigationVisible = true)
    }
}

@Composable
internal fun PlaylistLocateChromeLayer(
    host: PlaylistLocateHostState,
    bottomPadding: () -> Dp,
    topBarMode: Boolean = false,
) {
    host.registration?.let { registration ->
        PlaylistLocateButtonChrome(
            registration = registration,
            modifier = registration.modifier,
            bottomPadding = bottomPadding,
            navigationVisible = host.navigationVisible && !topBarMode,
        )
    }
}

@Composable
private fun PlaylistLocateButtonChrome(
    registration: PlaylistLocateRegistration,
    modifier: Modifier,
    bottomPadding: () -> Dp = { 0.dp },
    navigationVisible: Boolean,
) {
    val miniPlayerVerticalOffset = LocalMiniPlayerVerticalOffset.current
    val searchBoundsState = LocalMiniPlayerSearchBounds.current

    AnimatedVisibility(
        visible = navigationVisible && registration.shouldShow,
        enter = EnterTransition.None,
        exit = ExitTransition.None,
        modifier = modifier
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                layout(placeable.width, placeable.height) {
                    val searchBounds = searchBoundsState?.value
                    val rightInWindow = coordinates?.localToWindow(Offset(placeable.width.toFloat(), 0f))?.x
                    val x = if (searchBounds != null && rightInWindow != null) {
                        (searchBounds.right - rightInWindow).roundToInt()
                    } else {
                        0
                    }
                    val y = (miniPlayerVerticalOffset() - bottomPadding()).toPx().roundToInt()
                    placeable.place(x, y)
                }
            }
            .zIndex(0f),
    ) {
        val actionProgress = transition.animateFloat(
            transitionSpec = { spring(dampingRatio = 0.75f, stiffness = 228.1251f) },
            label = "PlaylistLocateToolbarAction",
        ) { state ->
            when (state) {
                EnterExitState.Visible -> 1f
                EnterExitState.PreEnter, EnterExitState.PostExit -> 0f
            }
        }
        val blurLayer = rememberGraphicsLayer()
        val blurPadPx = with(LocalDensity.current) { 32.dp.roundToPx() }
        Box(
            Modifier
                .graphicsLayer {
                    // Match GlasmioNavigationScaffold's group/member toolbar motion.
                    val p = actionProgress.value.coerceIn(0f, 1f)
                    val scale = .24f + .92f * p - .16f * p * p
                    alpha = p * p
                    scaleX = scale
                    scaleY = scale
                }
                .drawWithContent {
                    val p = actionProgress.value.coerceIn(0f, 1f)
                    val blur = 10.dp.toPx() * (1f - p)
                    if (blur <= .01f) {
                        drawContent()
                    } else {
                        val targetSize = IntSize(
                            size.width.roundToInt().coerceAtLeast(1) + blurPadPx * 2,
                            size.height.roundToInt().coerceAtLeast(1) + blurPadPx * 2,
                        )
                        blurLayer.clip = true
                        blurLayer.record(size = targetSize) {
                            drawContext.transform.translate(blurPadPx.toFloat(), blurPadPx.toFloat())
                            try {
                                this@drawWithContent.drawContent()
                            } finally {
                                drawContext.transform.translate(-blurPadPx.toFloat(), -blurPadPx.toFloat())
                            }
                        }
                        blurLayer.renderEffect = io.github.neoruaa.glasmio.glass.createAnimatedBlurComposeRenderEffect(
                            blur, Shader.TileMode.DECAL,
                        )
                        drawContext.transform.translate(-blurPadPx.toFloat(), -blurPadPx.toFloat())
                        try {
                            drawLayer(blurLayer)
                        } finally {
                            drawContext.transform.translate(blurPadPx.toFloat(), blurPadPx.toFloat())
                        }
                    }
                },
        ) {
            GlasmioIconButton(
                onClick = { registration.locating = true },
                backdrop = LocalGlassBackdrop.current,
                style = GlassSurfaceStyle.Navigation,
                nativeNavigationFlex = true,
                size = CompactBottomControlSize,
                enabled = !registration.locating,
            ) {
                CompositionLocalProvider(LocalSfToolbarSymbols provides true) {
                    if (registration.locating) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        SfIcon("scope", stringResource(R.string.playlist_locate_current_track))
                    }
                }
            }
        }
    }
}
