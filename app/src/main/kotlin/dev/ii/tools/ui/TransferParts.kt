package dev.ii.tools.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ii.tools.R
import dev.ii.tools.link.FileLane

/**
 * How files travel between the phone and the PCs: *Safe* on the link's own socket,
 * *Faster* on a lane of their own ([FileLane]). The same preference in Settings and Share.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TransferModeRow() {
    val ctx = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val stored by FileLane.fast.collectAsStateWithLifecycle()
    val fast = stored ?: FileLane.isFast(ctx)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.join)).background(Layer.pane)) {
        ToolRow(
            title = stringResource(R.string.transfer_mode),
            summary = stringResource(if (fast) R.string.transfer_faster_body else R.string.transfer_safe_body),
            icon = if (fast) R.drawable.rounded_bolt else R.drawable.rounded_shield,
            tint = if (fast) Tint.PRIMARY else Tint.SECONDARY,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
        ) {
            listOf(false to R.string.transfer_safe, true to R.string.transfer_faster).forEachIndexed { i, (value, label) ->
                ToggleButton(
                    checked = fast == value,
                    onCheckedChange = {
                        if (fast != value) {
                            haptics.performHapticFeedback(HapticFeedbackType.ToggleOn)
                            FileLane.setFast(ctx, value)
                        }
                    },
                    shapes = if (i == 0) ButtonGroupDefaults.connectedLeadingButtonShapes() else ButtonGroupDefaults.connectedTrailingButtonShapes(),
                    modifier = Modifier.weight(1f).semantics { role = Role.RadioButton },
                ) {
                    Text(stringResource(label))
                }
            }
        }
    }
}
