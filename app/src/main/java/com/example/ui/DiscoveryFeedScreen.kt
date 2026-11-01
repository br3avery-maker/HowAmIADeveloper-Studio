package com.example.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DynamicFeed
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.ViewModule
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
import com.example.data.DiscoveryDisplayMode
import com.example.data.GalleryCategory
import com.example.data.InstalledAppletEntity
import com.example.data.LauncherPrefs
import com.example.data.LauncherViewMode
import com.example.data.PublisherProfileEntity
import com.example.data.StackedAppletGroup
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
import com.example.ui.theme.ObsidianCardGlass
import com.example.ui.theme.ObsidianElevated
import com.example.ui.theme.ZineBoneCream

@Composable
fun DiscoveryFeedScreen(
    initialTabIsNsites: Boolean,
    initialPublisherPubkey: String? = null,
    launcherPrefs: LauncherPrefs = LauncherPrefs(),
    publisherProfiles: Map<String, PublisherProfileEntity> = emptyMap(),
    stackedGroups: List<StackedAppletGroup> = emptyList(),
    catalogApplets: List<InstalledAppletEntity>,
    localCuratedCount: Int = 0,
    spamShieldEnabled: Boolean = true,
    blockedSpamCount: Int = 0,
    connectedRelays: Int = 0,
    onLookupPublisher: (String) -> String? = { null },
    onToggleViewMode: () -> Unit = {},
    onToggleDiscoveryMode: () -> Unit = {},
    onToggleSpamShield: () -> Unit = {},
    onRefreshCatalog: (silent: Boolean) -> Unit = {},
    onToggleNip51Star: (InstalledAppletEntity) -> Unit = {},
    onPublishNip51List: () -> Unit = {},
    onInstallToggle: (InstalledAppletEntity, Boolean) -> Unit,
    onOpenApplet: (InstalledAppletEntity) -> Unit,
    onOpenAppletInApp: (InstalledAppletEntity) -> Unit = onOpenApplet,
    onForkToStudio: (InstalledAppletEntity) -> Unit,
    onOpenCreatePublish: () -> Unit,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)

    LaunchedEffect(Unit) {
        onRefreshCatalog(true)
    }

    var selectedCategoryName by rememberSaveable {
        mutableStateOf(
            if (initialTabIsNsites) GalleryCategory.NSITES.name else GalleryCategory.ALL.name
        )
    }
    val selectedCategory = remember(selectedCategoryName) {
        runCatching { GalleryCategory.valueOf(selectedCategoryName) }.getOrDefault(GalleryCategory.ALL)
    }

    var liveFeedTab by rememberSaveable { mutableIntStateOf(if (initialTabIsNsites) 1 else 0) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var inspectedGroupId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedPublisherPubkey by rememberSaveable(initialPublisherPubkey) {
        mutableStateOf(initialPublisherPubkey?.trim()?.lowercase()?.takeIf { it.length == 64 })
    }

    val queryResolvedPubkeyHex = remember(searchQuery) {
        NostrCrypto.resolvePubkeyHex(searchQuery)
    }

    LaunchedEffect(queryResolvedPubkeyHex) {
        if (queryResolvedPubkeyHex != null) {
            onLookupPublisher(queryResolvedPubkeyHex)
        }
    }

    val activePublisherFilterHex = selectedPublisherPubkey ?: queryResolvedPubkeyHex

    val selectPublisherAction: (String) -> Unit = { rawPk ->
        val resolved = onLookupPublisher(rawPk) ?: NostrCrypto.resolvePubkeyHex(rawPk) ?: rawPk.lowercase()
        if (resolved.length == 64) {
            selectedPublisherPubkey = resolved
        }
    }

    val activeInspectedGroup = remember(stackedGroups, inspectedGroupId) {
        inspectedGroupId?.let { id ->
            stackedGroups.firstOrNull { g ->
                g.groupId == id || g.variants.any { v -> v.applet.appletId == id }
            }
        }
    }

    val featuredGroups by remember(stackedGroups) {
        derivedStateOf { stackedGroups.filter { it.isFeatured } }
    }

    val filteredGroups by remember(stackedGroups, selectedCategory, searchQuery, activePublisherFilterHex, publisherProfiles) {
        derivedStateOf {
            val q = searchQuery.trim()
            stackedGroups.filter { group ->
                val catMatch = selectedCategory in group.categories
                val pubFilterMatch = activePublisherFilterHex == null || group.variants.any { v ->
                    v.applet.authorPubkey.equals(activePublisherFilterHex, ignoreCase = true)
                }
                val queryMatch = q.isEmpty() || queryResolvedPubkeyHex != null || group.variants.any { v ->
                    val app = v.applet
                    val prof = publisherProfiles[app.authorPubkey.lowercase()]
                    app.title.contains(q, ignoreCase = true) ||
                        app.description.contains(q, ignoreCase = true) ||
                        app.dTag.contains(q, ignoreCase = true) ||
                        app.authorNpub.contains(q, ignoreCase = true) ||
                        app.authorPubkey.contains(q, ignoreCase = true) ||
                        prof?.displayName?.contains(q, ignoreCase = true) == true ||
                        prof?.username?.contains(q, ignoreCase = true) == true ||
                        prof?.nip05?.contains(q, ignoreCase = true) == true
                }
                catMatch && pubFilterMatch && queryMatch
            }
        }
    }

    val liveFeedItems by remember(catalogApplets, liveFeedTab, searchQuery, activePublisherFilterHex, publisherProfiles) {
        derivedStateOf {
            val wantNsite = liveFeedTab == 1
            val q = searchQuery.trim()
            catalogApplets
                .filter { item ->
                    val prof = publisherProfiles[item.authorPubkey.lowercase()]
                    val pubFilterMatch = activePublisherFilterHex == null ||
                        item.authorPubkey.equals(activePublisherFilterHex, ignoreCase = true)
                    val queryMatch = q.isEmpty() || queryResolvedPubkeyHex != null ||
                        item.title.contains(q, ignoreCase = true) ||
                        item.description.contains(q, ignoreCase = true) ||
                        item.dTag.contains(q, ignoreCase = true) ||
                        item.authorNpub.contains(q, ignoreCase = true) ||
                        item.authorPubkey.contains(q, ignoreCase = true) ||
                        prof?.displayName?.contains(q, ignoreCase = true) == true ||
                        prof?.username?.contains(q, ignoreCase = true) == true ||
                        prof?.nip05?.contains(q, ignoreCase = true) == true
                    item.isNsite == wantNsite && pubFilterMatch && queryMatch
                }
                .sortedByDescending { it.updatedAt }
        }
    }

    val isGalleryMode = launcherPrefs.discoveryMode == DiscoveryDisplayMode.CURATED_GALLERY

    Box(modifier = Modifier.fillMaxSize()) {
        // 1. Same Custom Home Screen Wallpaper Layer for Seamless Launcher Continuity
        LauncherWallpaperLayer(prefs = launcherPrefs, extraScrimBoost = 0.16f)

        // 2. Responsive Storefront / Gallery Foreground matching Home Screen
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .windowInsetsPadding(WindowInsets.navigationBars)
        ) {
            val isWide = maxWidth >= 600.dp
            val gridColumns = if (isWide) 6 else 4

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 840.dp)
                    .align(Alignment.TopCenter)
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                // Top Bar: Back + Single-Line Search + Grid/List Toggle + Gallery|Feed Mode + Publish
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Surface(
                        onClick = onBack,
                        shape = CircleShape,
                        color = ObsidianCardGlass,
                        border = BorderStroke(1.dp, BrePinkDark2),
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
                        placeholder = stringResource(R.string.search_gallery_publishers_hint),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("discovery_search_input")
                    )

                    if (isGalleryMode) {
                        // Exact same Icon Grid / Specimen List Toggle as LauncherHomeScreen
                        Surface(
                            onClick = onToggleViewMode,
                            shape = CircleShape,
                            color = ObsidianCardGlass,
                            border = BorderStroke(1.dp, BrePinkDark2),
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .size(38.dp)
                                .testTag("discovery_view_mode_toggle")
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = if (launcherPrefs.viewMode == LauncherViewMode.ICON_GRID) {
                                        Icons.AutoMirrored.Filled.ViewList
                                    } else {
                                        Icons.Default.ViewModule
                                    },
                                    contentDescription = stringResource(R.string.cd_toggle_launcher_view),
                                    tint = BreLightPink,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }

                    // Toggle between Curated Gallery (default) & Optional Live Relay Feed
                    Surface(
                        onClick = onToggleDiscoveryMode,
                        shape = RoundedCornerShape(999.dp),
                        color = if (isGalleryMode) BrePinkDark3 else ObsidianCardGlass,
                        border = BorderStroke(
                            1.dp,
                            if (isGalleryMode) BreDeepPink else ZineBoneCream.copy(alpha = 0.65f)
                        ),
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .height(38.dp)
                            .testTag("discovery_mode_toggle")
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 9.dp)
                        ) {
                            Icon(
                                imageVector = if (isGalleryMode) Icons.Default.AutoAwesome else Icons.Default.DynamicFeed,
                                contentDescription = "Switch between Curated Gallery and Live Relay Feed",
                                tint = if (isGalleryMode) BreWhite else ZineBoneCream,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (isGalleryMode) "Gallery" else "Feed",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                color = if (isGalleryMode) BreWhite else ZineBoneCream,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }

                    Surface(
                        onClick = onOpenCreatePublish,
                        shape = RoundedCornerShape(999.dp),
                        color = BreDeepPink,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .height(38.dp)
                            .testTag("discovery_publish_button")
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = "Publish new Napplet or Nsite",
                                tint = BreWhite,
                                modifier = Modifier.size(16.dp)
                            )
                            if (isWide) {
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
                }

                Spacer(modifier = Modifier.height(8.dp))

                val featuredCreatorHex = NostrCrypto.DEFAULT_FEATURED_CREATOR_PUBKEY_HEX
                val featuredCreatorProfile = publisherProfiles[featuredCreatorHex]
                val isFeaturedCreatorFiltered = activePublisherFilterHex?.equals(featuredCreatorHex, ignoreCase = true) == true

                // Telemetry & Curation Status Strip (Featured Creator + Spam Shield + NIP-51 Sync + Relay Refresh)
                GalleryStatusStrip(
                    isGalleryMode = isGalleryMode,
                    stackedCount = stackedGroups.size,
                    totalRawCleaned = catalogApplets.size,
                    localCuratedCount = localCuratedCount,
                    spamShieldEnabled = spamShieldEnabled,
                    blockedSpamCount = blockedSpamCount,
                    connectedRelays = connectedRelays,
                    featuredCreatorLabel = featuredCreatorProfile?.takeIf { it.hasResolvedIdentity }?.bestName
                        ?: "Creator Nsites",
                    isFeaturedCreatorSelected = isFeaturedCreatorFiltered,
                    onToggleFeaturedCreator = {
                        if (isFeaturedCreatorFiltered) {
                            selectedPublisherPubkey = null
                            if (queryResolvedPubkeyHex != null) searchQuery = ""
                        } else if (featuredCreatorHex.length == 64) {
                            selectPublisherAction(featuredCreatorHex)
                        }
                    },
                    onToggleSpamShield = onToggleSpamShield,
                    onPublishNip51List = onPublishNip51List,
                    onRefresh = { onRefreshCatalog(false) }
                )

                if (activePublisherFilterHex != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    val pubProfile = publisherProfiles[activePublisherFilterHex]
                    val fallbackNpub = remember(activePublisherFilterHex) {
                        runCatching { NostrCrypto.encodeNpub(activePublisherFilterHex) }.getOrDefault(activePublisherFilterHex)
                    }
                    val authorAppsCount = remember(catalogApplets, activePublisherFilterHex) {
                        catalogApplets.count { it.authorPubkey.equals(activePublisherFilterHex, ignoreCase = true) }
                    }
                    PublisherProfileCard(
                        profile = pubProfile,
                        authorPubkey = activePublisherFilterHex,
                        authorNpub = fallbackNpub,
                        appCount = authorAppsCount,
                        onClearFilter = {
                            selectedPublisherPubkey = null
                            if (queryResolvedPubkeyHex != null) searchQuery = ""
                        }
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Main Body: Curated Storefront Gallery (Default) vs Optional Chronological Live Feed
                AnimatedContent(
                    targetState = isGalleryMode,
                    transitionSpec = {
                        fadeIn(spring(stiffness = Spring.StiffnessMediumLow)) togetherWith
                            fadeOut(spring(stiffness = Spring.StiffnessMedium))
                    },
                    label = "gallery_vs_feed_switch",
                    modifier = Modifier.weight(1f)
                ) { galleryActive ->
                    if (galleryActive) {
                        Column(modifier = Modifier.fillMaxSize()) {
                            // Curated Category & NIP-51 Filter Chips
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                contentPadding = PaddingValues(horizontal = 2.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("gallery_category_row")
                            ) {
                                items(GalleryCategory.entries, key = { it.name }) { category ->
                                    val count = stackedGroups.count { category in it.categories }
                                    val selected = selectedCategory == category
                                    val chipTag = when (category) {
                                        GalleryCategory.NAPPLETS -> "tab_napplets"
                                        GalleryCategory.NSITES -> "tab_nsites"
                                        else -> "category_chip_${category.name.lowercase()}"
                                    }
                                    Surface(
                                        onClick = { selectedCategoryName = category.name },
                                        shape = RoundedCornerShape(999.dp),
                                        color = if (selected) BreDeepPink else BreGray850.copy(alpha = 0.90f),
                                        border = BorderStroke(
                                            1.dp,
                                            if (selected) BreLightPink else ObsidianBorder
                                        ),
                                        modifier = Modifier.testTag(chipTag)
                                    ) {
                                        Text(
                                            text = "${category.glyph} ${category.label} ($count)",
                                            style = MaterialTheme.typography.labelMedium.copy(
                                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                                            ),
                                            color = if (selected) BreWhite else BreGray100,
                                            maxLines = 1,
                                            softWrap = false,
                                            modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp)
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            if (filteredGroups.isEmpty()) {
                                EmptyDiscoveryState(
                                    isSearchActive = searchQuery.isNotBlank() ||
                                        selectedCategory != GalleryCategory.ALL ||
                                        activePublisherFilterHex != null,
                                    emptyTitle = when {
                                        activePublisherFilterHex != null -> "Querying publisher Nsites & Kind 0…"
                                        selectedCategory == GalleryCategory.NIP51_CURATED -> "No NIP-51 Curated Apps Yet"
                                        else -> "No matching gallery apps"
                                    },
                                    emptySubtitle = when {
                                        activePublisherFilterHex != null ->
                                            "Connected relays and NIP-65 outbox relays are being queried for manifests signed by ${PublisherProfileEntity.shortNpub(runCatching { NostrCrypto.encodeNpub(activePublisherFilterHex) }.getOrDefault(activePublisherFilterHex))}."
                                        selectedCategory == GalleryCategory.NIP51_CURATED ->
                                            "Tap any app tile and press the NIP-51 bookmark button to build & sign your own Kind 30003 curated gallery list."
                                        else ->
                                            "Clear filters or sync connected relays to pull live NIP-5D & NIP-5A manifests."
                                    },
                                    onResetFilter = {
                                        searchQuery = ""
                                        selectedPublisherPubkey = null
                                        selectedCategoryName = GalleryCategory.ALL.name
                                    },
                                    onRefresh = { onRefreshCatalog(false) }
                                )
                            } else {
                                val showSpotlight = searchQuery.isBlank() &&
                                    activePublisherFilterHex == null &&
                                    (selectedCategory == GalleryCategory.ALL || selectedCategory == GalleryCategory.FEATURED) &&
                                    featuredGroups.isNotEmpty()

                                AnimatedContent(
                                    targetState = launcherPrefs.viewMode,
                                    transitionSpec = {
                                        fadeIn(spring(stiffness = Spring.StiffnessMediumLow)) togetherWith
                                            fadeOut(spring(stiffness = Spring.StiffnessMedium))
                                    },
                                    label = "gallery_grid_vs_list",
                                    modifier = Modifier.fillMaxSize()
                                ) { mode ->
                                    when (mode) {
                                        LauncherViewMode.ICON_GRID -> {
                                            LazyVerticalGrid(
                                                columns = GridCells.Fixed(gridColumns),
                                                contentPadding = PaddingValues(bottom = 20.dp),
                                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                                verticalArrangement = Arrangement.spacedBy(16.dp),
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .testTag("gallery_icon_grid")
                                            ) {
                                                if (showSpotlight) {
                                                    item(
                                                        key = "spotlight_header",
                                                        span = { GridItemSpan(maxLineSpan) }
                                                    ) {
                                                        Column {
                                                            FeaturedSpotlightCarousel(
                                                                featuredGroups = featuredGroups,
                                                                publisherProfiles = publisherProfiles,
                                                                onSelectPublisher = selectPublisherAction,
                                                                onSelectGroup = { inspectedGroupId = it.groupId }
                                                            )
                                                            Spacer(modifier = Modifier.height(6.dp))
                                                            Text(
                                                                text = "STACKED GALLERY • TAP TILE FOR EDITIONS, LONG-PRESS TO OPEN",
                                                                style = MaterialTheme.typography.labelSmall,
                                                                color = ZineBoneCream.copy(alpha = 0.72f),
                                                                modifier = Modifier.padding(horizontal = 2.dp)
                                                            )
                                                        }
                                                    }
                                                }

                                                items(filteredGroups, key = { it.groupId }) { group ->
                                                    StackedGalleryGridTile(
                                                        group = group,
                                                        publisherProfile = publisherProfiles[group.primary.authorPubkey.lowercase()],
                                                        onSelectGroup = { inspectedGroupId = group.groupId },
                                                        onQuickLaunchPrimary = { onOpenApplet(group.primary) }
                                                    )
                                                }
                                            }
                                        }

                                        LauncherViewMode.DESCRIPTION_LIST -> {
                                            LazyColumn(
                                                contentPadding = PaddingValues(bottom = 20.dp),
                                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .testTag("gallery_description_list")
                                            ) {
                                                if (showSpotlight) {
                                                    item(key = "spotlight_list_header") {
                                                        FeaturedSpotlightCarousel(
                                                            featuredGroups = featuredGroups,
                                                            publisherProfiles = publisherProfiles,
                                                            onSelectPublisher = selectPublisherAction,
                                                            onSelectGroup = { inspectedGroupId = it.groupId }
                                                        )
                                                    }
                                                }

                                                items(filteredGroups, key = { it.groupId }) { group ->
                                                    StackedGalleryListCard(
                                                        group = group,
                                                        publisherProfile = publisherProfiles[group.primary.authorPubkey.lowercase()],
                                                        openNsitesExternally = launcherPrefs.openNsitesExternally,
                                                        onSelectPublisher = selectPublisherAction,
                                                        onSelectGroup = { inspectedGroupId = group.groupId },
                                                        onQuickInstallOrOpen = {
                                                            if (group.primary.isInstalled) {
                                                                onOpenApplet(group.primary)
                                                            } else {
                                                                onInstallToggle(group.primary, true)
                                                            }
                                                        }
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        // Optional Raw Chronological Live Relay Feed
                        LiveRelayStreamPanel(
                            selectedTab = liveFeedTab,
                            onSelectTab = { liveFeedTab = it },
                            nappletCount = catalogApplets.count { !it.isNsite },
                            nsiteCount = catalogApplets.count { it.isNsite },
                            items = liveFeedItems,
                            publisherProfiles = publisherProfiles,
                            openNsitesExternally = launcherPrefs.openNsitesExternally,
                            isSearchActive = searchQuery.isNotBlank() || activePublisherFilterHex != null,
                            onClearSearch = {
                                searchQuery = ""
                                selectedPublisherPubkey = null
                            },
                            onRefresh = { onRefreshCatalog(false) },
                            onSelectPublisher = selectPublisherAction,
                            onInstallToggle = onInstallToggle,
                            onOpenApplet = onOpenApplet,
                            onForkToStudio = onForkToStudio
                        )
                    }
                }
            }
        }

        // Storefront & Stacked Version/Clone Picker Bottom Sheet
        if (activeInspectedGroup != null) {
            StorefrontVersionPickerSheet(
                group = activeInspectedGroup,
                publisherProfiles = publisherProfiles,
                openNsitesExternally = launcherPrefs.openNsitesExternally,
                onSelectPublisher = selectPublisherAction,
                onDismiss = { inspectedGroupId = null },
                onInstallToggle = onInstallToggle,
                onOpenApplet = onOpenApplet,
                onOpenInApp = onOpenAppletInApp,
                onForkToStudio = onForkToStudio,
                onToggleNip51Star = onToggleNip51Star,
                onPublishNip51List = onPublishNip51List
            )
        }
    }
}

@Composable
private fun GalleryStatusStrip(
    isGalleryMode: Boolean,
    stackedCount: Int,
    totalRawCleaned: Int,
    localCuratedCount: Int,
    spamShieldEnabled: Boolean,
    blockedSpamCount: Int,
    connectedRelays: Int,
    featuredCreatorLabel: String,
    isFeaturedCreatorSelected: Boolean,
    onToggleFeaturedCreator: () -> Unit,
    onToggleSpamShield: () -> Unit,
    onPublishNip51List: () -> Unit,
    onRefresh: () -> Unit
) {
    val collapsedClones = (totalRawCleaned - stackedCount).coerceAtLeast(0)

    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        item {
            Surface(
                onClick = onToggleFeaturedCreator,
                shape = RoundedCornerShape(999.dp),
                color = if (isFeaturedCreatorSelected) BreDeepPink else BrePinkDark3.copy(alpha = 0.72f),
                border = BorderStroke(
                    1.dp,
                    if (isFeaturedCreatorSelected) BreWhite else BreDeepPink
                ),
                modifier = Modifier.testTag("featured_creator_filter_chip")
            ) {
                Text(
                    text = "☆ $featuredCreatorLabel",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = BreWhite,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
                )
            }
        }
        item {
            Surface(
                onClick = onToggleSpamShield,
                shape = RoundedCornerShape(999.dp),
                color = if (spamShieldEnabled) Color(0xFF12241C) else ObsidianCardGlass,
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
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = when {
                            !spamShieldEnabled -> "Spam Shield: OFF"
                            blockedSpamCount > 0 -> "Shield • $blockedSpamCount bots hidden"
                            else -> "Spam Shield: ON"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (spamShieldEnabled) CyberMint else BreGray100,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }

        if (isGalleryMode && collapsedClones > 0) {
            item {
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = BrePinkDark3.copy(alpha = 0.78f),
                    border = BorderStroke(1.dp, BrePinkDark1)
                ) {
                    Text(
                        text = "Stacked $collapsedClones clones/versions",
                        style = MaterialTheme.typography.labelSmall,
                        color = BreLightPink,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
                    )
                }
            }
        }

        if (localCuratedCount > 0) {
            item {
                Surface(
                    onClick = onPublishNip51List,
                    shape = RoundedCornerShape(999.dp),
                    color = Color(0xFF28200B),
                    border = BorderStroke(1.dp, AmberSignal.copy(alpha = 0.65f)),
                    modifier = Modifier.testTag("strip_publish_nip51_button")
                ) {
                    Text(
                        text = "★ Sync NIP-51 ($localCuratedCount)",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = AmberSignal,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
                    )
                }
            }
        }

        item {
            Surface(
                onClick = onRefresh,
                shape = RoundedCornerShape(999.dp),
                color = ObsidianCardGlass,
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
                        text = if (connectedRelays > 0) "Sync ($connectedRelays)" else "Sync Relays",
                        style = MaterialTheme.typography.labelSmall,
                        color = BreLightPink,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }
    }
}

@Composable
private fun LiveRelayStreamPanel(
    selectedTab: Int,
    onSelectTab: (Int) -> Unit,
    nappletCount: Int,
    nsiteCount: Int,
    items: List<InstalledAppletEntity>,
    publisherProfiles: Map<String, PublisherProfileEntity>,
    openNsitesExternally: Boolean,
    isSearchActive: Boolean,
    onClearSearch: () -> Unit,
    onRefresh: () -> Unit,
    onSelectPublisher: (String) -> Unit,
    onInstallToggle: (InstalledAppletEntity, Boolean) -> Unit,
    onOpenApplet: (InstalledAppletEntity) -> Unit,
    onForkToStudio: (InstalledAppletEntity) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = ObsidianCardGlass,
            border = BorderStroke(1.dp, ObsidianBorder),
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp)
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
                    onClick = { onSelectTab(0) },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("tab_napplets")
                )
                CompactSegmentTab(
                    selected = selectedTab == 1,
                    label = "${stringResource(R.string.tab_nsites)} ($nsiteCount)",
                    onClick = { onSelectTab(1) },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("tab_nsites")
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (items.isEmpty()) {
            EmptyDiscoveryState(
                isSearchActive = isSearchActive,
                emptyTitle = if (isSearchActive) "No matching feed events" else "Listening for live manifests…",
                emptySubtitle = "Unstacked chronological stream of NIP-5D & NIP-5A manifests arriving from your relay pool.",
                onResetFilter = onClearSearch,
                onRefresh = onRefresh
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
                items(items, key = { it.appletId }) { item ->
                    LiveFeedCatalogCard(
                        applet = item,
                        publisherProfile = publisherProfiles[item.authorPubkey.lowercase()],
                        openNsitesExternally = openNsitesExternally,
                        onSelectPublisher = onSelectPublisher,
                        onInstallToggle = { onInstallToggle(item, !item.isInstalled) },
                        onOpen = { onOpenApplet(item) },
                        onFork = { onForkToStudio(item) }
                    )
                }
            }
        }
    }
}

@Composable
private fun CompactSearchPill(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = ObsidianCardGlass,
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
            Spacer(modifier = Modifier.width(6.dp))
            Box(
                contentAlignment = Alignment.CenterStart,
                modifier = Modifier.weight(1f)
            ) {
                if (query.isEmpty()) {
                    Text(
                        text = placeholder,
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
private fun LiveFeedCatalogCard(
    applet: InstalledAppletEntity,
    publisherProfile: PublisherProfileEntity?,
    openNsitesExternally: Boolean,
    onSelectPublisher: (String) -> Unit,
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

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = BreGray850.copy(alpha = 0.94f),
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

                    Spacer(modifier = Modifier.height(3.dp))

                    PublisherAttributionRow(
                        profile = publisherProfile,
                        authorPubkey = applet.authorPubkey,
                        authorNpub = applet.authorNpub,
                        dTag = applet.dTag,
                        onClickPublisher = onSelectPublisher
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
                    val opensExternally = openNsitesExternally &&
                        applet.isNsite &&
                        applet.inlineHtml.isBlank() &&
                        ExternalSiteLauncher.resolveExternalUrl(applet) != null

                    CompactFeedPill(
                        label = "Fork",
                        filled = false,
                        outlined = false,
                        onClick = onFork,
                        modifier = Modifier.testTag("fork_applet_${applet.dTag}")
                    )
                    if (applet.isInstalled) {
                        CompactFeedPill(
                            label = "Unpin",
                            filled = false,
                            outlined = true,
                            onClick = onInstallToggle
                        )
                        CompactFeedPill(
                            label = stringResource(R.string.action_installed_open) +
                                if (opensExternally) " ↗" else "",
                            filled = true,
                            outlined = false,
                            onClick = onOpen,
                            modifier = Modifier.testTag("open_applet_${applet.dTag}")
                        )
                    } else {
                        CompactFeedPill(
                            label = if (opensExternally) "Open ↗" else "Preview",
                            filled = false,
                            outlined = true,
                            onClick = onOpen,
                            modifier = Modifier.testTag("preview_applet_${applet.dTag}")
                        )
                        CompactFeedPill(
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
private fun CompactFeedPill(
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
        border = if (outlined) BorderStroke(1.dp, BrePinkDark1) else null,
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
    emptyTitle: String,
    emptySubtitle: String,
    onResetFilter: () -> Unit,
    onRefresh: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = ObsidianElevated.copy(alpha = 0.88f),
        border = BorderStroke(1.dp, ObsidianBorder),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 18.dp)
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = emptyTitle,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = BreWhite,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = emptySubtitle,
                style = MaterialTheme.typography.bodySmall,
                color = BreGray100,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(14.dp))
            Surface(
                onClick = if (isSearchActive) onResetFilter else onRefresh,
                shape = RoundedCornerShape(999.dp),
                color = BreDeepPink
            ) {
                Text(
                    text = if (isSearchActive) "Reset Filters" else "Query Relays Now",
                    style = MaterialTheme.typography.labelLarge,
                    color = BreWhite,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 9.dp)
                )
            }
        }
    }
}
