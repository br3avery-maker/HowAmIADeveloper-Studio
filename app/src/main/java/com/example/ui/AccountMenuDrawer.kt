package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.InstalledAppletEntity
import com.example.data.VaultAccountEntity
import com.example.ui.theme.BreBlack
import com.example.ui.theme.BreDeepPink
import com.example.ui.theme.BreGray100
import com.example.ui.theme.BreGray850
import com.example.ui.theme.BreLightPink
import com.example.ui.theme.BrePinkDark2
import com.example.ui.theme.BreWhite
import com.example.ui.theme.CyberMint
import com.example.ui.theme.ObsidianBorder
import com.example.ui.theme.ObsidianElevated
import com.example.ui.theme.ObsidianSurface
import com.example.ui.theme.ZineBoneCream

/**
 * Amethyst / Ditto-inspired Left Slide Drawer:
 * - Top: Signed-in Nostr Account header + quick multi-account switcher pills.
 * - Middle (scrollable): Direct quick-switch links to all Saved / Pinned Nsites (even while inside a running Nsite!).
 * - Bottom (pinned): Settings, Adaptive Relay + Blossom Node, Identity Vault, Profile, and Notes.
 */
@Composable
fun AccountMenuDrawerContent(
    activeAccount: VaultAccountEntity?,
    accounts: List<VaultAccountEntity>,
    installedNsites: List<InstalledAppletEntity> = emptyList(),
    currentlyRunningAppletId: String? = null,
    connectedRelays: Int,
    totalRelays: Int,
    onSelectDestination: (ShellDestination) -> Unit,
    onLaunchNsiteFromDrawer: (InstalledAppletEntity) -> Unit = {},
    onForkNsiteFromDrawer: (InstalledAppletEntity) -> Unit = {},
    onSwitchAccount: (String) -> Unit,
    onCopiedNpub: () -> Unit
) {
    val clipboard = LocalClipboardManager.current

    ModalDrawerSheet(
        drawerContainerColor = ObsidianSurface,
        modifier = Modifier
            .widthIn(max = 336.dp)
            .fillMaxHeight()
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .windowInsetsPadding(WindowInsets.statusBars)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            // 1. Top Signed-In Nostr Identity Header (Amethyst / Ditto style)
            Surface(
                onClick = { onSelectDestination(ShellDestination.UserProfile) },
                shape = RoundedCornerShape(18.dp),
                color = ObsidianElevated,
                border = BorderStroke(1.dp, BrePinkDark2),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("drawer_profile_header_card")
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AccountAvatarThumbnail(
                            account = activeAccount,
                            sizeDp = 50,
                            contentDescription = stringResource(R.string.cd_user_avatar)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = activeAccount?.displayName?.ifBlank { "Sovereign Rebel" }
                                    ?: "Sovereign Rebel",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Black),
                                color = ZineBoneCream,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = activeAccount?.resolvedSignerType?.label ?: "Local Keystore",
                                style = MaterialTheme.typography.labelSmall,
                                color = CyberMint,
                                maxLines = 1
                            )
                        }
                        IconButton(
                            onClick = {
                                activeAccount?.npub?.let {
                                    clipboard.setText(AnnotatedString(it))
                                    onCopiedNpub()
                                }
                            },
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = stringResource(R.string.cd_copy_npub),
                                tint = BreLightPink,
                                modifier = Modifier.size(15.dp)
                            )
                        }
                    }

                    // Multi-account switcher row (like Amethyst avatar switcher)
                    if (accounts.size > 1) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            accounts.take(4).forEach { acc ->
                                Surface(
                                    onClick = { onSwitchAccount(acc.pubkeyHex) },
                                    shape = RoundedCornerShape(999.dp),
                                    color = if (acc.isActive) BreDeepPink else BreBlack,
                                    border = BorderStroke(
                                        1.dp,
                                        if (acc.isActive) ZineBoneCream else ObsidianBorder
                                    )
                                ) {
                                    Text(
                                        text = acc.displayName.ifBlank { acc.npub.take(8) },
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                        color = BreWhite,
                                        maxLines = 1,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 2. Middle Scrollable Section: Quick-Jump Links to Saved Nsites
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp)
            ) {
                Text(
                    text = "SAVED NSITES (${installedNsites.size})",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = ZineBoneCream
                )
                Text(
                    text = "+ Explore",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = BreDeepPink,
                    modifier = Modifier.clickable {
                        onSelectDestination(ShellDestination.DiscoveryFeed())
                    }
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            LazyColumn(
                contentPadding = PaddingValues(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .testTag("drawer_nsites_list")
            ) {
                if (installedNsites.isEmpty()) {
                    item {
                        Surface(
                            onClick = { onSelectDestination(ShellDestination.DiscoveryFeed()) },
                            shape = RoundedCornerShape(12.dp),
                            color = BreGray850,
                            border = BorderStroke(1.dp, ObsidianBorder),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "No pinned Nsites yet — tap to browse Chaos Feed",
                                style = MaterialTheme.typography.bodySmall,
                                color = BreGray100,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }
                } else {
                    items(installedNsites, key = { "drawer_site_${it.appletId}" }) { site ->
                        val isRunning = site.appletId == currentlyRunningAppletId
                        Surface(
                            onClick = { onLaunchNsiteFromDrawer(site) },
                            shape = RoundedCornerShape(14.dp),
                            color = if (isRunning) BrePinkDark2.copy(alpha = 0.35f) else BreBlack,
                            border = BorderStroke(
                                1.dp,
                                if (isRunning) BreDeepPink else ObsidianBorder
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("drawer_nsite_${site.dTag}")
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(BreGray850)
                                        .border(1.dp, BrePinkDark2, RoundedCornerShape(8.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(text = site.iconGlyph, style = MaterialTheme.typography.bodyMedium)
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = site.title,
                                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                                        color = if (isRunning) ZineBoneCream else BreWhite,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (isRunning) {
                                        Text(
                                            text = "ACTIVE NOW",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = CyberMint
                                        )
                                    }
                                }
                                IconButton(
                                    onClick = { onForkNsiteFromDrawer(site) },
                                    modifier = Modifier.size(30.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.CallSplit,
                                        contentDescription = "Fork ${site.title}",
                                        tint = BreLightPink,
                                        modifier = Modifier.size(15.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider(color = ObsidianBorder)
            Spacer(modifier = Modifier.height(8.dp))

            // 3. Pinned Bottom Section: Settings, Studio, Node & Identity Vault (Amethyst / Ditto pattern)
            Text(
                text = "SHELL & SETTINGS",
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = ZineBoneCream,
                modifier = Modifier.padding(horizontal = 6.dp)
            )
            Spacer(modifier = Modifier.height(4.dp))

            DrawerActionRow(
                icon = Icons.Default.Code,
                label = stringResource(R.string.drawer_studio),
                badge = "Fork/Build",
                testTag = "drawer_studio_item",
                onClick = { onSelectDestination(ShellDestination.CreatorStudio()) }
            )
            DrawerActionRow(
                icon = Icons.Default.Hub,
                label = stringResource(R.string.drawer_relays),
                badge = "$connectedRelays/$totalRelays",
                testTag = "drawer_relays_item",
                onClick = { onSelectDestination(ShellDestination.RelayManager) }
            )
            DrawerActionRow(
                icon = Icons.Default.Key,
                label = stringResource(R.string.drawer_vault),
                badge = "${accounts.size} IDs",
                testTag = "drawer_vault_item",
                onClick = { onSelectDestination(ShellDestination.VaultAndAudit) }
            )
            DrawerActionRow(
                icon = Icons.Default.EditNote,
                label = stringResource(R.string.drawer_notes),
                badge = "Broadcast",
                testTag = "drawer_notes_item",
                onClick = { onSelectDestination(ShellDestination.NotesComposer) }
            )
            DrawerActionRow(
                icon = Icons.Default.AccountCircle,
                label = stringResource(R.string.drawer_edit_profile),
                badge = null,
                testTag = "drawer_profile_item",
                onClick = { onSelectDestination(ShellDestination.UserProfile) }
            )
            DrawerActionRow(
                icon = Icons.Default.Palette,
                label = stringResource(R.string.drawer_wallpaper),
                badge = null,
                testTag = "drawer_wallpaper_item",
                onClick = { onSelectDestination(ShellDestination.WallpaperSettings) }
            )
        }
    }
}

@Composable
private fun DrawerActionRow(
    icon: ImageVector,
    label: String,
    badge: String?,
    testTag: String,
    onClick: () -> Unit
) {
    NavigationDrawerItem(
        icon = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = BreDeepPink,
                modifier = Modifier.size(18.dp)
            )
        },
        label = {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        badge = badge?.let {
            {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = ZineBoneCream
                )
            }
        },
        selected = false,
        onClick = onClick,
        colors = NavigationDrawerItemDefaults.colors(
            unselectedContainerColor = ObsidianSurface,
            unselectedTextColor = MaterialTheme.colorScheme.onSurface
        ),
        modifier = Modifier
            .height(42.dp)
            .padding(vertical = 1.dp)
            .testTag(testTag)
    )
}
