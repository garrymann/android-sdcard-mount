package dev.rsm.ui.volumes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import dev.rsm.storage.DetectedVolume
import dev.rsm.storage.VolumeDetector

/**
 * Holds the volume list and keeps it current.
 *
 * Separated from the composables so that rendering has no knowledge of `StorageManager`,
 * `UsbManager` or SAF grant plumbing — the screen renders a list of [DetectedVolume] and nothing
 * else, which is what lets it be previewed and tested with fabricated volumes.
 */
@Stable
class VolumeListState(
    private val detector: VolumeDetector,
) {
    var volumes by mutableStateOf<List<DetectedVolume>>(emptyList())
        private set

    /** True until the first scan completes, so the UI does not flash "nothing found" on launch. */
    var hasScanned by mutableStateOf(false)
        private set

    /**
     * FR-201: persisted tree grants are re-validated on launch, before the first list is built, so a
     * grant left over from a card that has since been reformatted never presents as valid access.
     */
    fun start() {
        detector.revalidateGrants()
        refresh()
    }

    fun refresh() {
        volumes = detector.scan()
        hasScanned = true
    }

    /**
     * Withdraws the SAF grant for [volume] and rescans so the card reflects it immediately.
     *
     * Returns whether anything was actually released, which the screen turns into one of two
     * different messages. Reporting "access revoked" for a grant that had already lapsed would be
     * the same class of defect FR-201 AC-5 rules out on the way in: treating the app's own record of
     * a grant as evidence about the world.
     */
    fun revokeAccess(volume: DetectedVolume): Boolean {
        val revoked = detector.revokeGrant(volume.id)
        refresh()
        return revoked
    }

    fun observe(): AutoCloseable = detector.observe(::refresh)
}

/**
 * Builds a [VolumeListState] bound to the composition's lifetime.
 *
 * The `DisposableEffect` is what satisfies FR-103: registration happens once, and the callbacks are
 * unregistered when the screen leaves the composition rather than leaking a `StorageVolumeCallback`
 * and a `BroadcastReceiver` per recomposition.
 */
@Composable
fun rememberVolumeListState(): VolumeListState {
    val context = LocalContext.current
    val state = remember(context) { VolumeListState(VolumeDetector.create(context)) }

    DisposableEffect(state) {
        state.start()
        val registration = state.observe()
        onDispose { registration.close() }
    }
    return state
}
