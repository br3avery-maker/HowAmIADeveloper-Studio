package com.example.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import com.example.data.PublisherProfileEntity
import com.example.data.ResolvedWebAsset
import com.example.data.VaultAccountEntity
import com.example.nostr.ExternalSiteLauncher
import com.example.ui.theme.BreBlack
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppletRuntimeScreen(
    applet: InstalledAppletEntity,
    activeAccount: VaultAccountEntity?,
    publisherProfile: PublisherProfileEntity? = null,
    onInspectPublisher: ((String) -> Unit)? = null,
    resolveDocument: suspend (InstalledAppletEntity) -> String,
    resolveAsset: suspend (InstalledAppletEntity, String, Boolean, (String) -> String) -> ResolvedWebAsset?,
    onBridgeCall: suspend (InstalledAppletEntity, String, String, JSONObject) -> Any?,
    onToggleNip07: () -> Unit,
    onClose: () -> Unit
) {
    BackHandler(onBack = onClose)

    val context = LocalContext.current
    val externalUrl = remember(applet) { ExternalSiteLauncher.resolveExternalUrl(applet) }
    var reloadNonce by remember { mutableIntStateOf(0) }
    var rawHtml by remember(applet.appletId) { mutableStateOf(applet.inlineHtml) }
    var htmlDoc by remember { mutableStateOf<String?>(null) }
    var showBundleSheet by remember { mutableStateOf(false) }

    LaunchedEffect(applet.appletId, applet.enableNip07, applet.updatedAt, reloadNonce) {
        val rawDoc = resolveDocument(applet)
        rawHtml = rawDoc
        htmlDoc = injectRuntimeBootstrap(rawDoc, enableNip07 = applet.enableNip07)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BreBlack)
            .windowInsetsPadding(WindowInsets.statusBars)
            .windowInsetsPadding(WindowInsets.navigationBars)
    ) {
        // Compact Shell Control Header
        Surface(
            color = ObsidianElevated,
            border = BorderStroke(1.dp, ObsidianBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(
                    text = applet.iconGlyph,
                    style = MaterialTheme.typography.titleLarge
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = applet.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val pubCaption = publisherProfile?.bestName ?: PublisherProfileEntity.shortNpub(applet.authorNpub)
                    Text(
                        text = buildString {
                            if (pubCaption.isNotBlank()) append("By $pubCaption • ")
                            append(activeAccount?.npub?.let { "Signer: ${it.take(10)}…" } ?: "Isolated Sandbox")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = CyberMint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                FilterChip(
                    selected = applet.enableNip07,
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
                            text = if (applet.enableNip07) "NIP-07 ON" else "NIP-5D ONLY",
                            style = MaterialTheme.typography.labelSmall
                        )
                    },
                    modifier = Modifier.testTag("runtime_nip07_toggle")
                )

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
                            contentDescription = "Open in External Custom Tab",
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
                        imageVector = Icons.Default.Code,
                        contentDescription = "Inspect bundle manifest",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                IconButton(
                    onClick = { reloadNonce++ },
                    modifier = Modifier.minimumInteractiveComponentSize()
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Reload applet",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
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
                        contentDescription = "Close applet",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        // Full-bleed WebView surface — zero synthetic wrapper DOM
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            SandboxedAppletSurface(
                applet = applet,
                rawHtml = rawHtml,
                assembledWebDoc = htmlDoc,
                reloadKey = reloadNonce,
                resolveAsset = { path, isMainFrame ->
                    resolveAsset(applet, path, isMainFrame) { doc ->
                        injectRuntimeBootstrap(doc, enableNip07 = applet.enableNip07)
                    }
                },
                onBridgeCall = onBridgeCall,
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
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Verified Manifest & Blossom Bundle",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = BreWhite
                )
                PublisherProfileCard(
                    profile = publisherProfile,
                    authorPubkey = applet.authorPubkey,
                    authorNpub = applet.authorNpub,
                    onFilterByPublisher = onInspectPublisher?.let { fn ->
                        { pk ->
                            showBundleSheet = false
                            fn(pk)
                        }
                    }
                )
                Text(
                    text = "Kind ${applet.kind} • d:${applet.dTag} • ${if (applet.isNsite) "NIP-5A Nsite" else "NIP-5D Napplet"}",
                    fontFamily = JetBrainsMonoFontFamily,
                    fontSize = 11.sp,
                    color = BreLightPink
                )
                if (applet.indexSha256.isNotBlank()) {
                    Text(
                        text = "Index SHA-256: ${applet.indexSha256}",
                        fontFamily = JetBrainsMonoFontFamily,
                        fontSize = 11.sp,
                        color = CyberMint
                    )
                }
                Text(
                    text = "Blossom Server: ${applet.blossomServer}",
                    fontFamily = JetBrainsMonoFontFamily,
                    fontSize = 11.sp,
                    color = BreGray100
                )
                Spacer(modifier = Modifier.height(4.dp))
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
 * Injects the real host `@JavascriptInterface` bridge for NIP-5D `window.postMessage` NAPs
 * (`signer.*`, `relay.*`, `storage.*`, `intent.*`) and optional `window.nostr` (NIP-07),
 * preserving `<!DOCTYPE html>` at byte 0 so CSS Standards Mode (`100vh`, Grid, Flexbox) never breaks.
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
    if (entry.isNap) {
      window.postMessage(
        ok ? { id: reqId, reply: true, result: payload }
           : { id: reqId, reply: true, error: String(payload || 'Signer rejected') },
        '*'
      );
    } else {
      if (ok) entry.resolve(payload);
      else entry.reject(new Error(String(payload || 'Signer rejected')));
    }
  };

  function invokeNative(protocol, method, params, existingId, isNap) {
    return new Promise(function(resolve, reject) {
      var reqId = existingId || ('sov_' + Date.now() + '_' + Math.random().toString(36).slice(2, 9));
      pending[reqId] = { resolve: resolve, reject: reject, isNap: !!isNap };
      if (window.__SovereignNativeBridge && typeof window.__SovereignNativeBridge.postRequest === 'function') {
        try {
          window.__SovereignNativeBridge.postRequest(reqId, protocol, method, JSON.stringify(params || {}));
        } catch (err) {
          delete pending[reqId];
          if (isNap) window.postMessage({ id: reqId, reply: true, error: String(err) }, '*');
          else reject(err);
        }
      } else {
        delete pending[reqId];
        var msg = 'Host signer bridge unavailable';
        if (isNap) window.postMessage({ id: reqId, reply: true, error: msg }, '*');
        else reject(new Error(msg));
      }
    });
  }

  window.addEventListener('message', function(ev) {
    var data = ev.data;
    if (typeof data === 'string') {
      try { data = JSON.parse(data); } catch (e) { return; }
    }
    if (!data || typeof data !== 'object' || data.reply === true) return;
    var type = typeof data.type === 'string' ? data.type : '';
    if (!type) return;
    if (
      type.indexOf('signer.') === 0 ||
      type.indexOf('relay.') === 0 ||
      type.indexOf('storage.') === 0 ||
      type.indexOf('intent.') === 0
    ) {
      var reqId = String(data.id || ('nap_' + Date.now()));
      invokeNative('NIP-5D NAP', type, data, reqId, true);
    }
  });

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
