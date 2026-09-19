package com.synclab.airlens.stream

import com.synclab.airlens.camera.CameraLens

data class StreamProfile(
    val id: String,
    val name: String,
    val config: StreamConfig,
    val lens: CameraLens?,
    val obsHost: String,
    val obsPort: Int,
    val listeningPort: Int,
)
