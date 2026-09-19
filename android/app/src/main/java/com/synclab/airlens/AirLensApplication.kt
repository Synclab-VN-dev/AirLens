package com.synclab.airlens

import android.app.Application
import com.synclab.airlens.stream.StreamConfig
import com.synclab.airlens.stream.StreamConfigStore

class AirLensApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        StreamConfig.installRuntimeConfig(StreamConfigStore.load(this))
    }
}
