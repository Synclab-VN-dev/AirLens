package dev.openstream.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Paint
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import dev.openstream.app.camera.CameraLens
import dev.openstream.app.encoder.AvcProfilePreference
import dev.openstream.app.encoder.VideoBitrateMode
import dev.openstream.app.stream.ConnectionTarget
import dev.openstream.app.stream.StreamConfig
import dev.openstream.app.stream.StreamConfigStore
import dev.openstream.app.stream.StreamPreset
import dev.openstream.app.stream.StreamProfile
import dev.openstream.app.stream.StreamProfileStore
import dev.openstream.app.stream.StreamingCapabilityResolver
import dev.openstream.app.stream.SupportedStreamMode
import dev.openstream.app.ui.PillToggle
import java.util.UUID

class SettingsActivity : Activity() {

    // ---- Views: header / cards ----
    private lateinit var root: FrameLayout
    private lateinit var btnBack: TextView
    private lateinit var btnReset: TextView
    private lateinit var profileRow: LinearLayout
    private lateinit var profileEmpty: LinearLayout
    private lateinit var profileNameView: TextView
    private lateinit var profileMeta: TextView
    private lateinit var btnCreateProfile: TextView
    private lateinit var rowCamera: LinearLayout
    private lateinit var rowResolution: LinearLayout
    private lateinit var rowFrameRate: LinearLayout
    private lateinit var cameraValue: TextView
    private lateinit var resolutionValue: TextView
    private lateinit var fpsValue: TextView
    private lateinit var modeWarning: LinearLayout
    private lateinit var modeWarningTitle: TextView
    private lateinit var modeWarningDivider: View
    private lateinit var btnSwitchMode: TextView
    private lateinit var bitrateValue: TextView
    private lateinit var bitrateBadge: TextView
    private lateinit var bitrateSlider: SeekBar
    private lateinit var bitrateRecLabel: TextView
    private lateinit var bitrateMaxLabel: TextView
    private lateinit var audioToggle: PillToggle
    private lateinit var audioStatus: TextView
    private lateinit var audioDivider: View
    private lateinit var rowAudioDetail: LinearLayout
    private lateinit var audioSummary: TextView
    private lateinit var connectionCard: LinearLayout
    private lateinit var rowObsHost: LinearLayout
    private lateinit var hostValue: TextView
    private lateinit var hostError: TextView
    private lateinit var rowSrt: LinearLayout
    private lateinit var srtValue: TextView

    // ---- Views: advanced ----
    private lateinit var rowAdvancedHeader: LinearLayout
    private lateinit var advancedPreview: TextView
    private lateinit var advancedChevron: TextView
    private lateinit var advancedBody: LinearLayout
    private lateinit var segBitrateSystem: TextView
    private lateinit var segBitrateCbr: TextView
    private lateinit var segBitrateVbr: TextView
    private lateinit var segAvcAuto: TextView
    private lateinit var segAvcBaseline: TextView
    private lateinit var segAvcMain: TextView
    private lateinit var segAvcHigh: TextView
    private lateinit var avcNote: TextView
    private lateinit var bFramesToggle: PillToggle
    private lateinit var btnKeyframeMinus: TextView
    private lateinit var keyframeValue: TextView
    private lateinit var btnKeyframePlus: TextView
    private lateinit var customSizeToggle: PillToggle
    private lateinit var customFields: LinearLayout
    private lateinit var inputWidth: EditText
    private lateinit var inputHeight: EditText
    private lateinit var inputFps: EditText

    // ---- Views: report / footer / actions / toast / sheet ----
    private lateinit var reportRows: LinearLayout
    private lateinit var reportLensLabel: TextView
    private lateinit var reportChips: LinearLayout
    private lateinit var versionInfo: TextView
    private lateinit var blockedText: TextView
    private lateinit var btnSave: TextView
    private lateinit var btnSaveAndConnect: LinearLayout
    private lateinit var connectSpinner: ProgressBar
    private lateinit var connectLabel: TextView
    private lateinit var toastView: TextView
    private lateinit var sheetScrim: View
    private lateinit var sheetPanel: LinearLayout
    private lateinit var sheetTitle: TextView
    private lateinit var sheetSubtitle: TextView
    private lateinit var sheetScroll: View
    private lateinit var sheetContent: LinearLayout
    private lateinit var sheetFooter: TextView
    private lateinit var btnSheetDone: TextView

    // ---- State ----
    private data class UiState(
        var lens: CameraLens,
        var width: Int,
        var height: Int,
        var fps: Int,
        var customSize: Boolean,
        var bitrateMbps: Int,
        var bitrateMode: VideoBitrateMode,
        var avcProfile: AvcProfilePreference,
        var bFrames: Boolean,
        var keyframeSeconds: Int,
        var audioEnabled: Boolean,
        var audioSampleRate: Int,
        var audioChannels: Int,
        var audioBitrateKbps: Int,
        var host: String,
        var obsPort: Int,
        var listenPort: Int,
        var latencyMs: Int,
        var advancedOpen: Boolean = false,
    )

    private lateinit var uiState: UiState
    private var rendering = false
    private var savedPendingRestart = false
    private var currentSheet: SheetSpec? = null
    private var toastHideRunnable: Runnable? = null

