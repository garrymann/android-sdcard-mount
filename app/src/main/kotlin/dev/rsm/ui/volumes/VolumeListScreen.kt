package dev.rsm.ui.volumes

import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SdStorage
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import dev.rsm.R
import dev.rsm.fs.model.DetectedFilesystem
import dev.rsm.fs.volume.AccessMethod
import dev.rsm.fs.volume.PlatformVolumeState
import dev.rsm.fs.volume.VolumeAccess
import dev.rsm.storage.DetectedVolume
import dev.rsm.ui.messages.ReasonStrings
import kotlinx.coroutines.launch

/**
 * The main screen: every detected volume, with why it is or is not reachable.
 *
 * FR-600 fixes the per-volume content — label, size, used/free, detected filesystem, current access
 * state, and access method — and its AC-1 calls that enumeration exhaustive, so none of the six is
 * dropped for being uninteresting. FR-601 adds the explanation for anything unreachable, and AC-7
 * requires the root verdict beside it every time.
 *
 * ### Why the card has an expander but the six fields are not behind it
 *
 * AC-1's "exhaustive" reading means all six must be *visible*, so the expander holds only extra
 * diagnostics — the volume id, where it is attached, whether a grant is stored. Moving any of the six
 * in there would turn a satisfied requirement into a hidden one, which is the obvious way a redesign
 * breaks this file.
 *
 * ### Insets
 *
 * The `Scaffold` is what keeps content out from under the notification bar. `TopAppBar` insets itself
 * by `safeDrawing` top, and the body applies the `contentPadding` the `Scaffold` hands back, so
 * nothing relies on a hardcoded status-bar height. See `MainActivity` for why edge-to-edge is not
 * opted out of instead.
 */
