package dev.openstream.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.openstream.app.camera.CameraCapabilityProbe
import dev.openstream.app.camera.CameraLens
import dev.openstream.app.encoder.AvcProfilePreference
import dev.openstream.app.encoder.CodecPreference
import dev.openstream.app.encoder.HardwareVideoCapabilityProbe
import dev.openstream.app.encoder.VideoBitrateMode
import dev.openstream.app.stream.StreamConfig
import dev.openstream.app.stream.StreamConfigStore
import dev.openstream.app.stream.StreamingCapabilityResolver
import org.json.JSONObject
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Nghiệm thu Giai đoạn 4 trên thiết bị thật.
 *
 * Nếu chuỗi Camera2 + MediaCodec không hỗ trợ 4K60, test ghi bằng chứng
 * modeSupported=false và xác nhận resolver không quảng cáo 4K60. Nếu có hỗ trợ,
 * test phát 4K60 thật qua SRT để đầu Linux kiểm bằng ffprobe.
 */
@RunWith(AndroidJUnit4::class)
class Phase4DeviceE2eTest {

    @Test
    fun validate4k60CapabilityAndStreamWhenSupported() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val args = InstrumentationRegistry.getArguments()

        val receiverHost = requireArgument(args.getString(ARG_RECEIVER_HOST), ARG_RECEIVER_HOST)
        val receiverPort = args.getString(ARG_RECEIVER_PORT)?.toIntOrNull() ?: DEFAULT_RECEIVER_PORT
        val durationSeconds = args.getString(ARG_DURATION_SECONDS)?.toIntOrNull()
            ?.coerceIn(MIN_DURATION_SECONDS, MAX_DURATION_SECONDS)
            ?: DEFAULT_DURATION_SECONDS
        val streamBitrateMbps = args.getString(ARG_STREAM_BITRATE_MBPS)?.toIntOrNull()
            ?.coerceIn(StreamConfig.MIN_CONFIGURABLE_BITRATE_MBPS, StreamConfig.MAX_CONFIGURABLE_BITRATE_MBPS)
            ?: DEFAULT_STREAM_BITRATE_MBPS
        val capabilityBitrateMbps = args.getString(ARG_CAPABILITY_BITRATE_MBPS)?.toIntOrNull()
            ?.coerceIn(StreamConfig.MIN_CONFIGURABLE_BITRATE_MBPS, StreamConfig.MAX_CONFIGURABLE_BITRATE_MBPS)
            ?: DEFAULT_CAPABILITY_BITRATE_MBPS
        val latencyMs = args.getString(ARG_LATENCY_MS)?.toIntOrNull()
            ?.coerceIn(StreamConfigStore.MIN_LATENCY_MS, StreamConfigStore.MAX_LATENCY_MS)
            ?: DEFAULT_LATENCY_MS

        assertTrue(
            "CAMERA permission must be granted before instrumentation starts",
            context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
        )
        assertTrue(
            "RECORD_AUDIO permission must be granted before instrumentation starts",
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
        )

        val rawCameraMode = CameraCapabilityProbe(context).query()
            .flatMap { it.modes }
            .firstOrNull {
                it.lens == CameraLens.Back &&
                    it.width == TARGET_WIDTH &&
                    it.height == TARGET_HEIGHT &&
                    it.fps == TARGET_FPS
            }
        val hardwareProbe = HardwareVideoCapabilityProbe()
        val hardwareSupports = hardwareProbe.supportsAvc(
            width = TARGET_WIDTH,
            height = TARGET_HEIGHT,
            fps = TARGET_FPS,
            bitrate = capabilityBitrateMbps * 1_000_000,
            bitrateMode = VideoBitrateMode.Cbr,
            profilePreference = AvcProfilePreference.Auto,
        )
        val resolver = StreamingCapabilityResolver(context)
        val capabilityModes = resolver.resolve(
            bitrate = capabilityBitrateMbps * 1_000_000,
            bitrateMode = VideoBitrateMode.Cbr,
            profilePreference = AvcProfilePreference.Auto,
        )
        val targetMode = capabilityModes.firstOrNull {
            it.lens == CameraLens.Back &&
                it.width == TARGET_WIDTH &&
                it.height == TARGET_HEIGHT &&
                it.fps == TARGET_FPS
        }

        if (rawCameraMode == null || !hardwareSupports) {
            assertTrue(
                "Resolver must not advertise 4K60 when Camera2 or hardware AVC cannot satisfy the requested gate",
                targetMode == null,
            )
            writePreflightEvidence(
                filesDir = context.filesDir,
                modeSupported = false,
                cameraId = rawCameraMode?.cameraId,
                rawCameraSupports = rawCameraMode != null,
                hardwareSupports = hardwareSupports,
                highProfileAvailable = false,
                maxHardwareBitrate = null,
                profilePreference = AvcProfilePreference.Auto,
                capabilityBitrateMbps = capabilityBitrateMbps,
                streamBitrateMbps = streamBitrateMbps,
                durationSeconds = durationSeconds,
                receiverHost = receiverHost,
                receiverPort = receiverPort,
                latencyMs = latencyMs,
            )
            return
        }

