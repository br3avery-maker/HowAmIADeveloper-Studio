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
import com.example.data.GraphicTemplatePreset
import com.example.data.InstalledAppletEntity
import com.example.data.LauncherPrefs
import com.example.data.LauncherViewMode
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
import com.example.data.StarterCatalog
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
import kotlinx.coroutines.flow.update
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
        val initialTabIsNsites: Boolean = true,
        val initialPublisherPubkey: String? = null
    ) : ShellDestination
    data object UserProfile : ShellDestination
    data object RelayManager : ShellDestination
    data object NotesComposer : ShellDestination
    data class CreatorStudio(val forkSource: InstalledAppletEntity? = null) : ShellDestination
    data object VaultAndAudit : ShellDestination
    data object WallpaperSettings : ShellDestination
    data class RunningApplet(val appletId: String) : ShellDestination
}

data class StudioDraftState(
    val title: String = "My Sovereign Nsite",
    val dTag: String = "my-nsite",
    val description: String = "Multi-file NIP-5A website hosted on Blossom.",
    val iconGlyph: String = "🌐",
    val targetKind: Int = NostrKinds.NSITE_NAMED,
    val html: String = DEFAULT_STUDIO_HTML,
    val css: String = StarterCatalog.COMMON_APPLET_CSS.trimIndent(),
    val js: String = DEFAULT_STUDIO_JS
) {
    companion object {
        val DEFAULT_STUDIO_HTML = """
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <title>My Sovereign Nsite</title>
  <link rel="stylesheet" href="./css/style.css" />
</head>
<body>
  <div class="card">
    <h2>⚡ Sovereign Nsite</h2>
    <p>Edit multi-file HTML, CSS, JS, and assets, then publish to Blossom &amp; Nostr relays.</p>
    <button id="helloSign">Sign Event via window.nostr</button>
    <div id="resultBox" class="mono" style="margin-top:10px;"></div>
  </div>
  <script type="module" src="./js/app.js"></script>
</body>
</html>
        """.trimIndent()

        val DEFAULT_STUDIO_JS = """
document.getElementById('helloSign')?.addEventListener('click', async () => {
  const out = document.getElementById('resultBox');
  if (!window.nostr) {
    out.textContent = 'NIP-07 window.nostr disabled for this view.';
    return;
  }
  try {
    const signed = await window.nostr.signEvent({
      kind: 1,
      created_at: Math.floor(Date.now() / 1000),
      tags: [],
      content: 'Published from my NIP-5A Nsite! #nostr #nsite'
    });
    out.textContent = 'Signed Event ID: ' + signed.id.slice(0, 18) + '…';
  } catch (err) {
    out.textContent = 'Denied: ' + err.message;
  }
});
        """.trimIndent()
    }
}

data class TransientStatusBanner(
    val message: String,
    val isError: Boolean = false,
    val id: Long = System.currentTimeMillis()
)

