package com.example.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.VaultAccountEntity
import com.example.ui.theme.BreBlack
import com.example.ui.theme.BreGray850
import com.example.ui.theme.BrePinkDark2
import com.example.ui.theme.BreWhite
import com.example.ui.theme.ZineBoneCream
import kotlinx.coroutines.launch

private val PRESET_GLYPHS = listOf("≠", "☆", "⚡", "♥", "📺", "🐰")
private val PRESET_BADGE_COLORS = listOf(
    "#FF1493" to "Deep Pink",
    "#FFB6D9" to "Light Pink",
    "#E8DCC8" to "Bone Cream",
    "#CD0D72" to "Pink Dark",
    "#C0C0C0" to "Silver"
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UserProfileScreen(
    activeAccount: VaultAccountEntity?,
    isWorking: Boolean,
    onImportAvatar: suspend (android.net.Uri) -> String?,
    onSaveAndPublish: (
        displayName: String,
        bio: String,
        localAvatarPath: String,
        uploadToBlossom: Boolean,
        badgeGlyph: String,
        badgeColorHex: String
    ) -> Unit,
    onCopiedKey: () -> Unit,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)

    var displayName by rememberSaveable(activeAccount?.pubkeyHex) {
        mutableStateOf(activeAccount?.displayName.orEmpty())
    }
    var bio by rememberSaveable(activeAccount?.pubkeyHex) {
        mutableStateOf(activeAccount?.bio.orEmpty())
    }
    var avatarLocalPath by rememberSaveable(activeAccount?.pubkeyHex) {
        mutableStateOf(activeAccount?.localAvatarPath.orEmpty())
    }
    var badgeGlyph by rememberSaveable(activeAccount?.pubkeyHex) {
        mutableStateOf(activeAccount?.badgeGlyph?.ifBlank { "≠" } ?: "≠")
    }
    var badgeColorHex by rememberSaveable(activeAccount?.pubkeyHex) {
        mutableStateOf(activeAccount?.badgeColorHex?.ifBlank { "#FF1493" } ?: "#FF1493")
    }
    var uploadToBlossom by rememberSaveable { mutableStateOf(true) }

    LaunchedEffect(activeAccount?.pubkeyHex) {
        if (activeAccount != null) {
            displayName = activeAccount.displayName
            bio = activeAccount.bio
            avatarLocalPath = activeAccount.localAvatarPath
            badgeGlyph = activeAccount.badgeGlyph.ifBlank { "≠" }
            badgeColorHex = activeAccount.badgeColorHex.ifBlank { "#FF1493" }
        }
    }

    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val saved = onImportAvatar(uri)
                if (saved != null) {
                    avatarLocalPath = saved
                }
            }
        }
    }

    val previewAccount = activeAccount?.copy(
        displayName = displayName,
        localAvatarPath = avatarLocalPath,
        badgeGlyph = badgeGlyph,
        badgeColorHex = badgeColorHex
    )

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
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("profile_back_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.cd_back),
                        tint = BreWhite
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
                Column {
                    Text(
                        text = stringResource(R.string.profile_title),
                        style = MaterialTheme.typography.headlineSmall,
                        color = BreWhite
                    )
                    Text(
                        text = "AGENT ZONE • KIND 0 NOSTR IDENTITY",
                        style = MaterialTheme.typography.labelSmall,
                        color = ZineBoneCream
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Agent Zone Medallion + Avatar Upload + Associated Pubkey Card
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = BreGray850,
                border = BorderStroke(1.dp, BrePinkDark2),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp)
                ) {
                    AccountAvatarThumbnail(
                        account = previewAccount,
                        sizeDp = 96,
                        contentDescription = stringResource(R.string.cd_user_avatar)
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                photoPickerLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            },
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("upload_avatar_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.AddAPhoto,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = stringResource(R.string.profile_upload_avatar))
                        }
                        if (avatarLocalPath.isNotBlank()) {
                            OutlinedButton(
                                onClick = { avatarLocalPath = "" },
                                shape = RoundedCornerShape(14.dp)
                            ) {
                                Text("Use Glyph")
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Bound Public Key Box
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = BreBlack,
                        border = BorderStroke(1.dp, BrePinkDark2),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Verified,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "ASSOCIATED NOSTR PUBLIC KEY",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = ZineBoneCream
                                )
                                Text(
                                    text = activeAccount?.npub ?: "Generating…",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                            }
                            IconButton(
                                onClick = {
                                    activeAccount?.npub?.let {
                                        clipboard.setText(AnnotatedString(it))
                                        onCopiedKey()
                                    }
                                },
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("copy_profile_npub_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ContentCopy,
                                    contentDescription = stringResource(R.string.cd_copy_npub),
                                    tint = BreWhite
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Agent Zone: Add Your Icon, Initials, Glyph or Color
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = BreGray850,
                border = BorderStroke(1.dp, BrePinkDark2),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "AGENT ZONE • CUSTOMIZABLE PER PROFILE",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Pick a medallion glyph, initials, or ring color",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        PRESET_GLYPHS.forEach { g ->
                            FilterChip(
                                selected = badgeGlyph == g,
                                onClick = { badgeGlyph = g },
                                label = { Text(g) }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = badgeGlyph,
                        onValueChange = { badgeGlyph = it.take(3) },
                        label = { Text("Custom Initials or Glyph (max 3 chars)") },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        PRESET_BADGE_COLORS.forEach { (hex, name) ->
                            val swatch = parseHexColorSafe(hex)
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(CircleShape)
                                    .background(swatch)
                                    .border(
                                        width = if (badgeColorHex.equals(hex, ignoreCase = true)) 3.dp else 1.dp,
                                        color = if (badgeColorHex.equals(hex, ignoreCase = true)) BreWhite else Color.DarkGray,
                                        shape = CircleShape
                                    )
                                    .clickable { badgeColorHex = hex }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = displayName,
                onValueChange = { displayName = it },
                label = { Text(stringResource(R.string.profile_display_name_label)) },
                placeholder = { Text("e.g. BreBre Sovereign") },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("profile_display_name_input")
            )

            Spacer(modifier = Modifier.height(14.dp))

            OutlinedTextField(
                value = bio,
                onValueChange = { bio = it },
                label = { Text(stringResource(R.string.profile_bio_label)) },
                placeholder = { Text("Move different. Impact everything on Nostr…") },
                minLines = 3,
                maxLines = 5,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("profile_bio_input")
            )

            Spacer(modifier = Modifier.height(14.dp))

            Surface(
                shape = RoundedCornerShape(16.dp),
                color = BreGray850,
                border = BorderStroke(1.dp, BrePinkDark2),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Publish Avatar to Blossom (BUD-02)",
                            style = MaterialTheme.typography.titleSmall,
                            color = BreWhite
                        )
                        Text(
                            text = "Signs a Kind 24242 upload token so relays receive a content-addressed picture URL.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Switch(
                        checked = uploadToBlossom,
                        onCheckedChange = { uploadToBlossom = it },
                        modifier = Modifier.testTag("blossom_avatar_switch")
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Button(
                onClick = {
                    onSaveAndPublish(
                        displayName,
                        bio,
                        avatarLocalPath,
                        uploadToBlossom,
                        badgeGlyph,
                        badgeColorHex
                    )
                },
                enabled = !isWorking && activeAccount != null,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .testTag("save_profile_button")
            ) {
                if (isWorking) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = BreWhite
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                }
                Text(
                    text = "${stringResource(R.string.profile_save_publish)} →",
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}
