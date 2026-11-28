package com.ljyh.mei.ui.component.player.component.applemusic

import androidx.annotation.OptIn
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.util.UnstableApi
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.kyant.capsule.ContinuousRoundedRectangle
import com.ljyh.mei.constants.OriginalCoverKey
import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.playback.PlayerConnection
import com.ljyh.mei.source.api.TrackRef
import com.ljyh.mei.source.script.sourceTrackArtwork
import com.ljyh.mei.ui.component.player.PlayerArtworkTransition
import com.ljyh.mei.ui.component.sheet.LocalPlayerSheet
import com.ljyh.mei.ui.component.sheet.playerArtwork
import com.ljyh.mei.utils.image.saveImageToGallery
import com.ljyh.mei.utils.rememberPreference
import com.ljyh.mei.utils.size1600
import com.ljyh.mei.utils.smallImage
import kotlinx.coroutines.launch
import net.engawapg.lib.zoomable.rememberZoomState
import net.engawapg.lib.zoomable.zoomable

@OptIn(UnstableApi::class)
@Composable
fun Cover(
    playerConnection: PlayerConnection,
    mediaMetadata: MediaMetadata,
    isPlaying: Boolean,
    modifier: Modifier,
    pausedScale: Float = 0.75f,
    onClick: (() -> Unit)? = null,
    onDoubleClick: (Offset, Int) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    val sheet = LocalPlayerSheet.current
    val sheetExpanded by remember(sheet) {
        derivedStateOf { sheet == null || sheet.state.isExpanded }
    }
    val originalCover by rememberPreference(
        OriginalCoverKey,
        defaultValue = false
    )

    val coverShape = ContinuousRoundedRectangle(12.dp)
    val animatedCoverScale by rememberAppleMusicPlaybackScale(isPlaying, sheetExpanded, pausedScale)
    val coverScale = rememberUpdatedState(
        if (sheetExpanded) animatedCoverScale else if (isPlaying) 1f else pausedScale,
    )

    val currentOnClick by rememberUpdatedState(onClick)
    val currentOnDoubleClick by rememberUpdatedState(onDoubleClick)
    var showFullImage by remember { mutableStateOf(false) }
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier.graphicsLayer {
                scaleX = coverScale.value
                scaleY = coverScale.value
            }.playerArtwork(
                circle = false,
                shadowElevation = { 12.dp },
                backgroundColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                playbackScale = { coverScale.value },
            ).fillMaxSize()
                .aspectRatio(1f)
                .shadow(12.dp, coverShape)
                .pointerInput(onClick != null) {
                    detectTapGestures(
                        onTap = { currentOnClick?.invoke() },
                        onDoubleTap = if (currentOnClick == null) {
                            { offset -> currentOnDoubleClick(offset, size.width) }
                        } else null,
                        onLongPress = { showFullImage = true },
                    )
                },
            shape = coverShape,
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
        ) {
            PlayerArtworkTransition(
                coverUrl = mediaMetadata.coverUrl,
                modifier = Modifier.fillMaxSize(),
                transitionSpec = { fadeIn(tween(500)) togetherWith fadeOut(tween(500)) },
            ) { coverUrl ->
                val url = coverUrl.orEmpty()
                val trackRef = mediaMetadata.trackRef.takeIf { coverUrl == mediaMetadata.coverUrl }
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .placeholderMemoryCacheKey(url.smallImage())
                        .data(if (originalCover) url else url.size1600())
                        .sourceTrackArtwork(trackRef)
                        .crossfade(false)
                        .build(),
                    contentScale = ContentScale.Crop,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        if (showFullImage) {
            FullScreenImageViewer(
                imageUrl = mediaMetadata.coverUrl,
                trackRef = mediaMetadata.trackRef,
                onDismiss = { showFullImage = false }
            )
        }
    }
}

/**
 * 全屏图片查看器组件
 * 支持：双指缩放、拖拽、保存图片
 */
@Composable
fun FullScreenImageViewer(
    imageUrl: String,
    trackRef: TrackRef? = null,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { onDismiss() })
                }
        ) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(imageUrl)
                    .sourceTrackArtwork(trackRef)
                    .crossfade(true)
                    .build(),
                contentDescription = "Full Screen Image",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .zoomable(rememberZoomState())
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = { onDismiss() })
                    }
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .align(Alignment.TopCenter)
            ) {
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.TopStart)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = Color.White
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .align(Alignment.BottomCenter)
            ) {
                IconButton(
                    onClick = {
                        scope.launch {
                            saveImageToGallery(context, imageUrl)
                        }
                    },
                    modifier = Modifier.align(Alignment.BottomEnd)
                ) {
                    Icon(
                        imageVector = Icons.Default.SaveAlt,
                        contentDescription = "Save",
                        tint = Color.White
                    )
                }
            }
        }
    }
}
