package dev.rsm.storage

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.storage.StorageVolume
import android.provider.DocumentsContract

/**
 * Persisted SAF tree grants (FR-201).
 *
 * ### Why re-validation is not optional
 *
 * FR-201's rationale: "a persisted grant can outlive the thing it names: tree URIs are keyed to a
 * volume whose UUID **changes on reformat**, so a grant that is still present in the persisted
 * permission list is not evidence that it still points at the volume the user chose." AC-5 turns
 * that into a hard rule — presence in the list is not validity.
 *
 * So [hasGrantFor] matches a grant against a volume that is *currently present*, rather than the app
 * trusting its own stored list. A grant for a reformatted card simply stops matching, because the
 * volume's UUID no longer equals the one embedded in the tree URI.
 */
class SafTreeGrants(
    private val contentResolver: ContentResolver,
) {

    /** The persistable read/write flags SAF requires to be taken together. */
    private val persistFlags =
        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

    /**
     * True when a live persisted grant covers [volume].
     *
     * The comparison is on the volume id embedded in the tree document id — `1234-5678:` for a
     * removable card, `primary:` for the emulated volume — which is the same key SAF itself uses.
     */
    fun hasGrantFor(volume: StorageVolume): Boolean {
        val volumeKey = volumeKeyOf(volume) ?: return false
        return contentResolver.persistedUriPermissions.any { permission ->
            permission.isReadPermission && treeVolumeKeyOf(permission.uri) == volumeKey
        }
    }

    /** The persisted tree URI for [volume], if one is currently valid. */
    fun grantedTreeUri(volume: StorageVolume): Uri? {
        val volumeKey = volumeKeyOf(volume) ?: return null
        return contentResolver.persistedUriPermissions
            .firstOrNull { it.isReadPermission && treeVolumeKeyOf(it.uri) == volumeKey }
            ?.uri
    }

    /**
     * Takes a persistable grant on the tree the user just picked.
     *
     * Returns false when the system declines to persist it, which the caller must surface as a
     * specific reason rather than silently continuing with a grant that will not survive restart.
     */
    fun persist(treeUri: Uri): Boolean = runCatching {
        contentResolver.takePersistableUriPermission(treeUri, persistFlags)
        true
    }.getOrDefault(false)

    /**
     * Releases every persisted grant covering [volume], at the user's request.
     *
     * Returns false when there was nothing to release, so the caller can tell "revoked" apart from
     * "already gone" instead of reporting a success that did not happen.
     *
     * ### Why this is not in the spec, and why it is here anyway
     *
     * The corpus requires taking and re-validating grants (FR-201) and says nothing about giving one
     * back — there is no requirement to cite. It is added because the alternative is worse than a
     * gap: without it the only way to withdraw access this app was granted is Android's per-app
     * storage settings, which most users will never find, and FR-201's own rationale (a grant
     * outliving the thing it names) argues the user should be able to drop one deliberately rather
     * than wait for a reformat to invalidate it.
     *
     * All matching grants are released rather than the first, because nothing stops a volume
     * accumulating several tree URIs — one per directory the user ever picked on it — and leaving
     * the rest behind would present as "revoke did nothing".
     */
    fun releaseGrantFor(volume: StorageVolume): Boolean {
        val volumeKey = volumeKeyOf(volume) ?: return false
        return contentResolver.persistedUriPermissions
            .filter { treeVolumeKeyOf(it.uri) == volumeKey }
            .fold(false) { released, permission ->
                // runCatching per grant: one URI the system refuses to release must not abandon the
                // rest, and a partial release is still worth reporting as a release.
                val ok = runCatching {
                    contentResolver.releasePersistableUriPermission(permission.uri, persistFlags)
                }.isSuccess
                released || ok
            }
    }

    /**
     * Drops persisted grants that no longer match any present volume.
     *
     * Called on launch per FR-201. Releasing them keeps the persisted-permission list — which is a
     * finite per-app resource — from filling with grants for cards that were reformatted or are
     * never coming back.
     */
    fun releaseGrantsNotMatching(presentVolumes: List<StorageVolume>) {
        val liveKeys = presentVolumes.mapNotNull(::volumeKeyOf).toSet()
        contentResolver.persistedUriPermissions
            .filter { treeVolumeKeyOf(it.uri) !in liveKeys }
            .forEach { stale ->
                runCatching { contentResolver.releasePersistableUriPermission(stale.uri, persistFlags) }
            }
    }

    private fun volumeKeyOf(volume: StorageVolume): String? =
        if (volume.isPrimary) PRIMARY_KEY else volume.uuid

    /** `content://…/tree/1234-5678%3A` → `1234-5678`. Null when the URI is not a tree URI. */
    private fun treeVolumeKeyOf(uri: Uri): String? = runCatching {
        DocumentsContract.getTreeDocumentId(uri).substringBefore(':')
    }.getOrNull()

    private companion object {
        /** SAF's own name for the emulated volume in a tree document id. */
        const val PRIMARY_KEY = "primary"
    }
}
