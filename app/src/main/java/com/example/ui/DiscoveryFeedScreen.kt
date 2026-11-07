package com.example.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
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
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.unit.sp
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
import com.example.ui.theme.BreBlack
import com.example.ui.theme.BreDeepPink
import com.example.ui.theme.BreGray100
import com.example.ui.theme.BreGray600
import com.example.ui.theme.BreGray850
import com.example.ui.theme.BreLightPink
import com.example.ui.theme.BrePinkDark1
import com.example.ui.theme.BrePinkDark2
import com.example.ui.theme.BreWhite
import com.example.ui.theme.CyberMint
import com.example.ui.theme.ObsidianBorder
import com.example.ui.theme.ObsidianCardGlass
import com.example.ui.theme.ObsidianElevated
import com.example.ui.theme.ZineBoneCream

/**
 * Deepink "CHAOS FEED" NIP-5A Nsite Discovery & Curated Gallery Screen:
 * - Focused 100% on NIP-5A Nsites (zero NIP-5D Napplet tabs).
 * - Accepts search queries, d-tags, creator handles, `npub1…`, `naddr1…`, or `nsite.lol` URLs.
 * - Clean cards without raw protocol/dev clutter; tapping any group opens the editions/versions sheet.
 */
@Composable
fun DiscoveryFeedScreen(
    initialTabIsNsites: Boolean = true,
    initialPublisherPubkey: String? = null,
    launcherPrefs: LauncherPrefs,
    publisherProfiles: Map<String, PublisherProfileEntity> = emptyMap(),
    stackedGroups: List<StackedAppletGroup>,
    catalogApplets: List<InstalledAppletEntity>,
    localCuratedCount: Int,
    spamShieldEnabled: Boolean = true,
    blockedSpamCount: Int = 0,
    connectedRelays: Int,
    onLookupPublisher: (String) -> String? = { null },
    onOpenManualAddress: (String) -> Unit = {},
    onToggleViewMode: () -> Unit,
    onToggleDiscoveryMode: () -> Unit,
    onToggleSpamShield: () -> Unit = {},
    onRefreshCatalog: (Boolean) -> Unit = {},
    onToggleNip51Star: (InstalledAppletEntity) -> Unit,
    onPublishNip51List: () -> Unit,
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

    var searchQuery by rememberSaveable { mutableStateOf("") }
    var activePublisherFilterPubkey by rememberSaveable(initialPublisherPubkey) {
        mutableStateOf(initialPublisherPubkey?.lowercase())
    }
    var selectedCategoryName by rememberSaveable {
        mutableStateOf(GalleryCategory.ALL.name)
    }
    val selectedCategory = remember(selectedCategoryName) {
        runCatching { GalleryCategory.valueOf(selectedCategoryName) }.getOrDefault(GalleryCategory.ALL)
    }
    var inspectedStackGroup by remember { mutableStateOf<StackedAppletGroup?>(null) }

    LaunchedEffect(searchQuery) {
        val trimmed = searchQuery.trim()
        if (trimmed.startsWith("npub1", ignoreCase = true) ||
            trimmed.startsWith("nprofile1", ignoreCase = true) ||
            (trimmed.length == 64 && trimmed.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' })
        ) {
            val resolved = onLookupPublisher(trimmed)
            if (resolved != null) {
                activePublisherFilterPubkey = resolved
            }
        }
    }

    val queryLooksLikeDirectAddress = remember(searchQuery) {
        val q = searchQuery.trim().lowercase()
        q.startsWith("naddr1") || q.contains(".nsite.lol") || q.startsWith("https://") || q.startsWith("nostr:")
    }

    val filteredGroups = remember(
        stackedGroups,
        selectedCategory,
        searchQuery,
        activePublisherFilterPubkey,
        publisherProfiles
    ) {
        val q = searchQuery.trim().lowercase()
        val isKeyQuery = q.startsWith("npub1") || q.startsWith("nprofile1")
        stackedGroups.filter { group ->
            val matchesCat = selectedCategory == GalleryCategory.ALL ||
                group.categories.contains(selectedCategory)
            val matchesPublisherFilter = activePublisherFilterPubkey.isNullOrBlank() ||
                group.variants.any { it.applet.authorPubkey.equals(activePublisherFilterPubkey, ignoreCase = true) }
            val matchesQuery = q.isEmpty() || isKeyQuery ||
                group.primary.title.lowercase().contains(q) ||
                group.primary.description.lowercase().contains(q) ||
                group.primary.dTag.lowercase().contains(q) ||
                group.variants.any { v ->
                    val prof = publisherProfiles[v.applet.authorPubkey.lowercase()]
                    v.applet.authorNpub.lowercase().contains(q) ||
                        (prof != null && (
                            prof.displayName.lowercase().contains(q) ||
                                prof.username.lowercase().contains(q) ||
                                prof.nip05.lowercase().contains(q)
                            ))
                }
            matchesCat && matchesPublisherFilter && matchesQuery
        }
    }

    val featuredGroups = remember(stackedGroups) {
        stackedGroups.filter { it.isFeatured || it.isCuratedNip51 }
    }

    val filteredLiveFeedItems = remember(
        catalogApplets,
        searchQuery,
        activePublisherFilterPubkey,
        publisherProfiles
    ) {
        val q = searchQuery.trim().lowercase()
        val isKeyQuery = q.startsWith("npub1") || q.startsWith("nprofile1")
        catalogApplets.filter { item ->
            val prof = publisherProfiles[item.authorPubkey.lowercase()]
            val matchesPub = activePublisherFilterPubkey.isNullOrBlank() ||
                item.authorPubkey.equals(activePublisherFilterPubkey, ignoreCase = true)
            val matchesQuery = q.isEmpty() || isKeyQuery ||
                item.title.lowercase().contains(q) ||
                item.description.lowercase().contains(q) ||
                item.dTag.lowercase().contains(q) ||
                item.authorNpub.lowercase().contains(q) ||
                (prof != null && (
                    prof.displayName.lowercase().contains(q) ||
                        prof.username.lowercase().contains(q) ||
                        prof.nip05.lowercase().contains(q)
                    ))
            matchesPub && matchesQuery
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LauncherWallpaperLayer(prefs = launcherPrefs, extraScrimBoost = 0.24f)

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
        ) {
            val isWide = maxWidth >= 600.dp
            val gridColumns = if (isWide) 6 else 4

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 860.dp)
                    .align(Alignment.TopCenter)
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                // 1. Deepink "CHAOS FEED" Header Bar
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier
                            .size(40.dp)
                            .minimumInteractiveComponentSize()
                            .testTag("discovery_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back),
                            tint = ZineBoneCream
                        )
                    }

                    Text(
                        text = "CHAOS FEED",
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.4.sp
                        ),
                        color = ZineBoneCream,
                        modifier = Modifier.weight(1f)
                    )

                    // Curated Gallery vs Live Stream toggle
                    Surface(
                        onClick = onToggleDiscoveryMode,
                        shape = RoundedCornerShape(999.dp),
                        color = BreGray850,
                        border = BorderStroke(1.dp, BrePinkDark2),
                        modifier = Modifier.testTag("discovery_mode_toggle")
                    ) {
                        Text(
                            text = if (launcherPrefs.discoveryMode == DiscoveryDisplayMode.CURATED_GALLERY) {
                                "CURATED"
                            } else {
                                "LIVE STREAM"
                            },
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Black),
                            color = BreLightPink,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }

                    IconButton(
                        onClick = onToggleViewMode,
                        modifier = Modifier
                            .size(38.dp)
                            .testTag("discovery_view_mode_toggle")
                    ) {
                        Icon(
                            imageVector = if (launcherPrefs.viewMode == LauncherViewMode.ICON_GRID) {
                                Icons.AutoMirrored.Outlined.ViewList
                            } else {
                                Icons.Default.GridView
                            },
                            contentDescription = "Toggle grid/list",
                            tint = BreWhite
                        )
                    }

                    IconButton(
                        onClick = { onRefreshCatalog(false) },
                        modifier = Modifier
                            .size(38.dp)
                            .testTag("discovery_refresh_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Sync Relays",
                            tint = CyberMint
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // 2. Search Bar + Direct Address Launch Action
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    CompactSearchPill(
                        query = searchQuery,
                        onQueryChange = { searchQuery = it },
                        placeholder = stringResource(R.string.search_gallery_publishers_hint),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("discovery_search_input")
                    )

                    if (queryLooksLikeDirectAddress) {
                        Surface(
                            onClick = {
                                val target = searchQuery.trim()
                                searchQuery = ""
                                onOpenManualAddress(target)
                            },
                            shape = RoundedCornerShape(999.dp),
                            color = BreDeepPink
                        ) {
                            Text(
                                text = "OPEN URL >>>",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Black),
                                color = BreWhite,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // 3. Category Pills + Spam Shield
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("gallery_category_row")
                ) {
                    items(GalleryCategory.entries, key = { it.name }) { category ->
                        val isSelected = selectedCategory == category
                        FilterChip(
                            selected = isSelected,
                            onClick = { selectedCategoryName = category.name },
                            label = {
                                Text(
                                    text = "${category.glyph} ${category.label}",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            },
                            modifier = Modifier.testTag(
                                if (category == GalleryCategory.NSITES) "tab_nsites" else "cat_${category.name.lowercase()}"
                            )
                        )
                    }

                    item {
                        FilterChip(
                            selected = spamShieldEnabled,
                            onClick = onToggleSpamShield,
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Security,
                                    contentDescription = null,
                                    modifier = Modifier.size(13.dp)
                                )
                            },
                            label = {
                                Text(
                                    text = if (blockedSpamCount > 0) "Shield ($blockedSpamCount)" else "Shield",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            },
                            modifier = Modifier.testTag("spam_shield_chip")
                        )
                    }
                }

                // Active Publisher Spotlight Banner (when filtering by creator)
                if (!activePublisherFilterPubkey.isNullOrBlank()) {
                    val pubKey = activePublisherFilterPubkey!!
                    val prof = publisherProfiles[pubKey.lowercase()]
                    val pubNpub = prof?.npub ?: runCatching { NostrCrypto.encodeNpub(pubKey) }.getOrDefault(pubKey)
                    Spacer(modifier = Modifier.height(8.dp))
                    PublisherProfileCard(
                        profile = prof,
                        authorPubkey = pubKey,
                        authorNpub = pubNpub,
                        appCount = filteredGroups.size,
                        onClearFilter = {
                            activePublisherFilterPubkey = null
                            if (searchQuery.startsWith("npub1", ignoreCase = true)) {
                                searchQuery = ""
                            }
                        }
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // 4. Main Gallery or Live Feed Body
                Box(modifier = Modifier.weight(1f)) {
                    AnimatedContent(
                        targetState = launcherPrefs.discoveryMode,
                        label = "discovery_mode_content"
                    ) { mode ->
                        when (mode) {
                            DiscoveryDisplayMode.CURATED_GALLERY -> {
                                if (filteredGroups.isEmpty()) {
                                    EmptyDiscoveryState(
                                        isSearchActive = searchQuery.isNotBlank() ||
                                            selectedCategory != GalleryCategory.ALL ||
                                            !activePublisherFilterPubkey.isNullOrBlank(),
                                        emptyTitle = if (selectedCategory == GalleryCategory.NIP51_CURATED) {
                                            "No Curated Nsites Yet"
                                        } else {
                                            "No Matching Nsites"
                                        },
                                        emptySubtitle = "Try clearing your filter or paste an nsite.lol / naddr1 address above.",
                                        onResetFilter = {
                                            searchQuery = ""
                                            selectedCategoryName = GalleryCategory.ALL.name
                                            activePublisherFilterPubkey = null
                                        },
                                        onRefresh = { onRefreshCatalog(false) }
                                    )
                                } else if (launcherPrefs.viewMode == LauncherViewMode.ICON_GRID) {
                                    LazyVerticalGrid(
                                        columns = GridCells.Fixed(gridColumns),
                                        contentPadding = PaddingValues(bottom = 18.dp),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        verticalArrangement = Arrangement.spacedBy(14.dp),
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .testTag("gallery_icon_grid")
                                    ) {
                                        if ((selectedCategory == GalleryCategory.ALL || selectedCategory == GalleryCategory.FEATURED) &&
                                            searchQuery.isBlank() &&
                                            activePublisherFilterPubkey.isNullOrBlank() &&
                                            featuredGroups.isNotEmpty()
                                        ) {
                                            item(span = { GridItemSpan(maxLineSpan) }) {
                                                FeaturedSpotlightCarousel(
                                                    featuredGroups = featuredGroups,
                                                    publisherProfiles = publisherProfiles,
                                                    onSelectPublisher = { pk ->
                                                        onLookupPublisher(pk)
                                                        activePublisherFilterPubkey = pk
                                                    },
                                                    onSelectGroup = { grp -> inspectedStackGroup = grp }
                                                )
                                            }
                                        }

                                        items(filteredGroups, key = { it.groupId }) { group ->
                                            StackedGalleryGridTile(
                                                group = group,
                                                publisherProfile = publisherProfiles[group.primary.authorPubkey.lowercase()],
                                                onSelectGroup = { inspectedStackGroup = group },
                                                onQuickLaunchPrimary = { onOpenApplet(group.primary) }
                                            )
                                        }
                                    }
                                } else {
                                    LazyColumn(
                                        contentPadding = PaddingValues(bottom = 18.dp),
                                        verticalArrangement = Arrangement.spacedBy(10.dp),
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .testTag("gallery_list_column")
                                    ) {
                                        if ((selectedCategory == GalleryCategory.ALL || selectedCategory == GalleryCategory.FEATURED) &&
                                            searchQuery.isBlank() &&
                                            activePublisherFilterPubkey.isNullOrBlank() &&
                                            featuredGroups.isNotEmpty()
                                        ) {
                                            item {
                                                FeaturedSpotlightCarousel(
                                                    featuredGroups = featuredGroups,
                                                    publisherProfiles = publisherProfiles,
                                                    onSelectPublisher = { pk ->
                                                        onLookupPublisher(pk)
                                                        activePublisherFilterPubkey = pk
                                                    },
                                                    onSelectGroup = { grp -> inspectedStackGroup = grp }
                                                )
                                            }
                                        }

                                        items(filteredGroups, key = { it.groupId }) { group ->
                                            StackedGalleryListCard(
                                                group = group,
                                                publisherProfile = publisherProfiles[group.primary.authorPubkey.lowercase()],
                                                openNsitesExternally = launcherPrefs.openNsitesExternally,
                                                onSelectPublisher = { pk ->
                                                    onLookupPublisher(pk)
                                                    activePublisherFilterPubkey = pk
                                                },
                                                onSelectGroup = { inspectedStackGroup = group },
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

                            DiscoveryDisplayMode.LIVE_FEED -> {
                                if (filteredLiveFeedItems.isEmpty()) {
                                    EmptyDiscoveryState(
                                        isSearchActive = searchQuery.isNotBlank() || !activePublisherFilterPubkey.isNullOrBlank(),
                                        emptyTitle = "Listening for Live Nsites…",
                                        emptySubtitle = "Real-time stream of NIP-5A Nsite manifests arriving from your relay pool.",
                                        onResetFilter = {
                                            searchQuery = ""
                                            activePublisherFilterPubkey = null
                                        },
                                        onRefresh = { onRefreshCatalog(false) }
                                    )
                                } else {
                                    LazyColumn(
                                        contentPadding = PaddingValues(bottom = 16.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .testTag("discovery_feed_list")
                                    ) {
                                        items(filteredLiveFeedItems, key = { it.appletId }) { item ->
                                            LiveFeedCatalogCard(
                                                applet = item,
                                                publisherProfile = publisherProfiles[item.authorPubkey.lowercase()],
                                                openNsitesExternally = launcherPrefs.openNsitesExternally,
                                                onSelectPublisher = { pk ->
                                                    onLookupPublisher(pk)
                                                    activePublisherFilterPubkey = pk
                                                },
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
                }
            }
        }

        val activeGroup = inspectedStackGroup
        if (activeGroup != null) {
            StorefrontVersionPickerSheet(
                group = activeGroup,
                publisherProfiles = publisherProfiles,
                openNsitesExternally = launcherPrefs.openNsitesExternally,
                onSelectPublisher = { pk ->
                    onLookupPublisher(pk)
                    activePublisherFilterPubkey = pk
                },
                onDismiss = { inspectedStackGroup = null },
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
        modifier = modifier.height(40.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = null,
                tint = BreLightPink,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
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
        shape = RoundedCornerShape(16.dp),
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
                        .size(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(BreBlack)
                        .border(1.dp, BrePinkDark2, RoundedCornerShape(10.dp)),
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
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = ZineBoneCream
                        ) {
                            Text(
                                text = if (applet.isInstalled) "PINNED" else "ONLINE",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Black),
                                color = BreBlack,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(3.dp))

                    PublisherAttributionRow(
                        profile = publisherProfile,
                        authorPubkey = applet.authorPubkey,
                        authorNpub = applet.authorNpub,
                        dTag = null,
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
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val opensExternally = openNsitesExternally &&
                        applet.isNsite &&
                        applet.inlineHtml.isBlank() &&
                        ExternalSiteLauncher.resolveExternalUrl(applet) != null

                    CompactFeedPill(
                        label = "FORK",
                        filled = false,
                        outlined = false,
                        onClick = onFork,
                        modifier = Modifier.testTag("fork_applet_${applet.dTag}")
                    )
                    if (applet.isInstalled) {
                        CompactFeedPill(
                            label = "UNPIN",
                            filled = false,
                            outlined = true,
                            onClick = onInstallToggle
                        )
                        CompactFeedPill(
                            label = if (opensExternally) "LAUNCH ↗" else "LAUNCH >>>",
                            filled = true,
                            outlined = false,
                            onClick = onOpen,
                            modifier = Modifier.testTag("open_applet_${applet.dTag}")
                        )
                    } else {
                        CompactFeedPill(
                            label = if (opensExternally) "OPEN ↗" else "PREVIEW",
                            filled = false,
                            outlined = true,
                            onClick = onOpen,
                            modifier = Modifier.testTag("preview_applet_${applet.dTag}")
                        )
                        CompactFeedPill(
                            label = "INSTALL >>>",
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
        shape = RoundedCornerShape(8.dp),
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
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Black),
            color = if (filled) BreWhite else BreLightPink,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
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
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Black),
                color = ZineBoneCream,
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
                shape = RoundedCornerShape(10.dp),
                color = BreDeepPink
            ) {
                Text(
                    text = if (isSearchActive) "RESET FILTERS >>>" else "QUERY RELAYS NOW >>>",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Black),
                    color = BreWhite,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp)
                )
            }
        }
    }
}
