package dev.rawrec.app.ui.gallery

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.view.Surface
import android.view.TextureView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.rawrec.app.playback.RvspAudioPlayer
import dev.rawrec.app.ui.components.RawRecIcons
import dev.rawrec.app.ui.components.rotateIcon
import dev.rawrec.app.ui.theme.DeckLabelStyle
import dev.rawrec.app.ui.theme.DeckTimecodeStyle
import dev.rawrec.tool.ColorScience
import dev.rawrec.tool.Rvtool
import dev.rawrec.tool.gpu.GpuManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile

@Composable
fun GalleryPlayerDeck(
    file: File,
    autoPlay: Boolean = true,
    onBack: () -> Unit,
    onExportRequested: (File, Int, Bitmap?) -> Unit,
    modifier: Modifier = Modifier
) {
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    var videoRotation by remember { mutableIntStateOf(0) }
    var isLoaded by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }

    var header by remember { mutableStateOf<Rvtool.Header?>(null) }
    var frames by remember { mutableStateOf<List<Rvtool.FrameRec>>(emptyList()) }
    var audioRecords by remember { mutableStateOf<List<Rvtool.AudioRec>>(emptyList()) }

    var audioPlayer by remember { mutableStateOf<RvspAudioPlayer?>(null) }
    var currentFrameIndex by remember { mutableIntStateOf(0) }
    var isPlaying by remember { mutableStateOf(autoPlay) }
    var isRepeat by remember { mutableStateOf(true) }
    var isMuted by remember { mutableStateOf(false) }
    var selectedProfile by remember { mutableStateOf(ColorScience.ToneProfile.CINE_FILMIC) }
    var measuredFps by remember { mutableDoubleStateOf(0.0) }

    var currentBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isDecodingFrame by remember { mutableStateOf(false) }

    val isMp4 = remember(file) { file.name.endsWith(".mp4", ignoreCase = true) }
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var mpDurationMs by remember { mutableIntStateOf(0) }
    var mpPositionMs by remember { mutableIntStateOf(0) }
    var mpWidth by remember { mutableIntStateOf(0) }
    var mpHeight by remember { mutableIntStateOf(0) }

    // Parallel prefetch ring buffer & cache
    val prefetchCache = remember { java.util.concurrent.ConcurrentHashMap<Int, Bitmap>() }
    val bitmapPool = remember { java.util.concurrent.ConcurrentLinkedQueue<Bitmap>() }
    val rawPayloadPool = remember { java.util.concurrent.ConcurrentLinkedQueue<ByteArray>() }
    var sharedRaf by remember { mutableStateOf<RandomAccessFile?>(null) }
    val decoder = remember(header) {
        runCatching {
            dev.rawrec.app.codec.FrameCodecs.byId(header?.videoCodec ?: "ZSTD").openDecoder()
        }.getOrNull()
    }

    // Open file & scan records (MP4 vs RVSP)
    LaunchedEffect(file) {
        if (isMp4) {
            withContext(Dispatchers.IO) {
                runCatching {
                    val mp = MediaPlayer()
                    mp.setDataSource(file.absolutePath)
                    mp.isLooping = isRepeat
                    mp.prepare()
                    val w = mp.videoWidth
                    val h = mp.videoHeight
                    val dur = mp.duration
                    withContext(Dispatchers.Main) {
                        mediaPlayer = mp
                        mpDurationMs = dur
                        mpWidth = w
                        mpHeight = h
                        isLoaded = true
                        if (isPlaying) mp.start()
                    }
                }.onFailure {
                    withContext(Dispatchers.Main) {
                        loadError = it.message ?: "Failed to open MP4 video"
                    }
                }
            }
        } else {
            withContext(Dispatchers.IO) {
                runCatching {
                    val (h, raf) = Rvtool.openHeader(file.absolutePath)
                    val (scannedFrames, scannedAudio) = Rvtool.scanRecords(raf)
                    header = h
                    frames = scannedFrames
                    audioRecords = scannedAudio
                    sharedRaf = raf
                    val player = RvspAudioPlayer(file, audioRecords)
                    audioPlayer = player
                    isLoaded = true
                }.onFailure {
                    loadError = it.message ?: "Failed to open take"
                }
            }
        }
    }

    LaunchedEffect(isPlaying, isMp4) {
        if (!isMp4) return@LaunchedEffect
        while (isActive) {
            mediaPlayer?.let { mp ->
                if (mp.isPlaying) {
                    mpPositionMs = mp.currentPosition
                }
            }
            delay(33)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            runCatching {
                mediaPlayer?.stop()
                mediaPlayer?.release()
                mediaPlayer = null
            }
            audioPlayer?.release()
            runCatching { sharedRaf?.close() }
            sharedRaf = null
            runCatching { decoder?.close() }
            prefetchCache.values.forEach { runCatching { it.recycle() } }
            prefetchCache.clear()
            bitmapPool.forEach { runCatching { it.recycle() } }
            bitmapPool.clear()
            rawPayloadPool.clear()
            currentBitmap = null
        }
    }

    suspend fun decodeFrame(frameIdx: Int, h: Rvtool.Header, profile: ColorScience.ToneProfile): Bitmap? {
        val cached = prefetchCache.remove(frameIdx)
        if (cached != null && !cached.isRecycled) {
            return cached
        }
        val frameRec = frames.getOrNull(frameIdx) ?: return null
        return withContext(Dispatchers.IO) {
            val raf = sharedRaf ?: return@withContext null
            try {
                val t0 = System.currentTimeMillis()
                val rawPayload = synchronized(raf) {
                    Rvtool.readPayload(raf, frameRec)
                }
                val t1 = System.currentTimeMillis()
                val expectedRaw = if (h.packing == 1) (h.width * h.height / 4 * 5) else (h.width * h.height * 2)
                var rawBuf = rawPayloadPool.poll()
                if (rawBuf == null || rawBuf.size != expectedRaw) {
                    rawBuf = ByteArray(expectedRaw)
                }
                val dec = decoder
                val decoded = if (dec != null && h.videoCodec == "ZSTD") {
                    val rc = dec.decodeInto(rawPayload, rawBuf)
                    if (rc > 0) rawBuf else Rvtool.decodedPayload(h, rawPayload)
                } else {
                    Rvtool.decodedPayload(h, rawPayload)
                }
                val t2 = System.currentTimeMillis()

                val outW = h.width / 4
                val outH = h.height / 4
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
                    profile = profile,
                    enableVignette = true,
                    downsample = 2
                )
                if (decoded === rawBuf) {
                    rawPayloadPool.offer(rawBuf)
                }
                val t3 = System.currentTimeMillis()

                var targetBmp = bitmapPool.poll()
                if (targetBmp == null || targetBmp.width != outW || targetBmp.height != outH || targetBmp.isRecycled) {
                    targetBmp = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
                }

                targetBmp.setPixels(argb, 0, outW, 0, 0, outW, outH)
                val t4 = System.currentTimeMillis()
                dev.rawrec.app.util.AppLog.i("FrameTiming", "frame=$frameIdx read=${t1 - t0}ms zstd=${t2 - t1}ms gpu=${t3 - t2}ms bmp=${t4 - t3}ms total=${t4 - t0}ms")
                targetBmp
            } catch (t: Throwable) {
                dev.rawrec.app.util.AppLog.e("GalleryPlayer", "Frame decode error: ${t.message}", t)
                null
            }
        }
    }

    // Dual-worker parallel lookahead prefetcher running across performance CPU cores
    LaunchedEffect(isPlaying, isLoaded, selectedProfile) {
        if (!isPlaying || !isLoaded || frames.isEmpty() || header == null) return@LaunchedEffect
        val h = header ?: return@LaunchedEffect
        val outW = h.width / 4
        val outH = h.height / 4

        withContext(Dispatchers.IO) {
            val raf = sharedRaf ?: return@withContext
            val workerCount = 2
            kotlinx.coroutines.coroutineScope {
                for (workerId in 0 until workerCount) {
                    launch {
                        var prefetchIdx = currentFrameIndex + 1 + workerId

                        while (isActive && isPlaying) {
                            if (prefetchIdx < currentFrameIndex + 1 || prefetchIdx > currentFrameIndex + 8) {
                                prefetchIdx = currentFrameIndex + 1 + workerId
                            }

                            if (prefetchIdx < frames.size && prefetchIdx <= currentFrameIndex + 6) {
                                if (!prefetchCache.containsKey(prefetchIdx)) {
                                    val frameRec = frames[prefetchIdx]
                                    try {
                                        val rawPayload = synchronized(raf) {
                                            Rvtool.readPayload(raf, frameRec)
                                        }
                                        val expectedRaw = if (h.packing == 1) (h.width * h.height / 4 * 5) else (h.width * h.height * 2)
                                        var rawBuf = rawPayloadPool.poll()
                                        if (rawBuf == null || rawBuf.size != expectedRaw) {
                                            rawBuf = ByteArray(expectedRaw)
                                        }
                                        val dec = decoder
                                        val decoded = if (dec != null && h.videoCodec == "ZSTD") {
                                            val rc = dec.decodeInto(rawPayload, rawBuf)
                                            if (rc > 0) rawBuf else Rvtool.decodedPayload(h, rawPayload)
                                        } else {
                                            Rvtool.decodedPayload(h, rawPayload)
                                        }

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
                                            enableVignette = true,
                                            downsample = 2
                                        )
                                        if (decoded === rawBuf) {
                                            rawPayloadPool.offer(rawBuf)
                                        }

                                        var targetBmp = bitmapPool.poll()
                                        if (targetBmp == null || targetBmp.width != outW || targetBmp.height != outH || targetBmp.isRecycled) {
                                            targetBmp = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
                                        }
                                        targetBmp.setPixels(argb, 0, outW, 0, 0, outW, outH)
                                        prefetchCache[prefetchIdx] = targetBmp
                                    } catch (_: Throwable) {}
                                }
                                prefetchIdx += workerCount
                            } else {
                                delay(4)
                            }

                            // Evict stale cached bitmaps older than currentFrameIndex - 1
                            if (workerId == 0) {
                                prefetchCache.keys.filter { it < currentFrameIndex - 1 }.forEach { staleKey ->
                                    prefetchCache.remove(staleKey)?.let { staleBmp ->
                                        if (!staleBmp.isRecycled && !bitmapPool.contains(staleBmp)) {
                                            bitmapPool.offer(staleBmp)
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

    // Frame decode when paused / scrubbing / step / look switch
    LaunchedEffect(currentFrameIndex, selectedProfile, isLoaded, isPlaying) {
        if (isPlaying || !isLoaded || frames.isEmpty() || header == null) return@LaunchedEffect
        val h = header ?: return@LaunchedEffect
        isDecodingFrame = true
        val bmp = decodeFrame(currentFrameIndex, h, selectedProfile)
        if (bmp != null) {
            currentBitmap = bmp
        }
        isDecodingFrame = false
    }

    // Playback loop with real-time master clock synchronization
    LaunchedEffect(isPlaying, isLoaded, selectedProfile) {
        if (!isPlaying || !isLoaded || frames.isEmpty() || header == null) {
            measuredFps = 0.0
            return@LaunchedEffect
        }
        val h = header ?: return@LaunchedEffect
        val effectiveFps = if (h.fpsMilli > 0) (h.fpsMilli / 1000.0) else 30.0
        val frameIntervalMs = (1000.0 / effectiveFps).toLong().coerceIn(16L, 100L)

        var startFrameIdx = currentFrameIndex
        var startFrameTsNs = frames.getOrNull(startFrameIdx)?.tsNs ?: 0L
        audioPlayer?.play(startFrameTsNs)

        var playbackStartRealtimeNs = System.nanoTime()
        var lastWindowStartNs = System.nanoTime()
        var framesPresentedInWindow = 0

        while (isActive && isPlaying) {
            val bmp = decodeFrame(currentFrameIndex, h, selectedProfile)
            if (bmp != null) {
                val old = currentBitmap
                currentBitmap = bmp
                if (old != null && old !== bmp && !old.isRecycled && !bitmapPool.contains(old)) {
                    bitmapPool.offer(old)
                }
                framesPresentedInWindow++
                val nowNs = System.nanoTime()
                val windowElapsedNs = nowNs - lastWindowStartNs
                if (windowElapsedNs >= 350_000_000L) { // update every 350ms
                    measuredFps = (framesPresentedInWindow * 1e9) / windowElapsedNs
                    framesPresentedInWindow = 0
                    lastWindowStartNs = nowNs
                }
            }

            // Real-time hardware audio clock synchronization (sample-accurate lip-sync)
            val audioClockTs = audioPlayer?.getHardwareAudioTimeNs()
            val targetMediaTsNs = if (audioClockTs != null && audioClockTs > 0L) {
                audioClockTs
            } else {
                val elapsedNs = System.nanoTime() - playbackStartRealtimeNs
                startFrameTsNs + elapsedNs
            }

            // Match presentation frame with the real-time audio playback clock
            var nextIdx = currentFrameIndex + 1
            while (nextIdx < frames.size && frames[nextIdx].tsNs <= targetMediaTsNs) {
                nextIdx++
            }

            if (nextIdx < frames.size) {
                val nextTs = frames[nextIdx].tsNs
                val remainingNs = nextTs - targetMediaTsNs
                val delayMs = (remainingNs / 1_000_000L).coerceIn(2L, frameIntervalMs)
                delay(delayMs)
                currentFrameIndex = nextIdx
            } else {
                if (isRepeat) {
                    val firstTs = frames.firstOrNull()?.tsNs ?: 0L
                    currentFrameIndex = 0
                    startFrameIdx = 0
                    startFrameTsNs = firstTs
                    audioPlayer?.seekTo(firstTs)
                    playbackStartRealtimeNs = System.nanoTime()
                    lastWindowStartNs = System.nanoTime()
                    framesPresentedInWindow = 0
                    prefetchCache.clear()
                    delay(frameIntervalMs)
                    continue
                } else {
                    isPlaying = false
                    audioPlayer?.pause()
                    measuredFps = 0.0
                    break
                }
            }
        }
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = Color(0xFF090C12)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 1. Studio Deck Top Header
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Surface(
                            shape = MaterialTheme.shapes.extraSmall,
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                            modifier = Modifier
                                .size(36.dp)
                                .clickable {
                                    audioPlayer?.pause()
                                    onBack()
                                }
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text("←", color = MaterialTheme.colorScheme.primary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        Column {
                            Text(
                                text = file.name,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            if (isMp4) {
                                Text(
                                    text = if (mpWidth > 0) "${mpWidth}×${mpHeight} · H.264 · MP4 VIDEO" else "H.264 · MP4 VIDEO",
                                    style = DeckLabelStyle,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                header?.let { h ->
                                    Text(
                                        text = "${h.width}×${h.height} · ${h.videoCodec} · ${if (h.packing == 1) "MIPI10" else "RAW16"}",
                                        style = DeckLabelStyle,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Audio Badge
                        Surface(
                            shape = MaterialTheme.shapes.extraSmall,
                            color = if (isMp4 || audioRecords.isNotEmpty()) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f) else MaterialTheme.colorScheme.surfaceContainerHighest
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    if (isMp4 || audioRecords.isNotEmpty()) RawRecIcons.Mic else RawRecIcons.VolumeMute,
                                    contentDescription = null,
                                    tint = if (isMp4 || audioRecords.isNotEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                    modifier = Modifier.size(12.dp)
                                )
                                Text(
                                    text = if (isMp4) "AUDIO STREAM" else if (audioRecords.isNotEmpty()) "48kHz PCM" else "NO AUDIO",
                                    style = DeckLabelStyle,
                                    color = if (isMp4 || audioRecords.isNotEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                                )
                            }
                        }

                        // Rotate Button
                        Surface(
                            shape = MaterialTheme.shapes.extraSmall,
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                            modifier = Modifier
                                .height(34.dp)
                                .clickable {
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    videoRotation = (videoRotation + 90) % 360
                                }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    RawRecIcons.Rotate,
                                    contentDescription = "Rotate",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(15.dp)
                                )
                                Text(
                                    text = "${videoRotation}°",
                                    style = DeckLabelStyle,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }

                        // Export Button (only for RAW RVSP)
                        if (!isMp4) {
                            Surface(
                                shape = MaterialTheme.shapes.extraSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .height(34.dp)
                                    .clickable {
                                        audioPlayer?.pause()
                                        isPlaying = false
                                        onExportRequested(file, videoRotation, currentBitmap)
                                    }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        RawRecIcons.Export,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Text(
                                        "EXPORT",
                                        style = DeckLabelStyle,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimary
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 2. Video Viewport Surface
            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                if (!isLoaded) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                } else if (loadError != null) {
                    Text(
                        text = "Error: $loadError",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else {
                    val boxW = constraints.maxWidth.toFloat()
                    val boxH = constraints.maxHeight.toFloat()
                    val vidAspect = if (isMp4) {
                        if (mpHeight > 0) mpWidth.toFloat() / mpHeight.toFloat() else 16f / 9f
                    } else {
                        val h = header
                        if (h != null && h.height > 0) h.width.toFloat() / h.height.toFloat() else 4f / 3f
                    }
                    val contentW = if (boxH * vidAspect <= boxW) boxH * vidAspect else boxW
                    val contentH = contentW / vidAspect
                    val isSwapped = videoRotation == 90 || videoRotation == 270
                    val rotScale = if (isSwapped && contentW > 0f && contentH > 0f) {
                        minOf(boxW / contentH, boxH / contentW).coerceAtMost(1f)
                    } else 1f

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                rotationZ = videoRotation.toFloat()
                                scaleX = rotScale
                                scaleY = rotScale
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        if (isMp4) {
                            AndroidView(
                                factory = { ctx ->
                                    TextureView(ctx).apply {
                                        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                                                mediaPlayer?.setSurface(Surface(st))
                                            }
                                            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                                            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                                                mediaPlayer?.setSurface(null)
                                                return true
                                            }
                                            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            currentBitmap?.let { bmp ->
                                Image(
                                    bitmap = bmp.asImageBitmap(),
                                    contentDescription = "Video Frame",
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Fit
                                )
                            }
                        }
                    }

                    // On-Screen Timecode & Frame Telemetry
                    val currentTsNs = frames.getOrNull(currentFrameIndex)?.tsNs ?: 0L
                    val firstTsNs = frames.firstOrNull()?.tsNs ?: 0L
                    val elapsedMs = if (isMp4) mpPositionMs.toLong() else if (currentTsNs >= firstTsNs) (currentTsNs - firstTsNs) / 1_000_000L else 0L
                    val fps = header?.let { if (it.fpsMilli > 0) it.fpsMilli / 1000.0 else 30.0 } ?: 30.0

                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(12.dp)
                    ) {
                        Surface(
                            shape = MaterialTheme.shapes.extraSmall,
                            color = Color.Black.copy(alpha = 0.75f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = formatSmpteTimecode(elapsedMs, fps),
                                    style = DeckTimecodeStyle,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontSize = 15.sp
                                )
                                Text(
                                    text = if (isMp4) "${mpPositionMs / 1000}s / ${(mpDurationMs / 1000).coerceAtLeast(1)}s" else "${currentFrameIndex + 1} / ${frames.size}",
                                    style = DeckLabelStyle,
                                    color = Color.White
                                )
                                val displayFps = if (isPlaying && measuredFps > 0.0) measuredFps else if (isPlaying) fps else 0.0
                                Text(
                                    text = if (isPlaying) {
                                        if (isMp4) "PLAYING" else "%.1f FPS".format(displayFps)
                                    } else "PAUSED",
                                    style = DeckLabelStyle,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isPlaying) {
                                        if (isMp4 || displayFps >= 27.5 || measuredFps == 0.0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.outline
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // 3. Tone / Look Preset Quick Selector Strip (only for RAW RVSP takes)
            if (!isMp4) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "LOOK:",
                            style = DeckLabelStyle,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(end = 4.dp)
                        )

                        ColorScience.ToneProfile.values()
                            .filter { it != ColorScience.ToneProfile.CUSTOM_LUT }
                            .forEach { profile ->
                                val isSel = profile == selectedProfile
                                Surface(
                                    shape = MaterialTheme.shapes.extraSmall,
                                    color = if (isSel) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
                                    border = if (isSel) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
                                    modifier = Modifier.clickable {
                                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        prefetchCache.values.forEach { if (!it.isRecycled && !bitmapPool.contains(it)) bitmapPool.offer(it) }
                                        prefetchCache.clear()
                                        selectedProfile = profile
                                    }
                                ) {
                                    Text(
                                        text = profile.displayName.substringBefore(" ("),
                                        style = DeckLabelStyle,
                                        fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isSel) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                                    )
                                }
                            }
                    }
                }
            }

            // 4. Cinema Transport Controls Deck
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Scrubber Slider
                    val sliderVal = if (isMp4) mpPositionMs.toFloat() else currentFrameIndex.toFloat()
                    val sliderMax = if (isMp4) mpDurationMs.toFloat().coerceAtLeast(1f) else (frames.size - 1).toFloat().coerceAtLeast(1f)
                    if (isMp4 || frames.size > 1) {
                        Slider(
                            value = sliderVal.coerceIn(0f, sliderMax),
                            onValueChange = {
                                if (isMp4) {
                                    mpPositionMs = it.toInt()
                                    mediaPlayer?.seekTo(it.toInt())
                                } else {
                                    isPlaying = false
                                    audioPlayer?.pause()
                                    prefetchCache.values.forEach { if (!it.isRecycled && !bitmapPool.contains(it)) bitmapPool.offer(it) }
                                    prefetchCache.clear()
                                    currentFrameIndex = it.toInt().coerceIn(0, frames.size - 1)
                                    frames.getOrNull(currentFrameIndex)?.let { fr ->
                                        audioPlayer?.seekTo(fr.tsNs)
                                    }
                                }
                            },
                            valueRange = 0f..sliderMax,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    // Transport Buttons Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Left: Repeat / Loop toggle
                        Surface(
                            shape = MaterialTheme.shapes.extraSmall,
                            color = if (isRepeat) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
                            border = if (isRepeat) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
                            modifier = Modifier
                                .size(38.dp)
                                .clickable {
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    isRepeat = !isRepeat
                                    if (isMp4) {
                                        mediaPlayer?.isLooping = isRepeat
                                    }
                                }
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    RawRecIcons.Repeat,
                                    contentDescription = "Loop Repeat",
                                    tint = if (isRepeat) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                        // Center: Step -1, Play/Pause, Step +1
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Step -1
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                modifier = Modifier
                                    .size(42.dp)
                                    .clickable {
                                        if (isMp4) {
                                            val newPos = (mpPositionMs - 1000).coerceAtLeast(0)
                                            mpPositionMs = newPos
                                            mediaPlayer?.seekTo(newPos)
                                        } else {
                                            isPlaying = false
                                            audioPlayer?.pause()
                                            if (currentFrameIndex > 0) {
                                                currentFrameIndex--
                                                frames.getOrNull(currentFrameIndex)?.let { audioPlayer?.seekTo(it.tsNs) }
                                            }
                                        }
                                    }
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        RawRecIcons.StepBack,
                                        contentDescription = "Step -1",
                                        tint = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }

                            // Play / Pause
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .size(54.dp)
                                    .clickable {
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        if (isMp4) {
                                            if (isPlaying) {
                                                mediaPlayer?.pause()
                                                isPlaying = false
                                            } else {
                                                mediaPlayer?.start()
                                                isPlaying = true
                                            }
                                        } else {
                                            if (isPlaying) {
                                                isPlaying = false
                                                audioPlayer?.pause()
                                            } else {
                                                if (currentFrameIndex >= frames.size - 1) {
                                                    currentFrameIndex = 0
                                                }
                                                isPlaying = true
                                            }
                                        }
                                    }
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        if (isPlaying) RawRecIcons.Pause else RawRecIcons.Play,
                                        contentDescription = if (isPlaying) "Pause" else "Play",
                                        tint = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }

                            // Step +1
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                modifier = Modifier
                                    .size(42.dp)
                                    .clickable {
                                        if (isMp4) {
                                            val newPos = (mpPositionMs + 1000).coerceAtMost(mpDurationMs)
                                            mpPositionMs = newPos
                                            mediaPlayer?.seekTo(newPos)
                                        } else {
                                            isPlaying = false
                                            audioPlayer?.pause()
                                            if (currentFrameIndex + 1 < frames.size) {
                                                currentFrameIndex++
                                                frames.getOrNull(currentFrameIndex)?.let { audioPlayer?.seekTo(it.tsNs) }
                                            }
                                        }
                                    }
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        RawRecIcons.StepForward,
                                        contentDescription = "Step +1",
                                        tint = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }

                        // Right: Audio Mute Toggle
                        Surface(
                            shape = MaterialTheme.shapes.extraSmall,
                            color = if (!isMuted && (isMp4 || audioRecords.isNotEmpty())) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
                            border = if (!isMuted && (isMp4 || audioRecords.isNotEmpty())) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
                            modifier = Modifier
                                .size(38.dp)
                                .clickable {
                                    if (isMp4) {
                                        isMuted = !isMuted
                                        val v = if (isMuted) 0f else 1f
                                        mediaPlayer?.setVolume(v, v)
                                    } else if (audioRecords.isNotEmpty()) {
                                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        isMuted = audioPlayer?.toggleMute() ?: false
                                    }
                                }
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    if (isMuted || (!isMp4 && audioRecords.isEmpty())) RawRecIcons.VolumeMute else RawRecIcons.VolumeUp,
                                    contentDescription = "Mute",
                                    tint = if (!isMuted && (isMp4 || audioRecords.isNotEmpty())) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatSmpteTimecode(elapsedMs: Long, fps: Double): String {
    val totalSec = elapsedMs / 1000
    val ms = elapsedMs % 1000
    val frame = (ms * fps / 1000.0).toInt().coerceAtLeast(0)
    val s = totalSec % 60
    val m = (totalSec / 60) % 60
    val h = totalSec / 3600
    return "%02d:%02d:%02d:%02d".format(h, m, s, frame)
}
