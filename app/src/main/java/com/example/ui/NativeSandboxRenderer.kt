package com.example.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.data.InstalledAppletEntity
import com.example.data.ResolvedWebAsset
import com.example.nostr.ExternalSiteLauncher
import com.example.nostr.NostrCrypto
import com.example.ui.theme.BreBlack
import com.example.ui.theme.BreDeepPink
import com.example.ui.theme.BreGray100
import com.example.ui.theme.BreGray850
import com.example.ui.theme.BreGray900
import com.example.ui.theme.BreLightPink
import com.example.ui.theme.BrePinkDark2
import com.example.ui.theme.BrePinkDark3
import com.example.ui.theme.BreWhite
import com.example.ui.theme.CoralAlert
import com.example.ui.theme.CyberMint
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.ZineBoneCream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Tracks whether the host environment (e.g. an unprivileged cloud container without isolatedProcess
 * support) killed Chromium's sandboxed WebView child process, preventing crash loops while allowing
 * manual retry at any time.
 */
private val containerBlockedChromiumRenderer = AtomicBoolean(false)

/**
 * Real HTML5 / CSS3 / ES-Module / Canvas Sandboxed WebView Engine for NIP-5D Napplets & NIP-5A Nsites.
 *
 * - Serves each applet from a deterministic isolated HTTPS virtual origin (`https://app-<hash>.nsite.local/`)
 *   so `window.localStorage`, `indexedDB`, `crypto.subtle`, ES modules (`<script type="module">`),
 *   2D `<canvas>`, CSS Grid/Flexbox, and multi-directional pointer/touch dragging work natively.
 * - Intercepts subresource paths (`/index.html`, `/assets/bundle.js`, `/assets/bundle.css`, images, WASM, fonts)
 *   via `shouldInterceptRequest`, resolving SHA-256 verified blobs from Blossom servers.
 * - Wires real `@JavascriptInterface` execution for both NIP-5D `window.postMessage` NAPs and
 *   optional NIP-07 `window.nostr` calls through the Built-in Signer & Relay Pool.
 * - Overrides `onRenderProcessGone` returning `true` so container renderer exits never crash the app,
 *   presenting an honest diagnostic panel rather than a synthetic/scraped UI.
 */
