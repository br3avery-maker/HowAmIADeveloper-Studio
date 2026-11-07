package com.example.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.History
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
    var showBundleSheet by remember { mutableStateOf(false) }

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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BreBlack)
            .windowInsetsPadding(WindowInsets.statusBars)
            .windowInsetsPadding(WindowInsets.navigationBars)
    ) {
        // SovereignShell Runtime Control Bar
        Surface(
            color = ObsidianElevated,
            border = BorderStroke(1.dp, ObsidianBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = effectiveApplet.iconGlyph,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 4.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = effectiveApplet.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = BreWhite,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val pubCaption = publisherProfile?.bestName
                        ?: PublisherProfileEntity.shortNpub(effectiveApplet.authorNpub)
                    Text(
                        text = buildString {
                            if (pubCaption.isNotBlank()) append("By $pubCaption")
                            if (selectedVersion != null) append(" • Pin ${selectedVersion.eventId.take(6)}")
                            else if (versions.size > 1) append(" • ${versions.size} vers")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = BreLightPink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                FilterChip(
                    selected = effectiveApplet.enableNip07,
                    onClick = onToggleNip07,
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Security,
                            contentDescription = null,
                            modifier = Modifier.size(13.dp)
                        )
                    },
                    label = {
                        Text(
                            text = if (effectiveApplet.enableNip07) "NIP-07" else "No Signer",
                            style = MaterialTheme.typography.labelSmall
                        )
                    },
                    modifier = Modifier.testTag("runtime_nip07_toggle")
                )

                IconButton(
                    onClick = { onForkSite(selectedVersion) },
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("runtime_fork_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.CallSplit,
                        contentDescription = "Fork this Nsite in Studio",
                        tint = BreDeepPink
                    )
                }

                if (!externalUrl.isNullOrBlank()) {
                    IconButton(
                        onClick = {
                            ExternalSiteLauncher.launchCustomTabOrBrowser(context, externalUrl)
                        },
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("runtime_open_external_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                            contentDescription = "Open in External Browser",
                            tint = BreLightPink
                        )
                    }
                }

                IconButton(
                    onClick = { showBundleSheet = true },
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("runtime_inspect_button")
                ) {
                    Icon(
                        imageVector = if (versions.size > 1) Icons.Default.History else Icons.Default.Code,
                        contentDescription = "Inspect versions, publisher, and NIP-5A manifest",
                        tint = BreWhite
                    )
                }

                IconButton(
                    onClick = { reloadNonce++ },
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("runtime_reload_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Reload Nsite",
                        tint = BreGray100
                    )
                }

                IconButton(
                    onClick = onClose,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("runtime_close_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close Nsite",
                        tint = BreWhite
                    )
                }
            }
        }

        // Full-bleed WebView surface executing real HTML, CSS, JS, Canvas, and relative assets
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
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
        }
    }

    if (showBundleSheet) {
        ModalBottomSheet(
            onDismissRequest = { showBundleSheet = false },
            containerColor = ObsidianElevated
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "NIP-5A Manifest, Publisher & Versions",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = BreWhite
                    )
                    Button(
                        onClick = {
                            showBundleSheet = false
                            onForkSite(selectedVersion)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = BreDeepPink),
                        shape = RoundedCornerShape(999.dp),
                        modifier = Modifier.testTag("sheet_fork_nsite_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.CallSplit,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Fork in Studio", color = BreWhite)
                    }
                }

                PublisherProfileCard(
                    profile = publisherProfile,
                    authorPubkey = effectiveApplet.authorPubkey,
                    authorNpub = effectiveApplet.authorNpub,
                    onFilterByPublisher = onInspectPublisher?.let { fn ->
                        { pk ->
                            showBundleSheet = false
                            fn(pk)
                        }
                    }
                )

                Text(
                    text = "Kind ${effectiveApplet.kind} • d:${effectiveApplet.dTag} • Signer: ${activeAccount?.displayName ?: "None"}",
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
                    text = "Primary Blossom: ${effectiveApplet.blossomServer}",
                    fontFamily = JetBrainsMonoFontFamily,
                    fontSize = 11.sp,
                    color = BreGray100
                )

                if (versions.isNotEmpty()) {
                    HorizontalDivider(color = ObsidianBorder)
                    Text(
                        text = "AVAILABLE SIGNED VERSIONS (${versions.size})",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = ZineBoneCream
                    )
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
                                        text = "${dateFmt.format(Date(ver.createdAtSec * 1000L))} • ${ver.fileCount} file(s)",
                                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                        color = if (isCurrent) BreLightPink else BreWhite
                                    )
                                    Text(
                                        text = "ev:${ver.eventId.take(12)}… • sha:${ver.indexSha256.take(12)}…" +
                                            if (ver.signatureVerified) " • Schnorr ✓" else "",
                                        fontFamily = JetBrainsMonoFontFamily,
                                        fontSize = 10.sp,
                                        color = CyberMint
                                    )
                                }
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
                                            showBundleSheet = false
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

                HorizontalDivider(color = ObsidianBorder)
                Text(
                    text = "VERIFIED ENTRY HTML SOURCE",
                    style = MaterialTheme.typography.labelSmall,
                    color = ZineBoneCream
                )
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = BreGray900,
                    border = BorderStroke(1.dp, BrePinkDark2),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = rawHtml.take(3000).ifBlank { "(Empty HTML)" },
                        fontFamily = JetBrainsMonoFontFamily,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        color = BreGray100,
                        modifier = Modifier.padding(12.dp)
                    )
                }
                Spacer(modifier = Modifier.height(20.dp))
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
