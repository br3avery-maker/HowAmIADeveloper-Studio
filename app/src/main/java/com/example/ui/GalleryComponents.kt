package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.InstalledAppletEntity
import com.example.data.PublisherProfileEntity
import com.example.data.StackedAppletGroup
import com.example.data.StackedVariant
import com.example.data.VariantRelation
import com.example.nostr.ExternalSiteLauncher
import com.example.nostr.NostrCrypto
import com.example.nostr.NsiteSpamFilter
import com.example.ui.theme.AmberSignal
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
import com.example.ui.theme.ObsidianElevated
import com.example.ui.theme.ZineBoneCream

@Composable
fun FeaturedSpotlightCarousel(
    featuredGroups: List<StackedAppletGroup>,
    publisherProfiles: Map<String, PublisherProfileEntity> = emptyMap(),
    onSelectPublisher: ((String) -> Unit)? = null,
    onSelectGroup: (StackedAppletGroup) -> Unit
) {
    if (featuredGroups.isEmpty()) return

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 2.dp)
        ) {
            Text(
                text = "☆ FEATURED & NIP-51 SPOTLIGHT",
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = BreLightPink
            )
            Text(
                text = "${featuredGroups.size} curated",
                style = MaterialTheme.typography.labelSmall,
                color = ZineBoneCream.copy(alpha = 0.78f)
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(horizontal = 2.dp),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("featured_spotlight_carousel")
        ) {
            items(featuredGroups.take(8), key = { "feat_${it.groupId}" }) { group ->
                val app = group.primary
                val pubProfile = publisherProfiles[app.authorPubkey.lowercase()]
                Surface(
                    onClick = { onSelectGroup(group) },
                    shape = RoundedCornerShape(16.dp),
                    color = Color.Transparent,
                    border = BorderStroke(
                        1.dp,
                        if (app.isNsite) ZineBoneCream.copy(alpha = 0.65f) else BreDeepPink.copy(alpha = 0.75f)
                    ),
                    modifier = Modifier.width(236.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .background(
                                Brush.linearGradient(
                                    colors = listOf(
                                        BreGray850.copy(alpha = 0.96f),
                                        BrePinkDark3.copy(alpha = 0.55f),
                                        BreBlack.copy(alpha = 0.96f)
                                    )
                                )
                            )
                            .padding(11.dp)
                    ) {
                        Column {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(BreBlack)
                                        .border(1.dp, BrePinkDark1, RoundedCornerShape(10.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = app.iconGlyph,
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = NsiteSpamFilter.sanitizeSingleLine(app.title),
                                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                                        color = BreWhite,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = buildString {
                                            append(if (app.isNsite) "NSITE" else "NIP-5D")
                                            if (group.hasMultipleEditions) append(" • ×${group.stackCount} stacked")
                                            else if (group.isCuratedNip51) append(" • NIP-51")
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (group.hasMultipleEditions) BreLightPink else ZineBoneCream,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(5.dp))
                            PublisherAttributionRow(
                                profile = pubProfile,
                                authorPubkey = app.authorPubkey,
                                authorNpub = app.authorNpub,
                                onClickPublisher = onSelectPublisher
                            )
                            Spacer(modifier = Modifier.height(5.dp))
                            Text(
                                text = NsiteSpamFilter.sanitizeSingleLine(app.description),
                                style = MaterialTheme.typography.bodySmall,
                                color = BreGray100,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Home-screen-matched 62dp squircle tile with tactile offset backing cards when 2+ clones/versions are stacked.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StackedGalleryGridTile(
    group: StackedAppletGroup,
    publisherProfile: PublisherProfileEntity? = null,
    onSelectGroup: () -> Unit,
    onQuickLaunchPrimary: () -> Unit
) {
    val applet = group.primary
    val borderColor = if (applet.isNsite) ZineBoneCream.copy(alpha = 0.82f) else BreDeepPink
    val publisherCaption = remember(publisherProfile, applet.authorNpub) {
        publisherProfile?.bestName ?: PublisherProfileEntity.shortNpub(applet.authorNpub)
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .combinedClickable(
                onClick = onSelectGroup,
                onLongClick = onQuickLaunchPrimary
            )
            .padding(vertical = 6.dp, horizontal = 4.dp)
            .testTag("gallery_tile_${applet.dTag}")
    ) {
        Box(
            modifier = Modifier.size(70.dp),
            contentAlignment = Alignment.Center
        ) {
            // Back-most 3rd plate when 3+ clones/versions are stacked
            if (group.stackCount >= 3) {
                Box(
                    modifier = Modifier
                        .size(58.dp)
                        .offset(x = 6.dp, y = (-5).dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(BreBlack.copy(alpha = 0.85f))
                        .border(1.dp, borderColor.copy(alpha = 0.40f), RoundedCornerShape(16.dp))
                )
            }
            // Middle 2nd plate when 2+ clones/versions are stacked
            if (group.hasMultipleEditions) {
                Box(
                    modifier = Modifier
                        .size(60.dp)
                        .offset(x = 3.dp, y = (-2.5).dp)
                        .clip(RoundedCornerShape(17.dp))
                        .background(BrePinkDark3.copy(alpha = 0.72f))
                        .border(1.dp, borderColor.copy(alpha = 0.65f), RoundedCornerShape(17.dp))
                )
            }

            // Foreground Home-screen-matched 62dp launcher squircle
            Box(
                modifier = Modifier
                    .size(62.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(BreGray850.copy(alpha = 0.94f))
                    .border(
                        width = 1.2.dp,
                        color = borderColor,
                        shape = RoundedCornerShape(18.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = applet.iconGlyph,
                    style = MaterialTheme.typography.headlineMedium,
                    color = if (applet.iconGlyph == "≠" || applet.iconGlyph == "☆") BreDeepPink else BreWhite
                )
            }

            // Top-right stack count pill (×N) OR Pinned check dot
            if (group.hasMultipleEditions) {
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = BreDeepPink,
                    border = BorderStroke(1.dp, BreBlack),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .testTag("stack_badge_${applet.dTag}")
                ) {
                    Text(
                        text = "×${group.stackCount}",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Black),
                        color = BreWhite,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                    )
                }
            } else if (group.anyInstalled) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(CyberMint)
                        .border(1.dp, BreBlack, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Pinned to Home",
                        tint = BreBlack,
                        modifier = Modifier.size(11.dp)
                    )
                }
            }

            // Bottom-end publisher avatar badge
            PublisherAvatar(
                profile = publisherProfile,
                fallbackNpub = applet.authorNpub,
                sizeDp = 19,
                modifier = Modifier.align(Alignment.BottomEnd)
            )

            // Bottom-start NIP-51 curated badge
            if (group.isCuratedNip51) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .size(17.dp)
                        .clip(CircleShape)
                        .background(BreBlack)
                        .border(1.dp, AmberSignal, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Bookmark,
                        contentDescription = "NIP-51 Curated",
                        tint = AmberSignal,
                        modifier = Modifier.size(10.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = NsiteSpamFilter.sanitizeSingleLine(applet.title),
            style = MaterialTheme.typography.labelLarge,
            color = BreWhite,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
        Text(
            text = buildString {
                append(publisherCaption)
                if (group.hasMultipleEditions && group.anyInstalled) append(" • ✓")
            },
            style = MaterialTheme.typography.labelSmall,
            color = if (applet.isNsite) ZineBoneCream else BreLightPink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * Home-screen-matched BreBre Specimen List Card with stacked backing lip when 2+ editions exist.
 */
@Composable
fun StackedGalleryListCard(
    group: StackedAppletGroup,
    publisherProfile: PublisherProfileEntity? = null,
    openNsitesExternally: Boolean = true,
    onSelectPublisher: ((String) -> Unit)? = null,
    onSelectGroup: () -> Unit,
    onQuickInstallOrOpen: () -> Unit
) {
    val applet = group.primary
    val cleanTitle = remember(applet.title) {
        NsiteSpamFilter.sanitizeSingleLine(applet.title).ifBlank { applet.dTag }
    }
    val cleanDesc = remember(applet.description) {
        NsiteSpamFilter.sanitizeSingleLine(applet.description)
    }

    Box(modifier = Modifier.fillMaxWidth()) {
        if (group.hasMultipleEditions) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .padding(horizontal = 6.dp)
                    .offset(y = 4.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(BrePinkDark3.copy(alpha = 0.55f))
                    .border(1.dp, BrePinkDark2, RoundedCornerShape(18.dp))
            )
        }

        Surface(
            onClick = onSelectGroup,
            shape = RoundedCornerShape(18.dp),
            color = BreGray850.copy(alpha = 0.94f),
            border = BorderStroke(
                1.dp,
                if (applet.isNsite) ZineBoneCream.copy(alpha = 0.55f) else BrePinkDark2
            ),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("gallery_list_card_${applet.dTag}")
        ) {
            Column(modifier = Modifier.padding(13.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(BrePinkDark3)
                            .border(
                                1.dp,
                                if (applet.isNsite) ZineBoneCream else BreDeepPink,
                                RoundedCornerShape(12.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = applet.iconGlyph,
                            style = MaterialTheme.typography.titleLarge,
                            color = BreWhite
                        )
                    }

                    Spacer(modifier = Modifier.width(11.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = cleanTitle,
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                color = BreWhite,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            ProtocolBadgePill(isNsite = applet.isNsite, nip07 = applet.enableNip07)
                        }

                        Spacer(modifier = Modifier.height(3.dp))

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            PublisherAttributionRow(
                                profile = publisherProfile,
                                authorPubkey = applet.authorPubkey,
                                authorNpub = applet.authorNpub,
                                dTag = applet.dTag,
                                onClickPublisher = onSelectPublisher,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            if (group.hasMultipleEditions) {
                                Surface(
                                    shape = RoundedCornerShape(999.dp),
                                    color = BrePinkDark3,
                                    border = BorderStroke(1.dp, BreDeepPink)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Layers,
                                            contentDescription = null,
                                            tint = BreLightPink,
                                            modifier = Modifier.size(11.dp)
                                        )
                                        Spacer(modifier = Modifier.width(3.dp))
                                        Text(
                                            text = "${group.stackCount} editions ▾",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = BreWhite,
                                            maxLines = 1,
                                            softWrap = false
                                        )
                                    }
                                }
                            }
                            if (group.isCuratedNip51) {
                                Text(
                                    text = "★ NIP-51",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = AmberSignal,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                    }
                }

                if (cleanDesc.isNotBlank()) {
                    Spacer(modifier = Modifier.height(7.dp))
                    Text(
                        text = cleanDesc,
                        style = MaterialTheme.typography.bodySmall,
                        color = BreGray100,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.height(9.dp))

                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = if (group.hasMultipleEditions) {
                            "Tap card to compare ${group.stackCount} versions / clones"
                        } else if (group.anyInstalled) {
                            "Pinned on Home Screen"
                        } else {
                            "Tap for manifest & NIP-51 options"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (group.anyInstalled) CyberMint else BreGray600,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    val opensExternally = openNsitesExternally &&
                        applet.isNsite &&
                        applet.inlineHtml.isBlank() &&
                        ExternalSiteLauncher.resolveExternalUrl(applet) != null

                    Surface(
                        onClick = onQuickInstallOrOpen,
                        shape = RoundedCornerShape(999.dp),
                        color = if (applet.isInstalled) BrePinkDark1 else BreDeepPink
                    ) {
                        Text(
                            text = if (applet.isInstalled) {
                                stringResource(R.string.action_installed_open) + if (opensExternally) " ↗" else " →"
                            } else {
                                stringResource(R.string.action_install)
                            },
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = BreWhite,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.padding(horizontal = 13.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Interactive Storefront & Stacked Version/Clone Picker Modal Bottom Sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorefrontVersionPickerSheet(
    group: StackedAppletGroup,
    publisherProfiles: Map<String, PublisherProfileEntity> = emptyMap(),
    openNsitesExternally: Boolean = true,
    onSelectPublisher: ((String) -> Unit)? = null,
    onDismiss: () -> Unit,
    onInstallToggle: (InstalledAppletEntity, Boolean) -> Unit,
    onOpenApplet: (InstalledAppletEntity) -> Unit,
    onOpenInApp: (InstalledAppletEntity) -> Unit = onOpenApplet,
    onForkToStudio: (InstalledAppletEntity) -> Unit,
    onToggleNip51Star: (InstalledAppletEntity) -> Unit,
    onPublishNip51List: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val primary = group.primary
    val primaryPublisher = publisherProfiles[primary.authorPubkey.lowercase()]

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = ObsidianElevated,
        modifier = Modifier.testTag("version_picker_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 6.dp)
        ) {
            // Header Summary
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(BreBlack)
                        .border(1.5.dp, BreDeepPink, RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = primary.iconGlyph,
                        style = MaterialTheme.typography.headlineSmall
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = NsiteSpamFilter.sanitizeSingleLine(primary.title),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = BreWhite,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ProtocolBadgePill(isNsite = primary.isNsite, nip07 = primary.enableNip07)
                        Text(
                            text = if (group.hasMultipleEditions) {
                                "${group.stackCount} stacked editions"
                            } else {
                                "Single verified edition"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = BreLightPink
                        )
                    }
                }

                // NIP-51 Star / Curate Toggle
                Surface(
                    onClick = { onToggleNip51Star(primary) },
                    shape = RoundedCornerShape(999.dp),
                    color = if (group.isLocalStarred) Color(0xFF2B220B) else BreBlack,
                    border = BorderStroke(
                        1.dp,
                        if (group.isLocalStarred) AmberSignal else ObsidianBorder
                    ),
                    modifier = Modifier.testTag("sheet_nip51_star_button")
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)
                    ) {
                        Icon(
                            imageVector = if (group.isLocalStarred) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                            contentDescription = "Curate to NIP-51 list",
                            tint = if (group.isLocalStarred) AmberSignal else BreGray100,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (group.isLocalStarred) "Curated" else "NIP-51",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (group.isLocalStarred) AmberSignal else BreGray100
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = NsiteSpamFilter.sanitizeSingleLine(primary.description),
                style = MaterialTheme.typography.bodySmall,
                color = BreGray100
            )

            Spacer(modifier = Modifier.height(8.dp))

            PublisherProfileCard(
                profile = primaryPublisher,
                authorPubkey = primary.authorPubkey,
                authorNpub = primary.authorNpub,
                onFilterByPublisher = onSelectPublisher?.let { fn ->
                    { pubkeyHex ->
                        onDismiss()
                        fn(pubkeyHex)
                    }
                }
            )

            if (group.isLocalStarred) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    onClick = onPublishNip51List,
                    shape = RoundedCornerShape(10.dp),
                    color = BreBlack,
                    border = BorderStroke(1.dp, AmberSignal.copy(alpha = 0.65f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("publish_nip51_list_button")
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = "Sign & Broadcast NIP-51 App List (Kind 30003)",
                            style = MaterialTheme.typography.labelMedium,
                            color = AmberSignal
                        )
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = null,
                            tint = AmberSignal,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(color = ObsidianBorder)
            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = if (group.hasMultipleEditions) {
                    "EDITIONS, CLONES & FORKS (${group.stackCount})"
                } else {
                    "EDITION DETAILS & ACTIONS"
                },
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = ZineBoneCream
            )

            Spacer(modifier = Modifier.height(6.dp))

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(group.variants, key = { it.applet.appletId }) { variant ->
                    VariantEditionCard(
                        variant = variant,
                        publisherProfile = publisherProfiles[variant.applet.authorPubkey.lowercase()],
                        openNsitesExternally = openNsitesExternally,
                        onSelectPublisher = onSelectPublisher?.let { fn ->
                            { pubkeyHex ->
                                onDismiss()
                                fn(pubkeyHex)
                            }
                        },
                        onInstallToggle = {
                            onInstallToggle(variant.applet, !variant.applet.isInstalled)
                        },
                        onOpen = {
                            onDismiss()
                            onOpenApplet(variant.applet)
                        },
                        onOpenInApp = {
                            onDismiss()
                            onOpenInApp(variant.applet)
                        },
                        onFork = {
                            onDismiss()
                            onForkToStudio(variant.applet)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun VariantEditionCard(
    variant: StackedVariant,
    publisherProfile: PublisherProfileEntity? = null,
    openNsitesExternally: Boolean,
    onSelectPublisher: ((String) -> Unit)? = null,
    onInstallToggle: () -> Unit,
    onOpen: () -> Unit,
    onOpenInApp: () -> Unit,
    onFork: () -> Unit
) {
    val clipboard = LocalClipboardManager.current
    val app = variant.applet
    val hasExternalUrl = remember(app) { ExternalSiteLauncher.resolveExternalUrl(app) != null }
    val opensExternallyByDefault = openNsitesExternally && app.isNsite && app.inlineHtml.isBlank() && hasExternalUrl
    val naddr = remember(app.kind, app.authorPubkey, app.dTag) {
        NostrCrypto.encodeNaddr(app.kind, app.authorPubkey, app.dTag)
    }
    val relationColor = when (variant.relation) {
        VariantRelation.PRIMARY -> CyberMint
        VariantRelation.IDENTICAL_CLONE -> AmberSignal
        VariantRelation.SAME_AUTHOR_VERSION -> BreLightPink
        VariantRelation.COMMUNITY_FORK -> ZineBoneCream
    }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = BreBlack,
        border = BorderStroke(
            1.dp,
            if (app.isInstalled) CyberMint.copy(alpha = 0.6f) else ObsidianBorder
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("variant_row_${app.dTag}")
    ) {
        Column(modifier = Modifier.padding(11.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = relationColor.copy(alpha = 0.16f),
                        border = BorderStroke(1.dp, relationColor.copy(alpha = 0.55f))
                    ) {
                        Text(
                            text = variant.relation.badgeLabel,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = relationColor,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    Text(
                        text = NsiteSpamFilter.sanitizeSingleLine(app.title),
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                        color = BreWhite,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                IconButton(
                    onClick = { clipboard.setText(AnnotatedString(naddr)) },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = "Copy naddr coordinate",
                        tint = BreGray100,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            PublisherAttributionRow(
                profile = publisherProfile,
                authorPubkey = app.authorPubkey,
                authorNpub = app.authorNpub,
                dTag = app.dTag,
                onClickPublisher = onSelectPublisher
            )

            Spacer(modifier = Modifier.height(2.dp))

            val shaLabel = if (app.indexSha256.isNotBlank()) {
                " • sha:${app.indexSha256.take(10)}…"
            } else {
                " • inline bundle"
            }
            Text(
                text = "Kind ${app.kind}$shaLabel",
                style = MaterialTheme.typography.labelSmall,
                color = BreGray600,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Fork",
                    style = MaterialTheme.typography.labelMedium,
                    color = BreLightPink,
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .clickable(onClick = onFork)
                        .padding(horizontal = 8.dp, vertical = 5.dp)
                        .testTag("fork_applet_${app.dTag}")
                )
                if (opensExternallyByDefault) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Surface(
                        onClick = onOpenInApp,
                        shape = RoundedCornerShape(999.dp),
                        color = BreGray850,
                        border = BorderStroke(1.dp, ObsidianBorder),
                        modifier = Modifier.testTag("open_in_app_${app.dTag}")
                    ) {
                        Text(
                            text = "In-App",
                            style = MaterialTheme.typography.labelMedium,
                            color = BreGray100,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(6.dp))
                if (app.isInstalled) {
                    Surface(
                        onClick = onInstallToggle,
                        shape = RoundedCornerShape(999.dp),
                        color = BreGray850,
                        border = BorderStroke(1.dp, BrePinkDark1)
                    ) {
                        Text(
                            text = "Unpin",
                            style = MaterialTheme.typography.labelMedium,
                            color = BreWhite,
                            modifier = Modifier.padding(horizontal = 11.dp, vertical = 5.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Surface(
                        onClick = onOpen,
                        shape = RoundedCornerShape(999.dp),
                        color = BreDeepPink,
                        modifier = Modifier.testTag("open_applet_${app.dTag}")
                    ) {
                        Text(
                            text = stringResource(R.string.action_installed_open) +
                                if (opensExternallyByDefault) " ↗" else " →",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = BreWhite,
                            modifier = Modifier.padding(horizontal = 13.dp, vertical = 5.dp)
                        )
                    }
                } else {
                    Surface(
                        onClick = onOpen,
                        shape = RoundedCornerShape(999.dp),
                        color = BreGray850,
                        border = BorderStroke(1.dp, ObsidianBorder),
                        modifier = Modifier.testTag("preview_applet_${app.dTag}")
                    ) {
                        Text(
                            text = if (opensExternallyByDefault) "Open ↗" else "Preview",
                            style = MaterialTheme.typography.labelMedium,
                            color = BreGray100,
                            modifier = Modifier.padding(horizontal = 11.dp, vertical = 5.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Surface(
                        onClick = onInstallToggle,
                        shape = RoundedCornerShape(999.dp),
                        color = BreDeepPink,
                        modifier = Modifier.testTag("install_applet_${app.dTag}")
                    ) {
                        Text(
                            text = stringResource(R.string.action_install),
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = BreWhite,
                            modifier = Modifier.padding(horizontal = 13.dp, vertical = 5.dp)
                        )
                    }
                }
            }
        }
    }
}
