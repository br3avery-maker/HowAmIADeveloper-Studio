package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

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
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "configured_relays")
data class RelayEntity(
    @PrimaryKey val url: String,
    val readEnabled: Boolean = true,
    val writeEnabled: Boolean = true,
    val addedAt: Long = System.currentTimeMillis()
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
    @PrimaryKey val appletId: String, // e.g., "35129:pubkey:dTag" or "builtin:pulsefeed"
    val kind: Int, // 35129 (Napplet) or 35128 / 15128 (Nsite)
    val authorPubkey: String,
    val authorNpub: String,
    val dTag: String,
    val title: String,
    val description: String,
    val iconGlyph: String,
    val accentHex: String,
    val isNsite: Boolean,
    val enableNip07: Boolean = false,
    val blossomServer: String = "https://blossom.primal.net",
    val indexSha256: String = "",
    val manifestPathsJson: String = "{}",
    val inlineHtml: String = "",
    val inlineCss: String = "",
    val inlineJs: String = "",
    val isInstalled: Boolean = true,
    val pinOrder: Int = 0,
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
