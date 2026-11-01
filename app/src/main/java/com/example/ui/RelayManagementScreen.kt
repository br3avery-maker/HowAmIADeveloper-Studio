package com.example.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.ManagedRelayItem
import com.example.nostr.RelayConnectionStatus
import com.example.ui.theme.CoralAlert
import com.example.ui.theme.CyberMint
import com.example.ui.theme.ObsidianBase
import com.example.ui.theme.ObsidianBorder
import com.example.ui.theme.ObsidianElevated
import com.example.ui.theme.SignalAmber
import com.example.ui.theme.SlateMuted

private val SUGGESTED_RELAYS = listOf(
    "wss://relay.nostr.band",
    "wss://purplepag.es",
    "wss://offchain.pub"
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RelayManagementScreen(
    relays: List<ManagedRelayItem>,
    isWorking: Boolean,
    onAddRelay: (String, () -> Unit) -> Unit,
    onRemoveRelay: (String) -> Unit,
    onReconnectRelay: (String) -> Unit,
    onPublishNip65: () -> Unit,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)

    var relayUrlInput by rememberSaveable { mutableStateOf("") }
    val connectedCount = relays.count { it.statusInfo.status == RelayConnectionStatus.CONNECTED }

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
                .widthIn(max = 680.dp)
                .align(Alignment.TopCenter)
                .padding(horizontal = 20.dp, vertical = 14.dp)
        ) {
            // Header
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
                            .testTag("relays_back_button")
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
                            text = stringResource(R.string.relays_title),
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            text = "$connectedCount of ${relays.size} relays connected live",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (connectedCount > 0) CyberMint else SignalAmber
                        )
                    }
                }

                OutlinedButton(
                    onClick = onPublishNip65,
                    enabled = !isWorking && relays.isNotEmpty(),
                    shape = RoundedCornerShape(999.dp),
                    modifier = Modifier.testTag("publish_nip65_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.CloudSync,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = "NIP-65", style = MaterialTheme.typography.labelMedium)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Add New Relay Input Card
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = ObsidianElevated,
                border = BorderStroke(1.dp, ObsidianBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "ADD NOSTR RELAY ENDPOINT",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = relayUrlInput,
                            onValueChange = { relayUrlInput = it },
                            placeholder = { Text(stringResource(R.string.relays_url_hint)) },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("relay_url_input")
                        )
                        Button(
                            onClick = {
                                onAddRelay(relayUrlInput) { relayUrlInput = "" }
                            },
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .height(54.dp)
                                .testTag("add_relay_button")
                        ) {
                            Icon(imageVector = Icons.Default.Add, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(text = "Add")
                        }
                    }

                    // Quick-add suggested relays not yet in list
                    val existingUrls = relays.map { it.url }.toSet()
                    val availableSuggestions = SUGGESTED_RELAYS.filter { it !in existingUrls }
                    if (availableSuggestions.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(10.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            availableSuggestions.forEach { suggestion ->
                                AssistChip(
                                    onClick = { onAddRelay(suggestion) {} },
                                    label = {
                                        Text(
                                            text = "+ ${suggestion.removePrefix("wss://")}",
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    }
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Configured Relays List with Live Connection Status
            LazyColumn(
                contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .testTag("relay_list_column")
            ) {
                itemsIndexed(relays, key = { _, item -> item.url }) { index, relay ->
                    RelayStatusCard(
                        relay = relay,
                        index = index,
                        onReconnect = { onReconnectRelay(relay.url) },
                        onRemove = { onRemoveRelay(relay.url) }
                    )
                }
            }
        }
    }
}

@Composable
private fun RelayStatusCard(
    relay: ManagedRelayItem,
    index: Int,
    onReconnect: () -> Unit,
    onRemove: () -> Unit
) {
    val status = relay.statusInfo.status
    val (dotColor, statusText) = when (status) {
        RelayConnectionStatus.CONNECTED -> CyberMint to "Connected"
        RelayConnectionStatus.CONNECTING -> SignalAmber to "Connecting"
        RelayConnectionStatus.DISCONNECTED -> SlateMuted to "Disconnected"
        RelayConnectionStatus.ERROR -> CoralAlert to "Unreachable"
    }

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = ObsidianElevated,
        border = BorderStroke(1.dp, ObsidianBorder),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("relay_item_card_$index")
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(11.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = relay.url,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(3.dp))
                val detail = buildString {
                    append(statusText)
                    relay.statusInfo.latencyMs?.let { append(" • ${it}ms") }
                    if (relay.statusInfo.eventsReceived > 0) {
                        append(" • ${relay.statusInfo.eventsReceived} events")
                    }
                    relay.statusInfo.lastMessage?.takeIf { status == RelayConnectionStatus.ERROR }?.let {
                        append(" ($it)")
                    }
                }
                Text(
                    text = detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = dotColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            IconButton(
                onClick = onReconnect,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("reconnect_relay_button_$index")
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Reconnect ${relay.url}",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            IconButton(
                onClick = onRemove,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("remove_relay_button_$index")
            ) {
                Icon(
                    imageVector = Icons.Default.DeleteOutline,
                    contentDescription = "Remove ${relay.url}",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
