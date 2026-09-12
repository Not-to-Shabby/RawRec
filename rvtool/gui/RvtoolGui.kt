package dev.rawrec.tool

import dev.rawrec.app.scopes.CinemaScopes
import dev.rawrec.app.inspect.FrameInspector
import java.awt.*
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine
import javax.swing.*
import javax.swing.border.AbstractBorder
import javax.swing.filechooser.FileNameExtensionFilter
import javax.swing.plaf.basic.BasicComboBoxUI
import javax.swing.plaf.basic.BasicComboPopup
import javax.swing.plaf.basic.BasicSliderUI
import javax.swing.plaf.basic.ComboPopup
import kotlin.math.max

/**
 * rvtool GUI — RAWREC Studio Deck for RVSP takes.
 *
 * Professional cinema studio player/scrubber matching RawRec's cinema mobile UI.
 * Features deep Titanium Slate styling, Electric Cyan telemetry, live multi-channel
 * scopes (Histogram, Peaking, False Color, Zebras), nanosecond frame pacing,
 * 3D LUT preview, OpenCL/Vulkan compute selection, and CinemaDNG/HLG MP4 export.
 */
object RvtoolGui {

    // ---- Studio Deck Color Tokens ----
    val BG = Color(14, 17, 24)           // #0E1118 (Titanium Slate)
    val PANEL = Color(22, 28, 38)        // #161C26 (Gunmetal)
    val PANEL_HI = Color(31, 39, 54)     // #1F2736 (Elevated Card)
    val BORDER = Color(43, 54, 72)       // #2B3648 (1px Card Border)
    val BORDER_HI = Color(65, 80, 105)   // #415069 (Highlight Border)
    val FG = Color(240, 244, 248)        // #F0F4F8 (Primary Text)
    val FG_DIM = Color(138, 148, 166)    // #8A94A6 (Subdued Labels)
    val ACCENT = Color(0, 229, 255)      // #00E5FF (Electric Cyan)
    val ACCENT_BG = Color(0, 48, 64)     // #003040 (Active Pill Container)
    val TALLY = Color(255, 45, 85)       // #FF2D55 (Cinema Crimson)
    val MONO = Font("Consolas", Font.PLAIN, 12)
    val MONO_BOLD = Font("Consolas", Font.BOLD, 12)
    val MONO_TIMECODE = Font("Consolas", Font.BOLD, 18)
    val LABEL_FONT = Font("SansSerif", Font.BOLD, 10)

    // ---- state ----
    private var raf: java.io.RandomAccessFile? = null
    private var header: Rvtool.Header? = null
    private var frames: List<Rvtool.FrameRec> = emptyList()
    private var audioRecs: List<Rvtool.AudioRec> = emptyList()
    private var filePath: File? = null

    private const val RAM_CACHE_CAPACITY = 96
    private const val PREFETCH_LOOKAHEAD = 48

    private val decoder = Executors.newSingleThreadExecutor { r ->
        Thread(r, "rvtool-gui-decode").apply { isDaemon = true }
    }
    private val prefetchWorkers = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(4, 8)
    private val prefetchPool = Executors.newFixedThreadPool(prefetchWorkers) { r ->
        Thread(r, "rvtool-gui-prefetch").apply { isDaemon = true; priority = Thread.NORM_PRIORITY }
    }
    @Volatile private var renderGen = 0L
    private val prefetchInFlight = ConcurrentHashMap.newKeySet<Int>()
    private var loadedCustomLut: CubeLut? = null
    private var loadedLutFile: File? = null

