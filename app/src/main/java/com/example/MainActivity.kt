package com.example

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Router
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.NsiteVersionEntity
import com.example.nostr.ExternalSiteLauncher
import com.example.nostr.NsiteAddressResolver
import com.example.nostr.SignDecision
import com.example.ui.*
import com.example.ui.theme.*
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val initialDeepLink = intent?.dataString
        setContent {
            MyApplicationTheme {
                SovereignShellRoot(initialDeepLink = initialDeepLink)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SovereignShellRoot(
    vm: ShellViewModel = viewModel(),
    initialDeepLink: String? = null
) {
    val context = LocalContext.current
    val destination by vm.currentDestination.collectAsStateWithLifecycle()
    val banner by vm.banner.collectAsStateWithLifecycle()
    val isWorking by vm.isWorking.collectAsStateWithLifecycle()
    val latestDeployment by vm.latestDeployment.collectAsStateWithLifecycle()
    val latestPublishVerification by vm.latestPublishVerification.collectAsStateWithLifecycle()
    val revealedNsec by vm.revealedNsec.collectAsStateWithLifecycle()
    val activeSignPrompt by vm.activeSignPrompt.collectAsStateWithLifecycle()

    val allProjects by vm.allProjects.collectAsStateWithLifecycle()
    val activeProject by vm.activeProject.collectAsStateWithLifecycle()
    val activeProjectFiles by vm.activeProjectFiles.collectAsStateWithLifecycle()
    val selectedFilePath by vm.selectedFilePath.collectAsStateWithLifecycle()

    val accounts by vm.accounts.collectAsStateWithLifecycle()
    val activeAccount by vm.activeAccount.collectAsStateWithLifecycle()
    val launcherPrefs by vm.launcherPrefs.collectAsStateWithLifecycle()
    val spamShieldEnabled by vm.spamShieldEnabled.collectAsStateWithLifecycle()
    val installedApplets by vm.installedApplets.collectAsStateWithLifecycle()
    val publisherProfiles by vm.publisherProfiles.collectAsStateWithLifecycle()
    val catalogApplets by vm.catalogApplets.collectAsStateWithLifecycle()
    val blockedSpamCount by vm.blockedSpamCount.collectAsStateWithLifecycle()
    val stackedGalleryGroups by vm.stackedGalleryGroups.collectAsStateWithLifecycle()
    val localCuratedCount by vm.localCuratedCount.collectAsStateWithLifecycle()
    val managedRelays by vm.managedRelays.collectAsStateWithLifecycle()
    val blossomServers by vm.blossomServers.collectAsStateWithLifecycle()
    val notes by vm.notes.collectAsStateWithLifecycle()
    val auditLog by vm.auditLog.collectAsStateWithLifecycle()
    val signerPolicies by vm.signerPolicies.collectAsStateWithLifecycle()
    val connectedRelayCount by vm.connectedRelayCount.collectAsStateWithLifecycle()

    // Wire NIP-55 Android Intent launcher
    val nip55Launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        vm.repository.signerManager.onNip55ActivityResult(result.resultCode, result.data)
    }

    DisposableEffect(vm) {
        vm.repository.signerManager.launchNip55IntentCallback = { intent: Intent ->
            runCatching {
                nip55Launcher.launch(intent)
                true
            }.getOrDefault(false)
        }
        onDispose {
            vm.repository.signerManager.launchNip55IntentCallback = null
        }
    }

    // Handle incoming nostr: / nsite.lol deep link if present
    LaunchedEffect(initialDeepLink) {
        if (!initialDeepLink.isNullOrBlank()) {
            val parsed = NsiteAddressResolver.parseSync(initialDeepLink)
            if (parsed != null) {
                vm.launchOrInstallByAddress(initialDeepLink, openImmediately = true)
            }
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    LaunchedEffect(banner?.id) {
        banner?.let { b ->
            snackbarHostState.showSnackbar(b.message, duration = SnackbarDuration.Short)
            vm.dismissBanner()
        }
    }

    if (destination != ShellDestination.HomeLauncher) {
        BackHandler {
            vm.navigateBack()
        }
    }

    val isFullBleedRuntime = destination is ShellDestination.RunningApplet
    val currentlyRunningId = (destination as? ShellDestination.RunningApplet)?.appletId
    val activeTab = vm.activePrimaryTabFor(destination)

    // Amethyst / Ditto Left Slide-Out Navigation Drawer
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            gesturesEnabled = !isFullBleedRuntime,
            drawerContent = {
                AccountMenuDrawerContent(
                    activeAccount = activeAccount,
                    accounts = accounts,
                    installedNsites = installedApplets,
                    currentlyRunningAppletId = currentlyRunningId,
                    connectedRelays = connectedRelayCount,
                    totalRelays = managedRelays.size,
                    onSelectDestination = { targetDest ->
                        scope.launch { drawerState.close() }
                        vm.navigateTo(targetDest)
                    },
                    onLaunchNsiteFromDrawer = { applet ->
                        scope.launch { drawerState.close() }
                        vm.navigateTo(ShellDestination.RunningApplet(applet.appletId))
                    },
                    onForkNsiteFromDrawer = { applet ->
                        scope.launch { drawerState.close() }
                        vm.forkAppletToStudio(applet)
                    },
                    onSwitchAccount = { pubkeyHex ->
                        vm.switchAccount(pubkeyHex)
                    },
                    onCopiedNpub = {
                        vm.showBanner("Copied npub to clipboard")
                    }
                )
            }
        ) {
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                containerColor = BreBlack,
                snackbarHost = {
                    SnackbarHost(hostState = snackbarHostState) { data ->
                        Snackbar(
                            snackbarData = data,
                            containerColor = ObsidianElevated,
                            contentColor = ZineBoneCream,
                            actionColor = BreDeepPink
                        )
                    }
                },
                bottomBar = {
                    if (!isFullBleedRuntime) {
                        DeepinkBottomBarWithCenterFab(
                            activeTab = activeTab,
                            onSelectTab = { tab -> vm.selectPrimaryTab(tab) },
                            onCenterFabClick = { vm.triggerDomainPublishAction() }
                        )
                    }
                }
            ) { innerPadding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(if (isFullBleedRuntime) PaddingValues(0.dp) else innerPadding)
                ) {
                    when (val dest = destination) {
                        is ShellDestination.HomeLauncher -> {
                            LauncherHomeScreen(
                                activeAccount = activeAccount,
                                launcherPrefs = launcherPrefs,
                                installedApplets = installedApplets,
                                publisherProfiles = publisherProfiles,
                                connectedRelays = connectedRelayCount,
                                totalRelays = managedRelays.size,
                                onOpenSearchFeed = {
                                    vm.navigateTo(ShellDestination.DiscoveryFeed(initialTabIsNsites = true))
                                },
                                onLaunchManualAddress = { raw ->
                                    vm.launchOrInstallByAddress(raw, openImmediately = true)
                                },
                                onForkManualAddress = { raw ->
                                    vm.forkAddressToStudio(raw)
                                },
                                onInspectPublisher = { pubkey ->
                                    vm.navigateTo(
                                        ShellDestination.DiscoveryFeed(
                                            initialTabIsNsites = true,
                                            initialPublisherPubkey = pubkey
                                        )
                                    )
                                },
                                onSyncRelays = { vm.refreshDiscoveryCatalog(silent = false) },
                                onToggleViewMode = { vm.toggleLauncherViewMode() },
                                onOpenAccountDrawer = { scope.launch { drawerState.open() } },
                                onLaunchApplet = { applet ->
                                    if (launcherPrefs.openNsitesExternally) {
                                        val url = ExternalSiteLauncher.resolveExternalUrl(applet)
                                        if (url != null) {
                                            ExternalSiteLauncher.launchCustomTabOrBrowser(context, url)
                                        } else {
                                            vm.navigateTo(ShellDestination.RunningApplet(applet.appletId))
                                        }
                                    } else {
                                        vm.navigateTo(ShellDestination.RunningApplet(applet.appletId))
                                    }
                                },
                                onLaunchInApp = { applet ->
                                    vm.navigateTo(ShellDestination.RunningApplet(applet.appletId))
                                },
                                onLaunchExternal = { applet ->
                                    val url = ExternalSiteLauncher.resolveExternalUrl(applet)
                                    if (url != null) {
                                        ExternalSiteLauncher.launchCustomTabOrBrowser(context, url)
                                    } else {
                                        vm.showBanner("No external gateway URL available for ${applet.title}", isError = true)
                                    }
                                },
                                onForkApplet = { applet -> vm.forkAppletToStudio(applet) },
                                onToggleNip07 = { applet -> vm.toggleAppletNip07(applet) },
                                onUnpinApplet = { applet -> vm.setAppletInstalled(applet, false) },
                                onOpenAppletPermissions = { vm.navigateTo(ShellDestination.VaultAndAudit) },
                                onOpenNotesComposer = { vm.navigateTo(ShellDestination.NotesComposer) },
                                onOpenRelayManager = { vm.navigateTo(ShellDestination.RelayManager) },
                                onOpenStudio = { vm.navigateTo(ShellDestination.CreatorStudio()) }
                            )
                        }

                        is ShellDestination.DiscoveryFeed -> {
                            DiscoveryFeedScreen(
                                initialTabIsNsites = dest.initialTabIsNsites,
                                initialPublisherPubkey = dest.initialPublisherPubkey,
                                launcherPrefs = launcherPrefs,
                                publisherProfiles = publisherProfiles,
                                stackedGroups = stackedGalleryGroups,
                                catalogApplets = catalogApplets,
                                localCuratedCount = localCuratedCount,
                                spamShieldEnabled = spamShieldEnabled,
                                blockedSpamCount = blockedSpamCount,
                                connectedRelays = connectedRelayCount,
                                onLookupPublisher = { vm.lookupPublisher(it) },
                                onOpenManualAddress = { raw ->
                                    vm.launchOrInstallByAddress(raw, openImmediately = true)
                                },
                                onToggleViewMode = { vm.toggleLauncherViewMode() },
                                onToggleDiscoveryMode = { vm.toggleDiscoveryDisplayMode() },
                                onToggleSpamShield = { vm.toggleSpamShield() },
                                onRefreshCatalog = { silent -> vm.refreshDiscoveryCatalog(silent) },
                                onToggleNip51Star = { vm.toggleNip51Bookmark(it) },
                                onPublishNip51List = { vm.publishNip51CuratedList() },
                                onInstallToggle = { applet, install -> vm.setAppletInstalled(applet, install) },
                                onOpenApplet = { applet ->
                                    vm.setAppletInstalled(applet, true)
                                    vm.navigateTo(ShellDestination.RunningApplet(applet.appletId))
                                },
                                onOpenAppletInApp = { applet ->
                                    vm.navigateTo(ShellDestination.RunningApplet(applet.appletId))
                                },
                                onForkToStudio = { applet -> vm.forkAppletToStudio(applet) },
                                onOpenCreatePublish = { vm.navigateTo(ShellDestination.CreatorStudio()) },
                                onBack = { vm.navigateBack() }
                            )
                        }

                        is ShellDestination.CreatorStudio -> {
                            CreatorStudioScreen(
                                activeAccount = activeAccount,
                                projects = allProjects,
                                activeProject = activeProject,
                                projectFiles = activeProjectFiles,
                                selectedFilePath = selectedFilePath,
                                blossomServer = launcherPrefs.primaryBlossomServer,
                                isWorking = isWorking,
                                latestReport = latestDeployment,
                                latestVerification = latestPublishVerification,
                                onSelectProject = { vm.selectProject(it) },
                                onCreateScratchProject = { title, dTag, kind ->
                                    vm.createNewScratchProject(title, dTag, kind)
                                },
                                onDeleteProject = { vm.deleteProject(it) },
                                onImportHtmlUri = { vm.importHtmlUri(it) },
                                onImportZipUri = { vm.importZipUri(it) },
                                onImportFolderUri = { vm.importFolderTreeUri(it) },
                                onForkByAddress = { raw -> vm.forkAddressToStudio(raw) },
                                onSelectFile = { vm.selectProjectFile(it) },
                                onSaveProjectMetadata = { title, dTag, kind, desc, entry ->
                                    vm.saveActiveProjectMetadata(title, dTag, kind, desc, entry)
                                },
                                onSaveTextFile = { path, content, showToast ->
                                    vm.saveProjectTextFile(path, content, showToast)
                                },
                                onCreateNewFile = { path, content ->
                                    vm.createNewProjectFile(path, content)
                                },
                                onImportAssetFile = { dir, uri ->
                                    vm.importAssetIntoProject(dir, uri)
                                },
                                onRenameFile = { oldP, newP ->
                                    vm.renameProjectFile(oldP, newP)
                                },
                                onDeleteFile = { vm.deleteProjectFile(it) },
                                resolvePreviewAsset = { projId, relPath, isMainFrame, bootstrapInjector ->
                                    vm.repository.projectRepository.resolveProjectPreviewAsset(
                                        projectId = projId,
                                        rawPath = relPath,
                                        isMainFrame = isMainFrame,
                                        transformRootHtml = bootstrapInjector
                                    )
                                },
                                onPreviewBridgeCall = { protocolLabel, type, payload ->
                                    vm.previewStudioBridgeCall(protocolLabel, type, payload)
                                },
                                onPublishProject = { vm.publishActiveProject() },
                                onCopiedUrl = { vm.showBanner("Copied live Nsite URL") },
                                onBack = { vm.navigateBack() }
                            )
                        }

                        is ShellDestination.RelayManager -> {
                            RelayManagementScreen(
                                relays = managedRelays,
                                blossomServers = blossomServers,
                                isWorking = isWorking,
                                onAddRelay = { url, onClear -> vm.addRelay(url, onClear) },
                                onRemoveRelay = { vm.removeRelay(it) },
                                onReconnectRelay = { vm.reconnectRelay(it) },
                                onAddBlossomServer = { url, asPrimary, onClear ->
                                    vm.addBlossomServer(url, asPrimary, onClear)
                                },
                                onSetPrimaryBlossom = { vm.updateBlossomServer(it) },
                                onRemoveBlossomServer = { vm.removeBlossomServer(it) },
                                onProbeAllEndpoints = { vm.probeAllNetworkEndpoints() },
                                onPublishNip65AndBlossom = { vm.publishNip65AndBlossomLists() },
                                onOpenIdentityVault = { vm.navigateTo(ShellDestination.VaultAndAudit) },
                                onOpenAccountMenu = { scope.launch { drawerState.open() } },
                                onBack = { vm.navigateBack() }
                            )
                        }

                        is ShellDestination.VaultAndAudit -> {
                            VaultAndAuditScreen(
                                accounts = accounts,
                                activeAccount = activeAccount,
                                revealedNsec = revealedNsec,
                                policies = signerPolicies,
                                auditLog = auditLog,
                                isWorking = isWorking,
                                onGenerateAccount = { vm.generateAccount(it) },
                                onImportAccount = { key, alias -> vm.importAccount(key, alias) },
                                onAddReadOnlyNpub = { npub, alias -> vm.addReadOnlyNpub(npub, alias) },
                                onConnectNip55Signer = { alias, pkg -> vm.connectNip55Signer(alias, pkg) },
                                onConnectNip46Bunker = { uri, alias, onClear ->
                                    vm.connectNip46Bunker(uri, alias, onClear)
                                },
                                onSwitchAccount = { vm.switchAccount(it) },
                                onToggleRevealNsec = { vm.toggleRevealNsec() },
                                onRevokePolicy = { vm.revokePolicy(it) },
                                onOpenProfileEditor = { vm.navigateTo(ShellDestination.UserProfile) },
                                onOpenAccountMenu = { scope.launch { drawerState.open() } },
                                onCopiedText = { vm.showBanner(it) },
                                onBack = { vm.navigateBack() }
                            )
                        }

                        is ShellDestination.WallpaperSettings -> {
                            WallpaperPersonalizationScreen(
                                prefs = launcherPrefs,
                                onSelectPreset = { vm.selectPresetWallpaper(it) },
                                onPickCustomImage = { vm.pickCustomWallpaper(it) },
                                onUpdateScrim = { vm.updateWallpaperScrim(it) },
                                onUpdateBlossomServer = { vm.updateBlossomServer(it) },
                                onToggleOpenNsitesExternally = { vm.setOpenNsitesExternally(it) },
                                onBack = { vm.navigateBack() }
                            )
                        }

                        is ShellDestination.NotesComposer -> {
                            NotesComposerScreen(
                                activeAccount = activeAccount,
                                notes = notes,
                                connectedRelays = connectedRelayCount,
                                totalRelays = managedRelays.size,
                                isWorking = isWorking,
                                onSignAndPublishNote = { content, tags, confirm, onDone ->
                                    vm.promptSignAndPublishNote(content, tags, confirm, onDone)
                                },
                                onBack = { vm.navigateBack() }
                            )
                        }

                        is ShellDestination.UserProfile -> {
                            UserProfileScreen(
                                activeAccount = activeAccount,
                                isWorking = isWorking,
                                onImportAvatar = { uri -> vm.importAvatarUri(uri) },
                                onSaveAndPublish = { name, bio, localPath, upload, glyph, colorHex ->
                                    vm.saveUserProfile(name, bio, localPath, upload, glyph, colorHex)
                                },
                                onCopiedKey = { vm.showBanner("Copied npub to clipboard") },
                                onBack = { vm.navigateBack() }
                            )
                        }

                        is ShellDestination.RunningApplet -> {
                            val applet = installedApplets.firstOrNull { it.appletId == dest.appletId }
                                ?: catalogApplets.firstOrNull { it.appletId == dest.appletId }
                            if (applet != null) {
                                val versionsFlow = remember(applet.appletId) {
                                    vm.observeVersionsForSite(applet.appletId)
                                }
                                val versions by versionsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
                                var selectedVersion by remember(applet.appletId) {
                                    mutableStateOf<NsiteVersionEntity?>(null)
                                }

                                AppletRuntimeScreen(
                                    applet = applet,
                                    activeAccount = activeAccount,
                                    publisherProfile = publisherProfiles[applet.authorPubkey.lowercase()],
                                    versions = versions,
                                    selectedVersion = selectedVersion,
                                    onSelectVersion = { selectedVersion = it },
                                    onForkSite = { ver -> vm.forkAppletToStudio(applet, ver) },
                                    onOpenShellDrawer = { scope.launch { drawerState.open() } },
                                    onInspectPublisher = { pubkey ->
                                        vm.navigateTo(
                                            ShellDestination.DiscoveryFeed(
                                                initialTabIsNsites = true,
                                                initialPublisherPubkey = pubkey
                                            )
                                        )
                                    },
                                    resolveDocument = { eff -> vm.repository.resolveAppletDocument(eff) },
                                    resolveAsset = { eff, path, isMainFrame, bootstrapInjector ->
                                        vm.repository.resolveAppletAsset(eff, path, isMainFrame, bootstrapInjector)
                                    },
                                    onBridgeCall = { eff, proto, type, payload ->
                                        vm.handleAppletBridgeCall(eff, proto, type, payload)
                                    },
                                    onToggleNip07 = { vm.toggleAppletNip07(applet) },
                                    onClose = { vm.navigateBack() }
                                )
                            } else {
                                LaunchedEffect(Unit) { vm.navigateBack() }
                            }
                        }
                    }

                    // NIP-07 / NIP-55 / NIP-46 Signer Confirmation Sheet
                    activeSignPrompt?.let { req ->
                        ModalBottomSheet(
                            onDismissRequest = { vm.resolveSignPrompt(SignDecision.REJECT) },
                            containerColor = Color.Transparent,
                            dragHandle = null
                        ) {
                            InteractiveSignerBottomSheet(
                                request = req,
                                onDecision = { decision -> vm.resolveSignPrompt(decision) }
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Deepink Arcade Bottom Bar with Raised Glowing Pink ≠ Center Medallion FAB.
 * - Left 2 items: Lobby (Home), Explore (Chaos Feed)
 * - Center Raised Glowing ≠ Medallion: Context-aware domain publish action
 * - Right 2 items: Studio (Create/Fork/Publish), Node & ID (Relays/Blossom/Vault)
 */
@Composable
private fun DeepinkBottomBarWithCenterFab(
    activeTab: PrimaryShellTab,
    onSelectTab: (PrimaryShellTab) -> Unit,
    onCenterFabClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(BreBlack)
            .navigationBarsPadding()
    ) {
        Surface(
            color = ObsidianSurface,
            border = BorderStroke(1.dp, ObsidianBorder),
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .align(Alignment.BottomCenter)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left 2 tabs
                BottomBarTabItem(
                    label = "Lobby",
                    icon = Icons.Default.Home,
                    selected = activeTab == PrimaryShellTab.LAUNCHER,
                    testTag = "nav_tab_launcher",
                    onClick = { onSelectTab(PrimaryShellTab.LAUNCHER) },
                    modifier = Modifier.weight(1f)
                )
                BottomBarTabItem(
                    label = "Explore",
                    icon = Icons.Default.Explore,
                    selected = activeTab == PrimaryShellTab.DISCOVER,
                    testTag = "nav_tab_discover",
                    onClick = { onSelectTab(PrimaryShellTab.DISCOVER) },
                    modifier = Modifier.weight(1f)
                )

                // Center spacer for raised ≠ medallion
                Spacer(modifier = Modifier.width(74.dp))

                // Right 2 tabs
                BottomBarTabItem(
                    label = "Studio",
                    icon = Icons.Default.Build,
                    selected = activeTab == PrimaryShellTab.STUDIO,
                    testTag = "nav_tab_studio",
                    onClick = { onSelectTab(PrimaryShellTab.STUDIO) },
                    modifier = Modifier.weight(1f)
                )
                BottomBarTabItem(
                    label = "Node & ID",
                    icon = Icons.Default.Router,
                    selected = activeTab == PrimaryShellTab.VAULT_NET,
                    testTag = "nav_tab_vault_net",
                    onClick = { onSelectTab(PrimaryShellTab.VAULT_NET) },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // Raised Glowing ≠ Center Medallion FAB (Publishes event for active domain)
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = (-14).dp)
                .size(66.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            BreDeepPink,
                            BrePinkDark2,
                            BreBlack
                        )
                    )
                )
                .border(2.5.dp, ZineBoneCream, CircleShape)
                .clickable { onCenterFabClick() }
                .testTag("bottom_center_publish_fab"),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(BreDeepPink)
                    .border(2.dp, BreBlack, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "≠",
                    color = BreBlack,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Black
                )
            }
        }
    }
}

@Composable
private fun BottomBarTabItem(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    testTag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .clickable { onClick() }
            .testTag(testTag),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (selected) BreDeepPink else ZineBoneCream.copy(alpha = 0.7f),
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) BreDeepPink else ZineBoneCream.copy(alpha = 0.7f),
            fontWeight = if (selected) FontWeight.Black else FontWeight.Medium,
            fontSize = 10.sp
        )
    }
}
