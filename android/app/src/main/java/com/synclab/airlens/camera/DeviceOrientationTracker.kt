package com.synclab.airlens.camera

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.OrientationEventListener

/**
 * Converts noisy sensor angles into stable 0/90/180/270 device rotations.
 *
 * The app activity is portrait-locked, so display rotation cannot be used as
 * the physical-orientation source of truth. A short debounce prevents OBS from
 * oscillating when the phone is held close to an orientation boundary.
 */
class DeviceOrientationTracker(
    context: Context,
    private val debounceMs: Long = DEFAULT_DEBOUNCE_MS,
    private val onStableRotationChanged: (Int) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var stableRotation = 0
    private var candidateRotation: Int? = null
    private var pendingCommit: Runnable? = null

    private val listener = object : OrientationEventListener(context.applicationContext) {
        override fun onOrientationChanged(orientation: Int) {
            if (orientation == ORIENTATION_UNKNOWN) return
            val quantized = quantizeDeviceOrientationDegrees(orientation)
            if (quantized == stableRotation) {
                candidateRotation = null
                pendingCommit?.let(handler::removeCallbacks)
                pendingCommit = null
                return
            }
            if (candidateRotation == quantized) return

            candidateRotation = quantized
            pendingCommit?.let(handler::removeCallbacks)
            val commit = Runnable {
                if (candidateRotation != quantized) return@Runnable
                pendingCommit = null
                candidateRotation = null
                if (stableRotation != quantized) {
                    stableRotation = quantized
                    onStableRotationChanged(quantized)
                }
            }
            pendingCommit = commit
            handler.postDelayed(commit, debounceMs)
        }
    }

    fun start() {
        if (listener.canDetectOrientation()) listener.enable()
    }

    fun stop() {
        listener.disable()
        candidateRotation = null
        pendingCommit?.let(handler::removeCallbacks)
        pendingCommit = null
    }

    fun currentRotationDegrees(): Int = stableRotation

    companion object {
        const val DEFAULT_DEBOUNCE_MS = 200L
    }
}
