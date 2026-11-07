package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.nostr.SignerType

@Entity(tableName = "vault_accounts")
data class VaultAccountEntity(
    @PrimaryKey val pubkeyHex: String,
    val npub: String,
    val encryptedPrivKeyBase64: String,
    val ivBase64: String,
    val displayName: String,
    val bio: String,
    val localAvatarPath: String = "",
    val remoteAvatarUrl: String = "",
    val badgeGlyph: String = "≠",
    val badgeColorHex: String = "#FF1493",
    val isActive: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val signerType: String = SignerType.LOCAL_KEYSTORE.name,
    val signerPackage: String = "",
    val bunkerUri: String = "",
    val bunkerClientPubkeyHex: String = ""
) {
    val resolvedSignerType: SignerType
        get() = runCatching { SignerType.valueOf(signerType) }.getOrDefault(SignerType.LOCAL_KEYSTORE)

    val canSign: Boolean
        get() = resolvedSignerType != SignerType.NPUB_READONLY
}

@Entity(tableName = "configured_relays")
data class RelayEntity(
    @PrimaryKey val url: String,
    val readEnabled: Boolean = true,
    val writeEnabled: Boolean = true,
    val addedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "relay_health")
data class RelayHealthEntity(
    @PrimaryKey val url: String,
    val supportedNipsCsv: String = "",
    val softwareInfo: String = "",
    val authRequired: Boolean = false,
    val paymentRequired: Boolean = false,
    val maxMessageBytes: Int = 0,
    val avgLatencyMs: Long = 0L,
    val successCount: Int = 0,
    val failureCount: Int = 0,
    val consecutiveFailures: Int = 0,
    val verifiedPublishCount: Int = 0,
    val lastSuccessAt: Long = 0L,
    val lastFailureAt: Long = 0L,
    val lastErrorReason: String = "",
    val lastProbedAt: Long = 0L
)

@Entity(tableName = "configured_blossom_servers")
data class ConfiguredBlossomServerEntity(
    @PrimaryKey val serverUrl: String,
    val enabled: Boolean = true,
    val isBackupMirror: Boolean = false,
    val addedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "blossom_server_health")
data class BlossomServerHealthEntity(
    @PrimaryKey val serverUrl: String,
    val requiresAuth: Boolean = true,
    val maxUploadBytes: Long = 0L,
    val supportsMirror: Boolean = false,
    val avgLatencyMs: Long = 0L,
    val uploadSuccessCount: Int = 0,
    val uploadFailureCount: Int = 0,
    val verifiedRetrievalCount: Int = 0,
    val hashMismatchCount: Int = 0,
    val consecutiveFailures: Int = 0,
    val lastSuccessAt: Long = 0L,
    val lastFailureAt: Long = 0L,
    val lastStatusMessage: String = "",
    val lastProbedAt: Long = 0L
)

@Entity(tableName = "nostr_notes")
data class NoteEntity(
    @PrimaryKey val eventId: String,
    val pubkeyHex: String,
    val npub: String,
    val authorName: String,
    val authorAvatar: String = "",
    val content: String,
    val createdAtSec: Long,
    val sigHex: String,
    val acceptedRelaysCount: Int = 0,
    val rawEventJson: String = "",
    val isLocalUser: Boolean = false
)

@Entity(tableName = "installed_applets")
data class InstalledAppletEntity(
    @PrimaryKey val appletId: String, // e.g., "35128:pubkey:dTag" or "15128:pubkey:root"
    val kind: Int, // 35128 (Named Nsite) or 15128 (Root Nsite)
    val authorPubkey: String,
    val authorNpub: String,
    val dTag: String,
    val title: String,
    val description: String,
    val iconGlyph: String,
    val accentHex: String,
    val isNsite: Boolean,
    val enableNip07: Boolean = true,
    val blossomServer: String = "https://blossom.primal.net",
    val indexSha256: String = "",
    val manifestPathsJson: String = "{}",
    val inlineHtml: String = "",
    val inlineCss: String = "",
    val inlineJs: String = "",
    val isInstalled: Boolean = true,
    val pinOrder: Int = 0,
    val updatedAt: Long = System.currentTimeMillis(),
    val latestEventId: String = "",
    val cachedComplete: Boolean = false
)

