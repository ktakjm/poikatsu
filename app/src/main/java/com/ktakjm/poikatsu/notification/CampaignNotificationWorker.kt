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
import com.ktakjm.poikatsu.data.DataRepository
import com.ktakjm.poikatsu.data.GithubRawClient
import com.ktakjm.poikatsu.data.MunicipalityMaster
import com.ktakjm.poikatsu.data.PoikatsuJson
import com.ktakjm.poikatsu.data.SettingsRepository
import com.ktakjm.poikatsu.domain.CampaignNotification
import com.ktakjm.poikatsu.domain.mergeUserData
import com.ktakjm.poikatsu.domain.notificationLine
import com.ktakjm.poikatsu.domain.notificationTargets
import com.ktakjm.poikatsu.domain.notificationTitle
import com.ktakjm.poikatsu.domain.planCampaignNotifications
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * キャンペーン通知(#6)の日次ジョブ本体。最新の施策データを取得し、ユーザーに関係する施策の
 * 「開始」「終了間近」を判定して、あるときだけ1件のまとめ通知を出す。
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
        val dataDir = if (settings.useTestData) "data-test" else "data"
        val repository = DataRepository(
            readAsset = { path -> app.assets.open(path).bufferedReader().use { it.readText() } },
            cacheDir = File(app.filesDir, "remote_data"),
            fetchRemote = { fileName, ref, dir -> GithubRawClient.fetch(fileName, ref, dir) },
            resolveSha = { ref -> GithubRawClient.resolveCommitSha(ref) },
        )
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
        val master = runCatching {
            val text = app.assets.open("$dataDir/municipalities.json").bufferedReader().use { it.readText() }
            PoikatsuJson.parseMunicipalities(text)
        }.getOrElse { e ->
            Timber.w(e, "通知ジョブ: 自治体マスタの読み込みに失敗")
            MunicipalityMaster()
        }

        val merged = mergeUserData(
            base = loaded.data,
            cardOverrides = settings.cardOverrides,
            ownedBrands = settings.ownedBrands,
            customCards = settings.customCards,
            customCampaigns = settings.activeCustomCampaigns,
        )
        val targets = notificationTargets(
            campaigns = merged.engineData.campaigns,
            ownedCards = merged.engineData.cards,
            enabledQrIds = settings.enabledQrPaymentIds,
            registeredAreas = settings.registeredAreas,
            master = master,
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

    /** まとめ通知を1件出す。許可が無く出せなかったときは false */
    private fun postNotification(context: Context, items: List<CampaignNotification>): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        CampaignNotifications.ensureChannel(context)
        val intent = Intent(context, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val lines = items.map { notificationLine(it) }
        val notification = NotificationCompat.Builder(context, CampaignNotifications.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_campaign)
            .setContentTitle(notificationTitle(items))
            .setContentText(lines.joinToString(" / "))
            .setStyle(NotificationCompat.BigTextStyle().bigText(lines.joinToString("\n")))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(CampaignNotifications.NOTIFICATION_ID, notification)
        return true
    }
}
