package com.synclab.airlens.camera

internal fun normalizeRightAngleDegrees(degrees: Int): Int {
    val normalized = ((degrees % 360) + 360) % 360
    return when (normalized) {
        0, 90, 180, 270 -> normalized
        else -> 0
    }
}

internal fun quantizeDeviceOrientationDegrees(orientationDegrees: Int): Int {
    if (orientationDegrees < 0) return 0
    return (((orientationDegrees + 45) / 90) * 90) % 360
}

/**
 * Maps the physical device orientation plus Camera2 sensor orientation to the
 * clockwise rotation libobs must apply to the decoded async source.
 *
 * This follows the Camera2 preview-rotation contract: front cameras use the
 * opposite sign from back cameras because their sensor coordinate system is
 * mirrored relative to the viewer.
 */
internal fun resolveObsCameraRotation(
    sensorOrientationDegrees: Int,
    deviceOrientationDegrees: Int,
    frontFacing: Boolean,
): Int {
    val sensor = normalizeRightAngleDegrees(sensorOrientationDegrees)
    val device = normalizeRightAngleDegrees(deviceOrientationDegrees)
    val sign = if (frontFacing) 1 else -1
    return normalizeRightAngleDegrees(sensor - device * sign)
}
