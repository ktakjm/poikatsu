package com.ktakjm.poikatsu

import android.app.Application
import com.ktakjm.poikatsu.notification.CampaignNotifications
import timber.log.Timber

class PoikatsuApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        // 通知チャンネルは起動時に作っておく(冪等)。ジョブの登録/解除は設定トグル側で行い、
        // 登録済みジョブは WorkManager が再起動をまたいで維持するため、ここでは何もしない
        CampaignNotifications.ensureChannel(this)
    }
}
