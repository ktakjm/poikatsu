package com.ktakjm.poikatsu.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.ktakjm.poikatsu.domain.delayUntilNextNotifyTime
import java.time.Duration
import java.time.LocalDateTime
import kotlinx.coroutines.flow.first

/**
 * キャンペーン通知(#6)の日次ジョブ管理と通知チャンネル。
 *
 * 方式はローカル通知: サーバー(FCM)は使わず、端末内の WorkManager が毎日の通知時刻
 * (設定値。既定 8:00)ごろに [CampaignNotificationWorker] を起動して最新データを取得・判定し、
 * 通知すべき施策があるときだけ1件のまとめ通知を出す。登録エリア・所有カード等の個人化情報は
 * DataStore(端末内)にしかないため、この判定は端末側でしか行えない(issue #6 の調査コメント参照)。
 */
object CampaignNotifications {

    /**
     * 通知チャンネルの ID。
     *
     * チャンネルは**作成後の初期設定(重要度・バイブ等)をアプリから変更できない**(端末設定で
     * ユーザーが変えた値をアプリが勝手に戻せないようにする Android の制約。同じ id での
     * 削除→再作成でも旧設定が復活する)。アプリの上書きインストール(installDebug)でも
     * チャンネルは残るため、**一般公開後に初期設定を変えるときは id にサフィックスを付けて
     * 別チャンネルにする**(旧 id は deleteNotificationChannel で掃除する)。
     * 公開前の現在は、初期設定を変えたらアンインストール→再インストールで作り直せばよいので
     * 素の id のままにしている。
     */
    const val CHANNEL_ID = "campaign_alerts"

    /** まとめ通知1件を上書き更新するための固定 ID */
    const val NOTIFICATION_ID = 1

    private const val WORK_NAME = "campaign_notification"

    /** テスト実行(開発者向け)。日次ジョブとは別の unique work にして本番のスケジュールに触れない */
    private const val TEST_WORK_NAME = "campaign_notification_test"

    /**
     * 日次ジョブを登録し、次回実行を「次に通知時刻を迎える時点」へ固定する
     * (setNextScheduleTimeOverride)。素の周期実行は「前回実行の約24時間後」なので後ろズレが
     * 蓄積するが、Worker が実行のたびに本メソッドで翌日の通知時刻へ**再アンカー**することで
     * ズレを毎日リセットする(残るのは当日の WorkManager の起動遅延のみ。通常は数分以内)。
     * override は次の1回にだけ効き、再アンカーが何かの理由で漏れても 24 時間周期のジョブ
     * として発火し続ける(次に Worker が走った時点で補正される)。
     * 登録は WorkManager が永続化するため、端末再起動後の再登録は不要。ただし**再インストール+
     * データ復元では復元されない**(下記 [ensureScheduled] 参照)。
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

    /**
     * 設定が ON なのにジョブが無ければ登録する(起動時の埋め合わせ)。
     *
     * WorkManager のジョブは自前の Room DB(`androidx.work.workdb`)に載っていて、その置き場所は
     * **`no_backup` ディレクトリ**(`WorkDatabasePathHelper` が `getNoBackupFilesDir()` 配下に置く)。
     * つまり Auto Backup の対象外で、機種変更・再インストールでは復元されない。一方 DataStore の
     * 設定(`notificationsEnabled`)は復元されるため、放っておくと**設定はオンなのにジョブが無い
     * =通知が来ない**状態になる(2026-07-27 の復元テストで実際に発生)。起動時にここで突き合わせる。
     *
     * 既に登録済みなら何もしない。無条件に [schedule] を呼ぶと、通知時刻を過ぎて実行待ちのジョブが
     * あるときに翌日へ再アンカーされ、その日の通知を取りこぼすため。
     */
    suspend fun ensureScheduled(context: Context, notifyTimeMinutes: Int) {
        val existing = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWorkFlow(WORK_NAME)
            .first()
        if (existing.none { !it.state.isFinished }) schedule(context, notifyTimeMinutes)
    }

    /** 日次ジョブを解除する(通知設定 OFF 時) */
    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    /**
     * 通知ジョブを今すぐ(または [delaySeconds] 秒後に)1回だけ走らせる(開発者向けのテスト実行)。
     * 日次ジョブの発火を待たずに本番と同じ判定・通知経路を確認するためのもので、通知設定 OFF でも
     * 実行し、日次ジョブのスケジュールには触れない([CampaignNotificationWorker] の testRun 分岐)。
     * 遅延ありは「画面を消してから鳴らす」検証用: WorkManager の遅延なので厳密な秒数ではない。
     */
    fun runTest(context: Context, delaySeconds: Long) {
        val request = OneTimeWorkRequestBuilder<CampaignNotificationWorker>()
            .setInputData(workDataOf(CampaignNotificationWorker.KEY_TEST_RUN to true))
            .apply { if (delaySeconds > 0) setInitialDelay(Duration.ofSeconds(delaySeconds)) }
            .build()
        // REPLACE: 連打しても最後の1回だけが残る(古い予約が後から鳴らない)
        WorkManager.getInstance(context)
            .enqueueUniqueWork(TEST_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    /**
     * 通知チャンネルを作成する(冪等)。Application 起動時と通知直前に呼ぶ。
     * ここで指定する値は**作成時の初期値**で、以後はユーザーが端末設定で変えたものが優先される
     * (アプリからは上書きできない。[CHANNEL_ID] 参照)。
     */
    fun ensureChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "キャンペーン通知",
            // HIGH は画面 ON のときヘッドアップ(画面上部のポップアップ)まで出す重要度。
            // DEFAULT だと音・バイブは鳴るがポップアップしない。1日1回・該当がある日だけの
            // 通知なので、その日の行動に間に合うよう気付ける HIGH を初期値にする
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "キャンペーンの開始・終了間近のお知らせ"
            // 朝の通知に気付けるよう既定でバイブさせる(パターンは端末既定のまま)
            enableVibration(true)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
