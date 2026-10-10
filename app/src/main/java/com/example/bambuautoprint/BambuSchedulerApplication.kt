package com.example.bambuautoprint

import android.app.Application
import com.example.bambuautoprint.automation.PrintNotifications

class BambuSchedulerApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        PrintNotifications.createChannels(this)
    }
}