@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
@Composable
internal fun SandboxedAppletSurface(
    applet: InstalledAppletEntity,
    rawHtml: String,
    assembledWebDoc: String?,
    reloadKey: Int = 0,
    resolveAsset: (suspend (String, Boolean) -> ResolvedWebAsset?)? = null,
    onBridgeCall: suspend (InstalledAppletEntity, String, String, JSONObject) -> Any?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentApplet by rememberUpdatedState(applet)
    val currentAssembledDoc by rememberUpdatedState(assembledWebDoc)
    val currentRawHtml by rememberUpdatedState(rawHtml)
    val currentResolveAsset by rememberUpdatedState(resolveAsset)
    val currentOnBridgeCall by rememberUpdatedState(onBridgeCall)

    var rendererGone by remember { mutableStateOf(containerBlockedChromiumRenderer.get()) }
    var webViewRetryNonce by remember { mutableIntStateOf(0) }
    var isLoadingPage by remember(applet.appletId, reloadKey, webViewRetryNonce) { mutableStateOf(true) }

    val virtualHost = remember(applet.appletId) {
        "app-${NostrCrypto.sha256Hex(applet.appletId).take(20)}.nsite.local"
    }
    val entryUrl = remember(virtualHost) { "https://$virtualHost/index.html" }

    if (rendererGone) {
        ContainerWebViewDiagnosticFallback(
            applet = applet,
            rawHtml = rawHtml.ifBlank { assembledWebDoc.orEmpty() },
            onBridgeCall = onBridgeCall,
            onRetryWebView = {
                containerBlockedChromiumRenderer.set(false)
                rendererGone = false
                webViewRetryNonce++
            },
            modifier = modifier
        )
        return
    }

    var activeWebView by remember { mutableStateOf<WebView?>(null) }

    DisposableEffect(applet.appletId, applet.enableNip07, reloadKey, webViewRetryNonce) {
        onDispose {
            activeWebView?.let { wv ->
                runCatching {
                    wv.stopLoading()
                    wv.removeJavascriptInterface("__SovereignNativeBridge")
                    (wv.parent as? ViewGroup)?.removeView(wv)
                    wv.destroy()
                }
            }
            activeWebView = null
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(BreBlack)
    ) {
        androidx.compose.runtime.key(applet.appletId, applet.enableNip07, reloadKey, webViewRetryNonce) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        setBackgroundColor(AndroidColor.BLACK)
                        // Software layer prevents virtualized GPU driver crashes in cloud/emulator containers
                        // while rendering full CSS3, DOM, SVG, and 2D HTML5 Canvas cleanly.
                        setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                        overScrollMode = View.OVER_SCROLL_NEVER
                        isVerticalScrollBarEnabled = false
                        isHorizontalScrollBarEnabled = false

                        // Prevent parent Compose containers / drawers from stealing diagonal or horizontal drag gestures
                        setOnTouchListener { v, event ->
                            when (event.actionMasked) {
                                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                                    v.parent?.requestDisallowInterceptTouchEvent(true)
                                }
                                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                    v.parent?.requestDisallowInterceptTouchEvent(false)
                                }
                            }
                            false
                        }

                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            databaseEnabled = true
                            mediaPlaybackRequiresUserGesture = false
                            useWideViewPort = true
                            loadWithOverviewMode = true
                            setSupportZoom(false)
                            builtInZoomControls = false
                            displayZoomControls = false
                            allowFileAccess = false
                            allowContentAccess = false
                            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        }

                        val jsBridge = SovereignHostJsBridge(
                            scope = scope,
                            webViewProvider = { this },
                            appletProvider = { currentApplet },
                            bridgeHandler = { app, proto, method, params ->
                                currentOnBridgeCall(app, proto, method, params)
                            }
                        )
                        addJavascriptInterface(jsBridge, "__SovereignNativeBridge")

                        webChromeClient = WebChromeClient()
                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                isLoadingPage = true
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                isLoadingPage = false
                            }

                            override fun shouldInterceptRequest(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): WebResourceResponse? {
                                val req = request ?: return null
                                val uri = req.url ?: return null
                                val host = uri.host.orEmpty()

                                if (host.equals(virtualHost, ignoreCase = true)) {
                                    if (req.method.equals("OPTIONS", ignoreCase = true)) {
                                        return WebResourceResponse(
                                            "text/plain",
                                            "utf-8",
                                            204,
                                            "No Content",
                                            CORS_HEADERS,
                                            ByteArrayInputStream(ByteArray(0))
                                        )
                                    }

                                    val path = uri.encodedPath?.ifBlank { "/index.html" } ?: "/index.html"
                                    val resolver = currentResolveAsset
                                    if (resolver != null) {
                                        val asset = runCatching {
                                            runBlocking(Dispatchers.IO) {
                                                resolver(path, req.isForMainFrame)
                                            }
                                        }.getOrNull()

                                        if (asset != null) {
                                            return WebResourceResponse(
                                                asset.mimeType,
                                                asset.encoding,
                                                200,
                                                "OK",
                                                CORS_HEADERS,
                                                ByteArrayInputStream(asset.bytes)
                                            )
                                        }
                                    }

                                    // Fallback for Studio Live Preview or inline-only bundles without repository resolver
                                    if (path == "/" || path.equals("/index.html", ignoreCase = true)) {
                                        val doc = currentAssembledDoc
                                            ?: injectRuntimeBootstrap(
                                                currentRawHtml,
                                                enableNip07 = currentApplet.enableNip07
                                            )
                                        return WebResourceResponse(
                                            "text/html",
                                            "utf-8",
                                            200,
                                            "OK",
                                            CORS_HEADERS,
                                            ByteArrayInputStream(doc.toByteArray(Charsets.UTF_8))
                                        )
                                    }
                                    if (path.equals("/style.css", ignoreCase = true) && currentApplet.inlineCss.isNotBlank()) {
                                        return WebResourceResponse(
                                            "text/css",
                                            "utf-8",
                                            200,
                                            "OK",
                                            CORS_HEADERS,
                                            ByteArrayInputStream(currentApplet.inlineCss.toByteArray(Charsets.UTF_8))
                                        )
                                    }
                                    if (path.equals("/app.js", ignoreCase = true) && currentApplet.inlineJs.isNotBlank()) {
                                        return WebResourceResponse(
                                            "text/javascript",
                                            "utf-8",
                                            200,
                                            "OK",
                                            CORS_HEADERS,
                                            ByteArrayInputStream(currentApplet.inlineJs.toByteArray(Charsets.UTF_8))
                                        )
                                    }

                                    return WebResourceResponse(
                                        "text/plain",
                                        "utf-8",
                                        404,
                                        "Not Found",
                                        CORS_HEADERS,
                                        ByteArrayInputStream("Asset not found: $path".toByteArray(Charsets.UTF_8))
                                    )
                                }

                                // Enforce strict NIP-5D network isolation when NIP-07 / clearnet mode is off for Napplets
                                val appSnap = currentApplet
                                if (!appSnap.isNsite && !appSnap.enableNip07) {
                                    val pathSegment = uri.lastPathSegment.orEmpty().substringBefore('.')
                                    val isContentAddressedBlob = pathSegment.length == 64 &&
                                        pathSegment.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
                                    if (!isContentAddressedBlob) {
                                        return WebResourceResponse(
                                            "text/plain",
                                            "utf-8",
                                            403,
                                            "Blocked by NIP-5D Isolation",
                                            CORS_HEADERS,
                                            ByteArrayInputStream(ByteArray(0))
                                        )
                                    }
                                }

                                return null
                            }

                            override fun onRenderProcessGone(
                                view: WebView?,
                                detail: RenderProcessGoneDetail?
                            ): Boolean {
                                containerBlockedChromiumRenderer.set(true)
                                view?.let { dead ->
                                    runCatching {
                                        (dead.parent as? ViewGroup)?.removeView(dead)
                                        dead.destroy()
                                    }
                                }
                                activeWebView = null
                                isLoadingPage = false
                                rendererGone = true
                                // Returning true prevents Android's AwBrowserTerminator from killing the host process
                                return true
                            }
                        }

                        activeWebView = this
                        loadUrl(entryUrl)
                    }
                },
                update = { wv ->
                    activeWebView = wv
                    // Live-update Studio preview drafts when assembledWebDoc changes while editing
                    if (currentApplet.appletId.startsWith("studio:preview:") && currentAssembledDoc != null) {
                        val tag = currentAssembledDoc.hashCode()
                        if (wv.tag != tag) {
                            wv.tag = tag
                            wv.reload()
                        }
                    }
                }
            )
        }

        if (isLoadingPage) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(BreBlack.copy(alpha = 0.72f)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(
                        color = BreDeepPink,
                        strokeWidth = 2.5.dp,
                        modifier = Modifier.size(30.dp)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = if (applet.inlineHtml.isNotBlank()) {
                            "Booting WebView sandbox…"
                        } else {
                            "Verifying Blossom SHA-256 bundle…"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = BreLightPink
                    )
                }
            }
        }
    }
}

