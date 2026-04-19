package com.tarmac

import android.app.Application

class TarmacApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        System.loadLibrary("tarmac-native")
    }
}