    // LRU preview cache: index -> painted image (half-res RGB/gray render)
    private val previewCache = object : LinkedHashMap<Int, BufferedImage>(RAM_CACHE_CAPACITY, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, BufferedImage>) =
            size > RAM_CACHE_CAPACITY
    }

    @Volatile private var isPlaying = false
    @Volatile private var currentPlaybackIndex = 0
    private var playThread: Thread? = null
    @Volatile private var isProgrammaticSliderChange = false

    private var audioThread: Thread? = null
    @Volatile private var audioStop = false
    @Volatile private var isAudioEnabled = true
    private var audioLine: SourceDataLine? = null

    // ---- UI components ----
    private lateinit var frame: JFrame
    private lateinit var preview: PreviewPanel
    private lateinit var histPanel: HistogramPanel
    private lateinit var slider: JSlider
    private lateinit var infoArea: JTextArea
    private lateinit var timecodeLabel: JLabel
    private lateinit var statusBadge: JLabel
    private lateinit var frameReadout: JLabel
    private lateinit var verdict: JLabel
    private lateinit var playBtn: DeckToggleButton
    private lateinit var rgbView: DeckToggleButton
    private lateinit var grayView: DeckToggleButton
    private lateinit var toneBox: JComboBox<String>
    private lateinit var backendBox: JComboBox<String>
    private val applyWbCheck = DeckToggleButton("WB", true)
    private val vignetteCheck = DeckToggleButton("VIGN", true)
    private val peaking = DeckToggleButton("PEAK", false)
    private val falseColor = DeckToggleButton("FALSE", false)
    private val zebras = DeckToggleButton("ZEBRA", false)
    private val audioBtn = DeckToggleButton("AUDIO", false)
    private val curveBtn = DeckToggleButton("CURVE", true)
    private lateinit var curvePanel: CurvePlotPanel
    @Volatile private var isRepeat = true
    private val loopBtn = DeckToggleButton("LOOP", true)
    private val clipStatsLabel = JLabel("CLIP: 0.0% / 0.0%").apply {
        font = MONO
        foreground = FG_DIM
    }

    @JvmStatic
    fun main(args: Array<String>) {
        if (GraphicsEnvironment.isHeadless()) {
            println("rvtool gui: headless environment detected — a display is required.")
            println("usage: powershell tools\\rvtool.ps1 gui <file.rvsp>")
            return
        }
        SwingUtilities.invokeLater {
            installTheme()
            buildUi()
            val path = args.firstOrNull() ?: pickFile() ?: return@invokeLater
            openFile(File(path))
        }
    }

    private fun pickFile(): String? {
        val fc = JFileChooser(System.getProperty("user.dir"))
        fc.fileFilter = FileNameExtensionFilter("RVSP takes (*.rvsp)", "rvsp")
        return if (fc.showOpenDialog(null) == JFileChooser.APPROVE_OPTION)
            fc.selectedFile.absolutePath else null
    }

    private fun installTheme() {
        System.setProperty("awt.useSystemAAFontSettings", "on")
        System.setProperty("swing.aatext", "true")
        UIManager.put("Panel.background", BG)
        UIManager.put("Label.foreground", FG)
        UIManager.put("Label.background", PANEL)
        UIManager.put("Button.background", PANEL_HI)
        UIManager.put("Button.foreground", FG)
        UIManager.put("ComboBox.background", PANEL_HI)
        UIManager.put("ComboBox.foreground", FG)
        UIManager.put("ComboBox.selectionBackground", ACCENT_BG)
        UIManager.put("ComboBox.selectionForeground", ACCENT)
        UIManager.put("Slider.background", PANEL)
        UIManager.put("Slider.foreground", ACCENT)
    }

    // ---- UI construction ----
    private fun buildUi() {
        frame = JFrame("rvtool // RAWREC STUDIO DECK").apply {
            defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
            contentPane.background = BG
            preferredSize = Dimension(1440, 880)
        }

        // ==========================================
        // 1. TOP STUDIO HEADER TOOLBAR
        // ==========================================
        val toolbar = JPanel(BorderLayout(8, 0)).apply {
            background = PANEL
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, BORDER),
                BorderFactory.createEmptyBorder(6, 12, 6, 12)
            )
        }

        // Left cluster: Logo, Open, View mode
        val leftCluster = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            isOpaque = false
            add(JLabel("STUDIO DECK").apply {
                font = Font("SansSerif", Font.BOLD, 13)
                foreground = ACCENT
            })
            add(JLabel("RAW").apply {
                font = Font("SansSerif", Font.BOLD, 10)
                foreground = FG_DIM
            })
            add(Box.createHorizontalStrut(6))

            val openBtn = DeckButton("OPEN…").apply {
                toolTipText = "Open .rvsp take"
                addActionListener { pickFile()?.let { openFile(File(it)) } }
            }
            add(openBtn)
            add(Box.createHorizontalStrut(4))

            rgbView = DeckToggleButton("RGB", true)
            grayView = DeckToggleButton("GRAY", false)
            rgbView.addActionListener {
                rgbView.isSelected = true
                grayView.isSelected = false
                refreshCurrent()
            }
            grayView.addActionListener {
                grayView.isSelected = true
                rgbView.isSelected = false
                refreshCurrent()
            }
            add(rgbView)
            add(grayView)
            add(applyWbCheck.apply { addActionListener { refreshCurrent() } })
            add(vignetteCheck.apply { addActionListener { refreshCurrent() } })
        }

        // Center cluster: Engine, Tone profile, Import LUT (Non-wrapping BoxLayout)
        val centerCluster = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false

            add(Box.createHorizontalGlue())
            add(JLabel("ENGINE:").apply { font = LABEL_FONT; foreground = FG_DIM })
            add(Box.createHorizontalStrut(4))

            val backends = dev.rawrec.tool.gpu.BackendType.values()
            backendBox = JComboBox(backends.map { it.displayName }.toTypedArray()).apply {
                styleDeckCombo(this)
                preferredSize = Dimension(140, 26)
                maximumSize = Dimension(155, 26)
                selectedIndex = 0
                addActionListener {
                    val selected = backends.getOrElse(selectedIndex) { dev.rawrec.tool.gpu.BackendType.OPENCL }
                    dev.rawrec.tool.gpu.GpuManager.requestedBackendType = selected
                    val devName = dev.rawrec.tool.gpu.GpuManager.activeBackend.deviceName
                    verdict.text = "GPU Engine: $devName"
                    refreshCurrent()
                }
            }
            add(backendBox)
            add(Box.createHorizontalStrut(10))

            add(JLabel("LOOK:").apply { font = LABEL_FONT; foreground = FG_DIM })
            add(Box.createHorizontalStrut(4))

            val toneProfiles = ColorScience.ToneProfile.values()
            toneBox = JComboBox(toneProfiles.map { it.displayName }.toTypedArray()).apply {
                styleDeckCombo(this)
                preferredSize = Dimension(175, 26)
                maximumSize = Dimension(200, 26)
                selectedIndex = 0
                addActionListener { refreshCurrent() }
            }
            add(toneBox)
            add(Box.createHorizontalStrut(4))

            val importLutBtn = DeckButton("+ LUT").apply {
                toolTipText = "Import 3D / 1D LUT (.cube file)"
                border = BorderFactory.createEmptyBorder(4, 8, 4, 8)
                addActionListener { importLut() }
            }
            add(importLutBtn)
            add(Box.createHorizontalGlue())
        }

        // Right cluster: Studio Exports
        val rightCluster = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
            isOpaque = false
            val exportMp4 = DeckButton("EXPORT MP4", highlight = true).apply {
                toolTipText = "Export BT.2100 HLG MP4 with 16-bit PCM Audio"
                addActionListener { exportMp4() }
            }
            val extractDng = DeckButton("EXTRACT DNG…").apply {
                toolTipText = "Extract single-IFD CinemaDNG frame sequence"
                addActionListener { extractDng() }
            }
            val batchExport = DeckButton("BATCH…").apply {
                toolTipText = "Batch export all .rvsp takes in a folder"
                addActionListener { openBatchDialog() }
            }
            val exportRgb = DeckButton("RGB").apply {
                toolTipText = "Export current frame as RGB BMP"
                addActionListener { exportRgb() }
            }
            val exportBmp = DeckButton("BMP").apply {
                toolTipText = "Export current frame as raw grayscale BMP"
                addActionListener { exportBmp() }
            }
            val exportWav = DeckButton("WAV").apply {
                toolTipText = "Extract 16-bit 48kHz stereo WAV audio"
                addActionListener { exportWav() }
            }

            add(exportMp4)
            add(extractDng)
            add(batchExport)
            add(exportRgb)
            add(exportBmp)
            add(exportWav)
        }

        toolbar.add(leftCluster, BorderLayout.WEST)
        toolbar.add(centerCluster, BorderLayout.CENTER)
        toolbar.add(rightCluster, BorderLayout.EAST)

        // ==========================================
        // 2. CENTER PREVIEW MONITOR
        // ==========================================
        preview = PreviewPanel()
        histPanel = HistogramPanel()

        // Right side: Live Scope Rack
        val scopeRack = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = PANEL
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 1, 0, 0, BORDER),
                BorderFactory.createEmptyBorder(10, 12, 10, 12)
            )
            preferredSize = Dimension(240, 0)

            val scopeHeader = JLabel("SCOPE RACK").apply {
                font = LABEL_FONT
                foreground = ACCENT
            }
            add(scopeHeader)
            add(Box.createVerticalStrut(6))
            add(histPanel)
            add(Box.createVerticalStrut(4))
            add(clipStatsLabel)
            add(Box.createVerticalStrut(12))

            val overlayLabel = JLabel("VIEWFINDER OVERLAYS").apply {
                font = LABEL_FONT
                foreground = ACCENT
            }
            add(overlayLabel)
            add(Box.createVerticalStrut(8))

            val buttonStrip = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
                isOpaque = false
                peaking.addActionListener { refreshCurrent() }
                falseColor.addActionListener { refreshCurrent() }
                zebras.addActionListener { refreshCurrent() }
                add(peaking)
                add(falseColor)
                add(zebras)
            }
            add(buttonStrip)
            add(Box.createVerticalGlue())
        }

        val centerViewport = JPanel(BorderLayout()).apply {
            background = BG
            add(preview, BorderLayout.CENTER)
            add(scopeRack, BorderLayout.EAST)
        }

        // ==========================================
        // 3. LEFT TAKE INSPECTOR / TELEMETRY DECK
        // ==========================================
        infoArea = JTextArea("No take loaded.\nOpen a .rvsp recording file to inspect telemetry.").apply {
            font = MONO
            background = PANEL
            foreground = FG
            isEditable = false
            isOpaque = true
            lineWrap = false
            border = BorderFactory.createEmptyBorder(10, 12, 10, 12)
        }

        val infoScroll = JScrollPane(infoArea).apply {
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
            preferredSize = Dimension(300, 0)
            background = PANEL
            viewport.background = PANEL
            border = BorderFactory.createMatteBorder(0, 0, 0, 1, BORDER)
        }

        // ==========================================
        // 4. BOTTOM TIMELINE & TRANSPORT DECK
        // ==========================================
        timecodeLabel = JLabel("00:00:00:00").apply {
            font = MONO_TIMECODE
            foreground = FG
        }
        statusBadge = JLabel("STBY").apply {
            font = LABEL_FONT
            foreground = ACCENT
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(ACCENT, 1),
                BorderFactory.createEmptyBorder(2, 6, 2, 6)
            )
        }
        frameReadout = JLabel("FRAME: 0 / 0").apply {
            font = MONO
            foreground = FG_DIM
        }
        verdict = JLabel("viewfinder: idle").apply {
            font = MONO
            foreground = FG_DIM
        }

        slider = JSlider(0, 0).apply {
            setUI(DeckSliderUI(this))
            background = PANEL
            foreground = ACCENT
            majorTickSpacing = 30
            paintTicks = false
        }
        slider.addChangeListener {
            if (isProgrammaticSliderChange) return@addChangeListener
            val idx = slider.value
            if (isPlaying) {
                currentPlaybackIndex = idx
                if (isAudioEnabled && audioRecs.isNotEmpty()) {
                    startAudioPlayback(idx)
                }
            } else {
                requestFrame(idx, fast = slider.valueIsAdjusting)
            }
        }

        playBtn = DeckToggleButton("PLAY", false).apply {
            addActionListener { togglePlay() }
        }
        val stepBackBtn = DeckButton("|◀").apply {
            addActionListener { stepFrame(-1) }
        }
        val stepFwdBtn = DeckButton("▶|").apply {
            addActionListener { stepFrame(1) }
        }
        loopBtn.addActionListener { isRepeat = loopBtn.isSelected }
        audioBtn.addActionListener { toggleAudio() }

        val bottomDeck = JPanel(GridBagLayout()).apply {
            background = PANEL
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, BORDER),
                BorderFactory.createEmptyBorder(8, 16, 8, 16)
            )
        }
        curveBtn.addActionListener {
            curvePanel.isVisible = curveBtn.isSelected
            bottomDeck.revalidate()
            bottomDeck.repaint()
        }
        val gc = GridBagConstraints().apply {
            insets = Insets(2, 6, 2, 6)
            fill = GridBagConstraints.HORIZONTAL
        }

        // Row 1: Timecode, Status badge, Frame Readout, Verdict diagnostics
        gc.gridx = 0; gc.gridy = 0; gc.weightx = 1.0; gc.gridwidth = 7
        val telemetryRow = JPanel(FlowLayout(FlowLayout.LEFT, 12, 0)).apply {
            isOpaque = false
            add(statusBadge)
            add(timecodeLabel)
            add(Box.createHorizontalStrut(8))
            add(frameReadout)
            add(Box.createHorizontalStrut(16))
            add(verdict)
        }
        bottomDeck.add(telemetryRow, gc)

        // Row 2: Transport controls, Scopes/Telemetry toggles and Timeline Scrubber
        gc.gridy = 1; gc.gridwidth = 1; gc.weightx = 0.0
        bottomDeck.add(playBtn, gc)

        gc.gridx = 1; gc.weightx = 0.0
        bottomDeck.add(stepBackBtn, gc)

        gc.gridx = 2; gc.weightx = 0.0
        bottomDeck.add(stepFwdBtn, gc)

        gc.gridx = 3; gc.weightx = 0.0
        bottomDeck.add(loopBtn, gc)

        gc.gridx = 4; gc.weightx = 0.0
        bottomDeck.add(audioBtn, gc)

        gc.gridx = 5; gc.weightx = 0.0
        bottomDeck.add(curveBtn, gc)

        gc.gridx = 6; gc.weightx = 1.0; gc.fill = GridBagConstraints.HORIZONTAL
        bottomDeck.add(slider, gc)

        // Row 3: ISO & Exposure Telemetry Curve Plot
        curvePanel = CurvePlotPanel()
        gc.gridx = 0; gc.gridy = 2; gc.gridwidth = 7; gc.weightx = 1.0; gc.fill = GridBagConstraints.HORIZONTAL
        bottomDeck.add(curvePanel, gc)

        // Assembly
        frame.contentPane.apply {
            add(toolbar, BorderLayout.NORTH)
            add(centerViewport, BorderLayout.CENTER)
            add(bottomDeck, BorderLayout.SOUTH)
            add(infoScroll, BorderLayout.WEST)
        }
        frame.pack()
        frame.setLocationRelativeTo(null)
        frame.isVisible = true

        frame.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent?) { preview.repaint() }
        })
    }

    private fun styleDeckCombo(combo: JComboBox<*>) {
        combo.font = MONO
        combo.background = PANEL_HI
        combo.foreground = FG
        combo.border = BorderFactory.createLineBorder(BORDER, 1)
        combo.setUI(object : BasicComboBoxUI() {
            override fun createArrowButton(): JButton {
                return JButton("▾").apply {
                    font = Font("SansSerif", Font.PLAIN, 10)
                    background = PANEL_HI
                    foreground = ACCENT
                    border = BorderFactory.createEmptyBorder(0, 4, 0, 4)
                    isFocusPainted = false
                    isContentAreaFilled = false
                }
            }
            override fun createPopup(): ComboPopup {
                return BasicComboPopup(comboBox).apply {
                    border = BorderFactory.createLineBorder(BORDER, 1)
                }
            }
        })
    }

    // ---- file open / index ----
    private fun openFile(f: File) {
        try {
            stopPlayback()
            val (h, r) = Rvtool.openHeader(f.absolutePath)
            synchronized(previewCache) { previewCache.clear() }
            prefetchInFlight.clear()
            renderGen++
            raf?.close()
            raf = r; header = h; filePath = f
            val (fr, au) = r.let { Rvtool.scanRecords(it) }
            frames = fr; audioRecs = au
            val hasAudio = audioRecs.isNotEmpty()
            audioBtn.isEnabled = hasAudio
            audioBtn.isSelected = hasAudio && isAudioEnabled
            audioBtn.toolTipText = if (hasAudio) "Audio Monitor (Mute/Unmute)" else "No audio in this take"

            val meta = Rvtool.metaJson(r)
            val dur = if (h.fpsMilli > 0) frames.size / (h.fpsMilli / 1000.0) else 0.0
            val sb = StringBuilder()
            sb.append("─── TAKE TELEMETRY ───\n")
            sb.append("File     : ${f.name}\n")
            sb.append("Size     : ${"%.2f".format(f.length() / 1e9)} GB\n")
            sb.append("Camera   : ${h.cameraModel}\n")
            sb.append("Frames   : ${frames.size}\n")
            sb.append("Duration : ${"%.2f".format(dur)} s\n")
            sb.append("Base FPS : ${h.fpsMilli / 1000.0}\n\n")

            sb.append("─── SENSOR GEOMETRY ───\n")
            sb.append("Raster   : ${h.width} x ${h.height}\n")
            sb.append("Bit Depth: ${h.bitDepth}-bit\n")
            sb.append("CFA Pat  : ${cfaName(h.cfa)} (${h.cfa})\n")
            sb.append("Packing  : ${packingName(h.packing)}\n")
            sb.append("Codec    : ${h.videoCodec}\n\n")

            sb.append("─── COLOR CALIBRATION ───\n")
            sb.append("WhiteLvl : ${h.whiteLevel}\n")
            sb.append("BlackLvl : [${h.blackLevels.joinToString(", ")}]\n")
            sb.append("AsShot   : [${h.asShotNeutral.joinToString(", ") { "%.3f".format(it) }}]\n")
            sb.append("Matrix   :\n")
            for (row in 0..2) {
                sb.append("  [${h.colorMatrix.slice(row*3 until row*3+3).joinToString(", ") { "%.3f".format(it) }}]\n")
            }
            sb.append("\n─── CONTAINER META ───\n")
            meta.forEach { (k, v) -> sb.append("$k: $v\n") }

            infoArea.text = sb.toString()
            infoArea.caretPosition = 0

            isProgrammaticSliderChange = true
            slider.maximum = max(0, frames.size - 1)
            slider.value = 0
            isProgrammaticSliderChange = false
            playBtn.isSelected = false
            playBtn.text = "PLAY"
            playBtn.foreground = FG
            statusBadge.text = "STBY"
            statusBadge.foreground = ACCENT
            statusBadge.border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(ACCENT, 1),
                BorderFactory.createEmptyBorder(2, 6, 2, 6)
            )

            if (::curvePanel.isInitialized) {
                curvePanel.setFrames(frames)
                curvePanel.setCurrentFrame(0)
            }

            requestFrame(0, fast = false)
            triggerPrefetch(0)
        } catch (t: Throwable) {
            JOptionPane.showMessageDialog(frame, "open failed: ${t.message}", "rvtool", JOptionPane.ERROR_MESSAGE)
        }
    }

    private fun cfaName(cfa: Int): String = when (cfa) {
        0 -> "RGGB"; 1 -> "GRBG"; 2 -> "GBRG"; 3 -> "BGGR"; else -> "RAW"
    }

    private fun packingName(p: Int): String = when (p) {
        0 -> "UNPACKED_16"; 1 -> "MIPI_RAW10"; else -> "CUSTOM_$p"
    }

    // ---- frame decode/render ----
    private fun requestFrame(idx: Int, fast: Boolean) {
        val h = header ?: return
        val fr = frames.getOrNull(idx) ?: return
        val fps = if (h.fpsMilli > 0) h.fpsMilli / 1000.0 else 30.0
        val elapsedSec = (fr.tsNs - (frames.firstOrNull()?.tsNs ?: 0L)) / 1e9
        val totalSec = elapsedSec.coerceAtLeast(0.0)
        val hrs = (totalSec / 3600).toInt()
        val mins = ((totalSec % 3600) / 60).toInt()
        val secs = (totalSec % 60).toInt()
        val ff = ((totalSec - totalSec.toInt()) * fps).toInt().coerceIn(0, (fps - 1).toInt())

        timecodeLabel.text = "%02d:%02d:%02d:%02d".format(hrs, mins, secs, ff)
        frameReadout.text = "FRAME: %d / %d (%.2fs)".format(idx + 1, frames.size, totalSec)
        if (::curvePanel.isInitialized) {
            curvePanel.setCurrentFrame(idx)
        }
        val isoText = if (fr.iso == 0) "ISO AUTO" else "ISO ${fr.iso}"
        val expText = formatExp(fr.expNs)

        val gen = ++renderGen
        decoder.execute {
            try {
                val cached = synchronized(previewCache) { previewCache[idx] }
                val (img, diagText) = if (cached != null) {
                    cached to null
                } else {
                    val r = raf ?: return@execute
                    val payload = synchronized(r) { Rvtool.readPayload(r, fr) }
                    val decoded = Rvtool.decodedPayload(h, payload)
                    val samples = Rvtool.samplesOf(h, decoded)
                    val blackAvg = h.blackLevels.average().toInt()
                    val luma = Rvtool.luma8Of(samples, h.width, h.height, blackAvg, h.whiteLevel)
                    val rendered = renderImage(idx, decoded, samples, luma, h)
                    synchronized(previewCache) { previewCache[idx] = rendered }
                    val dText = if (!fast) {
                        val diag = FrameInspector.diagnose(samples, h.width, h.height, h.cfa, h.whiteLevel)
                        diag.verdict + "  " + diag.channels.joinToString(" ") {
                            "%s μ%.0f σ%.0f".format(it.name, it.mean, it.stdDev)
                        }
                    } else null
                    rendered to dText
                }
                if (gen == renderGen) SwingUtilities.invokeLater {
                    if (gen == renderGen) {
                        verdict.text = "$expText | $isoText" + if (diagText != null) " | $diagText" else ""
                        preview.setImage(img)
                        preview.repaint()
                    }
                }
            } catch (t: Throwable) {
                SwingUtilities.invokeLater {
                    if (gen == renderGen) verdict.text = "decode failed: ${t.message}"
                }
            }
        }
    }

    private fun renderImage(
        idx: Int,
        decoded: ByteArray,
        samples: ShortArray,
        luma: ByteArray,
        h: Rvtool.Header
    ): BufferedImage {
        val w = h.width / 2; val hh = h.height / 2
        val selectedItem = toneBox.selectedItem as? String ?: ""
        val isLutSelected = selectedItem.startsWith("LUT:") && loadedCustomLut != null
        val profile = if (rgbView.isSelected) {
            if (isLutSelected) {
                ColorScience.ToneProfile.CUSTOM_LUT
            } else {
                ColorScience.ToneProfile.values().getOrElse(toneBox.selectedIndex) { ColorScience.ToneProfile.DEFAULT }
            }
        } else {
            ColorScience.ToneProfile.DEFAULT
        }
        val activeLut = if (profile == ColorScience.ToneProfile.CUSTOM_LUT) loadedCustomLut else null
        var argb: IntArray = if (rgbView.isSelected) {
            dev.rawrec.tool.gpu.GpuManager.processFrame(
                mipiPayload = decoded,
                width = h.width,
                height = h.height,
                cfa = h.cfa,
                packing = h.packing,
                blackLevels = h.blackLevels,
                whiteLevel = h.whiteLevel,
                asShotNeutral = h.asShotNeutral,
                applyCalibration = applyWbCheck.isSelected,
                profile = profile,
                enableVignette = vignetteCheck.isSelected,
                customLut = activeLut
            )
        } else {
            IntArray(w * hh) { i ->
                val v = (luma[i].toInt() and 0xFF)
                (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
        }
        // scopes overlay chain
        if (peaking.isSelected || falseColor.isSelected || zebras.isSelected) {
            if (falseColor.isSelected) {
                val fc = CinemaScopes.applyFalseColor(luma, w, hh)
                for (i in argb.indices) argb[i] = fc[i] or 0xFF000000.toInt()
            }
            if (zebras.isSelected) {
                val z = CinemaScopes.applyZebras(luma, w, hh)
                for (i in argb.indices) argb[i] = z[i]
            }
            if (peaking.isSelected) {
                val p = CinemaScopes.applyFocusPeaking(luma, w, hh)
                for (i in argb.indices) {
                    if (p[i] == 0xFF00FF00.toInt()) argb[i] = p[i]
                }
            }
        }
        val img = BufferedImage(w, hh, BufferedImage.TYPE_INT_RGB)
        img.setRGB(0, 0, w, hh, argb, 0, w)

        val hist = CinemaScopes.computeHistogram(argb, sampleStep = 4)
        SwingUtilities.invokeLater {
            histPanel.update(hist)
            clipStatsLabel.text = "CLIP: %.1f%% / %.1f%%".format(hist.shadowClippedPercent, hist.highlightClippedPercent)
        }
        return img
    }

    private fun formatExp(ns: Long): String = when {
        ns <= 0 -> "1/50s"
        ns >= 1_000_000_000 -> "%.1fs".format(ns / 1e9)
        else -> "1/%ds".format(Math.round(1e9 / ns))
    }

    private fun refreshCurrent() {
        synchronized(previewCache) { previewCache.clear() }
        prefetchInFlight.clear()
        requestFrame(slider.value, fast = false)
        triggerPrefetch(slider.value)
    }

    private fun stepFrame(delta: Int) {
        val next = (slider.value + delta).coerceIn(0, max(0, frames.size - 1))
        if (next != slider.value) {
            slider.value = next
        }
    }

    private fun triggerPrefetch(fromIdx: Int, lookahead: Int = PREFETCH_LOOKAHEAD) {
        val h = header ?: return
        val end = minOf(frames.size, fromIdx + lookahead)
        for (idx in (fromIdx + 1) until end) {
            val already = synchronized(previewCache) { previewCache.containsKey(idx) }
            if (!already && prefetchInFlight.add(idx)) {
                val fr = frames.getOrNull(idx)
                if (fr == null) {
                    prefetchInFlight.remove(idx)
                    continue
                }
                prefetchPool.execute {
                    try {
                        if (synchronized(previewCache) { previewCache.containsKey(idx) }) return@execute
                        val r = raf ?: return@execute
                        val payload = synchronized(r) { Rvtool.readPayload(r, fr) }
                        val decoded = Rvtool.decodedPayload(h, payload)
                        val samples = Rvtool.samplesOf(h, decoded)
                        val blackAvg = h.blackLevels.average().toInt()
                        val luma = Rvtool.luma8Of(samples, h.width, h.height, blackAvg, h.whiteLevel)
                        val rendered = renderImage(idx, decoded, samples, luma, h)
                        synchronized(previewCache) { previewCache[idx] = rendered }
                    } catch (_: Throwable) {
                    } finally {
                        prefetchInFlight.remove(idx)
                    }
                }
            }
        }
    }

    // ---- playback loop ----
    private fun stopPlayback() {
        isPlaying = false
        stopAudioPlayback()
        playThread?.interrupt()
        playThread = null
    }

    private fun togglePlay() {
        if (frames.isEmpty()) {
            playBtn.isSelected = false
            return
        }
        if (!isPlaying) {
            // If at the end of take, rewind to start
            if (slider.value >= frames.size - 1) {
                slider.value = 0
            }
            isPlaying = true
            playBtn.isSelected = true
            playBtn.text = "PAUSE"
            statusBadge.text = "PLAY"
            statusBadge.foreground = TALLY
            statusBadge.border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(TALLY, 1),
                BorderFactory.createEmptyBorder(2, 6, 2, 6)
            )

            currentPlaybackIndex = slider.value
            val h = header ?: return
            val targetFps = if (h.fpsMilli > 0) h.fpsMilli / 1000.0 else 30.0
            val frameIntervalNs = (1_000_000_000.0 / targetFps).toLong()

            if (isAudioEnabled && audioRecs.isNotEmpty()) {
                startAudioPlayback(currentPlaybackIndex)
            }

            playThread = Thread({
                try {
                    var idx = currentPlaybackIndex
                    var nextFrameNs = System.nanoTime()

                    while (isPlaying && idx < frames.size) {
                        triggerPrefetch(idx, lookahead = PREFETCH_LOOKAHEAD)
                        val cached = synchronized(previewCache) { previewCache[idx] }

                        val frameImg = if (cached != null) {
                            cached
                        } else {
                            val r = raf ?: break
                            val fr = frames[idx]
                            val payload = synchronized(r) { Rvtool.readPayload(r, fr) }
                            val decoded = Rvtool.decodedPayload(h, payload)
                            val samples = Rvtool.samplesOf(h, decoded)
                            val blackAvg = h.blackLevels.average().toInt()
                            val luma = Rvtool.luma8Of(samples, h.width, h.height, blackAvg, h.whiteLevel)
                            val rendered = renderImage(idx, decoded, samples, luma, h)
                            synchronized(previewCache) { previewCache[idx] = rendered }
                            rendered
                        }

                        val curIdx = idx
                        val fr = frames[curIdx]
                        val elapsedSec = (fr.tsNs - (frames.firstOrNull()?.tsNs ?: 0L)) / 1e9
                        val totalSec = elapsedSec.coerceAtLeast(0.0)
                        val hrs = (totalSec / 3600).toInt()
                        val mins = ((totalSec % 3600) / 60).toInt()
                        val secs = (totalSec % 60).toInt()
                        val ff = ((totalSec - totalSec.toInt()) * targetFps).toInt().coerceIn(0, (targetFps - 1).toInt())

                        SwingUtilities.invokeLater {
                            if (isPlaying) {
                                isProgrammaticSliderChange = true
                                slider.value = curIdx
                                isProgrammaticSliderChange = false
                                timecodeLabel.text = "%02d:%02d:%02d:%02d".format(hrs, mins, secs, ff)
                                frameReadout.text = "FRAME: %d / %d (%.2fs)".format(curIdx + 1, frames.size, totalSec)
                                if (::curvePanel.isInitialized) {
                                    curvePanel.setCurrentFrame(curIdx)
                                }
                                preview.setImage(frameImg)
                                preview.repaint()
                            }
                        }

                        nextFrameNs += frameIntervalNs
                        val sleepNs = nextFrameNs - System.nanoTime()
                        if (sleepNs > 1_000_000) {
                            try {
                                Thread.sleep(sleepNs / 1_000_000, (sleepNs % 1_000_000).toInt())
                            } catch (e: InterruptedException) {
                                break
                            }
                        } else if (sleepNs < -frameIntervalNs * 2) {
                            nextFrameNs = System.nanoTime()
                        }

                        if (currentPlaybackIndex != idx) {
                            idx = currentPlaybackIndex
                            nextFrameNs = System.nanoTime()
                            if (isAudioEnabled && audioRecs.isNotEmpty()) {
                                startAudioPlayback(idx)
                            }
                        } else {
                            idx++
                            if (idx >= frames.size) {
                                if (isRepeat && frames.isNotEmpty()) {
                                    idx = 0
                                    currentPlaybackIndex = 0
                                    nextFrameNs = System.nanoTime()
                                    if (isAudioEnabled && audioRecs.isNotEmpty()) {
                                        startAudioPlayback(0)
                                    }
                                } else {
                                    currentPlaybackIndex = idx
                                }
                            } else {
                                currentPlaybackIndex = idx
                            }
                        }
                    }
                } catch (_: Throwable) {
                } finally {
                    stopAudioPlayback()
                    SwingUtilities.invokeLater {
                        isPlaying = false
                        playBtn.isSelected = false
                        playBtn.text = "PLAY"
                        statusBadge.text = "STBY"
                        statusBadge.foreground = ACCENT
                        statusBadge.border = BorderFactory.createCompoundBorder(
                            BorderFactory.createLineBorder(ACCENT, 1),
                            BorderFactory.createEmptyBorder(2, 6, 2, 6)
                        )
                    }
                }
            }, "rvtool-gui-player").apply { isDaemon = true; start() }
        } else {
            stopPlayback()
            playBtn.text = "PLAY"
            statusBadge.text = "STBY"
            statusBadge.foreground = ACCENT
            statusBadge.border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(ACCENT, 1),
                BorderFactory.createEmptyBorder(2, 6, 2, 6)
            )
        }
    }

    private fun toggleAudio() {
        if (audioRecs.isEmpty()) {
            JOptionPane.showMessageDialog(frame, "No audio records embedded in this take.", "Audio", JOptionPane.INFORMATION_MESSAGE)
            audioBtn.isSelected = false
            isAudioEnabled = false
            return
        }
        isAudioEnabled = audioBtn.isSelected
        if (isPlaying) {
            if (isAudioEnabled) {
                startAudioPlayback(currentPlaybackIndex)
            } else {
                stopAudioPlayback()
            }
        }
    }

    private fun startAudioPlayback(startFrameIdx: Int) {
        stopAudioPlayback()
        if (!isAudioEnabled || audioRecs.isEmpty() || !isPlaying) return

        val targetTs = frames.getOrNull(startFrameIdx)?.tsNs ?: 0L
        val audioStart = if (targetTs > 0L && audioRecs.first().timestampNs > 0L) {
            val found = audioRecs.indexOfFirst { it.timestampNs >= targetTs }
            if (found >= 0) found else audioRecs.size
        } else {
            val ratio = if (frames.isNotEmpty()) startFrameIdx.toDouble() / frames.size else 0.0
            (ratio * audioRecs.size).toInt().coerceIn(0, audioRecs.size)
        }

        if (audioStart >= audioRecs.size) return

        audioStop = false
        val src = raf ?: return
        audioThread = Thread({
            var line: SourceDataLine? = null
            try {
                val fmt = AudioFormat(48_000f, 16, 2, true, false)
                line = AudioSystem.getSourceDataLine(fmt)
                audioLine = line
                line.open(fmt, 16_384)
                line.start()
                val buf = ByteArray(16_384)

                for (i in audioStart until audioRecs.size) {
                    if (audioStop || !isPlaying) break
                    val rec = audioRecs[i]
                    val pcm = synchronized(src) { Rvtool.readPayload(src, rec.offset, rec.size) }
                    var off = 0
                    while (off < pcm.size && !audioStop && isPlaying) {
                        val n = minOf(buf.size, pcm.size - off)
                        System.arraycopy(pcm, off, buf, 0, n)
                        line.write(buf, 0, n)
                        off += n
                    }
                }
                if (!audioStop && isPlaying) {
                    line.drain()
                }
            } catch (_: Throwable) {
            } finally {
                runCatching {
                    line?.stop()
                    line?.flush()
                    line?.close()
                }
                if (audioLine === line) {
                    audioLine = null
                }
            }
        }, "rvtool-gui-audio").apply { isDaemon = true; start() }
    }

    private fun stopAudioPlayback() {
        audioStop = true
        val line = audioLine
        audioLine = null
        runCatching {
            line?.stop()
            line?.flush()
            line?.close()
        }
        val t = audioThread
        audioThread = null
        t?.interrupt()
    }

    // ---- exports ----
    private fun saveDialog(suffix: String): File? {
        val base = filePath?.nameWithoutExtension ?: "take"
        val fc = JFileChooser(System.getProperty("user.dir"))
        fc.selectedFile = File("${base}${suffix}")
        return if (fc.showSaveDialog(frame) == JFileChooser.APPROVE_OPTION)
            fc.selectedFile else null
    }

    private fun importLut() {
        val fc = JFileChooser(System.getProperty("user.dir"))
        fc.fileFilter = FileNameExtensionFilter("3D / 1D LUT (*.cube)", "cube")
        if (fc.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION) return
        val f = fc.selectedFile ?: return
        try {
            val lut = CubeLut.parse(f)
            loadedCustomLut = lut
            loadedLutFile = f

            val lutTitle = "LUT: ${lut.title}"
            var found = false
            for (i in 0 until toneBox.itemCount) {
                if (toneBox.getItemAt(i).startsWith("LUT:")) {
                    toneBox.removeItemAt(i)
                    toneBox.insertItemAt(lutTitle, i)
                    toneBox.selectedIndex = i
                    found = true
                    break
                }
            }
            if (!found) {
                toneBox.addItem(lutTitle)
                toneBox.selectedIndex = toneBox.itemCount - 1
            }
            refreshCurrent()
        } catch (t: Throwable) {
            JOptionPane.showMessageDialog(frame, "Failed to load LUT: ${t.message}", "LUT Error", JOptionPane.ERROR_MESSAGE)
        }
    }

    private fun exportBmp() {
        val p = filePath?.absolutePath ?: return
        val out = saveDialog("_frame.bmp") ?: return
        Rvtool.bmp(p, slider.value, out.absolutePath)
    }

    private fun exportRgb() {
        val p = filePath?.absolutePath ?: return
        val out = saveDialog("_frame_rgb.bmp") ?: return
        val selectedItem = toneBox.selectedItem as? String ?: ""
        val isLutSelected = selectedItem.startsWith("LUT:") && loadedCustomLut != null
        val profile = if (isLutSelected) {
            ColorScience.ToneProfile.CUSTOM_LUT
        } else {
            ColorScience.ToneProfile.values().getOrElse(toneBox.selectedIndex) { ColorScience.ToneProfile.DEFAULT }
        }
        val lutPath = if (isLutSelected) loadedLutFile?.absolutePath else null
        Rvtool.rgb(
            p,
            slider.value,
            out.absolutePath,
            profile.id,
            applyCalibration = applyWbCheck.isSelected,
            lutPath = lutPath
        )
    }

    private fun exportWav() {
        val p = filePath?.absolutePath ?: return
        val out = saveDialog(".wav") ?: return
        Rvtool.wav(p, out.absolutePath)
    }

    private fun exportMp4() {
        val p = filePath?.absolutePath ?: return
        val selectedItem = toneBox.selectedItem as? String ?: ""
        val isLutSelected = selectedItem.startsWith("LUT:") && loadedCustomLut != null
        val profile = if (isLutSelected) {
            ColorScience.ToneProfile.CUSTOM_LUT
        } else {
            val cur = ColorScience.ToneProfile.values().getOrElse(toneBox.selectedIndex) { ColorScience.ToneProfile.DEFAULT }
            if (cur == ColorScience.ToneProfile.DEFAULT) ColorScience.ToneProfile.CINE_HLG else cur
        }
        val lutObj = if (isLutSelected) loadedCustomLut else null
        val out = saveDialog("_hlg.mp4") ?: return

        decoder.execute {
            SwingUtilities.invokeLater { verdict.text = "exporting HLG MP4..." }
            try {
                val f = HlgMp4Exporter.export(
                    p,
                    out.absolutePath,
                    profile = profile,
                    customLut = lutObj,
                    applyWb = applyWbCheck.isSelected,
                    isHlg = true
                ) { cur, tot ->
                    SwingUtilities.invokeLater {
                        verdict.text = "exporting HLG MP4: $cur/$tot frames (${cur * 100 / tot}%)"
                    }
                }
                SwingUtilities.invokeLater {
                    verdict.text = "exported HLG MP4 (${f.length() / 1024} KB)"
                    JOptionPane.showMessageDialog(frame, "Exported HLG MP4 video to:\n${f.absolutePath}")
                }
            } catch (t: Throwable) {
                SwingUtilities.invokeLater {
                    verdict.text = "export MP4 failed: ${t.message}"
                    JOptionPane.showMessageDialog(frame, "Export MP4 failed: ${t.message}", "Export Error", JOptionPane.ERROR_MESSAGE)
                }
            }
        }
    }

    private fun extractDng() {
        val p = filePath?.absolutePath ?: return
        val startField = JTextField("0")
        val countField = JTextField("60")

        val toneProfiles = ColorScience.ToneProfile.values()
        val profileItems = toneProfiles.map { it.displayName }.toMutableList()
        if (loadedCustomLut != null) {
            profileItems.add("LUT: ${loadedCustomLut?.title}")
        }
        val profileCombo = JComboBox(profileItems.toTypedArray()).apply {
            selectedIndex = toneBox.selectedIndex.coerceIn(0, profileItems.size - 1)
        }
        val bakeCheck = JCheckBox("Bake tone curve directly into RAW pixels", false)

        val panel = JPanel(GridLayout(4, 2, 8, 8)).apply {
            add(JLabel("Start frame:"))
            add(startField)
            add(JLabel("Frame count (-1=all):"))
            add(countField)
            add(JLabel("Tone Look Profile:"))
            add(profileCombo)
            add(JLabel("Bake Mode:"))
            add(bakeCheck)
        }
        val ok = JOptionPane.showConfirmDialog(frame, panel, "Extract CinemaDNG Sequence", JOptionPane.OK_CANCEL_OPTION)
        if (ok != JOptionPane.OK_OPTION) return
        val start = startField.text.toIntOrNull() ?: 0
        val count = countField.text.toIntOrNull() ?: -1
        val selectedStr = profileCombo.selectedItem as? String ?: ""
        val isLutSelected = selectedStr.startsWith("LUT:") && loadedCustomLut != null
        val profile = if (isLutSelected) {
            ColorScience.ToneProfile.CUSTOM_LUT
        } else {
            ColorScience.ToneProfile.values().getOrElse(profileCombo.selectedIndex) { ColorScience.ToneProfile.DEFAULT }
        }
        val lutPath = if (isLutSelected) loadedLutFile?.absolutePath else null
        val out = saveDialog("") ?: return

        Rvtool.extract(
            p,
            out.absolutePath,
            start,
            count,
            profileName = profile.id,
            bakeTone = bakeCheck.isSelected,
            lutPath = lutPath
        )
        JOptionPane.showMessageDialog(frame, "Extracted CinemaDNG sequence to:\n${out.absolutePath}")
    }

    private fun openBatchDialog() {
        val dlg = JDialog(frame, "Batch Take Export", true).apply {
            layout = BorderLayout(10, 10)
            preferredSize = Dimension(580, 400)
            contentPane.background = BG
        }

        var inFolder = filePath?.parentFile ?: File(System.getProperty("user.dir"))
        var outFolder = File(inFolder, "batch_export")

        val form = JPanel(GridBagLayout()).apply {
            isOpaque = false
            border = BorderFactory.createEmptyBorder(16, 16, 16, 16)
        }
        val gc = GridBagConstraints().apply {
            insets = Insets(6, 6, 6, 6)
            fill = GridBagConstraints.HORIZONTAL
        }

        // Row 0: In folder
        gc.gridx = 0; gc.gridy = 0; gc.weightx = 0.0
        val inLbl = JLabel("Source Folder:").apply { foreground = FG; font = LABEL_FONT }
        form.add(inLbl, gc)
        gc.gridx = 1; gc.weightx = 1.0
        val inField = JTextField(inFolder.absolutePath).apply {
            background = PANEL; foreground = FG; caretColor = ACCENT; font = MONO
        }
        form.add(inField, gc)
        gc.gridx = 2; gc.weightx = 0.0
        val inBrowse = DeckButton("Browse…").apply {
            addActionListener {
                val fc = JFileChooser(inFolder).apply { fileSelectionMode = JFileChooser.DIRECTORIES_ONLY }
                if (fc.showOpenDialog(dlg) == JFileChooser.APPROVE_OPTION) {
                    inFolder = fc.selectedFile
                    inField.text = inFolder.absolutePath
                }
            }
        }
        form.add(inBrowse, gc)

        // Row 1: Out folder
        gc.gridx = 0; gc.gridy = 1; gc.weightx = 0.0
        val outLbl = JLabel("Output Folder:").apply { foreground = FG; font = LABEL_FONT }
        form.add(outLbl, gc)
        gc.gridx = 1; gc.weightx = 1.0
        val outField = JTextField(outFolder.absolutePath).apply {
            background = PANEL; foreground = FG; caretColor = ACCENT; font = MONO
        }
        form.add(outField, gc)
        gc.gridx = 2; gc.weightx = 0.0
        val outBrowse = DeckButton("Browse…").apply {
            addActionListener {
                val fc = JFileChooser(outFolder).apply { fileSelectionMode = JFileChooser.DIRECTORIES_ONLY }
                if (fc.showSaveDialog(dlg) == JFileChooser.APPROVE_OPTION) {
                    outFolder = fc.selectedFile
                    outField.text = outFolder.absolutePath
                }
            }
        }
        form.add(outBrowse, gc)

        // Row 2: Format & Profile
        gc.gridx = 0; gc.gridy = 2; gc.weightx = 0.0
        val fmtLbl = JLabel("Export Format:").apply { foreground = FG; font = LABEL_FONT }
        form.add(fmtLbl, gc)
        gc.gridx = 1; gc.weightx = 1.0
        val fmtCombo = JComboBox(arrayOf("CinemaDNG Sequence (.dng + .wav)", "BT.2100 HLG MP4 (.mp4)")).apply {
            background = PANEL; foreground = FG
        }
        form.add(fmtCombo, gc)

        // Row 3: Tone Look Profile
        gc.gridx = 0; gc.gridy = 3; gc.weightx = 0.0
        val toneLbl = JLabel("Look Profile:").apply { foreground = FG; font = LABEL_FONT }
        form.add(toneLbl, gc)
        gc.gridx = 1; gc.weightx = 1.0
        val toneProfiles = ColorScience.ToneProfile.values()
        val toneCombo = JComboBox(toneProfiles.map { it.displayName }.toTypedArray()).apply {
            background = PANEL; foreground = FG
            selectedIndex = toneBox.selectedIndex.coerceIn(0, toneProfiles.size - 1)
        }
        form.add(toneCombo, gc)

        // Row 4: Bake Tone checkbox
        gc.gridx = 1; gc.gridy = 4; gc.weightx = 1.0
        val bakeCheck = JCheckBox("Bake tone curve directly into RAW pixels (CinemaDNG only)", false).apply {
            foreground = FG; isOpaque = false
        }
        form.add(bakeCheck, gc)

        // Progress bar & Status
        val progBar = JProgressBar(0, 100).apply {
            isStringPainted = true
            string = "Ready"
            foreground = ACCENT
            background = PANEL
        }
        val statusLbl = JLabel("Click 'START BATCH' to process all .rvsp takes.").apply {
            foreground = FG_DIM; font = MONO
        }

        val progPanel = JPanel(BorderLayout(6, 6)).apply {
            isOpaque = false
            border = BorderFactory.createEmptyBorder(0, 16, 8, 16)
            add(statusLbl, BorderLayout.NORTH)
            add(progBar, BorderLayout.CENTER)
        }

        // Action Buttons
        val btnPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 10, 10)).apply {
            isOpaque = false
        }
        val startBtn = DeckButton("START BATCH", highlight = true)
        val closeBtn = DeckButton("CLOSE").apply { addActionListener { dlg.dispose() } }
        btnPanel.add(startBtn)
        btnPanel.add(closeBtn)

        startBtn.addActionListener {
            val src = File(inField.text)
            val dst = File(outField.text)
            val isMp4 = fmtCombo.selectedIndex == 1
            val selectedProfile = toneProfiles.getOrElse(toneCombo.selectedIndex) { ColorScience.ToneProfile.DEFAULT }
            val bake = bakeCheck.isSelected

            startBtn.isEnabled = false
            closeBtn.text = "CANCEL"

            Thread({
                try {
                    val count = Rvtool.batchProcess(
                        inDir = src.absolutePath,
                        outDir = dst.absolutePath,
                        format = if (isMp4) "mp4" else "dng",
                        profileName = selectedProfile.id,
                        bakeTone = bake,
                        lutPath = loadedLutFile?.absolutePath
                    ) { cur, tot, name ->
                        SwingUtilities.invokeLater {
                            val pct = (cur.toDouble() / tot * 100).toInt()
                            progBar.value = pct
                            progBar.string = "$cur / $tot ($pct%)"
                            statusLbl.text = "Processing: $name"
                        }
                    }
                    SwingUtilities.invokeLater {
                        progBar.value = 100
                        progBar.string = "Completed"
                        statusLbl.text = "Successfully processed $count takes."
                        startBtn.isEnabled = true
                        closeBtn.text = "CLOSE"
                        JOptionPane.showMessageDialog(dlg, "Batch export completed ($count takes).", "Batch Export", JOptionPane.INFORMATION_MESSAGE)
                    }
                } catch (t: Throwable) {
                    SwingUtilities.invokeLater {
                        statusLbl.text = "Error: ${t.message}"
                        startBtn.isEnabled = true
                        closeBtn.text = "CLOSE"
                        JOptionPane.showMessageDialog(dlg, "Batch export failed: ${t.message}", "Error", JOptionPane.ERROR_MESSAGE)
                    }
                }
            }, "rvtool-batch-worker").start()
        }

        dlg.add(form, BorderLayout.NORTH)
        dlg.add(progPanel, BorderLayout.CENTER)
        dlg.add(btnPanel, BorderLayout.SOUTH)
        dlg.pack()
        dlg.setLocationRelativeTo(frame)
        dlg.isVisible = true
    }
}

