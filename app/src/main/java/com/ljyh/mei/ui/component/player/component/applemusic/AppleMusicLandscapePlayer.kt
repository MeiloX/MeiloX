package com.ljyh.mei.ui.component.player.component.applemusic

import android.annotation.SuppressLint
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import com.ljyh.mei.constants.LandscapeMiniPlayerHeight
import com.ljyh.mei.ui.component.player.LocalPlayerLyricsRequest
import com.ljyh.mei.ui.component.player.MiniPlayer
import com.ljyh.mei.ui.component.player.component.FluidBackground
import com.ljyh.mei.ui.component.player.overlay.PlayerOverlayHandler
import com.ljyh.mei.ui.component.player.rememberPlayerSheetPresentation
import com.ljyh.mei.ui.component.player.state.PlayerStateContainer
import com.ljyh.mei.ui.component.sheet.BottomSheet
import com.ljyh.mei.ui.component.sheet.LocalPlayerSheet
import com.ljyh.mei.ui.component.sheet.isUsable
import com.ljyh.mei.ui.component.utils.rememberDeviceInfo
import io.github.neoruaa.glasmio.glass.LocalBlurBackdrop
import io.github.neoruaa.glasmio.glass.LocalGlassBackdrop
import io.github.neoruaa.glasmio.glass.rememberRasterizedBackdrop
import io.github.neoruaa.glasmio.glass.render.Backdrop
import io.github.neoruaa.glasmio.glass.render.backdrops.LayerBackdrop
import io.github.neoruaa.glasmio.glass.render.backdrops.layerBackdrop
import io.github.neoruaa.glasmio.glass.trackBackdropPosition
import io.github.neoruaa.glasmio.scaffold.GlasmioBottomSheetState
import io.github.neoruaa.glasmio.scaffold.HorizontalSwipeDirection
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@SuppressLint("ConfigurationScreenWidthHeight")
@OptIn(UnstableApi::class)
@Composable
fun AppleMusicLandscapePlayer(
    state: GlasmioBottomSheetState,
    modifier: Modifier = Modifier,
    stateContainer: PlayerStateContainer,
    overlayHandler: PlayerOverlayHandler,
    collapsedBackdrop: Backdrop,
    playerBackgroundBackdrop: LayerBackdrop,
    playerContentBackdrop: LayerBackdrop,
    compactMiniPlayerProgress: State<Float>,
    miniPlayerVerticalOffset: () -> Dp,
) {
    val device = rememberDeviceInfo()
    val sheetPresentation = rememberPlayerSheetPresentation(state)
    val playerVisible = sheetPresentation.isContentVisible
    val queueBackdrop = rememberRasterizedBackdrop(playerBackgroundBackdrop, enabled = playerVisible)

    val sheetLayers = LocalPlayerSheet.current
    val expandPlayer: () -> Unit = { sheetLayers?.expand() }
    val scope = rememberCoroutineScope()
    var pendingExpansion by remember { mutableStateOf<Job?>(null) }
    var narrowPage by remember { mutableStateOf(AppleMusicPlayerPage.Artwork) }
    val lyricsRequest = LocalPlayerLyricsRequest.current
    LaunchedEffect(lyricsRequest?.value) {
        if (lyricsRequest?.value == true) {
            narrowPage = AppleMusicPlayerPage.Lyrics
            stateContainer.landscapeQueueSelected.value = false
            stateContainer.landscapeDetailVisible.value = true
            lyricsRequest.value = false
        }
    }

    fun openLandscapeDetail(queueSelected: Boolean) {
        if (sheetLayers?.isTransitioning == true) return
        val previousBounds = sheetLayers?.fullArtworkBounds
        val needsArtworkPlacement = device.isTablet &&
            !state.isExpanded && !stateContainer.landscapeDetailVisible.value &&
            previousBounds?.isUsable() == true
        narrowPage = if (queueSelected) AppleMusicPlayerPage.Queue else AppleMusicPlayerPage.Lyrics
        stateContainer.landscapeQueueSelected.value = queueSelected
        stateContainer.landscapeDetailVisible.value = true
        if (pendingExpansion?.isActive == true) return
        if (needsArtworkPlacement) {
            pendingExpansion = scope.launch {
                // Freeze the transition target only after the selected layout has placed
                // its artwork. A frame delay alone does not acknowledge new geometry.
                snapshotFlow { sheetLayers.fullArtworkBounds }
                    .first { it != previousBounds }
                expandPlayer()
            }
        } else {
            expandPlayer()
        }
    }
    val openLandscapeLyrics: () -> Unit = { openLandscapeDetail(queueSelected = false) }
    val openLandscapeQueue: () -> Unit = { openLandscapeDetail(queueSelected = true) }
    DisposableEffect(overlayHandler, stateContainer, state, device.isTablet) {
        overlayHandler.onShowPlaylist = openLandscapeQueue
        onDispose {
            if (overlayHandler.onShowPlaylist === openLandscapeQueue) {
                overlayHandler.onShowPlaylist = null
            }
        }
    }

    val mediaMetadata by stateContainer.mediaMetadata
    val isPlaying by stateContainer.isPlaying
    BottomSheet(
        state = state,
        modifier = modifier,
        collapsedDragOffset = miniPlayerVerticalOffset,
        collapsedDragHeight = LandscapeMiniPlayerHeight,
        topDragHeight = if (device.isTablet) 96.dp else 0.dp,
        transitionBackdrop = collapsedBackdrop,
        onDismiss = {
            stateContainer.playerConnection.player.stop()
            stateContainer.playerConnection.player.clearMediaItems()
        },
        onHorizontalSwipe = if (sheetPresentation.isCollapsed) null else { direction ->
            when (direction) {
                HorizontalSwipeDirection.Left -> stateContainer.playerConnection.seekToNext()
                HorizontalSwipeDirection.Right -> stateContainer.playerConnection.seekToPrevious()
            }
        },
        backgroundContent = {
            FluidBackground(
                imageUrl = mediaMetadata?.coverUrl,
                trackRef = mediaMetadata?.trackRef,
                beatMeter = stateContainer.playerConnection.service.beatMeter,
                isPlaying = isPlaying,
                alpha = 1f,
                backdrop = playerBackgroundBackdrop,
                behindLyrics = if (device.isTablet) {
                    stateContainer.landscapeDetailVisible.value && !stateContainer.landscapeQueueSelected.value
                } else {
                    narrowPage == AppleMusicPlayerPage.Lyrics
                },
            )
        },
        collapsedContent = { gestures ->
            MiniPlayer(
                // These parameters are unused by the mini player.
                position = 0L,
                duration = 0L,
                backdrop = collapsedBackdrop,
                compactProgress = compactMiniPlayerProgress,
                gestureModifier = gestures,
                onClick = expandPlayer,
                onMoreClick = overlayHandler::showMoreAction,
                onLyricsClick = openLandscapeLyrics,
                onQueueClick = openLandscapeQueue,
            )
        }
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer()
                .layerBackdrop(playerContentBackdrop)
                .trackBackdropPosition(playerContentBackdrop),
        ) {
            if (device.isTablet) {
                AppleMusicTabletLayout(stateContainer, overlayHandler, queueBackdrop)
            } else {
                CompositionLocalProvider(
                    LocalGlassBackdrop provides queueBackdrop,
                    LocalBlurBackdrop provides queueBackdrop,
                ) {
                    NarrowLandscapePlayer(
                        stateContainer,
                        overlayHandler,
                        narrowPage,
                        active = playerVisible,
                        onPageSelected = { narrowPage = it },
                        artwork = { artworkModifier ->
                            mediaMetadata?.let { track ->
                                Cover(
                                    stateContainer.playerConnection,
                                    track,
                                    isPlaying,
                                    artworkModifier,
                                    pausedScale = 0.95f,
                                    onClick = { narrowPage = AppleMusicPlayerPage.Artwork },
                                )
                            }
                        },
                    )
                }
            }
        }
    }
}
