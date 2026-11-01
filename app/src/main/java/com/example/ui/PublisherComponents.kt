package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.R
import com.example.data.PublisherProfileEntity
import com.example.nostr.NostrCrypto
import com.example.ui.theme.BreBlack
import com.example.ui.theme.BreDeepPink
import com.example.ui.theme.BreGray100
import com.example.ui.theme.BreGray600
import com.example.ui.theme.BreGray850
import com.example.ui.theme.BreLightPink
import com.example.ui.theme.BrePinkDark1
import com.example.ui.theme.BrePinkDark2
import com.example.ui.theme.BrePinkDark3
import com.example.ui.theme.BreWhite
import com.example.ui.theme.CyberMint
import com.example.ui.theme.ObsidianBorder
import com.example.ui.theme.ZineBoneCream
import java.io.File

/**
 * Circular Nostr publisher avatar backed by Kind 0 `picture` URL (or local file) with a clean
 * monogram fallback so every Nsite/Napplet card has crisp author identity.
 */
@Composable
fun PublisherAvatar(
    profile: PublisherProfileEntity?,
    fallbackNpub: String,
    sizeDp: Int,
    modifier: Modifier = Modifier
) {
    val labelForCd = profile?.bestName ?: PublisherProfileEntity.shortNpub(fallbackNpub)
    val cd = stringResource(R.string.cd_publisher_avatar, labelForCd)
    val pic = profile?.pictureUrl.orEmpty()
    val localFile = remember(pic) {
        if (pic.isNotBlank() && !pic.startsWith("http", ignoreCase = true)) File(pic) else null
    }
    val monogram = remember(profile?.displayName, profile?.username, fallbackNpub) {
        val raw = profile?.displayName?.trim()?.ifBlank { profile.username.trim() }.orEmpty()
        if (raw.isNotBlank()) {
            raw.first().uppercaseChar().toString()
        } else {
            fallbackNpub.removePrefix("npub1").take(2).uppercase().ifBlank { "N" }
        }
    }

    Box(
        modifier = modifier
            .size(sizeDp.dp)
            .clip(CircleShape)
            .background(BrePinkDark3)
            .border(1.dp, BrePinkDark1, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        when {
            localFile != null && localFile.exists() -> {
                AsyncImage(
                    model = localFile,
                    contentDescription = cd,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(sizeDp.dp)
                        .clip(CircleShape)
                )
            }
            pic.startsWith("https://", ignoreCase = true) || pic.startsWith("http://", ignoreCase = true) -> {
                AsyncImage(
                    model = pic,
                    contentDescription = cd,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(sizeDp.dp)
                        .clip(CircleShape)
                )
            }
            else -> {
                Text(
                    text = monogram,
                    style = if (sizeDp >= 34) {
                        MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Black)
                    } else {
                        MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
                    },
                    color = ZineBoneCream,
                    maxLines = 1
                )
            }
        }
    }
}

/**
 * Compact inline publisher attribution row for Home list cards, Gallery cards, and Live Feed items.
 */
