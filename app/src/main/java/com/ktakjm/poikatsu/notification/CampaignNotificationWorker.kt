package com.ktakjm.poikatsu.notification

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.ktakjm.poikatsu.MainActivity
import com.ktakjm.poikatsu.R
import com.ktakjm.poikatsu.data.createDataRepository
import com.ktakjm.poikatsu.data.dataDirFor
import com.ktakjm.poikatsu.data.loadMunicipalityMaster
import com.ktakjm.poikatsu.data.SettingsRepository
import com.ktakjm.poikatsu.domain.CampaignNotification
import com.ktakjm.poikatsu.domain.campaignGroupKey
import com.ktakjm.poikatsu.domain.mergeUserData
import com.ktakjm.poikatsu.domain.notificationItemText
import com.ktakjm.poikatsu.domain.notificationItemTitle
import com.ktakjm.poikatsu.domain.notificationLine
import com.ktakjm.poikatsu.domain.notificationTargets
import com.ktakjm.poikatsu.domain.notificationTitle
import com.ktakjm.poikatsu.domain.planCampaignNotifications
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * キャンペーン通知(#6)の日次ジョブ本体。最新の施策データを取得し、ユーザーに関係する施策の
 * 「開始」「終了間近」を判定して、あるときだけ1キャンペーン=1通知で出す(#82。複数件は
 * グループ+サマリで束ねる)。タップでおトクタブの該当キャンペーン詳細カードが開く。
 * 判定・文言は domain/NotificationPlanner.kt(純 Kotlin)、データ取得・設定マージは
 * MainViewModel と同じ経路(DataRepository / mergeUserData)を使い、アプリの表示と基準を揃える。
 */
class CampaignNotificationWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext
        // 開発者向けのテスト実行(CampaignNotifications.runTest)。通知設定 OFF でも実行し、
        // 日次ジョブのスケジュールには触れない。判定・通知そのものは本番と同じ経路を通す
        val isTestRun = inputData.getBoolean(KEY_TEST_RUN, false)
        val settingsRepo = SettingsRepository(app)
        val settings = settingsRepo.settings.first()
        // トグル OFF はジョブごと解除される(CampaignNotifications.cancel)ため通常ここは通らないが、
        // 解除と実行が競合したときの保険として黙って終える
        if (!isTestRun && !settings.notificationsEnabled) return@withContext Result.success()

        // データ取得は MainViewModel と同じ設定(テストデータ・同梱・ref)に追従する
        val dataDir = dataDirFor(settings.useTestData)
        val repository = createDataRepository(app)
        // リモートの最新を優先し、取れなければローカル(キャッシュ→同梱)で判定する。
        // 圏外でも「手元のデータで分かる範囲」を通知する方が、黙って何も出ないより価値がある
        val loaded = runCatching {
            if (settings.useBundledData) {
                repository.loadBundled(dataDir)
            } else {
                repository.refresh(settings.dataCommitRef.ifBlank { "main" }, dataDir)
                    ?: repository.loadLocal(dataDir)
            }
        }.getOrElse { e ->
            Timber.w(e, "通知ジョブ: 施策データの読み込みに失敗")
            // テスト実行は「押した1回」で完結させる(バックオフ後に忘れた頃鳴るのを避ける)
            return@withContext if (isTestRun) Result.failure() else Result.retry()
        }
        // 自治体マスタが読めないときは空のまま=自治体施策は通知されない(誤配より出さない側に倒す)
        val master = loadMunicipalityMaster(app)

        // カタログ+ユーザー設定のマージはアプリ本体(MainViewModel.rebuild)と同じ入口を通す
        val merged = mergeUserData(loaded.data, settings)
        val targets = notificationTargets(
            campaigns = merged.engineData.campaigns,
            ownedCards = merged.engineData.cards,
            enabledQrIds = settings.enabledQrPaymentIds,
            registeredAreas = settings.registeredAreas,
            master = master,
            memberships = settings.pointProgramMemberships,
        )
        val notified = settingsRepo.notifiedCampaignKeys()
        val items = planCampaignNotifications(targets, LocalDate.now())
            .filter { it.dedupKey !in notified }
            .sortedWith(compareBy({ it.kind }, { it.days }))
        if (items.isEmpty()) {
            Timber.d("通知ジョブ: 対象なし(通知済み %d 件を除外後)", notified.size)
        } else {
            // 通知が許可されていなければ通知済みにせず終える(後から許可されたとき、
            // まだ通知ウィンドウ内の施策は翌日のジョブで改めて通知される)
            if (postNotification(app, items)) {
                settingsRepo.addNotifiedCampaignKeys(items.map { it.dedupKey })
                Timber.d("通知ジョブ: %d件を通知", items.size)
            } else {
                Timber.w("通知ジョブ: 通知が許可されていないため見送り(%d件)", items.size)
            }
        }
        // 実行のたびに次回を「翌日の通知時刻」へ再アンカーし、周期実行の後ろズレをリセットする
        // (詳細は CampaignNotifications.schedule)。retry で終える経路では再アンカーしない
        // (バックオフ後の再実行が成功した時点で行う)。テスト実行は日次ジョブに影響させない
        if (!isTestRun) CampaignNotifications.schedule(app, settings.notificationTimeMinutes)
        Result.success()
    }

    companion object {
        /** テスト実行フラグ(入力データ)。true なら通知設定 OFF でも実行し、再アンカーもしない */
        const val KEY_TEST_RUN = "test_run"
    }

    /** 1キャンペーン=1通知で出す(#82。複数件はグループ+サマリで束ねる)。許可が無く出せなかったときは false */
    private fun postNotification(context: Context, items: List<CampaignNotification>): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return false
        CampaignNotifications.ensureChannel(context)
        // 複数件のときは音・バイブをサマリ1回に集約する(各通知が鳴るとN連発になる)。
        // 1件のみはサマリを出さないので、その通知自身が鳴る
        val alertBehavior = if (items.size >= 2) {
            NotificationCompat.GROUP_ALERT_SUMMARY
        } else {
            NotificationCompat.GROUP_ALERT_ALL
        }
        items.forEach { item ->
            // 通知 ID は dedupKey のハッシュ: 同じキャンペーンの再掲は上書き、期間改定は別通知になる
            val id = item.dedupKey.hashCode()
            // タップで対象キャンペーンの詳細カードを開くためのグループキー。requestCode は通知ごとに
            // 変える(PendingIntent は extras の違いを区別しないため、同じ requestCode だと
            // FLAG_UPDATE_CURRENT で extras が上書きされ、全通知が最後のキャンペーンに飛ぶ)
            val intent = Intent(context, MainActivity::class.java)
                .putExtra(CampaignNotifications.EXTRA_CAMPAIGN_GROUP_KEY, campaignGroupKey(item.campaign))
            val pending = PendingIntent.getActivity(
                context, id, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val notification = NotificationCompat.Builder(context, CampaignNotifications.CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_campaign)
                .setContentTitle(notificationItemTitle(item))
                .setContentText(notificationItemText(item))
                .setContentIntent(pending)
                .setAutoCancel(true)
                .setGroup(CampaignNotifications.GROUP_KEY)
                .setGroupAlertBehavior(alertBehavior)
                .build()
            manager.notify(id, notification)
        }
        // 複数件はサマリ通知で束ねる(通知シェードで1グループに折り畳まれる)。タップは
        // おトクタブを開くだけ(extra は空文字)。今回1件のみなら出さない——前日までの
        // 古いサマリが残っていても、新しい通知は同じグループに合流するだけで実害はない
        if (items.size >= 2) {
            val intent = Intent(context, MainActivity::class.java)
                .putExtra(CampaignNotifications.EXTRA_CAMPAIGN_GROUP_KEY, "")
            val pending = PendingIntent.getActivity(
                context, CampaignNotifications.SUMMARY_NOTIFICATION_ID, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val style = NotificationCompat.InboxStyle()
            items.forEach { style.addLine(notificationLine(it)) }
            val summary = NotificationCompat.Builder(context, CampaignNotifications.CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_campaign)
                .setContentTitle(notificationTitle(items))
                .setStyle(style)
                .setContentIntent(pending)
                .setAutoCancel(true)
                .setGroup(CampaignNotifications.GROUP_KEY)
                .setGroupSummary(true)
                .build()
            manager.notify(CampaignNotifications.SUMMARY_NOTIFICATION_ID, summary)
        }
        return true
    }
}
