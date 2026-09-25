package com.tifusi.vpn

import android.app.Application
import com.tifusi.vpn.data.AppSettings

class TifusiVpnApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppSettings.load(this)
    }
}
