package com.example.ui

import android.app.Application
import android.net.Uri
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
import com.example.data.ManagedRelayItem
import com.example.data.NoteEntity
import com.example.data.PublishDeploymentReport
import com.example.data.ShellRepository
import com.example.data.SignerAuditEntity
import com.example.data.SignerPolicyEntity
import com.example.data.StackedAppletGroup
import com.example.data.StarterCatalog
import com.example.data.VaultAccountEntity
import com.example.data.WallpaperStyle
import com.example.nostr.NostrCrypto
import com.example.nostr.NostrEvent
import com.example.nostr.NostrKinds
import com.example.nostr.PendingSignRequest
import com.example.nostr.RelayConnectionStatus
import com.example.nostr.SignDecision
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

sealed interface ShellDestination {
    data object HomeLauncher : ShellDestination
    data class DiscoveryFeed(val initialTabIsNsites: Boolean = false) : ShellDestination
    data object UserProfile : ShellDestination
    data object RelayManager : ShellDestination
    data object NotesComposer : ShellDestination
    data class CreatorStudio(val forkSource: InstalledAppletEntity? = null) : ShellDestination
    data object SelfPublishShell : ShellDestination
    data object VaultAndAudit : ShellDestination
    data object WallpaperSettings : ShellDestination
    data class RunningApplet(val appletId: String) : ShellDestination
}

data class StudioDraftState(
    val title: String = "My Sovereign Applet",
    val dTag: String = "my-napplet",
    val description: String = "Built & published on-device with SovereignShell.",
    val iconGlyph: String = "✨",
    val targetKind: Int = NostrKinds.NAPPLET_NAMED,
    val html: String = DEFAULT_STUDIO_HTML,
    val css: String = StarterCatalog.COMMON_APPLET_CSS.trimIndent(),
    val js: String = DEFAULT_STUDIO_JS
) {
    companion object {
        val DEFAULT_STUDIO_HTML = """
<div class="card">
  <h2>✨ Custom Napplet</h2>
  <p>Edit HTML, CSS, and JS in Studio, then publish blobs to Blossom &amp; Nostr relays.</p>
  <button id="helloSign">Request Signature via NAP</button>
  <div id="resultBox" class="mono" style="margin-top:10px;"></div>
</div>
        """.trimIndent()

        val DEFAULT_STUDIO_JS = """
document.getElementById('helloSign').onclick = () => {
  const id = 'st_' + Date.now();
  window.addEventListener('message', function onReply(ev) {
    let d = ev.data; if (typeof d === 'string') { try { d = JSON.parse(d); } catch(e){ return; } }
    if (d && d.id === id && d.reply) {
      window.removeEventListener('message', onReply);
      document.getElementById('resultBox').textContent = d.error
        ? ('Denied: ' + d.error)
        : ('Signed Event ID: ' + d.result.id.slice(0, 18) + '…');
    }
  });
  window.postMessage({
    id,
    type: 'signer.signEvent',
    event: { kind: 1, content: 'Hello from my self-published Napplet! #nostr #napplet', tags: [] }
  }, '*');
};
        """.trimIndent()
    }
}

