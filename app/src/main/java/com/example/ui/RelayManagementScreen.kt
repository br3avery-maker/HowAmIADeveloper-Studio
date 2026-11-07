package com.example.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.ManagedBlossomItem
import com.example.data.ManagedRelayItem
import com.example.nostr.RelayConnectionStatus
import com.example.ui.theme.*
import kotlin.math.roundToInt
import kotlin.math.sin

@Composable
fun RelayManagementScreen(
    relays: List<ManagedRelayItem>,
    blossomServers: List<ManagedBlossomItem>,
    isWorking: Boolean,
    onAddRelay: (String, () -> Unit) -> Unit,
    onRemoveRelay: (String) -> Unit,
    onReconnectRelay: (String) -> Unit,
    onAddBlossomServer: (String, Boolean, () -> Unit) -> Unit,
    onSetPrimaryBlossom: (String) -> Unit,
    onRemoveBlossomServer: (String) -> Unit,
    onProbeAllEndpoints: () -> Unit,
    onPublishNip65AndBlossom: () -> Unit,
    onOpenIdentityVault: () -> Unit,
    onOpenAccountMenu: () -> Unit,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)

    var newRelayUrl by remember { mutableStateOf("") }
    var newBlossomUrl by remember { mutableStateOf("") }
    var activeSection by remember { mutableIntStateOf(0) } // 0 = Relays, 1 = Blossom Mirrors

    val connectedCount = relays.count { it.statusInfo.status == RelayConnectionStatus.CONNECTED }
    val enabledBlossomCount = blossomServers.count { it.enabled }
    val totalVerifiedItems = relays.sumOf { (it.health?.verifiedPublishCount ?: 0) + it.statusInfo.eventsReceived } +
        blossomServers.sumOf { it.health?.verifiedRetrievalCount ?: 0 }
    val avgLatency = (
        relays.mapNotNull { it.health?.avgLatencyMs?.takeIf { lat -> lat > 0 } } +
            blossomServers.mapNotNull { it.health?.avgLatencyMs?.takeIf { lat -> lat > 0 } }
        ).average().takeIf { !it.isNaN() }?.roundToInt() ?: 42

    val healthyRelaysRatio = if (relays.isEmpty()) 1f else {
        (connectedCount.toFloat() / relays.size.toFloat()).coerceIn(0.15f, 1f)
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(BreBlack)
            .windowInsetsPadding(WindowInsets.statusBars)
            .testTag("relay_management_list"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. DEEPINK "NODE ACTIVE" HEADER + CIRCUIT EMBLEM (Matches 1794037248244_ff6f862d.jpg)
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        IconButton(
                            onClick = onOpenAccountMenu,
                            modifier = Modifier
                                .size(44.dp)
                                .testTag("node_drawer_button")
                        ) {
                            Icon(
                                Icons.Default.Menu,
                                contentDescription = "Open Shell Drawer",
                                tint = ZineBoneCream,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier
                                .size(44.dp)
                                .testTag("relay_back_btn")
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = BreGray100
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "NODE\nACTIVE",
                        style = MaterialTheme.typography.displayMedium,
                        color = ZineBoneCream,
                        fontWeight = FontWeight.Black,
                        lineHeight = 38.sp
                    )
                    Spacer(Modifier.height(4.dp))
                    Box(
                        modifier = Modifier
                            .width(140.dp)
                            .height(3.dp)
                            .background(BreDeepPink)
                    )
                }

                // Glowing Pink ≠ Circuit Emblem
                Box(
                    modifier = Modifier
                        .size(108.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    BreDeepPink.copy(alpha = 0.42f),
                                    BrePinkDark2.copy(alpha = 0.16f),
                                    Color.Transparent
                                )
                            )
                        )
                        .clickable { onOpenIdentityVault() }
                        .testTag("node_open_vault_emblem"),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(74.dp)
                            .clip(CircleShape)
                            .background(BreDeepPink)
                            .border(3.dp, BreBlack, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "≠",
                            color = BreBlack,
                            fontSize = 40.sp,
                            fontWeight = FontWeight.Black
                        )
                    }
                }
            }
        }

        // 2. TELEMETRY RATE & JAGGED PINK WAVEFORM GRAPH
        item {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "HASH & RELAY RATE: ${avgLatency}ms AVG",
                        style = MaterialTheme.typography.titleSmall,
                        fontFamily = FontFamily.Monospace,
                        color = ZineBoneCream,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                    Text(
                        text = "$connectedCount/${relays.size} RELAYS",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = BreDeepPink,
                        fontWeight = FontWeight.Black
                    )
                }

                // Jagged pink waveform canvas
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(68.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(ObsidianSurface)
                        .border(1.dp, ObsidianBorder, RoundedCornerShape(6.dp))
                ) {
                    val w = size.width
                    val h = size.height
                    val steps = 48
                    val path = Path().apply {
                        moveTo(0f, h)
                        for (i in 0..steps) {
                            val x = (i.toFloat() / steps) * w
                            val seed = (i * 1.7f) + connectedCount + totalVerifiedItems
                            val wave = (sin(seed.toDouble()).toFloat() * 0.32f +
                                sin((seed * 2.3f).toDouble()).toFloat() * 0.22f + 0.5f)
                                .coerceIn(0.12f, 0.92f)
                            val y = h - (wave * h * 0.85f)
                            lineTo(x, y)
                        }
                        lineTo(w, h)
                        close()
                    }
                    drawPath(
                        path = path,
                        brush = Brush.verticalGradient(
                            colors = listOf(BreDeepPink.copy(alpha = 0.85f), BrePinkDark2.copy(alpha = 0.15f))
                        )
                    )
                    for (i in 0 until steps) {
                        val x1 = (i.toFloat() / steps) * w
                        val x2 = ((i + 1).toFloat() / steps) * w
                        val s1 = (i * 1.7f) + connectedCount + totalVerifiedItems
                        val s2 = ((i + 1) * 1.7f) + connectedCount + totalVerifiedItems
                        val y1 = h - ((sin(s1.toDouble()).toFloat() * 0.32f + sin((s1 * 2.3f).toDouble()).toFloat() * 0.22f + 0.5f).coerceIn(0.12f, 0.92f) * h * 0.85f)
                        val y2 = h - ((sin(s2.toDouble()).toFloat() * 0.32f + sin((s2 * 2.3f).toDouble()).toFloat() * 0.22f + 0.5f).coerceIn(0.12f, 0.92f) * h * 0.85f)
                        drawLine(
                            color = BreLightPink,
                            start = Offset(x1, y1),
                            end = Offset(x2, y2),
                            strokeWidth = 2.5f
                        )
                    }
                }

                // Dual progress bar (Relays vs Blossom Mirrors)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    LinearProgressIndicator(
                        progress = { healthyRelaysRatio },
                        modifier = Modifier
                            .weight(1f)
                            .height(10.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        color = BreDeepPink,
                        trackColor = BrePinkDark3
                    )
                    LinearProgressIndicator(
                        progress = {
                            if (blossomServers.isEmpty()) 0f else (enabledBlossomCount.toFloat() / blossomServers.size.toFloat()).coerceIn(0.15f, 1f)
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(10.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        color = BreLightPink,
                        trackColor = BrePinkDark3
                    )
                }
            }
        }

        // 3. BONE CREAM SUMMARY METRIC CARD ("VERIFIED PAYLOADS")
        item {
            Surface(
                shape = CutCornerShape(topStart = 12.dp, bottomEnd = 12.dp),
                color = ZineBoneCream,
                border = BorderStroke(2.dp, BreBlack),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 18.dp, horizontal = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "VERIFIED MESH PAYLOADS",
                        style = MaterialTheme.typography.labelLarge,
                        color = BreBlack,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.5.sp
                    )
                    Text(
                        text = "$totalVerifiedItems BLOBS & EVENTS",
                        style = MaterialTheme.typography.headlineMedium,
                        color = BreBlack,
                        fontWeight = FontWeight.Black
                    )
                    Text(
                        text = "$connectedCount LIVE RELAYS • $enabledBlossomCount BLOSSOM MIRRORS • SHA-256 VERIFIED",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = BreBlack.copy(alpha = 0.78f),
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // 4. "ENGAGE" HAZARD-BORDERED ACTION BUTTON + VAULT QUICK SWITCH
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(
                    shape = CutCornerShape(8.dp),
                    color = BreDeepPink,
                    border = BorderStroke(2.dp, ZineBoneCream),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !isWorking) {
                            onProbeAllEndpoints()
                        }
                        .testTag("engage_mesh_probe_button")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 14.dp, horizontal = 18.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isWorking) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.5.dp,
                                color = ZineBoneCream
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = "PROBING MESH...",
                                style = MaterialTheme.typography.headlineSmall,
                                color = ZineBoneCream,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 2.sp
                            )
                        } else {
                            Text(
                                text = ">>>  ENGAGE  <<<",
                                style = MaterialTheme.typography.headlineSmall,
                                color = ZineBoneCream,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 2.sp
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onPublishNip65AndBlossom,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("relay_publish_nip65_btn"),
                        border = BorderStroke(1.dp, BreDeepPink),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = ZineBoneCream)
                    ) {
                        Icon(Icons.Default.CloudDone, contentDescription = null, modifier = Modifier.size(16.dp), tint = BreDeepPink)
                        Spacer(Modifier.width(6.dp))
                        Text("Broadcast NIP-65 + 10063", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    }
                    OutlinedButton(
                        onClick = onOpenIdentityVault,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("open_signer_vault_from_node_btn"),
                        border = BorderStroke(1.dp, ZineBoneCream),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = ZineBoneCream)
                    ) {
                        Icon(Icons.Default.Security, contentDescription = null, modifier = Modifier.size(16.dp), tint = BreDeepPink)
                        Spacer(Modifier.width(6.dp))
                        Text("Identity & Signer Vault", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    }
                }
            }
        }

        // 5. SECTION SWITCHER: RELAYS vs BLOSSOM MIRRORS
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = activeSection == 0,
                    onClick = { activeSection = 0 },
                    label = { Text("NOSTR RELAYS (${relays.size})", fontWeight = FontWeight.Black) },
                    leadingIcon = { Icon(Icons.Default.Router, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = BreDeepPink,
                        selectedLabelColor = BreBlack,
                        selectedLeadingIconColor = BreBlack
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("tab_nostr_relays")
                )
                FilterChip(
                    selected = activeSection == 1,
                    onClick = { activeSection = 1 },
                    label = { Text("BLOSSOM MIRRORS (${blossomServers.size})", fontWeight = FontWeight.Black) },
                    leadingIcon = { Icon(Icons.Default.Storage, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = BreDeepPink,
                        selectedLabelColor = BreBlack,
                        selectedLeadingIconColor = BreBlack
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("tab_blossom_mirrors")
                )
            }
        }

        if (activeSection == 0) {
            // ADD RELAY BAR
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = ObsidianSurface),
                    border = BorderStroke(1.dp, ObsidianBorder)
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "ADD NIP-5A / NIP-65 RELAY",
                            style = MaterialTheme.typography.labelMedium,
                            color = BreDeepPink,
                            fontWeight = FontWeight.Black
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = newRelayUrl,
                                onValueChange = { newRelayUrl = it },
                                placeholder = { Text("wss://relay.damus.io") },
                                singleLine = true,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("relay_input_field")
                            )
                            Button(
                                onClick = {
                                    if (newRelayUrl.isNotBlank()) {
                                        onAddRelay(newRelayUrl) { newRelayUrl = "" }
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = BreDeepPink,
                                    contentColor = BreBlack
                                ),
                                modifier = Modifier
                                    .height(52.dp)
                                    .testTag("relay_add_btn")
                            ) {
                                Icon(Icons.Default.Add, contentDescription = "Add Relay")
                            }
                        }
                    }
                }
            }

            items(relays, key = { it.url }) { relay ->
                RelayItemCard(
                    relay = relay,
                    onReconnect = { onReconnectRelay(relay.url) },
                    onRemove = { onRemoveRelay(relay.url) }
                )
            }
        } else {
            // ADD BLOSSOM MIRROR BAR
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = ObsidianSurface),
                    border = BorderStroke(1.dp, ObsidianBorder)
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "ADD BLOSSOM STORAGE MIRROR (KIND 10063)",
                            style = MaterialTheme.typography.labelMedium,
                            color = BreDeepPink,
                            fontWeight = FontWeight.Black
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = newBlossomUrl,
                                onValueChange = { newBlossomUrl = it },
                                placeholder = { Text("https://blossom.primal.net") },
                                singleLine = true,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("new_blossom_input")
                            )
                            Button(
                                onClick = {
                                    if (newBlossomUrl.isNotBlank()) {
                                        onAddBlossomServer(newBlossomUrl, false) { newBlossomUrl = "" }
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = BreDeepPink,
                                    contentColor = BreBlack
                                ),
                                modifier = Modifier
                                    .height(52.dp)
                                    .testTag("add_blossom_button")
                            ) {
                                Icon(Icons.Default.Add, contentDescription = "Add Blossom Mirror")
                            }
                        }
                    }
                }
            }

            items(blossomServers, key = { it.serverUrl }) { server ->
                BlossomMirrorCard(
                    server = server,
                    onSetPrimary = { onSetPrimaryBlossom(server.serverUrl) },
                    onRemove = { onRemoveBlossomServer(server.serverUrl) }
                )
            }
        }
    }
}

