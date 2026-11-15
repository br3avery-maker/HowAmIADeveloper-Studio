package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.ManagedRelayItem
import com.example.data.NsiteProjectEntity
import com.example.data.NsiteProjectFileEntity
import com.example.data.PublishDeploymentReport
import com.example.data.PublishVerificationBreakdown
import com.example.data.VaultAccountEntity
import com.example.nostr.ExternalSiteLauncher
import com.example.nostr.NostrEvent
import com.example.nostr.NostrKinds
import com.example.nostr.RelayBackgroundStatus
import com.example.nostr.RelayConnectionStatus
import com.example.ui.theme.BreBlack
import com.example.ui.theme.BreDeepPink
import com.example.ui.theme.BreGray100
import com.example.ui.theme.BreGray600
import com.example.ui.theme.BreGray850
import com.example.ui.theme.BreGray900
import com.example.ui.theme.BreLightPink
import com.example.ui.theme.BrePinkDark2
import com.example.ui.theme.BrePinkDark3
import com.example.ui.theme.BreWhite
import com.example.ui.theme.CoralAlert
import com.example.ui.theme.CyberMint
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.ObsidianBorder
import com.example.ui.theme.ObsidianSurface
import com.example.ui.theme.ZineBoneCream

private enum class InspectorTab(val label: String) {
    METADATA("Metadata"),
    FILE_HASHES("File Hashes"),
    RELAYS("Relays")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreatorInspectorBottomSheet(
    activeAccount: VaultAccountEntity?,
    activeProject: NsiteProjectEntity?,
    projectFiles: List<NsiteProjectFileEntity>,
    blossomServer: String,
    relays: List<ManagedRelayItem>,
    relayBackgroundStatus: RelayBackgroundStatus,
    latestReport: PublishDeploymentReport?,
    latestVerification: PublishVerificationBreakdown?,
    isWorking: Boolean,
    onMaintainRelays: () -> Unit,
    onCopyText: (label: String, value: String) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectedTabIndex by rememberSaveable { mutableIntStateOf(0) }
    val clipboard = LocalClipboardManager.current

    val copyWithFeedback: (String, String) -> Unit = { label, value ->
        clipboard.setText(AnnotatedString(value))
        onCopyText(label, value)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = BreGray900,
        contentColor = BreWhite,
        modifier = Modifier.testTag("studio_inspector_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Sheet Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        tint = BreDeepPink,
                        modifier = Modifier.size(22.dp)
                    )
                    Column {
                        Text(
                            text = "PROJECT INSPECTOR",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Black,
                                letterSpacing = 1.sp
                            ),
                            color = ZineBoneCream
                        )
                        Text(
                            text = activeProject?.title ?: "No active project",
                            style = MaterialTheme.typography.labelSmall,
                            color = BreLightPink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("inspector_close_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close Inspector",
                        tint = BreGray100
                    )
                }
            }

            // 3 Tabs: Metadata | File Hashes | Relays
            TabRow(
                selectedTabIndex = selectedTabIndex,
                containerColor = BreGray850,
                contentColor = BreDeepPink,
                indicator = { tabPositions ->
                    if (selectedTabIndex in tabPositions.indices) {
                        TabRowDefaults.SecondaryIndicator(
                            modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTabIndex]),
                            color = BreDeepPink
                        )
                    }
                }
            ) {
                InspectorTab.entries.forEachIndexed { index, tab ->
                    val countSuffix = when (tab) {
                        InspectorTab.METADATA -> ""
                        InspectorTab.FILE_HASHES -> " (${projectFiles.size})"
                        InspectorTab.RELAYS -> " (${relays.size})"
                    }
                    Tab(
                        selected = selectedTabIndex == index,
                        onClick = { selectedTabIndex = index },
                        text = {
                            Text(
                                text = "${tab.label}$countSuffix",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = if (selectedTabIndex == index) FontWeight.Black else FontWeight.Medium
                                ),
                                color = if (selectedTabIndex == index) ZineBoneCream else BreGray100,
                                maxLines = 1
                            )
                        },
                        modifier = Modifier.testTag(
                            when (tab) {
                                InspectorTab.METADATA -> "inspector_tab_metadata"
                                InspectorTab.FILE_HASHES -> "inspector_tab_hashes"
                                InspectorTab.RELAYS -> "inspector_tab_relays"
                            }
                        )
                    )
                }
            }

            when (InspectorTab.entries.getOrElse(selectedTabIndex) { InspectorTab.METADATA }) {
                InspectorTab.METADATA -> {
                    InspectorMetadataPane(
                        activeAccount = activeAccount,
                        activeProject = activeProject,
                        projectFiles = projectFiles,
                        blossomServer = blossomServer,
                        latestReport = latestReport,
                        latestVerification = latestVerification,
                        onCopy = copyWithFeedback
                    )
                }

                InspectorTab.FILE_HASHES -> {
                    InspectorFileHashesPane(
                        activeProject = activeProject,
                        projectFiles = projectFiles,
                        onCopy = copyWithFeedback
                    )
                }

                InspectorTab.RELAYS -> {
                    InspectorRelaysPane(
                        relays = relays,
                        relayBackgroundStatus = relayBackgroundStatus,
                        isWorking = isWorking,
                        onMaintainRelays = onMaintainRelays
                    )
                }
            }
        }
    }
}

