package com.tortugapower.audiobookplayer.ui.screens.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.RemoveCircle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tortugapower.audiobookplayer.R

/** One removable row of an import confirmation list. [tint] null uses the theme's primary color. */
data class ImportConfirmationRow(
    val id: String,
    val icon: ImageVector,
    val title: String,
    val subtitle: String? = null,
    val tint: Color? = null
)

/**
 * The import confirmation sheet both import flows share (iOS `ImportConfirmationView`): ✕/✓ header, the Import
 * title, the file count and removable rows, with [description] pinned under the list. Like iOS, a swipe, a tap
 * outside or Back can't close it, so a staged import is never thrown away by accident: ✕ is the way out.
 *
 * [emptyState] is shown instead of the list while there are no rows, for a flow whose rows arrive while the sheet
 * is up (Transfer from Computer); null keeps the empty list. [footer] goes under the description.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportConfirmationSheet(
    rows: List<ImportConfirmationRow>,
    fileCount: Int,
    onRemove: (ImportConfirmationRow) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    description: String? = null,
    emptyState: (@Composable () -> Unit)? = null,
    footer: @Composable ColumnScope.() -> Unit = {}
) {
    ModalBottomSheet(
        onDismissRequest = {},
        sheetState = rememberModalBottomSheetState(
            skipPartiallyExpanded = true,
            confirmValueChange = { it != SheetValue.Hidden }
        ),
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = false),
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = null,
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onCancel,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.common_close),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }

                // Confirming an emptied list does nothing, so it isn't offered (iOS parity).
                val canConfirm = rows.isNotEmpty()
                IconButton(
                    onClick = onConfirm,
                    enabled = canConfirm,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f))
                ) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = stringResource(R.string.common_accept),
                        tint = if (canConfirm) MaterialTheme.colorScheme.primary
                               else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = stringResource(R.string.import_title),
                fontSize = 40.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(32.dp))

            if (rows.isEmpty() && emptyState != null) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    emptyState()
                }
            } else {
                Text(
                    text = stringResource(R.string.import_files_count, fileCount),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(16.dp))

                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(rows, key = { it.id }) { row ->
                        ImportConfirmationListItem(row, onRemove = { onRemove(row) })
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant,
                            thickness = 0.5.dp
                        )
                    }
                }

                // Pinned under the list rather than scrolling with it: it's the caveat to read before confirming.
                if (description != null) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = description,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 16.sp,
                        lineHeight = 22.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            footer()
        }
    }
}

@Composable
private fun ImportConfirmationListItem(row: ImportConfirmationRow, onRemove: () -> Unit) {
    // The button's 48dp target takes most of the row's padding: a file's row stays as tall, and the icon as far
    // from the file icon.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onRemove) {
            Icon(
                imageVector = Icons.Default.RemoveCircle,
                // Says what it removes: TalkBack reads it apart from the row's title.
                contentDescription = stringResource(R.string.import_remove_item_accessibility, row.title),
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(28.dp)
            )
        }

        Spacer(modifier = Modifier.width(6.dp))

        Icon(
            imageVector = row.icon,
            contentDescription = null,
            tint = row.tint ?: MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp)
        )

        Spacer(modifier = Modifier.width(16.dp))

        Column {
            Text(
                text = row.title,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 16.sp,
                maxLines = 1
            )
            if (row.subtitle != null) {
                Text(
                    text = row.subtitle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp
                )
            }
        }
    }
}