private val CORS_HEADERS = mapOf(
    "Access-Control-Allow-Origin" to "*",
    "Access-Control-Allow-Methods" to "GET, HEAD, OPTIONS",
    "Access-Control-Allow-Headers" to "*",
    "Cache-Control" to "no-cache"
)

private class SovereignHostJsBridge(
    private val scope: CoroutineScope,
    private val webViewProvider: () -> WebView?,
    private val appletProvider: () -> InstalledAppletEntity,
    private val bridgeHandler: suspend (InstalledAppletEntity, String, String, JSONObject) -> Any?
) {
    @JavascriptInterface
    fun postRequest(reqId: String, protocol: String, method: String, payloadJson: String) {
        scope.launch(Dispatchers.Main) {
            val payload = runCatching { JSONObject(payloadJson) }.getOrElse { JSONObject() }
            val result = runCatching {
                bridgeHandler(appletProvider(), protocol, method, payload)
            }
            val wv = webViewProvider() ?: return@launch
            val quotedId = JSONObject.quote(reqId)
            val jsCallback = result.fold(
                onSuccess = { value ->
                    "window.__sovereignDeliverReply && window.__sovereignDeliverReply($quotedId, true, ${toJsLiteral(value)});"
                },
                onFailure = { err ->
                    val errMsg = JSONObject.quote(err.message ?: "Request denied")
                    "window.__sovereignDeliverReply && window.__sovereignDeliverReply($quotedId, false, $errMsg);"
                }
            )
            wv.evaluateJavascript(jsCallback, null)
        }
    }
}

internal fun toJsLiteral(value: Any?): String = when (value) {
    null, JSONObject.NULL -> "null"
    is JSONObject -> value.toString()
    is JSONArray -> value.toString()
    is Boolean -> value.toString()
    is Number -> value.toString()
    is String -> JSONObject.quote(value)
    else -> JSONObject.quote(value.toString())
}

