package dev.rsm.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.rsm.ui.theme.RsmTheme

/**
 * The app's launch activity and the host for its Compose UI.
 *
 * Also the target of the `USB_DEVICE_ATTACHED` filter (FR-100), which is why `launchMode` is
 * `singleTask`: an attach while the app is already open must resurface this instance rather than
 * stack a second copy. The attach intent itself needs no handling here — the volume state registers
 * for USB and volume-state changes for as long as the screen is composed, so arriving by attach and
 * arriving by launcher converge on the same rescan.
 *
 * ### enableEdgeToEdge, and why the previous version put content under the status bar
 *
 * From targetSdk 35 the platform draws every app behind the system bars; this app targets 37, so it
 * is not opting in to edge-to-edge, it is being given it. The old version then applied a flat
 * `padding(16.dp)` and nothing else, so the first list item sat underneath the notification bar.
 *
 * The fix is to consume the insets rather than to escape the enforcement: `enableEdgeToEdge()` makes
 * the arrangement explicit and — via `SystemBarStyle.auto` by default — flips the status-bar icons
 * to match light or dark mode, and the `Scaffold`/`TopAppBar` inside apply `safeDrawing` so content
 * starts below the bar. Setting `android:windowOptOutEdgeToEdgeEnforcement` would also stop the
 * overlap, and is the wrong answer: it is a transitional compatibility flag, and it would leave the
 * app looking a generation old on every device.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            RsmTheme {
                RsmApp()
            }
        }
    }
}
