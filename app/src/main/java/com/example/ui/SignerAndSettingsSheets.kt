package com.example.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.LauncherPrefs
import com.example.data.SignerAuditEntity
import com.example.data.SignerPolicyEntity
import com.example.data.VaultAccountEntity
import com.example.data.WallpaperStyle
import com.example.nostr.PendingSignRequest
import com.example.nostr.SignDecision
import com.example.ui.theme.CoralAlert
import com.example.ui.theme.CyberMint
import com.example.ui.theme.ObsidianBase
import com.example.ui.theme.ObsidianBorder
import com.example.ui.theme.ObsidianElevated
import com.example.ui.theme.ObsidianSurface

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignerApprovalBottomSheet(
    request: PendingSignRequest,
    onDecision: (SignDecision) -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = { onDecision(SignDecision.REJECT) },
        containerColor = ObsidianSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .testTag("signer_approval_sheet")
        ) {
            Text(
                text = request.protocolLabel.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = CyberMint
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "${request.appletTitle} requests ${request.method}",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "Active Key: ${request.activeDisplayName} (${request.activeNpub.take(14)}…) • Event Kind ${request.eventKind}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(12.dp))

            Surface(
                shape = RoundedCornerShape(14.dp),
                color = ObsidianBase,
                border = BorderStroke(1.dp, ObsidianBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = request.contentPreview,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = request.rawPayloadJson.take(260),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedButton(
                    onClick = { onDecision(SignDecision.REJECT) },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = CoralAlert),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("signer_reject_button")
                ) {
                    Text(text = stringResource(R.string.signer_reject))
                }

                OutlinedButton(
                    onClick = { onDecision(SignDecision.ALLOW_ALWAYS_KIND) },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .weight(1.2f)
                        .height(48.dp)
                        .testTag("signer_always_button")
                ) {
                    Text(text = stringResource(R.string.signer_allow_always))
                }

                Button(
                    onClick = { onDecision(SignDecision.ALLOW_ONCE) },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .weight(1.1f)
                        .height(48.dp)
                        .testTag("signer_allow_once_button")
                ) {
                    Text(text = stringResource(R.string.signer_allow_once))
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
fun SignerVaultAndAuditScreen(
    accounts: List<VaultAccountEntity>,
    revealedNsec: String?,
    policies: List<SignerPolicyEntity>,
    auditLog: List<SignerAuditEntity>,
    onSwitchAccount: (String) -> Unit,
    onGenerateNewAccount: (String) -> Unit,
    onImportNsecAccount: (String, String) -> Unit,
    onToggleRevealNsec: () -> Unit,
    onRevokePolicy: (String) -> Unit,
    onCopiedSecret: () -> Unit,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)

    var newAlias by rememberSaveable { mutableStateOf("") }
    var importKey by rememberSaveable { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianBase)
            .windowInsetsPadding(WindowInsets.statusBars)
            .windowInsetsPadding(WindowInsets.navigationBars)
    ) {
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 680.dp)
                .align(Alignment.TopCenter)
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("vault_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back),
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Column {
                        Text(
                            text = "Signer Vault & Permissions",
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            text = "AES-GCM Hardware Encrypted Multi-Account Nsec Store",
                            style = MaterialTheme.typography.labelSmall,
                            color = CyberMint
                        )
                    }
                }
            }

            // Accounts List & Backup
            item {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = ObsidianElevated,
                    border = BorderStroke(1.dp, ObsidianBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "STORED IDENTITIES (${accounts.size})",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        accounts.forEach { acc ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 6.dp)
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = acc.displayName.ifBlank { "Identity" },
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "${acc.npub.take(18)}…${acc.npub.takeLast(6)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                if (acc.isActive) {
                                    OutlinedButton(onClick = onToggleRevealNsec, shape = RoundedCornerShape(999.dp)) {
                                        Icon(
                                            imageVector = if (revealedNsec != null) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(if (revealedNsec != null) "Hide nsec" else "Backup nsec")
                                    }
                                } else {
                                    Button(
                                        onClick = { onSwitchAccount(acc.pubkeyHex) },
                                        shape = RoundedCornerShape(999.dp)
                                    ) {
                                        Text("Activate")
                                    }
                                }
                            }
                        }

                        if (revealedNsec != null) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = ObsidianBase,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(10.dp)
                                ) {
                                    Text(
                                        text = revealedNsec,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = CoralAlert,
                                        modifier = Modifier.weight(1f)
                                    )
                                    IconButton(onClick = {
                                        clipboard.setText(AnnotatedString(revealedNsec))
                                        onCopiedSecret()
                                    }) {
                                        Icon(imageVector = Icons.Default.ContentCopy, contentDescription = "Copy nsec")
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Generate or Import Keypair
            item {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = ObsidianElevated,
                    border = BorderStroke(1.dp, ObsidianBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "ADD OR IMPORT NOSTR KEYPAIR",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = newAlias,
                            onValueChange = { newAlias = it },
                            label = { Text("Account Alias") },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = importKey,
                            onValueChange = { importKey = it },
                            label = { Text("Optional nsec1… or 64-char hex to import") },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("vault_import_nsec_input")
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = {
                                    onGenerateNewAccount(newAlias)
                                    newAlias = ""
                                },
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("vault_generate_key_button")
                            ) {
                                Icon(imageVector = Icons.Default.Key, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Generate New")
                            }
                            OutlinedButton(
                                onClick = {
                                    onImportNsecAccount(importKey, newAlias)
                                    importKey = ""
                                    newAlias = ""
                                },
                                enabled = importKey.isNotBlank(),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("vault_import_key_button")
                            ) {
                                Text("Import nsec")
                            }
                        }
                    }
                }
            }

            if (policies.isNotEmpty()) {
                item {
                    Text(
                        text = "SAVED PER-APPLET POLICIES",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                items(policies, key = { it.policyKey }) { pol ->
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = ObsidianElevated,
                        border = BorderStroke(1.dp, ObsidianBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(pol.appletTitle, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "Always Allow ${pol.method} (Kind ${pol.eventKind})",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = CyberMint
                                )
                            }
                            IconButton(onClick = { onRevokePolicy(pol.policyKey) }) {
                                Icon(
                                    imageVector = Icons.Default.DeleteOutline,
                                    contentDescription = "Revoke policy",
                                    tint = CoralAlert
                                )
                            }
                        }
                    }
                }
            }

            item {
                Text(
                    text = "SIGNER AUDIT LOG (${auditLog.size})",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            items(auditLog, key = { it.id }) { entry ->
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = ObsidianElevated,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "${entry.appletTitle} • ${entry.protocolLabel}",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (entry.approved) CyberMint else CoralAlert
                        )
                        Text(
                            text = entry.summary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WallpaperAndBlossomScreen(
    prefs: LauncherPrefs,
    onPickCustomPhoto: (android.net.Uri) -> Unit,
    onSelectStyle: (WallpaperStyle) -> Unit,
    onUpdateScrim: (Float) -> Unit,
    onUpdateBlossomServer: (String) -> Unit,
    onToggleExternalNsites: (Boolean) -> Unit = {},
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)

    var blossomInput by rememberSaveable(prefs.primaryBlossomServer) {
        mutableStateOf(prefs.primaryBlossomServer)
    }

    val wallpaperPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) onPickCustomPhoto(uri)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianBase)
            .windowInsetsPadding(WindowInsets.statusBars)
            .windowInsetsPadding(WindowInsets.navigationBars)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 640.dp)
                .align(Alignment.TopCenter)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.minimumInteractiveComponentSize()
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.cd_back),
                        tint = MaterialTheme.colorScheme.onBackground
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Launcher Background & Blossom",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Surface(
                shape = RoundedCornerShape(20.dp),
                color = ObsidianElevated,
                border = BorderStroke(1.dp, ObsidianBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "HOME SCREEN BACKGROUND",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(
                        onClick = {
                            wallpaperPicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("pick_custom_wallpaper_button")
                    ) {
                        Icon(imageVector = Icons.Default.AddPhotoAlternate, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Choose Custom Background Photo")
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        FilterChip(
                            selected = prefs.wallpaperStyle == WallpaperStyle.CYBER_ZINE,
                            onClick = { onSelectStyle(WallpaperStyle.CYBER_ZINE) },
                            label = { Text("Cyber Zine") }
                        )
                        FilterChip(
                            selected = prefs.wallpaperStyle == WallpaperStyle.BREBRE_NEON_ORBS,
                            onClick = { onSelectStyle(WallpaperStyle.BREBRE_NEON_ORBS) },
                            label = { Text("BreBre Orbs") }
                        )
                        FilterChip(
                            selected = prefs.wallpaperStyle == WallpaperStyle.AGENT_CIRCUIT,
                            onClick = { onSelectStyle(WallpaperStyle.AGENT_CIRCUIT) },
                            label = { Text("Agent Circuit") }
                        )
                        FilterChip(
                            selected = prefs.wallpaperStyle == WallpaperStyle.DEFAULT_AURORA,
                            onClick = { onSelectStyle(WallpaperStyle.DEFAULT_AURORA) },
                            label = { Text("Aurora") }
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Wallpaper Contrast Scrim (${(prefs.scrimDimAlpha * 100).toInt()}%)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Slider(
                        value = prefs.scrimDimAlpha,
                        onValueChange = onUpdateScrim,
                        valueRange = 0.15f..0.85f
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Surface(
                shape = RoundedCornerShape(20.dp),
                color = ObsidianElevated,
                border = BorderStroke(1.dp, ObsidianBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "BLOSSOM SERVER (NSITE & AVATAR STORAGE)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = blossomInput,
                        onValueChange = { blossomInput = it },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = { onUpdateBlossomServer(blossomInput) },
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text("Save Blossom Endpoint")
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Surface(
                shape = RoundedCornerShape(20.dp),
                color = ObsidianElevated,
                border = BorderStroke(1.dp, ObsidianBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(
                            text = "NSITE LAUNCHER MODE",
                            style = MaterialTheme.typography.labelSmall,
                            color = CyberMint
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Open Nsites in External Custom Tab",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Launches remote NIP-5A Nsites in an expandable Android Custom Tab sheet like standard Nostr shells. NIP-5D Napplets & 'In-App' actions use the built-in sandboxed WebView.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = prefs.openNsitesExternally,
                        onCheckedChange = onToggleExternalNsites,
                        modifier = Modifier.testTag("toggle_external_nsites_switch")
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}
