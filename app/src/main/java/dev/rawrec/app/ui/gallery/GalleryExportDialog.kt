package dev.rawrec.app.ui.gallery

import android.content.Context
import android.graphics.Bitmap
import android.os.PowerManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.rawrec.app.ui.components.ChoiceChipRow
import dev.rawrec.app.ui.components.RawRecIcons
import dev.rawrec.app.ui.theme.DeckLabelStyle
import dev.rawrec.tool.ColorScience
import dev.rawrec.tool.HlgMp4Exporter
import dev.rawrec.tool.Rvtool
import dev.rawrec.tool.gpu.GpuManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class ExportFormat(val label: String, val subtitle: String) {
    MP4("HLG MP4", "Hardware video (H.264 / AAC) with embedded color grade"),
    CINEMA_DNG("CinemaDNG", "Lossless 16-bit DNG frame sequence + 48kHz WAV audio")
}

@Composable
fun GalleryExportDialog(
    file: File,
    initialRotation: Int = 0,
    previewBitmap: Bitmap? = null,
    onDismiss: () -> Unit,
    onExportComplete: (File) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var selectedFormat by remember { mutableStateOf(ExportFormat.MP4) }
    var bakeToneInDng by remember { mutableStateOf(false) }
    var selectedProfile by remember { mutableStateOf(ColorScience.ToneProfile.CINE_HLG) }
    var selectedRotation by remember(initialRotation) { mutableIntStateOf(initialRotation) }
    var dropdownExpanded by remember { mutableStateOf(false) }

    var thumbnailBitmap by remember(previewBitmap) { mutableStateOf<Bitmap?>(previewBitmap) }

    var isExporting by remember { mutableStateOf(false) }
    var exportProgress by remember { mutableFloatStateOf(0f) }
    var currentFrame by remember { mutableIntStateOf(0) }
    var totalFrames by remember { mutableIntStateOf(0) }
    var exportedFile by remember { mutableStateOf<File?>(null) }
    var exportError by remember { mutableStateOf<String?>(null) }

    var exportJob by remember { mutableStateOf<Job?>(null) }
    val wakeLock = remember {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RawRec:GalleryExportWakeLock")
    }

    LaunchedEffect(file, selectedProfile) {
        if (thumbnailBitmap == null && file.name.endsWith(".rvsp", ignoreCase = true)) {
            val bmp = withContext(Dispatchers.IO) {
                try {
                    val (h, raf) = Rvtool.openHeader(file.absolutePath)
                    raf.use {
                        val frames = Rvtool.scanFrames(raf)
                        if (frames.isNotEmpty()) {
                            val frameIdx = if (frames.size > 15) minOf(30, frames.size / 4) else 0
                            val rawPayload = Rvtool.readPayload(raf, frames[frameIdx])
                            val expectedRaw = if (h.packing == 1) (h.width * h.height / 4 * 5) else (h.width * h.height * 2)
                            val rawBuf = ByteArray(expectedRaw)
                            val decoder = runCatching {
                                dev.rawrec.app.codec.FrameCodecs.byId(h.videoCodec).openDecoder()
                            }.getOrNull()
                            val decoded = if (decoder != null && h.videoCodec == "ZSTD") {
                                val rc = decoder.decodeInto(rawPayload, rawBuf)
                                decoder.close()
                                if (rc > 0) rawBuf else Rvtool.decodedPayload(h, rawPayload)
                            } else {
                                Rvtool.decodedPayload(h, rawPayload)
                            }
                            val outW = (h.width / 2) / 2
                            val outH = (h.height / 2) / 2
                            val argb = GpuManager.processFrame(
                                mipiPayload = decoded,
                                width = h.width,
                                height = h.height,
                                cfa = h.cfa,
                                packing = h.packing,
                                blackLevels = h.blackLevels,
                                whiteLevel = h.whiteLevel,
                                asShotNeutral = h.asShotNeutral,
                                applyCalibration = true,
                                profile = selectedProfile,
                                enableVignette = false,
                                downsample = 2
                            )
                            Bitmap.createBitmap(argb, outW, outH, Bitmap.Config.ARGB_8888)
                        } else null
                    }
                } catch (_: Throwable) {
                    null
                }
            }
            if (bmp != null) {
                val old = thumbnailBitmap
                thumbnailBitmap = bmp
                if (old != null && old !== previewBitmap) {
                    old.recycle()
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            exportJob?.cancel()
            if (wakeLock?.isHeld == true) {
                wakeLock.release()
            }
            if (previewBitmap == null) {
                thumbnailBitmap?.recycle()
                thumbnailBitmap = null
            }
        }
    }

    AlertDialog(
        onDismissRequest = {
            if (!isExporting) onDismiss()
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    RawRecIcons.Export,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
                Text(
                    if (selectedFormat == ExportFormat.MP4) "EXPORT HLG MP4" else "EXPORT CINEMADNG SEQUENCE",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Source Info
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                ) {
                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = file.name,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Size: ${formatFileSize(file.length())} · Format: Hardware MP4 or CinemaDNG",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Export Target Format Selector
                if (!isExporting && exportedFile == null) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "Export Target Format",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        ChoiceChipRow(
                            options = ExportFormat.values().map { it.label },
                            selected = { it == selectedFormat.label },
                            onSelect = { label ->
                                selectedFormat = ExportFormat.values().firstOrNull { it.label == label } ?: ExportFormat.MP4
                            }
                        )
                        Text(
                            selectedFormat.subtitle,
                            style = dev.rawrec.app.ui.theme.TelemetryStyle,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                // GPU Acceleration Badge
                if (selectedFormat == ExportFormat.MP4) {
                    Surface(
                        shape = MaterialTheme.shapes.extraSmall,
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text("⚡", fontSize = 12.sp)
                            Text(
                                text = "Acceleration: ${GpuManager.activeBackend.deviceName}",
                                style = DeckLabelStyle,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }

                // CinemaDNG Destination & Strategy
                if (selectedFormat == ExportFormat.CINEMA_DNG && !isExporting && exportedFile == null) {
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                "Destination Directory",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                "${file.parentFile?.absolutePath}/${file.nameWithoutExtension}_dng/",
                                style = dev.rawrec.app.ui.theme.TelemetryStyle,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                "Writes uncompressed single-IFD0 16-bit DNG files with calibration opcodes and 48kHz stereo WAV in audio/audio.wav (compatible with DaVinci Resolve & Premiere Pro).",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "DNG Tone Curve Strategy",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        ChoiceChipRow(
                            options = listOf("Embed Tag 50981", "Bake Tone Pixels"),
                            selected = { opt ->
                                if (bakeToneInDng) opt.startsWith("Bake") else opt.startsWith("Embed")
                            },
                            onSelect = { label ->
                                bakeToneInDng = label.startsWith("Bake")
                            }
                        )
                        Text(
                            if (bakeToneInDng)
                                "Bakes the tone curve directly into raw 16-bit pixel samples (destructive)."
                            else
                                "Embeds the selected tone profile non-destructively as TIFF Tag 50981 (ProfileToneCurve). Raw Bayer pixels stay untouched.",
                            style = dev.rawrec.app.ui.theme.TelemetryStyle,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                // Live Dynamic Rotation Preview Thumbnail (MP4 Only)
                if (selectedFormat == ExportFormat.MP4 && !isExporting && exportedFile == null) {
                    thumbnailBitmap?.let { bmp ->
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = Color.Black,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(120.dp)
                                .clickable {
                                    selectedRotation = (selectedRotation + 90) % 360
                                }
                        ) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                val isSwapped = selectedRotation == 90 || selectedRotation == 270
                                val aspect = if (bmp.height > 0) bmp.width.toFloat() / bmp.height.toFloat() else 4f / 3f
                                val rotScale = if (isSwapped) (1f / aspect).coerceAtMost(1f) else 1f

                                Image(
                                    bitmap = bmp.asImageBitmap(),
                                    contentDescription = "Export Orientation Preview",
                                    modifier = Modifier
                                        .fillMaxHeight(0.88f)
                                        .aspectRatio(aspect)
                                        .graphicsLayer {
                                            rotationZ = selectedRotation.toFloat()
                                            scaleX = rotScale
                                            scaleY = rotScale
                                        },
                                    contentScale = ContentScale.Fit
                                )

                                Surface(
                                    shape = MaterialTheme.shapes.extraSmall,
                                    color = Color.Black.copy(alpha = 0.75f),
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(6.dp)
                                    ) {
                                    Text(
                                        text = "ORIENTATION: $selectedRotation° (TAP TO ROTATE)",
                                        style = DeckLabelStyle,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    }

                    // Video Rotation Selector & Preview
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "Export Orientation / Rotation",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            listOf(0, 90, 180, 270).forEach { deg ->
                                val isSel = selectedRotation == deg
                                Surface(
                                    shape = MaterialTheme.shapes.extraSmall,
                                    color = if (isSel) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
                                    border = if (isSel) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable { selectedRotation = deg }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(vertical = 8.dp),
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            RawRecIcons.Rotate,
                                            contentDescription = null,
                                            tint = if (isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                            modifier = Modifier.size(13.dp)
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text(
                                            "$deg°",
                                            style = DeckLabelStyle,
                                            fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                                            color = if (isSel) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Preset Selector
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "Color Look / Tone Preset",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Box {
                            Surface(
                                shape = MaterialTheme.shapes.small,
                                color = MaterialTheme.colorScheme.surface,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { dropdownExpanded = true }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        selectedProfile.displayName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Icon(
                                        RawRecIcons.Chevron,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }

                            DropdownMenu(
                                expanded = dropdownExpanded,
                                onDismissRequest = { dropdownExpanded = false }
                            ) {
                                ColorScience.ToneProfile.values()
                                    .filter { it != ColorScience.ToneProfile.CUSTOM_LUT }
                                    .forEach { profile ->
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    profile.displayName,
                                                    fontWeight = if (profile == selectedProfile) FontWeight.Bold else FontWeight.Normal,
                                                    color = if (profile == selectedProfile) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                                )
                                            },
                                            onClick = {
                                                selectedProfile = profile
                                                dropdownExpanded = false
                                            }
                                        )
                                    }
                            }
                        }
                    }
                }

                // Exporting Progress
                if (isExporting) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                "Grading & Muxing...",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                "${(exportProgress * 100).toInt()}% ($currentFrame/$totalFrames)",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        LinearProgressIndicator(
                            progress = { exportProgress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                        )
                    }
                }

                // Success State
                if (exportedFile != null) {
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = Color(0xFF1B3D2B),
                        border = BorderStroke(1.dp, Color(0xFF34C759))
                    ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                "✓ EXPORT COMPLETE",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF34C759)
                            )
                            Text(
                                exportedFile?.absolutePath ?: "",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White
                            )
                        }
                    }
                }

                // Error State
                if (exportError != null) {
                    Text(
                        text = "Export failed: $exportError",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            if (exportedFile != null) {
                Button(onClick = onDismiss) {
                    Text("Done")
                }
            } else if (!isExporting) {
                Button(
                    onClick = {
                        isExporting = true
                        exportError = null
                        wakeLock?.acquire(30 * 60 * 1000L) // max 30 min for large takes
                        exportJob = scope.launch(Dispatchers.IO) {
                            try {
                                if (selectedFormat == ExportFormat.MP4) {
                                    val outName = "${file.nameWithoutExtension}_${selectedProfile.id}.mp4"
                                    val outFile = File(file.parentFile, outName)
                                    val res = dev.rawrec.app.export.AndroidVideoExporter.export(
                                        rvspPath = file.absolutePath,
                                        outMp4Path = outFile.absolutePath,
                                        profile = selectedProfile,
                                        rotationDegrees = selectedRotation,
                                        onProgress = { cur, tot ->
                                            currentFrame = cur
                                            totalFrames = tot
                                            exportProgress = if (tot > 0) cur.toFloat() / tot else 0f
                                        }
                                    )
                                    withContext(Dispatchers.Main) {
                                        isExporting = false
                                        exportedFile = res
                                        onExportComplete(res)
                                    }
                                } else {
                                    val outDir = File(file.parentFile, "${file.nameWithoutExtension}_dng")
                                    outDir.mkdirs()
                                    runCatching { File(outDir, ".nomedia").createNewFile() }
                                    Rvtool.extract(
                                        path = file.absolutePath,
                                        outDir = outDir.absolutePath,
                                        profileName = selectedProfile.id,
                                        bakeTone = bakeToneInDng,
                                        compensateDrops = true,
                                        onProgress = { cur, tot ->
                                            currentFrame = cur
                                            totalFrames = tot
                                            exportProgress = if (tot > 0) cur.toFloat() / tot else 0f
                                        },
                                        isCancelled = { exportJob?.isCancelled == true }
                                    )
                                    withContext(Dispatchers.Main) {
                                        isExporting = false
                                        exportedFile = outDir
                                        onExportComplete(outDir)
                                    }
                                }
                            } catch (e: Exception) {
                                withContext(Dispatchers.Main) {
                                    isExporting = false
                                    exportError = e.message ?: e.toString()
                                }
                            } finally {
                                if (wakeLock?.isHeld == true) {
                                    wakeLock.release()
                                }
                            }
                        }
                    }
                ) {
                    Text(if (selectedFormat == ExportFormat.MP4) "Start MP4 Export" else "Start DNG Export")
                }
            } else {
                OutlinedButton(
                    onClick = {
                        exportJob?.cancel()
                        isExporting = false
                        if (wakeLock?.isHeld == true) {
                            wakeLock.release()
                        }
                    },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Cancel")
                }
            }
        },
        dismissButton = {
            if (!isExporting && exportedFile == null) {
                OutlinedButton(onClick = onDismiss) {
                    Text("Close")
                }
            }
        }
    )
}

private fun formatFileSize(bytes: Long): String = when {
    bytes >= 1 shl 30 -> "%.2f GB".format(bytes.toDouble() / (1 shl 30))
    bytes >= 1 shl 20 -> "%.1f MB".format(bytes.toDouble() / (1 shl 20))
    bytes >= 1 shl 10 -> "%.0f KB".format(bytes.toDouble() / (1 shl 10))
    else -> "$bytes B"
}