@Entity(tableName = "nsite_versions")
data class NsiteVersionEntity(
    @PrimaryKey val eventId: String,
    val coordinate: String, // "$kind:$authorPubkey:$dTag"
    val kind: Int,
    val authorPubkey: String,
    val authorNpub: String,
    val dTag: String,
    val title: String,
    val description: String,
    val indexSha256: String,
    val fileCount: Int,
    val blossomServersCsv: String,
    val manifestPathsJson: String,
    val rawEventJson: String,
    val signatureVerified: Boolean,
    val createdAtSec: Long,
    val discoveredAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "nsite_projects")
data class NsiteProjectEntity(
    @PrimaryKey val projectId: String,
    val ownerPubkeyHex: String,
    val title: String,
    val dTag: String,
    val targetKind: Int, // 35128 Named Nsite or 15128 Root Nsite
    val description: String,
    val entryPath: String = "/index.html",
    val sourceType: String = "SCRATCH", // SCRATCH, HTML_FILE, ZIP_ARCHIVE, FOLDER, FORK
    val forkSourceCoordinate: String = "",
    val forkSourceEventId: String = "",
    val forkSourcePubkey: String = "",
    val forkSourceNpub: String = "",
    val forkSourceDTag: String = "",
    val forkSourceVersionSec: Long = 0L,
    val incompleteImportWarning: String = "",
    val versionCounter: Int = 1,
    val lastPublishedEventId: String = "",
    val lastPublishedUrl: String = "",
    val lastPublishedAt: Long = 0L,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    val isFork: Boolean get() = forkSourcePubkey.isNotBlank() || sourceType == "FORK"
}

@Entity(tableName = "nsite_project_files", primaryKeys = ["projectId", "relativePath"])
data class NsiteProjectFileEntity(
    val projectId: String,
    val relativePath: String, // Normalized POSIX path starting with "/", e.g. "/index.html", "/assets/app.js"
    val mimeType: String,
    val isBinary: Boolean,
    val sizeBytes: Long,
    val sha256: String,
    val textContent: String = "",
    val diskBlobPath: String = "",
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "applet_nap_storage", primaryKeys = ["appletId", "storageKey"])
data class AppletStorageEntity(
    val appletId: String,
    val storageKey: String,
    val storageValue: String,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "signer_policies")
data class SignerPolicyEntity(
    @PrimaryKey val policyKey: String, // "$pubkeyHex|$appletId|$method|$eventKind"
    val pubkeyHex: String,
    val appletId: String,
    val appletTitle: String,
    val method: String,
    val eventKind: Int,
    val allowAlways: Boolean,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "signer_audit_log")
data class SignerAuditEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val pubkeyHex: String,
    val appletId: String,
    val appletTitle: String,
    val protocolLabel: String,
    val method: String,
    val eventKind: Int,
    val approved: Boolean,
    val summary: String
)

@Entity(tableName = "curated_bookmarks", primaryKeys = ["coordinate", "curatorPubkey"])
data class CuratedBookmarkEntity(
    val coordinate: String, // "$kind:$authorPubkey:$dTag"
    val curatorPubkey: String,
    val curatorNpub: String,
    val listSlug: String = "sovereign-gallery",
    val listTitle: String = "Curated Picks",
    val isLocalCurator: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "publisher_profiles")
data class PublisherProfileEntity(
    @PrimaryKey val pubkeyHex: String,
    val npub: String,
    val displayName: String = "",
    val username: String = "",
    val nip05: String = "",
    val pictureUrl: String = "",
    val bannerUrl: String = "",
    val about: String = "",
    val website: String = "",
    val lud16: String = "",
    val eventCreatedAtSec: Long = 0L,
    val updatedAt: Long = System.currentTimeMillis()
) {
    val bestName: String
        get() = displayName.trim().ifBlank { username.trim() }.ifBlank { shortNpub(npub) }

    val hasResolvedIdentity: Boolean
        get() = displayName.isNotBlank() || username.isNotBlank() || nip05.isNotBlank() || pictureUrl.isNotBlank()

    val formattedNip05: String
        get() = nip05.trim().removePrefix("_@")

    companion object {
        fun shortNpub(npub: String): String {
            val clean = npub.trim()
            return if (clean.length > 18) "${clean.take(10)}…${clean.takeLast(5)}" else clean
        }
    }
}