// ==========================================
// CUSTOM CINEMA DECK CONTROLS
// ==========================================

/** Sleek tactile button with rounded corners, subtle border, and hover glow. */
class DeckButton(
    text: String,
    private val highlight: Boolean = false
) : JButton(text) {
    private var isHovered = false

    init {
        font = Font("SansSerif", Font.BOLD, 11)
        foreground = if (highlight) RvtoolGui.ACCENT else RvtoolGui.FG
        isFocusPainted = false
        isContentAreaFilled = false
        border = BorderFactory.createEmptyBorder(6, 12, 6, 12)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

        addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) { isHovered = true; repaint() }
            override fun mouseExited(e: MouseEvent) { isHovered = false; repaint() }
        })
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val bg = when {
            model.isPressed -> RvtoolGui.PANEL
            isHovered -> RvtoolGui.BORDER_HI
            highlight -> Color(0, 48, 64)
            else -> RvtoolGui.PANEL_HI
        }
        g2.color = bg
        g2.fill(RoundRectangle2D.Float(0f, 0f, width.toFloat(), height.toFloat(), 6f, 6f))

        g2.color = if (isHovered || highlight) RvtoolGui.ACCENT else RvtoolGui.BORDER
        g2.stroke = BasicStroke(1f)
        g2.draw(RoundRectangle2D.Float(0.5f, 0.5f, width - 1f, height - 1f, 6f, 6f))

        g2.dispose()
        super.paintComponent(g)
    }
}

