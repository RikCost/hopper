package com.aengix.hopper

import android.app.Application
import com.aengix.hopper.data.ProfileStore
import com.aengix.hopper.ssh.HopSecurityProviders

class HopperApp : Application() {
    override fun onCreate() {
        super.onCreate()
        HopSecurityProviders.ensureRegistered()
        ProfileStore.init(this)
    }
}
