package com.tortugapower.audiobookplayer.ui.screens.settings

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.tortugapower.audiobookplayer.R
import com.tortugapower.audiobookplayer.logic.HardcoverSettingsManager
import com.tortugapower.audiobookplayer.ui.components.BookPlayerSlider
import com.tortugapower.audiobookplayer.ui.components.BookPlayerTabScaffold
import com.tortugapower.audiobookplayer.ui.components.SettingsToggleItem
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HardcoverSettingsScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current

    var token by remember { mutableStateOf("") }
    var autoMatch by remember { mutableStateOf(false) }
    var autoAdd by remember { mutableStateOf(true) }
    var threshold by remember { mutableStateOf(1f) } // 1% to 100%

    // Load initial values from Datastore
    LaunchedEffect(Unit) {
        token = HardcoverSettingsManager.getToken(context).first()
        autoMatch = HardcoverSettingsManager.getAutoMatchBooks(context).first()
        autoAdd = HardcoverSettingsManager.getAutoAddToWantToRead(context).first()
        threshold = HardcoverSettingsManager.getReadingThreshold(context).first() * 100f
    }

    BookPlayerTabScaffold(
        title = stringResource(R.string.hardcover_settings_title),
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.common_back),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
        },
        actions = {
            TextButton(
                onClick = {
                    scope.launch {
                        HardcoverSettingsManager.setToken(context, token)
                        HardcoverSettingsManager.setAutoMatchBooks(context, autoMatch)
                        HardcoverSettingsManager.setAutoAddToWantToRead(context, autoAdd)
                        HardcoverSettingsManager.setReadingThreshold(context, threshold / 100f)
                        onBack()
                    }
                }
            ) {
                Text(
                    text = stringResource(R.string.common_save),
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            // API Access Section
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.hardcover_api_access_section),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Bold
                )

                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    placeholder = { 
                        Text(
                            text = stringResource(R.string.hardcover_api_token_placeholder),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        ) 
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        focusedTextColor = MaterialTheme.colorScheme.onSurface,
                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface
                    ),
                    singleLine = true
                )

                // Hint with click link
                val apiLink = stringResource(R.string.hardcover_api_access_link)
                val fullHint = stringResource(R.string.hardcover_api_access_hint, apiLink)
                val annotatedLinkString = buildAnnotatedString {
                    val linkStartIndex = fullHint.indexOf(apiLink)
                    if (linkStartIndex != -1) {
                        append(fullHint.substring(0, linkStartIndex))
                        pushStringAnnotation(tag = "URL", annotation = apiLink)
                        withStyle(style = SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline)) {
                            append(apiLink)
                        }
                        pop()
                        append(fullHint.substring(linkStartIndex + apiLink.length))
                    } else {
                        append(fullHint)
                    }
                }
                ClickableText(
                    text = annotatedLinkString,
                    onClick = { offset ->
                        annotatedLinkString.getStringAnnotations(tag = "URL", start = offset, end = offset)
                            .firstOrNull()?.let { annotation ->
                                uriHandler.openUri(annotation.item)
                            }
                    },
                    style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                )
            }

            // Automation Section
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.hardcover_automation_section),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Bold
                )

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column {
                        SettingsToggleItem(
                            label = stringResource(R.string.hardcover_auto_match_books),
                            checked = autoMatch,
                            onCheckedChange = { autoMatch = it }
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                        )
                        SettingsToggleItem(
                            label = stringResource(R.string.hardcover_auto_add_want_to_read),
                            checked = autoAdd,
                            onCheckedChange = { autoAdd = it }
                        )
                    }
                }

                Text(
                    text = stringResource(R.string.hardcover_automation_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Progress Tracking Section
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.hardcover_progress_tracking_section),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Bold
                )

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(vertical = 16.dp, horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.hardcover_reading_threshold),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = stringResource(R.string.hardcover_reading_threshold_percent, threshold.toInt()),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        BookPlayerSlider(
                            value = threshold,
                            onValueChange = { threshold = it },
                            valueRange = 1f..100f
                        )
                    }
                }

                Text(
                    text = stringResource(R.string.hardcover_progress_tracking_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
