package dev.openstream.app

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import dev.openstream.app.stream.ConnectionTarget
import dev.openstream.app.stream.StreamingProfile
import dev.openstream.app.stream.StreamingProfileStore

class ProfileManagerActivity : Activity() {
    private lateinit var profileName: EditText
    private lateinit var profileList: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profiles)

        profileName = findViewById(R.id.profileName)
        profileList = findViewById(R.id.profileList)
        findViewById<Button>(R.id.btnSaveCurrentProfile).setOnClickListener { saveCurrentProfile() }
        findViewById<Button>(R.id.btnBackProfiles).setOnClickListener { finish() }
        renderProfiles()
    }

    private fun saveCurrentProfile() {
        val result = runCatching {
            StreamingProfileStore.saveCurrent(this, profileName.text.toString())
        }
        result.onSuccess { profile ->
            profileName.text.clear()
            Toast.makeText(this, "Đã lưu ${profile.name}", Toast.LENGTH_SHORT).show()
            renderProfiles()
        }.onFailure { error ->
            profileName.error = error.message ?: "Không lưu được cấu hình"
            profileName.requestFocus()
        }
    }

    private fun renderProfiles() {
        profileList.removeAllViews()
        val profiles = StreamingProfileStore.list(this)
        if (profiles.isEmpty()) {
            profileList.addView(TextView(this).apply {
                text = "Chưa có cấu hình đã lưu."
                setTextColor(getColor(R.color.os_text_tertiary))
                textSize = 14f
                setPadding(0, dp(8), 0, dp(16))
            })
            return
        }
        profiles.forEach { profile -> profileList.addView(profileCard(profile)) }
    }

    private fun profileCard(profile: StreamingProfile): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setBackgroundResource(R.drawable.bg_minimal_input)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(12) }

            addView(TextView(this@ProfileManagerActivity).apply {
                text = profile.name
                textSize = 18f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(getColor(R.color.os_text_primary))
            })
            addView(TextView(this@ProfileManagerActivity).apply {
                val target = if (profile.obsHost.isBlank()) {
                    "Chưa đặt máy OBS"
                } else {
                    "${profile.obsHost}:${profile.obsPort}"
                }
                text = buildString {
                    append("${profile.config.width}×${profile.config.height}@${profile.config.fps}")
                    append(" · ${profile.config.bitrateMbps} Mbps")
                    append(" · AAC ${profile.config.audioSampleRate / 1000} kHz")
                    append("\n")
                    append(target)
                    append(" · SRT ${profile.config.latencyMs} ms")
                }
                textSize = 13f
                setTextColor(getColor(R.color.os_text_secondary))
                setPadding(0, dp(6), 0, dp(12))
            })

            addView(LinearLayout(this@ProfileManagerActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END

                addView(actionButton("Áp dụng") { applyProfile(profile, connect = false) })
                if (profile.obsHost.isNotBlank()) {
                    addView(actionButton("Kết nối") { applyProfile(profile, connect = true) })
                }
                addView(actionButton("Xóa") {
                    StreamingProfileStore.delete(this@ProfileManagerActivity, profile.name)
                    Toast.makeText(
                        this@ProfileManagerActivity,
                        "Đã xóa ${profile.name}",
                        Toast.LENGTH_SHORT,
                    ).show()
                    renderProfiles()
                })
            })
        }
    }

    private fun actionButton(label: String, action: () -> Unit): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 13f
            setTextColor(getColor(R.color.os_text_primary))
            setBackgroundResource(R.drawable.bg_minimal_btn_ghost)
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(44),
            ).apply { marginStart = dp(8) }
        }
    }

    private fun applyProfile(profile: StreamingProfile, connect: Boolean) {
        StreamingProfileStore.apply(this, profile)
        val restart = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            if (connect) {
                data = Uri.Builder()
                    .scheme("openstream")
                    .authority("connect")
                    .appendQueryParameter("host", profile.obsHost)
                    .appendQueryParameter("port", profile.obsPort.toString())
                    .appendQueryParameter("latency", profile.config.latencyMs.toString())
                    .appendQueryParameter("bitrateMbps", profile.config.bitrateMbps
                        .coerceIn(
                            dev.openstream.app.stream.StreamConfig.MIN_BITRATE_MBPS,
                            dev.openstream.app.stream.StreamConfig.MAX_BITRATE_MBPS,
                        ).toString())
                    .appendQueryParameter("name", profile.name)
                    .build()
            }
        }
        Toast.makeText(
            this,
            if (connect) "Đang dùng ${profile.name}" else "Đã áp dụng ${profile.name}",
            Toast.LENGTH_SHORT,
        ).show()
        startActivity(restart)
        finish()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