        assertNotNull(
            "Camera2 + hardware AVC support 4K60 independently, so resolver must advertise the same mode",
            targetMode,
        )
        targetMode!!

        val profilePreference = if (targetMode.highProfileAvailable) {
            AvcProfilePreference.High
        } else {
            AvcProfilePreference.Auto
        }
        val networkModes = resolver.resolve(
            bitrate = streamBitrateMbps * 1_000_000,
            bitrateMode = VideoBitrateMode.Cbr,
            profilePreference = profilePreference,
        )
        assertTrue(
            "The same physical 4K60 path must support the E2E stream bitrate $streamBitrateMbps Mbps",
            networkModes.any {
                it.cameraId == targetMode.cameraId &&
                    it.lens == targetMode.lens &&
                    it.width == TARGET_WIDTH &&
                    it.height == TARGET_HEIGHT &&
                    it.fps == TARGET_FPS
            },
        )

        val config = StreamConfig.Baseline1080p30.copy(
            width = TARGET_WIDTH,
            height = TARGET_HEIGHT,
            fps = TARGET_FPS,
            bitrate = streamBitrateMbps * 1_000_000,
            keyframeIntervalSeconds = TARGET_KEYFRAME_SECONDS,
            latencyMs = latencyMs,
            codecPreference = CodecPreference.ForceAvc,
            videoBitrateMode = VideoBitrateMode.Cbr,
            avcProfilePreference = profilePreference,
            bFramesEnabled = false,
            audioEnabled = true,
            audioSampleRate = TARGET_AUDIO_SAMPLE_RATE,
            audioChannelCount = TARGET_AUDIO_CHANNELS,
            audioBitrate = TARGET_AUDIO_BITRATE,
        )
        StreamConfigStore.save(context, config)
        StreamConfig.installRuntimeConfig(config)

        writePreflightEvidence(
            filesDir = context.filesDir,
            modeSupported = true,
            cameraId = targetMode.cameraId,
            rawCameraSupports = true,
            hardwareSupports = true,
            highProfileAvailable = targetMode.highProfileAvailable,
            maxHardwareBitrate = targetMode.maxHardwareBitrate,
            profilePreference = profilePreference,
            capabilityBitrateMbps = capabilityBitrateMbps,
            streamBitrateMbps = streamBitrateMbps,
            durationSeconds = durationSeconds,
            receiverHost = receiverHost,
            receiverPort = receiverPort,
            latencyMs = latencyMs,
        )

