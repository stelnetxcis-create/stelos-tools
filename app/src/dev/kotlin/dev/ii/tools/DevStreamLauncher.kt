package dev.ii.tools

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import dev.ii.tools.screen.SecondScreen

/**
 * Dev builds only: opens the second screen in a mode, as the home card's buttons do, so a
 * stream test can start from adb:
 * `adb shell am start -n net.stelnet.tools.dev/dev.ii.tools.DevStreamLauncher --es mode extend`
 */
class DevStreamLauncher : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SecondScreen.pendingMode = intent.getStringExtra("mode")?.takeIf { it == "extend" || it == "mirror" } ?: "extend"
        startActivity(Intent(this, SecondScreenActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }
}