/** Segmented / Pill toggle button with Electric Cyan illumination when active. */
class DeckToggleButton(
    text: String,
    selected: Boolean = false
) : JToggleButton(text, selected) {
    private var isHovered = false

    init {
        font = Font("SansSerif", Font.BOLD, 10)
        isFocusPainted = false
        isContentAreaFilled = false
        border = BorderFactory.createEmptyBorder(5, 10, 5, 10)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

        addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) { isHovered = true; repaint() }
            override fun mouseExited(e: MouseEvent) { isHovered = false; repaint() }
        })
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val sel = isSelected
        val bg = when {
            sel -> RvtoolGui.ACCENT_BG
            isHovered -> RvtoolGui.BORDER_HI
            else -> RvtoolGui.PANEL_HI
        }
        g2.color = bg
        g2.fill(RoundRectangle2D.Float(0f, 0f, width.toFloat(), height.toFloat(), 6f, 6f))

        g2.color = if (sel) RvtoolGui.ACCENT else if (isHovered) RvtoolGui.BORDER_HI else RvtoolGui.BORDER
        g2.stroke = BasicStroke(if (sel) 1.5f else 1f)
        g2.draw(RoundRectangle2D.Float(0.5f, 0.5f, width - 1f, height - 1f, 6f, 6f))

        foreground = if (sel) RvtoolGui.ACCENT else RvtoolGui.FG_DIM
        g2.dispose()
        super.paintComponent(g)
    }
}

