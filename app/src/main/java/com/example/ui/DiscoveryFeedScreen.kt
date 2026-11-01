package com.example.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.InstalledAppletEntity
import com.example.nostr.NsiteSpamFilter
import com.example.ui.theme.BreBlack
import com.example.ui.theme.BreDeepPink
import com.example.ui.theme.BreGray100
import com.example.ui.theme.BreGray600
import com.example.ui.theme.BreLightPink
import com.example.ui.theme.BrePinkDark1
import com.example.ui.theme.BrePinkDark2
import com.example.ui.theme.BrePinkDark3
import com.example.ui.theme.BreWhite
import com.example.ui.theme.CyberMint
import com.example.ui.theme.ObsidianBase
import com.example.ui.theme.ObsidianBorder
import com.example.ui.theme.ObsidianElevated
import com.example.ui.theme.ZineBoneCream

@Composable
fun DiscoveryFeedScreen(
    initialTabIsNsites: Boolean,
    catalogApplets: List<InstalledAppletEntity>,
    spamShieldEnabled: Boolean = true,
    blockedSpamCount: Int = 0,
    connectedRelays: Int = 0,
    onToggleSpamShield: () -> Unit = {},
    onRefreshCatalog: (silent: Boolean) -> Unit = {},
    onInstallToggle: (InstalledAppletEntity, Boolean) -> Unit,
    onOpenApplet: (InstalledAppletEntity) -> Unit,
    onForkToStudio: (InstalledAppletEntity) -> Unit,
    onOpenCreatePublish: () -> Unit,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)

    LaunchedEffect(Unit) {
        onRefreshCatalog(true)
    }

    var selectedTab by rememberSaveable { mutableIntStateOf(if (initialTabIsNsites) 1 else 0) }
    var searchQuery by rememberSaveable { mutableStateOf("") }

    val nappletCount by remember(catalogApplets) {
        derivedStateOf { catalogApplets.count { !it.isNsite } }
    }
    val nsiteCount by remember(catalogApplets) {
        derivedStateOf { catalogApplets.count { it.isNsite } }
    }

    val filteredItems by remember(catalogApplets, selectedTab, searchQuery) {
        derivedStateOf {
            val wantNsite = selectedTab == 1
            val q = searchQuery.trim()
            catalogApplets.filter { item ->
                item.isNsite == wantNsite &&
                    (q.isEmpty() ||
                        item.title.contains(q, ignoreCase = true) ||
                        item.description.contains(q, ignoreCase = true) ||
                        item.dTag.contains(q, ignoreCase = true) ||
                        item.authorNpub.contains(q, ignoreCase = true))
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianBase)
            .windowInsetsPadding(WindowInsets.statusBars)
            .windowInsetsPadding(WindowInsets.navigationBars)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 720.dp)
                .align(Alignment.TopCenter)
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            // Compact Single-Line Top Bar: Back + 40dp Pill Search + Publish Pill
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Surface(
                    onClick = onBack,
                    shape = CircleShape,
                    color = ObsidianElevated,
                    border = BorderStroke(1.dp, ObsidianBorder),
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .size(38.dp)
                        .testTag("discovery_back_button")
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back),
                            tint = BreWhite,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                CompactSearchPill(
                    query = searchQuery,
                    onQueryChange = { searchQuery = it },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("discovery_search_input")
                )

                Surface(
                    onClick = onOpenCreatePublish,
                    shape = RoundedCornerShape(999.dp),
                    color = BrePinkDark3,
                    border = BorderStroke(1.dp, BreDeepPink),
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .height(38.dp)
                        .testTag("discovery_publish_button")
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 11.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            tint = BreWhite,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(
                            text = "Publish",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = BreWhite,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Compact Segmented Tabs: Napplets (NIP-5D) | Nsites (NIP-5A) with Live Counts
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = ObsidianElevated,
                border = BorderStroke(1.dp, ObsidianBorder),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(38.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(3.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    CompactSegmentTab(
                        selected = selectedTab == 0,
                        label = "${stringResource(R.string.tab_napplets)} ($nappletCount)",
                        onClick = { selectedTab = 0 },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("tab_napplets")
                    )
                    CompactSegmentTab(
                        selected = selectedTab == 1,
                        label = "${stringResource(R.string.tab_nsites)} ($nsiteCount)",
                        onClick = { selectedTab = 1 },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("tab_nsites")
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Slim Spam-Shield & Relay Sync Strip
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Surface(
                    onClick = onToggleSpamShield,
                    shape = RoundedCornerShape(999.dp),
                    color = if (spamShieldEnabled) Color(0xFF14241D) else ObsidianElevated,
                    border = BorderStroke(
                        1.dp,
                        if (spamShieldEnabled) CyberMint.copy(alpha = 0.55f) else ObsidianBorder
                    ),
                    modifier = Modifier.testTag("spam_shield_toggle")
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Shield,
                            contentDescription = "Toggle spam bot filter",
                            tint = if (spamShieldEnabled) CyberMint else BreGray100,
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        val shieldText = when {
                            !spamShieldEnabled -> "Spam Shield: OFF"
                            blockedSpamCount > 0 -> "Spam Shield • $blockedSpamCount bots filtered"
                            else -> "Spam Shield: ON"
                        }
                        Text(
                            text = shieldText,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (spamShieldEnabled) CyberMint else BreGray100,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }

                Surface(
                    onClick = { onRefreshCatalog(false) },
                    shape = RoundedCornerShape(999.dp),
                    color = ObsidianElevated,
                    border = BorderStroke(1.dp, ObsidianBorder),
                    modifier = Modifier.testTag("discovery_refresh_button")
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh manifests from relays",
                            tint = BreLightPink,
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (connectedRelays > 0) "Sync ($connectedRelays relays)" else "Sync Relays",
                            style = MaterialTheme.typography.labelSmall,
                            color = BreLightPink,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (filteredItems.isEmpty()) {
                EmptyDiscoveryState(
                    isSearchActive = searchQuery.isNotBlank(),
                    isNsiteTab = selectedTab == 1,
                    onClearSearch = { searchQuery = "" },
                    onRefresh = { onRefreshCatalog(false) }
                )
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .testTag("discovery_feed_list")
                ) {
                    items(filteredItems, key = { it.appletId }) { item ->
                        DiscoveryCatalogCard(
                            applet = item,
                            onInstallToggle = { onInstallToggle(item, !item.isInstalled) },
                            onOpen = { onOpenApplet(item) },
                            onFork = { onForkToStudio(item) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactSearchPill(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = ObsidianElevated,
        border = BorderStroke(1.dp, BrePinkDark2),
        modifier = modifier.height(38.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 11.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = null,
                tint = BreLightPink,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(7.dp))
            Box(
                contentAlignment = Alignment.CenterStart,
                modifier = Modifier.weight(1f)
            ) {
                if (query.isEmpty()) {
                    Text(
                        text = "Search apps, nsites, d-tags…",
                        style = MaterialTheme.typography.bodySmall,
                        color = BreGray600,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall.copy(color = BreWhite),
                    cursorBrush = SolidColor(BreDeepPink),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (query.isNotEmpty()) {
                IconButton(
                    onClick = { onQueryChange("") },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Clear,
                        contentDescription = "Clear search",
                        tint = BreGray100,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun CompactSegmentTab(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(9.dp),
        color = if (selected) BreDeepPink else Color.Transparent,
        modifier = modifier.fillMaxHeight()
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                ),
                color = if (selected) BreWhite else BreGray100,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun DiscoveryCatalogCard(
    applet: InstalledAppletEntity,
    onInstallToggle: () -> Unit,
    onOpen: () -> Unit,
    onFork: () -> Unit
) {
    val cleanTitle = remember(applet.title) {
        NsiteSpamFilter.sanitizeSingleLine(applet.title).ifBlank { applet.dTag }
    }
    val cleanDesc = remember(applet.description) {
        NsiteSpamFilter.sanitizeSingleLine(applet.description)
    }
    val shortAuthor = remember(applet.authorNpub) {
        if (applet.authorNpub.length > 18) {
            "${applet.authorNpub.take(11)}…${applet.authorNpub.takeLast(4)}"
        } else {
            applet.authorNpub
        }
    }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = ObsidianElevated,
        border = BorderStroke(1.dp, ObsidianBorder),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("discovery_card_${applet.dTag}")
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(BreBlack)
                        .border(
                            1.dp,
                            if (applet.isNsite) Color(0xFF3E3529) else BrePinkDark2,
                            RoundedCornerShape(10.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = applet.iconGlyph,
                        style = MaterialTheme.typography.titleMedium
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

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

                    Spacer(modifier = Modifier.height(2.dp))

                    Text(
                        text = "d:${applet.dTag} • $shortAuthor",
                        style = MaterialTheme.typography.labelSmall,
                        color = ZineBoneCream.copy(alpha = 0.78f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            if (cleanDesc.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = cleanDesc,
                    style = MaterialTheme.typography.bodySmall,
                    color = BreGray100,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Kind ${applet.kind}" + if (applet.isInstalled) " • Pinned" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (applet.isInstalled) CyberMint else BreGray600,
                    maxLines = 1,
                    softWrap = false
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CompactCardActionPill(
                        label = "Fork",
                        filled = false,
                        outlined = false,
                        onClick = onFork,
                        modifier = Modifier.testTag("fork_applet_${applet.dTag}")
                    )

                    if (applet.isInstalled) {
                        CompactCardActionPill(
                            label = "Unpin",
                            filled = false,
                            outlined = true,
                            onClick = onInstallToggle
                        )
                        CompactCardActionPill(
                            label = stringResource(R.string.action_installed_open),
                            filled = true,
                            outlined = false,
                            onClick = onOpen,
                            modifier = Modifier.testTag("open_applet_${applet.dTag}")
                        )
                    } else {
                        CompactCardActionPill(
                            label = stringResource(R.string.action_install),
                            filled = true,
                            outlined = false,
                            onClick = onInstallToggle,
                            modifier = Modifier.testTag("install_applet_${applet.dTag}")
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactCardActionPill(
    label: String,
    filled: Boolean,
    outlined: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(999.dp),
        color = when {
            filled -> BreDeepPink
            outlined -> BreBlack
            else -> Color.Transparent
        },
        border = when {
            outlined -> BorderStroke(1.dp, BrePinkDark1)
            else -> null
        },
        modifier = modifier
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = if (filled) BreWhite else BreLightPink,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
        )
    }
}

@Composable
private fun EmptyDiscoveryState(
    isSearchActive: Boolean,
    isNsiteTab: Boolean,
    onClearSearch: () -> Unit,
    onRefresh: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = if (isSearchActive) "No matching manifests" else if (isNsiteTab) "Syncing Nsites…" else "Syncing Napplets…",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = BreWhite
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = if (isSearchActive) {
                "Try a shorter d-tag, title keyword, or clear your search filter."
            } else {
                "Querying connected relays for verified NIP-5D & NIP-5A manifests with Spam Shield active."
            },
            style = MaterialTheme.typography.bodySmall,
            color = BreGray100,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(14.dp))
        Surface(
            onClick = if (isSearchActive) onClearSearch else onRefresh,
            shape = RoundedCornerShape(999.dp),
            color = BreDeepPink
        ) {
            Text(
                text = if (isSearchActive) "Clear Search" else "Query Relays Now",
                style = MaterialTheme.typography.labelLarge,
                color = BreWhite,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 9.dp)
            )
        }
    }
}
