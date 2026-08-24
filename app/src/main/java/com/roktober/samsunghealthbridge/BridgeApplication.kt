package com.roktober.samsunghealthbridge

import android.app.Application
import com.roktober.samsunghealthbridge.sync.SyncScheduler

class BridgeApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        SyncScheduler.register(this)
    }
}
