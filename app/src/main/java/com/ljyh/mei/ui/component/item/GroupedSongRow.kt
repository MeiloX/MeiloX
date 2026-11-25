package com.ljyh.mei.ui.component.item

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.ljyh.mei.R
import com.ljyh.mei.ui.component.copyNameActions
import io.github.neoruaa.glasmio.core.IosTypography
import io.github.neoruaa.glasmio.core.LocalGlassColors
import io.github.neoruaa.glasmio.ui.GlasmioListRow

@Composable
internal fun GroupedSongRow(
    title: String,
    artist: String,
    leading: @Composable () -> Unit,
    onClick: () -> Unit,
    showTopSeparator: Boolean,
    subtitle: String? = artist,
    detail: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = LocalGlassColors.current
    val subtitleContent: (@Composable () -> Unit)? = subtitle?.let { text ->
        @Composable {
            MediaRowName(
                text,
                IosTypography.subheadline,
                colors.secondaryContent,
                R.string.artist_action_copy_name,
                copyName = artist,
            )
        }
    }
    GlasmioListRow(
        titleContent = {
            MediaRowName(title, IosTypography.body, colors.content, R.string.track_action_copy_name)
        },
        modifier = Modifier.copyNameActions(
            R.string.track_action_copy_name to title,
            R.string.artist_action_copy_name to artist,
            enabled = true,
        ),
        subtitleContent = subtitleContent,
        detail = detail,
        leading = leading,
        onClick = onClick,
        trailing = trailing,
        showTopSeparator = showTopSeparator,
    )
}
