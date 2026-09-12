package dev.rawrec.app.ui.gallery

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.rawrec.app.capture.RecordingController
import dev.rawrec.app.ui.components.RawRecIcons
import dev.rawrec.app.ui.theme.DeckLabelStyle
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class GalleryFilter(val label: String) {
    ALL("ALL TAKES"),
    RAW("RAW (.RVSP)"),
    MP4("VIDEO (.MP4)")
}

private data class ExportRequest(
    val file: File,
    val rotation: Int = 0,
    val previewBitmap: android.graphics.Bitmap? = null
)

@Composable
fun GalleryScreen(
    controller: RecordingController,
    initialFile: File? = null,
    initialExportFile: File? = null,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    var recordings by remember { mutableStateOf<List<File>>(emptyList()) }
    var selectedFilter by remember { mutableStateOf(GalleryFilter.ALL) }
    var activePlayerFile by remember(initialFile) { mutableStateOf<File?>(initialFile) }
    var activeExportRequest by remember(initialExportFile) {
        mutableStateOf(initialExportFile?.let { ExportRequest(it, 0, null) })
    }
    var fileToDelete by remember { mutableStateOf<File?>(null) }

    fun refreshList() {
        recordings = controller.listRecordings()
    }

    LaunchedEffect(Unit) {
        refreshList()
    }

    val filteredRecordings = remember(recordings, selectedFilter) {
        when (selectedFilter) {
            GalleryFilter.ALL -> recordings
            GalleryFilter.RAW -> recordings.filter { it.name.endsWith(".rvsp", ignoreCase = true) }
            GalleryFilter.MP4 -> recordings.filter { it.name.endsWith(".mp4", ignoreCase = true) }
        }
    }

    val totalBytes = remember(recordings) { recordings.sumOf { it.length() } }

    Box(modifier = modifier.fillMaxSize()) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // 1. Header Bar
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                RawRecIcons.Movie,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                            Column {
                                Text(
                                    "STUDIO GALLERY",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    "${recordings.size} TAKES · ${formatBytes(totalBytes)}",
                                    style = DeckLabelStyle,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }

                        Surface(
                            shape = MaterialTheme.shapes.extraSmall,
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                            modifier = Modifier
                                .height(36.dp)
                                .clickable { onClose() }
                        ) {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 14.dp)) {
                                Text(
                                    "CLOSE",
                                    style = DeckLabelStyle,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }

                // 2. Filter Tabs
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        GalleryFilter.values().forEach { filter ->
                            val isSel = filter == selectedFilter
                            Surface(
                                shape = MaterialTheme.shapes.extraSmall,
                                color = if (isSel) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
                                border = if (isSel) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
                                modifier = Modifier.clickable { selectedFilter = filter }
                            ) {
                                Text(
                                    text = filter.label,
                                    style = DeckLabelStyle,
                                    fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSel) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                }

                // 3. Take Cards List
                if (filteredRecordings.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                RawRecIcons.Movie,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.outlineVariant,
                                modifier = Modifier.size(48.dp)
                            )
                            Text(
                                "No takes found in /sdcard/RawRec/",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(filteredRecordings, key = { it.absolutePath }) { file ->
                            TakeCard(
                                file = file,
                                onPlay = { activePlayerFile = file },
                                onExport = { activeExportRequest = ExportRequest(file, 0, null) },
                                onDelete = { fileToDelete = file }
                            )
                        }
                    }
                }
            }
        }

        // Fullscreen Player Overlay
        activePlayerFile?.let { file ->
            GalleryPlayerDeck(
                file = file,
                onBack = { activePlayerFile = null },
                onExportRequested = { f, rot, bmp ->
                    activeExportRequest = ExportRequest(f, rot, bmp)
                }
            )
        }

        // Export Dialog
        activeExportRequest?.let { req ->
            GalleryExportDialog(
                file = req.file,
                initialRotation = req.rotation,
                previewBitmap = req.previewBitmap,
                onDismiss = { activeExportRequest = null },
                onExportComplete = {
                    refreshList()
                }
            )
        }

        // Delete Confirmation Dialog
        fileToDelete?.let { file ->
            AlertDialog(
                onDismissRequest = { fileToDelete = null },
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                title = { Text("Delete Take?") },
                text = { Text("Are you sure you want to permanently delete '${file.name}' (${formatBytes(file.length())})?") },
                confirmButton = {
                    Button(
                        onClick = {
                            file.delete()
                            fileToDelete = null
                            refreshList()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Delete", color = MaterialTheme.colorScheme.onError)
                    }
                },
                dismissButton = {
                    OutlinedButton(onClick = { fileToDelete = null }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}

@Composable
private fun TakeCard(
    file: File,
    onPlay: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit
) {
    val isRvsp = file.name.endsWith(".rvsp", ignoreCase = true)
    val dateStr = remember(file) {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        sdf.format(Date(file.lastModified()))
    }

    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left Info
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable { onPlay() },
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Codec Badge
                    Surface(
                        shape = MaterialTheme.shapes.extraSmall,
                        color = if (isRvsp) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Text(
                            text = if (isRvsp) "RVSP RAW" else "MP4 VIDEO",
                            style = DeckLabelStyle,
                            fontWeight = FontWeight.Bold,
                            color = if (isRvsp) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    Text(
                        text = file.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Text(
                    text = "$dateStr · ${formatBytes(file.length())}",
                    style = DeckLabelStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Right Action Buttons
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Play Button
                Surface(
                    shape = MaterialTheme.shapes.extraSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .size(34.dp)
                        .clickable { onPlay() }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            RawRecIcons.Play,
                            contentDescription = "Play Take",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                // Export Button (Only for RVSP files)
                if (isRvsp) {
                    Surface(
                        shape = MaterialTheme.shapes.extraSmall,
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                        modifier = Modifier
                            .size(34.dp)
                            .clickable { onExport() }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                RawRecIcons.Export,
                                contentDescription = "Export Take",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }

                // Delete Button
                Surface(
                    shape = MaterialTheme.shapes.extraSmall,
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier
                        .size(34.dp)
                        .clickable { onDelete() }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            RawRecIcons.Trash,
                            contentDescription = "Delete Take",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1 shl 30 -> "%.2f GB".format(bytes.toDouble() / (1 shl 30))
    bytes >= 1 shl 20 -> "%.1f MB".format(bytes.toDouble() / (1 shl 20))
    bytes >= 1 shl 10 -> "%.0f KB".format(bytes.toDouble() / (1 shl 10))
    else -> "$bytes B"
}