@Composable
private fun InspectorMetadataPane(
    activeAccount: VaultAccountEntity?,
    activeProject: NsiteProjectEntity?,
    projectFiles: List<NsiteProjectFileEntity>,
    blossomServer: String,
    latestReport: PublishDeploymentReport?,
    latestVerification: PublishVerificationBreakdown?,
    onCopy: (String, String) -> Unit
) {
    if (activeProject == null) {
        Text(
            text = "Create or open an Nsite project in Creator Studio to inspect NIP-5A metadata.",
            style = MaterialTheme.typography.bodySmall,
            color = BreGray100
        )
        return
    }

    val pubHex = activeAccount?.pubkeyHex.orEmpty().ifBlank { activeProject.ownerPubkeyHex }
    val npub = activeAccount?.npub.orEmpty()
    val effectiveDTag = if (activeProject.targetKind == NostrKinds.NSITE_ROOT) {
        "root"
    } else {
        activeProject.dTag.ifBlank { "my-nsite" }
    }
    val coordinate = "${activeProject.targetKind}:${pubHex.ifBlank { "<unsigned>" }}:$effectiveDTag"
    val kindLabel = if (activeProject.targetKind == NostrKinds.NSITE_ROOT) {
        "15128 (Root Profile Nsite)"
    } else {
        "35128 (Named Subdomain Nsite)"
    }
    val aggregateHash = remember(projectFiles) {
        val pathMap = projectFiles.associate { it.relativePath to it.sha256 }
        NostrEvent.computeNip5aAggregateHash(pathMap)
    }
    val gatewayUrl = remember(activeProject, pubHex, npub, effectiveDTag) {
        ExternalSiteLauncher.buildNip5aGatewayUrl(
            kind = activeProject.targetKind,
            authorPubkeyHex = pubHex,
            authorNpub = npub,
            dTag = effectiveDTag
        ) ?: activeProject.lastPublishedUrl.ifBlank { "Requires valid 1-13 char slug ([a-z0-9-]) & signing pubkey" }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 420.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = ObsidianSurface,
            border = BorderStroke(1.dp, ObsidianBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "NIP-5A MANIFEST CONTRACT",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Black),
                    color = BreDeepPink
                )
                InspectorKeyValueRow(label = "Kind", value = kindLabel)
                InspectorKeyValueRow(label = "Identifier (d)", value = effectiveDTag)
                InspectorKeyValueRow(label = "Entry Path", value = activeProject.entryPath)
                InspectorKeyValueRow(
                    label = "Source / Rev",
                    value = "${activeProject.sourceType} • v${activeProject.versionCounter}"
                )
                InspectorCopyableMonoBlock(
                    label = "Coordinate",
                    value = coordinate,
                    copyTag = "inspector_copy_coordinate_btn",
                    onCopy = { onCopy("NIP-5A coordinate", coordinate) }
                )
                InspectorCopyableMonoBlock(
                    label = "Aggregate Hash (x)",
                    value = aggregateHash.ifBlank { "No files in workspace" },
                    valueTag = "inspector_aggregate_hash_value",
                    copyTag = "inspector_copy_aggregate_btn",
                    enabled = aggregateHash.isNotBlank(),
                    onCopy = { onCopy("aggregate SHA-256", aggregateHash) }
                )
                InspectorCopyableMonoBlock(
                    label = "Gateway Address",
                    value = gatewayUrl,
                    copyTag = "inspector_copy_gateway_btn",
                    enabled = gatewayUrl.startsWith("http"),
                    onCopy = { onCopy("gateway URL", gatewayUrl) }
                )
            }
        }

        Surface(
            shape = RoundedCornerShape(12.dp),
            color = ObsidianSurface,
            border = BorderStroke(1.dp, ObsidianBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "BLOSSOM & PUBLICATION STATUS",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Black),
                    color = CyberMint
                )
                InspectorKeyValueRow(label = "Primary Server", value = blossomServer.ifBlank { "Not set" })
                val lastEvId = latestReport?.manifestEventId?.ifBlank { null }
                    ?: activeProject.lastPublishedEventId.ifBlank { null }
                InspectorKeyValueRow(
                    label = "Last Event ID",
                    value = lastEvId ?: "Draft not published yet"
                )
                if (latestVerification != null) {
                    InspectorKeyValueRow(
                        label = "Blossom Mirrors",
                        value = "${latestVerification.verifiedUploadedBlobs}/${latestVerification.totalProjectFiles} verified across ${latestVerification.mirroredServerCount} server(s)"
                    )
                    InspectorKeyValueRow(
                        label = "Relay Read-back",
                        value = "${latestVerification.acceptedRelays}/${latestVerification.connectedRelays} accepted • ${latestVerification.verifiedRetrievalRelays} verified"
                    )
                }
            }
        }

        if (activeProject.isFork) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = BreGray850,
                border = BorderStroke(1.dp, BrePinkDark2),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "FORK PROVENANCE",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Black),
                        color = ZineBoneCream
                    )
                    InspectorKeyValueRow(label = "Origin Slug", value = activeProject.forkSourceDTag.ifBlank { "root" })
                    InspectorKeyValueRow(label = "Origin Author", value = activeProject.forkSourceNpub.ifBlank { activeProject.forkSourcePubkey })
                    if (activeProject.forkSourceCoordinate.isNotBlank()) {
                        InspectorKeyValueRow(label = "Origin Coordinate", value = activeProject.forkSourceCoordinate)
                    }
                }
            }
        }
    }
}