@OptIn(ExperimentalCoroutinesApi::class)
class ShellViewModel @JvmOverloads constructor(
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

    private val _studioDraft = MutableStateFlow(StudioDraftState())
    val studioDraft: StateFlow<StudioDraftState> = _studioDraft.asStateFlow()

    private val _latestDeployment = MutableStateFlow<PublishDeploymentReport?>(null)
    val latestDeployment: StateFlow<PublishDeploymentReport?> = _latestDeployment.asStateFlow()

    private val _latestPublishVerification = MutableStateFlow<PublishVerificationBreakdown?>(null)
    val latestPublishVerification: StateFlow<PublishVerificationBreakdown?> = _latestPublishVerification.asStateFlow()

    private val _revealedNsec = MutableStateFlow<String?>(null)
    val revealedNsec: StateFlow<String?> = _revealedNsec.asStateFlow()

    private val pendingSignDeferreds = ConcurrentHashMap<String, CompletableDeferred<SignDecision>>()
    private val _activeSignPrompt = MutableStateFlow<PendingSignRequest?>(null)
    val activeSignPrompt: StateFlow<PendingSignRequest?> = _activeSignPrompt.asStateFlow()

    // Multi-file Nsite Project Workspace State
    val allProjects: StateFlow<List<NsiteProjectEntity>> = repository.projectRepository.allProjectsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _activeProjectId = MutableStateFlow<String?>(null)
    val activeProjectId: StateFlow<String?> = _activeProjectId.asStateFlow()

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

    private val _selectedFilePath = MutableStateFlow("/index.html")
    val selectedFilePath: StateFlow<String> = _selectedFilePath.asStateFlow()

    val accounts: StateFlow<List<VaultAccountEntity>> = repository.accountsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val activeAccount: StateFlow<VaultAccountEntity?> = repository.activeAccountFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val launcherPrefs: StateFlow<LauncherPrefs> = repository.prefsRepo.prefsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LauncherPrefs())

    private val _spamShieldEnabled = MutableStateFlow(true)
    val spamShieldEnabled: StateFlow<Boolean> = _spamShieldEnabled.asStateFlow()

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

    val managedRelays: StateFlow<List<ManagedRelayItem>> = repository.managedRelaysFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val blossomServers: StateFlow<List<ManagedBlossomItem>> = repository.managedBlossomServersFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val notes: StateFlow<List<NoteEntity>> = repository.notesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val auditLog: StateFlow<List<SignerAuditEntity>> = repository.auditLogFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val signerPolicies: StateFlow<List<SignerPolicyEntity>> = repository.signerPoliciesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val connectedRelayCount: StateFlow<Int> = combine(managedRelays) { arr ->
        arr.first().count { it.statusInfo.status == RelayConnectionStatus.CONNECTED }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    init {
        viewModelScope.launch {
            val active = repository.dao.getActiveAccount()
            val initialProj = repository.projectRepository.ensureInitialProjectExists(
                ownerPubkeyHex = active?.pubkeyHex.orEmpty()
            )
            if (_activeProjectId.value == null) {
                _activeProjectId.value = initialProj.projectId
                _selectedFilePath.value = initialProj.entryPath
            }
        }
    }

    fun observeVersionsForSite(coordinate: String): Flow<List<NsiteVersionEntity>> =
        repository.observeNsiteVersions(coordinate)

    // --- Navigation & 4-Tab + Context-Aware Center "≠" FAB ---

    fun activePrimaryTabFor(dest: ShellDestination): PrimaryShellTab = when (dest) {
        ShellDestination.HomeLauncher,
        ShellDestination.NotesComposer,
        ShellDestination.WallpaperSettings -> PrimaryShellTab.LAUNCHER
        is ShellDestination.DiscoveryFeed -> PrimaryShellTab.DISCOVER
        is ShellDestination.CreatorStudio -> PrimaryShellTab.STUDIO
        ShellDestination.RelayManager,
        ShellDestination.VaultAndAudit,
        ShellDestination.UserProfile -> PrimaryShellTab.VAULT_NET
        is ShellDestination.RunningApplet -> PrimaryShellTab.LAUNCHER
    }

    fun selectPrimaryTab(tab: PrimaryShellTab) {
        val target = when (tab) {
            PrimaryShellTab.LAUNCHER -> ShellDestination.HomeLauncher
            PrimaryShellTab.DISCOVER -> ShellDestination.DiscoveryFeed()
            PrimaryShellTab.STUDIO -> ShellDestination.CreatorStudio()
            PrimaryShellTab.VAULT_NET -> ShellDestination.RelayManager
        }
        navigateTo(target)
    }

    /**
     * Context-aware Center Glowing "≠" FAB action:
     * Publishes/triggers the signed Nostr event corresponding to the currently active domain!
     */
    fun triggerDomainPublishAction() {
        when (val dest = _currentDestination.value) {
            ShellDestination.HomeLauncher -> {
                navigateTo(ShellDestination.NotesComposer)
            }
            is ShellDestination.DiscoveryFeed -> {
                if (localCuratedCount.value > 0) {
                    publishNip51CuratedList()
                } else {
                    navigateTo(ShellDestination.CreatorStudio())
                }
            }
            is ShellDestination.CreatorStudio -> {
                publishActiveProject()
            }
            ShellDestination.RelayManager -> {
                publishNip65AndBlossomLists()
            }
            ShellDestination.VaultAndAudit,
            ShellDestination.UserProfile -> {
                navigateTo(ShellDestination.UserProfile)
            }
            ShellDestination.NotesComposer -> {
                // Already on Note Composer
            }
            ShellDestination.WallpaperSettings -> {
                navigateTo(ShellDestination.CreatorStudio())
            }
            is ShellDestination.RunningApplet -> {
                val running = catalogApplets.value.firstOrNull { it.appletId == dest.appletId }
                    ?: installedApplets.value.firstOrNull { it.appletId == dest.appletId }
                if (running != null) {
                    forkAppletToStudio(running, null)
                }
            }
        }
    }

    fun navigateTo(destination: ShellDestination) {
        if (destination is ShellDestination.CreatorStudio && destination.forkSource != null) {
            forkAppletToStudio(destination.forkSource, null)
            return
        }
        _latestDeployment.value = null
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

    // --- Manual NIP-5A Address Resolution & Launch ---

    fun launchOrInstallByAddress(rawAddress: String, openImmediately: Boolean = true, onDone: () -> Unit = {}) {
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
                onDone()
                showBanner("Verified & pinned ${resolved.title}")
                if (openImmediately) {
                    navigateTo(ShellDestination.RunningApplet(resolved.appletId))
                }
            }.onFailure { err ->
                showBanner(err.message ?: "Could not resolve Nsite address", isError = true)
            }
            _isWorking.value = false
        }
    }

    // --- Multi-File Nsite Project Studio, Forker & Publisher ---

    fun selectProject(projectId: String) {
        _activeProjectId.value = projectId
        _latestDeployment.value = null
        _latestPublishVerification.value = null
        viewModelScope.launch {
            val proj = repository.dao.getProjectById(projectId)
            val files = repository.dao.getProjectFilesList(projectId)
            _selectedFilePath.value = proj?.entryPath?.takeIf { ep -> files.any { it.relativePath == ep } }
                ?: files.firstOrNull()?.relativePath
                ?: "/index.html"
        }
    }

    fun selectProjectFile(relativePath: String) {
        _selectedFilePath.value = relativePath
    }

    fun createNewScratchProject(title: String, dTag: String, targetKind: Int = NostrKinds.NSITE_NAMED) {
        viewModelScope.launch {
            _isWorking.value = true
            runCatching {
                val owner = activeAccount.value?.pubkeyHex.orEmpty()
                repository.projectRepository.createScratchProject(
                    ownerPubkeyHex = owner,
                    title = title,
                    dTag = dTag,
                    targetKind = targetKind
                )
            }.onSuccess { proj ->
                _activeProjectId.value = proj.projectId
                _selectedFilePath.value = proj.entryPath
                _latestDeployment.value = null
                _latestPublishVerification.value = null
                showBanner("Created multi-file project '${proj.title}'")
            }.onFailure { err ->
                showBanner(err.message ?: "Could not create project", isError = true)
            }
            _isWorking.value = false
        }
    }

    fun deleteProject(projectId: String) {
        viewModelScope.launch {
            repository.projectRepository.deleteEntireProject(projectId)
            val remaining = repository.dao.getAllProjectsList()
            if (remaining.isEmpty()) {
                val created = repository.projectRepository.ensureInitialProjectExists(
                    activeAccount.value?.pubkeyHex.orEmpty()
                )
                _activeProjectId.value = created.projectId
                _selectedFilePath.value = created.entryPath
            } else if (_activeProjectId.value == projectId) {
                _activeProjectId.value = remaining.first().projectId
                _selectedFilePath.value = remaining.first().entryPath
            }
            showBanner("Deleted local project")
        }
    }

    fun importHtmlUri(uri: Uri) {
        viewModelScope.launch {
            _isWorking.value = true
            runCatching {
                val app = getApplication<Application>()
                val displayName = queryUriDisplayName(uri).ifBlank { "index.html" }
                val bytes = app.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: error("Unable to read selected HTML file")
                repository.projectRepository.importSingleHtmlFile(
                    ownerPubkeyHex = activeAccount.value?.pubkeyHex.orEmpty(),
                    titleHint = displayName,
                    originalFileName = displayName,
                    htmlBytes = bytes
                )
            }.onSuccess { proj ->
                _activeProjectId.value = proj.projectId
                _selectedFilePath.value = proj.entryPath
                showBanner("Imported '${proj.title}' from HTML file")
            }.onFailure { err ->
                showBanner(err.message ?: "Failed to import HTML file", isError = true)
            }
            _isWorking.value = false
        }
    }

    fun importZipUri(uri: Uri) {
        viewModelScope.launch {
            _isWorking.value = true
            runCatching {
                val app = getApplication<Application>()
                val displayName = queryUriDisplayName(uri).ifBlank { "site.zip" }
                val stream = app.contentResolver.openInputStream(uri)
                    ?: error("Unable to open selected ZIP archive")
                stream.use { zis ->
                    repository.projectRepository.importZipArchive(
                        ownerPubkeyHex = activeAccount.value?.pubkeyHex.orEmpty(),
                        titleHint = displayName,
                        zipInputStream = zis
                    )
                }
            }.onSuccess { proj ->
                _activeProjectId.value = proj.projectId
                _selectedFilePath.value = proj.entryPath
                showBanner("Imported multi-file ZIP '${proj.title}'")
            }.onFailure { err ->
                showBanner(err.message ?: "Failed to import ZIP archive", isError = true)
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
            }.onSuccess { proj ->
                _activeProjectId.value = proj.projectId
                _selectedFilePath.value = proj.entryPath
                showBanner("Imported folder into project '${proj.title}'")
            }.onFailure { err ->
                showBanner(err.message ?: "Failed to import folder", isError = true)
            }
            _isWorking.value = false
        }
    }

    fun forkAppletToStudio(applet: InstalledAppletEntity, specificVersion: NsiteVersionEntity? = null) {
        viewModelScope.launch {
            _isWorking.value = true
            runCatching {
                repository.forkSiteIntoProject(applet, specificVersion)
            }.onSuccess { outcome ->
                _activeProjectId.value = outcome.project.projectId
                _selectedFilePath.value = outcome.project.entryPath
                _latestDeployment.value = null
                _latestPublishVerification.value = null
                val dest = ShellDestination.CreatorStudio()
                navStack.addLast(dest)
                _currentDestination.value = dest
                if (outcome.isComplete) {
                    showBanner("Forked '${applet.title}' (${outcome.verifiedPaths.size} verified files)")
                } else {
                    showBanner(
                        "Forked '${applet.title}' with ${outcome.missingOrCorruptPaths.size} unverified/missing file(s)",
                        isError = true
                    )
                }
            }.onFailure { err ->
                showBanner(err.message ?: "Failed to fork Nsite", isError = true)
            }
            _isWorking.value = false
        }
    }

    fun forkAddressToStudio(rawAddress: String, onDone: () -> Unit = {}) {
        val trimmed = rawAddress.trim()
        if (trimmed.isBlank()) {
            showBanner("Enter an nsite.lol URL, naddr1…, or coordinate to fork", isError = true)
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
                if (_currentDestination.value !is ShellDestination.CreatorStudio) {
                    val dest = ShellDestination.CreatorStudio()
                    navStack.addLast(dest)
                    _currentDestination.value = dest
                }
                if (outcome.isComplete) {
                    showBanner("Forked ${outcome.project.title} (${outcome.verifiedPaths.size} verified files)")
                } else {
                    showBanner(
                        "Fork imported with ${outcome.missingOrCorruptPaths.size} missing/corrupt file(s)",
                        isError = true
                    )
                }
            }.onFailure { err ->
                showBanner(err.message ?: "Could not fork address", isError = true)
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
        val projId = activeProject.value?.projectId ?: return
        viewModelScope.launch {
            repository.projectRepository.updateProjectMetadata(
                projectId = projId,
                title = title,
                dTag = dTag,
                targetKind = targetKind,
                description = description,
                entryPath = entryPath
            )
        }
    }

    fun saveProjectTextFile(relativePath: String, content: String, showConfirmation: Boolean = false) {
        val projId = activeProject.value?.projectId ?: return
        viewModelScope.launch {
            val saved = repository.projectRepository.saveTextFile(projId, relativePath, content)
            _selectedFilePath.value = saved.relativePath
            if (showConfirmation) {
                showBanner("Saved ${saved.relativePath} (sha:${saved.sha256.take(8)}…)")
            }
        }
    }

    fun createNewProjectFile(rawPath: String, initialContent: String = "") {
        val projId = activeProject.value?.projectId ?: return
        if (rawPath.isBlank()) {
            showBanner("Provide a file path (e.g. /about.html or /js/game.js)", isError = true)
            return
        }
        viewModelScope.launch {
            val created = repository.projectRepository.saveTextFile(projId, rawPath, initialContent)
            _selectedFilePath.value = created.relativePath
            showBanner("Created ${created.relativePath}")
        }
    }

    fun importAssetIntoProject(targetDirOrPath: String, uri: Uri) {
        val projId = activeProject.value?.projectId ?: return
        viewModelScope.launch {
            _isWorking.value = true
            runCatching {
                val app = getApplication<Application>()
                val fileName = queryUriDisplayName(uri).ifBlank { "asset_${System.currentTimeMillis()}.bin" }
                val bytes = app.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: error("Could not read selected asset")
                val resolvedPath = if (targetDirOrPath.endsWith("/") || targetDirOrPath.isBlank()) {
                    "${targetDirOrPath.trimEnd('/')}/$fileName"
                } else {
                    targetDirOrPath
                }
                repository.projectRepository.importOrReplaceAsset(projId, resolvedPath, bytes)
            }.onSuccess { entity ->
                _selectedFilePath.value = entity.relativePath
                showBanner("Imported ${entity.relativePath} (${entity.sizeBytes} bytes)")
            }.onFailure { err ->
                showBanner(err.message ?: "Failed to import asset", isError = true)
            }
            _isWorking.value = false
        }
    }

    fun renameProjectFile(oldPath: String, newPath: String) {
        val projId = activeProject.value?.projectId ?: return
        viewModelScope.launch {
            val renamed = repository.projectRepository.renameProjectFile(projId, oldPath, newPath)
            if (renamed != null) {
                _selectedFilePath.value = renamed.relativePath
                showBanner("Renamed to ${renamed.relativePath}")
            }
        }
    }

    fun deleteProjectFile(relativePath: String) {
        val projId = activeProject.value?.projectId ?: return
        viewModelScope.launch {
            repository.projectRepository.deleteProjectFile(projId, relativePath)
            val remaining = repository.dao.getProjectFilesList(projId)
            if (_selectedFilePath.value == relativePath) {
                _selectedFilePath.value = remaining.firstOrNull()?.relativePath ?: "/index.html"
            }
            showBanner("Removed $relativePath")
        }
    }

    fun publishActiveProject() {
        val proj = activeProject.value
        if (proj == null) {
            showBanner("Create or select a project first", isError = true)
            return
        }
        val account = activeAccount.value
        if (account == null || !account.canSign) {
            showBanner("Switch to a signing Nostr identity to publish", isError = true)
            return
        }
        viewModelScope.launch {
            _isWorking.value = true
            runCatching {
                repository.projectRepository.publishProjectAsNip5a(proj.projectId, account)
            }.onSuccess { (report, verification) ->
                _latestDeployment.value = report
                _latestPublishVerification.value = verification
                showBanner(
                    "Published '${report.title}' (${verification.verifiedUploadedBlobs} verified blobs • ${verification.acceptedRelays} relays)"
                )
            }.onFailure { err ->
                showBanner(err.message ?: "Publication failed", isError = true)
            }
            _isWorking.value = false
        }
    }

    private fun queryUriDisplayName(uri: Uri): String {
        val app = getApplication<Application>()
        return runCatching {
            app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0).orEmpty() else ""
            }.orEmpty()
        }.getOrDefault("").ifBlank {
            uri.lastPathSegment?.substringAfterLast('/') ?: ""
        }
    }

    // --- Launcher Controls ---

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
                if (switchedToFeed) "Switched to Live Relay Stream"
                else "Switched to Curated Nsite Gallery"
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

    fun addBlossomServer(url: String, asPrimary: Boolean = false, onClear: () -> Unit = {}) {
        viewModelScope.launch {
            runCatching { repository.addBlossomServerUrl(url, asPrimary) }
                .onSuccess { clean ->
                    onClear()
                    showBanner(
                        if (asPrimary) "Set $clean as primary Blossom server"
                        else "Added $clean to Blossom mirror pool"
                    )
                }
                .onFailure { err ->
                    showBanner(err.message ?: "Invalid Blossom server URL", isError = true)
                }
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
                .onSuccess { showBanner("Probed NIP-11 relay capabilities & Blossom BUD-01/02 health") }
                .onFailure { showBanner(it.message ?: "Network probe error", isError = true) }
            _isWorking.value = false
        }
    }

    fun publishBlossomListKind10063() {
        viewModelScope.launch {
            _isWorking.value = true
            runCatching { repository.publishBlossomServersKind10063() }
                .onSuccess { res ->
                    showBanner("Signed & broadcast Kind 10063 Blossom list (${res.acceptedCount}/${res.receipts.size} relays)")
                }
                .onFailure { showBanner(it.message ?: "Failed to publish Blossom list", isError = true) }
            _isWorking.value = false
        }
    }

    fun publishNip65AndBlossomLists() {
        viewModelScope.launch {
            _isWorking.value = true
            runCatching {
                val r1 = repository.publishRelayListNip65()
                val r2 = repository.publishBlossomServersKind10063()
                r1 to r2
            }.onSuccess { (r1, r2) ->
                showBanner("Signed & broadcast NIP-65 Relays (${r1.acceptedCount}) + Kind 10063 Blossom (${r2.acceptedCount})")
            }.onFailure { err ->
                showBanner(err.message ?: "Failed to broadcast network config", isError = true)
            }
            _isWorking.value = false
        }
    }

    fun setOpenNsitesExternally(enabled: Boolean) {
        viewModelScope.launch {
            repository.prefsRepo.setOpenNsitesExternally(enabled)
            showBanner(
                if (enabled) "Nsites will launch in External Browser by default"
                else "Nsites will run natively in Built-in WebView"
            )
        }
    }

    // --- User Profile System (Kind 0 + Avatar Upload) ---

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

    // --- Multi-Account Signer Vault (Local Keystore + NIP-55 + NIP-46 + Read-Only npub) ---

    fun generateAccount(alias: String) {
        viewModelScope.launch {
            runCatching { repository.createNewAccount(alias) }
                .onSuccess { acc -> showBanner("Switched to new identity ${acc.npub.take(14)}…") }
                .onFailure { showBanner(it.message ?: "Could not generate key", isError = true) }
        }
    }

    fun importAccount(nsecOrHexOrNpub: String, alias: String) {
        viewModelScope.launch {
            runCatching { repository.importAccountFromKey(nsecOrHexOrNpub, alias) }
                .onSuccess { acc ->
                    showBanner("Activated ${acc.displayName} (${acc.resolvedSignerType.label})")
                }
                .onFailure { showBanner(it.message ?: "Invalid key or npub", isError = true) }
        }
    }

    fun addReadOnlyNpub(npubOrHex: String, alias: String) {
        viewModelScope.launch {
            runCatching { repository.addReadOnlyNpubAccount(npubOrHex, alias) }
                .onSuccess { acc -> showBanner("Added read-only identity ${acc.npub.take(14)}…") }
                .onFailure { showBanner(it.message ?: "Invalid npub", isError = true) }
        }
    }

    fun connectNip55Signer(alias: String = "", preferredPackage: String = "") {
        viewModelScope.launch {
            _isWorking.value = true
            runCatching { repository.connectNip55SignerAccount(preferredPackage, alias) }
                .onSuccess { acc ->
                    showBanner("Connected NIP-55 Android Signer (${acc.npub.take(14)}…)")
                }
                .onFailure { err ->
                    showBanner(err.message ?: "NIP-55 connection failed", isError = true)
                }
            _isWorking.value = false
        }
    }

    fun connectNip46Bunker(bunkerUri: String, alias: String = "", onClear: () -> Unit = {}) {
        viewModelScope.launch {
            _isWorking.value = true
            runCatching { repository.connectNip46BunkerAccount(bunkerUri, alias) }
                .onSuccess { acc ->
                    onClear()
                    showBanner("Connected NIP-46 Bunker (${acc.npub.take(14)}…)")
                }
                .onFailure { err ->
                    showBanner(err.message ?: "NIP-46 Bunker handshake failed", isError = true)
                }
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
                    showBanner("Active account uses an external signer or read-only npub", isError = true)
                } else {
                    _revealedNsec.value = exported
                }
            }
        }
    }

    fun revokePolicy(policyKey: String) {
        viewModelScope.launch {
            repository.revokePolicy(policyKey)
            showBanner("Signer permission policy revoked")
        }
    }

    // --- Relay Management ---

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

    fun publishNip65Relays() {
        viewModelScope.launch {
            _isWorking.value = true
            runCatching { repository.publishRelayListNip65() }
                .onSuccess { res ->
                    showBanner("Signed & broadcast NIP-65 relay list (${res.acceptedCount}/${res.receipts.size} relays)")
                }
                .onFailure { showBanner(it.message ?: "Failed to publish relay list", isError = true) }
            _isWorking.value = false
        }
    }

    // --- Create & Publish Nostr Notes (NIP-01 Kind 1) ---

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

    // --- Discovery, Pinning & Studio Legacy Helpers ---

    fun toggleSpamShield() {
        val next = !_spamShieldEnabled.value
        _spamShieldEnabled.value = next
        showBanner(
            if (next) "Spam Shield enabled — bot clones hidden"
            else "Spam Shield paused — showing raw relay stream"
        )
    }

    fun refreshDiscoveryCatalog(silent: Boolean = false) {
        viewModelScope.launch {
            runCatching { repository.refreshCatalogFromRelays() }
                .onSuccess {
                    if (!silent) {
                        showBanner("Syncing live NIP-5A Nsites & Kind 0 publishers…")
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
                        if (added) "Starred ${applet.title} in your NIP-51 list"
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
                        "Signed & published NIP-51 Nsite list (Kind 30003) to ${res.acceptedCount}/${res.receipts.size} relays"
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
            showBanner(if (installed) "Pinned ${applet.title} to Lobby" else "Removed ${applet.title} from Lobby")
        }
    }

    fun toggleAppletNip07(applet: InstalledAppletEntity) {
        viewModelScope.launch {
            val next = !applet.enableNip07
            repository.setAppletNip07(applet.appletId, next)
            showBanner(if (next) "Enabled window.nostr (NIP-07) for ${applet.title}" else "Disabled window.nostr for ${applet.title}")
        }
    }

    fun updateStudioDraft(transform: (StudioDraftState) -> StudioDraftState) {
        _studioDraft.update(transform)
    }

    fun applyGraphicTemplate(preset: GraphicTemplatePreset) {
        val (html, css, js) = StarterCatalog.templateDraftFor(preset)
        val proj = activeProject.value
        if (proj != null) {
            viewModelScope.launch {
                repository.projectRepository.saveTextFile(proj.projectId, "/index.html", html)
                repository.projectRepository.saveTextFile(proj.projectId, "/css/style.css", css)
                repository.projectRepository.saveTextFile(proj.projectId, "/js/app.js", js)
                showBanner("Applied '${preset.label}' template to ${proj.title}")
            }
        }
    }

    // --- Interactive Signer Broker (Supports Local Keystore + NIP-55 + NIP-46) ---

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
                require(active.canSign) { "Current identity is read-only. Switch to a signing identity." }
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
                        createdAt = createdAt,
                        kind = kind,
                        tags = tags,
                        content = content
                    )
                ).toJsonObject()
            }

            "signer.nip04Encrypt" -> {
                require(active.canSign) { "Current identity is read-only" }
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
                require(active.canSign) { "Current identity is read-only" }
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
                require(active.canSign) { "Current identity is read-only" }
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
                require(active.canSign) { "Current identity is read-only" }
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

            "relay.publish" -> {
                val evObj = payload.optJSONObject("event") ?: error("Missing signed event")
                val ev = NostrEvent.fromJson(evObj) ?: error("Invalid NostrEvent JSON")
                val summary = repository.relayPool.publishEvent(ev)
                if (ev.kind == NostrKinds.TEXT_NOTE) {
                    repository.mirrorPublishedNote(ev, summary.acceptedCount)
                }
                JSONObject().apply {
                    put("id", ev.id)
                    put("accepted", summary.acceptedCount)
                    put("relays", summary.receipts.size)
                }
            }

            "relay.query" -> {
                val filter = payload.optJSONObject("filter") ?: JSONObject().put("kinds", JSONArray().put(1))
                val remote = repository.relayPool.queryEventsOnce(filter)
                val outArr = JSONArray()
                if (remote.isNotEmpty()) {
                    remote.forEach { outArr.put(it.toJsonObject()) }
                } else {
                    notes.value.take(10).forEach { n ->
                        if (n.rawEventJson.isNotBlank()) {
                            runCatching { outArr.put(JSONObject(n.rawEventJson)) }
                        }
                    }
                }
                outArr
            }

            "storage.get" -> repository.napStorageGet(applet.appletId, payload.optString("key"))
            "storage.set" -> {
                repository.napStorageSet(
                    applet.appletId,
                    payload.optString("key"),
                    payload.optString("value")
                )
                true
            }
            "storage.remove" -> {
                repository.napStorageRemove(applet.appletId, payload.optString("key"))
                true
            }

            else -> error("Unsupported bridge method: $type")
        }
    }

    suspend fun previewStudioBridgeCall(
        protocolLabel: String,
        type: String,
        payload: JSONObject
    ): Any? {
        val proj = activeProject.value
        val active = activeAccount.value
        val syntheticApplet = InstalledAppletEntity(
            appletId = "studio:preview:${proj?.projectId ?: "draft"}",
            kind = proj?.targetKind ?: NostrKinds.NSITE_NAMED,
            authorPubkey = active?.pubkeyHex.orEmpty(),
            authorNpub = active?.npub.orEmpty(),
            dTag = proj?.dTag ?: "draft",
            title = "${proj?.title ?: "Studio Project"} (Preview)",
            description = proj?.description.orEmpty(),
            iconGlyph = "🌐",
            accentHex = "#FF1493",
            isNsite = true,
            enableNip07 = true
        )
        return handleAppletBridgeCall(syntheticApplet, protocolLabel, type, payload)
    }

    class Factory(private val application: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ShellViewModel(application) as T
        }
    }
}