/** Custom deck slider UI with dark groove track and Electric Cyan progress. */
class DeckSliderUI(slider: JSlider) : BasicSliderUI(slider) {
    override fun paintTrack(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val trackH = 4
        val trackY = trackRect.y + (trackRect.height - trackH) / 2
        val trackW = trackRect.width
        val trackX = trackRect.x

        // Background groove
        g2.color = Color(10, 13, 19)
        g2.fill(RoundRectangle2D.Float(trackX.toFloat(), trackY.toFloat(), trackW.toFloat(), trackH.toFloat(), 4f, 4f))
        g2.color = RvtoolGui.BORDER
        g2.draw(RoundRectangle2D.Float(trackX.toFloat(), trackY.toFloat(), trackW.toFloat(), trackH.toFloat(), 4f, 4f))

        // Filled active progress in Electric Cyan
        val fillW = thumbRect.x + thumbRect.width / 2 - trackX
        if (fillW > 0) {
            g2.color = RvtoolGui.ACCENT
            g2.fill(RoundRectangle2D.Float(trackX.toFloat(), trackY.toFloat(), fillW.toFloat(), trackH.toFloat(), 4f, 4f))
        }
        g2.dispose()
    }

    override fun paintThumb(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val cx = thumbRect.x + thumbRect.width / 2f
        val cy = thumbRect.y + thumbRect.height / 2f
        val radius = 7f

        // Outer cyan glow
        g2.color = RvtoolGui.ACCENT
        g2.fillOval((cx - radius).toInt(), (cy - radius).toInt(), (radius * 2).toInt(), (radius * 2).toInt())

        // Inner white dot
        g2.color = Color.WHITE
        g2.fillOval((cx - 2.5f).toInt(), (cy - 2.5f).toInt(), 5, 5)

        g2.dispose()
    }

