package com.ktakjm.poikatsu

import android.app.Application
import timber.log.Timber

class PoikatsuApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
    }
}
