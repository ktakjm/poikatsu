package com.ktakjm.poikatsu.data

import java.io.File

enum class DataSource { REMOTE, CACHE, BUNDLED }

data class LoadedData(val data: PoikatsuData, val source: DataSource)

/**
 * 施策データの取得戦略:
 * 1. 起動時は loadLocal() — 前回リモート取得のキャッシュ、なければ同梱 assets から即時ロード
 * 2. 裏で refresh() — リモート(GitHub raw)から取得し、パースに成功した場合のみキャッシュ保存
 *
 * merchants/campaigns はリモート更新の対象。profile はユーザー設定なので常にローカル。
 * Android 非依存(関数とFileを注入)にしてユニットテスト可能にしている。
 */
class DataRepository(
    private val readAsset: (String) -> String,
    private val cacheDir: File,
    private val fetchRemote: (String, String) -> String?,
) {
    companion object {
        const val MERCHANTS = "merchants.json"
        const val CAMPAIGNS = "campaigns.json"
        const val PROFILE = "profile.json"
    }

    fun loadLocal(): LoadedData {
        val profileJson = readAsset(PROFILE)
        val cachedMerchants = File(cacheDir, MERCHANTS)
        val cachedCampaigns = File(cacheDir, CAMPAIGNS)
        if (cachedMerchants.isFile && cachedCampaigns.isFile) {
            // キャッシュが壊れていたら捨てて assets にフォールバック
            runCatching {
                val data = PoikatsuJson.parse(cachedMerchants.readText(), cachedCampaigns.readText(), profileJson)
                return LoadedData(data, DataSource.CACHE)
            }
        }
        val data = PoikatsuJson.parse(readAsset(MERCHANTS), readAsset(CAMPAIGNS), profileJson)
        return LoadedData(data, DataSource.BUNDLED)
    }

    /** 取得・パースのいずれかに失敗したら null(呼び出し側はローカルデータを使い続ける) */
    fun refresh(ref: String = "main"): LoadedData? {
        val merchantsJson = fetchRemote(MERCHANTS, ref) ?: return null
        val campaignsJson = fetchRemote(CAMPAIGNS, ref) ?: return null
        val data = runCatching {
            PoikatsuJson.parse(merchantsJson, campaignsJson, readAsset(PROFILE))
        }.getOrNull() ?: return null
        runCatching {
            cacheDir.mkdirs()
            File(cacheDir, MERCHANTS).writeText(merchantsJson)
            File(cacheDir, CAMPAIGNS).writeText(campaignsJson)
        }
        return LoadedData(data, DataSource.REMOTE)
    }
}
