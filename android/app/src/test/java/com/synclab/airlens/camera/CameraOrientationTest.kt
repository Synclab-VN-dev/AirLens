package com.synclab.airlens.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraOrientationTest {
    @Test
    fun quantizesPhysicalOrientationToRightAngles() {
        assertEquals(0, quantizeDeviceOrientationDegrees(0))
        assertEquals(0, quantizeDeviceOrientationDegrees(44))
        assertEquals(270, quantizeDeviceOrientationDegrees(45))
        assertEquals(270, quantizeDeviceOrientationDegrees(134))
        assertEquals(180, quantizeDeviceOrientationDegrees(135))
        assertEquals(90, quantizeDeviceOrientationDegrees(225))
        assertEquals(90, quantizeDeviceOrientationDegrees(314))
        assertEquals(0, quantizeDeviceOrientationDegrees(315))
        assertEquals(0, quantizeDeviceOrientationDegrees(359))
    }

    @Test
    fun resolvesBackCameraForSensor90() {
        assertEquals(90, resolveObsCameraRotation(90, 0, frontFacing = false))
        assertEquals(180, resolveObsCameraRotation(90, 90, frontFacing = false))
        assertEquals(270, resolveObsCameraRotation(90, 180, frontFacing = false))
        assertEquals(0, resolveObsCameraRotation(90, 270, frontFacing = false))
    }

    @Test
    fun resolvesFrontCameraForSensor270() {
        assertEquals(270, resolveObsCameraRotation(270, 0, frontFacing = true))
        assertEquals(180, resolveObsCameraRotation(270, 90, frontFacing = true))
        assertEquals(90, resolveObsCameraRotation(270, 180, frontFacing = true))
        assertEquals(0, resolveObsCameraRotation(270, 270, frontFacing = true))
    }

    @Test
    fun invalidAnglesFallBackSafely() {
        assertEquals(0, normalizeRightAngleDegrees(45))
        assertEquals(0, normalizeRightAngleDegrees(-1))
    }
}
