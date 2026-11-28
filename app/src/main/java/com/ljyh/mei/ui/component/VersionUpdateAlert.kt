package com.ljyh.mei.ui.component

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.DialogProperties
import com.ljyh.mei.BuildConfig
import com.ljyh.mei.R
import com.ljyh.mei.ui.component.utils.rememberAlertContent
import com.ljyh.mei.utils.VersionUpdateResult
import io.github.neoruaa.glasmio.ui.GlasmioAlertButtonRole
import io.github.neoruaa.glasmio.ui.GlasmioAlertButtonSpec
import io.github.neoruaa.glasmio.ui.GlasmioAlertDialog

@Composable
internal fun UpdateAlertDialog(
    visible: Boolean,
    title: String,
    message: String? = null,
    buttons: List<GlasmioAlertButtonSpec>,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    GlasmioAlertDialog(
        visible = visible,
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = title,
        message = message,
        buttons = buttons,
        content = content,
    )
}

@Composable
fun VersionUpdateAlert(
    result: VersionUpdateResult?,
    onDismiss: () -> Unit,
) {
    val visible = result != null
    val displayedResult = rememberAlertContent(result) ?: return
    val context = LocalContext.current
    when (val result = displayedResult) {
        is VersionUpdateResult.UpdateAvailable -> {
            UpdateAlertDialog(
                visible = visible,
                title = stringResource(
                    if (result.comparisonKnown) R.string.about_update_available_title
                    else R.string.about_beta_latest_build_title,
                ),
                message = stringResource(
                    if (result.comparisonKnown) R.string.about_update_available_message
                    else R.string.about_beta_comparison_unavailable_message,
                    result.latestTag,
                    result.currentBuildLabel ?: BuildConfig.VERSION_NAME,
                ),
                buttons = listOf(
                    GlasmioAlertButtonSpec(
                        label = stringResource(R.string.cancel),
                        role = GlasmioAlertButtonRole.Cancel,
                        onClick = onDismiss,
                    ),
                    GlasmioAlertButtonSpec(
                        label = stringResource(R.string.about_open_update),
                        onClick = {
                            onDismiss()
                            runCatching {
                                if (result.requiresLogin) {
                                    Toast.makeText(
                                        context,
                                        R.string.about_beta_download_login_required,
                                        Toast.LENGTH_LONG,
                                    ).show()
                                }
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(result.releaseUrl))
                                context.startActivity(Intent.createChooser(intent, null))
                            }
                        },
                    ),
                ),
            )
        }

        VersionUpdateResult.UpToDate -> {
            UpdateAlertDialog(
                visible = visible,
                title = stringResource(R.string.about_update_up_to_date_title),
                message = stringResource(
                    R.string.about_update_up_to_date_message,
                    BuildConfig.VERSION_NAME,
                ),
                buttons = listOf(
                    GlasmioAlertButtonSpec(
                        label = stringResource(R.string.done),
                        role = GlasmioAlertButtonRole.Cancel,
                        onClick = onDismiss,
                    ),
                ),
            )
        }

        VersionUpdateResult.Failed -> {
            UpdateAlertDialog(
                visible = visible,
                title = stringResource(R.string.about_update_failed_title),
                message = stringResource(R.string.about_update_failed_message),
                buttons = listOf(
                    GlasmioAlertButtonSpec(
                        label = stringResource(R.string.done),
                        role = GlasmioAlertButtonRole.Cancel,
                        onClick = onDismiss,
                    ),
                ),
            )
        }
    }
}
