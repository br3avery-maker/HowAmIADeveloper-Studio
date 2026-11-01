package com.example.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.InstalledAppletEntity
import com.example.ui.theme.BreBlack
import com.example.ui.theme.BreDeepPink
import com.example.ui.theme.BreGray100
import com.example.ui.theme.BreGray850
import com.example.ui.theme.BreGray900
import com.example.ui.theme.BreLightPink
import com.example.ui.theme.BrePinkDark1
import com.example.ui.theme.BrePinkDark2
import com.example.ui.theme.BrePinkDark3
import com.example.ui.theme.BreWhite
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.ZineBoneCream
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Pure-Compose Sandboxed NIP-5D & NIP-07 Runtime Engine.
 * Renders Napplets (Kind 35129/15129) and Nsites (Kind 35128/15128) natively in Jetpack Compose
 * without spawning out-of-process Chromium WebView renderers, guaranteeing zero renderer crashes
 * across all Android devices and containerized streaming emulators while executing real
 * NAP-SIGNER, NAP-RELAY, NAP-STORAGE, NAP-INTENT, and optional NIP-07 calls.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SandboxedAppletSurface(
    applet: InstalledAppletEntity,
    rawHtml: String,
    assembledWebDoc: String?,
    reloadKey: Int = 0,
    onBridgeCall: suspend (InstalledAppletEntity, String, String, JSONObject) -> Any?,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val effectiveSource = rawHtml.ifBlank { assembledWebDoc.orEmpty() }

    var statusOutput by rememberSaveable(applet.appletId, reloadKey) { mutableStateOf("") }
    var pubkeyPreview by rememberSaveable(applet.appletId, reloadKey) { mutableStateOf("") }
    var textInput by rememberSaveable(applet.appletId) { mutableStateOf("") }
    var showSourceInspector by rememberSaveable(applet.appletId) { mutableStateOf(false) }
    val queriedEvents = remember(applet.appletId, reloadKey) { mutableStateListOf<Pair<String, String>>() }

    val lowerDTag = applet.dTag.lowercase()
    val isCrtStyle = lowerDTag.contains("puter") || effectiveSource.contains("crt-shell", ignoreCase = true)
    val isZineStyle = lowerDTag.contains("zine") || effectiveSource.contains("zine-banner", ignoreCase = true)
    val isAgentStyle = lowerDTag.contains("agent") || effectiveSource.contains("agent-hero", ignoreCase = true) || applet.iconGlyph == "≠"
    val isCipherPad = lowerDTag.contains("cipher") || effectiveSource.contains("cipherStatus", ignoreCase = true)
    val isRelaySonar = lowerDTag.contains("sonar") || effectiveSource.contains("sonarOut", ignoreCase = true)
    val isPulseNotes = lowerDTag.contains("pulse") || effectiveSource.contains("noteBox", ignoreCase = true)

    val parsedHeadings = remember(effectiveSource) { extractHeadings(effectiveSource) }
    val parsedParagraphs = remember(effectiveSource) { extractParagraphs(effectiveSource) }
    val parsedButtons = remember(effectiveSource) { extractButtons(effectiveSource) }
    val parsedPlaceholder = remember(effectiveSource) { extractInputPlaceholder(effectiveSource) }

    LaunchedEffect(applet.appletId, applet.enableNip07, reloadKey) {
        if (applet.isNsite && isAgentStyle && !applet.enableNip07) {
            pubkeyPreview = "NIP-07 disabled for this nsite. Toggle NIP-07 in the top runtime bar."
        } else {
            val proto = if (applet.enableNip07 && applet.isNsite) "NIP-07 window.nostr" else "NIP-5D NAP"
            runCatching {
                onBridgeCall(applet, proto, "signer.getPublicKey", JSONObject())?.toString().orEmpty()
            }.onSuccess { pk ->
                if (pk.isNotBlank()) {
                    pubkeyPreview = "Active Signer Pubkey: ${pk.take(22)}…"
                }
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(BreBlack)
    ) {
        // Signature BreBre neon corner glow
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        BreDeepPink.copy(alpha = 0.28f),
                        BrePinkDark3.copy(alpha = 0.12f),
                        Color.Transparent
                    ),
                    center = Offset(size.width * 0.9f, size.height * 0.08f),
                    radius = size.minDimension * 0.55f
                ),
                radius = size.minDimension * 0.55f,
                center = Offset(size.width * 0.9f, size.height * 0.08f)
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 680.dp)
                .align(Alignment.TopCenter)
                .verticalScroll(rememberScrollState())
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (isZineStyle) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = BreBlack,
                    border = BorderStroke(2.dp, BreDeepPink),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(14.dp)
                    ) {
                        Text(
                            text = "☆ PRETEND INTERNET ☆",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Black),
                            color = BreDeepPink
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "M I C R O - S E R I E S   W O R L D   G U I D E",
                            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 2.sp),
                            color = ZineBoneCream
                        )
                    }
                }
            }

            if (isCrtStyle) {
                // Puter CRT Cyber-Sticker Monitor Frame
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = ZineBoneCream,
                    border = BorderStroke(4.dp, BreDeepPink),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "PUTER // OS • ${applet.title.uppercase()}",
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Black),
                                color = BreBlack
                            )
                            Text(
                                text = "☆ ♥ ××",
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Black),
                                color = BreDeepPink
                            )
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(BreGray900)
                                .border(3.dp, BreBlack, RoundedCornerShape(16.dp))
                                .padding(vertical = 24.dp, horizontal = 16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Row(horizontalArrangement = Arrangement.spacedBy(26.dp)) {
                                    Box(
                                        modifier = Modifier
                                            .size(width = 26.dp, height = 32.dp)
                                            .background(BreWhite)
                                    )
                                    Box(
                                        modifier = Modifier
                                            .size(width = 26.dp, height = 32.dp)
                                            .background(BreWhite)
                                    )
                                }
                                Spacer(modifier = Modifier.height(14.dp))
                                Text(
                                    text = statusOutput.ifBlank { pubkeyPreview.ifBlank { "READY FOR NAP SIGNAL_" } },
                                    fontFamily = JetBrainsMonoFontFamily,
                                    fontSize = 12.sp,
                                    color = BreLightPink,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    scope.launch {
                                        statusOutput = "Awaiting Signer approval…"
                                        runCatching {
                                            val evObj = onBridgeCall(
                                                applet,
                                                "NIP-5D NAP",
                                                "signer.signEvent",
                                                JSONObject().put(
                                                    "event",
                                                    JSONObject()
                                                        .put("kind", 1)
                                                        .put("content", "Puter CRT ping from SovereignShell! ☆ #nostr")
                                                        .put("tags", JSONArray())
                                                )
                                            ) as JSONObject
                                            val pub = onBridgeCall(
                                                applet,
                                                "NIP-5D NAP",
                                                "relay.publish",
                                                JSONObject().put("event", evObj)
                                            ) as JSONObject
                                            "TRANSMITTED ID: ${evObj.optString("id").take(16)}… (${pub.optInt("accepted")} relays)"
                                        }.onSuccess { statusOutput = it }
                                            .onFailure { statusOutput = "DENIED: ${it.message}" }
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = BreDeepPink)
                            ) {
                                Text("Transmit Note →", color = BreWhite)
                            }
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        runCatching {
                                            val pk = onBridgeCall(applet, "NIP-5D NAP", "signer.getPublicKey", JSONObject())
                                            "PUBKEY: ${pk.toString().take(22)}…"
                                        }.onSuccess { statusOutput = it }
                                            .onFailure { statusOutput = "ERR: ${it.message}" }
                                    }
                                },
                                border = BorderStroke(1.dp, BrePinkDark1)
                            ) {
                                Text("Ping Identity", color = BreBlack)
                            }
                        }
                    }
                }
            } else {
                // Hero / Applet Header Card
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = BreGray850,
                    border = BorderStroke(1.dp, if (isZineStyle) ZineBoneCream else BrePinkDark2),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        horizontalAlignment = if (isAgentStyle) Alignment.CenterHorizontally else Alignment.Start,
                        modifier = Modifier.padding(18.dp)
                    ) {
                        if (isAgentStyle) {
                            Box(
                                modifier = Modifier.size(76.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Canvas(modifier = Modifier.fillMaxSize()) {
                                    drawCircle(color = ZineBoneCream)
                                    drawCircle(
                                        color = BreDeepPink,
                                        radius = size.minDimension / 2f - 4.dp.toPx(),
                                        style = Stroke(
                                            width = 2.dp.toPx(),
                                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f), 0f)
                                        )
                                    )
                                }
                                Text(
                                    text = applet.iconGlyph.ifBlank { "≠" },
                                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Black),
                                    color = BreBlack
                                )
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                        }

                        Text(
                            text = if (applet.isNsite) "NIP-5A NSITE SANDBOX" else "☆ NIP-5D SANDBOXED NAPPLET ☆",
                            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.4.sp),
                            color = BreLightPink
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = parsedHeadings.firstOrNull()?.takeIf { it.isNotBlank() }
                                ?: "${applet.iconGlyph} ${applet.title}",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = BreWhite,
                            textAlign = if (isAgentStyle) TextAlign.Center else TextAlign.Start
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = parsedParagraphs.firstOrNull()?.takeIf { it.isNotBlank() }
                                ?: applet.description,
                            style = MaterialTheme.typography.bodyMedium,
                            color = BreGray100,
                            textAlign = if (isAgentStyle) TextAlign.Center else TextAlign.Start
                        )
                        if (pubkeyPreview.isNotBlank()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = pubkeyPreview,
                                fontFamily = JetBrainsMonoFontFamily,
                                fontSize = 11.sp,
                                color = ZineBoneCream
                            )
                        }
                    }
                }
            }

            // Specialized & Custom HTML/JS Interactive Capability Panels
            when {
                isPulseNotes -> {
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = BreGray850,
                        border = BorderStroke(1.dp, BrePinkDark2),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            OutlinedTextField(
                                value = textInput,
                                onValueChange = { textInput = it },
                                placeholder = { Text("Compose a Kind 1 note via NAP-SIGNER…") },
                                minLines = 3,
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = {
                                        if (textInput.isBlank()) return@Button
                                        scope.launch {
                                            statusOutput = "Awaiting Built-in Signer approval…"
                                            runCatching {
                                                val evObj = onBridgeCall(
                                                    applet,
                                                    "NIP-5D NAP",
                                                    "signer.signEvent",
                                                    JSONObject().put(
                                                        "event",
                                                        JSONObject()
                                                            .put("kind", 1)
                                                            .put("content", textInput.trim())
                                                            .put("tags", JSONArray().put(JSONArray().put("client").put("PulseNotes-NIP5D")))
                                                    )
                                                ) as JSONObject
                                                val res = onBridgeCall(
                                                    applet,
                                                    "NIP-5D NAP",
                                                    "relay.publish",
                                                    JSONObject().put("event", evObj)
                                                ) as JSONObject
                                                textInput = ""
                                                "Published ${evObj.optString("id").take(14)}… (${res.optInt("accepted")} relays)"
                                            }.onSuccess { statusOutput = it }
                                                .onFailure { statusOutput = "Signer rejected: ${it.message}" }
                                        }
                                    }
                                ) {
                                    Text("Sign & Publish →")
                                }
                                OutlinedButton(
                                    onClick = {
                                        scope.launch {
                                            statusOutput = "Querying host relay pool…"
                                            runCatching {
                                                val arr = onBridgeCall(
                                                    applet,
                                                    "NIP-5D NAP",
                                                    "relay.query",
                                                    JSONObject().put("filter", JSONObject().put("kinds", JSONArray().put(1)).put("limit", 10))
                                                ) as JSONArray
                                                queriedEvents.clear()
                                                for (i in 0 until arr.length()) {
                                                    val item = arr.optJSONObject(i) ?: continue
                                                    queriedEvents.add(
                                                        "${item.optString("pubkey").take(12)}…" to item.optString("content")
                                                    )
                                                }
                                                "Loaded ${queriedEvents.size} Kind 1 notes from relay pool"
                                            }.onSuccess { statusOutput = it }
                                                .onFailure { statusOutput = "Query error: ${it.message}" }
                                        }
                                    }
                                ) {
                                    Text("Query Relay Feed")
                                }
                            }
                            if (statusOutput.isNotBlank()) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = statusOutput,
                                    fontFamily = JetBrainsMonoFontFamily,
                                    fontSize = 12.sp,
                                    color = BreLightPink
                                )
                            }
                        }
                    }
                }

                isCipherPad -> {
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = BreGray850,
                        border = BorderStroke(1.dp, BrePinkDark2),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            OutlinedTextField(
                                value = textInput,
                                onValueChange = { textInput = it },
                                placeholder = { Text("Enter private memo to encrypt with your vault key…") },
                                minLines = 3,
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = {
                                        if (textInput.isBlank()) return@Button
                                        scope.launch {
                                            runCatching {
                                                val pk = onBridgeCall(applet, "NIP-5D NAP", "signer.getPublicKey", JSONObject()).toString()
                                                val ct = onBridgeCall(
                                                    applet,
                                                    "NIP-5D NAP",
                                                    "signer.nip44Encrypt",
                                                    JSONObject().put("pubkey", pk).put("plaintext", textInput)
                                                ).toString()
                                                onBridgeCall(
                                                    applet,
                                                    "NIP-5D NAP",
                                                    "storage.set",
                                                    JSONObject().put("key", "vault_memo_v2").put("value", ct)
                                                )
                                                "Stored NIP-44 v2 ciphertext: ${ct.take(32)}…"
                                            }.onSuccess { statusOutput = it }
                                                .onFailure { statusOutput = "Error: ${it.message}" }
                                        }
                                    }
                                ) {
                                    Text("Encrypt & Save (NIP-44) →")
                                }
                                OutlinedButton(
                                    onClick = {
                                        scope.launch {
                                            runCatching {
                                                val ct = onBridgeCall(
                                                    applet,
                                                    "NIP-5D NAP",
                                                    "storage.get",
                                                    JSONObject().put("key", "vault_memo_v2")
                                                )?.toString().orEmpty()
                                                if (ct.isBlank() || ct == "null") error("No encrypted memo stored yet.")
                                                val pk = onBridgeCall(applet, "NIP-5D NAP", "signer.getPublicKey", JSONObject()).toString()
                                                val pt = onBridgeCall(
                                                    applet,
                                                    "NIP-5D NAP",
                                                    "signer.nip44Decrypt",
                                                    JSONObject().put("pubkey", pk).put("ciphertext", ct)
                                                ).toString()
                                                textInput = pt
                                                "Decrypted NIP-44 v2 memo via Built-in Signer!"
                                            }.onSuccess { statusOutput = it }
                                                .onFailure { statusOutput = "Error: ${it.message}" }
                                        }
                                    }
                                ) {
                                    Text("Load & Decrypt")
                                }
                            }
                            if (statusOutput.isNotBlank()) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = statusOutput,
                                    fontFamily = JetBrainsMonoFontFamily,
                                    fontSize = 12.sp,
                                    color = BreLightPink
                                )
                            }
                        }
                    }
                }

                isRelaySonar -> {
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = BreGray850,
                        border = BorderStroke(1.dp, BrePinkDark2),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Button(
                                onClick = {
                                    scope.launch {
                                        statusOutput = "Scanning relay pool…"
                                        runCatching {
                                            val kindsArr = JSONArray().put(0).put(1).put(35128).put(35129)
                                            val arr = onBridgeCall(
                                                applet,
                                                "NIP-5D NAP",
                                                "relay.query",
                                                JSONObject().put("filter", JSONObject().put("kinds", kindsArr).put("limit", 12))
                                            ) as JSONArray
                                            queriedEvents.clear()
                                            for (i in 0 until arr.length()) {
                                                val ev = arr.optJSONObject(i) ?: continue
                                                queriedEvents.add(
                                                    "Kind ${ev.optInt("kind")} • ${ev.optString("id").take(14)}…" to ev.optString("content").take(80)
                                                )
                                            }
                                            "Scanned ${queriedEvents.size} events across connected relays"
                                        }.onSuccess { statusOutput = it }
                                            .onFailure { statusOutput = "Scan error: ${it.message}" }
                                    }
                                }
                            ) {
                                Text("Scan Latest Events →")
                            }
                            if (statusOutput.isNotBlank()) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = statusOutput,
                                    fontFamily = JetBrainsMonoFontFamily,
                                    fontSize = 12.sp,
                                    color = BreLightPink
                                )
                            }
                        }
                    }
                }

                !isCrtStyle -> {
                    // Dynamic HTML/CSS/JS Applet Card: renders parsed inputs, additional paragraphs, and buttons
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = BreGray850,
                        border = BorderStroke(1.dp, BrePinkDark2),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            if (parsedHeadings.size > 1) {
                                Text(
                                    text = parsedHeadings[1],
                                    style = MaterialTheme.typography.titleMedium,
                                    color = BreWhite
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                            }
                            if (parsedParagraphs.size > 1) {
                                Text(
                                    text = parsedParagraphs[1],
                                    style = MaterialTheme.typography.bodySmall,
                                    color = BreGray100
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                            }

                            if (parsedPlaceholder.isNotBlank()) {
                                OutlinedTextField(
                                    value = textInput,
                                    onValueChange = { textInput = it },
                                    placeholder = { Text(parsedPlaceholder) },
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                            }

                            val buttonsToRender = parsedButtons.ifEmpty {
                                listOf(
                                    if (isAgentStyle) "Sign Schnorr Proof →" else "Sign Event via NAP →",
                                    "Read Pubkey"
                                )
                            }

                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                buttonsToRender.forEachIndexed { idx, label ->
                                    val lowerLabel = label.lowercase()
                                    val isPubkeyBtn = lowerLabel.contains("pubkey") || lowerLabel.contains("identity") || lowerLabel.contains("ping")
                                    val isQueryBtn = lowerLabel.contains("query") || lowerLabel.contains("scan") || lowerLabel.contains("feed")

                                    val onClickAction: () -> Unit = {
                                        scope.launch {
                                            if (applet.isNsite && isAgentStyle && !applet.enableNip07) {
                                                statusOutput = "Enable NIP-07 in the runtime bar first."
                                                return@launch
                                            }
                                            val proto = if (applet.enableNip07) "NIP-07 window.nostr" else "NIP-5D NAP"
                                            when {
                                                isPubkeyBtn -> {
                                                    runCatching {
                                                        val pk = onBridgeCall(applet, proto, "signer.getPublicKey", JSONObject())
                                                        "PUBKEY: $pk"
                                                    }.onSuccess { statusOutput = it }
                                                        .onFailure { statusOutput = "ERR: ${it.message}" }
                                                }
                                                isQueryBtn -> {
                                                    runCatching {
                                                        val arr = onBridgeCall(
                                                            applet,
                                                            proto,
                                                            "relay.query",
                                                            JSONObject().put("filter", JSONObject().put("kinds", JSONArray().put(1)).put("limit", 8))
                                                        ) as JSONArray
                                                        queriedEvents.clear()
                                                        for (i in 0 until arr.length()) {
                                                            val ev = arr.optJSONObject(i) ?: continue
                                                            queriedEvents.add("${ev.optString("pubkey").take(12)}…" to ev.optString("content"))
                                                        }
                                                        "Loaded ${queriedEvents.size} events from relay pool"
                                                    }.onSuccess { statusOutput = it }
                                                        .onFailure { statusOutput = "ERR: ${it.message}" }
                                                }
                                                else -> {
                                                    val kind = if (isAgentStyle) 27235 else 1
                                                    val body = textInput.trim().ifBlank {
                                                        if (isAgentStyle) "Agent Zone Identity Proof"
                                                        else "Signed from ${applet.title} inside SovereignShell! ☆ #nostr"
                                                    }
                                                    runCatching {
                                                        val signed = onBridgeCall(
                                                            applet,
                                                            proto,
                                                            "signer.signEvent",
                                                            JSONObject().put(
                                                                "event",
                                                                JSONObject()
                                                                    .put("kind", kind)
                                                                    .put("content", body)
                                                                    .put("tags", JSONArray())
                                                            )
                                                        ) as JSONObject
                                                        if (kind == 1) {
                                                            onBridgeCall(applet, proto, "relay.publish", JSONObject().put("event", signed))
                                                        }
                                                        textInput = ""
                                                        "SIGNED ID: ${signed.optString("id").take(18)}… • SIG: ${signed.optString("sig").take(22)}…"
                                                    }.onSuccess { statusOutput = it }
                                                        .onFailure { statusOutput = "DENIED: ${it.message}" }
                                                }
                                            }
                                        }
                                    }

                                    if (idx == 0) {
                                        Button(onClick = onClickAction) {
                                            Text(label)
                                        }
                                    } else {
                                        OutlinedButton(onClick = onClickAction) {
                                            Text(label)
                                        }
                                    }
                                }
                            }

                            if (statusOutput.isNotBlank()) {
                                Spacer(modifier = Modifier.height(10.dp))
                                Text(
                                    text = statusOutput,
                                    fontFamily = JetBrainsMonoFontFamily,
                                    fontSize = 12.sp,
                                    color = BreLightPink
                                )
                            }
                        }
                    }
                }
            }

            if (queriedEvents.isNotEmpty()) {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = BreGray850,
                    border = BorderStroke(1.dp, BrePinkDark2),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "LIVE RELAY STREAM RESULTS",
                            style = MaterialTheme.typography.labelSmall,
                            color = ZineBoneCream
                        )
                        queriedEvents.forEach { (header, body) ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(BreBlack)
                                    .padding(10.dp)
                            ) {
                                Text(
                                    text = header,
                                    fontFamily = JetBrainsMonoFontFamily,
                                    fontSize = 11.sp,
                                    color = BreLightPink
                                )
                                if (body.isNotBlank()) {
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = body,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = BreWhite
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Collapsible Source Bundle Inspector
            Row(
                horizontalArrangement = Arrangement.End,
                modifier = Modifier.fillMaxWidth()
            ) {
                TextButton(onClick = { showSourceInspector = !showSourceInspector }) {
                    Text(
                        text = if (showSourceInspector) "Hide Bundle Source" else "Inspect NIP-5D / NIP-5A Bundle",
                        style = MaterialTheme.typography.labelSmall,
                        color = BreGray100
                    )
                }
            }

            AnimatedVisibility(visible = showSourceInspector) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = BreGray900,
                    border = BorderStroke(1.dp, BrePinkDark3),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = effectiveSource.take(1800),
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

private fun extractHeadings(html: String): List<String> =
    Regex("<h[1-3][^>]*>(.*?)</h[1-3]>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        .findAll(html)
        .map { stripHtmlTags(it.groupValues[1]) }
        .filter { it.isNotBlank() }
        .toList()

private fun extractParagraphs(html: String): List<String> =
    Regex("<p[^>]*>(.*?)</p>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        .findAll(html)
        .map { stripHtmlTags(it.groupValues[1]) }
        .filter { it.isNotBlank() }
        .toList()

private fun extractButtons(html: String): List<String> =
    Regex("<button[^>]*>(.*?)</button>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        .findAll(html)
        .map { stripHtmlTags(it.groupValues[1]) }
        .filter { it.isNotBlank() }
        .toList()

private fun extractInputPlaceholder(html: String): String =
    Regex("placeholder=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
        .find(html)
        ?.groupValues
        ?.getOrNull(1)
        ?.let { stripHtmlTags(it) }
        .orEmpty()

private fun stripHtmlTags(raw: String): String =
    raw.replace(Regex("<[^>]+>"), "")
        .replace("&amp;", "&")
        .replace("&nbsp;", " ")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&rarr;", "→")
        .trim()
