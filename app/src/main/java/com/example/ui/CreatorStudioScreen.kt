package com.example.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.InstalledAppletEntity
import com.example.data.NsiteProjectEntity
import com.example.data.NsiteProjectFileEntity
import com.example.data.PublishDeploymentReport
import com.example.data.PublishVerificationBreakdown
import com.example.data.ResolvedWebAsset
import com.example.data.VaultAccountEntity
import com.example.nostr.DeliveryVerificationStage
import com.example.nostr.ExternalSiteLauncher
import com.example.nostr.NostrKinds
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
import com.example.ui.theme.ObsidianElevated
import com.example.ui.theme.ZineBoneCream
import org.json.JSONObject

private enum class StudioWorkspacePane {
    FILES_CODE,
    LIVE_PREVIEW,
    DEPLOY_VERIFY
}

/**
 * Multi-File NIP-5A Nsite Editor, Forker & Publisher styled after the Deepink "LAUNCH" screen:
 * - Supports all 4 required input sources:
 *   1. Single HTML file
 *   2. Multi-file ZIP archive
 *   3. Folder (`OpenDocumentTree`)
 *   4. Existing published NIP-5A Nsite (Fork)
 * - Multi-file tree & asset manager, monospace code editor, real WebView live preview,
 *   and verified Blossom + Relay publication under the active identity without ever
 *   overwriting or impersonating an original fork publisher.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CreatorStudioScreen(
    activeAccount: VaultAccountEntity?,
    projects: List<NsiteProjectEntity>,
    activeProject: NsiteProjectEntity?,
    projectFiles: List<NsiteProjectFileEntity>,
    selectedFilePath: String,
    blossomServer: String,
    isWorking: Boolean,
    latestReport: PublishDeploymentReport?,
    latestVerification: PublishVerificationBreakdown?,
    onSelectProject: (String) -> Unit,
    onCreateScratchProject: (String, String, Int) -> Unit,
    onDeleteProject: (String) -> Unit,
    onImportHtmlUri: (Uri) -> Unit,
    onImportZipUri: (Uri) -> Unit,
    onImportFolderUri: (Uri) -> Unit,
    onForkByAddress: (String) -> Unit,
    onSelectFile: (String) -> Unit,
    onSaveProjectMetadata: (String, String, Int, String, String) -> Unit,
    onSaveTextFile: (String, String, Boolean) -> Unit,
    onCreateNewFile: (String, String) -> Unit,
    onImportAssetFile: (String, Uri) -> Unit,
    onRenameFile: (String, String) -> Unit,
    onDeleteFile: (String) -> Unit,
    resolvePreviewAsset: suspend (String, String, Boolean, (String) -> String) -> ResolvedWebAsset?,
    onPreviewBridgeCall: suspend (String, String, JSONObject) -> Any?,
    onPublishProject: () -> Unit,
    onCopiedUrl: () -> Unit,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)

    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    var activePane by rememberSaveable { mutableStateOf(StudioWorkspacePane.FILES_CODE) }
    var projectMenuExpanded by remember { mutableStateOf(false) }
    var showNewProjectDialog by remember { mutableStateOf(false) }
    var showNewFileDialog by remember { mutableStateOf(false) }
    var showForkAddressDialog by remember { mutableStateOf(false) }
    var previewReloadNonce by remember { mutableIntStateOf(0) }

    // Metadata buffers synced to activeProject
    var titleBuf by rememberSaveable(activeProject?.projectId) {
        mutableStateOf(activeProject?.title ?: "My Sovereign Nsite")
    }
    var dTagBuf by rememberSaveable(activeProject?.projectId) {
        mutableStateOf(activeProject?.dTag ?: "my-nsite")
    }
    var descBuf by rememberSaveable(activeProject?.projectId) {
        mutableStateOf(activeProject?.description ?: "")
    }
    var entryPathBuf by rememberSaveable(activeProject?.projectId) {
        mutableStateOf(activeProject?.entryPath ?: "/index.html")
    }
    var targetKindBuf by rememberSaveable(activeProject?.projectId) {
        mutableIntStateOf(activeProject?.targetKind ?: NostrKinds.NSITE_NAMED)
    }

    LaunchedEffect(activeProject?.projectId, activeProject?.updatedAt) {
        if (activeProject != null) {
            titleBuf = activeProject.title
            dTagBuf = activeProject.dTag
            descBuf = activeProject.description
            entryPathBuf = activeProject.entryPath
            targetKindBuf = activeProject.targetKind
        }
    }

    val currentFileEntity = remember(projectFiles, selectedFilePath) {
        projectFiles.firstOrNull { it.relativePath == selectedFilePath }
            ?: projectFiles.firstOrNull()
    }

    // Unsaved editor buffer preserved across Editor <-> Live Preview switches!
    var editorTextBuf by rememberSaveable(activeProject?.projectId, currentFileEntity?.relativePath) {
        mutableStateOf(currentFileEntity?.textContent.orEmpty())
    }
    var isEditorDirty by rememberSaveable(activeProject?.projectId, currentFileEntity?.relativePath) {
        mutableStateOf(false)
    }

    LaunchedEffect(currentFileEntity?.relativePath, currentFileEntity?.sha256) {
        if (currentFileEntity != null && !isEditorDirty) {
            editorTextBuf = currentFileEntity.textContent
        }
    }

    // Android Storage Access Framework Launchers for the 4 Input Sources + Asset Replacement
    val htmlFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri -> if (uri != null) onImportHtmlUri(uri) }

    val zipFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri -> if (uri != null) onImportZipUri(uri) }

    val folderTreeLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri -> if (treeUri != null) onImportFolderUri(treeUri) }

    var pendingAssetTargetPath by remember { mutableStateOf("/assets/") }
    val assetImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            onImportAssetFile(pendingAssetTargetPath, uri)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BreBlack)
            .windowInsetsPadding(WindowInsets.statusBars)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 880.dp)
                .align(Alignment.TopCenter)
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            // 1. Deepink "LAUNCH" Header + Project Switcher
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("studio_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = ZineBoneCream
                        )
                    }
                    Column {
                        Text(
                            text = "LAUNCH STUDIO",
                            style = MaterialTheme.typography.headlineSmall.copy(
                                fontWeight = FontWeight.Black,
                                letterSpacing = 1.2.sp
                            ),
                            color = ZineBoneCream
                        )
                        Text(
                            text = activeAccount?.let { "Publishing as ${it.displayName.ifBlank { it.npub.take(12) }}" }
                                ?: "Select a signing identity",
                            style = MaterialTheme.typography.labelSmall,
                            color = BreLightPink
                        )
                    }
                }

                // Project Switcher Pill + New Project Button
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box {
                        Surface(
                            onClick = { projectMenuExpanded = true },
                            shape = RoundedCornerShape(10.dp),
                            color = BreGray850,
                            border = BorderStroke(1.dp, BrePinkDark2),
                            modifier = Modifier.testTag("studio_project_switcher")
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    text = activeProject?.title?.take(16) ?: "Project",
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                    color = BreWhite,
                                    maxLines = 1
                                )
                                Text(
                                    text = " (${projectFiles.size}f) ▾",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = ZineBoneCream
                                )
                            }
                        }

                        DropdownMenu(
                            expanded = projectMenuExpanded,
                            onDismissRequest = { projectMenuExpanded = false }
                        ) {
                            projects.forEach { proj ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = "${proj.title} (${proj.sourceType})",
                                            fontWeight = if (proj.projectId == activeProject?.projectId) FontWeight.Bold else FontWeight.Normal
                                        )
                                    },
                                    onClick = {
                                        projectMenuExpanded = false
                                        onSelectProject(proj.projectId)
                                    }
                                )
                            }
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("+ New Scratch Nsite", color = BreDeepPink) },
                                onClick = {
                                    projectMenuExpanded = false
                                    showNewProjectDialog = true
                                }
                            )
                            if (activeProject != null && projects.size > 1) {
                                DropdownMenuItem(
                                    text = { Text("Delete Current Project", color = CoralAlert) },
                                    onClick = {
                                        projectMenuExpanded = false
                                        onDeleteProject(activeProject.projectId)
                                    }
                                )
                            }
                        }
                    }

                    IconButton(
                        onClick = { showNewProjectDialog = true },
                        modifier = Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(BreDeepPink)
                            .testTag("studio_new_project_button")
                    ) {
                        Icon(imageVector = Icons.Default.Add, contentDescription = "New Project", tint = BreWhite)
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 2. Deepink Bone-Cream Taped Input Source Cards (HTML | ZIP | FOLDER | FORK)
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                DeepinkSourceCard(
                    icon = Icons.Default.Description,
                    label = "HTML",
                    testTag = "import_html_button",
                    onClick = { htmlFileLauncher.launch("text/html") },
                    modifier = Modifier.weight(1f)
                )
                DeepinkSourceCard(
                    icon = Icons.Default.Archive,
                    label = "ZIP",
                    testTag = "import_zip_button",
                    onClick = { zipFileLauncher.launch("application/zip") },
                    modifier = Modifier.weight(1f)
                )
                DeepinkSourceCard(
                    icon = Icons.Default.FolderOpen,
                    label = "FOLDER",
                    testTag = "import_folder_button",
                    onClick = { folderTreeLauncher.launch(null) },
                    modifier = Modifier.weight(1f)
                )
                DeepinkSourceCard(
                    icon = Icons.AutoMirrored.Filled.CallSplit,
                    label = "FORK URL",
                    testTag = "fork_url_button",
                    onClick = { showForkAddressDialog = true },
                    modifier = Modifier.weight(1f)
                )
            }

            // 3. Fork Provenance & Incomplete Import Integrity Warnings (if applicable)
            if (activeProject?.isFork == true) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = BreGray850,
                    border = BorderStroke(1.dp, BrePinkDark2),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("studio_fork_provenance_card")
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.CallSplit,
                            contentDescription = null,
                            tint = BreDeepPink,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "FORK PROVENANCE PRESERVED",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Black),
                                color = ZineBoneCream
                            )
                            Text(
                                text = "Forked from ${activeProject.forkSourceNpub.take(16)}… (d:${activeProject.forkSourceDTag}). Publishes as a new site under your identity.",
                                style = MaterialTheme.typography.bodySmall,
                                color = BreGray100
                            )
                        }
                    }
                }
            }

            if (!activeProject?.incompleteImportWarning.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = BreGray900,
                    border = BorderStroke(1.dp, CoralAlert),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("studio_incomplete_import_banner")
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.WarningAmber,
                            contentDescription = null,
                            tint = CoralAlert,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = activeProject?.incompleteImportWarning.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = CoralAlert
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 4. 3-Pane Workspace Switcher (FILES & CODE | LIVE PREVIEW | DEPLOY)
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = BreGray900,
                border = BorderStroke(1.dp, ObsidianBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    StudioPaneTab(
                        selected = activePane == StudioWorkspacePane.FILES_CODE,
                        label = "FILES & CODE (${projectFiles.size})",
                        testTag = "studio_tab_code",
                        onClick = { activePane = StudioWorkspacePane.FILES_CODE },
                        modifier = Modifier.weight(1f)
                    )
                    StudioPaneTab(
                        selected = activePane == StudioWorkspacePane.LIVE_PREVIEW,
                        label = "LIVE PREVIEW",
                        testTag = "studio_tab_preview",
                        onClick = {
                            // Auto-persist any unsaved text buffer before entering Live Preview!
                            if (isEditorDirty && currentFileEntity != null && !currentFileEntity.isBinary) {
                                onSaveTextFile(currentFileEntity.relativePath, editorTextBuf, false)
                                isEditorDirty = false
                            }
                            previewReloadNonce++
                            activePane = StudioWorkspacePane.LIVE_PREVIEW
                        },
                        modifier = Modifier.weight(1f)
                    )
                    StudioPaneTab(
                        selected = activePane == StudioWorkspacePane.DEPLOY_VERIFY,
                        label = "DEPLOY & VERIFY",
                        testTag = "studio_tab_deploy",
                        onClick = {
                            if (isEditorDirty && currentFileEntity != null && !currentFileEntity.isBinary) {
                                onSaveTextFile(currentFileEntity.relativePath, editorTextBuf, false)
                                isEditorDirty = false
                            }
                            activePane = StudioWorkspacePane.DEPLOY_VERIFY
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 5. Active Workspace Content
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (activePane) {
                    StudioWorkspacePane.FILES_CODE -> {
                        Column(modifier = Modifier.fillMaxSize()) {
                            // Horizontal Scrollable Multi-File Tree + Add File / Import Asset
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState())
                                    .testTag("studio_file_tree_row")
                            ) {
                                projectFiles.forEach { file ->
                                    val isSelected = file.relativePath == currentFileEntity?.relativePath
                                    Surface(
                                        onClick = {
                                            if (isEditorDirty && currentFileEntity != null && !currentFileEntity.isBinary) {
                                                onSaveTextFile(currentFileEntity.relativePath, editorTextBuf, false)
                                                isEditorDirty = false
                                            }
                                            onSelectFile(file.relativePath)
                                        },
                                        shape = RoundedCornerShape(8.dp),
                                        color = if (isSelected) BreDeepPink else BreGray850,
                                        border = BorderStroke(
                                            1.dp,
                                            if (isSelected) ZineBoneCream else ObsidianBorder
                                        ),
                                        modifier = Modifier.testTag("file_chip_${file.relativePath}")
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                        ) {
                                            Text(
                                                text = file.relativePath,
                                                fontFamily = JetBrainsMonoFontFamily,
                                                fontSize = 11.sp,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                color = BreWhite
                                            )
                                            if (file.relativePath == activeProject?.entryPath) {
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text(
                                                    text = "★",
                                                    fontSize = 10.sp,
                                                    color = ZineBoneCream
                                                )
                                            }
                                        }
                                    }
                                }

                                OutlinedButton(
                                    onClick = { showNewFileDialog = true },
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier
                                        .height(32.dp)
                                        .testTag("studio_add_file_button")
                                ) {
                                    Icon(imageVector = Icons.Default.CreateNewFolder, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("+ File", fontSize = 11.sp)
                                }

                                OutlinedButton(
                                    onClick = {
                                        pendingAssetTargetPath = "/assets/"
                                        assetImportLauncher.launch("*/*")
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier
                                        .height(32.dp)
                                        .testTag("studio_import_asset_button")
                                ) {
                                    Icon(imageVector = Icons.Default.UploadFile, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("+ Asset", fontSize = 11.sp)
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            // Active File Toolbar & Editor Surface
                            if (currentFileEntity != null) {
                                Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = BreGray900,
                                    border = BorderStroke(1.dp, BrePinkDark2),
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.fillMaxSize()) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .background(BreGray850)
                                                .padding(horizontal = 12.dp, vertical = 6.dp)
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = currentFileEntity.relativePath + if (isEditorDirty) " ●" else "",
                                                    fontFamily = JetBrainsMonoFontFamily,
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = ZineBoneCream
                                                )
                                                Text(
                                                    text = "${currentFileEntity.mimeType} • ${currentFileEntity.sizeBytes} B",
                                                    fontFamily = JetBrainsMonoFontFamily,
                                                    fontSize = 10.sp,
                                                    color = BreGray600
                                                )
                                            }

                                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                if (!currentFileEntity.isBinary) {
                                                    OutlinedButton(
                                                        onClick = {
                                                            onSaveTextFile(currentFileEntity.relativePath, editorTextBuf, true)
                                                            isEditorDirty = false
                                                        },
                                                        shape = RoundedCornerShape(8.dp),
                                                        modifier = Modifier
                                                            .height(32.dp)
                                                            .testTag("studio_save_file_button")
                                                    ) {
                                                        Icon(imageVector = Icons.Default.Save, contentDescription = null, modifier = Modifier.size(14.dp))
                                                        Spacer(modifier = Modifier.width(4.dp))
                                                        Text("Save", fontSize = 11.sp)
                                                    }
                                                } else {
                                                    OutlinedButton(
                                                        onClick = {
                                                            pendingAssetTargetPath = currentFileEntity.relativePath
                                                            assetImportLauncher.launch("*/*")
                                                        },
                                                        shape = RoundedCornerShape(8.dp),
                                                        modifier = Modifier.height(32.dp)
                                                    ) {
                                                        Text("Replace Asset", fontSize = 11.sp)
                                                    }
                                                }

                                                if (projectFiles.size > 1) {
                                                    IconButton(
                                                        onClick = { onDeleteFile(currentFileEntity.relativePath) },
                                                        modifier = Modifier.size(32.dp)
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Default.DeleteOutline,
                                                            contentDescription = "Delete file",
                                                            tint = CoralAlert,
                                                            modifier = Modifier.size(16.dp)
                                                        )
                                                    }
                                                }
                                            }
                                        }

                                        if (currentFileEntity.isBinary) {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .padding(24.dp),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                    Text(
                                                        text = "BINARY ASSET (${currentFileEntity.mimeType})",
                                                        style = MaterialTheme.typography.titleSmall,
                                                        color = ZineBoneCream
                                                    )
                                                    Spacer(modifier = Modifier.height(6.dp))
                                                    Text(
                                                        text = "Size: ${currentFileEntity.sizeBytes} bytes • SHA-256: ${currentFileEntity.sha256.take(16)}…",
                                                        fontFamily = JetBrainsMonoFontFamily,
                                                        fontSize = 11.sp,
                                                        color = CyberMint
                                                    )
                                                }
                                            }
                                        } else {
                                            BasicTextField(
                                                value = editorTextBuf,
                                                onValueChange = {
                                                    editorTextBuf = it
                                                    isEditorDirty = true
                                                },
                                                textStyle = TextStyle(
                                                    fontFamily = JetBrainsMonoFontFamily,
                                                    fontSize = 12.sp,
                                                    lineHeight = 18.sp,
                                                    color = BreWhite
                                                ),
                                                cursorBrush = SolidColor(BreDeepPink),
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .verticalScroll(rememberScrollState())
                                                    .padding(12.dp)
                                                    .testTag("studio_code_editor_input")
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    StudioWorkspacePane.LIVE_PREVIEW -> {
                        val proj = activeProject
                        if (proj != null) {
                            val previewApplet = remember(proj.projectId, proj.title, proj.dTag, proj.updatedAt) {
                                InstalledAppletEntity(
                                    appletId = "studio:preview:${proj.projectId}",
                                    kind = proj.targetKind,
                                    authorPubkey = activeAccount?.pubkeyHex.orEmpty(),
                                    authorNpub = activeAccount?.npub.orEmpty(),
                                    dTag = proj.dTag,
                                    title = "${proj.title} (Preview)",
                                    description = proj.description,
                                    iconGlyph = "🌐",
                                    accentHex = "#FF1493",
                                    isNsite = true,
                                    enableNip07 = true
                                )
                            }
                            val entryHtml = projectFiles.firstOrNull { it.relativePath == proj.entryPath }?.textContent
                                ?: projectFiles.firstOrNull { it.relativePath == "/index.html" }?.textContent
                                ?: ""

                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = BreBlack,
                                border = BorderStroke(1.dp, BreDeepPink),
                                modifier = Modifier
                                    .fillMaxSize()
                                    .testTag("studio_live_preview_container")
                            ) {
                                SandboxedAppletSurface(
                                    applet = previewApplet,
                                    rawHtml = entryHtml,
                                    assembledWebDoc = injectRuntimeBootstrap(entryHtml, enableNip07 = true),
                                    entryPath = proj.entryPath,
                                    reloadKey = previewReloadNonce,
                                    resolveAsset = { path, isMainFrame ->
                                        resolvePreviewAsset(proj.projectId, path, isMainFrame) { rootHtml ->
                                            injectRuntimeBootstrap(rootHtml, enableNip07 = true)
                                        }
                                    },
                                    onBridgeCall = { _, proto, method, params ->
                                        onPreviewBridgeCall(proto, method, params)
                                    },
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                    }

                    StudioWorkspacePane.DEPLOY_VERIFY -> {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            // Site Metadata & Coordinate Card
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = ObsidianElevated,
                                border = BorderStroke(1.dp, ObsidianBorder),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Text(
                                        text = "SITE METADATA & NIP-5A COORDINATE",
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Black),
                                        color = ZineBoneCream
                                    )

                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        FilterChip(
                                            selected = targetKindBuf == NostrKinds.NSITE_NAMED,
                                            onClick = {
                                                targetKindBuf = NostrKinds.NSITE_NAMED
                                                onSaveProjectMetadata(titleBuf, dTagBuf, targetKindBuf, descBuf, entryPathBuf)
                                            },
                                            label = { Text("Named Site (Subdomain)") }
                                        )
                                        FilterChip(
                                            selected = targetKindBuf == NostrKinds.NSITE_ROOT,
                                            onClick = {
                                                targetKindBuf = NostrKinds.NSITE_ROOT
                                                onSaveProjectMetadata(titleBuf, "root", targetKindBuf, descBuf, entryPathBuf)
                                            },
                                            label = { Text("Root Profile Site") }
                                        )
                                    }

                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedTextField(
                                            value = titleBuf,
                                            onValueChange = {
                                                titleBuf = it
                                                onSaveProjectMetadata(it, dTagBuf, targetKindBuf, descBuf, entryPathBuf)
                                            },
                                            label = { Text("Website Title") },
                                            singleLine = true,
                                            shape = RoundedCornerShape(12.dp),
                                            modifier = Modifier
                                                .weight(1.3f)
                                                .testTag("studio_title_input")
                                        )
                                        if (targetKindBuf == NostrKinds.NSITE_NAMED) {
                                            OutlinedTextField(
                                                value = dTagBuf,
                                                onValueChange = {
                                                    dTagBuf = it
                                                    onSaveProjectMetadata(titleBuf, it, targetKindBuf, descBuf, entryPathBuf)
                                                },
                                                label = { Text("Slug (d-tag)") },
                                                singleLine = true,
                                                shape = RoundedCornerShape(12.dp),
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .testTag("studio_dtag_input")
                                            )
                                        }
                                    }

                                    OutlinedTextField(
                                        value = descBuf,
                                        onValueChange = {
                                            descBuf = it
                                            onSaveProjectMetadata(titleBuf, dTagBuf, targetKindBuf, it, entryPathBuf)
                                        },
                                        label = { Text("Description") },
                                        singleLine = true,
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    )

                                    Text(
                                        text = "Entry Point: ${activeProject?.entryPath ?: "/index.html"} • ${projectFiles.size} file(s) • Primary Blossom: $blossomServer",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = CyberMint
                                    )
                                }
                            }

                            // Deepink Distressed Magenta CTA Button ("DEPLOY NSITE >>>")
                            Button(
                                onClick = {
                                    if (isEditorDirty && currentFileEntity != null && !currentFileEntity.isBinary) {
                                        onSaveTextFile(currentFileEntity.relativePath, editorTextBuf, false)
                                        isEditorDirty = false
                                    }
                                    onPublishProject()
                                },
                                enabled = !isWorking && activeAccount?.canSign == true,
                                colors = ButtonDefaults.buttonColors(containerColor = BreDeepPink),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(54.dp)
                                    .testTag("studio_publish_button")
                            ) {
                                if (isWorking) {
                                    CircularProgressIndicator(
                                        color = BreWhite,
                                        strokeWidth = 2.dp,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text("UPLOADING TO BLOSSOM & VERIFYING RELAYS…", fontWeight = FontWeight.Black)
                                } else {
                                    Icon(imageVector = Icons.Default.CloudUpload, contentDescription = null)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = if ((activeProject?.versionCounter ?: 1) > 1) {
                                            "PUBLISH UPDATE (v${activeProject?.versionCounter}) >>>"
                                        } else {
                                            "CLAIM & PUBLISH NSITE >>>"
                                        },
                                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Black),
                                        color = BreWhite
                                    )
                                }
                            }

                            // 4-Stage Verification Receipt Card
                            if (latestReport != null && latestVerification != null) {
                                PublishVerificationReceiptCard(
                                    report = latestReport,
                                    verification = latestVerification,
                                    onCopyUrl = {
                                        clipboard.setText(AnnotatedString(latestReport.gatewayPreviewUrl))
                                        onCopiedUrl()
                                    },
                                    onLaunchBrowser = {
                                        ExternalSiteLauncher.launchCustomTabOrBrowser(context, latestReport.gatewayPreviewUrl)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showNewProjectDialog) {
        var newTitle by remember { mutableStateOf("") }
        var newSlug by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showNewProjectDialog = false },
            containerColor = BreGray850,
            title = { Text("Create New Multi-File Nsite", color = ZineBoneCream) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newTitle,
                        onValueChange = { newTitle = it },
                        label = { Text("Project Title") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = newSlug,
                        onValueChange = { newSlug = it },
                        label = { Text("Slug (1-13 chars, e.g. my-arcade)") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showNewProjectDialog = false
                        onCreateScratchProject(
                            newTitle.ifBlank { "Untitled Nsite" },
                            newSlug.ifBlank { newTitle.ifBlank { "site" } },
                            NostrKinds.NSITE_NAMED
                        )
                    }
                ) {
                    Text("Create", color = BreDeepPink, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showNewProjectDialog = false }) {
                    Text("Cancel", color = BreGray100)
                }
            }
        )
    }

    if (showNewFileDialog) {
        var newPath by remember { mutableStateOf("/page.html") }
        AlertDialog(
            onDismissRequest = { showNewFileDialog = false },
            containerColor = BreGray850,
            title = { Text("Add Project File", color = ZineBoneCream) },
            text = {
                OutlinedTextField(
                    value = newPath,
                    onValueChange = { newPath = it },
                    label = { Text("Relative Path (e.g. /css/theme.css or /js/util.js)") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showNewFileDialog = false
                        onCreateNewFile(newPath, "")
                    }
                ) {
                    Text("Create File", color = BreDeepPink, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showNewFileDialog = false }) {
                    Text("Cancel", color = BreGray100)
                }
            }
        )
    }

    if (showForkAddressDialog) {
        var forkAddress by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showForkAddressDialog = false },
            containerColor = BreGray850,
            title = { Text("Fork Published NIP-5A Nsite", color = ZineBoneCream) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Enter an nsite.lol URL, naddr1…, npub1…, or 35128:pubkey:slug coordinate. SovereignShell will verify its Blossom blobs and reconstruct the project tree.",
                        style = MaterialTheme.typography.bodySmall,
                        color = BreGray100
                    )
                    OutlinedTextField(
                        value = forkAddress,
                        onValueChange = { forkAddress = it },
                        label = { Text("Nsite URL, naddr1…, or coordinate") },
                        singleLine = true,
                        modifier = Modifier.testTag("fork_dialog_address_input")
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showForkAddressDialog = false
                        onForkByAddress(forkAddress)
                    },
                    modifier = Modifier.testTag("fork_dialog_confirm_button")
                ) {
                    Text("Fork Site >>>", color = BreDeepPink, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showForkAddressDialog = false }) {
                    Text("Cancel", color = BreGray100)
                }
            }
        )
    }
}

