@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class
)

package dev.rawrec.app.ui.gallery

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
    val previewBitmap: android.graphics.Bitmap? = null,
    val initialProfile: dev.rawrec.tool.ColorScience.ToneProfile = dev.rawrec.tool.ColorScience.ToneProfile.CINE_FILMIC
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
                // 1. MD3 TopAppBar
                TopAppBar(
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Surface(
                                shape = MaterialTheme.shapes.small,
                                color = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(36.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        RawRecIcons.Movie,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
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
                    },
                    actions = {
                        FilledTonalButton(
                            onClick = onClose,
                            modifier = Modifier.padding(end = 8.dp)
                        ) {
                            Icon(
                                RawRecIcons.Close,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("Close")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        titleContentColor = MaterialTheme.colorScheme.onSurface
                    )
                )

                // 2. MD3 SingleChoiceSegmentedButtonRow Filter Bar
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        val filters = GalleryFilter.values()
                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            filters.forEachIndexed { index, filter ->
                                val isSel = filter == selectedFilter
                                SegmentedButton(
                                    selected = isSel,
                                    onClick = { selectedFilter = filter },
                                    shape = SegmentedButtonDefaults.itemShape(index = index, count = filters.size),
                                    icon = {
                                        SegmentedButtonDefaults.Icon(active = isSel) {
                                            Icon(
                                                imageVector = RawRecIcons.Check,
                                                contentDescription = null,
                                                modifier = Modifier.size(SegmentedButtonDefaults.IconSize)
                                            )
                                        }
                                    },
                                    label = {
                                        Text(
                                            text = filter.label,
                                            style = DeckLabelStyle,
                                            fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium
                                        )
                                    }
                                )
                            }
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

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
                onExportRequested = { f, rot, bmp, prof ->
                    activeExportRequest = ExportRequest(f, rot, bmp, prof)
                }
            )
        }

        // Export Dialog
        activeExportRequest?.let { req ->
            GalleryExportDialog(
                file = req.file,
                initialRotation = req.rotation,
                initialProfile = req.initialProfile,
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

    OutlinedCard(
        onClick = onPlay,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            contentColor = MaterialTheme.colorScheme.onSurface
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left Info
            Column(
                modifier = Modifier.weight(1f),
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
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Text(
                    text = "$dateStr · ${formatBytes(file.length())}",
                    style = dev.rawrec.app.ui.theme.TelemetryStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Right MD3 Action IconButtons
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Play Button
                FilledIconButton(
                    onClick = onPlay,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        RawRecIcons.Play,
                        contentDescription = "Play Take",
                        modifier = Modifier.size(18.dp)
                    )
                }

                // Export Button (Only for RVSP files)
                if (isRvsp) {
                    FilledTonalIconButton(
                        onClick = onExport,
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                        ),
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            RawRecIcons.Export,
                            contentDescription = "Export Take",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                // Delete Button
                OutlinedIconButton(
                    onClick = onDelete,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    colors = IconButtonDefaults.outlinedIconButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    ),
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        RawRecIcons.Trash,
                        contentDescription = "Delete Take",
                        modifier = Modifier.size(18.dp)
                    )
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