    override fun getThumbSize(): Dimension = Dimension(16, 16)
}

/** Half-res preview canvas: paints the current image letterboxed with center reticle. */
private class PreviewPanel : JPanel() {
    private var image: BufferedImage? = null

    init {
        background = Color(10, 12, 17)
    }

    fun setImage(img: BufferedImage) {
        image = img
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val img = image ?: run {
            g.color = RvtoolGui.FG_DIM
            g.font = RvtoolGui.MONO
            val text = "NO TAKE LOADED — CLICK [OPEN TAKE…] OR RUN: rvtool gui <take.rvsp>"
            val fm = g.fontMetrics
            val tw = fm.stringWidth(text)
            g.drawString(text, (width - tw) / 2, height / 2)
            return
        }
        val s = minOf(width.toFloat() / img.width, height.toFloat() / img.height)
        val w = (img.width * s).toInt().coerceAtLeast(1)
        val h = (img.height * s).toInt().coerceAtLeast(1)
        val x = (width - w) / 2
        val y = (height - h) / 2
        g.drawImage(img, x, y, w, h, null)

        // 1px subtle frame outline around active video
        g.color = RvtoolGui.BORDER
        g.drawRect(x - 1, y - 1, w + 1, h + 1)

        // Center reticle crosshair
        val cx = x + w / 2
        val cy = y + h / 2
        g.color = Color(255, 255, 255, 60)
        g.drawLine(cx - 12, cy, cx + 12, cy)
        g.drawLine(cx, cy - 12, cx, cy + 12)
    }
}

