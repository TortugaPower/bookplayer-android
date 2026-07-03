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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tortugapower.audiobookplayer.R
import com.tortugapower.audiobookplayer.database.entities.ExternalServerEntity
import com.tortugapower.audiobookplayer.database.entities.ExternalServiceType
import com.tortugapower.audiobookplayer.network.ConnectionResult
import com.tortugapower.audiobookplayer.viewmodel.ExternalServerViewModel
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun MediaServersScreen(
    viewModel: ExternalServerViewModel,
    onBack: () -> Unit,
    onServerClick: (ExternalServerEntity) -> Unit
) {
    val servers by viewModel.servers.collectAsState()
    var showAddServerDialog by remember { mutableStateOf<ExternalServiceType?>(null) }
    var showServerInfo by remember { mutableStateOf<ExternalServerEntity?>(null) }
    var isEditing by remember { mutableStateOf(false) }

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
                        onAddClick = { showAddServerDialog = type },
                        onDeleteClick = { viewModel.deleteServer(it) },
                        onServerClick = onServerClick,
                        onInfoClick = { showServerInfo = it }
                    )
                }
            }
        }
    }

    val scope = rememberCoroutineScope()
    var connectionError by remember { mutableStateOf<ConnectionResult.Failure?>(null) }
    var isConnecting by remember { mutableStateOf(false) }

    if (showServerInfo != null) {
        ServerInfoSheet(
            server = showServerInfo!!,
            onDismiss = { showServerInfo = null }
        )
    }

    val errorDisplayMessage = connectionError?.let { error ->
        error.messageResId?.let { resId ->
            stringResource(id = resId, *(error.args?.toTypedArray() ?: emptyArray()))
        } ?: error.message
    }

    if (showAddServerDialog != null) {
        AddServerSheet(
            type = showAddServerDialog!!,
            isConnecting = isConnecting,
            errorMessage = errorDisplayMessage,
            onDismiss = { 
                showAddServerDialog = null
                connectionError = null
            },
            onConnect = { name, url, username, password, headers ->
                scope.launch {
                    isConnecting = true
                    connectionError = null
                    val result = viewModel.testConnection(showAddServerDialog!!, url, username, password, headers)
                    isConnecting = false
                    
                    when (result) {
                        is ConnectionResult.Success -> {
                            val finalName = result.name ?: name
                            viewModel.addServer(finalName, showAddServerDialog!!, url, username, result.token, headers)
                            showAddServerDialog = null
                        }
                        is ConnectionResult.Failure -> {
                            connectionError = result
                        }
                    }
                }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddServerSheet(
    type: ExternalServiceType,
    isConnecting: Boolean,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onConnect: (String, String, String?, String?, Map<String, String>?) -> Unit,
    // Re-auth mode: prefill from the saved server and start at the credentials step with the
    // URL locked, so signing in again replaces the token on the same logical server.
    initialUrl: String = "",
    initialUsername: String = "",
    initialHeaders: Map<String, String>? = null,
    lockUrl: Boolean = false
) {
    var url by remember { mutableStateOf(initialUrl) }
    var username by remember { mutableStateOf(initialUsername) }
    var password by remember { mutableStateOf("") }
    val headers = remember {
        mutableStateListOf<Pair<String, String>>().apply {
            initialHeaders?.forEach { (k, v) -> add(k to v) }
        }
    }

    var currentStep by remember { mutableStateOf(if (lockUrl) 2 else 1) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = if (isConnecting) ({}) else onDismiss,
        sheetState = sheetState,
        dragHandle = null,
        containerColor = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxHeight(0.92f)
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
                TextButton(onClick = { if (currentStep == 1 || lockUrl) onDismiss() else currentStep = 1 }, enabled = !isConnecting) {
                    Text(if (currentStep == 1 || lockUrl) stringResource(id = R.string.common_cancel) else stringResource(id = R.string.common_back), color = MaterialTheme.colorScheme.primary)
                }
                
                Text(
                    text = if (currentStep == 1) "" else type.name.lowercase().replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                
                TextButton(
                    onClick = {
                        if (currentStep == 1) {
                            currentStep = 2
                        } else {
                            val headersMap = if (headers.isEmpty()) null else headers.toMap()
                            val derivedName = android.net.Uri.parse(url).host ?: url
                            onConnect(derivedName, url, username, password, headersMap)
                        }
                    },
                    enabled = url.isNotBlank() && !isConnecting
                ) {
                    Text(if (currentStep == 1) stringResource(id = R.string.media_servers_add_server_connect_button) else stringResource(id = R.string.media_servers_add_server_sign_in_button), color = MaterialTheme.colorScheme.primary)
                }
            }

            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                // Server URL Section (Always visible)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(id = R.string.media_servers_add_server_url_label),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Bold
                    )
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        placeholder = { Text(stringResource(id = R.string.media_servers_add_server_url_placeholder)) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        trailingIcon = {
                            if (url.isNotEmpty()) {
                                IconButton(onClick = { url = "" }) {
                                    Icon(Icons.Default.Close, contentDescription = stringResource(id = R.string.common_clear), modifier = Modifier.size(18.dp))
                                }
                            }
                        },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            focusedIndicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                            unfocusedIndicatorColor = Color.Transparent
                        ),
                        singleLine = true,
                        enabled = !isConnecting && currentStep == 1
                    )
                    if (currentStep == 1) {
                        Text(
                            text = stringResource(id = R.string.media_servers_add_server_connect_to_server, type.name.lowercase().replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }

                if (currentStep == 1) {
                    // Step 1: Custom HTTP Headers
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = stringResource(id = R.string.media_servers_add_server_custom_headers_label),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Bold
                        )
                        
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column {
                                headers.forEachIndexed { index, pair ->
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            TextField(
                                                value = pair.first,
                                                onValueChange = { newKey -> headers[index] = newKey to pair.second },
                                                placeholder = { Text(stringResource(id = R.string.media_servers_add_server_header_name_placeholder)) },
                                                modifier = Modifier.fillMaxWidth(),
                                                colors = TextFieldDefaults.colors(
                                                    focusedContainerColor = Color.Transparent,
                                                    unfocusedContainerColor = Color.Transparent,
                                                    focusedIndicatorColor = Color.Transparent,
                                                    unfocusedIndicatorColor = Color.Transparent
                                                ),
                                                singleLine = true,
                                                textStyle = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium)
                                            )
                                            TextField(
                                                value = pair.second,
                                                onValueChange = { newVal -> headers[index] = pair.first to newVal },
                                                placeholder = { Text(stringResource(id = R.string.media_servers_add_server_header_value_placeholder)) },
                                                modifier = Modifier.fillMaxWidth(),
                                                colors = TextFieldDefaults.colors(
                                                    focusedContainerColor = Color.Transparent,
                                                    unfocusedContainerColor = Color.Transparent,
                                                    focusedIndicatorColor = Color.Transparent,
                                                    unfocusedIndicatorColor = Color.Transparent
                                                ),
                                                singleLine = true,
                                                textStyle = MaterialTheme.typography.bodySmall
                                            )
                                        }
                                        IconButton(onClick = { headers.removeAt(index) }) {
                                            Icon(
                                                Icons.Default.Delete, 
                                                contentDescription = stringResource(id = R.string.common_remove), 
                                                tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f), 
                                                modifier = Modifier.size(24.dp)
                                            )
                                        }
                                    }
                                    if (index < headers.size - 1) {
                                        HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp), thickness = 0.5.dp)
                                    }
                                }
                                
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { 
                                            headers.add("" to "")
                                        }
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.AddCircle, 
                                        contentDescription = null, 
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(stringResource(id = R.string.media_servers_add_server_add_header_button), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
                                }
                            }
                        }
                        
                        Text(
                            text = stringResource(id = R.string.media_servers_add_server_headers_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                } else {
                    // Step 2: Login replaces Headers
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
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
                            Column {
                                TextField(
                                    value = username,
                                    onValueChange = { username = it },
                                    placeholder = { Text(stringResource(id = R.string.media_servers_add_server_username_placeholder)) },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = TextFieldDefaults.colors(
                                        focusedContainerColor = Color.Transparent,
                                        unfocusedContainerColor = Color.Transparent,
                                        focusedIndicatorColor = Color.Transparent,
                                        unfocusedIndicatorColor = Color.Transparent
                                    ),
                                    trailingIcon = {
                                        if (username.isNotEmpty()) {
                                            IconButton(onClick = { username = "" }) {
                                                Icon(Icons.Default.Close, contentDescription = stringResource(id = R.string.common_clear), modifier = Modifier.size(18.dp))
                                            }
                                        }
                                    },
                                    singleLine = true,
                                    enabled = !isConnecting
                                )
                                HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp), thickness = 0.5.dp)
                                TextField(
                                    value = password,
                                    onValueChange = { password = it },
                                    placeholder = { Text(stringResource(id = R.string.media_servers_add_server_password_placeholder)) },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = TextFieldDefaults.colors(
                                        focusedContainerColor = Color.Transparent,
                                        unfocusedContainerColor = Color.Transparent,
                                        focusedIndicatorColor = Color.Transparent,
                                        unfocusedIndicatorColor = Color.Transparent
                                    ),
                                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                                    singleLine = true,
                                    enabled = !isConnecting
                                )
                            }
                        }
                    }
                }

                if (errorMessage != null) {
                    Text(
                        text = errorMessage,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }

                if (isConnecting) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .padding(top = 16.dp)
                    )
                }
            }
        }
    }
}