// TopAppBar is still @ExperimentalMaterial3Api in material3 1.4.0 (verified against the artifact —
// TopAppBar, CenterAlignedTopAppBar, MediumTopAppBar and LargeTopAppBar all carry it; only the
// *Flexible* variants do not). The opt-in is scoped to this function rather than the file so that a
// future experimental API added elsewhere here has to be opted into deliberately.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VolumeListScreen(
    state: VolumeListState,
    onOpenAbout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var overflowOpen by remember { mutableStateOf(false) }

    // Held rather than acted on immediately: revoking is a deliberate, explainable act, so the tap
    // opens a dialog naming the volume instead of silently dropping the grant.
    var pendingRevoke by remember { mutableStateOf<DetectedVolume?>(null) }

    // The SAF tree picker (FR-200). The result is persisted immediately (FR-201) and the list is
    // rescanned so the volume moves from "needs grant" to "ready" without a manual refresh.
    val treePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        result.data?.data?.let { treeUri ->
            context.contentResolver.takePersistableUriPermission(
                treeUri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        state.refresh()
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.volumes_title)) },
                actions = {
                    IconButton(onClick = { state.refresh() }, modifier = Modifier.testTag("refresh")) {
                        Icon(Icons.Rounded.Refresh, stringResource(R.string.action_refresh))
                    }
                    Box {
                        IconButton(
                            onClick = { overflowOpen = true },
                            modifier = Modifier.testTag("overflow"),
                        ) {
                            Icon(Icons.Rounded.MoreVert, stringResource(R.string.action_more))
                        }
                        DropdownMenu(
                            expanded = overflowOpen,
                            onDismissRequest = { overflowOpen = false },
                        ) {
                            DropdownMenuItem(
                                modifier = Modifier.testTag("menu-about"),
                                text = { Text(stringResource(R.string.action_about)) },
                                leadingIcon = { Icon(Icons.Rounded.Info, contentDescription = null) },
                                onClick = {
                                    overflowOpen = false
                                    onOpenAbout()
                                },
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { contentPadding ->
        if (state.hasScanned && state.volumes.isEmpty()) {
            EmptyState(modifier = Modifier.fillMaxSize().padding(contentPadding))
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding)
                    .testTag("volume-list"),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            ) {
                // Withheld until the first scan lands, so launch does not flash "0 of 0 volumes".
                if (state.hasScanned) {
                    item(key = "summary") { SummaryLine(state.volumes) }
                }

                items(state.volumes, key = { it.id }) { volume ->
                    VolumeCard(
                        volume = volume,
                        onGrantAccess = {
                            volume.treeIntentSource?.let {
                                treePicker.launch(it.createOpenDocumentTreeIntent())
                            }
                        },
                        onRevokeAccess = { pendingRevoke = volume },
                    )
                }
            }
        }
    }

    pendingRevoke?.let { volume ->
        val revokedMessage = stringResource(R.string.volume_revoke_done, volume.label)
        val nothingMessage = stringResource(R.string.volume_revoke_nothing_held, volume.label)
        RevokeAccessDialog(
            volume = volume,
            onDismiss = { pendingRevoke = null },
            onConfirm = {
                val revoked = state.revokeAccess(volume)
                pendingRevoke = null
                scope.launch {
                    snackbarHostState.showSnackbar(if (revoked) revokedMessage else nothingMessage)
                }
            },
        )
    }
}

/**
 * A one-line answer to "is anything usable?" above the list.
 *
 * Plural-aware because "1 of 1 volumes ready to use" reads like a bug, and this is the first line on
 * the screen.
 */
@Composable
private fun SummaryLine(volumes: List<DetectedVolume>) {
    val ready = volumes.count { it.access is VolumeAccess.Ready }
    Text(
        text = pluralStringResource(R.plurals.volumes_summary, volumes.size, ready, volumes.size),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 4.dp).testTag("summary"),
    )
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp).testTag("startup-screen"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Rounded.SdStorage,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            modifier = Modifier.padding(top = 16.dp),
            text = stringResource(R.string.volumes_empty_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            modifier = Modifier.padding(top = 8.dp),
            text = stringResource(R.string.startup_message),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * One volume.
 *
 * Each of FR-600's six fields gets its own line rather than being packed into a shared row. That is
 * partly legibility and partly FR-606: at 200% font scale a row holding a label, a badge and a size
 * either clips or wraps into something unreadable, and nothing here has a fixed height.
 */
@Composable
private fun VolumeCard(
    volume: DetectedVolume,
    onGrantAccess: () -> Unit,
    onRevokeAccess: () -> Unit,
) {
    var detailsShown by remember { mutableStateOf(false) }
    val hasGrant = volume.facts.hasPersistedTreeGrant

    Card(modifier = Modifier.fillMaxWidth().testTag("volume-${volume.id}")) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = volumeIcon(volume),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = volume.label,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                // Rendered only when it would contain something. An always-present menu whose only
                // item is conditional is a dead affordance.
                if (hasGrant) {
                    VolumeOptionsMenu(volume = volume, onRevokeAccess = onRevokeAccess)
                }
            }

            AccessStateBadge(volume.access)

            // Detected filesystem + access method (FR-600, two of the six).
            Text(
                text = stringResource(
                    R.string.volume_via_method,
                    filesystemLabel(volume),
                    stringResource(accessMethodLabel(volume.access.displayMethod)),
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Platform state, kept distinct from the badge above — see the strings.xml comment.
            Text(
                text = stringResource(stateLabel(volume.facts.state)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            CapacitySection(volume)

            when (val access = volume.access) {
                is VolumeAccess.Blocked -> BlockedExplanation(access)

                is VolumeAccess.NeedsUserGrant -> Button(
                    onClick = onGrantAccess,
                    modifier = Modifier.testTag("grant-${volume.id}"),
                ) {
                    Text(stringResource(R.string.volume_grant))
                }

                is VolumeAccess.Ready -> Text(
                    text = stringResource(R.string.volume_ready_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                is VolumeAccess.Busy -> Unit
            }

            DetailsToggle(shown = detailsShown, onToggle = { detailsShown = !detailsShown })
            AnimatedVisibility(visible = detailsShown) { VolumeDetails(volume) }
        }
    }
}

/** The per-volume overflow. Currently one item; a `DropdownMenu` so adding the next one is free. */
@Composable
private fun VolumeOptionsMenu(volume: DetectedVolume, onRevokeAccess: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(
            onClick = { open = true },
            modifier = Modifier.testTag("volume-menu-${volume.id}"),
        ) {
            Icon(
                Icons.Rounded.MoreVert,
                contentDescription = stringResource(R.string.action_volume_options, volume.label),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                modifier = Modifier.testTag("revoke-${volume.id}"),
                text = { Text(stringResource(R.string.volume_revoke)) },
                leadingIcon = { Icon(Icons.Rounded.LinkOff, contentDescription = null) },
                onClick = {
                    open = false
                    onRevokeAccess()
                },
            )
        }
    }
}

/**
 * Confirmation before a grant is dropped.
 *
 * The volume is named in both the title and the body, and the body says what is *not* affected. The
 * failure mode this guards against is a user reading "revoke access" as "erase" — so the dialog
 * states plainly that nothing on the volume changes.
 */
@Composable
private fun RevokeAccessDialog(
    volume: DetectedVolume,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        modifier = Modifier.testTag("revoke-dialog"),
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.LinkOff, contentDescription = null) },
        title = { Text(stringResource(R.string.volume_revoke_title, volume.label)) },
        text = { Text(stringResource(R.string.volume_revoke_body, volume.label)) },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag("revoke-confirm")) {
                Text(stringResource(R.string.volume_revoke_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.volume_revoke_cancel))
            }
        },
    )
}

