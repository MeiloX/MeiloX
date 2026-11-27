package com.ljyh.mei.ui.screen.source

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.ljyh.mei.data.model.supportsNeteaseSongActions
import com.ljyh.mei.source.api.TrackRef
import com.ljyh.mei.ui.local.LocalPlayerConnection
import com.ljyh.mei.ui.screen.playlist.component.PlaylistLocateButton
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

@Composable
internal fun SourceTrackLocateButton(
    viewModel: SourceContentViewModel,
    request: SourceContentRequest,
    state: SourceContentState,
    listState: LazyListState,
    leadingItems: Int,
    modifier: Modifier = Modifier,
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val track by remember(playerConnection) {
        playerConnection.mediaMetadata
            .map { metadata -> metadata?.takeIf { it.supportsNeteaseSongActions }?.trackRef }
            .distinctUntilChanged()
    }.collectAsState(
        initial = playerConnection.mediaMetadata.value?.takeIf { it.supportsNeteaseSongActions }?.trackRef,
    )
    val revision by viewModel.contentRevision.collectAsState()
    val loadedTrackIds = remember(state.items) {
        HashSet<TrackRef>(state.items.size).apply {
            state.items.forEach { item ->
                val ref = item.ref
                if (ref is TrackRef) add(ref)
            }
        }
    }
    // Cache the playlist's full track index once per owner so switching tracks on the
    // mini player resolves membership synchronously in O(1) without toggling availability.
    var indexState by remember(viewModel, request, state.providerVersion, state.accountRevision) {
        mutableStateOf<SourceContentViewModel.IndexedTrackSet?>(null)
    }
    val canIndex = state.hasMore && !state.refreshing
    LaunchedEffect(viewModel, request, state.providerVersion, state.accountRevision, state.refreshing, revision, canIndex) {
        if (canIndex) {
            indexState = viewModel.indexedTrackIds(request)
        }
    }
    val currentTrack = track
    val available = currentTrack != null && !state.refreshing && when {
        currentTrack in loadedTrackIds -> true
        !state.hasMore -> false
        else -> when (val index = indexState) {
            null, SourceContentViewModel.IndexedTrackSet.Unavailable -> false
            // When the source omits a complete index, the button can still search the available pages.
            is SourceContentViewModel.IndexedTrackSet.Unsupported -> currentTrack.providerId == index.providerId
            is SourceContentViewModel.IndexedTrackSet.Ids ->
                currentTrack.providerId == index.providerId && currentTrack in index.ids
        }
    }
    PlaylistLocateButton(
        listState = listState,
        track = currentTrack,
        leadingItems = leadingItems,
        modifier = modifier,
        available = available,
    ) {
        currentTrack?.let { viewModel.locateTrack(request, it) }
    }
}