@Composable
fun PublisherAttributionRow(
    profile: PublisherProfileEntity?,
    authorPubkey: String,
    authorNpub: String,
    dTag: String? = null,
    onClickPublisher: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val effectiveNpub = remember(profile?.npub, authorNpub, authorPubkey) {
        profile?.npub?.takeIf { it.isNotBlank() }
            ?: authorNpub.ifBlank {
                runCatching { NostrCrypto.encodeNpub(authorPubkey) }.getOrDefault(authorPubkey.take(14))
            }
    }
    val displayName = profile?.bestName ?: PublisherProfileEntity.shortNpub(effectiveNpub)
    val nip05 = profile?.formattedNip05.orEmpty()
    val isFeaturedCreator = authorPubkey.equals(NostrCrypto.DEFAULT_FEATURED_CREATOR_PUBKEY_HEX, ignoreCase = true)

    val rowModifier = if (onClickPublisher != null && authorPubkey.length == 64) {
        modifier
            .clip(RoundedCornerShape(999.dp))
            .clickable { onClickPublisher(authorPubkey.lowercase()) }
            .padding(vertical = 2.dp, horizontal = 2.dp)
    } else {
        modifier
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        modifier = rowModifier
    ) {
        PublisherAvatar(
            profile = profile,
            fallbackNpub = effectiveNpub,
            sizeDp = 18
        )

        Text(
            text = displayName,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = if (isFeaturedCreator) BreLightPink else BreWhite,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        if (nip05.isNotBlank()) {
            Icon(
                imageVector = Icons.Default.Verified,
                contentDescription = "NIP-05 identifier",
                tint = CyberMint,
                modifier = Modifier.size(11.dp)
            )
            Text(
                text = nip05,
                style = MaterialTheme.typography.labelSmall,
                color = CyberMint.copy(alpha = 0.9f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        } else if (profile?.hasResolvedIdentity == true) {
            Text(
                text = "• ${PublisherProfileEntity.shortNpub(effectiveNpub)}",
                style = MaterialTheme.typography.labelSmall,
                color = BreGray600,
                maxLines = 1,
                softWrap = false
            )
        }

        if (!dTag.isNullOrBlank()) {
            Text(
                text = "• d:$dTag",
                style = MaterialTheme.typography.labelSmall,
                color = ZineBoneCream.copy(alpha = 0.80f),
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

/**
 * Rich Publisher Profile Card for Version Picker sheets, Manifest Inspector, Runtime Inspector,
 * and Discovery's active Publisher Spotlight filter banner.
 */
@Composable
fun PublisherProfileCard(
    profile: PublisherProfileEntity?,
    authorPubkey: String,
    authorNpub: String,
    appCount: Int? = null,
    onFilterByPublisher: ((String) -> Unit)? = null,
    onClearFilter: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val clipboard = LocalClipboardManager.current
    val effectiveNpub = remember(profile?.npub, authorNpub, authorPubkey) {
        profile?.npub?.takeIf { it.startsWith("npub1") }
            ?: authorNpub.takeIf { it.startsWith("npub1") }
            ?: runCatching { NostrCrypto.encodeNpub(authorPubkey) }.getOrDefault(authorPubkey)
    }
    val bestName = profile?.bestName ?: PublisherProfileEntity.shortNpub(effectiveNpub)
    val nip05 = profile?.formattedNip05.orEmpty()
    val handle = profile?.username?.trim()?.takeIf {
        it.isNotBlank() && !it.equals(profile.displayName.trim(), ignoreCase = true)
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = BreGray850.copy(alpha = 0.96f),
        border = BorderStroke(1.dp, BrePinkDark2),
        modifier = modifier
            .fillMaxWidth()
            .testTag("publisher_profile_card")
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                PublisherAvatar(
                    profile = profile,
                    fallbackNpub = effectiveNpub,
                    sizeDp = 42
                )

                Spacer(modifier = Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Text(
                            text = bestName,
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = BreWhite,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (nip05.isNotBlank()) {
                            Icon(
                                imageVector = Icons.Default.Verified,
                                contentDescription = "NIP-05",
                                tint = CyberMint,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }

                    val subLine = buildString {
                        if (!handle.isNullOrBlank()) append("@$handle")
                        if (nip05.isNotBlank()) {
                            if (isNotEmpty()) append(" • ")
                            append(nip05)
                        }
                        if (appCount != null) {
                            if (isNotEmpty()) append(" • ")
                            append("$appCount published")
                        }
                    }
                    if (subLine.isNotBlank()) {
                        Text(
                            text = subLine,
                            style = MaterialTheme.typography.labelSmall,
                            color = CyberMint,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = PublisherProfileEntity.shortNpub(effectiveNpub),
                            style = MaterialTheme.typography.labelSmall,
                            color = ZineBoneCream.copy(alpha = 0.85f),
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }

                IconButton(
                    onClick = { clipboard.setText(AnnotatedString(effectiveNpub)) },
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .size(36.dp)
                        .testTag("copy_publisher_npub_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = stringResource(R.string.publisher_copy_npub),
                        tint = BreGray100,
                        modifier = Modifier.size(15.dp)
                    )
                }

                if (onClearFilter != null) {
                    IconButton(
                        onClick = onClearFilter,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .size(36.dp)
                            .testTag("clear_publisher_filter_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Clear publisher filter",
                            tint = BreLightPink,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                }
            }

            if (!profile?.about.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = profile?.about.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = BreGray100,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (onFilterByPublisher != null && authorPubkey.length == 64) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val extraMeta = profile?.website?.takeIf { it.isNotBlank() }
                        ?: profile?.lud16?.takeIf { it.isNotBlank() }?.let { "⚡ $it" }
                        ?: "Kind 0 Nostr Publisher"
                    Text(
                        text = extraMeta,
                        style = MaterialTheme.typography.labelSmall,
                        color = BreGray600,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        onClick = { onFilterByPublisher(authorPubkey.lowercase()) },
                        shape = RoundedCornerShape(999.dp),
                        color = BreBlack,
                        border = BorderStroke(1.dp, ObsidianBorder),
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("filter_by_publisher_button")
                    ) {
                        Text(
                            text = stringResource(R.string.publisher_view_all_apps) + " →",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = BreLightPink,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                        )
                    }
                }
            }
        }
    }
}
