package com.facesort.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class FaceSortApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Scanning", NotificationManager.IMPORTANCE_LOW))
    }
    companion object { const val CHANNEL = "scan" }
}
