package com.synclab.airlens

import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.synclab.airlens.stream.ConnectionTarget
import com.synclab.airlens.stream.StreamConfig
import com.synclab.airlens.stream.StreamConfigStore
import com.synclab.airlens.stream.StreamProfileStore
import com.synclab.airlens.stream.StreamingCapabilityResolver
import com.synclab.airlens.ui.PillToggle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Phase7SettingsE2eTest {

    private fun launchSettings(host: String): Pair<SettingsActivity, Context> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val base = StreamConfig.Baseline1080p30
        StreamConfigStore.save(context, base)
        StreamConfig.installRuntimeConfig(base)
        StreamProfileStore.clear(context)

        val baselineModes = StreamingCapabilityResolver(context).resolve(base)
        assertTrue("Device must expose at least one Camera2 + hardware AVC mode", baselineModes.isNotEmpty())
        val lens = baselineModes.first().lens
        context.getSharedPreferences(SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(SettingsActivity.KEY_CAPABILITY_LENS, lens.name)
            .putString(SettingsActivity.KEY_OBS_HOST, host)
            .putInt(SettingsActivity.KEY_OBS_PORT, ConnectionTarget.DEFAULT_PORT)
            .putInt(SettingsActivity.KEY_LISTENING_PORT, ConnectionTarget.DEFAULT_PORT)
            .apply()

        val activity = instrumentation.startActivitySync(
            Intent(context, SettingsActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            },
        ) as SettingsActivity
        instrumentation.waitForIdleSync()
        return activity to context
    }

    private fun sheetRowByTag(activity: SettingsActivity, tag: String): View? {
        val content = activity.findViewById<LinearLayout>(R.id.sheetContent)
        return (0 until content.childCount)
            .map(content::getChildAt)
            .firstOrNull { it.tag == tag }
    }

    @Test
    fun currentConfigCanBeSavedWithoutCreatingProfile() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val (activity, context) = launchSettings(host = "")
        val base = StreamConfig.Baseline1080p30

        val saveSettings = activity.findViewById<TextView>(R.id.btnSaveSettings)
        val profileEmpty = activity.findViewById<View>(R.id.settingsProfileEmpty)

        instrumentation.runOnMainSync {
            assertTrue("Saving the current config must not require a profile", saveSettings.isEnabled)
            assertEquals(
                "Empty profile state must be shown when no profile exists",
                View.VISIBLE,
                profileEmpty.visibility,
            )
            saveSettings.performClick()
        }
        instrumentation.waitForIdleSync()

        assertTrue("Saving current config must not create a profile", StreamProfileStore.list(context).isEmpty())
        assertEquals(base.width, StreamConfigStore.load(context).width)
        assertEquals(base.height, StreamConfigStore.load(context).height)
        assertEquals(base.fps, StreamConfigStore.load(context).fps)
        assertFalse("Save must keep the settings screen open", activity.isFinishing)

        instrumentation.runOnMainSync { activity.finish() }
        StreamProfileStore.clear(context)
    }

    @Test
    fun profileCrudSheetPairingAndValidationWorkOnRealDevice() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val (activity, context) = launchSettings(host = "100.64.0.10")
        val resolver = StreamingCapabilityResolver(context)

        val audioToggle = activity.findViewById<PillToggle>(R.id.settingsAudioEnabled)
        val rowAudioDetail = activity.findViewById<View>(R.id.rowAudioDetail)
        val rowResolution = activity.findViewById<View>(R.id.rowResolution)
        val rowFrameRate = activity.findViewById<View>(R.id.rowFrameRate)
        val blockedText = activity.findViewById<TextView>(R.id.settingsBlockedText)
        val saveSettings = activity.findViewById<TextView>(R.id.btnSaveSettings)
        val saveAndConnect = activity.findViewById<View>(R.id.btnSaveAndConnect)

        instrumentation.runOnMainSync {
            assertNotNull(activity.findViewById<View>(R.id.settingsProfileCard))
            assertNotNull(audioToggle)
            assertNotNull(activity.findViewById<View>(R.id.settingsWidth))
            assertNotNull(activity.findViewById<View>(R.id.settingsHeight))
            assertNotNull(activity.findViewById<View>(R.id.settingsFps))
        }

        // --- Profile CRUD through the same code paths the sheet UI uses ---
        instrumentation.runOnMainSync {
            assertTrue(activity.createProfileNamed("Studio A"))
        }
        instrumentation.waitForIdleSync()
        var stored = StreamProfileStore.list(context)
        assertEquals(1, stored.size)
        assertEquals("Studio A", stored.single().name)
        assertEquals("Studio A", StreamProfileStore.active(context)?.name)

        // Saving under the same name updates instead of duplicating.
        instrumentation.runOnMainSync {
            activity.setHostForTest("100.64.0.20")
            assertTrue(activity.createProfileNamed("Studio A"))
        }
        instrumentation.waitForIdleSync()
        stored = StreamProfileStore.list(context)
        assertEquals(1, stored.size)
        assertEquals("100.64.0.20", stored.single().obsHost)

        instrumentation.runOnMainSync {
            activity.setHostForTest("100.64.0.30")
            assertTrue(activity.createProfileNamed("Studio B"))
        }
        instrumentation.waitForIdleSync()
        stored = StreamProfileStore.list(context)
        assertEquals(listOf("Studio A", "Studio B"), stored.map { it.name })

        // Using a profile restores its endpoint settings.
        val idA = stored.first { it.name == "Studio A" }.id
        instrumentation.runOnMainSync { assertTrue(activity.useProfileById(idA)) }
        instrumentation.waitForIdleSync()
        assertEquals("Studio A", StreamProfileStore.active(context)?.name)
        assertEquals(
            "100.64.0.20",
            context.getSharedPreferences(SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE)
                .getString(SettingsActivity.KEY_OBS_HOST, null),
        )

        // Invalid profile names are rejected before persistence.
        instrumentation.runOnMainSync { assertFalse(activity.createProfileNamed("   ")) }
        assertEquals(2, StreamProfileStore.list(context).size)

        // Delete keeps the remaining profile.
        val idB = StreamProfileStore.list(context).first { it.name == "Studio B" }.id
        instrumentation.runOnMainSync { assertTrue(activity.deleteProfileById(idB)) }
        instrumentation.waitForIdleSync()
        assertEquals(listOf("Studio A"), StreamProfileStore.list(context).map { it.name })

        // --- Resolution sheet exposes the resolver's supported/unsupported truth ---
        val state = activity.currentUiStateForTest()
        val lensName = state["lens"] as String
        val currentFps = state["fps"] as Int
        val probeModes = resolver.resolve(8_000_000).filter { it.lens.name == lensName }
        assertTrue("Lens must expose at least one verified mode", probeModes.isNotEmpty())

        instrumentation.runOnMainSync { rowResolution.performClick() }
        instrumentation.waitForIdleSync()
        probeModes.map { it.width to it.height }.distinct().forEach { (w, h) ->
            val row = sheetRowByTag(activity, "res:${w}x${h}")
            assertNotNull("Sheet must list every distinct resolution the lens reports", row)
            val supported = probeModes.any { it.width == w && it.height == h && it.fps == currentFps }
            // Locale-independent check: unsupported rows carry the "✕" mark.
            val mark = row!!.findViewById<TextView>(R.id.sheetRowMark).text.toString()
            assertEquals(
                "Row ${w}x$h must expose the real pairing truth at $currentFps FPS",
                !supported,
                mark == "✕",
            )
        }

        // Picking a supported resolution applies W/H and the recommended bitrate default.
        val supportedPair = probeModes.filter { it.fps == currentFps }
            .map { it.width to it.height }
            .distinct()
            .firstOrNull()
        if (supportedPair != null) {
            val (w, h) = supportedPair
            instrumentation.runOnMainSync {
                sheetRowByTag(activity, "res:${w}x${h}")!!.performClick()
            }
            instrumentation.waitForIdleSync()
            val after = activity.currentUiStateForTest()
            assertEquals(w, after["width"])
            assertEquals(h, after["height"])
            assertEquals(false, after["customSize"])
        }

        // --- Frame rate sheet lists every fps the lens reports ---
        instrumentation.runOnMainSync { rowFrameRate.performClick() }
        instrumentation.waitForIdleSync()
        probeModes.map { it.fps }.distinct().forEach { fps ->
            assertNotNull("Sheet must list $fps FPS", sheetRowByTag(activity, "fps:$fps"))
        }

        // Canonical fps values are never silently hidden: 60 FPS must be listed even
        // when the camera doesn't report it, greyed with an explanation, and tapping
        // it must not change the selected fps.
        val row60 = sheetRowByTag(activity, "fps:60")
        assertNotNull("60 FPS must always be listed (greyed when unsupported)", row60)
        if (probeModes.none { it.fps == 60 }) {
            val note60 = row60!!.findViewById<TextView>(R.id.sheetRowNote).text.toString()
            assertTrue("Unsupported 60 FPS row must carry a reason", note60.isNotBlank())
            val fpsBefore = activity.currentUiStateForTest()["fps"]
            instrumentation.runOnMainSync { row60.performClick() }
            instrumentation.waitForIdleSync()
            assertEquals(
                "Tapping unsupported 60 FPS must not change the selection",
                fpsBefore,
                activity.currentUiStateForTest()["fps"],
            )
        }
        instrumentation.runOnMainSync { activity.onBackPressed() } // close sheet
        instrumentation.waitForIdleSync()

        // --- Audio toggle drives the detail row visibility ---
        val audioBefore = activity.currentUiStateForTest()["audioEnabled"] as Boolean
        instrumentation.runOnMainSync { audioToggle.performClick() }
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync {
            assertEquals(!audioBefore, activity.currentUiStateForTest()["audioEnabled"])
            assertEquals(
                if (audioBefore) View.GONE else View.VISIBLE,
                rowAudioDetail.visibility,
            )
            audioToggle.performClick()
        }
        instrumentation.waitForIdleSync()

        // --- Invalid host blocks Save & Connect but never Save ---
        instrumentation.runOnMainSync { activity.setHostForTest("192.168.1.5.5") }
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync {
            assertFalse("Invalid host must disable Save & Connect", saveAndConnect.isEnabled)
            assertEquals(View.VISIBLE, blockedText.visibility)
            assertTrue("Save must stay available so the user can fix config", saveSettings.isEnabled)
        }

        // --- Reset restores defaults in the UI without persisting ---
        val persistedHost = context.getSharedPreferences(SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE)
            .getString(SettingsActivity.KEY_OBS_HOST, null)
        instrumentation.runOnMainSync { activity.resetToDefaults() }
        instrumentation.waitForIdleSync()
        val reset = activity.currentUiStateForTest()
        assertEquals(StreamConfig.Baseline1080p30.bitrateMbps, reset["bitrateMbps"])
        assertEquals("", reset["host"])
        assertEquals(
            "Reset must not persist anything until Save",
            persistedHost,
            context.getSharedPreferences(SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE)
                .getString(SettingsActivity.KEY_OBS_HOST, null),
        )

        instrumentation.runOnMainSync { activity.finish() }
        StreamProfileStore.clear(context)
    }
}
