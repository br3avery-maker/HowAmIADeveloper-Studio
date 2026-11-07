package com.example.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.LauncherPrefs
import com.example.data.SignerAuditEntity
import com.example.data.SignerPolicyEntity
import com.example.data.VaultAccountEntity
import com.example.data.WallpaperStyle
import com.example.nostr.PendingSignRequest
import com.example.nostr.SignDecision
import com.example.nostr.SignerType
import com.example.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun InteractiveSignerBottomSheet(
    request: PendingSignRequest,
    onDecision: (SignDecision) -> Unit
) {
    var rememberChoice by remember { mutableStateOf(false) }

    Surface(
        color = BreBlack,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        border = BorderStroke(1.5.dp, BreDeepPink),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("signer_permission_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = BrePinkDark3,
                    modifier = Modifier.size(44.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Shield, contentDescription = null, tint = BreDeepPink)
                    }
                }
                Column {
                    Text(
                        text = stringResource(R.string.signer_prompt_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = ZineBoneCream,
                        fontWeight = FontWeight.Black
                    )
                    Text(
                        text = "Private keys remain isolated in native vault / external signer",
                        style = MaterialTheme.typography.bodySmall,
                        color = BreGray100
                    )
                }
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = ObsidianSurface),
                border = BorderStroke(1.dp, ObsidianBorder)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("NSITE", style = MaterialTheme.typography.labelSmall, color = BreGray600)
                        Text(request.appletTitle, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = ZineBoneCream)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("CAPABILITY", style = MaterialTheme.typography.labelSmall, color = BreGray600)
                        Text(
                            "${request.method} (Kind ${request.eventKind})",
                            style = MaterialTheme.typography.labelMedium,
                            color = BreDeepPink,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    HorizontalDivider(color = ObsidianBorder)
                    Text(
                        text = request.contentPreview.take(260),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = BreGray100,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { rememberChoice = !rememberChoice }
            ) {
                Checkbox(
                    checked = rememberChoice,
                    onCheckedChange = { rememberChoice = it },
                    colors = CheckboxDefaults.colors(checkedColor = BreDeepPink, checkmarkColor = BreBlack)
                )
                Text(
                    text = stringResource(R.string.signer_allow_always),
                    style = MaterialTheme.typography.bodySmall,
                    color = ZineBoneCream
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = { onDecision(SignDecision.REJECT) },
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("signer_reject_btn"),
                    border = BorderStroke(1.dp, CoralAlert),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = CoralAlert)
                ) {
                    Text(stringResource(R.string.signer_reject), fontWeight = FontWeight.Black)
                }
                Button(
                    onClick = {
                        onDecision(
                            if (rememberChoice) SignDecision.ALLOW_ALWAYS_KIND else SignDecision.ALLOW_ONCE
                        )
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("signer_approve_btn"),
                    colors = ButtonDefaults.buttonColors(containerColor = BreDeepPink, contentColor = BreBlack)
                ) {
                    Text(stringResource(R.string.signer_allow_once), fontWeight = FontWeight.Black)
                }
            }
        }
    }
}