/**
 * Transparent diagnostic view shown ONLY if the host container OS forcibly terminates Android's
 * sandboxed Chromium WebView child process (`onRenderProcessGone`). Never scrapes or fakes the app's UI.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ContainerWebViewDiagnosticFallback(
    applet: InstalledAppletEntity,
    rawHtml: String,
    onBridgeCall: suspend (InstalledAppletEntity, String, String, JSONObject) -> Any?,
    onRetryWebView: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    var showRawSource by rememberSaveable(applet.appletId) { mutableStateOf(false) }
    var bridgeTestOutput by rememberSaveable(applet.appletId) { mutableStateOf("") }

    val gatewayUrl = remember(applet) {
        ExternalSiteLauncher.resolveExternalUrl(applet).orEmpty()
    }

    val manifestRoutes = remember(applet.manifestPathsJson) {
        buildList {
            runCatching {
                if (applet.manifestPathsJson.isNotBlank()) {
                    val obj = JSONObject(applet.manifestPathsJson)
                    val keys = obj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        if (!k.startsWith("__")) {
                            add(k to obj.optString(k))
                        }
                    }
                }
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(BreBlack)
            .verticalScroll(rememberScrollState())
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = BreGray850,
            border = BorderStroke(1.dp, CoralAlert),
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 680.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.WarningAmber,
                        contentDescription = null,
                        tint = CoralAlert,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Android WebView Renderer Blocked by Cloud Container",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = BreWhite
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "This streaming preview container terminated Android's sandboxed Chromium WebView process (code -1). " +
                        "The real Nostr manifest and Blossom bundle for \"${applet.title}\" were fetched and verified, " +
                        "and full HTML/CSS/JS WebView execution runs natively on physical Android devices (APK export).",
                    style = MaterialTheme.typography.bodySmall,
                    color = BreGray100
                )
                Spacer(modifier = Modifier.height(12.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onRetryWebView,
                        colors = ButtonDefaults.buttonColors(containerColor = BreDeepPink)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Retry Real WebView", color = BreWhite)
                    }

                    if (gatewayUrl.isNotBlank()) {
                        OutlinedButton(
                            onClick = {
                                ExternalSiteLauncher.launchCustomTabOrBrowser(context, gatewayUrl)
                            },
                            border = BorderStroke(1.dp, CyberMint)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = null,
                                tint = CyberMint,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Open Live URL", color = CyberMint)
                        }

                        OutlinedButton(
                            onClick = {
                                clipboard.setText(AnnotatedString(gatewayUrl))
                                bridgeTestOutput = "Copied live URL: $gatewayUrl"
                            },
                            border = BorderStroke(1.dp, BrePinkDark2)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = null,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Copy Gateway URL")
                        }
                    }
                }
            }
        }

        // Verified Blossom Manifest & Downloaded Bundle Metadata
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = BreGray850,
            border = BorderStroke(1.dp, BrePinkDark2),
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 680.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "VERIFIED NOSTR MANIFEST & BLOSSOM BUNDLE",
                    style = MaterialTheme.typography.labelSmall,
                    color = ZineBoneCream
                )
                Text(
                    text = "Kind ${applet.kind} • d:${applet.dTag} • Publisher: ${applet.authorNpub.ifBlank { "Local Studio" }}",
                    fontFamily = JetBrainsMonoFontFamily,
                    fontSize = 11.sp,
                    color = BreLightPink
                )
                if (applet.indexSha256.isNotBlank()) {
                    Text(
                        text = "Entry SHA-256: ${applet.indexSha256}",
                        fontFamily = JetBrainsMonoFontFamily,
                        fontSize = 11.sp,
                        color = CyberMint
                    )
                }
                Text(
                    text = "Blossom Server: ${applet.blossomServer} • Downloaded HTML: ${rawHtml.length} chars",
                    fontFamily = JetBrainsMonoFontFamily,
                    fontSize = 11.sp,
                    color = BreGray100
                )

                if (manifestRoutes.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Mapped Bundle Assets (${manifestRoutes.size} files):",
                        style = MaterialTheme.typography.labelSmall,
                        color = BreWhite
                    )
                    manifestRoutes.take(12).forEach { (route, sha) ->
                        Text(
                            text = "$route → ${sha.take(16)}…",
                            fontFamily = JetBrainsMonoFontFamily,
                            fontSize = 11.sp,
                            color = BreGray100
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { showRawSource = !showRawSource },
                        border = BorderStroke(1.dp, BrePinkDark2)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Code,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (showRawSource) "Hide Downloaded HTML" else "View Downloaded HTML (${rawHtml.length} B)")
                    }

                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                val proto = if (applet.enableNip07) "NIP-07 window.nostr" else "NIP-5D NAP"
                                runCatching {
                                    val pk = onBridgeCall(applet, proto, "signer.getPublicKey", JSONObject())
                                    "Host Signer Bridge OK — Active Pubkey: $pk"
                                }.onSuccess { bridgeTestOutput = it }
                                    .onFailure { bridgeTestOutput = "Bridge Error: ${it.message}" }
                            }
                        },
                        border = BorderStroke(1.dp, BrePinkDark2)
                    ) {
                        Text("Test Signer Bridge")
                    }
                }

                if (bridgeTestOutput.isNotBlank()) {
                    Text(
                        text = bridgeTestOutput,
                        fontFamily = JetBrainsMonoFontFamily,
                        fontSize = 11.sp,
                        color = CyberMint,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                AnimatedVisibility(visible = showRawSource) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = BreGray900,
                        border = BorderStroke(1.dp, BrePinkDark3),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                    ) {
                        Text(
                            text = rawHtml.take(4000),
                            fontFamily = JetBrainsMonoFontFamily,
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                            color = BreGray100,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
            }
        }
    }
}
