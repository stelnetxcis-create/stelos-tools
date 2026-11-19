package dev.ii.tools.ui

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ii.tools.R
import dev.ii.tools.link.LinkHub
import dev.ii.tools.link.LinkStatus
import dev.ii.tools.link.Tailnet
import kotlinx.coroutines.launch

/** "Connected", or "Connected via Tailscale" / "Connected · <address>". */
@Composable
fun onlineLabel(host: String?, withHost: Boolean = true): String = when {
    Tailnet.isTailnet(host) -> stringResource(R.string.status_online_tailnet)
    withHost && host != null -> stringResource(R.string.status_online) + " · " + host
    else -> stringResource(R.string.status_online)
}

/**
 * On a PC that is away and that its shell lets the phone reach over Tailscale: turn the
 * Tailscale app's VPN on and try again (or get the app, when it is missing). Hidden while
 * Tailscale is already up, or when the PC announced no tailnet address.
 */
@Composable
fun TailnetButton(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val pc by LinkHub.pc.collectAsStateWithLifecycle()
    val status by LinkHub.status.collectAsStateWithLifecycle()
    var up by remember { mutableStateOf(Tailnet.isUp(ctx)) }
    var installed by remember { mutableStateOf(Tailnet.installed(ctx)) }
    var working by remember { mutableStateOf(false) }
    LifecycleResumeEffect(status) {
        up = Tailnet.isUp(ctx)
        installed = Tailnet.installed(ctx)
        onPauseOrDispose { }
    }
    if (pc?.tailnet.isNullOrEmpty() || up || status is LinkStatus.Online) return
    if (!installed) {
        DashedPill(stringResource(R.string.tailnet_install), onClick = { Tailnet.openStore(ctx) }, icon = R.drawable.rounded_public, height = 52.dp, modifier = modifier)
        return
    }
    TonalPill(
        stringResource(if (working) R.string.tailnet_connecting else R.string.tailnet_connect),
        onClick = {
            if (working) return@TonalPill
            working = true
            scope.launch {
                val why = LinkHub.connectTailnet()
                working = false
                up = Tailnet.isUp(ctx)
                if (why != null) Toast.makeText(ctx, R.string.tailnet_failed, Toast.LENGTH_LONG).show()
            }
        },
        icon = R.drawable.rounded_public,
        height = 52.dp,
        modifier = modifier,
    )
}

/** Settings and the away home: let the app turn Tailscale on by itself away from the PC's LAN. */
@Composable
fun TailnetAutoRow() {
    val ctx = LocalContext.current
    val machines by LinkHub.machines.collectAsStateWithLifecycle()
    var on by remember { mutableStateOf(Tailnet.auto(ctx)) }
    var installed by remember { mutableStateOf(Tailnet.installed(ctx)) }
    LifecycleResumeEffect(Unit) {
        installed = Tailnet.installed(ctx)
        onPauseOrDispose { }
    }
    val anyTailnet = machines.any { it.pc.tailnet.isNotEmpty() }
    SwitchRow(
        stringResource(R.string.tailnet_auto),
        on,
        {
            on = it
            Tailnet.setAuto(ctx, it)
        },
        R.drawable.rounded_public,
        summary = stringResource(
            when {
                !installed -> R.string.tailnet_needs_app
                !anyTailnet -> R.string.tailnet_needs_pc
                else -> R.string.tailnet_auto_body
            },
        ),
        enabled = installed,
    )
}