        // Khởi động Activity trước rồi mới gửi pairing intent. Nhánh Phase 4 được
        // ghép trên Phase 3 khi review; cách này tập trung test 4K60/advanced
        // encoding và tránh lặp lại regression cold-start caller đã được Phase 3 gate.
        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            },
        ) as MainActivity
        instrumentation.waitForIdleSync()
        SystemClock.sleep(STARTUP_SETTLE_MS)

        val targetUri = Uri.Builder()
            .scheme("openstream")
            .authority("connect")
            .appendQueryParameter("host", receiverHost)
            .appendQueryParameter("port", receiverPort.toString())
            .appendQueryParameter("latency", latencyMs.toString())
            .appendQueryParameter("bitrateMbps", streamBitrateMbps.toString())
            .appendQueryParameter("name", "Phase 4 device E2E")
            .build()
        val pairingIntent = Intent(Intent.ACTION_VIEW, targetUri, context, MainActivity::class.java)
        instrumentation.runOnMainSync { activity.onNewIntent(pairingIntent) }

        assertTrue(
            "OpenStream did not reach LIVE state within ${CONNECT_TIMEOUT_MS}ms",
            waitForLiveState(instrumentation, activity, CONNECT_TIMEOUT_MS),
        )

        val deadline = SystemClock.elapsedRealtime() + durationSeconds * 1_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            assertTrue(
                "OpenStream left LIVE state during the 4K60 E2E sample",
                isLive(instrumentation, activity),
            )
            SystemClock.sleep(LIVE_POLL_MS)
        }

        val streamInfo = readStreamInfo(instrumentation, activity)
        val counts = FRAME_INFO.find(streamInfo)
        assertNotNull("Stream telemetry must expose frame/keyframe counters, got: $streamInfo", counts)
        val frames = counts!!.groupValues[1].toLong()
        val keyframes = counts.groupValues[2].toLong()
        assertTrue("4K60 stream must deliver encoded frames, got: $streamInfo", frames > 0)
        assertTrue("4K60 stream must deliver keyframes, got: $streamInfo", keyframes > 0)

        instrumentation.runOnMainSync { activity.finish() }
        instrumentation.waitForIdleSync()
    }

    private fun waitForLiveState(
        instrumentation: android.app.Instrumentation,
        activity: MainActivity,
        timeoutMs: Long,
    ): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (isLive(instrumentation, activity)) return true
            SystemClock.sleep(LIVE_POLL_MS)
        }
        return false
    }

    private fun isLive(
        instrumentation: android.app.Instrumentation,
        activity: MainActivity,
    ): Boolean {
        var live = false
        instrumentation.runOnMainSync {
            live = activity.findViewById<View>(R.id.liveBadge).visibility == View.VISIBLE
        }
        return live
    }

    private fun readStreamInfo(
        instrumentation: android.app.Instrumentation,
        activity: MainActivity,
    ): String {
        var value = ""
        instrumentation.runOnMainSync {
            value = activity.findViewById<android.widget.TextView>(R.id.streamInfoChip).text.toString()
        }
        return value
    }

    private fun writePreflightEvidence(
        filesDir: File,
        modeSupported: Boolean,
        cameraId: String?,
        rawCameraSupports: Boolean,
        hardwareSupports: Boolean,
        highProfileAvailable: Boolean,
        maxHardwareBitrate: Int?,
        profilePreference: AvcProfilePreference,
        capabilityBitrateMbps: Int,
        streamBitrateMbps: Int,
        durationSeconds: Int,
        receiverHost: String,
        receiverPort: Int,
        latencyMs: Int,
    ) {
        val json = JSONObject()
            .put("manufacturer", Build.MANUFACTURER)
            .put("model", Build.MODEL)
            .put("sdk", Build.VERSION.SDK_INT)
            .put("modeSupported", modeSupported)
            .put("rawCameraSupports4k60", rawCameraSupports)
            .put("hardwareAvcSupports4k60", hardwareSupports)
            .put("cameraId", cameraId ?: JSONObject.NULL)
            .put("lens", CameraLens.Back.name)
            .put("width", TARGET_WIDTH)
            .put("height", TARGET_HEIGHT)
            .put("fps", TARGET_FPS)
            .put("capabilityBitrateMbps", capabilityBitrateMbps)
            .put("streamBitrateMbps", streamBitrateMbps)
            .put("videoBitrateMode", VideoBitrateMode.Cbr.name)
            .put("profilePreference", profilePreference.name)
            .put("highProfileAvailable", highProfileAvailable)
            .put("maxHardwareBitrate", maxHardwareBitrate ?: JSONObject.NULL)
            .put("bFramesRequested", false)
            .put("keyframeIntervalSeconds", TARGET_KEYFRAME_SECONDS)
            .put("audioEnabled", true)
            .put("audioSampleRate", TARGET_AUDIO_SAMPLE_RATE)
            .put("audioChannels", TARGET_AUDIO_CHANNELS)
            .put("audioBitrate", TARGET_AUDIO_BITRATE)
            .put("receiverHost", receiverHost)
            .put("receiverPort", receiverPort)
            .put("latencyMs", latencyMs)
            .put("durationSeconds", durationSeconds)
        File(filesDir, PREFLIGHT_EVIDENCE_FILE).writeText(json.toString(2))
    }

    private fun requireArgument(value: String?, name: String): String {
        assertTrue("Missing instrumentation argument: $name", !value.isNullOrBlank())
        return value!!.trim()
    }

    companion object {
        private const val ARG_RECEIVER_HOST = "receiverHost"
        private const val ARG_RECEIVER_PORT = "receiverPort"
        private const val ARG_DURATION_SECONDS = "durationSeconds"
        private const val ARG_STREAM_BITRATE_MBPS = "streamBitrateMbps"
        private const val ARG_CAPABILITY_BITRATE_MBPS = "capabilityBitrateMbps"
        private const val ARG_LATENCY_MS = "latencyMs"

        private const val TARGET_WIDTH = 3840
        private const val TARGET_HEIGHT = 2160
        private const val TARGET_FPS = 60
        private const val TARGET_KEYFRAME_SECONDS = 2
        private const val TARGET_AUDIO_SAMPLE_RATE = 48_000
        private const val TARGET_AUDIO_CHANNELS = 1
        private const val TARGET_AUDIO_BITRATE = 128_000

        private const val DEFAULT_RECEIVER_PORT = 19001
        private const val DEFAULT_DURATION_SECONDS = 15
        private const val MIN_DURATION_SECONDS = 8
        private const val MAX_DURATION_SECONDS = 120
        private const val DEFAULT_STREAM_BITRATE_MBPS = 8
        private const val DEFAULT_CAPABILITY_BITRATE_MBPS = 35
        private const val DEFAULT_LATENCY_MS = 2_000
        private const val STARTUP_SETTLE_MS = 1_000L
        private const val CONNECT_TIMEOUT_MS = 25_000L
        private const val LIVE_POLL_MS = 500L
        private val FRAME_INFO = Regex("(\\d+) f · (\\d+) kf")

        const val PREFLIGHT_EVIDENCE_FILE = "phase4-device-e2e-preflight.json"
    }
}
