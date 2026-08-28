package com.ktakjm.poikatsu.data

import android.content.Context
import timber.log.Timber
import java.io.File

// アプリ本体(MainViewModel)と通知ジョブ(CampaignNotificationWorker)が同じ構成でデータ源を組むための
// 小さなファクトリ(#90)。両者で逐語コピーになっていた DataRepository 構築・自治体マスタ読み込みを集約する。

/** 施策データの assets/リモート上のディレクトリ名。「テストデータを使う」ON で data-test/ に切り替わる */
fun dataDirFor(useTestData: Boolean): String = if (useTestData) "data-test" else "data"

/** 自治体マスタの assets パス。リモート取得・キャッシュの対象外で、テストデータ利用時も data/ を読む(#90) */
const val MUNICIPALITIES_ASSET = "data/municipalities.json"

/** assets 直読+filesDir/remote_data キャッシュ+GitHub raw 取得の標準構成で [DataRepository] を作る */
fun createDataRepository(app: Context): DataRepository = DataRepository(
    readAsset = { path -> app.assets.open(path).bufferedReader().use { it.readText() } },
    cacheDir = File(app.filesDir, "remote_data"),
    fetchRemote = { fileName, ref, dataDir -> GithubRawClient.fetch(fileName, ref, dataDir) },
    resolveSha = { ref -> GithubRawClient.resolveCommitSha(ref) },
)

/**
 * 自治体マスタ(assets 同梱)を読む。読めなければ空のマスタ(=地域フィルタ無効・自治体施策は通知しない側に倒す)。
 * ブロッキング I/O なので呼び出し側で IO ディスパッチャに載せる
 */
fun loadMunicipalityMaster(app: Context): MunicipalityMaster = runCatching {
    val text = app.assets.open(MUNICIPALITIES_ASSET).bufferedReader().use { it.readText() }
    PoikatsuJson.parseMunicipalities(text)
}.getOrElse { e ->
    Timber.w(e, "%s の読み込みに失敗", MUNICIPALITIES_ASSET)
    MunicipalityMaster()
}