    private val resolver by lazy { StreamingCapabilityResolver(this) }
    private val capabilityCache =
        HashMap<Pair<VideoBitrateMode, AvcProfilePreference>, List<SupportedStreamMode>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        bindViews()
        uiState = savedInstanceState?.let(::restoreUiState) ?: loadUiState()
        savedPendingRestart = savedInstanceState?.getBoolean(KEY_STATE_PENDING_RESTART) ?: false
        wireListeners()
        showVersionInfo()
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val s = uiState
        outState.putString(KEY_STATE_LENS, s.lens.name)
        outState.putInt(KEY_STATE_WIDTH, s.width)
        outState.putInt(KEY_STATE_HEIGHT, s.height)
        outState.putInt(KEY_STATE_FPS, s.fps)
        outState.putBoolean(KEY_STATE_CUSTOM, s.customSize)
        outState.putInt(KEY_STATE_BITRATE, s.bitrateMbps)
        outState.putString(KEY_STATE_BITRATE_MODE, s.bitrateMode.name)
        outState.putString(KEY_STATE_AVC, s.avcProfile.name)
        outState.putBoolean(KEY_STATE_BFRAMES, s.bFrames)
        outState.putInt(KEY_STATE_KEYFRAME, s.keyframeSeconds)
        outState.putBoolean(KEY_STATE_AUDIO, s.audioEnabled)
        outState.putInt(KEY_STATE_AUDIO_RATE, s.audioSampleRate)
        outState.putInt(KEY_STATE_AUDIO_CH, s.audioChannels)
        outState.putInt(KEY_STATE_AUDIO_KBPS, s.audioBitrateKbps)
        outState.putString(KEY_STATE_HOST, s.host)
        outState.putInt(KEY_STATE_OBS_PORT, s.obsPort)
        outState.putInt(KEY_STATE_LISTEN_PORT, s.listenPort)
        outState.putInt(KEY_STATE_LATENCY, s.latencyMs)
        outState.putBoolean(KEY_STATE_ADVANCED, s.advancedOpen)
        outState.putBoolean(KEY_STATE_PENDING_RESTART, savedPendingRestart)
    }

    override fun onDestroy() {
        toastHideRunnable?.let { toastView.removeCallbacks(it) }
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            sheetPanel.visibility == View.VISIBLE -> closeSheet()
            savedPendingRestart -> restartMainActivity(false, uiState.host, uiState.obsPort, uiState.latencyMs)
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    // ================= State load / restore =================

    private fun loadUiState(): UiState {
        val config = StreamConfigStore.load(this)
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val savedLens = prefs.getString(KEY_CAPABILITY_LENS, null)
            ?.let { name -> runCatching { CameraLens.valueOf(name) }.getOrNull() }
        val lenses = resolvedModes().map { it.lens }.distinct()
        val lens = when {
            savedLens != null && (lenses.isEmpty() || savedLens in lenses) -> savedLens
            lenses.isNotEmpty() -> lenses.first()
            else -> CameraLens.Back
        }
        return UiState(
            lens = lens,
            width = config.width,
            height = config.height,
            fps = config.fps,
            customSize = StreamPreset.entries.none { it.matches(config) },
            bitrateMbps = config.bitrateMbps.coerceIn(SLIDER_MIN_MBPS, SLIDER_MAX_MBPS),
            bitrateMode = config.videoBitrateMode,
            avcProfile = config.avcProfilePreference,
            bFrames = config.bFramesEnabled,
            keyframeSeconds = config.keyframeIntervalSeconds,
            audioEnabled = config.audioEnabled,
            audioSampleRate = config.audioSampleRate,
            audioChannels = config.audioChannelCount,
            audioBitrateKbps = config.audioBitrateKbps,
            host = prefs.getString(KEY_OBS_HOST, "").orEmpty(),
            obsPort = prefs.getInt(KEY_OBS_PORT, ConnectionTarget.DEFAULT_PORT),
            listenPort = prefs.getInt(KEY_LISTENING_PORT, ConnectionTarget.DEFAULT_PORT),
            latencyMs = config.latencyMs,
        )
    }

    private fun restoreUiState(state: Bundle): UiState? {
        val lensName = state.getString(KEY_STATE_LENS) ?: return null
        return UiState(
            lens = runCatching { CameraLens.valueOf(lensName) }.getOrNull() ?: CameraLens.Back,
            width = state.getInt(KEY_STATE_WIDTH),
            height = state.getInt(KEY_STATE_HEIGHT),
            fps = state.getInt(KEY_STATE_FPS),
            customSize = state.getBoolean(KEY_STATE_CUSTOM),
            bitrateMbps = state.getInt(KEY_STATE_BITRATE),
            bitrateMode = enumOrDefault(state.getString(KEY_STATE_BITRATE_MODE), VideoBitrateMode.Cbr),
            avcProfile = enumOrDefault(state.getString(KEY_STATE_AVC), AvcProfilePreference.Auto),
            bFrames = state.getBoolean(KEY_STATE_BFRAMES),
            keyframeSeconds = state.getInt(KEY_STATE_KEYFRAME),
            audioEnabled = state.getBoolean(KEY_STATE_AUDIO),
            audioSampleRate = state.getInt(KEY_STATE_AUDIO_RATE),
            audioChannels = state.getInt(KEY_STATE_AUDIO_CH),
            audioBitrateKbps = state.getInt(KEY_STATE_AUDIO_KBPS),
            host = state.getString(KEY_STATE_HOST).orEmpty(),
            obsPort = state.getInt(KEY_STATE_OBS_PORT),
            listenPort = state.getInt(KEY_STATE_LISTEN_PORT),
            latencyMs = state.getInt(KEY_STATE_LATENCY),
            advancedOpen = state.getBoolean(KEY_STATE_ADVANCED),
        )
    }

    private inline fun <reified T : Enum<T>> enumOrDefault(raw: String?, fallback: T): T =
        raw?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: fallback

    // ================= Capability cache =================

    private fun resolvedModes(): List<SupportedStreamMode> {
        val key = uiStateOrDefaultKey()
        return capabilityCache.getOrPut(key) {
            runCatching {
                resolver.resolve(
                    bitrate = PROBE_BITRATE_BPS,
                    bitrateMode = key.first,
                    profilePreference = key.second,
                )
            }.getOrDefault(emptyList())
        }
    }

    private fun uiStateOrDefaultKey(): Pair<VideoBitrateMode, AvcProfilePreference> =
        if (::uiState.isInitialized) uiState.bitrateMode to uiState.avcProfile
        else VideoBitrateMode.Cbr to AvcProfilePreference.Auto

    private fun modesForLens(lens: CameraLens): List<SupportedStreamMode> =
        resolvedModes().filter { it.lens == lens }

    private fun modeFor(lens: CameraLens, width: Int, height: Int, fps: Int): SupportedStreamMode? =
        resolvedModes().firstOrNull {
            it.lens == lens && it.width == width && it.height == height && it.fps == fps
        }

    private fun isSupported(lens: CameraLens, width: Int, height: Int, fps: Int, mbps: Int): Boolean {
        val mode = modeFor(lens, width, height, fps) ?: return false
        val max = mode.maxHardwareBitrate ?: return true
        return max >= mbps * 1_000_000
    }

    // ================= View binding / listeners =================

    private fun bindViews() {
        root = findViewById(R.id.settingsRoot)
        btnBack = findViewById(R.id.btnBackSettings)
        btnReset = findViewById(R.id.btnResetSettings)
        profileRow = findViewById(R.id.settingsProfileRow)
        profileEmpty = findViewById(R.id.settingsProfileEmpty)
        profileNameView = findViewById(R.id.settingsProfileName)
        profileMeta = findViewById(R.id.settingsProfileMeta)
        btnCreateProfile = findViewById(R.id.btnCreateProfile)
        rowCamera = findViewById(R.id.rowCamera)
        rowResolution = findViewById(R.id.rowResolution)
        rowFrameRate = findViewById(R.id.rowFrameRate)
        cameraValue = findViewById(R.id.settingsCameraValue)
        resolutionValue = findViewById(R.id.settingsResolutionValue)
        fpsValue = findViewById(R.id.settingsFpsValue)
        modeWarning = findViewById(R.id.settingsModeWarning)
        modeWarningTitle = findViewById(R.id.settingsModeWarningTitle)
        modeWarningDivider = findViewById(R.id.settingsModeWarningDivider)
        btnSwitchMode = findViewById(R.id.btnSwitchMode)
        bitrateValue = findViewById(R.id.settingsBitrateValue)
        bitrateBadge = findViewById(R.id.settingsBitrateBadge)
        bitrateSlider = findViewById(R.id.settingsBitrateSlider)
        bitrateRecLabel = findViewById(R.id.settingsBitrateRecLabel)
        bitrateMaxLabel = findViewById(R.id.settingsBitrateMaxLabel)
        audioToggle = findViewById(R.id.settingsAudioEnabled)
        audioStatus = findViewById(R.id.settingsAudioStatus)
        audioDivider = findViewById(R.id.settingsAudioDivider)
        rowAudioDetail = findViewById(R.id.rowAudioDetail)
        audioSummary = findViewById(R.id.settingsAudioSummary)
        connectionCard = findViewById(R.id.settingsConnectionCard)
        rowObsHost = findViewById(R.id.rowObsHost)
        hostValue = findViewById(R.id.settingsHostValue)
        hostError = findViewById(R.id.settingsHostError)
        rowSrt = findViewById(R.id.rowSrt)
        srtValue = findViewById(R.id.settingsSrtValue)
        rowAdvancedHeader = findViewById(R.id.rowAdvancedHeader)
        advancedPreview = findViewById(R.id.settingsAdvancedPreview)
        advancedChevron = findViewById(R.id.settingsAdvancedChevron)
        advancedBody = findViewById(R.id.settingsAdvancedBody)
        segBitrateSystem = findViewById(R.id.segBitrateSystem)
        segBitrateCbr = findViewById(R.id.segBitrateCbr)
        segBitrateVbr = findViewById(R.id.segBitrateVbr)
        segAvcAuto = findViewById(R.id.segAvcAuto)
        segAvcBaseline = findViewById(R.id.segAvcBaseline)
        segAvcMain = findViewById(R.id.segAvcMain)
        segAvcHigh = findViewById(R.id.segAvcHigh)
        avcNote = findViewById(R.id.settingsAvcNote)
        bFramesToggle = findViewById(R.id.settingsBFrames)
        btnKeyframeMinus = findViewById(R.id.btnKeyframeMinus)
        keyframeValue = findViewById(R.id.settingsKeyframeValue)
        btnKeyframePlus = findViewById(R.id.btnKeyframePlus)
        customSizeToggle = findViewById(R.id.settingsCustomSize)
        customFields = findViewById(R.id.settingsCustomFields)
        inputWidth = findViewById(R.id.settingsWidth)
        inputHeight = findViewById(R.id.settingsHeight)
        inputFps = findViewById(R.id.settingsFps)
        reportRows = findViewById(R.id.settingsReportRows)
        reportLensLabel = findViewById(R.id.settingsReportLensLabel)
        reportChips = findViewById(R.id.settingsReportChips)
        versionInfo = findViewById(R.id.settingsVersionInfo)
        blockedText = findViewById(R.id.settingsBlockedText)
        btnSave = findViewById(R.id.btnSaveSettings)
        btnSaveAndConnect = findViewById(R.id.btnSaveAndConnect)
        connectSpinner = findViewById(R.id.settingsConnectSpinner)
        connectLabel = findViewById(R.id.settingsConnectLabel)
        toastView = findViewById(R.id.settingsToast)
        sheetScrim = findViewById(R.id.sheetScrim)
        sheetPanel = findViewById(R.id.sheetPanel)
        sheetTitle = findViewById(R.id.sheetTitle)
        sheetSubtitle = findViewById(R.id.sheetSubtitle)
        sheetScroll = findViewById(R.id.sheetScroll)
        sheetContent = findViewById(R.id.sheetContent)
        sheetFooter = findViewById(R.id.sheetFooter)
        btnSheetDone = findViewById(R.id.btnSheetDone)
    }

    private fun wireListeners() {
        btnBack.setOnClickListener { onBackPressed() }
        btnReset.setOnClickListener { confirmReset() }
        profileRow.setOnClickListener { openSheet(profileSheet()) }
        btnCreateProfile.setOnClickListener { promptNewProfile() }
        rowCamera.setOnClickListener { openSheet(cameraSheet()) }
        rowResolution.setOnClickListener { openSheet(resolutionSheet()) }
        rowFrameRate.setOnClickListener { openSheet(fpsSheet()) }
        rowAudioDetail.setOnClickListener { openSheet(audioSheet()) }
        rowObsHost.setOnClickListener { openSheet(connectionSheet()) }
        rowSrt.setOnClickListener { openSheet(connectionSheet()) }
        rowAdvancedHeader.setOnClickListener {
            uiState.advancedOpen = !uiState.advancedOpen
            render()
        }
        btnSwitchMode.setOnClickListener {
            suggestedFallback()?.let { mode -> applyMode(mode.width, mode.height, mode.fps) }
        }

        bitrateSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser || rendering) return
                uiState.bitrateMbps = progress
                render()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        audioToggle.onCheckedChanged = { checked ->
            uiState.audioEnabled = checked
            render()
        }
        bFramesToggle.onCheckedChanged = { checked ->
            uiState.bFrames = checked
            render()
        }
        customSizeToggle.onCheckedChanged = { checked ->
            uiState.customSize = checked
            render()
        }

        segBitrateSystem.setOnClickListener { pickBitrateMode(VideoBitrateMode.SystemDefault) }
        segBitrateCbr.setOnClickListener { pickBitrateMode(VideoBitrateMode.Cbr) }
        segBitrateVbr.setOnClickListener { pickBitrateMode(VideoBitrateMode.Vbr) }
        segAvcAuto.setOnClickListener { pickAvcProfile(AvcProfilePreference.Auto) }
        segAvcBaseline.setOnClickListener { pickAvcProfile(AvcProfilePreference.Baseline) }
        segAvcMain.setOnClickListener { pickAvcProfile(AvcProfilePreference.Main) }
        segAvcHigh.setOnClickListener { pickAvcProfile(AvcProfilePreference.High) }

        btnKeyframeMinus.setOnClickListener {
            uiState.keyframeSeconds = (uiState.keyframeSeconds - 1)
                .coerceIn(StreamConfigStore.MIN_KEYFRAME_INTERVAL, StreamConfigStore.MAX_KEYFRAME_INTERVAL)
            render()
        }
        btnKeyframePlus.setOnClickListener {
            uiState.keyframeSeconds = (uiState.keyframeSeconds + 1)
                .coerceIn(StreamConfigStore.MIN_KEYFRAME_INTERVAL, StreamConfigStore.MAX_KEYFRAME_INTERVAL)
            render()
        }

        inputWidth.addNumberWatcher { uiState.width = it }
        inputHeight.addNumberWatcher { uiState.height = it }
        inputFps.addNumberWatcher { uiState.fps = it }

        btnSave.setOnClickListener { saveSettings(connectAfterSave = false) }
        btnSaveAndConnect.setOnClickListener { saveSettings(connectAfterSave = true) }
        versionInfo.setOnClickListener { showAboutDialog() }
        sheetScrim.setOnClickListener { closeSheet() }
        btnSheetDone.setOnClickListener {
            val spec = currentSheet
            if (spec == null || spec.onDone()) closeSheet()
        }
    }

    private fun EditText.addNumberWatcher(update: (Int) -> Unit) {
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (rendering) return
                s?.toString()?.trim()?.toIntOrNull()?.let(update)
            }
        })
    }

    private fun pickBitrateMode(mode: VideoBitrateMode) {
        uiState.bitrateMode = mode
        render()
    }

    private fun pickAvcProfile(profile: AvcProfilePreference) {
        if (profile == AvcProfilePreference.High && !highProfileAvailable()) {
            showToast(getString(R.string.st_toast_high_unavailable))
            return
        }
        uiState.avcProfile = profile
        render()
    }

    private fun highProfileAvailable(): Boolean =
        modesForLens(uiState.lens).any { it.highProfileAvailable } ||
            resolvedModes().any { it.highProfileAvailable }

    // ================= Render =================

    private fun render() {
        rendering = true
        try {
            renderProfileCard()
            renderQuickSetup()
            renderAudio()
            renderConnection()
            renderAdvanced()
            renderReport()
            renderActionBar()
        } finally {
            rendering = false
        }
    }

    private fun renderProfileCard() {
        val profiles = StreamProfileStore.list(this)
        val active = StreamProfileStore.active(this)
        if (active == null) {
            profileRow.visibility = View.GONE
            profileEmpty.visibility = View.VISIBLE
        } else {
            profileRow.visibility = View.VISIBLE
            profileEmpty.visibility = View.GONE
            profileNameView.text = active.name
            profileMeta.text = getString(R.string.st_profile_meta, profiles.size)
        }
    }

    private fun lensName(lens: CameraLens): String = getString(
        when (lens) {
            CameraLens.Back -> R.string.st_lens_back
            CameraLens.BackUltrawide -> R.string.st_lens_ultrawide
            CameraLens.BackTelephoto -> R.string.st_lens_telephoto
            CameraLens.Front -> R.string.st_lens_front
        },
    )

    private fun resolutionLabel(width: Int, height: Int): String = when {
        width == 3840 && height == 2160 -> "4K UHD"
        width == 2560 && height == 1440 -> "1440p"
        width == 1920 && height == 1080 -> "1080p"
        width == 1280 && height == 720 -> "720p"
        else -> "${width}×${height}"
    }

    private fun modeLabel(width: Int, height: Int, fps: Int): String =
        "${resolutionLabel(width, height)} · $fps FPS"

    private fun recommendationFor(height: Int, fps: Int): Triple<Int, Int, Int> = when {
        height >= 2160 && fps >= 60 -> Triple(30, 45, 35)
        height >= 2160 -> Triple(24, 36, 30)
        height >= 1080 && fps >= 60 -> Triple(16, 24, 20)
        height >= 1080 -> Triple(10, 16, 12)
        fps >= 60 -> Triple(8, 14, 10)
        else -> Triple(8, 10, 8)
    }

    private fun currentTupleSupported(): Boolean =
        uiState.customSize || isSupported(uiState.lens, uiState.width, uiState.height, uiState.fps, uiState.bitrateMbps)

    private fun suggestedFallback(): SupportedStreamMode? {
        val modes = modesForLens(uiState.lens)
        if (modes.isEmpty()) return null
        // Prefer same resolution at a lower fps, then canonical presets from high to low.
        modes.filter { it.width == uiState.width && it.height == uiState.height && it.fps < uiState.fps }
            .maxByOrNull { it.fps }
            ?.let { return it }
        for (preset in StreamPreset.entries.reversed()) {
            modes.firstOrNull { it.width == preset.width && it.height == preset.height && it.fps == preset.fps }
                ?.let { return it }
        }
        return modes.maxByOrNull { it.width.toLong() * it.height * it.fps }
    }

    private fun applyMode(width: Int, height: Int, fps: Int) {
        uiState.width = width
        uiState.height = height
        uiState.fps = fps
        uiState.customSize = false
        val (_, _, default) = recommendationFor(height, fps)
        uiState.bitrateMbps = default
        uiState.keyframeSeconds = 2
        render()
    }

    private fun renderQuickSetup() {
        cameraValue.text = lensName(uiState.lens)
        resolutionValue.text = if (uiState.customSize) {
            "${uiState.width}×${uiState.height}"
        } else {
            resolutionLabel(uiState.width, uiState.height)
        }
        fpsValue.text = getString(R.string.st_fps_value, uiState.fps)

        val supported = currentTupleSupported()
        fpsValue.setTextColor(getColor(if (supported) R.color.st_t1 else R.color.st_warn))
        if (!supported) {
            modeWarning.visibility = View.VISIBLE
            modeWarningDivider.visibility = View.VISIBLE
            modeWarningTitle.text = getString(
                R.string.st_mode_unsupported_title,
                modeLabel(uiState.width, uiState.height, uiState.fps),
                uiState.bitrateMbps,
            )
            val fallback = suggestedFallback()
            if (fallback != null) {
                btnSwitchMode.visibility = View.VISIBLE
                btnSwitchMode.text = getString(
                    R.string.st_switch_to,
                    modeLabel(fallback.width, fallback.height, fallback.fps),
                )
            } else {
                btnSwitchMode.visibility = View.GONE
            }
        } else {
            modeWarning.visibility = View.GONE
            modeWarningDivider.visibility = View.GONE
        }

        val (recMin, recMax, _) = recommendationFor(uiState.height, uiState.fps)
        bitrateValue.text = getString(R.string.st_bitrate_value, uiState.bitrateMbps)
        val inRec = uiState.bitrateMbps in recMin..recMax
        bitrateBadge.text = when {
            inRec -> getString(R.string.st_badge_recommended)
            uiState.bitrateMbps > recMax -> getString(R.string.st_badge_above)
            else -> getString(R.string.st_badge_below)
        }
        bitrateBadge.setTextColor(getColor(if (inRec) R.color.st_ok else R.color.st_warn))
        bitrateSlider.progress = uiState.bitrateMbps.coerceIn(SLIDER_MIN_MBPS, SLIDER_MAX_MBPS)
        bitrateRecLabel.text = getString(
            R.string.st_rec_footer,
            recMin,
            recMax,
            modeLabel(uiState.width, uiState.height, uiState.fps),
        )
        val codecMaxMbps = modeFor(uiState.lens, uiState.width, uiState.height, uiState.fps)
            ?.maxHardwareBitrate?.div(1_000_000)
        bitrateMaxLabel.text = if (codecMaxMbps != null && codecMaxMbps < SLIDER_MAX_MBPS) {
            getString(R.string.st_codec_max_limited, codecMaxMbps)
        } else {
            getString(R.string.st_codec_max)
        }
    }

    private fun renderAudio() {
        audioToggle.setCheckedSilently(uiState.audioEnabled)
        audioStatus.text = getString(if (uiState.audioEnabled) R.string.st_audio_on else R.string.st_audio_off)
        val detail = if (uiState.audioEnabled) View.VISIBLE else View.GONE
        audioDivider.visibility = detail
        rowAudioDetail.visibility = detail
        audioSummary.text = getString(
            R.string.st_audio_summary,
            formatKhz(uiState.audioSampleRate),
            getString(if (uiState.audioChannels == 1) R.string.st_mono else R.string.st_stereo),
            uiState.audioBitrateKbps,
        )
    }

    private fun formatKhz(sampleRate: Int): String {
        val khz = sampleRate / 1000f
        return if (khz % 1f == 0f) khz.toInt().toString() else String.format("%.1f", khz)
    }

    private fun hostInvalid(): Boolean =
        !SettingsValidator.isValidHost(uiState.host.trim(), required = false)

    private fun renderConnection() {
        val bad = hostInvalid()
        hostValue.text = uiState.host.trim().ifBlank { getString(R.string.st_host_unset) }
        hostValue.setTextColor(getColor(if (bad) R.color.st_err else R.color.st_t2))
        hostError.visibility = if (bad) View.VISIBLE else View.GONE
        connectionCard.setBackgroundResource(if (bad) R.drawable.bg_st_card_error else R.drawable.bg_st_card)
        srtValue.text = getString(R.string.st_srt_summary, uiState.obsPort, uiState.latencyMs)
    }

    private fun renderAdvanced() {
        advancedBody.visibility = if (uiState.advancedOpen) View.VISIBLE else View.GONE
        advancedPreview.text = getString(
            if (uiState.advancedOpen) R.string.st_advanced_preview_open else R.string.st_advanced_preview_closed,
        )
        advancedChevron.rotation = if (uiState.advancedOpen) 90f else 0f

        renderSegment(segBitrateSystem, VideoBitrateMode.SystemDefault.displayName, uiState.bitrateMode == VideoBitrateMode.SystemDefault, enabled = true)
        renderSegment(segBitrateCbr, VideoBitrateMode.Cbr.displayName, uiState.bitrateMode == VideoBitrateMode.Cbr, enabled = true)
        renderSegment(segBitrateVbr, VideoBitrateMode.Vbr.displayName, uiState.bitrateMode == VideoBitrateMode.Vbr, enabled = true)

        val highAvailable = highProfileAvailable()
        renderSegment(segAvcAuto, "Auto", uiState.avcProfile == AvcProfilePreference.Auto, enabled = true)
        renderSegment(segAvcBaseline, "Baseline", uiState.avcProfile == AvcProfilePreference.Baseline, enabled = true)
        renderSegment(segAvcMain, "Main", uiState.avcProfile == AvcProfilePreference.Main, enabled = true)
        renderSegment(segAvcHigh, "High", uiState.avcProfile == AvcProfilePreference.High && highAvailable, enabled = highAvailable)
        avcNote.text = getString(
            if (highAvailable) R.string.st_avc_note_auto else R.string.st_avc_note_unavailable,
        )

        bFramesToggle.setCheckedSilently(uiState.bFrames)
        keyframeValue.text = getString(R.string.st_keyframe_value, uiState.keyframeSeconds)

        customSizeToggle.setCheckedSilently(uiState.customSize)
        customFields.visibility = if (uiState.customSize) View.VISIBLE else View.GONE
        syncCustomField(inputWidth, uiState.width)
        syncCustomField(inputHeight, uiState.height)
        syncCustomField(inputFps, uiState.fps)
    }

    private fun syncCustomField(field: EditText, value: Int) {
        val text = value.toString()
        if (!field.hasFocus() && field.text.toString() != text) field.setText(text)
    }

    private fun renderSegment(view: TextView, label: String, selected: Boolean, enabled: Boolean) {
        view.text = label
        view.isSelected = selected && enabled
        view.setTextColor(
            when {
                !enabled -> getColor(R.color.st_t3)
                selected -> getColor(R.color.st_on_accent)
                else -> getColor(R.color.st_t1)
            },
        )
        view.paintFlags = if (enabled) {
            view.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
        } else {
            view.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
        }
    }

    private fun renderReport() {
        val modes = modesForLens(uiState.lens)
        val current = modeFor(uiState.lens, uiState.width, uiState.height, uiState.fps)
        val anyMode = current ?: modes.firstOrNull()

        reportRows.removeAllViews()
        val inflater = LayoutInflater.from(this)
        fun addRow(key: String, value: String, colorRes: Int = R.color.st_report_text) {
            val row = inflater.inflate(R.layout.item_report_row, reportRows, false)
            row.findViewById<TextView>(R.id.reportRowKey).text = key
            row.findViewById<TextView>(R.id.reportRowValue).apply {
                text = value
                setTextColor(getColor(colorRes))
            }
            reportRows.addView(row)
        }

        addRow(getString(R.string.st_report_camera_id), anyMode?.cameraId ?: "—")
        if (resolvedModes().isEmpty()) {
            addRow(getString(R.string.st_report_encoder), getString(R.string.st_report_encoder_unknown), R.color.st_warn)
        } else {
            addRow(getString(R.string.st_report_encoder), getString(R.string.st_report_encoder_hw), R.color.st_ok)
        }
        val highAvailable = highProfileAvailable()
        addRow(
            getString(R.string.st_report_high),
            getString(if (highAvailable) R.string.st_report_high_yes else R.string.st_report_high_no),
            if (highAvailable) R.color.st_ok else R.color.st_warn,
        )
        val codecMax = (anyMode?.maxHardwareBitrate ?: modes.mapNotNull { it.maxHardwareBitrate }.maxOrNull())
        addRow(
            getString(R.string.st_report_codec_max),
            codecMax?.let { getString(R.string.st_report_codec_max_value, it / 1_000_000) } ?: "—",
        )
        addRow(getString(R.string.st_report_camera_level), cameraSupportLevel(anyMode?.cameraId))

        reportLensLabel.text = getString(R.string.st_report_verified_modes, lensName(uiState.lens))
        reportChips.removeAllViews()
        StreamPreset.entries.forEach { preset ->
            val ok = modeFor(uiState.lens, preset.width, preset.height, preset.fps) != null
            val chip = TextView(this).apply {
                text = (if (ok) "✓ " else "✕ ") + modeLabel(preset.width, preset.height, preset.fps)
                textSize = 12f
                setTextColor(getColor(if (ok) R.color.st_ok else R.color.st_report_faded))
                setBackgroundResource(R.drawable.bg_st_report_chip)
                setPadding(dp(10), dp(6), dp(10), dp(6))
            }
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = dp(6) }
            reportChips.addView(chip, lp)
        }
    }

    private fun cameraSupportLevel(cameraId: String?): String {
        if (cameraId == null) return "—"
        return runCatching {
            val manager = getSystemService(CAMERA_SERVICE) as CameraManager
            when (manager.getCameraCharacteristics(cameraId)
                .get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)) {
                CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
                CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
                CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
                CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
                CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
                else -> "—"
            }
        }.getOrDefault("—")
    }

    private fun renderActionBar() {
        val hostBlocked = uiState.host.trim().isNotEmpty() && hostInvalid()
        val modeBlocked = !currentTupleSupported()
        val blocked = hostBlocked || modeBlocked
        blockedText.visibility = if (blocked) View.VISIBLE else View.GONE
        blockedText.text = getString(if (hostBlocked) R.string.st_blocked_host else R.string.st_blocked_mode)
        btnSaveAndConnect.isEnabled = !blocked
        connectLabel.setTextColor(getColor(if (blocked) R.color.st_t3 else R.color.st_on_accent))
    }

    // ================= Bottom sheets =================

    private data class SheetRow(
        val title: CharSequence,
        val sub: CharSequence? = null,
        val note: CharSequence? = null,
        val noteColorRes: Int = R.color.st_t3,
        val mark: String? = null,
        val markColorRes: Int = R.color.st_accent,
        val selected: Boolean = false,
        val enabled: Boolean = true,
        val outlined: Boolean = false,
        val error: Boolean = false,
        val tag: String? = null,
        val customView: View? = null,
        val onTap: (() -> Unit)? = null,
        val onLongPress: (() -> Unit)? = null,
    )

    private data class SheetSpec(
        val title: String,
        val sub: String?,
        val footer: String?,
        val rows: () -> List<SheetRow>,
        val onDone: () -> Boolean = { true },
    )

    private fun openSheet(spec: SheetSpec) {
        currentSheet = spec
        renderSheetRows(spec)
        sheetTitle.text = spec.title
        sheetSubtitle.text = spec.sub
        sheetSubtitle.visibility = if (spec.sub.isNullOrBlank()) View.GONE else View.VISIBLE
        sheetFooter.text = spec.footer
        sheetFooter.visibility = if (spec.footer.isNullOrBlank()) View.GONE else View.VISIBLE

        sheetScroll.layoutParams = sheetScroll.layoutParams.apply {
            height = ViewGroup.LayoutParams.WRAP_CONTENT
        }
        sheetScrim.visibility = View.VISIBLE
        sheetScrim.alpha = 0f
        sheetScrim.animate().alpha(1f).setDuration(200).start()
        sheetPanel.visibility = View.VISIBLE
        sheetPanel.post {
            val maxHeight = (root.height * 0.8f).toInt()
            if (sheetPanel.height > maxHeight) {
                val overflow = sheetPanel.height - maxHeight
                sheetScroll.layoutParams = sheetScroll.layoutParams.apply {
                    height = (sheetScroll.height - overflow).coerceAtLeast(dp(120))
                }
            }
            sheetPanel.translationY = sheetPanel.height.toFloat()
            sheetPanel.animate().translationY(0f).setDuration(250).start()
        }
    }

    private fun refreshSheet() {
        currentSheet?.let(::renderSheetRows)
    }

    private fun closeSheet(animated: Boolean = true) {
        if (sheetPanel.visibility != View.VISIBLE) return
        currentSheet = null
        if (animated) {
            sheetScrim.animate().alpha(0f).setDuration(200)
                .withEndAction { sheetScrim.visibility = View.GONE }.start()
            sheetPanel.animate().translationY(sheetPanel.height.toFloat()).setDuration(220)
                .withEndAction { sheetPanel.visibility = View.GONE }.start()
        } else {
            sheetScrim.visibility = View.GONE
            sheetPanel.visibility = View.GONE
        }
        render()
    }

    private fun renderSheetRows(spec: SheetSpec) {
        sheetContent.removeAllViews()
        val inflater = LayoutInflater.from(this)
        spec.rows().forEach { row ->
            val view = inflater.inflate(R.layout.item_sheet_row, sheetContent, false)
            view.setBackgroundResource(
                when {
                    row.error -> R.drawable.bg_st_sheet_row_error
                    row.outlined -> R.drawable.bg_st_sheet_row_outline
                    row.selected -> R.drawable.bg_st_sheet_row_selected
                    else -> R.drawable.bg_st_sheet_row
                },
            )
            view.findViewById<TextView>(R.id.sheetRowTitle).apply {
                text = row.title
                setTextColor(
                    getColor(
                        when {
                            row.outlined -> R.color.st_accent
                            row.enabled -> R.color.st_t1
                            else -> R.color.st_t3
                        },
                    ),
                )
            }
            view.findViewById<TextView>(R.id.sheetRowSub).apply {
                if (row.sub.isNullOrBlank()) visibility = View.GONE else {
                    visibility = View.VISIBLE
                    text = row.sub
                }
            }
            view.findViewById<TextView>(R.id.sheetRowNote).apply {
                if (row.note.isNullOrBlank()) visibility = View.GONE else {
                    visibility = View.VISIBLE
                    text = row.note
                    setTextColor(getColor(row.noteColorRes))
                }
            }
            view.findViewById<TextView>(R.id.sheetRowMark).apply {
                text = row.mark.orEmpty()
                setTextColor(getColor(row.markColorRes))
            }
            view.findViewById<FrameLayout>(R.id.sheetRowCustom).apply {
                removeAllViews()
                if (row.customView != null) {
                    visibility = View.VISIBLE
                    (row.customView.parent as? ViewGroup)?.removeView(row.customView)
                    addView(row.customView)
                } else {
                    visibility = View.GONE
                }
            }
            view.tag = row.tag
            row.onTap?.let { onTap -> view.setOnClickListener { onTap() } }
            row.onLongPress?.let { onLong ->
                view.setOnLongClickListener {
                    onLong()
                    true
                }
            }
            sheetContent.addView(view)
        }
    }

    // ---- Sheet builders ----

    private fun profileSheet(): SheetSpec = SheetSpec(
        title = getString(R.string.st_sheet_profile_title),
        sub = getString(R.string.st_sheet_profile_sub),
        footer = getString(R.string.st_sheet_profile_footer),
        rows = {
            val activeId = StreamProfileStore.active(this)?.id
            StreamProfileStore.list(this).map { profile ->
                val isActive = profile.id == activeId
                SheetRow(
                    title = profile.name,
                    sub = profileSummary(profile),
                    mark = getString(if (isActive) R.string.st_profile_mark_active else R.string.st_profile_mark_use),
                    markColorRes = if (isActive) R.color.st_ok else R.color.st_accent,
                    selected = isActive,
                    tag = "profile:${profile.id}",
                    onTap = { useProfileById(profile.id) },
                    onLongPress = { promptProfileActions(profile) },
                )
            } + SheetRow(
                title = getString(R.string.st_sheet_profile_new),
                sub = getString(R.string.st_sheet_profile_new_sub),
                outlined = true,
                tag = "profile:new",
                onTap = { promptNewProfile() },
            )
        },
    )

    private fun profileSummary(profile: StreamProfile): String {
        val c = profile.config
        val host = profile.obsHost.trim()
        return buildString {
            append(modeLabel(c.width, c.height, c.fps))
            append(" · ${c.bitrateMbps} Mbps")
            if (host.isNotBlank()) append(" · $host")
        }
    }

    private fun cameraSheet(): SheetSpec = SheetSpec(
        title = getString(R.string.st_sheet_camera_title),
        sub = getString(R.string.st_sheet_camera_sub),
        footer = getString(R.string.st_sheet_camera_footer),
        rows = {
            val lenses = resolvedModes().map { it.lens }.distinct().ifEmpty { listOf(uiState.lens) }
            lenses.map { lens ->
                SheetRow(
                    title = "${lensName(lens)} · ${lens.shortLabel}",
                    sub = lensSub(lens),
                    mark = if (uiState.lens == lens) "✓" else null,
                    selected = uiState.lens == lens,
                    tag = "lens:${lens.name}",
                    onTap = {
                        uiState.lens = lens
                        closeSheet()
                    },
                )
            }
        },
    )

    private fun lensSub(lens: CameraLens): String = getString(
        when (lens) {
            CameraLens.Back -> R.string.st_lens_back_sub
            CameraLens.BackUltrawide -> R.string.st_lens_ultrawide_sub
            CameraLens.BackTelephoto -> R.string.st_lens_tele_sub
            CameraLens.Front -> R.string.st_lens_front_sub
        },
    )

    private fun resolutionSheet(): SheetSpec = SheetSpec(
        title = getString(R.string.st_sheet_res_title),
        sub = getString(R.string.st_sheet_res_sub, uiState.fps),
        footer = run {
            val (recMin, recMax, _) = recommendationFor(uiState.height, uiState.fps)
            getString(R.string.st_sheet_res_footer, recMin, recMax)
        },
        rows = {
            val reported = modesForLens(uiState.lens).map { it.width to it.height }.distinct()
            // Canonical resolutions luôn hiển thị (greyed nếu camera không báo).
            val options = (reported + listOf(3840 to 2160, 1920 to 1080))
                .distinct()
                .sortedByDescending { (w, h) -> w.toLong() * h }
            options.map { (w, h) ->
                val target = modeFor(uiState.lens, w, h, uiState.fps)
                val ok = target != null
                val sel = !uiState.customSize && uiState.width == w && uiState.height == h
                val (recMin, recMax, _) = recommendationFor(h, uiState.fps)
                SheetRow(
                    title = resolutionLabel(w, h),
                    sub = "${w} × ${h}",
                    note = if (ok) {
                        getString(R.string.st_pair_note, modeLabel(w, h, uiState.fps), recMin, recMax)
                    } else {
                        getString(R.string.st_pair_unsupported, w, h, uiState.fps, lensName(uiState.lens))
                    },
                    noteColorRes = if (ok) R.color.st_t3 else R.color.st_warn,
                    mark = if (sel) "✓" else if (!ok) "✕" else null,
                    markColorRes = if (sel) R.color.st_accent else R.color.st_warn,
                    selected = sel,
                    enabled = ok,
                    tag = "res:${w}x${h}",
                    onTap = {
                        if (ok) {
                            applyMode(w, h, uiState.fps)
                            closeSheet()
                        } else {
                            showToast(
                                getString(R.string.st_toast_mode_unavailable, modeLabel(w, h, uiState.fps)),
                            )
                        }
                    },
                )
            }
        },
    )

    private fun fpsSheet(): SheetSpec = SheetSpec(
        title = getString(R.string.st_sheet_fps_title),
        sub = getString(R.string.st_sheet_fps_sub, resolutionLabel(uiState.width, uiState.height)),
        footer = getString(R.string.st_sheet_fps_footer),
        rows = {
            val reported = modesForLens(uiState.lens).map { it.fps }.distinct()
            // Luôn hiển thị cả các fps chuẩn máy không báo — greyed kèm lý do,
            // thay vì biến mất im lặng (design: never silently hidden).
            val options = (reported + listOf(24, 30, 60)).distinct().sorted()
            options.map { fps ->
                val target = modeFor(uiState.lens, uiState.width, uiState.height, fps)
                val ok = target != null
                val sel = !uiState.customSize && uiState.fps == fps
                val (recMin, recMax, _) = recommendationFor(uiState.height, fps)
                val cameraOffersFps = fps in reported
                SheetRow(
                    title = getString(R.string.st_fps_value, fps),
                    note = when {
                        ok -> getString(
                            R.string.st_pair_note,
                            modeLabel(uiState.width, uiState.height, fps),
                            recMin,
                            recMax,
                        )
                        !cameraOffersFps &&
                            resolver.encoderSupports(uiState.width, uiState.height, fps) ->
                            getString(R.string.st_fps_vendor_locked, fps)
                        else -> getString(
                            R.string.st_pair_unsupported,
                            uiState.width,
                            uiState.height,
                            fps,
                            lensName(uiState.lens),
                        )
                    },
                    noteColorRes = if (ok) R.color.st_t3 else R.color.st_warn,
                    mark = if (sel) "✓" else if (!ok) "✕" else null,
                    markColorRes = if (sel) R.color.st_accent else R.color.st_warn,
                    selected = sel,
                    enabled = ok,
                    tag = "fps:$fps",
                    onTap = {
                        if (ok) {
                            applyMode(uiState.width, uiState.height, fps)
                            closeSheet()
                        } else {
                            showToast(
                                getString(
                                    R.string.st_toast_mode_unavailable,
                                    modeLabel(uiState.width, uiState.height, fps),
                                ),
                            )
                        }
                    },
                )
            }
        },
    )

    private fun audioSheet(): SheetSpec = SheetSpec(
        title = getString(R.string.st_sheet_audio_title),
        sub = getString(R.string.st_sheet_audio_sub),
        footer = getString(R.string.st_sheet_audio_footer),
        rows = {
            val rates = (listOf(44_100, 48_000) + uiState.audioSampleRate).distinct().sorted()
            val bitrates = (listOf(96, 128, 192) + uiState.audioBitrateKbps).distinct().sorted()
            listOf(
                SheetRow(
                    title = getString(R.string.st_sample_rate),
                    customView = pillGroup(
                        rates.map { rate -> "${formatKhz(rate)} kHz" to rate },
                        uiState.audioSampleRate,
                    ) { uiState.audioSampleRate = it },
                    tag = "audio:rate",
                ),
                SheetRow(
                    title = getString(R.string.st_channels),
                    sub = getString(R.string.st_channels_sub),
                    customView = pillGroup(
                        listOf(getString(R.string.st_mono) to 1, getString(R.string.st_stereo) to 2),
                        uiState.audioChannels,
                    ) { uiState.audioChannels = it },
                    tag = "audio:channels",
                ),
                SheetRow(
                    title = getString(R.string.st_audio_bitrate),
                    customView = pillGroup(
                        bitrates.map { kbps -> "$kbps kbps" to kbps },
                        uiState.audioBitrateKbps,
                    ) { uiState.audioBitrateKbps = it },
                    tag = "audio:bitrate",
                ),
            )
        },
    )

    private fun pillGroup(
        options: List<Pair<String, Int>>,
        selectedValue: Int,
        onPick: (Int) -> Unit,
    ): LinearLayout {
        val group = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        options.forEachIndexed { index, (label, value) ->
            val pill = TextView(this).apply {
                text = label
                textSize = 13f
                gravity = android.view.Gravity.CENTER
                setPadding(dp(6), dp(11), dp(6), dp(11))
                setBackgroundResource(R.drawable.bg_st_segment)
                isSelected = value == selectedValue
                setTextColor(getColor(if (value == selectedValue) R.color.st_on_accent else R.color.st_t1))
                setOnClickListener {
                    onPick(value)
                    refreshSheet()
                }
            }
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            if (index > 0) lp.marginStart = dp(8)
            group.addView(pill, lp)
        }
        return group
    }

    private fun connectionSheet(): SheetSpec {
        val inflater = LayoutInflater.from(this)

        fun inputRow(initial: String, hint: String, numeric: Boolean): Pair<View, EditText> {
            val view = inflater.inflate(R.layout.item_sheet_input, null, false)
            val field = view.findViewById<EditText>(R.id.sheetInputField).apply {
                if (numeric) inputType = android.text.InputType.TYPE_CLASS_NUMBER
                setText(initial)
                this.hint = hint
            }
            return view to field
        }

        val (hostView, hostField) = inputRow(uiState.host, "192.168.1.5", numeric = false)
        val (obsPortView, obsPortField) = inputRow(uiState.obsPort.toString(), "9000", numeric = true)
        val (listenPortView, listenPortField) = inputRow(uiState.listenPort.toString(), "9000", numeric = true)
        val (latencyView, latencyField) = inputRow(uiState.latencyMs.toString(), "120", numeric = true)

        hostField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val value = s?.toString()?.trim().orEmpty()
                uiState.host = value
                val bad = value.isNotEmpty() && !SettingsValidator.isValidHost(value, required = false)
                hostField.setBackgroundResource(if (bad) R.drawable.bg_st_input_error else R.drawable.bg_st_input)
            }
        })

        fun commitPort(field: EditText, default: Int, range: IntRange): Int? {
            val raw = field.text.toString().trim()
            if (raw.isBlank()) return default
            val value = SettingsValidator.parseNumber(raw, default, range)
            field.setBackgroundResource(if (value == null) R.drawable.bg_st_input_error else R.drawable.bg_st_input)
            return value
        }

        return SheetSpec(
            title = getString(R.string.st_sheet_conn_title),
            sub = getString(R.string.st_sheet_conn_sub),
            footer = getString(R.string.st_sheet_conn_footer),
            rows = {
                listOf(
                    SheetRow(
                        title = getString(R.string.st_obs_computer),
                        sub = getString(R.string.st_obs_computer_sub),
                        customView = hostView,
                        tag = "conn:host",
                    ),
                    SheetRow(
                        title = getString(R.string.st_obs_port),
                        sub = getString(R.string.st_obs_port_sub),
                        customView = obsPortView,
                        tag = "conn:obsPort",
                    ),
                    SheetRow(
                        title = getString(R.string.st_listen_port),
                        sub = getString(R.string.st_listen_port_sub),
                        customView = listenPortView,
                        tag = "conn:listenPort",
                    ),
                    SheetRow(
                        title = getString(R.string.st_latency),
                        sub = getString(R.string.st_latency_sub),
                        customView = latencyView,
                        tag = "conn:latency",
                    ),
                )
            },
            onDone = {
                uiState.host = hostField.text.toString().trim()
                val obsPort = commitPort(obsPortField, ConnectionTarget.DEFAULT_PORT, 1..65535)
                val listenPort = commitPort(listenPortField, ConnectionTarget.DEFAULT_PORT, 1024..65535)
                val latency = commitPort(
                    latencyField,
                    ConnectionTarget.DEFAULT_LATENCY_MS,
                    StreamConfigStore.MIN_LATENCY_MS..StreamConfigStore.MAX_LATENCY_MS,
                )
                if (obsPort != null && listenPort != null && latency != null) {
                    uiState.obsPort = obsPort
                    uiState.listenPort = listenPort
                    uiState.latencyMs = latency
                    true
                } else {
                    false
                }
            },
        )
    }

    // ================= Profiles =================

    private fun promptProfileActions(profile: StreamProfile) {
        AlertDialog.Builder(this)
            .setTitle(profile.name)
            .setItems(arrayOf(getString(R.string.st_rename), getString(R.string.st_delete))) { _, which ->
                when (which) {
                    0 -> promptRenameProfile(profile)
                    1 -> confirmDeleteProfile(profile)
                }
            }
            .show()
    }

    private fun promptRenameProfile(profile: StreamProfile) {
        promptProfileName(getString(R.string.st_rename_profile_title), profile.name) { name ->
            renameProfileById(profile.id, name)
        }
    }

    private fun confirmDeleteProfile(profile: StreamProfile) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.st_delete_confirm, profile.name))
            .setPositiveButton(R.string.st_delete) { _, _ -> deleteProfileById(profile.id) }
            .setNegativeButton(R.string.st_cancel, null)
            .show()
    }

    private fun promptNewProfile() {
        promptProfileName(getString(R.string.st_new_profile_title), "") { name ->
            createProfileNamed(name)
        }
    }

    private fun promptProfileName(title: String, initial: String, onSubmit: (String) -> Boolean) {
        val field = EditText(this).apply {
            setText(initial)
            hint = getString(R.string.st_profile_name_hint)
            setSingleLine()
        }
        val container = FrameLayout(this).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(field)
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(container)
            .setPositiveButton(R.string.st_ok) { _, _ ->
                if (!onSubmit(field.text.toString().trim())) {
                    showToast(getString(R.string.st_profile_name_invalid))
                }
            }
            .setNegativeButton(R.string.st_cancel, null)
            .show()
    }

    internal fun createProfileNamed(name: String): Boolean {
        if (!SettingsValidator.isValidProfileName(name)) return false
        val pending = readValidatedSettings(requireHost = false) ?: return false
        val existing = StreamProfileStore.list(this).firstOrNull { it.name.equals(name, ignoreCase = true) }
        val id = existing?.id ?: UUID.randomUUID().toString()
        StreamProfileStore.save(
            this,
            StreamProfile(
                id = id,
                name = name,
                config = pending.config,
                lens = pending.lens,
                obsHost = pending.host,
                obsPort = pending.port,
                listeningPort = pending.listenPort,
            ),
            makeActive = true,
        )
        showToast(getString(R.string.st_toast_profile_saved, name))
        refreshSheet()
        render()
        return true
    }

    internal fun useProfileById(id: String): Boolean {
        val profile = StreamProfileStore.load(this, id) ?: return false
        StreamProfileStore.setActive(this, id)
        StreamConfigStore.save(this, profile.config)
        StreamConfig.installRuntimeConfig(profile.config)
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putString(KEY_OBS_HOST, profile.obsHost)
            .putInt(KEY_OBS_PORT, profile.obsPort)
            .putInt(KEY_LISTENING_PORT, profile.listeningPort)
            .putString(KEY_CAPABILITY_LENS, profile.lens?.name)
            .apply()
        val config = profile.config
        uiState = uiState.copy(
            lens = profile.lens ?: uiState.lens,
            width = config.width,
            height = config.height,
            fps = config.fps,
            customSize = StreamPreset.entries.none { it.matches(config) },
            bitrateMbps = config.bitrateMbps.coerceIn(SLIDER_MIN_MBPS, SLIDER_MAX_MBPS),
            bitrateMode = config.videoBitrateMode,
            avcProfile = config.avcProfilePreference,
            bFrames = config.bFramesEnabled,
            keyframeSeconds = config.keyframeIntervalSeconds,
            audioEnabled = config.audioEnabled,
            audioSampleRate = config.audioSampleRate,
            audioChannels = config.audioChannelCount,
            audioBitrateKbps = config.audioBitrateKbps,
            host = profile.obsHost,
            obsPort = profile.obsPort,
            listenPort = profile.listeningPort,
            latencyMs = config.latencyMs,
        )
        savedPendingRestart = true
        closeSheet()
        showToast(getString(R.string.st_toast_profile_used, profile.name))
        return true
    }

    internal fun renameProfileById(id: String, name: String): Boolean {
        if (!SettingsValidator.isValidProfileName(name)) return false
        val profile = StreamProfileStore.load(this, id) ?: return false
        val wasActive = StreamProfileStore.active(this)?.id == id
        StreamProfileStore.save(this, profile.copy(name = name), makeActive = wasActive)
        refreshSheet()
        render()
        return true
    }

    internal fun deleteProfileById(id: String): Boolean {
        val profile = StreamProfileStore.load(this, id) ?: return false
        StreamProfileStore.delete(this, id)
        showToast(getString(R.string.st_toast_profile_deleted, profile.name))
        refreshSheet()
        render()
        return true
    }

    // ================= Reset / About / Toast =================

    private fun confirmReset() {
        AlertDialog.Builder(this)
            .setTitle(R.string.st_reset_title)
            .setMessage(R.string.st_reset_message)
            .setPositiveButton(R.string.st_reset) { _, _ -> resetToDefaults() }
            .setNegativeButton(R.string.st_cancel, null)
            .show()
    }

    internal fun resetToDefaults() {
        val defaults = StreamConfig.Baseline1080p30
        val lenses = resolvedModes().map { it.lens }.distinct()
        uiState = UiState(
            lens = lenses.firstOrNull() ?: CameraLens.Back,
            width = defaults.width,
            height = defaults.height,
            fps = defaults.fps,
            customSize = false,
            bitrateMbps = defaults.bitrateMbps,
            bitrateMode = defaults.videoBitrateMode,
            avcProfile = defaults.avcProfilePreference,
            bFrames = defaults.bFramesEnabled,
            keyframeSeconds = defaults.keyframeIntervalSeconds,
            audioEnabled = defaults.audioEnabled,
            audioSampleRate = defaults.audioSampleRate,
            audioChannels = defaults.audioChannelCount,
            audioBitrateKbps = defaults.audioBitrateKbps,
            host = "",
            obsPort = ConnectionTarget.DEFAULT_PORT,
            listenPort = ConnectionTarget.DEFAULT_PORT,
            latencyMs = defaults.latencyMs,
        )
        render()
    }

    private fun showAboutDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.app_name))
            .setMessage(versionInfo.text)
            .setPositiveButton(R.string.st_ok, null)
            .show()
    }

    private fun showToast(message: String) {
        toastHideRunnable?.let { toastView.removeCallbacks(it) }
        toastView.text = message
        toastView.visibility = View.VISIBLE
        toastView.alpha = 0.4f
        toastView.translationY = dp(18).toFloat()
        toastView.animate().alpha(1f).translationY(0f).setDuration(250).start()
        val hide = Runnable {
            toastView.animate().alpha(0f).setDuration(200)
                .withEndAction { toastView.visibility = View.GONE }.start()
        }
        toastHideRunnable = hide
        toastView.postDelayed(hide, 2600)
    }

    // ================= Save flow =================

    private fun saveSettings(connectAfterSave: Boolean) {
        val pending = readValidatedSettings(requireHost = connectAfterSave) ?: return
        persistCurrent(pending)
        if (!connectAfterSave) {
            savedPendingRestart = true
            showToast(getString(R.string.st_toast_saved))
            render()
            return
        }
        connectSpinner.visibility = View.VISIBLE
        connectLabel.text = getString(R.string.st_connecting)
        btnSave.isEnabled = false
        btnSaveAndConnect.isEnabled = false
        btnSaveAndConnect.postDelayed({
            restartMainActivity(true, pending.host, pending.port, pending.config.latencyMs)
        }, 300)
    }

    private fun readValidatedSettings(requireHost: Boolean): PendingSettings? {
        val current = StreamConfigStore.load(this)
        val s = uiState

        fun blocked(message: String): PendingSettings? {
            showToast(message)
            return null
        }

        if (s.width !in StreamConfigStore.MIN_WIDTH..StreamConfigStore.MAX_WIDTH ||
            s.height !in StreamConfigStore.MIN_HEIGHT..StreamConfigStore.MAX_HEIGHT ||
            s.fps !in StreamConfigStore.MIN_FPS..StreamConfigStore.MAX_FPS
        ) {
            return blocked(getString(R.string.st_toast_hw_reject))
        }
        val bitrateMbps = s.bitrateMbps.coerceIn(
            StreamConfig.MIN_CONFIGURABLE_BITRATE_MBPS,
            StreamConfig.MAX_CONFIGURABLE_BITRATE_MBPS,
        )

        val validAtRequestedSettings = runCatching {
            resolver.resolve(
                bitrate = bitrateMbps * 1_000_000,
                bitrateMode = s.bitrateMode,
                profilePreference = s.avcProfile,
            ).any { it.lens == s.lens && it.width == s.width && it.height == s.height && it.fps == s.fps }
        }.getOrDefault(false)
        if (!validAtRequestedSettings) {
            return blocked(getString(R.string.st_toast_hw_reject))
        }

        val host = s.host.trim()
        if (!SettingsValidator.isValidHost(host, required = requireHost)) {
            return blocked(getString(R.string.st_toast_fix_host))
        }

        val config = current.copy(
            width = s.width,
            height = s.height,
            fps = s.fps,
            bitrate = bitrateMbps * 1_000_000,
            keyframeIntervalSeconds = s.keyframeSeconds
                .coerceIn(StreamConfigStore.MIN_KEYFRAME_INTERVAL, StreamConfigStore.MAX_KEYFRAME_INTERVAL),
            latencyMs = s.latencyMs
                .coerceIn(StreamConfigStore.MIN_LATENCY_MS, StreamConfigStore.MAX_LATENCY_MS),
            videoBitrateMode = s.bitrateMode,
            avcProfilePreference = s.avcProfile,
            bFramesEnabled = s.bFrames,
            audioEnabled = s.audioEnabled,
            audioSampleRate = s.audioSampleRate
                .coerceIn(StreamConfigStore.MIN_AUDIO_SAMPLE_RATE, StreamConfigStore.MAX_AUDIO_SAMPLE_RATE),
            audioChannelCount = s.audioChannels
                .coerceIn(StreamConfigStore.MIN_AUDIO_CHANNELS, StreamConfigStore.MAX_AUDIO_CHANNELS),
            audioBitrate = s.audioBitrateKbps
                .coerceIn(StreamConfigStore.MIN_AUDIO_BITRATE_KBPS, StreamConfigStore.MAX_AUDIO_BITRATE_KBPS) * 1_000,
        )
        return PendingSettings(config, s.lens, host, s.obsPort, s.listenPort)
    }

    private fun persistCurrent(pending: PendingSettings) {
        StreamConfigStore.save(this, pending.config)
        StreamConfig.installRuntimeConfig(pending.config)
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putString(KEY_OBS_HOST, pending.host)
            .putInt(KEY_OBS_PORT, pending.port)
            .putInt(KEY_LISTENING_PORT, pending.listenPort)
            .putString(KEY_CAPABILITY_LENS, pending.lens?.name)
            .apply()
    }

    private fun restartMainActivity(connectAfterSave: Boolean, host: String, port: Int, latency: Int) {
        val restart = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            if (connectAfterSave) {
                data = Uri.Builder()
                    .scheme("openstream")
                    .authority("connect")
                    .appendQueryParameter("host", host)
                    .appendQueryParameter("port", port.toString())
                    .appendQueryParameter("latency", latency.toString())
                    .appendQueryParameter("name", ConnectionTarget.DEFAULT_NAME)
                    .build()
            }
        }
        startActivity(restart)
        finish()
    }

    private fun showVersionInfo() {
        runCatching {
            val info = packageManager.getPackageInfo(packageName, 0)
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
            versionInfo.text = getString(R.string.st_version_footer, info.versionName, code.toString())
        }
    }

    // ================= Test hooks =================

    internal fun currentUiStateForTest(): Map<String, Any> = mapOf(
        "lens" to uiState.lens.name,
        "width" to uiState.width,
        "height" to uiState.height,
        "fps" to uiState.fps,
        "customSize" to uiState.customSize,
        "bitrateMbps" to uiState.bitrateMbps,
        "audioEnabled" to uiState.audioEnabled,
        "host" to uiState.host,
        "obsPort" to uiState.obsPort,
        "listenPort" to uiState.listenPort,
        "latencyMs" to uiState.latencyMs,
    )

    internal fun setHostForTest(host: String) {
        uiState.host = host
        render()
    }

    // ================= Misc =================

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private data class PendingSettings(
        val config: StreamConfig,
        val lens: CameraLens?,
        val host: String,
        val port: Int,
        val listenPort: Int,
    )

    companion object {
        const val PREFS_NAME = StreamConfigStore.PREFS_NAME
        const val KEY_OBS_HOST = "obs_host"
        const val KEY_OBS_PORT = "obs_port"
        const val KEY_LATENCY = StreamConfigStore.KEY_LATENCY
        const val KEY_LISTENING_PORT = "listening_port"
        const val KEY_CAPABILITY_LENS = "capability_lens"
        const val EXTRA_CONNECT_AFTER_SAVE = "connect_after_save"

        private const val PROBE_BITRATE_BPS = 8_000_000
        private const val SLIDER_MIN_MBPS = 8
        private const val SLIDER_MAX_MBPS = 50

        private const val KEY_STATE_LENS = "st_lens"
        private const val KEY_STATE_WIDTH = "st_width"
        private const val KEY_STATE_HEIGHT = "st_height"
        private const val KEY_STATE_FPS = "st_fps"
        private const val KEY_STATE_CUSTOM = "st_custom"
        private const val KEY_STATE_BITRATE = "st_bitrate"
        private const val KEY_STATE_BITRATE_MODE = "st_bitrate_mode"
        private const val KEY_STATE_AVC = "st_avc"
        private const val KEY_STATE_BFRAMES = "st_bframes"
        private const val KEY_STATE_KEYFRAME = "st_keyframe"
        private const val KEY_STATE_AUDIO = "st_audio"
        private const val KEY_STATE_AUDIO_RATE = "st_audio_rate"
        private const val KEY_STATE_AUDIO_CH = "st_audio_ch"
        private const val KEY_STATE_AUDIO_KBPS = "st_audio_kbps"
        private const val KEY_STATE_HOST = "st_host"
        private const val KEY_STATE_OBS_PORT = "st_obs_port"
        private const val KEY_STATE_LISTEN_PORT = "st_listen_port"
        private const val KEY_STATE_LATENCY = "st_latency"
        private const val KEY_STATE_ADVANCED = "st_advanced"
        private const val KEY_STATE_PENDING_RESTART = "st_pending_restart"
    }
}