@Composable
fun VaultAndAuditScreen(
    accounts: List<VaultAccountEntity>,
    activeAccount: VaultAccountEntity?,
    revealedNsec: String?,
    policies: List<SignerPolicyEntity>,
    auditLog: List<SignerAuditEntity>,
    isWorking: Boolean = false,
    onGenerateAccount: (String) -> Unit,
    onImportAccount: (String, String) -> Unit,
    onAddReadOnlyNpub: (String, String) -> Unit,
    onConnectNip55Signer: (String, String) -> Unit,
    onConnectNip46Bunker: (String, String, () -> Unit) -> Unit,
    onSwitchAccount: (String) -> Unit,
    onToggleRevealNsec: () -> Unit,
    onRevokePolicy: (String) -> Unit,
    onOpenProfileEditor: () -> Unit,
    onOpenAccountMenu: () -> Unit,
    onCopiedText: (String) -> Unit,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)

    val clipboard = LocalClipboardManager.current
    var selectedSection by remember { mutableIntStateOf(0) } // 0 = Identity & Signers, 1 = Site Policies, 2 = Audit Log
    var importMode by remember { mutableIntStateOf(0) } // 0 = Generate, 1 = nsec/hex, 2 = NIP-55, 3 = NIP-46 Bunker, 4 = Read-Only npub

    var labelInput by remember { mutableStateOf("") }
    var secretOrUriInput by remember { mutableStateOf("") }
    var nip55PkgInput by remember { mutableStateOf("com.greenart7c3.nostrsigner") }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(BreBlack)
            .windowInsetsPadding(WindowInsets.statusBars)
            .testTag("signer_vault_screen"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. DEEPINK "RISE UP / IGNITE ID" HEADER (Matches 1794036632906_326cf034.jpg)
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    IconButton(
                        onClick = onOpenAccountMenu,
                        modifier = Modifier
                            .size(44.dp)
                            .testTag("vault_drawer_button")
                    ) {
                        Icon(Icons.Default.Menu, contentDescription = "Open Drawer", tint = ZineBoneCream)
                    }
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier
                            .size(44.dp)
                            .testTag("vault_back_btn")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = BreGray100)
                    }
                    Column {
                        Text(
                            text = "RISE UP • SOVEREIGN ID",
                            style = MaterialTheme.typography.titleLarge,
                            color = ZineBoneCream,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.sp
                        )
                        Text(
                            text = "KEYSTORE VAULT • NIP-55 INTENTS • NIP-46 BUNKER",
                            style = MaterialTheme.typography.labelSmall,
                            color = BreDeepPink,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                OutlinedButton(
                    onClick = onOpenProfileEditor,
                    border = BorderStroke(1.dp, BreDeepPink),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = ZineBoneCream),
                    modifier = Modifier.testTag("vault_edit_profile_btn")
                ) {
                    Icon(Icons.Default.Person, contentDescription = null, tint = BreDeepPink, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("KIND 0", fontWeight = FontWeight.Black, fontSize = 11.sp)
                }
            }
        }

        // 2. ACTIVE IDENTITY BONE-CREAM / DEEP-PINK CARD
        item {
            Surface(
                shape = CutCornerShape(topStart = 12.dp, bottomEnd = 12.dp),
                color = ObsidianSurface,
                border = BorderStroke(2.dp, BreDeepPink),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            AccountAvatarThumbnail(
                                account = activeAccount,
                                sizeDp = 48,
                                contentDescription = activeAccount?.displayName
                            )
                            Column {
                                Text(
                                    text = activeAccount?.displayName ?: "Sovereign Identity",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = ZineBoneCream,
                                    fontWeight = FontWeight.Black
                                )
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = BrePinkDark3
                                ) {
                                    Text(
                                        text = "SIGNER: ${activeAccount?.resolvedSignerType?.label ?: SignerType.LOCAL_KEYSTORE.label}",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = BreDeepPink,
                                        fontWeight = FontWeight.Black,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }

                        IconButton(
                            onClick = {
                                val npub = activeAccount?.npub.orEmpty()
                                if (npub.isNotBlank()) {
                                    clipboard.setText(AnnotatedString(npub))
                                    onCopiedText("Copied npub to clipboard")
                                }
                            },
                            modifier = Modifier.testTag("copy_npub_button")
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy npub", tint = BreDeepPink)
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = BreBlack,
                        border = BorderStroke(1.dp, ObsidianBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = activeAccount?.npub ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = ZineBoneCream,
                            modifier = Modifier.padding(10.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    if (activeAccount?.resolvedSignerType == SignerType.LOCAL_KEYSTORE) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedButton(
                                onClick = onToggleRevealNsec,
                                border = BorderStroke(1.dp, ObsidianBorder),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = BreGray100),
                                modifier = Modifier.testTag("vault_reveal_nsec_btn")
                            ) {
                                Icon(Icons.Default.Visibility, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(if (revealedNsec == null) "Reveal nsec1 Backup" else "Hide nsec1 Backup", fontSize = 11.sp)
                            }
                            if (revealedNsec != null) {
                                OutlinedButton(
                                    onClick = {
                                        clipboard.setText(AnnotatedString(revealedNsec))
                                        onCopiedText("Copied nsec1 backup")
                                    },
                                    border = BorderStroke(1.dp, BreDeepPink),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = BreDeepPink)
                                ) {
                                    Text("Copy nsec1", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                        if (revealedNsec != null) {
                            Text(
                                text = revealedNsec,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = ZineBoneCream
                            )
                        }
                    }
                }
            }
        }

        // 3. SECTION TABS
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = selectedSection == 0,
                    onClick = { selectedSection = 0 },
                    label = { Text("ACCOUNTS & SIGNERS", fontWeight = FontWeight.Black, fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = BreDeepPink,
                        selectedLabelColor = BreBlack
                    ),
                    modifier = Modifier.testTag("vault_tab_accounts")
                )
                FilterChip(
                    selected = selectedSection == 1,
                    onClick = { selectedSection = 1 },
                    label = { Text("PERMISSIONS (${policies.size})", fontWeight = FontWeight.Black, fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = BreDeepPink,
                        selectedLabelColor = BreBlack
                    ),
                    modifier = Modifier.testTag("vault_tab_policies")
                )
                FilterChip(
                    selected = selectedSection == 2,
                    onClick = { selectedSection = 2 },
                    label = { Text("AUDIT (${auditLog.size})", fontWeight = FontWeight.Black, fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = BreDeepPink,
                        selectedLabelColor = BreBlack
                    ),
                    modifier = Modifier.testTag("vault_tab_audit")
                )
            }
        }

        when (selectedSection) {
            0 -> {
                // IGNITE / ADD IDENTITY CARD (Supports Local Keystore, nsec, NIP-55, NIP-46 Bunker, Read-Only npub)
                item {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = ObsidianSurface,
                        border = BorderStroke(1.dp, ObsidianBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = "IGNITE ID • ADD OR CONNECT SIGNER",
                                style = MaterialTheme.typography.titleSmall,
                                color = BreDeepPink,
                                fontWeight = FontWeight.Black
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                listOf(
                                    0 to "NEW KEY",
                                    1 to "NSEC",
                                    2 to "NIP-55",
                                    3 to "NIP-46",
                                    4 to "NPUB"
                                ).forEach { (idx, title) ->
                                    FilterChip(
                                        selected = importMode == idx,
                                        onClick = { importMode = idx },
                                        label = { Text(title, fontSize = 10.sp, fontWeight = FontWeight.Black) },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = BrePinkDark3,
                                            selectedLabelColor = BreDeepPink
                                        ),
                                        modifier = Modifier.testTag("signer_mode_$idx")
                                    )
                                }
                            }

                            OutlinedTextField(
                                value = labelInput,
                                onValueChange = { labelInput = it },
                                label = { Text("Account Label / Alias") },
                                placeholder = { Text("Rebel Studio #2") },
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("identity_label_input")
                            )

                            when (importMode) {
                                0 -> {
                                    Text(
                                        text = "Generates a fresh secp256k1 Schnorr identity encrypted inside Android Keystore AES-256-GCM.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = BreGray100
                                    )
                                    Button(
                                        onClick = {
                                            onGenerateAccount(labelInput)
                                            labelInput = ""
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = BreDeepPink, contentColor = BreBlack),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(48.dp)
                                            .testTag("vault_generate_btn")
                                    ) {
                                        Text("IGNITE ID >>>", fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                                    }
                                }

                                1 -> {
                                    OutlinedTextField(
                                        value = secretOrUriInput,
                                        onValueChange = { secretOrUriInput = it },
                                        label = { Text("nsec1... or 64-char hex secret key") },
                                        singleLine = true,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("vault_import_key_input")
                                    )
                                    Button(
                                        onClick = {
                                            if (secretOrUriInput.isNotBlank()) {
                                                onImportAccount(secretOrUriInput.trim(), labelInput)
                                                secretOrUriInput = ""
                                                labelInput = ""
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = BreDeepPink, contentColor = BreBlack),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(48.dp)
                                            .testTag("vault_import_btn")
                                    ) {
                                        Text("IMPORT SECRET KEY >>>", fontWeight = FontWeight.Black)
                                    }
                                }

                                2 -> {
                                    Text(
                                        text = "Delegates event signing & NIP-04/NIP-44 encryption to an external Android signer app (Amber / nostrsigner:) via NIP-55 intents.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = BreGray100
                                    )
                                    OutlinedTextField(
                                        value = nip55PkgInput,
                                        onValueChange = { nip55PkgInput = it },
                                        label = { Text("Android Signer Package (optional)") },
                                        singleLine = true,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("nip55_package_input")
                                    )
                                    Button(
                                        onClick = {
                                            onConnectNip55Signer(labelInput, nip55PkgInput.trim())
                                            labelInput = ""
                                        },
                                        enabled = !isWorking,
                                        colors = ButtonDefaults.buttonColors(containerColor = BreDeepPink, contentColor = BreBlack),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(48.dp)
                                            .testTag("connect_nip55_button")
                                    ) {
                                        Text("LINK NIP-55 ANDROID SIGNER >>>", fontWeight = FontWeight.Black)
                                    }
                                }

                                3 -> {
                                    Text(
                                        text = "Connects a remote NIP-46 Nostr Connect bunker URI (bunker://<pubkey>?relay=wss://...&secret=...). Private keys stay on your remote bunker.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = BreGray100
                                    )
                                    OutlinedTextField(
                                        value = secretOrUriInput,
                                        onValueChange = { secretOrUriInput = it },
                                        label = { Text("bunker://<remote-pubkey>?relay=wss://...") },
                                        singleLine = true,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("nip46_bunker_input")
                                    )
                                    Button(
                                        onClick = {
                                            if (secretOrUriInput.isNotBlank()) {
                                                onConnectNip46Bunker(secretOrUriInput.trim(), labelInput) {
                                                    secretOrUriInput = ""
                                                    labelInput = ""
                                                }
                                            }
                                        },
                                        enabled = !isWorking,
                                        colors = ButtonDefaults.buttonColors(containerColor = BreDeepPink, contentColor = BreBlack),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(48.dp)
                                            .testTag("connect_nip46_button")
                                    ) {
                                        Text("CONNECT NIP-46 BUNKER >>>", fontWeight = FontWeight.Black)
                                    }
                                }

                                4 -> {
                                    Text(
                                        text = "Adds a read-only npub1 account for browsing, verifying, and forking Nsites without signing.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = BreGray100
                                    )
                                    OutlinedTextField(
                                        value = secretOrUriInput,
                                        onValueChange = { secretOrUriInput = it },
                                        label = { Text("npub1... or 64-char hex pubkey") },
                                        singleLine = true,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("readonly_npub_input")
                                    )
                                    Button(
                                        onClick = {
                                            if (secretOrUriInput.isNotBlank()) {
                                                onAddReadOnlyNpub(secretOrUriInput.trim(), labelInput)
                                                secretOrUriInput = ""
                                                labelInput = ""
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = BreDeepPink, contentColor = BreBlack),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(48.dp)
                                            .testTag("connect_readonly_npub_button")
                                    ) {
                                        Text("ADD READ-ONLY NPUB >>>", fontWeight = FontWeight.Black)
                                    }
                                }
                            }
                        }
                    }
                }

                // SAVED ACCOUNTS LIST
                items(accounts, key = { it.pubkeyHex }) { acct ->
                    val isCurrent = acct.pubkeyHex == activeAccount?.pubkeyHex
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (isCurrent) BrePinkDark3 else ObsidianSurface
                        ),
                        border = BorderStroke(1.dp, if (isCurrent) BreDeepPink else ObsidianBorder),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSwitchAccount(acct.pubkeyHex) }
                            .testTag("account_row_${acct.pubkeyHex.take(8)}")
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            AccountAvatarThumbnail(
                                account = acct,
                                sizeDp = 40,
                                contentDescription = acct.displayName
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = acct.displayName,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = ZineBoneCream,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "${acct.resolvedSignerType.label} • ${acct.npub.take(14)}…${acct.npub.takeLast(5)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = if (isCurrent) BreDeepPink else BreGray100
                                )
                            }
                            if (isCurrent) {
                                Icon(Icons.Default.CheckCircle, contentDescription = "Active", tint = BreDeepPink)
                            }
                        }
                    }
                }
            }

            1 -> {
                if (policies.isEmpty()) {
                    item {
                        Text(
                            text = "No remembered NIP-07 / Nsite signing policies yet. Every Nsite signature request will prompt for explicit confirmation.",
                            style = MaterialTheme.typography.bodySmall,
                            color = BreGray100
                        )
                    }
                } else {
                    items(policies, key = { it.policyKey }) { pol ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = ObsidianSurface),
                            border = BorderStroke(1.dp, ObsidianBorder),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(pol.appletTitle, fontWeight = FontWeight.Bold, color = ZineBoneCream)
                                    Text(
                                        "${pol.method} (Kind ${pol.eventKind})",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = BreGray100
                                    )
                                }
                                IconButton(onClick = { onRevokePolicy(pol.policyKey) }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Revoke Policy", tint = CoralAlert)
                                }
                            }
                        }
                    }
                }
            }

            2 -> {
                if (auditLog.isEmpty()) {
                    item {
                        Text(
                            text = "No cryptographic operations recorded yet.",
                            style = MaterialTheme.typography.bodySmall,
                            color = BreGray100
                        )
                    }
                } else {
                    items(auditLog, key = { it.id }) { entry ->
                        val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }
                        Card(
                            colors = CardDefaults.cardColors(containerColor = ObsidianSurface),
                            border = BorderStroke(1.dp, ObsidianBorder),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(entry.appletTitle, fontWeight = FontWeight.Bold, color = ZineBoneCream, fontSize = 13.sp)
                                    Text(
                                        text = if (entry.approved) "SIGNED" else "REJECTED",
                                        color = if (entry.approved) CyberMint else CoralAlert,
                                        fontWeight = FontWeight.Black,
                                        fontSize = 11.sp
                                    )
                                }
                                Text(
                                    text = "${entry.protocolLabel} • ${entry.method} (Kind ${entry.eventKind}) • ${fmt.format(Date(entry.timestamp))}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = BreDeepPink
                                )
                                Text(
                                    text = entry.summary,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = BreGray100,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun WallpaperPersonalizationScreen(
    prefs: LauncherPrefs,
    onSelectPreset: (WallpaperStyle) -> Unit,
    onPickCustomImage: (Uri) -> Unit,
    onUpdateScrim: (Float) -> Unit,
    onUpdateBlossomServer: (String) -> Unit,
    onToggleOpenNsitesExternally: (Boolean) -> Unit,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)

    var blossomInput by remember(prefs.primaryBlossomServer) {
        mutableStateOf(prefs.primaryBlossomServer)
    }

    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            onPickCustomImage(uri)
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(BreBlack)
            .windowInsetsPadding(WindowInsets.statusBars)
            .testTag("wallpaper_settings_screen"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                IconButton(onClick = onBack, modifier = Modifier.testTag("settings_back_btn")) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = ZineBoneCream)
                }
                Text(
                    text = "SHELL SETTINGS & THEME",
                    style = MaterialTheme.typography.titleLarge,
                    color = ZineBoneCream,
                    fontWeight = FontWeight.Black
                )
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = ObsidianSurface),
                border = BorderStroke(1.dp, ObsidianBorder)
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("WALLPAPER PRESET", color = BreDeepPink, fontWeight = FontWeight.Black)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        WallpaperStyle.entries.filter { it != WallpaperStyle.CUSTOM_IMAGE }.forEach { style ->
                            FilterChip(
                                selected = prefs.wallpaperStyle == style,
                                onClick = { onSelectPreset(style) },
                                label = { Text(style.name, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                            )
                        }
                    }
                    OutlinedButton(
                        onClick = {
                            photoPicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        border = BorderStroke(1.dp, BreDeepPink),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = ZineBoneCream)
                    ) {
                        Text("Pick Custom Wallpaper Image", fontWeight = FontWeight.Bold)
                    }
                    Text("Wallpaper Contrast Scrim: ${(prefs.scrimDimAlpha * 100).toInt()}%", color = BreGray100)
                    Slider(
                        value = prefs.scrimDimAlpha,
                        onValueChange = onUpdateScrim,
                        valueRange = 0.1f..0.95f
                    )
                }
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = ObsidianSurface),
                border = BorderStroke(1.dp, ObsidianBorder)
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("PRIMARY BLOSSOM SERVER", color = BreDeepPink, fontWeight = FontWeight.Black)
                    OutlinedTextField(
                        value = blossomInput,
                        onValueChange = { blossomInput = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = { onUpdateBlossomServer(blossomInput) },
                        colors = ButtonDefaults.buttonColors(containerColor = BreDeepPink, contentColor = BreBlack)
                    ) {
                        Text("Save Primary Blossom Server", fontWeight = FontWeight.Black)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Launch Nsites in External Browser by default",
                            color = ZineBoneCream,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                        Switch(
                            checked = prefs.openNsitesExternally,
                            onCheckedChange = onToggleOpenNsitesExternally
                        )
                    }
                }
            }
        }
    }
}
