package com.example.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.R
import com.example.data.InstalledAppletEntity
import com.example.data.LauncherPrefs
import com.example.data.LauncherViewMode
import com.example.data.PublisherProfileEntity
import com.example.data.VaultAccountEntity
import com.example.data.WallpaperStyle
import com.example.nostr.ExternalSiteLauncher
import com.example.nostr.NostrCrypto
import com.example.ui.theme.BreBlack
import com.example.ui.theme.BreDeepPink
import com.example.ui.theme.BreGray100
import com.example.ui.theme.BreGray600
import com.example.ui.theme.BreGray850
import com.example.ui.theme.BreGray900
import com.example.ui.theme.BreLightPink
import com.example.ui.theme.BrePinkDark1
import com.example.ui.theme.BrePinkDark2
import com.example.ui.theme.BrePinkDark3
import com.example.ui.theme.BreWhite
import com.example.ui.theme.CyberMint
import com.example.ui.theme.ZineBoneCream
import java.io.File

/**
 * Deepink Arcade "THE LOBBY" Home Launcher Screen:
 * - Top-Left: Signed-in Nostr account profile icon (opens Amethyst/Ditto side drawer).
 * - Bold Bone-Cream Header ("SELECT NSITE") + Grid/List toggle + Explore Search shortcut.
 * - Direct Manual NIP-5A Address / URL Launch Bar (`nsite.lol`, `naddr1…`, `npub1…`, `user@domain`).
 * - Hero Spotlight Card + Saved Library Grid/List with zero raw developer plumbing projected onto cards.
 */
