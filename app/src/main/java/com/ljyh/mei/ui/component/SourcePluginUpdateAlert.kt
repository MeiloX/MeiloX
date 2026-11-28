package com.ljyh.mei.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.ljyh.mei.R
import com.ljyh.mei.source.update.SourcePluginUpdate
import com.ljyh.mei.ui.component.utils.rememberAlertContent
import io.github.neoruaa.glasmio.core.IosTypography
import io.github.neoruaa.glasmio.core.LocalGlassColors
import io.github.neoruaa.glasmio.ui.GlasmioAlertButtonRole
import io.github.neoruaa.glasmio.ui.GlasmioAlertButtonSpec

@Composable
internal fun SourcePluginUpdateAlert(
    updates: List<SourcePluginUpdate>?,
    onDismiss: () -> Unit,
    onOpenMarket: () -> Unit,
) {
    val displayed = rememberAlertContent(updates?.takeIf { it.isNotEmpty() }) ?: return
    val sortedUpdates = remember(displayed) { displayed.sortedBy { it.automaticallyUpdated } }
    val allUpdated = displayed.all { it.automaticallyUpdated }
    UpdateAlertDialog(
        visible = !updates.isNullOrEmpty(),
        title = stringResource(if (allUpdated) R.string.source_updates_installed else R.string.source_updates_title),
        buttons = buildList {
            add(GlasmioAlertButtonSpec(
                label = stringResource(if (allUpdated) R.string.done else R.string.source_updates_later),
                role = GlasmioAlertButtonRole.Cancel,
                onClick = onDismiss,
            ))
            if (!allUpdated) {
                add(GlasmioAlertButtonSpec(
                    label = stringResource(R.string.source_updates_view),
                    onClick = {
                        onDismiss()
                        onOpenMarket()
                    },
                ))
            }
        },
    ) {
        LazyColumn(
            modifier = Modifier.weight(1f, fill = false).heightIn(max = 280.dp)
                .fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(sortedUpdates) { update ->
                Column(Modifier.semantics(mergeDescendants = true) {}) {
                    Text(update.name, style = IosTypography.body, color = LocalGlassColors.current.content)
                    Text(
                        text = if (update.automaticallyUpdated) {
                            stringResource(R.string.source_updates_automatic_version, update.version)
                        } else {
                            stringResource(R.string.source_updates_available_version, update.installedVersion, update.version)
                        },
                        style = IosTypography.footnote,
                        color = LocalGlassColors.current.secondaryContent,
                    )
                }
            }
        }
    }
}
