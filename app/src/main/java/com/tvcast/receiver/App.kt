package com.tvcast.receiver

import android.app.Application
import android.content.Context

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        MediaRepo.init(this)
    }

    companion object {
        lateinit var instance: Context
            private set
    }
}