@Composable
private fun DeepinkSourceCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    testTag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = ZineBoneCream,
        border = BorderStroke(1.5.dp, BreDeepPink),
        modifier = modifier
            .height(58.dp)
            .testTag(testTag)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(4.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = BreBlack,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Black),
                color = BreBlack,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun StudioPaneTab(
    selected: Boolean,
    label: String,
    testTag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(9.dp),
        color = if (selected) BreDeepPink else BreBlack,
        modifier = modifier
            .height(36.dp)
            .testTag(testTag)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = if (selected) FontWeight.Black else FontWeight.SemiBold
                ),
                color = if (selected) BreWhite else BreGray100,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun PublishVerificationReceiptCard(
    report: PublishDeploymentReport,
    verification: PublishVerificationBreakdown,
    onCopyUrl: () -> Unit,
    onLaunchBrowser: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = BreGray850,
        border = BorderStroke(1.dp, CyberMint),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("studio_publish_receipt_card")
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = CyberMint,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "VERIFIED NIP-5A DEPLOYMENT",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Black),
                    color = CyberMint
                )
            }

            Text(
                text = "Blossom: ${verification.verifiedUploadedBlobs}/${verification.totalProjectFiles} files SHA-256 verified across ${verification.mirroredServerCount} server(s)",
                style = MaterialTheme.typography.bodySmall,
                color = BreWhite
            )
            Text(
                text = "Relays: ${verification.connectedRelays} connected • ${verification.acceptedRelays} accepted (OK) • ${verification.verifiedRetrievalRelays} verified read-back",
                style = MaterialTheme.typography.bodySmall,
                color = ZineBoneCream
            )

            Text(
                text = report.gatewayPreviewUrl,
                fontFamily = JetBrainsMonoFontFamily,
                fontSize = 11.sp,
                color = BreLightPink
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onLaunchBrowser,
                    colors = ButtonDefaults.buttonColors(containerColor = BreDeepPink),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(imageVector = Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Open Live Nsite", color = BreWhite)
                }
                OutlinedButton(
                    onClick = onCopyUrl,
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(imageVector = Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Copy URL")
                }
            }
        }
    }
}
