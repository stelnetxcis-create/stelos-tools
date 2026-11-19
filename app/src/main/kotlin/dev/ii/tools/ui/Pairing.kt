@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.ii.tools.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import dev.ii.tools.R
import dev.ii.tools.link.LinkHub
import dev.ii.tools.link.LinkStatus
import dev.ii.tools.link.PairingUri
import dev.ii.tools.ui.theme.Expressive
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/** The pairing code scanner (Google's, no camera permission) and what it last read. */
class PairScanner(val scan: () -> Unit, val invalid: Boolean)

@Composable
fun rememberPairScanner(): PairScanner {
    val ctx = LocalContext.current
    var invalid by remember { mutableStateOf(false) }
    val scan = remember {
        {
            invalid = false
            val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
            GmsBarcodeScanning.getClient(ctx, options).startScan()
                .addOnSuccessListener { code ->
                    val uri = PairingUri.parse(code.rawValue)
                    if (uri != null) LinkHub.pair(uri) else invalid = true
                }
            Unit
        }
    }
    return PairScanner(scan, invalid)
}

/** Android 17's nearby devices permission, re-read on every return. */
class NetworkPermission(val granted: Boolean, val request: () -> Unit)

@Composable
fun rememberNetworkPermission(): NetworkPermission {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(LinkHub.hasNetworkPermission(ctx)) }
    LifecycleResumeEffect(Unit) {
        granted = LinkHub.hasNetworkPermission(ctx)
        onPauseOrDispose { }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        if (ok) LinkHub.onPermissionGranted()
    }
    return NetworkPermission(granted) { launcher.launch(LinkHub.LOCAL_NETWORK_PERMISSION) }
}

/**
 * Not paired yet: a big shape, the three steps the PC shows, the nearby devices
 * permission if it is missing, and the one button that pairs.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PairContent(status: LinkStatus, scanner: PairScanner, network: NetworkPermission, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            ShapeBox(MaterialShapes.Cookie12Sided, 136.dp, cs.primaryContainer) {
                Icon(painterResource(R.drawable.rounded_qr_code_scanner), null, tint = cs.onPrimaryContainer, modifier = Modifier.size(60.dp))
            }
            Text(stringResource(R.string.pair_title), style = Expressive.xwide(34.sp), textAlign = TextAlign.Center)
        }
        CardGroup {
            PairStep(1, stringResource(R.string.pair_step_1))
            PairStep(2, stringResource(R.string.pair_step_2))
            PairStep(3, stringResource(R.string.pair_step_3))
        }
        if (!network.granted) {
            ExpressiveCard(color = cs.tertiaryContainer, contentColor = cs.onTertiaryContainer, radius = 28.dp) {
                Row(Modifier.padding(start = 16.dp, end = 14.dp, top = 14.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(painterResource(R.drawable.rounded_devices), null, modifier = Modifier.size(26.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.perm_network), style = Expressive.title(16.sp))
                        Text(stringResource(R.string.perm_network_body), style = MaterialTheme.typography.bodyMedium)
                    }
                    FilledPill(stringResource(R.string.grant), onClick = network.request, height = 40.dp, container = cs.tertiary, content = cs.onTertiary)
                }
            }
        }
        if (status is LinkStatus.Pairing) {
            LinearWavyProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 18.dp))
        } else {
            FilledPill(
                stringResource(R.string.pair_scan),
                onClick = scanner.scan,
                icon = R.drawable.rounded_qr_code_scanner,
                height = 60.dp,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        val problem = when {
            scanner.invalid -> stringResource(R.string.pair_invalid)
            status is LinkStatus.Offline -> problemText(status.problem, status.failure)
            else -> null
        }
        AnimatedVisibility(problem != null) {
            Text(problem.orEmpty(), color = cs.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 4.dp))
        }
        Hint(stringResource(R.string.pair_link_hint), Modifier.fillMaxWidth())
    }
}

@Composable
private fun PairStep(n: Int, text: String) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.join)).background(Layer.pane).padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(cs.secondaryContainer), contentAlignment = Alignment.Center) {
            Text(n.toString(), style = Expressive.digits(24.sp), color = cs.onSecondaryContainer)
        }
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}