data class TransientStatusBanner(
    val message: String,
    val isError: Boolean = false,
    val id: Long = System.currentTimeMillis()
)

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

    private val _studioDraft = MutableStateFlow(StudioDraftState())
    val studioDraft: StateFlow<StudioDraftState> = _studioDraft.asStateFlow()

    private val _latestDeployment = MutableStateFlow<PublishDeploymentReport?>(null)
    val latestDeployment: StateFlow<PublishDeploymentReport?> = _latestDeployment.asStateFlow()

    private val _revealedNsec = MutableStateFlow<String?>(null)
    val revealedNsec: StateFlow<String?> = _revealedNsec.asStateFlow()

    private val pendingSignDeferreds = ConcurrentHashMap<String, CompletableDeferred<SignDecision>>()
    private val _activeSignPrompt = MutableStateFlow<PendingSignRequest?>(null)
    val activeSignPrompt: StateFlow<PendingSignRequest?> = _activeSignPrompt.asStateFlow()

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

    val notes: StateFlow<List<NoteEntity>> = repository.notesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val auditLog: StateFlow<List<SignerAuditEntity>> = repository.auditLogFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val signerPolicies: StateFlow<List<SignerPolicyEntity>> = repository.signerPoliciesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val connectedRelayCount: StateFlow<Int> = combine(managedRelays) { arr ->
        arr.first().count { it.statusInfo.status == RelayConnectionStatus.CONNECTED }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    // --- Navigation ---

    fun navigateTo(destination: ShellDestination) {
        if (destination is ShellDestination.CreatorStudio && destination.forkSource != null) {
            val src = destination.forkSource
            val forkDTag = "${src.dTag}-fork".take(13)
            _studioDraft.value = StudioDraftState(
                title = "${src.title} Remix",
                dTag = forkDTag,
                description = src.description,
                iconGlyph = src.iconGlyph,
                targetKind = src.kind,
                html = src.inlineHtml.ifBlank { StudioDraftState.DEFAULT_STUDIO_HTML },
                css = src.inlineCss.ifBlank { StarterCatalog.COMMON_APPLET_CSS.trimIndent() },
                js = src.inlineJs.ifBlank { StudioDraftState.DEFAULT_STUDIO_JS }
            )
            if (src.inlineHtml.isBlank()) {
                viewModelScope.launch {
                    runCatching { repository.resolveAppletDocument(src) }
                        .onSuccess { fetchedDoc ->
                            if (fetchedDoc.isNotBlank()) {
                                _studioDraft.update { current ->
                                    if (current.dTag == forkDTag) current.copy(html = fetchedDoc) else current
                                }
                            }
                        }
                }
            }
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
                if (switchedToFeed) "Switched to Live Relay Feed"
                else "Switched to Curated App Gallery"
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
            repository.prefsRepo.setBlossomServer(url)
            showBanner("Blossom server set to ${url.trim()}")
        }
    }

    fun setOpenNsitesExternally(enabled: Boolean) {
        viewModelScope.launch {
            repository.prefsRepo.setOpenNsitesExternally(enabled)
            showBanner(
                if (enabled) "Nsites will launch in External Custom Tab sheet"
                else "Nsites will open in Built-in WebView Sandbox"
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

    // --- Multi-Account Signer Vault ---

    fun generateAccount(alias: String) {
        viewModelScope.launch {
            runCatching { repository.createNewAccount(alias) }
                .onSuccess { acc -> showBanner("Switched to new identity ${acc.npub.take(14)}…") }
                .onFailure { showBanner(it.message ?: "Could not generate key", isError = true) }
        }
    }

    fun importAccount(nsecOrHex: String, alias: String) {
        viewModelScope.launch {
            runCatching { repository.importAccountFromKey(nsecOrHex, alias) }
                .onSuccess { acc -> showBanner("Imported ${acc.npub.take(14)}… into Hardware Vault") }
                .onFailure { showBanner(it.message ?: "Invalid nsec key", isError = true) }
        }
    }

    fun switchAccount(pubkeyHex: String) {
        viewModelScope.launch {
            _revealedNsec.value = null
            repository.switchAccount(pubkeyHex)
            showBanner("Active Nostr signer switched")
        }
    }

    fun toggleRevealNsec() {
        viewModelScope.launch {
            if (_revealedNsec.value != null) {
                _revealedNsec.value = null
            } else {
                _revealedNsec.value = repository.exportActiveNsec()
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
                    showBanner("Added & connecting to $normalized")
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
        showBanner("Reconnecting to $url…")
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

    // --- Create & Publish Nostr Notes (NIP-01 Kind 1 via Built-in Signer) ---

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
            if (active == null) {
                showBanner("No active Nostr account available", isError = true)
                return@launch
            }

            val approved = if (requireInteractiveConfirm) {
                requestInteractiveSignerDecision(
                    appletId = "shell:note-composer",
                    appletTitle = "SovereignShell Note Composer",
                    protocolLabel = "Built-in Signer (NIP-01)",
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
                    "Signed Kind 1 (${summary.event.id.take(10)}…) & published to ${summary.receipts.size} relays"
                )
            }.onFailure { err ->
                showBanner(err.message ?: "Failed to sign/publish note", isError = true)
            }
            _isWorking.value = false
        }
    }

    // --- Discovery, Pinning & Studio Publishing ---

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
                        showBanner("Syncing NIP-5D Napplets, NIP-5A Nsites & NIP-51 lists…")
                    }
                }
        }
    }

    fun toggleNip51Bookmark(applet: InstalledAppletEntity) {
        viewModelScope.launch {
            runCatching { repository.toggleLocalNip51Bookmark(applet) }
                .onSuccess { added ->
                    showBanner(
                        if (added) "Starred ${applet.title} in your NIP-51 gallery list"
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
                        "Signed & published NIP-51 gallery list (Kind 30003) to ${res.acceptedCount}/${res.receipts.size} relays"
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
            showBanner(if (installed) "Pinned ${applet.title} to Home Launcher" else "Removed ${applet.title} from Home")
        }
    }

    fun toggleAppletNip07(applet: InstalledAppletEntity) {
        viewModelScope.launch {
            val next = !applet.enableNip07
            repository.setAppletNip07(applet.appletId, next)
            showBanner(if (next) "Enabled NIP-07 window.nostr for ${applet.title}" else "Switched ${applet.title} to strict NIP-5D isolation")
        }
    }

    fun updateStudioDraft(transform: (StudioDraftState) -> StudioDraftState) {
        _studioDraft.update(transform)
    }

    fun publishStudioDraft() {
        val draft = _studioDraft.value
        if (draft.title.isBlank()) {
            showBanner("Provide a title for your manifest", isError = true)
            return
        }
        viewModelScope.launch {
            _isWorking.value = true
            runCatching {
                repository.publishProjectToBlossomAndRelays(
                    title = draft.title,
                    dTagRaw = draft.dTag,
                    description = draft.description,
                    iconGlyph = draft.iconGlyph,
                    targetKind = draft.targetKind,
                    indexHtml = draft.html,
                    styleCss = draft.css,
                    appJs = draft.js
                )
            }.onSuccess { report ->
                _latestDeployment.value = report
                showBanner("Published ${report.title} (Kind ${report.kind}) & pinned to Home!")
            }.onFailure { err ->
                showBanner(err.message ?: "Publish failed", isError = true)
            }
            _isWorking.value = false
        }
    }

    fun applyGraphicTemplate(preset: GraphicTemplatePreset) {
        val (html, css, js) = StarterCatalog.templateDraftFor(preset)
        _studioDraft.update {
            it.copy(
                html = html,
                css = css,
                js = js
            )
        }
        showBanner("Loaded '${preset.label}' graphic template")
    }

    fun executeSelfPublishShell(
        asRootNsite: Boolean,
        dTag: String,
        preset: GraphicTemplatePreset = GraphicTemplatePreset.BREBRE_SPECIMEN
    ) {
        viewModelScope.launch {
            _isWorking.value = true
            runCatching {
                repository.selfPublishShellAsNsite(
                    asRootNsite = asRootNsite,
                    customDTag = dTag,
                    preset = preset
                )
            }.onSuccess { report ->
                _latestDeployment.value = report
                showBanner("Self-published SovereignShell (${preset.badge}) as Kind ${report.kind} Nsite!")
            }.onFailure { err ->
                showBanner(err.message ?: "Self-publish failed", isError = true)
            }
            _isWorking.value = false
        }
    }

    // --- Interactive Built-in Signer Broker (for NIP-5D NAPs, NIP-07 & Shell Prompts) ---

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
        val (active, _) = repository.getActivePrivateKeyHex() ?: return false
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
        val (active, privHex) = repository.getActivePrivateKeyHex()
            ?: error("No active Nostr keypair")

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
                NostrEvent.createAndSign(
                    privKeyHex = privHex,
                    kind = kind,
                    content = content,
                    tags = tags,
                    createdAt = createdAt
                ).toJsonObject()
            }

            "signer.nip04Encrypt" -> {
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
                NostrCrypto.nip04Encrypt(privHex, peerPub, plaintext)
            }

            "signer.nip04Decrypt" -> {
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
                NostrCrypto.nip04Decrypt(privHex, peerPub, ciphertext)
            }

            "signer.nip44Encrypt" -> {
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
                NostrCrypto.nip44Encrypt(privHex, peerPub, plaintext)
            }

            "signer.nip44Decrypt" -> {
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
                NostrCrypto.nip44Decrypt(privHex, peerPub, ciphertext)
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
                    // Fallback include cached local notes matching kind 1
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

            "intent.composeNote" -> {
                navigateTo(ShellDestination.NotesComposer)
                true
            }
            "intent.openRelays" -> {
                navigateTo(ShellDestination.RelayManager)
                true
            }
            "intent.openProfile" -> {
                navigateTo(ShellDestination.UserProfile)
                true
            }
            "intent.openApplet" -> {
                val target = payload.optString("appletId").ifBlank { payload.optString("dTag") }
                val match = catalogApplets.value.firstOrNull {
                    it.appletId == target || it.dTag.equals(target, ignoreCase = true)
                } ?: error("Target napplet not found: $target")
                navigateTo(ShellDestination.RunningApplet(match.appletId))
                true
            }

            else -> error("Unsupported NAP method: $type")
        }
    }

    suspend fun previewStudioBridgeCall(
        protocolLabel: String,
        type: String,
        payload: JSONObject
    ): Any? {
        val draft = _studioDraft.value
        val active = activeAccount.value
        val syntheticApplet = InstalledAppletEntity(
            appletId = "studio:preview:${draft.dTag.ifBlank { "draft" }}",
            kind = draft.targetKind,
            authorPubkey = active?.pubkeyHex.orEmpty(),
            authorNpub = active?.npub.orEmpty(),
            dTag = draft.dTag.ifBlank { "draft" },
            title = "${draft.title.ifBlank { "Studio Draft" }} (Preview)",
            description = draft.description,
            iconGlyph = draft.iconGlyph,
            accentHex = "#FF1493",
            isNsite = draft.targetKind != NostrKinds.NAPPLET_NAMED,
            enableNip07 = false
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
