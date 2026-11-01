package com.example.ui

import android.webkit.JavascriptInterface
import android.webkit.WebView
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.InstalledAppletEntity
import com.example.data.VaultAccountEntity
import com.example.ui.theme.CyberMint
import com.example.ui.theme.ObsidianBase
import com.example.ui.theme.ObsidianBorder
import com.example.ui.theme.ObsidianElevated
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
fun AppletRuntimeScreen(
    applet: InstalledAppletEntity,
    activeAccount: VaultAccountEntity?,
    resolveDocument: suspend (InstalledAppletEntity) -> String,
    onBridgeCall: suspend (InstalledAppletEntity, String, String, JSONObject) -> Any?,
    onToggleNip07: () -> Unit,
    onClose: () -> Unit
) {
    BackHandler(onBack = onClose)

    var reloadNonce by remember { mutableIntStateOf(0) }
    var rawHtml by remember(applet.appletId) { mutableStateOf(applet.inlineHtml) }
    var htmlDoc by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(applet.appletId, applet.enableNip07, applet.updatedAt, reloadNonce) {
        val rawDoc = resolveDocument(applet)
        rawHtml = applet.inlineHtml.ifBlank { rawDoc }
        htmlDoc = injectRuntimeBootstrap(rawDoc, enableNip07 = applet.enableNip07)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianBase)
            .windowInsetsPadding(WindowInsets.statusBars)
            .windowInsetsPadding(WindowInsets.navigationBars)
    ) {
        // Floating Shell Control Header
        Surface(
            color = ObsidianElevated,
            border = BorderStroke(1.dp, ObsidianBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(
                    text = applet.iconGlyph,
                    style = MaterialTheme.typography.titleLarge
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = applet.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = activeAccount?.npub?.let { "Signer: ${it.take(12)}…" } ?: "Isolated Sandbox",
                        style = MaterialTheme.typography.labelSmall,
                        color = CyberMint
                    )
                }

                FilterChip(
                    selected = applet.enableNip07,
                    onClick = onToggleNip07,
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Security,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp)
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

        if (htmlDoc == null && !WebViewCrashGuard.isRendererUnavailable()) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        Box(modifier = Modifier.weight(1f)) {
            SandboxedAppletSurface(
                applet = applet,
                rawHtml = rawHtml,
                assembledWebDoc = htmlDoc,
                reloadKey = reloadNonce,
                onBridgeCall = onBridgeCall,
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("applet_runtime_webview")
            )
        }
    }
}

internal class SovereignJsBridge(
    private val webView: WebView,
    private val applet: InstalledAppletEntity,
    private val scope: CoroutineScope,
    private val onBridgeCall: suspend (InstalledAppletEntity, String, String, JSONObject) -> Any?
) {
    @JavascriptInterface
    fun postEnvelope(rawJson: String, protocol: String) {
        scope.launch(Dispatchers.Main) {
            val req = runCatching { JSONObject(rawJson) }.getOrNull() ?: return@launch
            val id = req.optString("id")
            val type = req.optString("type")
            if (id.isBlank() || type.isBlank()) return@launch

            val replyObj = JSONObject().apply {
                put("id", id)
                put("reply", true)
            }
            runCatching {
                onBridgeCall(applet, protocol, type, req)
            }.onSuccess { res ->
                replyObj.put("result", res ?: JSONObject.NULL)
            }.onFailure { err ->
                replyObj.put("error", err.message ?: "Signer denied request")
            }

            val escapedJson = JSONObject.quote(replyObj.toString())
            webView.evaluateJavascript(
                "window.__sovereignDeliverReply($escapedJson);",
                null
            )
        }
    }
}

internal fun injectRuntimeBootstrap(html: String, enableNip07: Boolean): String {
    val nip07Script = if (enableNip07) {
        """
        window.nostr = {
          _call(type, extra) {
            return new Promise((resolve, reject) => {
              const id = 'nip07_' + Math.random().toString(36).slice(2, 10);
              window.__sovereignCallbacks[id] = { resolve, reject };
              window.__SovereignHost.postEnvelope(JSON.stringify({ id, type, ...extra }), 'NIP-07 window.nostr');
            });
          },
          getPublicKey() { return this._call('signer.getPublicKey', {}); },
          getRelays() { return this._call('signer.getRelays', {}); },
          signEvent(event) { return this._call('signer.signEvent', { event }); },
          nip04: {
            encrypt(pubkey, plaintext) { return window.nostr._call('signer.nip04Encrypt', { pubkey, plaintext }); },
            decrypt(pubkey, ciphertext) { return window.nostr._call('signer.nip04Decrypt', { pubkey, ciphertext }); }
          },
          nip44: {
            encrypt(pubkey, plaintext) { return window.nostr._call('signer.nip44Encrypt', { pubkey, plaintext }); },
            decrypt(pubkey, ciphertext) { return window.nostr._call('signer.nip44Decrypt', { pubkey, ciphertext }); }
          }
        };
        """.trimIndent()
    } else {
        "try { delete window.nostr; } catch(e) {}"
    }

    val bootstrap = """
    <script>
      (function() {
        window.__sovereignCallbacks = {};
        window.__sovereignDeliverReply = function(rawStr) {
          try {
            const msg = JSON.parse(rawStr);
            if (window.__sovereignCallbacks[msg.id]) {
              const cb = window.__sovereignCallbacks[msg.id];
              delete window.__sovereignCallbacks[msg.id];
              if (msg.error) cb.reject(msg.error); else cb.resolve(msg.result);
              return;
            }
            window.postMessage(msg, '*');
          } catch (e) {}
        };
        window.addEventListener('message', function(ev) {
          let d = ev.data;
          if (typeof d === 'string') { try { d = JSON.parse(d); } catch(e) { return; } }
          if (!d || d.reply === true || !d.id || !d.type) return;
          if (window.__SovereignHost) {
            window.__SovereignHost.postEnvelope(JSON.stringify(d), 'NIP-5D NAP');
          }
        });
        $nip07Script
      })();
    </script>
    """.trimIndent()

    return if (html.contains("<head>", ignoreCase = true)) {
        html.replaceFirst(Regex("<head>", RegexOption.IGNORE_CASE), "<head>$bootstrap")
    } else {
        "$bootstrap\n$html"
    }
}
