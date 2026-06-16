@file:OptIn(ExperimentalMaterial3Api::class)

package com.tortugapower.audiobookplayer.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tortugapower.audiobookplayer.R
import com.tortugapower.audiobookplayer.database.entities.ExternalServerEntity
import com.tortugapower.audiobookplayer.database.entities.ExternalServiceType
import com.tortugapower.audiobookplayer.ui.components.LocalMiniPlayerInset
import com.tortugapower.audiobookplayer.viewmodel.ExternalServerViewModel

@Composable
fun MediaServersScreen(
    viewModel: ExternalServerViewModel,
    onBack: () -> Unit,
    onServerClick: (ExternalServerEntity) -> Unit
) {
    val servers by viewModel.servers.collectAsState()
    var showAddServerDialog by remember { mutableStateOf<ExternalServiceType?>(null) }
    var isEditing by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Media Servers") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                },
                actions = {
                    TextButton(onClick = { isEditing = !isEditing }) {
                        Text(if (isEditing) "Done" else "Edit")
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(bottom = LocalMiniPlayerInset.current),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            ExternalServiceType.values().forEach { type ->
                item {
                    ServerTypeSection(
                        type = type,
                        servers = servers.filter { it.type == type },
                        isEditing = isEditing,
                        onAddClick = { showAddServerDialog = type },
                        onDeleteClick = { viewModel.deleteServer(it) },
                        onServerClick = onServerClick
                    )
                }
            }
        }
    }

    if (showAddServerDialog != null) {
        AddServerDialog(
            type = showAddServerDialog!!,
            onDismiss = { showAddServerDialog = null },
            onConnect = { name, url, username, password, headers ->
                // In a real implementation, we would call viewModel.testConnection first
                // For now, let's just add it
                viewModel.addServer(name, showAddServerDialog!!, url, username, "dummy-token", headers)
                showAddServerDialog = null
            }
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
    onServerClick: (ExternalServerEntity) -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = type.name.lowercase().capitalize(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            IconButton(onClick = onAddClick) {
                Icon(
                    Icons.Default.AddCircle,
                    contentDescription = "Add",
                    tint = Color(0xFF3482F6)
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (servers.isEmpty()) {
            Text(
                text = "No servers added",
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
                        onClick = { onServerClick(server) }
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
    onClick: () -> Unit
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
                    Icon(Icons.Default.RemoveCircle, contentDescription = "Delete", tint = Color.Red)
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = server.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = server.username ?: "Anonymous",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            IconButton(onClick = { /* Show info */ }) {
                Icon(
                    Icons.Default.Info,
                    contentDescription = "Info",
                    tint = Color(0xFF3482F6)
                )
            }
        }
    }
}

@Composable
fun AddServerDialog(
    type: ExternalServiceType,
    onDismiss: () -> Unit,
    onConnect: (String, String, String?, String?, Map<String, String>?) -> Unit
) {
    var url by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Connect to ${type.name.lowercase().capitalize()}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Server URL") },
                    placeholder = { Text("http://example.com:8096") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Username") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConnect(url, url, username, password, null) },
                enabled = url.isNotBlank()
            ) {
                Text("Connect")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

fun String.capitalize() = this.lowercase().replaceFirstChar { it.uppercase() }
