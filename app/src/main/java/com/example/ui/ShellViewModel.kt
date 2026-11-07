package com.example.ui

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.CatalogStacker
import com.example.data.DiscoveryDisplayMode
import com.example.data.ForkImportOutcome
import com.example.data.InstalledAppletEntity
import com.example.data.LauncherPrefs
import com.example.data.ManagedBlossomItem
import com.example.data.ManagedRelayItem
import com.example.data.NoteEntity
import com.example.data.NsiteProjectEntity
import com.example.data.NsiteProjectFileEntity
import com.example.data.NsiteVersionEntity
import com.example.data.PublishDeploymentReport
import com.example.data.PublishVerificationBreakdown
import com.example.data.PublisherProfileEntity
import com.example.data.ShellRepository
import com.example.data.SignerAuditEntity
import com.example.data.SignerPolicyEntity
import com.example.data.StackedAppletGroup
import com.example.data.VaultAccountEntity
import com.example.data.WallpaperStyle
import com.example.nostr.NostrEvent
import com.example.nostr.NostrKinds
import com.example.nostr.PendingSignRequest
import com.example.nostr.RelayConnectionStatus
import com.example.nostr.SignDecision
import com.example.nostr.UnsignedNostrEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class PrimaryShellTab {
    LAUNCHER,
    DISCOVER,
    STUDIO,
    VAULT_NET
}

sealed interface ShellDestination {
    data object HomeLauncher : ShellDestination
    data class DiscoveryFeed(
        val initialPublisherPubkey: String? = null
    ) : ShellDestination
    data object CreatorStudio : ShellDestination
    data object VaultAndAudit : ShellDestination
    data object RelayManager : ShellDestination
    data object UserProfile : ShellDestination
    data object NotesComposer : ShellDestination
    data object WallpaperSettings : ShellDestination
    data class RunningApplet(val appletId: String) : ShellDestination
}

fun ShellDestination.primaryTabOrNull(): PrimaryShellTab? = when (this) {
    ShellDestination.HomeLauncher -> PrimaryShellTab.LAUNCHER
    is ShellDestination.DiscoveryFeed -> PrimaryShellTab.DISCOVER
    ShellDestination.CreatorStudio -> PrimaryShellTab.STUDIO
    ShellDestination.VaultAndAudit,
    ShellDestination.RelayManager,
    ShellDestination.UserProfile,
    ShellDestination.NotesComposer,
    ShellDestination.WallpaperSettings -> PrimaryShellTab.VAULT_NET
    is ShellDestination.RunningApplet -> null
}

data class TransientStatusBanner(
    val message: String,
    val isError: Boolean = false,
    val id: Long = System.currentTimeMillis()
)

