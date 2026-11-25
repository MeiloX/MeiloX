package com.ljyh.mei.ui.component

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ljyh.mei.R
import com.ljyh.mei.utils.setClipboard
import io.github.neoruaa.glasmio.core.LocalGlassColors
import io.github.neoruaa.glasmio.core.LocalGlasmioTextStyles
import io.github.neoruaa.glasmio.glass.glasmioMenuGesture
import io.github.neoruaa.glasmio.glass.rememberGlasmioMenuAnchorState
import io.github.neoruaa.glasmio.ui.GlasmioPopover

@Composable
internal fun CopyNamePopover(
    name: String,
    @StringRes actionLabel: Int,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    accessibilityOnParent: Boolean = false,
    textAnchor: CopyNameAnchor? = null,
    content: @Composable (gesture: Modifier, popoverVisible: Boolean) -> Unit,
) {
    val context = LocalContext.current
    val accent = LocalGlassColors.current.accent
    val label = stringResource(actionLabel)
    val copyLabel = stringResource(R.string.name_copy_action)
    val confirmation = stringResource(R.string.name_copied)
    val anchor = rememberGlasmioMenuAnchorState()
    var visible by remember { mutableStateOf(false) }
    var capturedName by remember { mutableStateOf(name) }
    var capturedBounds by remember { mutableStateOf<Rect?>(null) }
    val openPopover = {
        capturedName = name
        capturedBounds = textAnchor?.boundsInWindow()
        visible = true
        true
    }
    val gesture = if (enabled && name.isNotBlank()) {
        Modifier.glasmioMenuGesture(anchor) { openPopover() }.then(
            if (accessibilityOnParent) Modifier else {
                Modifier.semantics(mergeDescendants = true) {
                    onLongClick(label, openPopover)
                }
            },
        )
    } else Modifier

    GlasmioPopover(
        visible = visible,
        onDismissRequest = { visible = false },
        preferBelowAnchor = true,
        anchorBoundsInWindow = capturedBounds,
        modifier = modifier,
        adaptToSheet = false,
        title = label,
        anchor = { content(gesture, visible) },
    ) {
        val bodyStyle = LocalGlasmioTextStyles.current.body
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = capturedName,
                modifier = Modifier.weight(1f, fill = false),
                style = bodyStyle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            VerticalDivider(
                modifier = Modifier.height(14.dp),
                color = LocalContentColor.current.copy(alpha = 0.38f),
            )
            Text(
                text = copyLabel,
                color = accent,
                style = bodyStyle,
                modifier = Modifier.clickable(role = Role.Button, onClickLabel = label) {
                    visible = false
                    setClipboard(context, capturedName, label, confirmation)
                },
            )
        }
    }
}

internal class CopyNameAnchor {
    private var coordinates: LayoutCoordinates? = null
    private var lineBounds: Rect? = null

    val modifier = Modifier.onGloballyPositioned { coordinates = it }

    fun updateLayout(layout: TextLayoutResult) {
        lineBounds = Rect(
            layout.getLineLeft(0), 0f,
            layout.getLineRight(0), layout.size.height.toFloat(),
        )
    }

    fun boundsInWindow(): Rect? {
        val positioned = coordinates?.takeIf { it.isAttached } ?: return null
        val visibleBounds = positioned.boundsInWindow()
        val line = lineBounds ?: return visibleBounds.takeUnless { it.isEmpty }
        val textBounds = Rect(
            positioned.localToWindow(Offset(line.left, line.top)),
            positioned.localToWindow(Offset(line.right, line.bottom)),
        )
        return textBounds.intersect(visibleBounds).takeUnless { it.isEmpty }
    }
}

@Composable
internal fun Modifier.copyNameActions(
    vararg names: Pair<Int, String>,
    enabled: Boolean,
): Modifier {
    if (!enabled) return this
    val context = LocalContext.current
    val confirmation = stringResource(R.string.name_copied)
    val actions = names.filter { it.second.isNotBlank() }.map { (labelRes, value) ->
        val label = stringResource(labelRes)
        CustomAccessibilityAction(label) {
            setClipboard(context, value, label, confirmation)
            true
        }
    }
    return if (actions.isEmpty()) this else semantics { customActions = actions }
}
