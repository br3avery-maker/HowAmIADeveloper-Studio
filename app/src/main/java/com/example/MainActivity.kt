package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.AccountMenuDrawerContent
import com.example.ui.AppletRuntimeScreen
import com.example.ui.CreatorStudioScreen
import com.example.ui.DiscoveryFeedScreen
import com.example.ui.LauncherHomeScreen
import com.example.ui.NotesComposerScreen
import com.example.ui.RelayManagementScreen
import com.example.ui.SelfPublishShellScreen
import com.example.ui.ShellDestination
import com.example.ui.ShellViewModel
import com.example.ui.SignerApprovalBottomSheet
import com.example.ui.SignerVaultAndAuditScreen
import com.example.ui.UserProfileScreen
import com.example.ui.WallpaperAndBlossomScreen
import com.example.ui.theme.CoralAlert
import com.example.ui.theme.CyberMint
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.ObsidianElevated
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                val vm: ShellViewModel = viewModel(factory = ShellViewModel.Factory(application))
                SovereignShellRoot(vm = vm)
            }
        }
    }
}

@Composable
fun SovereignShellRoot(vm: ShellViewModel) {
    val destination by vm.currentDestination.collectAsStateWithLifecycle()
    val activeAccount by vm.activeAccount.collectAsStateWithLifecycle()
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    val launcherPrefs by vm.launcherPrefs.collectAsStateWithLifecycle()
    val installedApplets by vm.installedApplets.collectAsStateWithLifecycle()
    val catalogApplets by vm.catalogApplets.collectAsStateWithLifecycle()
    val relays by vm.managedRelays.collectAsStateWithLifecycle()
    val connectedRelayCount by vm.connectedRelayCount.collectAsStateWithLifecycle()
    val notes by vm.notes.collectAsStateWithLifecycle()
    val studioDraft by vm.studioDraft.collectAsStateWithLifecycle()
    val latestDeployment by vm.latestDeployment.collectAsStateWithLifecycle()
    val isWorking by vm.isWorking.collectAsStateWithLifecycle()
    val activeSignPrompt by vm.activeSignPrompt.collectAsStateWithLifecycle()
    val revealedNsec by vm.revealedNsec.collectAsStateWithLifecycle()
    val policies by vm.signerPolicies.collectAsStateWithLifecycle()
    val auditLog by vm.auditLog.collectAsStateWithLifecycle()
    val banner by vm.banner.collectAsStateWithLifecycle()

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    LaunchedEffect(banner?.id) {
        if (banner != null) {
            delay(3400L)
            vm.dismissBanner()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Right-Side Account Menu Drawer (slides out from upper-right account icon)
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            ModalNavigationDrawer(
                drawerState = drawerState,
                gesturesEnabled = destination == ShellDestination.HomeLauncher,
                drawerContent = {
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        AccountMenuDrawerContent(
                            activeAccount = activeAccount,
                            accounts = accounts,
                            connectedRelays = connectedRelayCount,
                            totalRelays = relays.size,
                            onSelectDestination = { target ->
                                scope.launch { drawerState.close() }
                                vm.navigateTo(target)
                            },
                            onSwitchAccount = { pubkey ->
                                vm.switchAccount(pubkey)
                            },
                            onCopiedNpub = {
                                vm.showBanner("Copied Nostr public key (npub) to clipboard")
                            }
                        )
                    }
                }
            ) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    when (val dest = destination) {
                        ShellDestination.HomeLauncher -> {
                            LauncherHomeScreen(
                                activeAccount = activeAccount,
                                launcherPrefs = launcherPrefs,
                                installedApplets = installedApplets,
                                connectedRelays = connectedRelayCount,
                                totalRelays = relays.size,
                                onOpenSearchFeed = { vm.navigateTo(ShellDestination.DiscoveryFeed()) },
                                onToggleViewMode = { vm.toggleLauncherViewMode() },
                                onOpenAccountDrawer = { scope.launch { drawerState.open() } },
                                onLaunchApplet = { app ->
                                    vm.navigateTo(ShellDestination.RunningApplet(app.appletId))
                                },
                                onForkApplet = { app ->
                                    vm.navigateTo(ShellDestination.CreatorStudio(forkSource = app))
                                },
                                onToggleNip07 = { app -> vm.toggleAppletNip07(app) },
                                onUnpinApplet = { app -> vm.setAppletInstalled(app, false) },
                                onOpenAppletPermissions = { vm.navigateTo(ShellDestination.VaultAndAudit) },
                                onOpenNotesComposer = { vm.navigateTo(ShellDestination.NotesComposer) },
                                onOpenRelayManager = { vm.navigateTo(ShellDestination.RelayManager) },
                                onOpenStudio = { vm.navigateTo(ShellDestination.CreatorStudio()) }
                            )
                        }

                        is ShellDestination.DiscoveryFeed -> {
                            DiscoveryFeedScreen(
                                initialTabIsNsites = dest.initialTabIsNsites,
                                catalogApplets = catalogApplets,
                                onInstallToggle = { app, install -> vm.setAppletInstalled(app, install) },
                                onOpenApplet = { app ->
                                    vm.navigateTo(ShellDestination.RunningApplet(app.appletId))
                                },
                                onForkToStudio = { app ->
                                    vm.navigateTo(ShellDestination.CreatorStudio(forkSource = app))
                                },
                                onOpenCreatePublish = {
                                    vm.navigateTo(ShellDestination.CreatorStudio())
                                },
                                onBack = { vm.navigateBack() }
                            )
                        }

                        ShellDestination.UserProfile -> {
                            UserProfileScreen(
                                activeAccount = activeAccount,
                                isWorking = isWorking,
                                onImportAvatar = { uri -> vm.importAvatarUri(uri) },
                                onSaveAndPublish = { name, bio, path, toBlossom, glyph, colorHex ->
                                    vm.saveUserProfile(name, bio, path, toBlossom, glyph, colorHex)
                                },
                                onCopiedKey = { vm.showBanner("Copied npub to clipboard") },
                                onBack = { vm.navigateBack() }
                            )
                        }

                        ShellDestination.RelayManager -> {
                            RelayManagementScreen(
                                relays = relays,
                                isWorking = isWorking,
                                onAddRelay = { url, onClear -> vm.addRelay(url, onClear) },
                                onRemoveRelay = { url -> vm.removeRelay(url) },
                                onReconnectRelay = { url -> vm.reconnectRelay(url) },
                                onPublishNip65 = { vm.publishNip65Relays() },
                                onBack = { vm.navigateBack() }
                            )
                        }

                        ShellDestination.NotesComposer -> {
                            NotesComposerScreen(
                                activeAccount = activeAccount,
                                notes = notes,
                                connectedRelays = connectedRelayCount,
                                totalRelays = relays.size,
                                isWorking = isWorking,
                                onSignAndPublishNote = { text, tags, confirm, onDone ->
                                    vm.promptSignAndPublishNote(text, tags, confirm, onDone)
                                },
                                onBack = { vm.navigateBack() }
                            )
                        }

                        is ShellDestination.CreatorStudio -> {
                            CreatorStudioScreen(
                                draft = studioDraft,
                                blossomServer = launcherPrefs.primaryBlossomServer,
                                isWorking = isWorking,
                                latestReport = latestDeployment,
                                onUpdateDraft = { vm.updateStudioDraft(it) },
                                onApplyTemplate = { vm.applyGraphicTemplate(it) },
                                onPreviewBridgeCall = { proto, type, payload ->
                                    vm.previewStudioBridgeCall(proto, type, payload)
                                },
                                onPublishDraft = { vm.publishStudioDraft() },
                                onCopiedUrl = { vm.showBanner("Copied Nsite Gateway URL") },
                                onBack = { vm.navigateBack() }
                            )
                        }

                        ShellDestination.SelfPublishShell -> {
                            SelfPublishShellScreen(
                                blossomServer = launcherPrefs.primaryBlossomServer,
                                pinnedCount = installedApplets.size,
                                isWorking = isWorking,
                                latestReport = latestDeployment,
                                onExecuteSelfPublish = { asRoot, slug, preset ->
                                    vm.executeSelfPublishShell(asRoot, slug, preset)
                                },
                                onCopiedUrl = { vm.showBanner("Copied Self-Published Nsite URL") },
                                onBack = { vm.navigateBack() }
                            )
                        }

                        ShellDestination.VaultAndAudit -> {
                            SignerVaultAndAuditScreen(
                                accounts = accounts,
                                revealedNsec = revealedNsec,
                                policies = policies,
                                auditLog = auditLog,
                                onSwitchAccount = { vm.switchAccount(it) },
                                onGenerateNewAccount = { vm.generateAccount(it) },
                                onImportNsecAccount = { key, alias -> vm.importAccount(key, alias) },
                                onToggleRevealNsec = { vm.toggleRevealNsec() },
                                onRevokePolicy = { vm.revokePolicy(it) },
                                onCopiedSecret = { vm.showBanner("Copied nsec backup — keep it secret!") },
                                onBack = { vm.navigateBack() }
                            )
                        }

                        ShellDestination.WallpaperSettings -> {
                            WallpaperAndBlossomScreen(
                                prefs = launcherPrefs,
                                onPickCustomPhoto = { vm.pickCustomWallpaper(it) },
                                onSelectStyle = { vm.selectPresetWallpaper(it) },
                                onUpdateScrim = { vm.updateWallpaperScrim(it) },
                                onUpdateBlossomServer = { vm.updateBlossomServer(it) },
                                onBack = { vm.navigateBack() }
                            )
                        }

                        is ShellDestination.RunningApplet -> {
                            val runningApplet = catalogApplets.firstOrNull { it.appletId == dest.appletId }
                            if (runningApplet != null) {
                                AppletRuntimeScreen(
                                    applet = runningApplet,
                                    activeAccount = activeAccount,
                                    resolveDocument = { vm.repository.resolveAppletDocument(it) },
                                    onBridgeCall = { app, proto, type, payload ->
                                        vm.handleAppletBridgeCall(app, proto, type, payload)
                                    },
                                    onToggleNip07 = { vm.toggleAppletNip07(runningApplet) },
                                    onClose = { vm.navigateBack() }
                                )
                            } else {
                                LaunchedEffect(Unit) { vm.navigateHome() }
                            }
                        }
                    }
                }
            }
        }

        // Global Built-in Signer Permission Modal Bottom Sheet
        val prompt = activeSignPrompt
        if (prompt != null) {
            SignerApprovalBottomSheet(
                request = prompt,
                onDecision = { vm.resolveSignPrompt(it) }
            )
        }

        // Non-intrusive Floating Status Banner
        AnimatedVisibility(
            visible = banner != null,
            enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(top = 8.dp, start = 16.dp, end = 16.dp)
        ) {
            banner?.let { b ->
                Surface(
                    onClick = { vm.dismissBanner() },
                    shape = RoundedCornerShape(999.dp),
                    color = ObsidianElevated,
                    border = BorderStroke(1.dp, if (b.isError) CoralAlert else CyberMint),
                    shadowElevation = 8.dp,
                    modifier = Modifier
                        .widthIn(max = 520.dp)
                        .testTag("status_banner")
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                    ) {
                        Spacer(modifier = Modifier.width(2.dp))
                        Text(
                            text = b.message,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (b.isError) CoralAlert else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}