@OptIn(ExperimentalCoroutinesApi::class)
class ShellViewModel(
    application: Application,
    val repository: ShellRepository = ShellRepository(application)
) : AndroidViewModel(application) {

    private val navStack = ArrayDeque<ShellDestination>().apply {
        addLast(ShellDestination.HomeLauncher)
    }

    private val _currentDestination = MutableStateFlow<ShellDestination>(ShellDestination.HomeLauncher)
    val currentDestination: StateFlow<ShellDestination> = _currentDestination.asStateFlow()

    private val _banner = MutableStateFlow<TransientStatusBanner?>(null)
    val banner: StateFlow<TransientStatusBanner?> = _banner.asStateFlow()

    private val _isWorking = MutableStateFlow(false)
    val isWorking: StateFlow<Boolean> = _isWorking.asStateFlow()

    private val _latestDeployment = MutableStateFlow<PublishDeploymentReport?>(null)
    val latestDeployment: StateFlow<PublishDeploymentReport?> = _latestDeployment.asStateFlow()

    private val _latestPublishVerification = MutableStateFlow<PublishVerificationBreakdown?>(null)
    val latestPublishVerification: StateFlow<PublishVerificationBreakdown?> = _latestPublishVerification.asStateFlow()

    private val _latestForkOutcome = MutableStateFlow<ForkImportOutcome?>(null)
    val latestForkOutcome: StateFlow<ForkImportOutcome?> = _latestForkOutcome.asStateFlow()

    private val _revealedNsec = MutableStateFlow<String?>(null)
    val revealedNsec: StateFlow<String?> = _revealedNsec.asStateFlow()

    private val pendingSignDeferreds = ConcurrentHashMap<String, CompletableDeferred<SignDecision>>()
    private val _activeSignPrompt = MutableStateFlow<PendingSignRequest?>(null)
    val activeSignPrompt: StateFlow<PendingSignRequest?> = _activeSignPrompt.asStateFlow()

    // --- Identity & Preferences ---

    val accounts: StateFlow<List<VaultAccountEntity>> = repository.accountsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val activeAccount: StateFlow<VaultAccountEntity?> = repository.activeAccountFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val launcherPrefs: StateFlow<LauncherPrefs> = repository.prefsRepo.prefsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LauncherPrefs())

    private val _spamShieldEnabled = MutableStateFlow(true)
    val spamShieldEnabled: StateFlow<Boolean> = _spamShieldEnabled.asStateFlow()

    // --- NIP-5A Nsite Library & Discovery ---

    val installedApplets: StateFlow<List<InstalledAppletEntity>> = repository.installedAppletsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val publisherProfiles: StateFlow<Map<String, PublisherProfileEntity>> = repository.publisherProfilesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    val catalogApplets: StateFlow<List<InstalledAppletEntity>> = combine(
        repository.rawCatalogAppletsFlow,
        repository.filteredCatalogFlow,
        _spamShieldEnabled
    ) { raw, filtered, shieldOn ->
        if (shieldOn) filtered.cleanItems else raw
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val blockedSpamCount: StateFlow<Int> = combine(
        repository.filteredCatalogFlow
    ) { arr ->
        arr.first().blockedSpamCount
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val stackedGalleryGroups: StateFlow<List<StackedAppletGroup>> = combine(
        catalogApplets,
        repository.curatedBookmarksFlow,
        activeAccount
    ) { visibleItems, bookmarks, active ->
        CatalogStacker.buildStackedGroups(
            items = visibleItems,
            bookmarks = bookmarks,
            localPubkeyHex = active?.pubkeyHex.orEmpty()
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val localCuratedCount: StateFlow<Int> = combine(
        repository.curatedBookmarksFlow,
        activeAccount
    ) { bookmarks, active ->
        val pk = active?.pubkeyHex.orEmpty()
        bookmarks.count {
            it.isLocalCurator || (pk.isNotBlank() && it.curatorPubkey.equals(pk, ignoreCase = true))
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    // --- Adaptive Relay + Blossom Telemetry ---

    val managedRelays: StateFlow<List<ManagedRelayItem>> = repository.managedRelaysFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val blossomServers: StateFlow<List<ManagedBlossomItem>> = repository.managedBlossomServersFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val connectedRelayCount: StateFlow<Int> = combine(managedRelays) { arr ->
        arr.first().count { it.statusInfo.status == RelayConnectionStatus.CONNECTED }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val notes: StateFlow<List<NoteEntity>> = repository.notesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val auditLog: StateFlow<List<SignerAuditEntity>> = repository.auditLogFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val signerPolicies: StateFlow<List<SignerPolicyEntity>> = repository.signerPoliciesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // --- Multi-File Nsite Studio Workspace ---

    val allProjects: StateFlow<List<NsiteProjectEntity>> = repository.projectRepository.allProjectsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _activeProjectId = MutableStateFlow<String?>(null)
    val activeProjectId: StateFlow<String?> = _activeProjectId.asStateFlow()

    private val _selectedFilePath = MutableStateFlow("/index.html")
    val selectedFilePath: StateFlow<String> = _selectedFilePath.asStateFlow()

    val activeProject: StateFlow<NsiteProjectEntity?> = combine(
        allProjects,
        _activeProjectId
    ) { projects, selectedId ->
        projects.firstOrNull { it.projectId == selectedId } ?: projects.firstOrNull()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val activeProjectFiles: StateFlow<List<NsiteProjectFileEntity>> = activeProject
        .flatMapLatest { proj ->
            if (proj == null) flowOf(emptyList())
            else repository.projectRepository.observeProjectFiles(proj.projectId)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // --- Navigation ---

    fun selectPrimaryTab(tab: PrimaryShellTab) {
        _revealedNsec.value = null
        val target = when (tab) {
            PrimaryShellTab.LAUNCHER -> ShellDestination.HomeLauncher
            PrimaryShellTab.DISCOVER -> ShellDestination.DiscoveryFeed()
            PrimaryShellTab.STUDIO -> {
                ensureStudioProjectReady()
                ShellDestination.CreatorStudio
            }
            PrimaryShellTab.VAULT_NET -> ShellDestination.VaultAndAudit
        }
        navStack.clear()
        navStack.addLast(ShellDestination.HomeLauncher)
        if (target != ShellDestination.HomeLauncher) {
            navStack.addLast(target)
        }
        _currentDestination.value = target
    }

    fun navigateTo(destination: ShellDestination) {
        if (destination == ShellDestination.CreatorStudio) {
            ensureStudioProjectReady()
        }
        navStack.addLast(destination)
        _currentDestination.value = destination
    }

    fun navigateBack() {
        _revealedNsec.value = null
        if (navStack.size > 1) {
            navStack.removeLast()
            _currentDestination.value = navStack.last()
        } else {
            _currentDestination.value = ShellDestination.HomeLauncher
        }
    }

    fun navigateHome() {
        _revealedNsec.value = null
        navStack.clear()
        navStack.addLast(ShellDestination.HomeLauncher)
        _currentDestination.value = ShellDestination.HomeLauncher
    }

    fun showBanner(msg: String, isError: Boolean = false) {
        _banner.value = TransientStatusBanner(message = msg, isError = isError)
    }

    fun dismissBanner() {
        _banner.value = null
    }

    fun observeNsiteVersions(coordinate: String): Flow<List<NsiteVersionEntity>> =
        repository.observeNsiteVersions(coordinate)

    // --- Launcher Manual Address & Site Caching ---

    fun launchOrInstallByAddress(
        rawAddress: String,
        openImmediately: Boolean = true,
        onSuccessClear: () -> Unit = {}
    ) {
        val trimmed = rawAddress.trim()
        if (trimmed.isBlank()) {
            showBanner("Enter an nsite.lol URL, naddr1…, npub1…, or NIP-05 address", isError = true)
            return
        }
        viewModelScope.launch {
            _isWorking.value = true
            runCatching {
                repository.resolveAndInstallManualNsiteAddress(trimmed, pinToLibrary = true)
            }.onSuccess { resolved ->
                onSuccessClear()
                showBanner("Resolved & verified '${resolved.title}'")
                if (openImmediately) {
                    navigateTo(ShellDestination.RunningApplet(resolved.appletId))
                }
            }.onFailure { err ->
                showBanner(err.message ?: "Could not resolve Nsite address", isError = true)
            }
            _isWorking.value = false
        }
    }

    fun verifyAndCacheSite(applet: InstalledAppletEntity) {
        viewModelScope.launch {
            _isWorking.value = true
            runCatching { repository.prefetchAndVerifySiteAssets(applet) }
                .onSuccess { updated ->
                    showBanner(
                        if (updated.cachedComplete) "All Blossom files verified & cached for ${updated.title}"
                        else "Cached partial assets for ${updated.title} — some blobs unreachable",
                        isError = !updated.cachedComplete
                    )
                }
                .onFailure { err ->
                    showBanner(err.message ?: "Verification failed", isError = true)
                }
            _isWorking.value = false
        }
    }

    // --- Multi-File Studio, Import, Fork & Verified Publish ---

    fun ensureStudioProjectReady() {
        viewModelScope.launch {
            val owner = activeAccount.value?.pubkeyHex.orEmpty()
            val proj = repository.projectRepository.ensureInitialProjectExists(owner)
            if (_activeProjectId.value == null) {
                _activeProjectId.value = proj.projectId
                _selectedFilePath.value = proj.entryPath
            }
        }
    }

    fun selectProject(projectId: String) {
        _activeProjectId.value = projectId
        _latestDeployment.value = null
        _latestPublishVerification.value = null
        _latestForkOutcome.value = null
        val target = allProjects.value.firstOrNull { it.projectId == projectId }
        if (target != null) {
            _selectedFilePath.value = target.entryPath
        }
    }

    fun selectProjectFile(relativePath: String) {
        _selectedFilePath.value = relativePath
    }

    fun createNewScratchProject(title: String, dTag: String, targetKind: Int = NostrKinds.NSITE_NAMED) {
        viewModelScope.launch {
            _isWorking.value = true
            runCatching {
                repository.projectRepository.createScratchProject(
                    ownerPubkeyHex = activeAccount.value?.pubkeyHex.orEmpty(),
                    title = title,
                    dTag = dTag,
                    targetKind = targetKind
                )
            }.onSuccess { created ->
                _activeProjectId.value = created.projectId
                _selectedFilePath.value = created.entryPath
                _latestDeployment.value = null
                _latestPublishVerification.value = null
                _latestForkOutcome.value = null
                showBanner("Created multi-file project '${created.title}'")
            }.onFailure { err ->
                showBanner(err.message ?: "Could not create project", isError = true)
            }
            _isWorking.value = false
        }
    }

    fun deleteProject(projectId: String) {
        viewModelScope.launch {
            runCatching { repository.projectRepository.deleteEntireProject(projectId) }
                .onSuccess {
                    if (_activeProjectId.value == projectId) {
                        _activeProjectId.value = null
                    }
                    ensureStudioProjectReady()
                    showBanner("Deleted local project")
                }
        }
    }

    fun importSingleHtmlUri(uri: Uri) {
        viewModelScope.launch {
            _isWorking.value = true
            runCatching {
                val app = getApplication<Application>()
                val name = queryUriFileName(uri).ifBlank { "index.html" }
                val bytes = app.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: error("Unable to read selected HTML file")
                repository.projectRepository.importSingleHtmlFile(
                    ownerPubkeyHex = activeAccount.value?.pubkeyHex.orEmpty(),
                    titleHint = name,
                    originalFileName = name,
                    htmlBytes = bytes
                )
            }.onSuccess { created ->
                _activeProjectId.value = created.projectId
                _selectedFilePath.value = created.entryPath
                _latestDeployment.value = null
                _latestPublishVerification.value = null
                _latestForkOutcome.value = null
                showBanner("Imported HTML file into '${created.title}'")
            }.onFailure { err ->
                showBanner(err.message ?: "HTML import failed", isError = true)
            }
            _isWorking.value = false
        }
    }

    fun importZipArchiveUri(uri: Uri) {
        viewModelScope.launch {
            _isWorking.value = true
            runCatching {
                val app = getApplication<Application>()
                val name = queryUriFileName(uri).ifBlank { "website.zip" }
                app.contentResolver.openInputStream(uri)?.use { stream ->
                    repository.projectRepository.importZipArchive(
                        ownerPubkeyHex = activeAccount.value?.pubkeyHex.orEmpty(),
                        titleHint = name,
                        zipInputStream = stream
                    )
                } ?: error("Unable to open selected ZIP archive")
            }.onSuccess { created ->
                _activeProjectId.value = created.projectId
                _selectedFilePath.value = created.entryPath
                _latestDeployment.value = null
                _latestPublishVerification.value = null
                _latestForkOutcome.value = null
                showBanner("Imported multi-file ZIP into '${created.title}'")
            }.onFailure { err ->
                showBanner(err.message ?: "ZIP import failed", isError = true)
            }
            _isWorking.value = false
        }
    }

    fun importFolderTreeUri(treeUri: Uri) {
        viewModelScope.launch {
            _isWorking.value = true
            runCatching {
                repository.projectRepository.importFolderFromTreeUri(
                    context = getApplication(),
                    ownerPubkeyHex = activeAccount.value?.pubkeyHex.orEmpty(),
                    treeUri = treeUri
                )
            }.onSuccess { created ->
                _activeProjectId.value = created.projectId
                _selectedFilePath.value = created.entryPath
                _latestDeployment.value = null
                _latestPublishVerification.value = null
                _latestForkOutcome.value = null
                showBanner("Imported folder into '${created.title}'")
            }.onFailure { err ->
                showBanner(err.message ?: "Folder import failed", isError = true)
            }
            _isWorking.value = false
        }
    }

    fun forkAppletToStudio(
        applet: InstalledAppletEntity,
        specificVersion: NsiteVersionEntity? = null
    ) {
        viewModelScope.launch {
            _isWorking.value = true
            runCatching {
                repository.forkSiteIntoProject(applet, specificVersion)
            }.onSuccess { outcome ->
                _activeProjectId.value = outcome.project.projectId
                _selectedFilePath.value = outcome.project.entryPath
                _latestDeployment.value = null
                _latestPublishVerification.value = null
                _latestForkOutcome.value = outcome
                navigateTo(ShellDestination.CreatorStudio)
                if (outcome.missingOrCorruptPaths.isEmpty()) {
                    showBanner("Forked '${applet.title}' (${outcome.verifiedPaths.size} verified files)")
                } else {
                    showBanner(
                        "Forked '${applet.title}' with ${outcome.missingOrCorruptPaths.size} missing/corrupt blob(s)",
                        isError = true
                    )
                }
            }.onFailure { err ->
                showBanner(err.message ?: "Fork failed", isError = true)
            }
            _isWorking.value = false
        }
    }

    fun forkManualAddressToStudio(rawAddress: String, onDone: () -> Unit = {}) {
        val trimmed = rawAddress.trim()
        if (trimmed.isBlank()) {
            showBanner("Enter an Nsite URL, naddr1…, or coordinate to fork", isError = true)
            return
        }
        viewModelScope.launch {
            _isWorking.value = true
            runCatching {
                repository.forkByManualAddress(trimmed)
            }.onSuccess { outcome ->
                onDone()
                _activeProjectId.value = outcome.project.projectId
                _selectedFilePath.value = outcome.project.entryPath
                _latestDeployment.value = null
                _latestPublishVerification.value = null
                _latestForkOutcome.value = outcome
                navigateTo(ShellDestination.CreatorStudio)
                if (outcome.missingOrCorruptPaths.isEmpty()) {
                    showBanner("Forked ${outcome.verifiedPaths.size} verified Blossom file(s) into Studio")
                } else {
                    showBanner(
                        "Partial fork: ${outcome.missingOrCorruptPaths.size} file(s) missing or hash-mismatched",
                        isError = true
                    )
                }
            }.onFailure { err ->
                showBanner(err.message ?: "Could not resolve or fork Nsite", isError = true)
            }
            _isWorking.value = false
        }
    }

    fun saveActiveProjectMetadata(
        title: String,
        dTag: String,
        targetKind: Int,
        description: String,
        entryPath: String
    ) {
        val proj = activeProject.value ?: return
        viewModelScope.launch {
            repository.projectRepository.updateProjectMetadata(
                projectId = proj.projectId,
                title = title,
                dTag = dTag,
                targetKind = targetKind,
                description = description,
                entryPath = entryPath
            )
            showBanner("Saved project settings")
        }
    }

    fun saveProjectTextFile(relativePath: String, content: String, announce: Boolean = false) {
        val proj = activeProject.value ?: return
        viewModelScope.launch {
            runCatching {
                repository.projectRepository.saveTextFile(proj.projectId, relativePath, content)
            }.onSuccess { saved ->
                if (announce) {
                    showBanner("Saved ${saved.relativePath} (${saved.sha256.take(10)}…)")
                }
            }.onFailure { err ->
                showBanner(err.message ?: "Failed saving file", isError = true)
            }
        }
    }

    fun createNewProjectFile(relativePath: String, initialContent: String = "") {
        val proj = activeProject.value ?: return
        viewModelScope.launch {
            runCatching {
                repository.projectRepository.saveTextFile(proj.projectId, relativePath, initialContent)
            }.onSuccess { created ->
                _selectedFilePath.value = created.relativePath
                showBanner("Created ${created.relativePath}")
            }.onFailure { err ->
                showBanner(err.message ?: "Could not create file", isError = true)
            }
        }
    }

    fun importAssetIntoProject(targetRelativePath: String, uri: Uri) {
        val proj = activeProject.value ?: return
        viewModelScope.launch {
            _isWorking.value = true
            runCatching {
                val app = getApplication<Application>()
                val pickedName = queryUriFileName(uri).ifBlank { "asset.bin" }
                val effectivePath = targetRelativePath.trim().ifBlank { "/assets/$pickedName" }
                val bytes = app.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: error("Unable to read selected asset")
                repository.projectRepository.importOrReplaceAsset(proj.projectId, effectivePath, bytes)
            }.onSuccess { entity ->
                _selectedFilePath.value = entity.relativePath
                showBanner("Imported asset ${entity.relativePath} (${entity.sizeBytes} B)")
            }.onFailure { err ->
                showBanner(err.message ?: "Asset import failed", isError = true)
            }
            _isWorking.value = false
        }
    }

    fun renameProjectFile(oldPath: String, newPath: String) {
        val proj = activeProject.value ?: return
        viewModelScope.launch {
            runCatching {
                repository.projectRepository.renameProjectFile(proj.projectId, oldPath, newPath)
            }.onSuccess { renamed ->
                if (renamed != null) {
                    _selectedFilePath.value = renamed.relativePath
                    showBanner("Renamed to ${renamed.relativePath}")
                }
            }.onFailure { err ->
                showBanner(err.message ?: "Rename failed", isError = true)
            }
        }
    }

    fun deleteProjectFile(relativePath: String) {
        val proj = activeProject.value ?: return
        viewModelScope.launch {
            repository.projectRepository.deleteProjectFile(proj.projectId, relativePath)
            if (_selectedFilePath.value == relativePath) {
                _selectedFilePath.value = proj.entryPath
            }
            showBanner("Deleted $relativePath")
        }
    }

    fun publishActiveProject() {
        val proj = activeProject.value
        if (proj == null) {
            showBanner("Select or create a project first", isError = true)
            return
        }
        val account = activeAccount.value
        if (account == null || !account.canSign) {
            showBanner("Active account is read-only. Switch to a signing identity in Vault & Net.", isError = true)
            return
        }
        viewModelScope.launch {
            _isWorking.value = true
            runCatching {
                repository.projectRepository.publishProjectAsNip5a(
                    projectId = proj.projectId,
                    activeAccount = account
                )
            }.onSuccess { (report, verification) ->
                _latestDeployment.value = report
                _latestPublishVerification.value = verification
                showBanner(
                    "Published '${report.title}' — ${verification.verifiedUploadedBlobs} verified blobs, " +
                        "${verification.verifiedRetrievalRelays}/${verification.acceptedRelays} verified relays"
                )
            }.onFailure { err ->
                showBanner(err.message ?: "NIP-5A publish failed", isError = true)
            }
            _isWorking.value = false
        }
    }

    // --- Launcher Appearance & Browser Preferences ---

    fun toggleLauncherViewMode() {
        viewModelScope.launch {
            repository.prefsRepo.toggleViewMode(launcherPrefs.value.viewMode)
        }
    }

    fun toggleDiscoveryDisplayMode() {
        viewModelScope.launch {
            val current = launcherPrefs.value.discoveryMode
            repository.prefsRepo.toggleDiscoveryMode(current)
            val switchedToFeed = current == DiscoveryDisplayMode.CURATED_GALLERY
            showBanner(
                if (switchedToFeed) "Switched to Live NIP-5A Relay Feed"
                else "Switched to Stacked Nsite Gallery"
            )
        }
    }

    fun pickCustomWallpaper(uri: Uri) {
        viewModelScope.launch {
            val savedPath = repository.copyPickedImageToInternalFile(uri, "wallpapers", "bg")
            if (savedPath != null) {
                repository.prefsRepo.setWallpaperStyle(WallpaperStyle.CUSTOM_IMAGE, savedPath)
                showBanner("Custom launcher background applied")
            } else {
                showBanner("Unable to import selected image", isError = true)
            }
        }
    }

    fun selectPresetWallpaper(style: WallpaperStyle) {
        viewModelScope.launch {
            repository.prefsRepo.setWallpaperStyle(style)
            showBanner("Wallpaper preset updated")
        }
    }

    fun updateWallpaperScrim(alpha: Float) {
        viewModelScope.launch {
            repository.prefsRepo.setScrimAlpha(alpha)
        }
    }

    fun updateBlossomServer(url: String) {
        viewModelScope.launch {
            runCatching { repository.setPrimaryBlossomServer(url) }
                .onSuccess { showBanner("Primary Blossom server set to ${url.trim()}") }
                .onFailure { showBanner(it.message ?: "Invalid Blossom URL", isError = true) }
        }
    }

    fun setOpenNsitesExternally(enabled: Boolean) {
        viewModelScope.launch {
            repository.prefsRepo.setOpenNsitesExternally(enabled)
            showBanner(
                if (enabled) "Default set to External Browser / Custom Tab"
                else "Default set to Built-in WebView Runtime"
            )
        }
    }

    // --- User Profile System (Kind 0) ---

    suspend fun importAvatarUri(uri: Uri): String? =
        repository.copyPickedImageToInternalFile(uri, "avatars", "avatar")

    fun saveUserProfile(
        displayName: String,
        bio: String,
        localAvatarPath: String,
        uploadAvatarToBlossom: Boolean,
        badgeGlyph: String = "≠",
        badgeColorHex: String = "#FF1493"
    ) {
        viewModelScope.launch {
            _isWorking.value = true
            runCatching {
                repository.saveAndPublishUserProfile(
                    displayName = displayName,
                    bio = bio,
                    localAvatarPath = localAvatarPath,
                    uploadAvatarToBlossom = uploadAvatarToBlossom,
                    badgeGlyph = badgeGlyph,
                    badgeColorHex = badgeColorHex
                )
            }.onSuccess { summary ->
                showBanner(
                    "Profile saved & Kind 0 signed (${summary.acceptedCount}/${summary.receipts.size} relays)"
                )
            }.onFailure { err ->
                showBanner(err.message ?: "Failed to save profile", isError = true)
            }
            _isWorking.value = false
        }
    }

    // --- Multi-Account Vault + NIP-55 + NIP-46 + Read-Only npub ---

    fun generateAccount(alias: String) {
        viewModelScope.launch {
            runCatching { repository.createNewAccount(alias) }
                .onSuccess { acc -> showBanner("Created & activated ${acc.npub.take(14)}…") }
                .onFailure { showBanner(it.message ?: "Could not generate key", isError = true) }
        }
    }

    fun importAccount(keyInput: String, alias: String) {
        viewModelScope.launch {
            runCatching { repository.importAccountFromKey(keyInput, alias) }
                .onSuccess { acc ->
                    val mode = acc.resolvedSignerType.label
                    showBanner("Imported ${acc.npub.take(14)}… ($mode)")
                }
                .onFailure { showBanner(it.message ?: "Invalid key", isError = true) }
        }
    }

    fun addReadOnlyNpub(npubInput: String, alias: String) {
        viewModelScope.launch {
            runCatching { repository.addReadOnlyNpubAccount(npubInput, alias) }
                .onSuccess { acc -> showBanner("Added read-only identity ${acc.npub.take(14)}…") }
                .onFailure { showBanner(it.message ?: "Invalid npub", isError = true) }
        }
    }

    fun connectNip55Signer(preferredPackage: String = "", alias: String = "") {
        viewModelScope.launch {
            _isWorking.value = true
            runCatching { repository.connectNip55SignerAccount(preferredPackage, alias) }
                .onSuccess { acc -> showBanner("Connected NIP-55 Android signer (${acc.npub.take(14)}…)") }
                .onFailure { showBanner(it.message ?: "NIP-55 signer connection failed", isError = true) }
            _isWorking.value = false
        }
    }

    fun connectNip46Bunker(bunkerUri: String, alias: String = "", onConnected: () -> Unit = {}) {
        viewModelScope.launch {
            _isWorking.value = true
            runCatching { repository.connectNip46BunkerAccount(bunkerUri, alias) }
                .onSuccess { acc ->
                    onConnected()
                    showBanner("Connected NIP-46 remote bunker (${acc.npub.take(14)}…)")
                }
                .onFailure { showBanner(it.message ?: "NIP-46 bunker handshake failed", isError = true) }
            _isWorking.value = false
        }
    }

    fun switchAccount(pubkeyHex: String) {
        viewModelScope.launch {
            _revealedNsec.value = null
            repository.switchAccount(pubkeyHex)
            showBanner("Active Nostr identity switched")
        }
    }

    fun toggleRevealNsec() {
        viewModelScope.launch {
            if (_revealedNsec.value != null) {
                _revealedNsec.value = null
            } else {
                val exported = repository.exportActiveNsec()
                if (exported == null) {
                    showBanner("Active identity uses an external or read-only signer (no local nsec)", isError = true)
                } else {
                    _revealedNsec.value = exported
                }
            }
        }
    }

    fun revokePolicy(policyKey: String) {
        viewModelScope.launch {
            repository.revokePolicy(policyKey)
            showBanner("Signing permission policy revoked")
        }
    }

    // --- Adaptive Relay + Blossom Configuration ---

    fun addRelay(urlInput: String, onSuccessClear: () -> Unit = {}) {
        viewModelScope.launch {
            runCatching { repository.addRelayUrl(urlInput) }
                .onSuccess { normalized ->
                    onSuccessClear()
                    showBanner("Added & probing $normalized")
                }
                .onFailure { err ->
                    showBanner(err.message ?: "Invalid relay URL", isError = true)
                }
        }
    }

    fun removeRelay(url: String) {
        viewModelScope.launch {
            repository.removeRelayUrl(url)
            showBanner("Removed $url")
        }
    }

    fun reconnectRelay(url: String) {
        repository.reconnectRelay(url)
        showBanner("Reconnecting & probing $url…")
    }

    fun addBlossomServer(urlInput: String, asPrimary: Boolean = false, onSuccessClear: () -> Unit = {}) {
        viewModelScope.launch {
            runCatching { repository.addBlossomServerUrl(urlInput, asPrimary = asPrimary) }
                .onSuccess { clean ->
                    onSuccessClear()
                    showBanner(
                        if (asPrimary) "Set primary Blossom server: $clean"
                        else "Added Blossom backup mirror: $clean"
                    )
                }
                .onFailure { err ->
                    showBanner(err.message ?: "Invalid Blossom URL", isError = true)
                }
        }
    }

    fun setPrimaryBlossomServer(url: String) {
        viewModelScope.launch {
            runCatching { repository.setPrimaryBlossomServer(url) }
                .onSuccess { showBanner("Primary Blossom server updated to $url") }
                .onFailure { showBanner(it.message ?: "Could not set primary Blossom server", isError = true) }
        }
    }

    fun removeBlossomServer(url: String) {
        viewModelScope.launch {
            repository.removeBlossomServerUrl(url)
            showBanner("Removed Blossom server $url")
        }
    }

    fun probeAllNetworkEndpoints() {
        viewModelScope.launch {
            _isWorking.value = true
            runCatching { repository.probeAllNetworkHealth() }
                .onSuccess { showBanner("Probed NIP-11 relay & BUD-01/02 Blossom capabilities") }
                .onFailure { showBanner(it.message ?: "Network probe failed", isError = true) }
            _isWorking.value = false
        }
    }

    fun publishNip65Relays() {
        viewModelScope.launch {
            _isWorking.value = true
            runCatching { repository.publishRelayListNip65() }
                .onSuccess { res ->
                    showBanner(
                        "Published NIP-65 relay list (${res.acceptedCount} accepted, ${res.verifiedRetrievalCount} verified)"
                    )
                }
                .onFailure { showBanner(it.message ?: "Failed to publish relay list", isError = true) }
            _isWorking.value = false
        }
    }

    fun publishBlossomListKind10063() {
        viewModelScope.launch {
            _isWorking.value = true
            runCatching { repository.publishBlossomServersKind10063() }
                .onSuccess { res ->
                    showBanner(
                        "Published Kind 10063 Blossom list (${res.acceptedCount} accepted, ${res.verifiedRetrievalCount} verified)"
                    )
                }
                .onFailure { showBanner(it.message ?: "Failed to publish Blossom list", isError = true) }
            _isWorking.value = false
        }
    }

    // --- Notes Composer (NIP-01 Kind 1) ---

    fun promptSignAndPublishNote(
        content: String,
        hashtagsRaw: String,
        requireInteractiveConfirm: Boolean,
        onPublished: () -> Unit
    ) {
        val trimmed = content.trim()
        if (trimmed.isBlank()) {
            showBanner("Enter note text before signing", isError = true)
            return
        }
        val hashtags = hashtagsRaw.split(",", " ")
            .map { it.trim().removePrefix("#") }
            .filter { it.isNotBlank() }

        viewModelScope.launch {
            val active = activeAccount.value
            if (active == null || !active.canSign) {
                showBanner("Switch to a signing Nostr identity first", isError = true)
                return@launch
            }

            val approved = if (requireInteractiveConfirm) {
                requestInteractiveSignerDecision(
                    appletId = "shell:note-composer",
                    appletTitle = "SovereignShell Note Composer",
                    protocolLabel = active.resolvedSignerType.label,
                    method = "signEvent",
                    eventKind = NostrKinds.TEXT_NOTE,
                    contentPreview = trimmed,
                    rawPayloadJson = JSONObject().apply {
                        put("kind", 1)
                        put("pubkey", active.pubkeyHex)
                        put("content", trimmed)
                    }.toString(2)
                )
            } else {
                true
            }

            if (!approved) {
                showBanner("Signing cancelled", isError = true)
                return@launch
            }

            _isWorking.value = true
            runCatching {
                repository.createSignAndPublishNote(trimmed, hashtags)
            }.onSuccess { summary ->
                onPublished()
                showBanner(
                    "Signed Kind 1 (${summary.event.id.take(10)}…) & published to ${summary.acceptedCount}/${summary.receipts.size} relays"
                )
            }.onFailure { err ->
                showBanner(err.message ?: "Failed to sign/publish note", isError = true)
            }
            _isWorking.value = false
        }
    }

    // --- Discovery & Pinning ---

    fun toggleSpamShield() {
        val next = !_spamShieldEnabled.value
        _spamShieldEnabled.value = next
        showBanner(
            if (next) "Spam Shield enabled — bot clones hidden"
            else "Spam Shield paused — showing raw relay feed"
        )
    }

    fun refreshDiscoveryCatalog(silent: Boolean = false) {
        viewModelScope.launch {
            runCatching { repository.refreshCatalogFromRelays() }
                .onSuccess {
                    if (!silent) {
                        showBanner("Syncing NIP-5A Nsites & Kind 0 publisher profiles…")
                    }
                }
        }
    }

    fun lookupPublisher(rawInput: String): String? =
        repository.lookupPublisherAndSync(rawInput)

    fun toggleNip51Bookmark(applet: InstalledAppletEntity) {
        viewModelScope.launch {
            runCatching { repository.toggleLocalNip51Bookmark(applet) }
                .onSuccess { added ->
                    showBanner(
                        if (added) "Starred ${applet.title} in your NIP-51 curated Nsite list"
                        else "Removed ${applet.title} from NIP-51 list"
                    )
                }
                .onFailure { err ->
                    showBanner(err.message ?: "Could not update NIP-51 list", isError = true)
                }
        }
    }

    fun publishNip51CuratedList() {
        viewModelScope.launch {
            _isWorking.value = true
            runCatching { repository.publishNip51CuratedList() }
                .onSuccess { res ->
                    showBanner(
                        "Published NIP-51 Nsite list (Kind 30003) to ${res.acceptedCount}/${res.receipts.size} relays"
                    )
                }
                .onFailure { err ->
                    showBanner(err.message ?: "Failed to publish NIP-51 list", isError = true)
                }
            _isWorking.value = false
        }
    }

    fun setAppletInstalled(applet: InstalledAppletEntity, installed: Boolean) {
        viewModelScope.launch {
            repository.setAppletInstalled(applet.appletId, installed)
            showBanner(if (installed) "Saved ${applet.title} to Launcher Library" else "Removed ${applet.title} from Library")
        }
    }

    fun toggleAppletNip07(applet: InstalledAppletEntity) {
        viewModelScope.launch {
            val next = !applet.enableNip07
            repository.setAppletNip07(applet.appletId, next)
            showBanner(
                if (next) "Enabled NIP-07 window.nostr for ${applet.title}"
                else "Disabled NIP-07 window.nostr for ${applet.title}"
            )
        }
    }

    // --- User-Controlled NIP-07 Signer Broker (Never Exposes Private Keys to JS) ---

    fun resolveSignPrompt(decision: SignDecision) {
        val current = _activeSignPrompt.value ?: return
        _activeSignPrompt.value = null
        pendingSignDeferreds.remove(current.requestId)?.complete(decision)
    }

    suspend fun requestInteractiveSignerDecision(
        appletId: String,
        appletTitle: String,
        protocolLabel: String,
        method: String,
        eventKind: Int,
        contentPreview: String,
        rawPayloadJson: String
    ): Boolean {
        val active = repository.getActiveSigningAccount() ?: return false
        val savedAllow = repository.checkSavedPolicy(active.pubkeyHex, appletId, method, eventKind)
        if (savedAllow == true) {
            repository.recordAudit(
                account = active,
                appletId = appletId,
                appletTitle = appletTitle,
                protocolLabel = "$protocolLabel (Auto-Policy)",
                method = method,
                eventKind = eventKind,
                approved = true,
                summary = "Auto-approved $method (Kind $eventKind)"
            )
            return true
        }

        val reqId = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<SignDecision>()
        pendingSignDeferreds[reqId] = deferred
        _activeSignPrompt.value = PendingSignRequest(
            requestId = reqId,
            appletId = appletId,
            appletTitle = appletTitle,
            protocolLabel = protocolLabel,
            method = method,
            eventKind = eventKind,
            contentPreview = contentPreview,
            rawPayloadJson = rawPayloadJson,
            activePubkeyHex = active.pubkeyHex,
            activeNpub = active.npub,
            activeDisplayName = active.displayName
        )

        val decision = deferred.await()
        val approved = decision != SignDecision.REJECT
        if (decision == SignDecision.ALLOW_ALWAYS_KIND) {
            repository.saveAlwaysAllowPolicy(
                pubkeyHex = active.pubkeyHex,
                appletId = appletId,
                appletTitle = appletTitle,
                method = method,
                kind = eventKind
            )
        }
        repository.recordAudit(
            account = active,
            appletId = appletId,
            appletTitle = appletTitle,
            protocolLabel = protocolLabel,
            method = method,
            eventKind = eventKind,
            approved = approved,
            summary = "${if (approved) "Approved" else "Rejected"} $method (Kind $eventKind): ${contentPreview.take(40)}"
        )
        return approved
    }

    suspend fun handleAppletBridgeCall(
        applet: InstalledAppletEntity,
        protocolLabel: String,
        type: String,
        payload: JSONObject
    ): Any? {
        val active = repository.dao.getActiveAccount()
            ?: error("No active Nostr identity")

        return when (type) {
            "signer.getPublicKey" -> active.pubkeyHex

            "signer.getRelays" -> {
                val obj = JSONObject()
                managedRelays.value.forEach { r ->
                    obj.put(r.url, JSONObject().put("read", r.readEnabled).put("write", r.writeEnabled))
                }
                obj
            }

            "signer.signEvent" -> {
                require(active.canSign) {
                    "Active identity (${active.displayName}) is read-only. Switch to a signing identity."
                }
                val evJson = payload.optJSONObject("event") ?: error("Missing event object")
                val kind = evJson.optInt("kind", 1)
                val content = evJson.optString("content", "")
                val createdAt = evJson.optLong("created_at", System.currentTimeMillis() / 1000L)
                val tagsArr = evJson.optJSONArray("tags") ?: JSONArray()
                val tags = buildList {
                    for (i in 0 until tagsArr.length()) {
                        val r = tagsArr.optJSONArray(i) ?: continue
                        add(List(r.length()) { idx -> r.optString(idx, "") })
                    }
                }
                val approved = requestInteractiveSignerDecision(
                    appletId = applet.appletId,
                    appletTitle = applet.title,
                    protocolLabel = protocolLabel,
                    method = "signEvent",
                    eventKind = kind,
                    contentPreview = content.ifBlank { "(Empty content, ${tags.size} tags)" },
                    rawPayloadJson = evJson.toString(2)
                )
                if (!approved) error("User rejected signature request")
                repository.signerManager.signEventForAccount(
                    account = active,
                    unsigned = UnsignedNostrEvent(
                        pubkey = active.pubkeyHex,
                        kind = kind,
                        content = content,
                        tags = tags,
                        createdAt = createdAt
                    )
                ).toJsonObject()
            }

            "signer.nip04Encrypt" -> {
                require(active.canSign) { "Read-only identity cannot encrypt" }
                val peerPub = payload.optString("pubkey").ifBlank { active.pubkeyHex }
                val plaintext = payload.optString("plaintext")
                val approved = requestInteractiveSignerDecision(
                    appletId = applet.appletId,
                    appletTitle = applet.title,
                    protocolLabel = protocolLabel,
                    method = "nip04Encrypt",
                    eventKind = 4,
                    contentPreview = "Encrypt ${plaintext.length} chars to ${peerPub.take(12)}…",
                    rawPayloadJson = payload.toString(2)
                )
                if (!approved) error("User rejected NIP-04 encrypt")
                repository.signerManager.nip04Encrypt(active, peerPub, plaintext)
            }

            "signer.nip04Decrypt" -> {
                require(active.canSign) { "Read-only identity cannot decrypt" }
                val peerPub = payload.optString("pubkey").ifBlank { active.pubkeyHex }
                val ciphertext = payload.optString("ciphertext")
                val approved = requestInteractiveSignerDecision(
                    appletId = applet.appletId,
                    appletTitle = applet.title,
                    protocolLabel = protocolLabel,
                    method = "nip04Decrypt",
                    eventKind = 4,
                    contentPreview = "Decrypt NIP-04 ciphertext from ${peerPub.take(12)}…",
                    rawPayloadJson = payload.toString(2)
                )
                if (!approved) error("User rejected NIP-04 decrypt")
                repository.signerManager.nip04Decrypt(active, peerPub, ciphertext)
            }

            "signer.nip44Encrypt" -> {
                require(active.canSign) { "Read-only identity cannot encrypt" }
                val peerPub = payload.optString("pubkey").ifBlank { active.pubkeyHex }
                val plaintext = payload.optString("plaintext")
                val approved = requestInteractiveSignerDecision(
                    appletId = applet.appletId,
                    appletTitle = applet.title,
                    protocolLabel = protocolLabel,
                    method = "nip44Encrypt",
                    eventKind = 44,
                    contentPreview = "NIP-44 v2 encrypt ${plaintext.length} chars to ${peerPub.take(12)}…",
                    rawPayloadJson = payload.toString(2)
                )
                if (!approved) error("User rejected NIP-44 encrypt")
                repository.signerManager.nip44Encrypt(active, peerPub, plaintext)
            }

            "signer.nip44Decrypt" -> {
                require(active.canSign) { "Read-only identity cannot decrypt" }
                val peerPub = payload.optString("pubkey").ifBlank { active.pubkeyHex }
                val ciphertext = payload.optString("ciphertext")
                val approved = requestInteractiveSignerDecision(
                    appletId = applet.appletId,
                    appletTitle = applet.title,
                    protocolLabel = protocolLabel,
                    method = "nip44Decrypt",
                    eventKind = 44,
                    contentPreview = "NIP-44 v2 decrypt ciphertext from ${peerPub.take(12)}…",
                    rawPayloadJson = payload.toString(2)
                )
                if (!approved) error("User rejected NIP-44 decrypt")
                repository.signerManager.nip44Decrypt(active, peerPub, ciphertext)
            }

            else -> error("Unsupported signer method: $type")
        }
    }

    suspend fun previewStudioBridgeCall(
        project: NsiteProjectEntity,
        protocolLabel: String,
        type: String,
        payload: JSONObject
    ): Any? {
        val active = activeAccount.value
        val syntheticApplet = InstalledAppletEntity(
            appletId = "studio:preview:${project.projectId}",
            kind = project.targetKind,
            authorPubkey = active?.pubkeyHex.orEmpty(),
            authorNpub = active?.npub.orEmpty(),
            dTag = project.dTag.ifBlank { "preview" },
            title = "${project.title.ifBlank { "Studio Project" }} (Preview)",
            description = project.description,
            iconGlyph = "🌐",
            accentHex = "#FF1493",
            isNsite = true,
            enableNip07 = true
        )
        return handleAppletBridgeCall(syntheticApplet, protocolLabel, type, payload)
    }

    private fun queryUriFileName(uri: Uri): String {
        val app = getApplication<Application>()
        return runCatching {
            app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0).orEmpty() else ""
            }.orEmpty()
        }.getOrDefault("").ifBlank {
            uri.lastPathSegment?.substringAfterLast('/').orEmpty()
        }
    }

    class Factory(private val application: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ShellViewModel(application) as T
        }
    }
}