@Composable
private fun InspectorFileHashesPane(
    activeProject: NsiteProjectEntity?,
    projectFiles: List<NsiteProjectFileEntity>,
    onCopy: (String, String) -> Unit
) {
    val totalBytes = remember(projectFiles) { projectFiles.sumOf { it.sizeBytes } }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(
                    imageVector = Icons.Default.Fingerprint,
                    contentDescription = null,
                    tint = CyberMint,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = "${projectFiles.size} tracked file(s)",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = ZineBoneCream
                )
            }
            Text(
                text = "Total: ${formatByteSize(totalBytes)}",
                fontFamily = JetBrainsMonoFontFamily,
                fontSize = 11.sp,
                color = BreLightPink
            )
        }

        if (projectFiles.isEmpty()) {
            Text(
                text = "No files in this project yet.",
                style = MaterialTheme.typography.bodySmall,
                color = BreGray100
            )
            return
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 390.dp)
                .testTag("inspector_file_hash_list"),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(projectFiles, key = { it.relativePath }) { file ->
                val isEntry = file.relativePath == activeProject?.entryPath
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = ObsidianSurface,
                    border = BorderStroke(1.dp, if (isEntry) BreDeepPink else ObsidianBorder),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("inspector_file_card_${file.relativePath}")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = file.relativePath,
                                    fontFamily = JetBrainsMonoFontFamily,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = BreWhite,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                if (isEntry) {
                                    Text(
                                        text = "ENTRY",
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Black,
                                        color = BreBlack,
                                        modifier = Modifier
                                            .background(BreDeepPink, RoundedCornerShape(4.dp))
                                            .padding(horizontal = 5.dp, vertical = 1.dp)
                                    )
                                }
                            }
                            Text(
                                text = "${file.mimeType} • ${formatByteSize(file.sizeBytes)} • ${if (file.isBinary) "Binary" else "UTF-8"}",
                                style = MaterialTheme.typography.labelSmall,
                                color = BreGray100
                            )
                            Text(
                                text = "sha256:${file.sha256}",
                                fontFamily = JetBrainsMonoFontFamily,
                                fontSize = 10.sp,
                                color = CyberMint,
                                lineHeight = 14.sp,
                                modifier = Modifier.testTag("inspector_file_sha_${file.relativePath}")
                            )
                        }
                        IconButton(
                            onClick = { onCopy(file.relativePath, file.sha256) },
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("inspector_copy_sha_${file.relativePath}")
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = "Copy SHA-256 for ${file.relativePath}",
                                tint = BreLightPink,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InspectorRelaysPane(
    relays: List<ManagedRelayItem>,
    relayBackgroundStatus: RelayBackgroundStatus,
    isWorking: Boolean,
    onMaintainRelays: () -> Unit
) {
    val connectedCount = relays.count { it.statusInfo.status == RelayConnectionStatus.CONNECTED }

    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 420.dp)
            .testTag("inspector_relay_status_list"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item(key = "__bg_manager_card__") {
            // Background Relay Manager Telemetry Card
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = BreGray850,
                border = BorderStroke(1.dp, BrePinkDark2),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("inspector_relay_bg_card")
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(
                                imageVector = Icons.Default.Router,
                                contentDescription = null,
                                tint = CyberMint,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "BACKGROUND RELAY MANAGER",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Black),
                                color = ZineBoneCream
                            )
                        }
                        Text(
                            text = if (relayBackgroundStatus.isCycling) {
                                "OPTIMIZING…"
                            } else if (relayBackgroundStatus.isRunning) {
                                "ACTIVE • #${relayBackgroundStatus.cycleCount}"
                            } else {
                                "STANDBY • #${relayBackgroundStatus.cycleCount}"
                            },
                            fontFamily = JetBrainsMonoFontFamily,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (relayBackgroundStatus.isRunning) CyberMint else BreLightPink
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        RelayStatChip(
                            label = "Connected",
                            value = "$connectedCount/${relays.size}",
                            modifier = Modifier.weight(1f)
                        )
                        RelayStatChip(
                            label = "Avg Latency",
                            value = if (relayBackgroundStatus.averageLatencyMs > 0L) {
                                "${relayBackgroundStatus.averageLatencyMs}ms"
                            } else "—",
                            modifier = Modifier.weight(1f)
                        )
                        RelayStatChip(
                            label = "NIP-01 Hints",
                            value = "+${relayBackgroundStatus.discoveredFromNip01Count}",
                            modifier = Modifier.weight(1f)
                        )
                        RelayStatChip(
                            label = "Dropped",
                            value = "${relayBackgroundStatus.droppedUnresponsiveCount}",
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Text(
                        text = relayBackgroundStatus.lastSummary,
                        fontFamily = JetBrainsMonoFontFamily,
                        fontSize = 10.sp,
                        color = BreGray100
                    )

                    Button(
                        onClick = onMaintainRelays,
                        enabled = !isWorking && !relayBackgroundStatus.isCycling,
                        colors = ButtonDefaults.buttonColors(containerColor = BreDeepPink),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("inspector_optimize_relays_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Dns,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = BreWhite
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Discover NIP-01 Relays & Drop Dead",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Black),
                            color = BreWhite
                        )
                    }
                }
            }
        }

        items(relays, key = { it.url }) { relay ->
            val statusColor = when (relay.statusInfo.status) {
                RelayConnectionStatus.CONNECTED -> CyberMint
                RelayConnectionStatus.CONNECTING -> BreLightPink
                RelayConnectionStatus.ERROR -> CoralAlert
                RelayConnectionStatus.DISCONNECTED -> BreGray600
            }
            val latency = relay.statusInfo.latencyMs?.takeIf { it > 0L }
                ?: relay.health?.avgLatencyMs?.takeIf { it > 0L }

            Surface(
                shape = RoundedCornerShape(10.dp),
                color = ObsidianSurface,
                border = BorderStroke(1.dp, ObsidianBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = relay.url,
                            fontFamily = JetBrainsMonoFontFamily,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = BreWhite,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = buildString {
                                append(relay.statusInfo.status.name)
                                if (latency != null) append(" ${latency}ms")
                            },
                            fontFamily = JetBrainsMonoFontFamily,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Black,
                            color = statusColor
                        )
                    }
                    val roleText = buildString {
                        if (relay.readEnabled) append("R")
                        if (relay.writeEnabled) append(if (isNotEmpty()) "/W" else "W")
                        val nips = relay.health?.supportedNipsCsv.orEmpty()
                        if (nips.isNotBlank()) append(" • NIPs: ${nips.take(36)}")
                        val msg = relay.statusInfo.lastMessage ?: relay.health?.lastErrorReason.orEmpty()
                        if (msg.isNotBlank()) append(" • $msg")
                    }
                    if (roleText.isNotBlank()) {
                        Text(
                            text = roleText,
                            style = MaterialTheme.typography.labelSmall,
                            color = BreGray100,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InspectorKeyValueRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = BreGray100,
            modifier = Modifier.width(108.dp)
        )
        Text(
            text = value,
            fontFamily = JetBrainsMonoFontFamily,
            fontSize = 11.sp,
            color = BreWhite,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun InspectorCopyableMonoBlock(
    label: String,
    value: String,
    copyTag: String,
    onCopy: () -> Unit,
    valueTag: String = "",
    enabled: Boolean = true
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        HorizontalDivider(color = ObsidianBorder)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = BreGray100
                )
                Text(
                    text = value,
                    fontFamily = JetBrainsMonoFontFamily,
                    fontSize = 11.sp,
                    color = BreLightPink,
                    modifier = if (valueTag.isNotBlank()) Modifier.testTag(valueTag) else Modifier
                )
            }
            if (enabled) {
                IconButton(
                    onClick = onCopy,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag(copyTag)
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = "Copy $label",
                        tint = CyberMint,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun RelayStatChip(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = BrePinkDark3.copy(alpha = 0.45f),
        border = BorderStroke(1.dp, ObsidianBorder),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = value,
                fontFamily = JetBrainsMonoFontFamily,
                fontSize = 12.sp,
                fontWeight = FontWeight.Black,
                color = ZineBoneCream,
                maxLines = 1
            )
            Text(
                text = label,
                fontSize = 9.sp,
                color = BreGray100,
                maxLines = 1
            )
        }
    }
}

private fun formatByteSize(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024.0) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    return "%.2f MB".format(mb)
}
