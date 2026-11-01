package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.VerifiedUser
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.VaultAccountEntity
import com.example.ui.theme.ObsidianBorder
import com.example.ui.theme.ObsidianElevated
import com.example.ui.theme.ObsidianSurface

@Composable
fun AccountMenuDrawerContent(
    activeAccount: VaultAccountEntity?,
    accounts: List<VaultAccountEntity>,
    connectedRelays: Int,
    totalRelays: Int,
    onSelectDestination: (ShellDestination) -> Unit,
    onSwitchAccount: (String) -> Unit,
    onCopiedNpub: () -> Unit
) {
    val clipboard = LocalClipboardManager.current

    ModalDrawerSheet(
        drawerContainerColor = ObsidianSurface,
        modifier = Modifier
            .widthIn(max = 340.dp)
            .fillMaxHeight()
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .windowInsetsPadding(WindowInsets.statusBars)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            // Profile Summary Header Card
            Surface(
                onClick = { onSelectDestination(ShellDestination.UserProfile) },
                shape = RoundedCornerShape(22.dp),
                color = ObsidianElevated,
                border = BorderStroke(1.dp, ObsidianBorder),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("drawer_profile_header_card")
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AccountAvatarThumbnail(
                            account = activeAccount,
                            sizeDp = 52,
                            contentDescription = stringResource(R.string.cd_user_avatar)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = activeAccount?.displayName?.ifBlank { "Set Display Name" }
                                    ?: "Sovereign Identity",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = activeAccount?.bio?.ifBlank { "Tap to add bio & avatar…" } ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = activeAccount?.npub?.let { "${it.take(18)}…${it.takeLast(8)}" } ?: "",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = {
                                activeAccount?.npub?.let {
                                    clipboard.setText(AnnotatedString(it))
                                    onCopiedNpub()
                                }
                            },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = stringResource(R.string.cd_copy_npub),
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }

            // Quick Multi-Account Switcher Pills (if > 1 account)
            if (accounts.size > 1) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "SWITCH IDENTITY",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 6.dp)
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    accounts.take(3).forEach { acc ->
                        Surface(
                            onClick = { onSwitchAccount(acc.pubkeyHex) },
                            shape = RoundedCornerShape(999.dp),
                            color = if (acc.isActive) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                ObsidianElevated
                            },
                            border = BorderStroke(
                                1.dp,
                                if (acc.isActive) MaterialTheme.colorScheme.primary else ObsidianBorder
                            )
                        ) {
                            Text(
                                text = acc.displayName.ifBlank { acc.npub.take(10) },
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = "IDENTITY & SOCIAL",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
            Spacer(modifier = Modifier.height(4.dp))

            DrawerActionRow(
                icon = Icons.Default.AccountCircle,
                label = stringResource(R.string.drawer_edit_profile),
                badge = "Kind 0",
                testTag = "drawer_profile_item",
                onClick = { onSelectDestination(ShellDestination.UserProfile) }
            )
            DrawerActionRow(
                icon = Icons.Default.EditNote,
                label = stringResource(R.string.drawer_notes),
                badge = "Kind 1",
                testTag = "drawer_notes_item",
                onClick = { onSelectDestination(ShellDestination.NotesComposer) }
            )
            DrawerActionRow(
                icon = Icons.Default.Hub,
                label = stringResource(R.string.drawer_relays),
                badge = "$connectedRelays/$totalRelays",
                testTag = "drawer_relays_item",
                onClick = { onSelectDestination(ShellDestination.RelayManager) }
            )

            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(color = ObsidianBorder)
            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "PUBLISH & SELF-HOST",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
            Spacer(modifier = Modifier.height(4.dp))

            DrawerActionRow(
                icon = Icons.Default.Code,
                label = stringResource(R.string.drawer_studio),
                badge = "Blossom",
                testTag = "drawer_studio_item",
                onClick = { onSelectDestination(ShellDestination.CreatorStudio()) }
            )
            DrawerActionRow(
                icon = Icons.Default.CloudUpload,
                label = stringResource(R.string.drawer_self_publish),
                badge = "NIP-5A",
                testTag = "drawer_self_publish_item",
                onClick = { onSelectDestination(ShellDestination.SelfPublishShell) }
            )

            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(color = ObsidianBorder)
            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "SIGNER VAULT & SHELL",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
            Spacer(modifier = Modifier.height(4.dp))

            DrawerActionRow(
                icon = Icons.Default.Key,
                label = stringResource(R.string.drawer_vault),
                badge = "${accounts.size} keys",
                testTag = "drawer_vault_item",
                onClick = { onSelectDestination(ShellDestination.VaultAndAudit) }
            )
            DrawerActionRow(
                icon = Icons.Default.VerifiedUser,
                label = stringResource(R.string.drawer_signer_audit),
                badge = null,
                testTag = "drawer_signer_log_item",
                onClick = { onSelectDestination(ShellDestination.VaultAndAudit) }
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
                tint = MaterialTheme.colorScheme.primary
            )
        },
        label = {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        badge = badge?.let {
            {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary
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
            .padding(vertical = 2.dp)
            .testTag(testTag)
    )
}
