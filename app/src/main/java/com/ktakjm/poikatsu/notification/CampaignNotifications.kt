package com.ktakjm.poikatsu.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.ktakjm.poikatsu.domain.delayUntilNextNotifyTime
import java.time.Duration
import java.time.LocalDateTime

/**
 * キャンペーン通知(#6)の日次ジョブ管理と通知チャンネル。
 *
 * 方式はローカル通知: サーバー(FCM)は使わず、端末内の WorkManager が毎朝の通知時刻
 * (設定値。既定 8:00)ごろに [CampaignNotificationWorker] を起動して最新データを取得・判定し、
 * 通知すべき施策があるときだけ1件のまとめ通知を出す。登録エリア・所有カード等の個人化情報は
 * DataStore(端末内)にしかないため、この判定は端末側でしか行えない(issue #6 の調査コメント参照)。
 */
object CampaignNotifications {

    const val CHANNEL_ID = "campaign_alerts"

    /** まとめ通知1件を上書き更新するための固定 ID */
    const val NOTIFICATION_ID = 1

    private const val WORK_NAME = "campaign_notification"

    /**
     * 日次ジョブを登録し、次回実行を「次に通知時刻を迎える時点」へ固定する
     * (setNextScheduleTimeOverride)。素の周期実行は「前回実行の約24時間後」なので後ろズレが
     * 蓄積するが、Worker が実行のたびに本メソッドで翌日の通知時刻へ**再アンカー**することで
     * ズレを毎日リセットする(残るのは当日の WorkManager の起動遅延のみ。通常は数分以内)。
     * override は次の1回にだけ効き、再アンカーが何かの理由で漏れても 24 時間周期のジョブ
     * として発火し続ける(次に Worker が走った時点で補正される)。
     * 登録は WorkManager が永続化するため、再起動後の再登録は不要(Application では何もしない)。
     * ジョブ実行中の呼び出しにも安全: UPDATE ポリシーは実行中の Worker をキャンセルせず、
     * 新しい指定は次回実行から効く。
     */
    fun schedule(context: Context, notifyTimeMinutes: Int) {
        val delay = delayUntilNextNotifyTime(LocalDateTime.now(), notifyTimeMinutes)
        val request = PeriodicWorkRequestBuilder<CampaignNotificationWorker>(Duration.ofDays(1))
            .setNextScheduleTimeOverride(System.currentTimeMillis() + delay.toMillis())
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    /** 日次ジョブを解除する(通知設定 OFF 時) */
    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    /** 通知チャンネルを作成する(冪等)。Application 起動時と通知直前に呼ぶ */
    fun ensureChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "キャンペーン通知",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "キャンペーンの開始・終了間近のお知らせ"
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
