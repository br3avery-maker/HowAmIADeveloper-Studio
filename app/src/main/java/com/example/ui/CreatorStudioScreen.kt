package com.example.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.GraphicTemplatePreset
import androidx.compose.runtime.remember
import com.example.data.InstalledAppletEntity
import com.example.data.PublishDeploymentReport
import com.example.nostr.NostrKinds
import com.example.ui.theme.BreBlack
import com.example.ui.theme.BreGray850
import com.example.ui.theme.BreLightPink
import com.example.ui.theme.BrePinkDark2
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.ZineBoneCream
import org.json.JSONObject

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun CreatorStudioScreen(
    draft: StudioDraftState,
    blossomServer: String,
    isWorking: Boolean,
    latestReport: PublishDeploymentReport?,
    onUpdateDraft: ((StudioDraftState) -> StudioDraftState) -> Unit,
    onApplyTemplate: (GraphicTemplatePreset) -> Unit,
    onPreviewBridgeCall: suspend (String, String, JSONObject) -> Any?,
    onPublishDraft: () -> Unit,
    onCopiedUrl: () -> Unit,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)

    var editorTab by rememberSaveable { mutableIntStateOf(0) }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(BreBlack)
            .windowInsetsPadding(WindowInsets.statusBars)
            .windowInsetsPadding(WindowInsets.navigationBars)
    ) {
        val showSplitPreview = maxWidth >= 820.dp
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = if (showSplitPreview) 1120.dp else 740.dp)
                .align(Alignment.TopCenter)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("studio_back_button")
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
                        text = "Creator Studio & Nsite Publisher",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = "Blossom: $blossomServer",
                        style = MaterialTheme.typography.labelSmall,
                        color = BreLightPink
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 1-Tap Graphic Template Selector
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = BreGray850,
                border = BorderStroke(1.dp, BrePinkDark2),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "LOAD GRAPHIC DESIGN TEMPLATE",
                        style = MaterialTheme.typography.labelSmall,
                        color = ZineBoneCream
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        GraphicTemplatePreset.entries.forEach { preset ->
                            AssistChip(
                                onClick = { onApplyTemplate(preset) },
                                label = {
                                    Text(
                                        text = "${preset.badge} • ${preset.label}",
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Manifest Target Kind Selector
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = draft.targetKind == NostrKinds.NAPPLET_NAMED,
                    onClick = { onUpdateDraft { it.copy(targetKind = NostrKinds.NAPPLET_NAMED) } },
                    label = { Text("Napplet (Kind 35129)") }
                )
                FilterChip(
                    selected = draft.targetKind == NostrKinds.NSITE_NAMED,
                    onClick = { onUpdateDraft { it.copy(targetKind = NostrKinds.NSITE_NAMED) } },
                    label = { Text("Named Nsite (Kind 35128)") }
                )
                FilterChip(
                    selected = draft.targetKind == NostrKinds.NSITE_ROOT,
                    onClick = { onUpdateDraft { it.copy(targetKind = NostrKinds.NSITE_ROOT) } },
                    label = { Text("Root Nsite (Kind 15128)") }
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = draft.title,
                    onValueChange = { v -> onUpdateDraft { it.copy(title = v) } },
                    label = { Text("Title") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("studio_title_input")
                )
                if (draft.targetKind != NostrKinds.NSITE_ROOT) {
                    OutlinedTextField(
                        value = draft.dTag,
                        onValueChange = { v -> onUpdateDraft { it.copy(dTag = v) } },
                        label = { Text("d-tag (slug)") },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .width(145.dp)
                            .testTag("studio_dtag_input")
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            OutlinedTextField(
                value = draft.description,
                onValueChange = { v -> onUpdateDraft { it.copy(description = v) } },
                label = { Text("Manifest Description") },
                maxLines = 2,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(12.dp))

            SecondaryTabRow(
                selectedTabIndex = editorTab,
                containerColor = BreGray850,
                modifier = Modifier.clip(RoundedCornerShape(14.dp))
            ) {
                Tab(selected = editorTab == 0, onClick = { editorTab = 0 }, text = { Text("index.html") })
                Tab(selected = editorTab == 1, onClick = { editorTab = 1 }, text = { Text("style.css") })
                Tab(selected = editorTab == 2, onClick = { editorTab = 2 }, text = { Text("app.js") })
                Tab(
                    selected = editorTab == 3,
                    onClick = { editorTab = 3 },
                    text = { Text("Live Preview") },
                    modifier = Modifier.testTag("studio_live_preview_tab")
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (editorTab == 3) {
                StudioLiveBridgePreviewPane(
                    draft = draft,
                    onPreviewBridgeCall = onPreviewBridgeCall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(340.dp)
                )
            } else {
                val activeCode = when (editorTab) {
                    0 -> draft.html
                    1 -> draft.css
                    else -> draft.js
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = activeCode,
                        onValueChange = { code ->
                            onUpdateDraft {
                                when (editorTab) {
                                    0 -> it.copy(html = code)
                                    1 -> it.copy(css = code)
                                    else -> it.copy(js = code)
                                }
                            }
                        },
                        textStyle = TextStyle(
                            fontFamily = JetBrainsMonoFontFamily,
                            fontSize = 12.sp,
                            lineHeight = 18.sp
                        ),
                        minLines = 9,
                        maxLines = 15,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("studio_code_editor")
                    )

                    if (showSplitPreview) {
                        StudioLiveBridgePreviewPane(
                            draft = draft,
                            onPreviewBridgeCall = onPreviewBridgeCall,
                            modifier = Modifier
                                .weight(1f)
                                .height(300.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Button(
                onClick = onPublishDraft,
                enabled = !isWorking,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("studio_publish_button")
            ) {
                if (isWorking) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                } else {
                    Icon(imageVector = Icons.Default.CloudUpload, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(text = "Hash Blobs, Sign Kind ${draft.targetKind} & Publish →")
            }

            if (latestReport != null) {
                Spacer(modifier = Modifier.height(16.dp))
                DeploymentReceiptCard(report = latestReport, onCopied = onCopiedUrl)
            }
        }
    }
}

@Composable
private fun StudioLiveBridgePreviewPane(
    draft: StudioDraftState,
    onPreviewBridgeCall: suspend (String, String, JSONObject) -> Any?,
    modifier: Modifier = Modifier
) {
    val assembledDoc = remember(draft.html, draft.css, draft.js) {
        val raw = buildString {
            append("<!doctype html><html><head><meta charset='utf-8'>")
            append("<meta name='viewport' content='width=device-width,initial-scale=1'>")
            append("<style>\n")
            append(draft.css)
            append("\n</style></head><body>\n")
            append(draft.html)
            append("\n<script>\n")
            append(draft.js)
            append("\n</script></body></html>")
        }
        injectRuntimeBootstrap(raw, enableNip07 = false)
    }

    val previewApplet = remember(draft.title, draft.dTag, draft.targetKind, draft.description, draft.iconGlyph) {
        InstalledAppletEntity(
            appletId = "studio:preview:${draft.dTag.ifBlank { "draft" }}",
            kind = draft.targetKind,
            authorPubkey = "",
            authorNpub = "",
            dTag = draft.dTag.ifBlank { "draft" },
            title = draft.title.ifBlank { "Studio Preview" },
            description = draft.description,
            iconGlyph = draft.iconGlyph,
            accentHex = "#FF1493",
            isNsite = draft.targetKind != NostrKinds.NAPPLET_NAMED,
            inlineHtml = draft.html,
            inlineCss = draft.css,
            inlineJs = draft.js
        )
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = BreBlack,
        border = BorderStroke(1.dp, BrePinkDark2),
        modifier = modifier.testTag("studio_live_preview_webview")
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Surface(color = BreGray850, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "LIVE NIP-5D SANDBOX PREVIEW • NAP-SIGNER / NAP-RELAY / NAP-STORAGE",
                    style = MaterialTheme.typography.labelSmall,
                    color = BreLightPink,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
            SandboxedAppletSurface(
                applet = previewApplet,
                rawHtml = draft.html,
                assembledWebDoc = assembledDoc,
                onBridgeCall = { _, proto, type, payload ->
                    onPreviewBridgeCall(proto, type, payload)
                },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SelfPublishShellScreen(
    blossomServer: String,
    pinnedCount: Int,
    isWorking: Boolean,
    latestReport: PublishDeploymentReport?,
    onExecuteSelfPublish: (asRootNsite: Boolean, dTag: String, preset: GraphicTemplatePreset) -> Unit,
    onCopiedUrl: () -> Unit,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)

    var publishAsRoot by rememberSaveable { mutableStateOf(false) }
    var customSlug by rememberSaveable { mutableStateOf("shell") }
    var selectedPreset by rememberSaveable { mutableStateOf(GraphicTemplatePreset.BREBRE_SPECIMEN) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BreBlack)
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
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("self_publish_back_button")
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
                        text = "Self-Publish SovereignShell",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = "NIP-5A Static Web Clone • Blossom BUD-02 + Kind 24242 Auth",
                        style = MaterialTheme.typography.labelSmall,
                        color = BreLightPink
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Surface(
                shape = RoundedCornerShape(22.dp),
                color = BreGray850,
                border = BorderStroke(1.dp, BrePinkDark2),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text(
                        text = "Reproduce This Launcher on the Sovereign Web",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Compiles a standalone HTML/CSS/JS web shell containing your Agent Zone profile medallion, relay list, NIP-01 note composer, and $pinnedCount pinned Napplets/Nsites, uploads content-addressed SHA-256 blobs to $blossomServer, and signs a NIP-5A manifest.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    Text(
                        text = "SELECT WEB SHELL GRAPHIC THEME",
                        style = MaterialTheme.typography.labelSmall,
                        color = ZineBoneCream
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        GraphicTemplatePreset.entries.forEach { preset ->
                            FilterChip(
                                selected = selectedPreset == preset,
                                onClick = { selectedPreset = preset },
                                label = { Text(preset.label) }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Publish as Root Nsite (Kind 15128)",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Disable to publish under a named subdomain d-tag (Kind 35128).",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = publishAsRoot,
                            onCheckedChange = { publishAsRoot = it }
                        )
                    }

                    if (!publishAsRoot) {
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = customSlug,
                            onValueChange = { customSlug = it },
                            label = { Text("Named Nsite Subdomain (d-tag)") },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    Button(
                        onClick = { onExecuteSelfPublish(publishAsRoot, customSlug, selectedPreset) },
                        enabled = !isWorking,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp)
                            .testTag("execute_self_publish_button")
                    ) {
                        if (isWorking) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                        } else {
                            Icon(imageVector = Icons.Default.RocketLaunch, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(text = "Self-Publish ${selectedPreset.badge} Shell →")
                    }
                }
            }

            if (latestReport != null) {
                Spacer(modifier = Modifier.height(18.dp))
                DeploymentReceiptCard(report = latestReport, onCopied = onCopiedUrl)
            }
        }
    }
}

@Composable
private fun DeploymentReceiptCard(
    report: PublishDeploymentReport,
    onCopied: () -> Unit
) {
    val clipboard = LocalClipboardManager.current

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = BreGray850,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("deployment_receipt_card")
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "☆ PUBLISHED NIP-5A / NIP-5D MANIFEST ☆",
                style = MaterialTheme.typography.labelSmall,
                color = BreLightPink
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "${report.title} (Kind ${report.kind})",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "Event ID: ${report.manifestEventId.take(22)}… • Relays: ${report.acceptedRelays}/${report.totalRelays}",
                style = MaterialTheme.typography.labelSmall,
                color = ZineBoneCream
            )

            Spacer(modifier = Modifier.height(10.dp))

            report.uploadedFiles.forEach { blob ->
                Text(
                    text = "SHA-256: ${blob.sha256.take(20)}… (${blob.statusDetail})",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Surface(
                shape = RoundedCornerShape(12.dp),
                color = BreBlack,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = report.gatewayPreviewUrl,
                        style = MaterialTheme.typography.labelMedium,
                        color = BreLightPink,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(report.gatewayPreviewUrl))
                            onCopied()
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "Copy Nsite Gateway URL",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}