/** The app's own verdict, as a tonal pill. Colour is paired with an icon, never carrying meaning alone. */
@Composable
private fun AccessStateBadge(access: VolumeAccess) {
    val (labelRes, icon, container, onContainer) = when (access) {
        is VolumeAccess.Ready -> BadgeStyle(
            if (access.readOnly) R.string.access_state_ready_read_only else R.string.access_state_ready,
            Icons.Rounded.CheckCircle,
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
        )

        is VolumeAccess.NeedsUserGrant -> BadgeStyle(
            R.string.access_state_needs_grant,
            Icons.Rounded.Warning,
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer,
        )

        is VolumeAccess.Busy -> BadgeStyle(
            R.string.access_state_busy,
            Icons.Rounded.Storage,
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
        )

        is VolumeAccess.Blocked -> BadgeStyle(
            R.string.access_state_blocked,
            Icons.Rounded.Block,
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
        )
    }

    Surface(
        color = container,
        contentColor = onContainer,
        shape = MaterialTheme.shapes.small,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
            Text(text = stringResource(labelRes), style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** Destructurable so the `when` above stays one expression per branch. */
private data class BadgeStyle(
    @param:StringRes val labelRes: Int,
    val icon: ImageVector,
    val container: Color,
    val onContainer: Color,
)

/**
 * Size and used/free (FR-600, two more of the six), with a bar when the numbers exist.
 *
 * For a volume the app cannot open the size is genuinely unknowable rather than merely unmeasured
 * (C-1), so the honest rendering is a sentence saying so, not a fabricated bar at 0%.
 */
@Composable
private fun CapacitySection(volume: DetectedVolume) {
    val context = LocalContext.current
    val total = volume.facts.totalBytes
    val free = volume.facts.freeBytes

    if (total == null || free == null || total <= 0L) {
        Text(
            text = stringResource(
                if (volume.isDeviceWithoutVolume) {
                    R.string.device_usage_unavailable
                } else {
                    R.string.volume_usage_unavailable
                },
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("usage-${volume.id}"),
        )
        return
    }

    val used = (total - free).coerceAtLeast(0L)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        LinearProgressIndicator(
            progress = { (used.toFloat() / total.toFloat()).coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                // The line below states the same numbers, so the bar is decorative to TalkBack
                // rather than a second, vaguer announcement of them.
                .clearAndSetSemantics { },
        )
        Text(
            text = stringResource(
                R.string.volume_usage_full,
                Formatter.formatFileSize(context, free),
                Formatter.formatFileSize(context, total),
                Formatter.formatFileSize(context, used),
            ),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag("usage-${volume.id}"),
        )
    }
}

@Composable
private fun DetailsToggle(shown: Boolean, onToggle: () -> Unit) {
    TextButton(onClick = onToggle, modifier = Modifier.testTag("details-toggle")) {
        Icon(
            imageVector = if (shown) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
            contentDescription = null,
        )
        Text(
            modifier = Modifier.padding(start = 4.dp),
            text = stringResource(
                if (shown) R.string.volume_details_hide else R.string.volume_details_show,
            ),
        )
    }
}

/** Extra diagnostics. Never a home for one of FR-600's six required fields — see the class docs. */
@Composable
private fun VolumeDetails(volume: DetectedVolume) {
    val facts = volume.facts
    val yes = stringResource(R.string.volume_detail_yes)
    val no = stringResource(R.string.volume_detail_no)

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        DetailRow(
            stringResource(
                if (volume.isDeviceWithoutVolume) {
                    R.string.device_detail_id
                } else {
                    R.string.volume_detail_id
                },
            ),
            facts.id,
        )
        DetailRow(
            stringResource(R.string.volume_detail_location),
            stringResource(
                if (facts.isInternalSlot) {
                    R.string.volume_detail_location_internal
                } else {
                    R.string.volume_detail_location_usb
                },
            ),
        )
        DetailRow(stringResource(R.string.volume_detail_removable), if (facts.isRemovable) yes else no)
        DetailRow(
            stringResource(R.string.volume_detail_grant),
            stringResource(
                if (facts.hasPersistedTreeGrant) {
                    R.string.volume_detail_grant_held
                } else {
                    R.string.volume_detail_grant_none
                },
            ),
        )
        DetailRow(stringResource(R.string.volume_detail_root), if (facts.rootAvailable) yes else no)
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1.4f),
        )
    }
}

/**
 * The specific reason plus the root verdict.
 *
 * The two are emitted together and unconditionally: FR-601 AC-7 says a reason without the root
 * verdict does not satisfy the requirement, so there is no branch here that can drop one.
 */
@Composable
private fun BlockedExplanation(blocked: VolumeAccess.Blocked) {
    val message = ReasonStrings.messageFor(blocked.reason)
    val reasonText = stringResource(message.stringRes, *message.formatArgs.toTypedArray())
    val rootText = stringResource(ReasonStrings.rootVerdictFor(blocked.reason.rootRelevance))

    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.small,
    ) {
        Column(
            // TalkBack reads the explanation as one utterance; split across two nodes it is announced
            // as two unrelated fragments, and the root verdict is the half users act on (FR-606).
            modifier = Modifier
                .padding(12.dp)
                .testTag("reason")
                .clearAndSetSemantics { contentDescription = "$reasonText $rootText" },
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(text = reasonText, style = MaterialTheme.typography.bodyMedium)
            Text(text = rootText, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * Icon per volume kind.
 *
 * Reads only `isRemovable` and `isInternalSlot`, both runtime observations — never an OEM or version
 * check (FR-005). Purely decorative: every icon here has a text equivalent on the card.
 */
private fun volumeIcon(volume: DetectedVolume): ImageVector = when {
    volume.facts.isInternalSlot -> Icons.Rounded.SdStorage
    volume.facts.isRemovable -> Icons.Rounded.Usb
    else -> Icons.Rounded.Storage
}

@Composable
private fun filesystemLabel(volume: DetectedVolume): String =
    when (val detected = volume.facts.detected) {
        is DetectedFilesystem.Known -> detected.kind.displayName
        // AC-3: the bytes, never the words "unknown filesystem".
        is DetectedFilesystem.Unrecognised -> detected.magicBytesHex
        DetectedFilesystem.NotProbed -> stringResource(R.string.volume_filesystem_unprobed)
    }

private fun accessMethodLabel(method: AccessMethod): Int = when (method) {
    AccessMethod.SAF -> R.string.access_method_saf
    AccessMethod.RAW_USB -> R.string.access_method_raw_usb
    AccessMethod.ROOT_MOUNT -> R.string.access_method_root
    AccessMethod.NONE -> R.string.access_method_none
}

private fun stateLabel(state: PlatformVolumeState): Int = when (state) {
    PlatformVolumeState.MOUNTED -> R.string.state_mounted
    PlatformVolumeState.MOUNTED_READ_ONLY -> R.string.state_mounted_read_only
    PlatformVolumeState.CHECKING -> R.string.state_checking
    PlatformVolumeState.EJECTING -> R.string.state_ejecting
    PlatformVolumeState.UNMOUNTED -> R.string.state_unmounted
    PlatformVolumeState.UNMOUNTABLE -> R.string.state_unmountable
    PlatformVolumeState.NO_FILESYSTEM -> R.string.state_no_filesystem
    PlatformVolumeState.BAD_REMOVAL -> R.string.state_bad_removal
    PlatformVolumeState.REMOVED -> R.string.state_removed
    PlatformVolumeState.UNKNOWN -> R.string.state_unknown
    PlatformVolumeState.NOT_REPORTED -> R.string.state_not_reported
}