@Composable
fun LauncherHomeScreen(
    activeAccount: VaultAccountEntity?,
    launcherPrefs: LauncherPrefs,
    installedApplets: List<InstalledAppletEntity>,
    publisherProfiles: Map<String, PublisherProfileEntity> = emptyMap(),
    connectedRelays: Int,
    totalRelays: Int,
    onOpenSearchFeed: () -> Unit,
    onLaunchManualAddress: (String) -> Unit = {},
    onForkManualAddress: (String) -> Unit = {},
    onInspectPublisher: (String) -> Unit = {},
    onSyncRelays: () -> Unit = {},
    onToggleViewMode: () -> Unit,
    onOpenAccountDrawer: () -> Unit,
    onLaunchApplet: (InstalledAppletEntity) -> Unit,
    onLaunchInApp: (InstalledAppletEntity) -> Unit = onLaunchApplet,
    onLaunchExternal: ((InstalledAppletEntity) -> Unit)? = null,
    onForkApplet: (InstalledAppletEntity) -> Unit,
    onToggleNip07: (InstalledAppletEntity) -> Unit,
    onUnpinApplet: (InstalledAppletEntity) -> Unit,
    onOpenAppletPermissions: () -> Unit,
    onOpenNotesComposer: () -> Unit,
    onOpenRelayManager: () -> Unit,
    onOpenStudio: () -> Unit
) {
    var inspectedManifestApplet by remember { mutableStateOf<InstalledAppletEntity?>(null) }
    var manualAddressInput by rememberSaveable { mutableStateOf("") }

    Box(modifier = Modifier.fillMaxSize()) {
        LauncherWallpaperLayer(prefs = launcherPrefs)

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
                    .widthIn(max = 840.dp)
                    .align(Alignment.TopCenter)
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                // 1. Top Header: Signed-in Nostr Profile Icon (Left) + "SELECT NSITE" + Search & View Toggle
                LauncherTopBar(
                    activeAccount = activeAccount,
                    viewMode = launcherPrefs.viewMode,
                    connectedRelays = connectedRelays,
                    onOpenSearchFeed = onOpenSearchFeed,
                    onToggleViewMode = onToggleViewMode,
                    onOpenAccountDrawer = onOpenAccountDrawer
                )

                Spacer(modifier = Modifier.height(10.dp))

                // 2. Direct NIP-5A Address / URL Launch Bar (nsite.lol, naddr1, npub1, user@domain)
                ManualAddressLaunchBar(
                    addressInput = manualAddressInput,
                    onAddressChange = { manualAddressInput = it },
                    onLaunchAddress = {
                        onLaunchManualAddress(manualAddressInput)
                        manualAddressInput = ""
                    },
                    onForkAddress = {
                        onForkManualAddress(manualAddressInput)
                        manualAddressInput = ""
                    }
                )

                Spacer(modifier = Modifier.height(12.dp))

                // 3. Main Lobby Canvas
                Box(modifier = Modifier.weight(1f)) {
                    if (installedApplets.isEmpty()) {
                        EmptyLauncherPlaceholder(
                            connectedRelays = connectedRelays,
                            onSyncRelays = onSyncRelays,
                            onOpenSearchFeed = onOpenSearchFeed
                        )
                    } else {
                        AnimatedContent(
                            targetState = launcherPrefs.viewMode,
                            transitionSpec = {
                                fadeIn(spring(stiffness = Spring.StiffnessMediumLow)) togetherWith
                                    fadeOut(spring(stiffness = Spring.StiffnessMedium))
                            },
                            label = "launcher_mode_switch"
                        ) { mode ->
                            when (mode) {
                                LauncherViewMode.ICON_GRID -> {
                                    Column(modifier = Modifier.fillMaxSize()) {
                                        // Deepink Arcade Hero Spotlight Card for top pinned Nsite
                                        val heroSite = installedApplets.first()
                                        DeepinkLobbyHeroCard(
                                            applet = heroSite,
                                            publisherProfile = publisherProfiles[heroSite.authorPubkey.lowercase()],
                                            onLaunch = { onLaunchApplet(heroSite) },
                                            onFork = { onForkApplet(heroSite) },
                                            onInspect = { inspectedManifestApplet = heroSite }
                                        )

                                        Spacer(modifier = Modifier.height(14.dp))

                                        LazyVerticalGrid(
                                            columns = GridCells.Fixed(gridColumns),
                                            contentPadding = PaddingValues(vertical = 6.dp),
                                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                                            verticalArrangement = Arrangement.spacedBy(16.dp),
                                            modifier = Modifier
                                                .weight(1f)
                                                .fillMaxWidth()
                                                .testTag("launcher_icon_grid")
                                        ) {
                                            items(installedApplets, key = { it.appletId }) { applet ->
                                                LauncherGridTile(
                                                    applet = applet,
                                                    publisherProfile = publisherProfiles[applet.authorPubkey.lowercase()],
                                                    onLaunch = { onLaunchApplet(applet) },
                                                    onLaunchInApp = { onLaunchInApp(applet) },
                                                    onLaunchExternal = onLaunchExternal?.let { fn -> { fn(applet) } },
                                                    onInspectPublisher = { onInspectPublisher(applet.authorPubkey) },
                                                    onViewManifest = { inspectedManifestApplet = applet },
                                                    onOpenPermissions = onOpenAppletPermissions,
                                                    onFork = { onForkApplet(applet) },
                                                    onToggleNip07 = { onToggleNip07(applet) },
                                                    onUnpin = { onUnpinApplet(applet) }
                                                )
                                            }
                                        }
                                    }
                                }

                                LauncherViewMode.DESCRIPTION_LIST -> {
                                    LazyColumn(
                                        contentPadding = PaddingValues(vertical = 6.dp),
                                        verticalArrangement = Arrangement.spacedBy(12.dp),
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .testTag("launcher_description_list")
                                    ) {
                                        items(installedApplets, key = { it.appletId }) { applet ->
                                            val opensExternally = launcherPrefs.openNsitesExternally &&
                                                applet.isNsite &&
                                                applet.inlineHtml.isBlank() &&
                                                ExternalSiteLauncher.resolveExternalUrl(applet) != null
                                            LauncherDescriptionRow(
                                                applet = applet,
                                                publisherProfile = publisherProfiles[applet.authorPubkey.lowercase()],
                                                opensExternally = opensExternally,
                                                onLaunch = { onLaunchApplet(applet) },
                                                onLaunchInApp = { onLaunchInApp(applet) },
                                                onLaunchExternal = onLaunchExternal?.let { fn -> { fn(applet) } },
                                                onInspectPublisher = { onInspectPublisher(applet.authorPubkey) },
                                                onViewManifest = { inspectedManifestApplet = applet },
                                                onOpenPermissions = onOpenAppletPermissions,
                                                onFork = { onForkApplet(applet) },
                                                onToggleNip07 = { onToggleNip07(applet) },
                                                onUnpin = { onUnpinApplet(applet) }
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

        val manifestTarget = inspectedManifestApplet
        if (manifestTarget != null) {
            ManifestInspectorDialog(
                applet = manifestTarget,
                publisherProfile = publisherProfiles[manifestTarget.authorPubkey.lowercase()],
                onInspectPublisher = { pubkeyHex ->
                    inspectedManifestApplet = null
                    onInspectPublisher(pubkeyHex)
                },
                onFork = {
                    val target = manifestTarget
                    inspectedManifestApplet = null
                    onForkApplet(target)
                },
                onDismiss = { inspectedManifestApplet = null }
            )
        }
    }
}

@Composable
private fun ManualAddressLaunchBar(
    addressInput: String,
    onAddressChange: (String) -> Unit,
    onLaunchAddress: () -> Unit,
    onForkAddress: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = BreGray900.copy(alpha = 0.94f),
        border = BorderStroke(1.dp, BrePinkDark2),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Box(
                contentAlignment = Alignment.CenterStart,
                modifier = Modifier
                    .weight(1f)
                    .height(36.dp)
            ) {
                if (addressInput.isEmpty()) {
                    Text(
                        text = stringResource(R.string.manual_address_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = BreGray600,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                BasicTextField(
                    value = addressInput,
                    onValueChange = onAddressChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall.copy(color = BreWhite),
                    cursorBrush = SolidColor(BreDeepPink),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("launcher_manual_address_input")
                )
            }

            if (addressInput.isNotBlank()) {
                Surface(
                    onClick = onForkAddress,
                    shape = RoundedCornerShape(8.dp),
                    color = BreGray850,
                    border = BorderStroke(1.dp, BrePinkDark1),
                    modifier = Modifier.testTag("launcher_manual_fork_button")
                ) {
                    Text(
                        text = "FORK",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Black),
                        color = BreLightPink,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
            }

            Surface(
                onClick = onLaunchAddress,
                shape = RoundedCornerShape(8.dp),
                color = BreDeepPink,
                modifier = Modifier.testTag("launcher_manual_open_button")
            ) {
                Text(
                    text = "LAUNCH >>>",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Black),
                    color = BreWhite,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                )
            }
        }
    }
}

@Composable
private fun DeepinkLobbyHeroCard(
    applet: InstalledAppletEntity,
    publisherProfile: PublisherProfileEntity?,
    onLaunch: () -> Unit,
    onFork: () -> Unit,
    onInspect: () -> Unit
) {
    val pubCaption = publisherProfile?.bestName ?: PublisherProfileEntity.shortNpub(applet.authorNpub)

    Surface(
        onClick = onLaunch,
        shape = RoundedCornerShape(20.dp),
        color = BreGray850.copy(alpha = 0.96f),
        border = BorderStroke(1.5.dp, BreDeepPink),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("lobby_hero_card")
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(BreBlack)
                            .border(1.5.dp, BreDeepPink, RoundedCornerShape(16.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = applet.iconGlyph,
                            style = MaterialTheme.typography.headlineMedium
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = applet.title.uppercase(),
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Black,
                                letterSpacing = 0.8.sp
                            ),
                            color = BreWhite,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = applet.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = BreGray100,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "By $pubCaption" + if (applet.cachedComplete) " • Verified Offline" else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = BreLightPink
                        )
                    }
                }

                // Taped Bone-Cream "ONLINE NOW" badge from Deepink Arcade mockup
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = ZineBoneCream
                ) {
                    Text(
                        text = "ONLINE NOW",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Black),
                        color = BreBlack,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Surface(
                    onClick = onLaunch,
                    shape = RoundedCornerShape(12.dp),
                    color = BreDeepPink,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.padding(vertical = 11.dp)
                    ) {
                        Text(
                            text = "PLAY / LAUNCH NSITE >>>",
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontWeight = FontWeight.Black,
                                letterSpacing = 0.8.sp
                            ),
                            color = BreWhite
                        )
                    }
                }

                Surface(
                    onClick = onFork,
                    shape = RoundedCornerShape(12.dp),
                    color = BreBlack,
                    border = BorderStroke(1.dp, BrePinkDark2)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.CallSplit,
                            contentDescription = "Fork",
                            tint = BreLightPink,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "FORK",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = BreLightPink
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun LauncherWallpaperLayer(
    prefs: LauncherPrefs,
    extraScrimBoost: Float = 0f
) {
    Box(modifier = Modifier.fillMaxSize().background(BreBlack)) {
        when (prefs.wallpaperStyle) {
            WallpaperStyle.CYBER_ZINE -> {
                Image(
                    painter = painterResource(id = R.drawable.img_zine_wallpaper),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }

            WallpaperStyle.CUSTOM_IMAGE -> {
                val file = remember(prefs.customWallpaperPath) { File(prefs.customWallpaperPath) }
                if (prefs.customWallpaperPath.isNotBlank() && file.exists()) {
                    AsyncImage(
                        model = file,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Image(
                        painter = painterResource(id = R.drawable.img_zine_wallpaper),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            WallpaperStyle.DEFAULT_AURORA -> {
                Image(
                    painter = painterResource(id = R.drawable.img_default_wallpaper),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }

            WallpaperStyle.BREBRE_NEON_ORBS -> {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawRect(color = BreBlack)
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                BreDeepPink.copy(alpha = 0.50f),
                                BrePinkDark3.copy(alpha = 0.28f),
                                Color.Transparent
                            ),
                            center = Offset(size.width * 0.92f, size.height * 0.06f),
                            radius = size.minDimension * 0.72f
                        ),
                        radius = size.minDimension * 0.72f,
                        center = Offset(size.width * 0.92f, size.height * 0.06f)
                    )
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                BrePinkDark1.copy(alpha = 0.42f),
                                BrePinkDark3.copy(alpha = 0.22f),
                                Color.Transparent
                            ),
                            center = Offset(size.width * 0.08f, size.height * 0.94f),
                            radius = size.minDimension * 0.65f
                        ),
                        radius = size.minDimension * 0.65f,
                        center = Offset(size.width * 0.08f, size.height * 0.94f)
                    )
                }
            }

            WallpaperStyle.AGENT_CIRCUIT -> {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawRect(color = BreBlack)
                    val step = 42.dp.toPx()
                    var x = 0f
                    while (x < size.width) {
                        drawLine(
                            color = BrePinkDark3.copy(alpha = 0.35f),
                            start = Offset(x, 0f),
                            end = Offset(x, size.height),
                            strokeWidth = 1f
                        )
                        x += step
                    }
                    var y = 0f
                    while (y < size.height) {
                        drawLine(
                            color = BrePinkDark3.copy(alpha = 0.35f),
                            start = Offset(0f, y),
                            end = Offset(size.width, y),
                            strokeWidth = 1f
                        )
                        y += step
                    }
                    drawCircle(
                        color = BrePinkDark2.copy(alpha = 0.25f),
                        radius = size.minDimension * 0.36f,
                        center = Offset(size.width / 2f, size.height * 0.45f),
                        style = Stroke(width = 3.dp.toPx())
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(BreBlack.copy(alpha = (prefs.scrimDimAlpha + extraScrimBoost).coerceIn(0.10f, 0.90f)))
        )
    }
}

@Composable
private fun LauncherTopBar(
    activeAccount: VaultAccountEntity?,
    viewMode: LauncherViewMode,
    connectedRelays: Int,
    onOpenSearchFeed: () -> Unit,
    onToggleViewMode: () -> Unit,
    onOpenAccountDrawer: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Top-Left Signed-In Nostr Account Profile Icon (opens Amethyst/Ditto Left Drawer)
        Box(
            modifier = Modifier
                .size(48.dp)
                .minimumInteractiveComponentSize()
                .clip(CircleShape)
                .clickable(onClick = onOpenAccountDrawer)
                .testTag("account_drawer_button"),
            contentAlignment = Alignment.Center
        ) {
            AccountAvatarThumbnail(
                account = activeAccount,
                sizeDp = 46,
                contentDescription = stringResource(R.string.cd_open_account_drawer)
            )
        }

        // Bold Condensed Bone-Cream Header ("SELECT NSITE")
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "SELECT NSITE",
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.4.sp
                ),
                color = ZineBoneCream,
                maxLines = 1
            )
            Text(
                text = "${activeAccount?.displayName?.ifBlank { "REBEL LOBBY" } ?: "REBEL LOBBY"} • $connectedRelays ONLINE",
                style = MaterialTheme.typography.labelSmall,
                color = BreLightPink,
                maxLines = 1
            )
        }

        // Search Feed Button
        Surface(
            onClick = onOpenSearchFeed,
            shape = CircleShape,
            color = BreGray900.copy(alpha = 0.92f),
            border = BorderStroke(1.dp, BrePinkDark2),
            modifier = Modifier
                .size(48.dp)
                .minimumInteractiveComponentSize()
                .testTag("launcher_search_bar")
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = "Search Chaos Feed",
                    tint = BreDeepPink
                )
            }
        }

        // Toggle for Icon Grid / List
        Surface(
            onClick = onToggleViewMode,
            shape = CircleShape,
            color = BreGray900.copy(alpha = 0.92f),
            border = BorderStroke(1.dp, BrePinkDark2),
            modifier = Modifier
                .size(48.dp)
                .minimumInteractiveComponentSize()
                .testTag("launcher_view_toggle")
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = if (viewMode == LauncherViewMode.ICON_GRID) {
                        Icons.AutoMirrored.Outlined.ViewList
                    } else {
                        Icons.Default.GridView
                    },
                    contentDescription = stringResource(R.string.cd_toggle_launcher_view),
                    tint = BreWhite
                )
            }
        }
    }
}

fun parseHexColorSafe(hex: String?, fallback: Color = BreDeepPink): Color {
    if (hex.isNullOrBlank()) return fallback
    return runCatching {
        Color(android.graphics.Color.parseColor(hex))
    }.getOrDefault(fallback)
}

@Composable
fun AccountAvatarThumbnail(
    account: VaultAccountEntity?,
    sizeDp: Int,
    contentDescription: String?
) {
    val ringColor = remember(account?.badgeColorHex) {
        parseHexColorSafe(account?.badgeColorHex, BreDeepPink)
    }
    val localFile = remember(account?.localAvatarPath) {
        account?.localAvatarPath?.takeIf { it.isNotBlank() }?.let { File(it) }
    }

    Box(
        modifier = Modifier.size(sizeDp.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val r = size.minDimension / 2f
            drawCircle(
                color = ringColor,
                radius = r - 1.dp.toPx(),
                style = Stroke(width = 2.dp.toPx())
            )
            drawCircle(
                color = ZineBoneCream.copy(alpha = 0.75f),
                radius = r - 4.dp.toPx(),
                style = Stroke(
                    width = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 5f), 0f)
                )
            )
        }

        val innerSize = (sizeDp - 10).coerceAtLeast(24).dp
        if (localFile != null && localFile.exists()) {
            AsyncImage(
                model = localFile,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(innerSize)
                    .clip(CircleShape)
            )
        } else if (!account?.remoteAvatarUrl.isNullOrBlank()) {
            AsyncImage(
                model = account?.remoteAvatarUrl,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(innerSize)
                    .clip(CircleShape)
            )
        } else {
            val label = account?.badgeGlyph?.takeIf { it.isNotBlank() }
                ?: account?.displayName?.trim()?.take(2)?.uppercase()
                ?: "≠"
            Box(
                modifier = Modifier
                    .size(innerSize)
                    .clip(CircleShape)
                    .background(ZineBoneCream),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Black),
                    color = BreBlack
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LauncherGridTile(
    applet: InstalledAppletEntity,
    publisherProfile: PublisherProfileEntity?,
    onLaunch: () -> Unit,
    onLaunchInApp: () -> Unit,
    onLaunchExternal: (() -> Unit)?,
    onInspectPublisher: () -> Unit,
    onViewManifest: () -> Unit,
    onOpenPermissions: () -> Unit,
    onFork: () -> Unit,
    onToggleNip07: () -> Unit,
    onUnpin: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val publisherCaption = remember(publisherProfile, applet.authorNpub) {
        publisherProfile?.bestName ?: PublisherProfileEntity.shortNpub(applet.authorNpub)
    }

    Box(contentAlignment = Alignment.TopCenter) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .combinedClickable(
                    onClick = onLaunch,
                    onLongClick = { menuExpanded = true }
                )
                .padding(vertical = 6.dp, horizontal = 4.dp)
                .testTag("launcher_tile_${applet.dTag}")
        ) {
            Box(
                modifier = Modifier.size(66.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(62.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(BreGray850.copy(alpha = 0.94f))
                        .border(
                            width = 1.2.dp,
                            color = BreDeepPink,
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

                PublisherAvatar(
                    profile = publisherProfile,
                    fallbackNpub = applet.authorNpub,
                    sizeDp = 20,
                    modifier = Modifier.align(Alignment.BottomEnd)
                )
            }
            Spacer(modifier = Modifier.height(5.dp))
            Text(
                text = applet.title,
                style = MaterialTheme.typography.labelLarge,
                color = BreWhite,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
            Text(
                text = publisherCaption,
                style = MaterialTheme.typography.labelSmall,
                color = ZineBoneCream,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }

        AppletContextDropdown(
            expanded = menuExpanded,
            applet = applet,
            publisherProfile = publisherProfile,
            onDismiss = { menuExpanded = false },
            onLaunchInApp = onLaunchInApp,
            onLaunchExternal = onLaunchExternal,
            onInspectPublisher = onInspectPublisher,
            onViewManifest = onViewManifest,
            onOpenPermissions = onOpenPermissions,
            onFork = onFork,
            onToggleNip07 = onToggleNip07,
            onUnpin = onUnpin
        )
    }
}

@Composable
private fun LauncherDescriptionRow(
    applet: InstalledAppletEntity,
    publisherProfile: PublisherProfileEntity?,
    opensExternally: Boolean,
    onLaunch: () -> Unit,
    onLaunchInApp: () -> Unit,
    onLaunchExternal: (() -> Unit)?,
    onInspectPublisher: () -> Unit,
    onViewManifest: () -> Unit,
    onOpenPermissions: () -> Unit,
    onFork: () -> Unit,
    onToggleNip07: () -> Unit,
    onUnpin: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Surface(
        onClick = onLaunch,
        shape = RoundedCornerShape(20.dp),
        color = BreGray850.copy(alpha = 0.94f),
        border = BorderStroke(1.dp, BrePinkDark2),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("launcher_list_item_${applet.dTag}")
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(BrePinkDark3)
                            .border(1.dp, BreDeepPink, RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = applet.iconGlyph,
                            style = MaterialTheme.typography.titleLarge,
                            color = BreWhite
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = applet.title,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = BreWhite,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(3.dp))
                        PublisherAttributionRow(
                            profile = publisherProfile,
                            authorPubkey = applet.authorPubkey,
                            authorNpub = applet.authorNpub,
                            dTag = null,
                            onClickPublisher = { onInspectPublisher() }
                        )
                    }
                }

                Box {
                    IconButton(
                        onClick = { menuExpanded = true },
                        modifier = Modifier.minimumInteractiveComponentSize()
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "Nsite options",
                            tint = BreGray100
                        )
                    }
                    AppletContextDropdown(
                        expanded = menuExpanded,
                        applet = applet,
                        publisherProfile = publisherProfile,
                        onDismiss = { menuExpanded = false },
                        onLaunchInApp = onLaunchInApp,
                        onLaunchExternal = onLaunchExternal,
                        onInspectPublisher = onInspectPublisher,
                        onViewManifest = onViewManifest,
                        onOpenPermissions = onOpenPermissions,
                        onFork = onFork,
                        onToggleNip07 = onToggleNip07,
                        onUnpin = onUnpin
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = applet.description,
                style = MaterialTheme.typography.bodySmall,
                color = BreGray100,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    onClick = onLaunch,
                    shape = RoundedCornerShape(10.dp),
                    color = BreDeepPink
                ) {
                    Text(
                        text = if (opensExternally) "LAUNCH ↗" else "LAUNCH >>>",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Black),
                        color = BreWhite,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }

                Surface(
                    onClick = onFork,
                    shape = RoundedCornerShape(10.dp),
                    color = BreBlack,
                    border = BorderStroke(1.dp, BrePinkDark2)
                ) {
                    Text(
                        text = "FORK",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = BreLightPink,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun ProtocolBadgePill(isNsite: Boolean, nip07: Boolean) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = ZineBoneCream
    ) {
        Text(
            text = "LIVE",
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Black),
            color = BreBlack,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun AppletContextDropdown(
    expanded: Boolean,
    applet: InstalledAppletEntity,
    publisherProfile: PublisherProfileEntity?,
    onDismiss: () -> Unit,
    onLaunchInApp: () -> Unit,
    onLaunchExternal: (() -> Unit)?,
    onInspectPublisher: () -> Unit,
    onViewManifest: () -> Unit,
    onOpenPermissions: () -> Unit,
    onFork: () -> Unit,
    onToggleNip07: () -> Unit,
    onUnpin: () -> Unit
) {
    val hasExternalUrl = remember(applet) { ExternalSiteLauncher.resolveExternalUrl(applet) != null }
    val pubLabel = publisherProfile?.bestName ?: PublisherProfileEntity.shortNpub(applet.authorNpub)

    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text("Launch in Built-in WebView") },
            onClick = {
                onDismiss()
                onLaunchInApp()
            }
        )
        if (hasExternalUrl && onLaunchExternal != null) {
            DropdownMenuItem(
                text = { Text("Open in External Browser ↗") },
                onClick = {
                    onDismiss()
                    onLaunchExternal()
                }
            )
        }
        DropdownMenuItem(
            text = { Text("Fork & Edit in Studio", fontWeight = FontWeight.Bold, color = BreDeepPink) },
            onClick = {
                onDismiss()
                onFork()
            }
        )
        if (applet.authorPubkey.length == 64) {
            DropdownMenuItem(
                text = { Text("More by $pubLabel") },
                onClick = {
                    onDismiss()
                    onInspectPublisher()
                }
            )
        }
        DropdownMenuItem(
            text = { Text("Under-the-Hood Manifest & Hashes") },
            onClick = {
                onDismiss()
                onViewManifest()
            }
        )
        DropdownMenuItem(
            text = { Text(if (applet.enableNip07) "Disconnect window.nostr Signer" else "Connect window.nostr Signer") },
            onClick = {
                onDismiss()
                onToggleNip07()
            }
        )
        DropdownMenuItem(
            text = { Text("Signing Permissions & Audit") },
            onClick = {
                onDismiss()
                onOpenPermissions()
            }
        )
        DropdownMenuItem(
            text = { Text("Remove from Lobby") },
            onClick = {
                onDismiss()
                onUnpin()
            }
        )
    }
}

@Composable
private fun ManifestInspectorDialog(
    applet: InstalledAppletEntity,
    publisherProfile: PublisherProfileEntity?,
    onInspectPublisher: (String) -> Unit,
    onFork: () -> Unit,
    onDismiss: () -> Unit
) {
    val clipboard = LocalClipboardManager.current
    val naddr = remember(applet.kind, applet.authorPubkey, applet.dTag) {
        NostrCrypto.encodeNaddr(applet.kind, applet.authorPubkey, applet.dTag)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = BreGray850,
        title = {
            Text(
                text = "${applet.iconGlyph} ${applet.title}",
                style = MaterialTheme.typography.titleMedium,
                color = BreWhite
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PublisherProfileCard(
                    profile = publisherProfile,
                    authorPubkey = applet.authorPubkey,
                    authorNpub = applet.authorNpub,
                    onFilterByPublisher = if (applet.authorPubkey.length == 64) onInspectPublisher else null
                )
                Text(
                    text = "NIP-5A Nsite (Kind ${applet.kind}) • Slug: ${applet.dTag.ifBlank { "root" }}",
                    style = MaterialTheme.typography.labelMedium,
                    color = BreLightPink
                )
                Text(
                    text = "Address: ${naddr.take(28)}…",
                    style = MaterialTheme.typography.labelSmall,
                    color = ZineBoneCream
                )
                Text(
                    text = "Blossom: ${applet.blossomServer}",
                    style = MaterialTheme.typography.labelSmall,
                    color = BreGray100
                )
                if (applet.indexSha256.isNotBlank()) {
                    Text(
                        text = "/index.html SHA-256:\n${applet.indexSha256}",
                        style = MaterialTheme.typography.labelSmall,
                        color = CyberMint
                    )
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onFork) {
                    Text("Fork in Studio", color = BreDeepPink, fontWeight = FontWeight.Bold)
                }
                TextButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(naddr))
                        onDismiss()
                    }
                ) {
                    Text("Copy naddr", color = ZineBoneCream)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = BreGray100)
            }
        }
    )
}

@Composable
private fun EmptyLauncherPlaceholder(
    connectedRelays: Int,
    onSyncRelays: () -> Unit,
    onOpenSearchFeed: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = if (connectedRelays == 0) {
                stringResource(R.string.empty_launcher_title_syncing)
            } else {
                stringResource(R.string.empty_launcher_title_ready)
            },
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Black),
            color = ZineBoneCream,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.empty_launcher_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = BreGray100,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(16.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                onClick = onSyncRelays,
                shape = RoundedCornerShape(14.dp),
                color = BreGray850,
                border = BorderStroke(1.dp, BrePinkDark2),
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("empty_home_sync_button")
            ) {
                Text(
                    text = "Sync Relays",
                    style = MaterialTheme.typography.labelLarge,
                    color = BreLightPink,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                )
            }
            Surface(
                onClick = onOpenSearchFeed,
                shape = RoundedCornerShape(14.dp),
                color = BreDeepPink,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("empty_home_explore_button")
            ) {
                Text(
                    text = "EXPLORE CHAOS FEED >>>",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Black),
                    color = BreWhite,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp)
                )
            }
        }
    }
}
