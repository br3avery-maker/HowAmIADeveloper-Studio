package com.example.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.InstalledAppletEntity
import com.example.data.NsiteVersionEntity
import com.example.data.PublisherProfileEntity
import com.example.data.ResolvedWebAsset
import com.example.data.VaultAccountEntity
import com.example.nostr.ExternalSiteLauncher
import com.example.ui.theme.BreBlack
import com.example.ui.theme.BreDeepPink
import com.example.ui.theme.BreGray100
import com.example.ui.theme.BreGray900
import com.example.ui.theme.BreLightPink
import com.example.ui.theme.BrePinkDark2
import com.example.ui.theme.BreWhite
import com.example.ui.theme.CyberMint
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.ObsidianBorder
import com.example.ui.theme.ObsidianElevated
import com.example.ui.theme.ZineBoneCream
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Full-bleed Nsite / Web App Runtime Screen modeled after the Deepink Arcade gameplay screen:
 * - Zero top developer toolbar stealing screen space or projecting raw Kind/hash text over games.
 * - Floating top-left **Signed-In Nostr Account Profile Icon** (`FAB WRAPPER / SETTINGS ID`).
 * - Tapping the floating profile icon opens the Amethyst/Ditto-style side drawer (with quick links
 *   to other saved Nsites, account switcher, and bottom-anchored settings), while long-pressing or
 *   tapping the HUD pill opens the site's Fork / Versions / Signer / Specs sheet.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AppletRuntimeScreen(
    applet: InstalledAppletEntity,
    activeAccount: VaultAccountEntity?,
    publisherProfile: PublisherProfileEntity? = null,
    versions: List<NsiteVersionEntity> = emptyList(),
    selectedVersion: NsiteVersionEntity? = null,
    onSelectVersion: (NsiteVersionEntity?) -> Unit = {},
    onForkSite: (NsiteVersionEntity?) -> Unit = {},
    onOpenShellDrawer: (() -> Unit)? = null,
    onInspectPublisher: ((String) -> Unit)? = null,
    resolveDocument: suspend (InstalledAppletEntity) -> String,
    resolveAsset: suspend (InstalledAppletEntity, String, Boolean, (String) -> String) -> ResolvedWebAsset?,
    onBridgeCall: suspend (InstalledAppletEntity, String, String, JSONObject) -> Any?,
    onToggleNip07: () -> Unit,
    onClose: () -> Unit
) {
    BackHandler(onBack = onClose)

    val context = LocalContext.current
    val effectiveApplet = remember(applet, selectedVersion) {
        if (selectedVersion == null) {
            applet
        } else {
            applet.copy(
                title = selectedVersion.title.ifBlank { applet.title },
                description = selectedVersion.description.ifBlank { applet.description },
                indexSha256 = selectedVersion.indexSha256.ifBlank { applet.indexSha256 },
                manifestPathsJson = selectedVersion.manifestPathsJson.ifBlank { applet.manifestPathsJson },
                blossomServer = selectedVersion.blossomServersCsv
                    .split(',')
                    .firstOrNull { it.isNotBlank() }
                    ?.ifBlank { applet.blossomServer }
                    ?: applet.blossomServer,
                updatedAt = selectedVersion.createdAtSec * 1000L,
                latestEventId = selectedVersion.eventId
            )
        }
    }

    val externalUrl = remember(effectiveApplet) { ExternalSiteLauncher.resolveExternalUrl(effectiveApplet) }
    var reloadNonce by remember { mutableIntStateOf(0) }
    var rawHtml by remember(effectiveApplet.appletId, effectiveApplet.latestEventId) {
        mutableStateOf(effectiveApplet.inlineHtml)
    }
    var htmlDoc by remember { mutableStateOf<String?>(null) }
    var showHudSheet by remember { mutableStateOf(false) }
    var showUnderTheHoodSpecs by remember { mutableStateOf(false) }

    LaunchedEffect(
        effectiveApplet.appletId,
        effectiveApplet.enableNip07,
        effectiveApplet.updatedAt,
        effectiveApplet.latestEventId,
        reloadNonce
    ) {
        val rawDoc = resolveDocument(effectiveApplet)
        rawHtml = rawDoc
        htmlDoc = injectRuntimeBootstrap(rawDoc, enableNip07 = effectiveApplet.enableNip07)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BreBlack)
            .windowInsetsPadding(WindowInsets.statusBars)
            .windowInsetsPadding(WindowInsets.navigationBars)
    ) {
        // 1. 100% Full-Bleed WebView Surface
        SandboxedAppletSurface(
            applet = effectiveApplet,
            rawHtml = rawHtml,
            assembledWebDoc = htmlDoc,
            reloadKey = reloadNonce,
            resolveAsset = { path, isMainFrame ->
                resolveAsset(effectiveApplet, path, isMainFrame) { doc ->
                    injectRuntimeBootstrap(doc, enableNip07 = effectiveApplet.enableNip07)
                }
            },
            onBridgeCall = onBridgeCall,
            onForkSite = { onForkSite(selectedVersion) },
            modifier = Modifier
                .fillMaxSize()
                .testTag("applet_runtime_webview")
        )

        // 2. Floating Top-Left Signed-In Nostr Profile Icon ("FAB WRAPPER / SETTINGS ID")
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 12.dp, top = 10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(50.dp)
                    .minimumInteractiveComponentSize()
                    .shadow(10.dp, CircleShape, ambientColor = BreDeepPink, spotColor = BreDeepPink)
                    .clip(CircleShape)
                    .background(BreBlack.copy(alpha = 0.86f))
                    .clickable {
                        if (onOpenShellDrawer != null) {
                            onOpenShellDrawer()
                        } else {
                            showHudSheet = true
                        }
                    }
                    .testTag("runtime_profile_fab"),
                contentAlignment = Alignment.Center
            ) {
                AccountAvatarThumbnail(
                    account = activeAccount,
                    sizeDp = 46,
                    contentDescription = "Open Nsite Switcher & Settings Drawer"
                )
            }

            // Compact semi-translucent Site HUD pill (Fork / Options / Exit)
            Surface(
                onClick = { showHudSheet = true },
                shape = RoundedCornerShape(999.dp),
                color = BreBlack.copy(alpha = 0.78f),
                border = BorderStroke(1.dp, BrePinkDark2.copy(alpha = 0.85f)),
                modifier = Modifier.testTag("runtime_inspect_button")
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = effectiveApplet.title,
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = ZineBoneCream,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.width(88.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.CallSplit,
                        contentDescription = "Fork this Nsite",
                        tint = BreDeepPink,
                        modifier = Modifier
                            .size(16.dp)
                            .clickable { onForkSite(selectedVersion) }
                            .testTag("runtime_fork_button")
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ExitToApp,
                        contentDescription = "Exit to Lobby",
                        tint = BreWhite,
                        modifier = Modifier
                            .size(16.dp)
                            .clickable { onClose() }
                            .testTag("runtime_close_button")
                    )
                }
            }
        }
    }

    if (showHudSheet) {
        ModalBottomSheet(
            onDismissRequest = { showHudSheet = false },
            containerColor = ObsidianElevated
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Text(
                            text = effectiveApplet.iconGlyph,
                            style = MaterialTheme.typography.headlineMedium
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = effectiveApplet.title.uppercase(),
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Black),
                                color = ZineBoneCream
                            )
                            val pubCaption = publisherProfile?.bestName
                                ?: PublisherProfileEntity.shortNpub(effectiveApplet.authorNpub)
                            Text(
                                text = "By $pubCaption",
                                style = MaterialTheme.typography.labelSmall,
                                color = BreLightPink
                            )
                        }
                    }

                    Button(
                        onClick = {
                            showHudSheet = false
                            onClose()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = BreGray900),
                        shape = RoundedCornerShape(999.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ExitToApp,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Exit to Lobby", color = BreWhite)
                    }
                }

                // Primary Action Row: Fork in Studio, Reload, Open in Browser, Open Side Drawer
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Button(
                        onClick = {
                            showHudSheet = false
                            onForkSite(selectedVersion)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = BreDeepPink),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.testTag("sheet_fork_nsite_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.CallSplit,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("FORK & EDIT IN STUDIO >>>", color = BreWhite, fontWeight = FontWeight.Bold)
                    }

                    FilterChip(
                        selected = effectiveApplet.enableNip07,
                        onClick = onToggleNip07,
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Security,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp)
                            )
                        },
                        label = {
                            Text(
                                text = if (effectiveApplet.enableNip07) "Signer Connected" else "Signer Off",
                                style = MaterialTheme.typography.labelSmall
                            )
                        },
                        modifier = Modifier.testTag("runtime_nip07_toggle")
                    )

                    OutlinedButton(
                        onClick = {
                            reloadNonce++
                            showHudSheet = false
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.testTag("runtime_reload_button")
                    ) {
                        Icon(imageVector = Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Reload")
                    }

                    if (!externalUrl.isNullOrBlank()) {
                        OutlinedButton(
                            onClick = {
                                showHudSheet = false
                                ExternalSiteLauncher.launchCustomTabOrBrowser(context, externalUrl)
                            },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.testTag("runtime_open_external_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Browser")
                        }
                    }

                    if (onOpenShellDrawer != null) {
                        OutlinedButton(
                            onClick = {
                                showHudSheet = false
                                onOpenShellDrawer()
                            },
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(imageVector = Icons.Default.Menu, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Switch Nsite / Drawer")
                        }
                    }
                }

                PublisherProfileCard(
                    profile = publisherProfile,
                    authorPubkey = effectiveApplet.authorPubkey,
                    authorNpub = effectiveApplet.authorNpub,
                    onFilterByPublisher = onInspectPublisher?.let { fn ->
                        { pk ->
                            showHudSheet = false
                            fn(pk)
                        }
                    }
                )

                if (versions.isNotEmpty()) {
                    HorizontalDivider(color = ObsidianBorder)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.History,
                            contentDescription = null,
                            tint = ZineBoneCream,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "RELEASE VERSIONS (${versions.size})",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = ZineBoneCream
                        )
                    }
                    val dateFmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US) }
                    versions.forEach { ver ->
                        val isCurrent = (selectedVersion?.eventId ?: applet.latestEventId) == ver.eventId
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = BreBlack,
                            border = BorderStroke(1.dp, if (isCurrent) BreDeepPink else ObsidianBorder),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "${dateFmt.format(Date(ver.createdAtSec * 1000L))} • ${ver.fileCount} files",
                                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                        color = if (isCurrent) BreLightPink else BreWhite
                                    )
                                    if (ver.signatureVerified) {
                                        Text(
                                            text = "Verified Nostr release",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = CyberMint
                                        )
                                    }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    if (!isCurrent) {
                                        OutlinedButton(
                                            onClick = {
                                                onSelectVersion(ver)
                                                reloadNonce++
                                            },
                                            shape = RoundedCornerShape(999.dp)
                                        ) {
                                            Text("Load", style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            showHudSheet = false
                                            onForkSite(ver)
                                        },
                                        border = BorderStroke(1.dp, BrePinkDark2),
                                        shape = RoundedCornerShape(999.dp)
                                    ) {
                                        Text("Fork", style = MaterialTheme.typography.labelSmall, color = BreLightPink)
                                    }
                                }
                            }
                        }
                    }
                }

                // Optional Under-the-Hood Protocol Specs (hidden unless explicitly toggled!)
                OutlinedButton(
                    onClick = { showUnderTheHoodSpecs = !showUnderTheHoodSpecs },
                    border = BorderStroke(1.dp, ObsidianBorder),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(imageVector = Icons.Default.Code, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (showUnderTheHoodSpecs) "Hide Under-the-Hood Protocol Specs" else "Inspect Under-the-Hood Manifest & Hashes",
                        color = BreGray100
                    )
                }

                if (showUnderTheHoodSpecs) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = BreGray900,
                        border = BorderStroke(1.dp, BrePinkDark2),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "Kind ${effectiveApplet.kind} • d:${effectiveApplet.dTag}",
                                fontFamily = JetBrainsMonoFontFamily,
                                fontSize = 11.sp,
                                color = BreLightPink
                            )
                            if (effectiveApplet.indexSha256.isNotBlank()) {
                                Text(
                                    text = "Entry SHA-256: ${effectiveApplet.indexSha256}",
                                    fontFamily = JetBrainsMonoFontFamily,
                                    fontSize = 11.sp,
                                    color = CyberMint
                                )
                            }
                            Text(
                                text = "Blossom: ${effectiveApplet.blossomServer}",
                                fontFamily = JetBrainsMonoFontFamily,
                                fontSize = 11.sp,
                                color = BreGray100
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = rawHtml.take(2000).ifBlank { "(Empty HTML)" },
                                fontFamily = JetBrainsMonoFontFamily,
                                fontSize = 10.sp,
                                lineHeight = 14.sp,
                                color = BreGray100
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

/**
 * Injects optional user-controlled `window.nostr` (NIP-07) host bridge into HTML documents
 * without ever exposing private keys to website JavaScript and preserving `<!DOCTYPE html>` at byte 0.
 */
internal fun injectRuntimeBootstrap(html: String, enableNip07: Boolean): String {
    val nip07Section = if (enableNip07) {
        """
        window.nostr = {
          getPublicKey: function() {
            return invokeNative('NIP-07 window.nostr', 'signer.getPublicKey', {});
          },
          getRelays: function() {
            return invokeNative('NIP-07 window.nostr', 'signer.getRelays', {});
          },
          signEvent: function(event) {
            return invokeNative('NIP-07 window.nostr', 'signer.signEvent', { event: event || {} });
          },
          nip04: {
            encrypt: function(pubkey, plaintext) {
              return invokeNative('NIP-07 window.nostr', 'signer.nip04Encrypt', { pubkey: String(pubkey || ''), plaintext: String(plaintext || '') });
            },
            decrypt: function(pubkey, ciphertext) {
              return invokeNative('NIP-07 window.nostr', 'signer.nip04Decrypt', { pubkey: String(pubkey || ''), ciphertext: String(ciphertext || '') });
            }
          },
          nip44: {
            encrypt: function(pubkey, plaintext) {
              return invokeNative('NIP-07 window.nostr', 'signer.nip44Encrypt', { pubkey: String(pubkey || ''), plaintext: String(plaintext || '') });
            },
            decrypt: function(pubkey, ciphertext) {
              return invokeNative('NIP-07 window.nostr', 'signer.nip44Decrypt', { pubkey: String(pubkey || ''), ciphertext: String(ciphertext || '') });
            }
          }
        };
        """.trimIndent()
    } else {
        "try { delete window.nostr; } catch(e) {}"
    }

    val bootstrapScript = """
<script>(function(){
  if (window.__sovereignRuntimeInitialized) return;
  window.__sovereignRuntimeInitialized = true;
  var pending = Object.create(null);

  window.__sovereignDeliverReply = function(reqId, ok, payload) {
    var entry = pending[reqId];
    if (!entry) return;
    delete pending[reqId];
    if (ok) entry.resolve(payload);
    else entry.reject(new Error(String(payload || 'Signer rejected')));
  };

  function invokeNative(protocol, method, params) {
    return new Promise(function(resolve, reject) {
      var reqId = 'sov_' + Date.now() + '_' + Math.random().toString(36).slice(2, 9);
      pending[reqId] = { resolve: resolve, reject: reject };
      if (window.__SovereignNativeBridge && typeof window.__SovereignNativeBridge.postRequest === 'function') {
        try {
          window.__SovereignNativeBridge.postRequest(reqId, protocol, method, JSON.stringify(params || {}));
        } catch (err) {
          delete pending[reqId];
          reject(err);
        }
      } else {
        delete pending[reqId];
        reject(new Error('Host signer bridge unavailable'));
      }
    });
  }

  $nip07Section
})();</script>
    """.trimIndent().replace("\n", "")

    val headRegex = Regex("<head(\\s[^>]*)?>", RegexOption.IGNORE_CASE)
    val htmlRegex = Regex("<html(\\s[^>]*)?>", RegexOption.IGNORE_CASE)
    val doctypeRegex = Regex("<!doctype[^>]*>", RegexOption.IGNORE_CASE)

    headRegex.find(html)?.let { m ->
        return html.replaceRange(m.range, "${m.value}$bootstrapScript")
    }
    htmlRegex.find(html)?.let { m ->
        return html.replaceRange(m.range, "${m.value}<head>$bootstrapScript</head>")
    }
    doctypeRegex.find(html)?.let { m ->
        return html.replaceRange(m.range, "${m.value}$bootstrapScript")
    }
    return "$bootstrapScript\n$html"
}
