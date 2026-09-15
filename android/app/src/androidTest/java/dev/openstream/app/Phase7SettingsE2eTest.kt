package dev.openstream.app

import android.content.Context
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.openstream.app.stream.ConnectionTarget
import dev.openstream.app.stream.StreamConfig
import dev.openstream.app.stream.StreamConfigStore
import dev.openstream.app.stream.StreamPreset
import dev.openstream.app.stream.StreamProfile
import dev.openstream.app.stream.StreamProfileStore
import dev.openstream.app.stream.StreamingCapabilityResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Phase7SettingsE2eTest {

    @Test
    fun profilesPresetsAndCapabilityReasonsAreUsableOnRealDevice() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val base = StreamConfig.Baseline1080p30
        StreamConfigStore.save(context, base)
        StreamConfig.installRuntimeConfig(base)

        val resolver = StreamingCapabilityResolver(context)
        val baselineModes = resolver.resolve(base)
        assertTrue("Device must expose at least one Camera2 + hardware AVC mode", baselineModes.isNotEmpty())
        val lens = baselineModes.first().lens
        context.getSharedPreferences(SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(SettingsActivity.KEY_CAPABILITY_LENS, lens.name)
            .putString(SettingsActivity.KEY_OBS_HOST, "100.64.0.10")
            .putInt(SettingsActivity.KEY_OBS_PORT, ConnectionTarget.DEFAULT_PORT)
            .putInt(SettingsActivity.KEY_LISTENING_PORT, ConnectionTarget.DEFAULT_PORT)
            .apply()

        StreamProfileStore.clear(context)
        val profileA = StreamProfile(
            id = "phase7-a",
            name = "Studio A",
            config = base,
            lens = lens,
            obsHost = "100.64.0.10",
            obsPort = 9000,
            listeningPort = 9000,
        )
        val profileB = profileA.copy(
            id = "phase7-b",
            name = "Studio B",
            config = StreamPreset.FullHd60.applyTo(base),
            obsHost = "100.64.0.11",
            obsPort = 9100,
        )
        StreamProfileStore.save(context, profileA, makeActive = false)
        StreamProfileStore.save(context, profileB, makeActive = true)
        assertEquals(2, StreamProfileStore.list(context).size)
        assertEquals("phase7-b", StreamProfileStore.active(context)?.id)

        val activity = instrumentation.startActivitySync(
            Intent(context, SettingsActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            },
        ) as SettingsActivity
        instrumentation.waitForIdleSync()

        instrumentation.runOnMainSync {
            assertNotNull(activity.findViewById<android.view.View>(R.id.settingsProfile))
            assertNotNull(activity.findViewById<android.view.View>(R.id.settingsPreset))
            assertNotNull(activity.findViewById<android.view.View>(R.id.settingsCapabilityLens))
            assertNotNull(activity.findViewById<android.view.View>(R.id.settingsCapabilityMode))
            assertNotNull(activity.findViewById<android.view.View>(R.id.settingsAudioEnabled))
            assertNotNull(activity.findViewById<android.view.View>(R.id.settingsObsHost))

            val profileSpinner = activity.findViewById<android.widget.Spinner>(R.id.settingsProfile)
            val presetSpinner = activity.findViewById<android.widget.Spinner>(R.id.settingsPreset)
            assertEquals(2, profileSpinner.count)
            assertEquals(StreamPreset.entries.size, presetSpinner.count)

            val labels = (0 until presetSpinner.count).map { index ->
                presetSpinner.getItemAtPosition(index).toString()
            }
            StreamPreset.entries.forEachIndexed { index, preset ->
                val candidate = preset.applyTo(base)
                val supported = resolver.resolve(candidate).any { mode ->
                    mode.lens == lens &&
                        mode.width == preset.width &&
                        mode.height == preset.height &&
                        mode.fps == preset.fps
                }
                assertEquals(
                    "Preset label must expose real unsupported state for ${preset.displayName}",
                    !supported,
                    labels[index].contains("Không hỗ trợ"),
                )
            }
        }

        instrumentation.runOnMainSync { activity.finish() }
        StreamProfileStore.delete(context, profileA.id)
        assertEquals(1, StreamProfileStore.list(context).size)
        StreamProfileStore.clear(context)
    }
}
