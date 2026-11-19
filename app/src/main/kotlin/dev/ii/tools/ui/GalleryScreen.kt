package dev.ii.tools.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.ii.tools.R
import dev.ii.tools.gallery.Gallery

/** Phone gallery on the PC: read-only access, the permission, and the switch. */
@Composable
fun GalleryScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var enabled by remember { mutableStateOf(Gallery.isEnabled(ctx)) }
    var imagesGranted by remember { mutableStateOf(Gallery.canReadImages(ctx)) }
    var permissionGranted by remember { mutableStateOf(Gallery.hasPermission(ctx)) }
    LifecycleResumeEffect(Unit) {
        imagesGranted = Gallery.canReadImages(ctx)
        permissionGranted = Gallery.hasPermission(ctx)
        Gallery.onPermissionChanged(ctx)
        onPauseOrDispose { }
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        imagesGranted = Gallery.canReadImages(ctx)
        permissionGranted = Gallery.hasPermission(ctx)
        Gallery.onPermissionChanged(ctx)
    }

    ToolScaffold(title = stringResource(R.string.gallery_title), onBack = onBack) {
        item { Hint(stringResource(R.string.gallery_hint)) }
        if (!permissionGranted) {
            item { GroupHeader(stringResource(R.string.setup_title)) }
            item {
                CardGroup {
                    PermissionRow(
                        title = stringResource(R.string.perm_gallery),
                        body = stringResource(R.string.perm_gallery_body),
                        icon = R.drawable.rounded_photo_library,
                        granted = false,
                        onGrant = { ask.launch(Gallery.PERMISSIONS) },
                    )
                }
            }
        }
        item {
            CardGroup {
                val toggle = { on: Boolean ->
                    enabled = on
                    Gallery.setEnabled(ctx, on)
                }
                SwitchRow(
                    title = stringResource(R.string.gallery_share),
                    checked = enabled,
                    onCheckedChange = toggle,
                    icon = R.drawable.rounded_photo_library,
                    enabled = imagesGranted,
                    summary = if (!imagesGranted) stringResource(R.string.settings_needs_gallery) else null,
                )
            }
        }
    }
}