@Composable
private fun RelayItemCard(
    relay: ManagedRelayItem,
    onReconnect: () -> Unit,
    onRemove: () -> Unit
) {
    val status = relay.statusInfo.status
    val statusColor = when (status) {
        RelayConnectionStatus.CONNECTED -> CyberMint
        RelayConnectionStatus.CONNECTING -> ZineBoneCream
        RelayConnectionStatus.ERROR -> CoralAlert
        RelayConnectionStatus.DISCONNECTED -> BreGray600
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = ObsidianSurface),
        border = BorderStroke(
            1.dp,
            if (status == RelayConnectionStatus.CONNECTED) BreDeepPink.copy(alpha = 0.5f) else ObsidianBorder
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(statusColor)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = relay.url,
                    style = MaterialTheme.typography.titleSmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = ZineBoneCream,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = buildString {
                        append(status.name)
                        val lat = relay.health?.avgLatencyMs?.takeIf { it > 0L } ?: relay.statusInfo.latencyMs?.takeIf { it > 0L }
                        if (lat != null) append(" • ${lat}ms")
                        append(" • ${relay.statusInfo.eventsReceived} rx")
                        if (relay.health?.authRequired == true) append(" • NIP-42 AUTH")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = BreGray100
                )
            }
            IconButton(onClick = onReconnect, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = "Reconnect Relay",
                    tint = BreLightPink,
                    modifier = Modifier.size(18.dp)
                )
            }
            IconButton(onClick = onRemove, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Remove Relay",
                    tint = CoralAlert,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun BlossomMirrorCard(
    server: ManagedBlossomItem,
    onSetPrimary: () -> Unit,
    onRemove: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = ObsidianSurface),
        border = BorderStroke(
            1.dp,
            if (server.isPrimary) BreDeepPink else ObsidianBorder
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                Icons.Default.CloudQueue,
                contentDescription = null,
                tint = if (server.isPrimary) BreDeepPink else BreLightPink
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = server.serverUrl,
                    style = MaterialTheme.typography.titleSmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = ZineBoneCream,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = buildString {
                        append(if (server.isPrimary) "PRIMARY SERVER" else "BACKUP MIRROR")
                        val verified = server.health?.verifiedRetrievalCount ?: 0
                        if (verified > 0) append(" • $verified verified blobs")
                        val lat = server.health?.avgLatencyMs?.takeIf { it > 0 }
                        if (lat != null) append(" • ${lat}ms")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = BreGray100
                )
            }
            if (!server.isPrimary) {
                IconButton(onClick = onSetPrimary, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Default.Star,
                        contentDescription = "Set Primary Blossom Server",
                        tint = ZineBoneCream,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            IconButton(onClick = onRemove, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Remove Blossom Mirror",
                    tint = CoralAlert,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}
