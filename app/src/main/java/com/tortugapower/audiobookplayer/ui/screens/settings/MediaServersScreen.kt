@file:OptIn(ExperimentalMaterial3Api::class)

package com.tortugapower.audiobookplayer.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tortugapower.audiobookplayer.R
import com.tortugapower.audiobookplayer.database.entities.ExternalServerEntity
import com.tortugapower.audiobookplayer.database.entities.ExternalServiceType
import com.tortugapower.audiobookplayer.repository.ExternalServerRepository
import com.tortugapower.audiobookplayer.ui.screens.settings.connection.ConnectionFlowSheet
import com.tortugapower.audiobookplayer.viewmodel.ConnectionFlowMode
import com.tortugapower.audiobookplayer.viewmodel.ExternalServerViewModel
import java.util.Locale

/**
 * The saved media servers, one section per integration, each with an add button. Adding a server
 * runs the connection flow ([ConnectionFlowSheet]); on success the sheet has already hidden itself
 * and the new server's library opens through [onServerClick].
 *
 * @param initialAddServerType opens the add-server flow for that integration as soon as the screen
 *   appears — the "connect your server" prompt deep-links here for a synced-down book whose server
 *   isn't configured on this device.
 */
@Composable
fun MediaServersScreen(
    viewModel: ExternalServerViewModel,
    externalServerRepository: ExternalServerRepository,
    onBack: () -> Unit,
    onServerClick: (ExternalServerEntity) -> Unit,
    initialAddServerType: ExternalServiceType? = null,
) {
    val servers by viewModel.servers.collectAsState()
    var addServerType by remember { mutableStateOf<ExternalServiceType?>(null) }
    var showServerInfo by remember { mutableStateOf<ExternalServerEntity?>(null) }
    var isEditing by remember { mutableStateOf(false) }

    LaunchedEffect(initialAddServerType) {
        if (initialAddServerType != null) addServerType = initialAddServerType
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(id = R.string.media_servers_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(id = R.string.common_close))
                    }
                },
                actions = {
                    if (isEditing) {
                        TextButton(onClick = { isEditing = false }) {
                            Text(stringResource(id = R.string.common_done))
                        }
                    } else {
                        IconButton(onClick = { isEditing = true }) {
                            Icon(Icons.Default.Edit, contentDescription = stringResource(id = R.string.media_servers_edit_button_description))
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            ExternalServiceType.values().forEach { type ->
                item {
                    ServerTypeSection(
                        type = type,
                        servers = servers.filter { it.type == type },
                        isEditing = isEditing,
                        onAddClick = { addServerType = type },
                        onDeleteClick = { viewModel.deleteServer(it) },
                        onServerClick = onServerClick,
                        onInfoClick = { showServerInfo = it }
                    )
                }
            }
        }
    }

    if (showServerInfo != null) {
        ServerInfoSheet(
            server = showServerInfo!!,
            onDismiss = { showServerInfo = null }
        )
    }

    addServerType?.let { type ->
        ConnectionFlowSheet(
            type = type,
            mode = ConnectionFlowMode.AddServer,
            externalServerRepository = externalServerRepository,
            onDismiss = { addServerType = null },
            onSignedIn = { server ->
                // The sheet has finished hiding by now, so presenting the library can't race it.
                addServerType = null
                onServerClick(server)
            },
        )
    }
}

@Composable
fun ServerTypeSection(
    type: ExternalServiceType,
    servers: List<ExternalServerEntity>,
    isEditing: Boolean,
    onAddClick: () -> Unit,
    onDeleteClick: (ExternalServerEntity) -> Unit,
    onServerClick: (ExternalServerEntity) -> Unit,
    onInfoClick: (ExternalServerEntity) -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = type.name.lowercase().replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            IconButton(onClick = onAddClick) {
                Icon(
                    Icons.Default.AddCircle,
                    contentDescription = stringResource(id = R.string.media_servers_add_button_description),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (servers.isEmpty()) {
            Text(
                text = stringResource(id = R.string.media_servers_no_servers_added),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.padding(vertical = 8.dp)
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                servers.forEach { server ->
                    ServerItem(
                        server = server,
                        isEditing = isEditing,
                        onDeleteClick = { onDeleteClick(server) },
                        onClick = { onServerClick(server) },
                        onInfoClick = { onInfoClick(server) }
                    )
                }
            }
        }
    }
}

@Composable
fun ServerItem(
    server: ExternalServerEntity,
    isEditing: Boolean,
    onDeleteClick: () -> Unit,
    onClick: () -> Unit,
    onInfoClick: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isEditing) {
                IconButton(onClick = onDeleteClick) {
                    Icon(Icons.Default.RemoveCircle, contentDescription = stringResource(id = R.string.media_servers_delete_button_description), tint = MaterialTheme.colorScheme.error)
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = server.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = server.username ?: stringResource(id = R.string.media_servers_anonymous_username),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            IconButton(onClick = onInfoClick) {
                Icon(
                    Icons.Default.Info,
                    contentDescription = stringResource(id = R.string.media_servers_info_button_description),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerInfoSheet(
    server: ExternalServerEntity,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = null,
        containerColor = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxSize()
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(id = R.string.common_close), color = MaterialTheme.colorScheme.primary)
                }
                
                Text(
                    text = stringResource(id = R.string.media_servers_connection_details_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                
                Spacer(modifier = Modifier.width(64.dp))
            }

            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                // Server Details Section
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(
                        text = stringResource(id = R.string.media_servers_server_section_title),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Bold
                    )
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(stringResource(id = R.string.media_servers_name_label), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(server.name, fontWeight = FontWeight.Medium)
                            }
                            HorizontalDivider(thickness = 0.5.dp)
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(stringResource(id = R.string.media_servers_url_label), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(server.url, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }

                    Text(
                        text = stringResource(id = R.string.media_servers_login_section_title),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Bold
                    )
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(stringResource(id = R.string.media_servers_username_label), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(server.username ?: stringResource(id = R.string.media_servers_anonymous_username), fontWeight = FontWeight.Medium)
                            }
                        }
                    }

                    if (!server.customHeaders.isNullOrEmpty()) {
                        Text(
                            text = stringResource(id = R.string.media_servers_custom_headers_section_title),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Bold
                        )
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                server.customHeaders!!.forEach { (key, value) ->
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text(key, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(value, fontWeight = FontWeight.Medium)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