/** Multi-channel RGB + Luma Histogram side panel with IRE markings. */
private class HistogramPanel : JPanel() {
    private var hist: dev.rawrec.app.scopes.HistogramData? = null

    init {
        background = Color(10, 13, 19)
        border = BorderFactory.createLineBorder(RvtoolGui.BORDER, 1)
        preferredSize = Dimension(216, 130)
        maximumSize = Dimension(216, 130)
    }

    fun update(h: dev.rawrec.app.scopes.HistogramData) {
        hist = h
        repaint()
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val h = hist ?: return
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val w = width
        val bh = height

        // Reference IRE grid lines (18% middle gray, 50%, 90% skin tone, 100% white)
        g2.color = Color(255, 255, 255, 25)
        val ireMarks = intArrayOf(46, 128, 230, 255)
        for (m in ireMarks) {
            val gx = (m.toFloat() / 255f * w).toInt()
            g2.drawLine(gx, 0, gx, bh)
        }

        val maxBin = h.lumaBins.maxOrNull()?.coerceAtLeast(1) ?: 1

        // Multi-channel RGB + Luma rendering
        for (i in 0 until 256) {
            val x = i * w / 256
            val barW = max(1, w / 256)

            // Red channel
            val rH = (h.redBins[i].toFloat() / maxBin * (bh - 6)).toInt()
            g2.color = Color(255, 60, 60, 90)
            g2.fillRect(x, bh - rH, barW, rH)

            // Green channel
            val gH = (h.greenBins[i].toFloat() / maxBin * (bh - 6)).toInt()
            g2.color = Color(60, 255, 60, 90)
            g2.fillRect(x, bh - gH, barW, gH)

            // Blue channel
            val bH = (h.blueBins[i].toFloat() / maxBin * (bh - 6)).toInt()
            g2.color = Color(60, 160, 255, 90)
            g2.fillRect(x, bh - bH, barW, bH)

            // Luma peak (Electric Cyan)
            val lH = (h.lumaBins[i].toFloat() / maxBin * (bh - 6)).toInt()
            g2.color = Color(0, 229, 255, 180)
            g2.drawLine(x, bh - lH, x + barW, bh - lH)
        }

        // Luma IRE legend
        g2.font = Font("SansSerif", Font.PLAIN, 9)
        g2.color = Color(255, 255, 255, 100)
        g2.drawString("0", 4, 12)
        g2.drawString("18", (46f / 255f * w).toInt() - 6, 12)
        g2.drawString("90", (230f / 255f * w).toInt() - 6, 12)
        g2.drawString("100", w - 20, 12)

        g2.dispose()
    }
}

