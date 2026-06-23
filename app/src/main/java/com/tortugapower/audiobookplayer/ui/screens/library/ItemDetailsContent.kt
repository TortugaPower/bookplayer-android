package com.tortugapower.audiobookplayer.ui.screens.library

import android.net.Uri
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Update
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.tortugapower.audiobookplayer.R
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import com.tortugapower.audiobookplayer.logic.HardcoverSettingsManager
import com.tortugapower.audiobookplayer.network.HardcoverBook
import com.tortugapower.audiobookplayer.viewmodel.LibraryViewModel
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun ItemDetailsContent(
    item: LibraryItemEntity,
    viewModel: LibraryViewModel,
    title: String,
    onTitleChange: (String) -> Unit,
    author: String,
    onAuthorChange: (String) -> Unit,
    artworkURL: String?,
    onUpdateArtwork: (Uri) -> Unit,
    onDeleteArtwork: () -> Unit,
    linkedBook: HardcoverBook?,
    onNavigateToBrowser: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
        onResult = { uri ->
            if (uri != null) {
                onUpdateArtwork(uri)
            }
        }
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            ActionButton(
                text = stringResource(R.string.common_cancel),
                onClick = onDismiss,
                containerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f),
                contentColor = MaterialTheme.colorScheme.onSurface
            )
            
            Text(
                text = stringResource(R.string.library_edit_book_title), 
                fontWeight = FontWeight.ExtraBold, 
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            
            ActionButton(
                text = stringResource(R.string.common_save),
                onClick = {
                    viewModel.updateItemDetails(item, title, author)
                    onDismiss()
                }
            )
        }

        Column(
            modifier = Modifier.fillMaxWidth()
        ) {
        Text(
            stringResource(R.string.common_details),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(vertical = 12.dp)
        )

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)
        ) {
            Column {
                TextField(
                    value = title,
                    onValueChange = onTitleChange,
                    placeholder = { Text(stringResource(R.string.library_title_placeholder), style = MaterialTheme.typography.bodyLarge) },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodyLarge,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        focusedTextColor = MaterialTheme.colorScheme.onSurface,
                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface
                    ),
                    trailingIcon = {
                        if (title.isNotEmpty()) {
                            IconButton(onClick = { onTitleChange("") }) {
                                Icon(
                                    Icons.Default.Close, 
                                    contentDescription = stringResource(R.string.common_clear), 
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                )
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 20.dp), 
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                )
                TextField(
                    value = author,
                    onValueChange = onAuthorChange,
                    placeholder = { Text(stringResource(R.string.library_author_placeholder), style = MaterialTheme.typography.bodyLarge) },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodyLarge,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        focusedTextColor = MaterialTheme.colorScheme.onSurface,
                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface
                    ),
                    trailingIcon = {
                        if (author.isNotEmpty()) {
                            IconButton(onClick = { onAuthorChange("") }) {
                                Icon(
                                    Icons.Default.Close, 
                                    contentDescription = stringResource(R.string.common_clear), 
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            stringResource(R.string.library_artwork_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(vertical = 12.dp)
        )

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)
        ) {
            Row(
                modifier = Modifier.padding(20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Artwork Image
                Box(
                    modifier = Modifier
                        .size(140.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)),
                    contentAlignment = Alignment.Center
                ) {
                    if (artworkURL != null) {
                        AsyncImage(
                            model = artworkURL,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Icon(
                            Icons.Default.Add, 
                            contentDescription = null, 
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(48.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(32.dp))

                Row(
                    modifier = Modifier
                        .weight(1f)
                        .height(140.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f)),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Update Button
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { 
                                launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            }
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Icon(
                            imageVector = if (artworkURL != null) Icons.Default.Update else Icons.Default.Add,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp)
                        )
                        Text(
                            if (artworkURL != null) stringResource(R.string.common_update) else stringResource(R.string.common_add),
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }

                    if (artworkURL != null) {
                        VerticalDivider(
                            modifier = Modifier.height(64.dp), 
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                        )
                        
                        // Delete Button
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { 
                                    onDeleteArtwork()
                                }
                                .padding(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            Icon(
                                Icons.Default.Delete, 
                                contentDescription = null, 
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(28.dp)
                            )
                            Text(
                                stringResource(R.string.common_delete), 
                                color = MaterialTheme.colorScheme.error, 
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.ExtraBold
                            )
                        }
                    }
                }
            }
        }

        // Hardcover Section (Only shown if token is set)
        val token by HardcoverSettingsManager.getToken(context).collectAsState(initial = "")
        if (token.isNotEmpty()) {
            Spacer(modifier = Modifier.height(32.dp))

            Text(
                text = stringResource(R.string.hardcover_settings_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(vertical = 12.dp)
            )

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onNavigateToBrowser() },
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)
            ) {
                Row(
                    modifier = Modifier.padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(width = 48.dp, height = 72.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (linkedBook != null) {
                            AsyncImage(
                                model = linkedBook.image?.url,
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(16.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = linkedBook?.title ?: "Link Book",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = linkedBook?.getAuthorName() ?: "Search on Hardcover",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(40.dp))

        // Metadata Footer
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 16.dp)
        ) {
            FooterInfoRow(
                label = stringResource(R.string.library_filename_label), 
                value = item.originalFileName ?: stringResource(R.string.common_unknown)
            )
            FooterInfoRow(
                label = stringResource(R.string.library_progress_label), 
                value = stringResource(R.string.library_progress_percent, (item.percentCompleted * 100).toInt())
            )
            
            val lastPlay = item.lastPlayDate
            if (lastPlay != null) {
                val date = Date(lastPlay)
                val format = SimpleDateFormat(stringResource(R.string.date_format_last_played), Locale.getDefault())
                FooterInfoRow(label = stringResource(R.string.library_last_played_label), value = format.format(date))
            }
        }
    }
}
}

@Composable
private fun FooterInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(end = 16.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun ActionButton(
    text: String,
    onClick: () -> Unit,
    containerColor: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
    contentColor: Color = MaterialTheme.colorScheme.primary,
) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = contentColor
        ),
        shape = RoundedCornerShape(20.dp),
        elevation = null,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        modifier = Modifier.height(40.dp)
    ) {
        Text(
            text = text, 
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold
        )
    }
}
