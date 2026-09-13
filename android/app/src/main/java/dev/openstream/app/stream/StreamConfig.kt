package dev.openstream.app.stream

import dev.openstream.app.encoder.CodecPreference

data class StreamConfig(
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrate: Int,
    val keyframeIntervalSeconds: Int,
    val latencyMs: Int,
    val codecPreference: CodecPreference,
    val audioEnabled: Boolean,
    val audioSampleRate: Int,
    val audioChannelCount: Int,
    val audioBitrate: Int,
) {
    val bitrateMbps: Int
        get() = bitrate / 1_000_000

    val audioBitrateKbps: Int
        get() = audioBitrate / 1_000

    companion object {
        const val MIN_BITRATE_MBPS = 8
        const val MAX_BITRATE_MBPS = 50

        // Giữ nguyên cấu hình ổn định của upstream làm mặc định. Các giai đoạn
        // tiếp theo sẽ lọc các lựa chọn khác theo khả năng thật của camera và codec.
        val Default1080p30 = StreamConfig(
            width = 1920,
            height = 1080,
            fps = 30,
            bitrate = 12_000_000,
            keyframeIntervalSeconds = 1,
            latencyMs = 120,
            codecPreference = CodecPreference.ForceAvc,
            audioEnabled = true,
            audioSampleRate = 48_000,
            audioChannelCount = 1,
            audioBitrate = 128_000,
        )

        val Fallback720p30 = StreamConfig(
            width = 1280,
            height = 720,
            fps = 30,
            bitrate = 8_000_000,
            keyframeIntervalSeconds = 1,
            latencyMs = 120,
            codecPreference = CodecPreference.ForceAvc,
            audioEnabled = true,
            audioSampleRate = 48_000,
            audioChannelCount = 1,
            audioBitrate = 128_000,
        )
    }
}
