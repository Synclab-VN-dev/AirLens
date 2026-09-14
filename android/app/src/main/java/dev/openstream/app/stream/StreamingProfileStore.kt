package dev.openstream.app.stream

import android.content.Context
import dev.openstream.app.SettingsActivity
import dev.openstream.app.encoder.AvcProfilePreference
import dev.openstream.app.encoder.CodecPreference
import dev.openstream.app.encoder.VideoBitrateMode
import org.json.JSONArray
import org.json.JSONObject

data class StreamingProfile(
    val name: String,
    val config: StreamConfig,
    val obsHost: String,
    val obsPort: Int,
    val listeningPort: Int,
    val capabilityLens: String?,
)

/**
 * Bounded persistent profile store for daily-use streaming presets.
 *
 * Profiles deliberately contain only user configuration. Runtime telemetry,
 * discovered OBS instances, reservations and transport state are never persisted.
 */
object StreamingProfileStore {
    private const val KEY_PROFILES = "streaming_profiles_v1"
    const val MAX_PROFILES = 20
    const val MAX_NAME_LENGTH = 48

    fun list(context: Context): List<StreamingProfile> {
        val raw = context.getSharedPreferences(StreamConfigStore.PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_PROFILES, null)
            ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until minOf(array.length(), MAX_PROFILES)) {
                    parseProfile(array.optJSONObject(index) ?: continue)?.let(::add)
                }
            }
        }.getOrDefault(emptyList())
    }

    fun saveCurrent(context: Context, name: String): StreamingProfile {
        val normalizedName = normalizeName(name)
        val prefs = context.getSharedPreferences(StreamConfigStore.PREFS_NAME, Context.MODE_PRIVATE)
        val profile = StreamingProfile(
            name = normalizedName,
            config = StreamConfigStore.load(context),
            obsHost = prefs.getString(SettingsActivity.KEY_OBS_HOST, "")?.trim().orEmpty(),
            obsPort = prefs.getInt(SettingsActivity.KEY_OBS_PORT, ConnectionTarget.DEFAULT_PORT)
                .coerceIn(1, 65535),
            listeningPort = prefs.getInt(SettingsActivity.KEY_LISTENING_PORT, ConnectionTarget.DEFAULT_PORT)
                .coerceIn(1024, 65535),
            capabilityLens = prefs.getString(SettingsActivity.KEY_CAPABILITY_LENS, null),
        )
        upsert(context, profile)
        return profile
    }

    fun upsert(context: Context, profile: StreamingProfile) {
        val normalized = profile.copy(name = normalizeName(profile.name))
        val profiles = list(context).toMutableList()
        val existing = profiles.indexOfFirst { it.name.equals(normalized.name, ignoreCase = true) }
        if (existing >= 0) {
            profiles[existing] = normalized
        } else {
            require(profiles.size < MAX_PROFILES) { "Chỉ lưu tối đa $MAX_PROFILES cấu hình" }
            profiles += normalized
        }
        persist(context, profiles)
    }

    fun delete(context: Context, name: String): Boolean {
        val profiles = list(context).toMutableList()
        val removed = profiles.removeAll { it.name.equals(name, ignoreCase = true) }
        if (removed) persist(context, profiles)
        return removed
    }

    fun apply(context: Context, profile: StreamingProfile) {
        StreamConfigStore.save(context, profile.config)
        StreamConfig.installRuntimeConfig(profile.config)
        context.getSharedPreferences(StreamConfigStore.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(SettingsActivity.KEY_OBS_HOST, profile.obsHost)
            .putInt(SettingsActivity.KEY_OBS_PORT, profile.obsPort.coerceIn(1, 65535))
            .putInt(SettingsActivity.KEY_LISTENING_PORT, profile.listeningPort.coerceIn(1024, 65535))
            .apply {
                if (profile.capabilityLens.isNullOrBlank()) {
                    remove(SettingsActivity.KEY_CAPABILITY_LENS)
                } else {
                    putString(SettingsActivity.KEY_CAPABILITY_LENS, profile.capabilityLens)
                }
            }
            .apply()
    }

    private fun persist(context: Context, profiles: List<StreamingProfile>) {
        val array = JSONArray()
        profiles.take(MAX_PROFILES).forEach { array.put(toJson(it)) }
        context.getSharedPreferences(StreamConfigStore.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PROFILES, array.toString())
            .apply()
    }

    private fun toJson(profile: StreamingProfile): JSONObject {
        val config = profile.config
        return JSONObject()
            .put("name", profile.name)
            .put("obsHost", profile.obsHost)
            .put("obsPort", profile.obsPort)
            .put("listeningPort", profile.listeningPort)
            .put("capabilityLens", profile.capabilityLens)
            .put("width", config.width)
            .put("height", config.height)
            .put("fps", config.fps)
            .put("bitrate", config.bitrate)
            .put("keyframeIntervalSeconds", config.keyframeIntervalSeconds)
            .put("latencyMs", config.latencyMs)
            .put("codecPreference", config.codecPreference.name)
            .put("videoBitrateMode", config.videoBitrateMode.name)
            .put("avcProfilePreference", config.avcProfilePreference.name)
            .put("bFramesEnabled", config.bFramesEnabled)
            .put("audioEnabled", config.audioEnabled)
            .put("audioSampleRate", config.audioSampleRate)
            .put("audioChannelCount", config.audioChannelCount)
            .put("audioBitrate", config.audioBitrate)
    }

    private fun parseProfile(json: JSONObject): StreamingProfile? = runCatching {
        val defaults = StreamConfig.Baseline1080p30
        val name = normalizeName(json.getString("name"))
        StreamingProfile(
            name = name,
            obsHost = json.optString("obsHost", "").trim(),
            obsPort = json.optInt("obsPort", ConnectionTarget.DEFAULT_PORT).coerceIn(1, 65535),
            listeningPort = json.optInt("listeningPort", ConnectionTarget.DEFAULT_PORT)
                .coerceIn(1024, 65535),
            capabilityLens = json.optString("capabilityLens", "").takeIf { it.isNotBlank() },
            config = defaults.copy(
                width = json.optInt("width", defaults.width).coerceIn(StreamConfigStore.MIN_WIDTH, StreamConfigStore.MAX_WIDTH),
                height = json.optInt("height", defaults.height).coerceIn(StreamConfigStore.MIN_HEIGHT, StreamConfigStore.MAX_HEIGHT),
                fps = json.optInt("fps", defaults.fps).coerceIn(StreamConfigStore.MIN_FPS, StreamConfigStore.MAX_FPS),
                bitrate = json.optInt("bitrate", defaults.bitrate).coerceIn(
                    StreamConfig.MIN_CONFIGURABLE_BITRATE_MBPS * 1_000_000,
                    StreamConfig.MAX_CONFIGURABLE_BITRATE_MBPS * 1_000_000,
                ),
                keyframeIntervalSeconds = json.optInt("keyframeIntervalSeconds", defaults.keyframeIntervalSeconds)
                    .coerceIn(StreamConfigStore.MIN_KEYFRAME_INTERVAL, StreamConfigStore.MAX_KEYFRAME_INTERVAL),
                latencyMs = json.optInt("latencyMs", defaults.latencyMs)
                    .coerceIn(StreamConfigStore.MIN_LATENCY_MS, StreamConfigStore.MAX_LATENCY_MS),
                codecPreference = enumOrDefault(json.optString("codecPreference"), defaults.codecPreference),
                videoBitrateMode = enumOrDefault(json.optString("videoBitrateMode"), defaults.videoBitrateMode),
                avcProfilePreference = enumOrDefault(json.optString("avcProfilePreference"), defaults.avcProfilePreference),
                bFramesEnabled = json.optBoolean("bFramesEnabled", defaults.bFramesEnabled),
                audioEnabled = json.optBoolean("audioEnabled", defaults.audioEnabled),
                audioSampleRate = json.optInt("audioSampleRate", defaults.audioSampleRate)
                    .coerceIn(StreamConfigStore.MIN_AUDIO_SAMPLE_RATE, StreamConfigStore.MAX_AUDIO_SAMPLE_RATE),
                audioChannelCount = json.optInt("audioChannelCount", defaults.audioChannelCount)
                    .coerceIn(StreamConfigStore.MIN_AUDIO_CHANNELS, StreamConfigStore.MAX_AUDIO_CHANNELS),
                audioBitrate = json.optInt("audioBitrate", defaults.audioBitrate).coerceIn(
                    StreamConfigStore.MIN_AUDIO_BITRATE_KBPS * 1_000,
                    StreamConfigStore.MAX_AUDIO_BITRATE_KBPS * 1_000,
                ),
            ),
        )
    }.getOrNull()

    private fun normalizeName(name: String): String {
        val normalized = name.trim().replace(Regex("\\s+"), " ")
        require(normalized.isNotBlank()) { "Tên cấu hình không được để trống" }
        require(normalized.length <= MAX_NAME_LENGTH) { "Tên cấu hình tối đa $MAX_NAME_LENGTH ký tự" }
        return normalized
    }

    private inline fun <reified T : Enum<T>> enumOrDefault(raw: String, fallback: T): T {
        return runCatching { enumValueOf<T>(raw) }.getOrDefault(fallback)
    }
}