/**
 * Timeline telemetry curves: plots per-frame ISO and exposure time (shutter) curves
 * horizontally aligned with the scrubber slider, with synchronized playhead cursor.
 */
private class CurvePlotPanel : JPanel() {
    private var frameData: List<Rvtool.FrameRec> = emptyList()
    private var currentIdx: Int = 0
    private var minIso = 50
    private var maxIso = 3200
    private var minExpMs = 0.5
    private var maxExpMs = 40.0

    init {
        preferredSize = Dimension(0, 48)
        minimumSize = Dimension(0, 36)
        background = RvtoolGui.PANEL
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, RvtoolGui.BORDER),
            BorderFactory.createEmptyBorder(2, 6, 2, 6)
        )
    }

    fun setFrames(frames: List<Rvtool.FrameRec>) {
        frameData = frames
        if (frames.isNotEmpty()) {
            val isos = frames.map { it.iso }.filter { it > 0 }
            minIso = isos.minOrNull() ?: 50
            maxIso = (isos.maxOrNull() ?: 3200).coerceAtLeast(minIso + 1)

            val exps = frames.map { it.expNs / 1e6 }.filter { it > 0.0 }
            minExpMs = exps.minOrNull() ?: 0.5
            maxExpMs = (exps.maxOrNull() ?: 40.0).coerceAtLeast(minExpMs + 0.1)
        }
        repaint()
    }

    fun setCurrentFrame(idx: Int) {
        currentIdx = idx
        repaint()
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val w = width
        val h = height
        if (w <= 0 || h <= 0) {
            g2.dispose()
            return
        }

        // Background
        g2.color = RvtoolGui.PANEL
        g2.fillRect(0, 0, w, h)

        // Midline reference grid
        g2.color = Color(38, 48, 64)
        g2.drawLine(0, h / 2, w, h / 2)

        if (frameData.isEmpty()) {
            g2.color = RvtoolGui.FG_DIM
            g2.font = Font("Monospaced", Font.PLAIN, 10)
            g2.drawString("TELEMETRY CURVE: NO FRAMES LOADED", 10, h / 2 + 4)
            g2.dispose()
            return
        }

        val count = frameData.size
        val plotH = h - 18
        val topY = 4

        // 1. Draw ISO curve (Electric Cyan)
        g2.color = RvtoolGui.ACCENT
        g2.stroke = BasicStroke(1.2f)
        var prevX = -1; var prevY = -1
        for (i in 0 until count) {
            val px = (i.toDouble() / (count - 1).coerceAtLeast(1) * w).toInt()
            val isoVal = frameData[i].iso
            val norm = ((isoVal - minIso).toDouble() / (maxIso - minIso)).coerceIn(0.0, 1.0)
            val py = topY + plotH - (norm * plotH).toInt()
            if (prevX >= 0) {
                g2.drawLine(prevX, prevY, px, py)
            }
            prevX = px; prevY = py
        }

        // 2. Draw Exposure curve (Warm Amber #FFB300)
        val amber = Color(255, 179, 0)
        g2.color = amber
        g2.stroke = BasicStroke(1.2f)
        prevX = -1; prevY = -1
        for (i in 0 until count) {
            val px = (i.toDouble() / (count - 1).coerceAtLeast(1) * w).toInt()
            val expVal = frameData[i].expNs / 1e6
            val norm = ((expVal - minExpMs) / (maxExpMs - minExpMs)).coerceIn(0.0, 1.0)
            val py = topY + plotH - (norm * plotH).toInt()
            if (prevX >= 0) {
                g2.drawLine(prevX, prevY, px, py)
            }
            prevX = px; prevY = py
        }

        // 3. Playhead cursor vertical line
        val curFrac = (currentIdx.toDouble() / (count - 1).coerceAtLeast(1)).coerceIn(0.0, 1.0)
        val cursorX = (curFrac * w).toInt().coerceIn(0, w - 1)
        g2.color = Color(255, 255, 255, 220)
        g2.stroke = BasicStroke(1.5f)
        g2.drawLine(cursorX, 0, cursorX, h)

        // 4. Live legend & current frame readout
        val curFr = frameData.getOrNull(currentIdx)
        val curIso = curFr?.iso ?: minIso
        val curExpMs = (curFr?.expNs ?: 0L) / 1e6
        val curShutter = if (curExpMs > 0.0) "1/%d s".format(Math.round(1000.0 / curExpMs).toInt()) else "n/a"

        g2.font = Font("Monospaced", Font.BOLD, 10)
        g2.color = RvtoolGui.ACCENT
        g2.drawString("■ ISO $curIso ($minIso-$maxIso)", 8, h - 4)

        g2.color = amber
        g2.drawString("■ SHUTTER $curShutter (${"%.1f".format(curExpMs)}ms)", 180, h - 4)

        g2.dispose()
    }
}
