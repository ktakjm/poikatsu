package com.ktakjm.poikatsu.data

import java.io.File

enum class DataSource { REMOTE, CACHE, BUNDLED }

data class LoadedData(
    val data: PoikatsuData,
    val source: DataSource,
    /**
     * このデータのフル commit SHA(開発者向け設定の表示用)。リモート取得時に ref から解決し、
     * キャッシュにも添えて保存する。解決失敗・同梱データ・SHA 保存前の旧キャッシュでは null。
     */
    val commitSha: String? = null,
)

/**
 * 施策データ・決済手段カタログの取得戦略:
 * 1. 起動時は loadLocal() — 前回リモート取得のキャッシュ、なければ同梱 assets から即時ロード
 * 2. 裏で refresh() — リモート(GitHub raw)から取得し、パースに成功した場合のみキャッシュ保存
 * 3. 開発者向け「同梱データを使う」ON 中は loadBundled() — キャッシュをバイパスして assets を直読
 *    (push せずにローカル編集した JSON を実機検証するためのモード。呼び出し側で refresh を抑止する)
 *
 * merchants/campaigns/payment_methods の 3 ファイルすべてがリモート更新の対象
 * (ユーザー固有値はカタログでなく DataStore 差分に持つため、カタログはデータ扱いでよい)。
 * dataDir("data" / "data-test")はリモート・assets とも同じディレクトリ構造で切り替わる。
 * Android 非依存(関数とFileを注入)にしてユニットテスト可能にしている。
 * readAsset には "data/merchants.json" のような assets 内パスを渡す。
 */
class DataRepository(
    private val readAsset: (String) -> String,
    private val cacheDir: File,
    private val fetchRemote: (String, String, String) -> String?,
    /** ref をフル commit SHA に解決する(GitHub API)。失敗・未対応は null で SHA 不明として扱う */
    private val resolveSha: (String) -> String? = { null },
) {
    companion object {
        const val MERCHANTS = "merchants.json"
        const val CAMPAIGNS = "campaigns.json"
        const val PAYMENT_METHODS = "payment_methods.json"
        private val ALL_FILES = listOf(MERCHANTS, CAMPAIGNS, PAYMENT_METHODS)

        /** キャッシュと同じ場所に置く、取得元 commit SHA のメモ(データファイルではない) */
        private const val COMMIT_SHA_FILE = "commit_sha.txt"
    }

    fun loadLocal(dataDir: String = "data"): LoadedData {
        val cached = ALL_FILES.map { File(cacheDir, it) }
        if (cached.all { it.isFile }) {
            // キャッシュが壊れていたら捨てて assets にフォールバック
            runCatching {
                val (merchants, campaigns, paymentMethods) = cached.map { it.readText() }
                val data = PoikatsuJson.parse(merchants, campaigns, paymentMethods)
                return LoadedData(data, DataSource.CACHE, readCachedSha())
            }
        }
        return loadBundled(dataDir)
    }

    /** 同梱 assets を直読する(キャッシュを見ない)。「同梱データを使う」ON 中の再読み込みにも使う */
    fun loadBundled(dataDir: String = "data"): LoadedData {
        val (merchants, campaigns, paymentMethods) = ALL_FILES.map { readAsset("$dataDir/$it") }
        return LoadedData(PoikatsuJson.parse(merchants, campaigns, paymentMethods), DataSource.BUNDLED)
    }

    /** 取得・パースのいずれかに失敗したら null(呼び出し側はローカルデータを使い続ける) */
    fun refresh(ref: String = "main", dataDir: String = "data"): LoadedData? {
        // 先に ref をフル SHA へ解決し、3ファイルの取得をその SHA に固定する。branch 名のまま
        // 順に取ると取得の合間の push で別 commit の内容が混ざり得るため。解決できないとき
        // (オフライン・API 制限等)は従来どおり ref のまま取得し、SHA は不明(null)として扱う
        val sha = resolveSha(ref)
        val fetchRef = sha ?: ref
        val texts = ALL_FILES.map { fetchRemote(it, fetchRef, dataDir) ?: return null }
        val data = runCatching {
            PoikatsuJson.parse(texts[0], texts[1], texts[2])
        }.getOrNull() ?: return null
        runCatching {
            cacheDir.mkdirs()
            ALL_FILES.forEachIndexed { i, name -> File(cacheDir, name).writeText(texts[i]) }
            // キャッシュ起動時にも「どの commit のデータか」を出せるよう SHA を添える。
            // SHA 不明のまま残すと別 commit のデータに古い SHA が付くので消す
            val shaFile = File(cacheDir, COMMIT_SHA_FILE)
            if (sha != null) shaFile.writeText(sha) else shaFile.delete()
        }
        return LoadedData(data, DataSource.REMOTE, sha)
    }

    private fun readCachedSha(): String? =
        runCatching { File(cacheDir, COMMIT_SHA_FILE).readText().trim().ifEmpty { null } }.getOrNull()
}
