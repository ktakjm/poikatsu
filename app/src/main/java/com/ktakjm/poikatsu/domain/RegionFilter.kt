package com.ktakjm.poikatsu.domain

import com.ktakjm.poikatsu.data.Campaign
import com.ktakjm.poikatsu.data.MunicipalityMaster
import com.ktakjm.poikatsu.data.RegisteredArea
import com.ktakjm.poikatsu.data.RegisteredAreaType

/**
 * 登録自治体(居住地・行動圏)によるキャンペーンの地域フィルタ。
 *
 * - 登録が無い、またはマスタ未ロードならフィルタしない(全表示)
 * - region を持たない施策(全国のカード・QR 施策等)は常に通過
 * - region がマスタに見つからない施策も通過(自治体合併等でマスタと施策データの名称が
 *   ずれたとき、フィルタの取りこぼしで施策が消えるより、ノイズが1件混ざる方を選ぶ)
 *
 * 突合は自治体コードでなく (都道府県名, 自治体名) で行う。campaign.region が名称しか
 * 持たない(施策データの手書きでコードを要求しない)ため。
 */
fun filterCampaignsByArea(
    campaigns: List<Campaign>,
    registered: List<RegisteredArea>,
    master: MunicipalityMaster,
): List<Campaign> {
    if (registered.isEmpty() || master.isEmpty()) return campaigns

    val allowed = allowedMunicipalities(registered, master)
    val knownMunicipalities = master.prefectures
        .flatMap { pref -> pref.municipalities.map { pref.name to it.name } }
        .toSet()

    return campaigns.filter { campaign ->
        val region = campaign.region ?: return@filter true
        val key = region.prefecture to region.name
        key in allowed || key !in knownMunicipalities
    }
}

/** 登録地域を (都道府県名, 自治体名) の集合に展開する(グループは構成自治体へ展開) */
private fun allowedMunicipalities(
    registered: List<RegisteredArea>,
    master: MunicipalityMaster,
): Set<Pair<String, String>> {
    val codeToName = master.prefectures
        .flatMap { pref -> pref.municipalities.map { it.code to (pref.name to it.name) } }
        .toMap()
    val groupById = master.prefectures
        .flatMap { it.groups }
        .associateBy { it.id }

    return buildSet {
        registered.forEach { area ->
            when (area.type) {
                RegisteredAreaType.MUNICIPALITY -> {
                    // 登録時のスナップショット名でも通す(マスタ更新でコードが消えた場合の保険)
                    add(area.prefecture to area.name)
                    codeToName[area.code]?.let { add(it) }
                }
                RegisteredAreaType.GROUP ->
                    groupById[area.code]?.municipalities?.forEach { code ->
                        codeToName[code]?.let { add(it) }
                    }
            }
        }
    }
}

/**
 * 登録地域に「厳密一致」する自治体施策のみ返す(探すタブのお知らせバナー用)。
 * filterCampaignsByArea と方向が逆な点に注意: あちらはフィルタなので取りこぼしを避けて
 * 全通し側に倒すが、こちらは能動的な表示なので誤表示を避けて「出さない」側に倒す
 * (未登録・マスタ未ロード・region 不一致はすべて非表示)。
 */
fun municipalCampaignsForAreas(
    campaigns: List<Campaign>,
    registered: List<RegisteredArea>,
    master: MunicipalityMaster,
): List<Campaign> {
    if (registered.isEmpty() || master.isEmpty()) return emptyList()
    val allowed = allowedMunicipalities(registered, master)
    return campaigns.filter { campaign ->
        campaign.campaignType == CampaignType.MUNICIPAL &&
            campaign.region?.let { (it.prefecture to it.name) in allowed } == true
    }
}

/**
 * 店舗・地点の所在地に一致する自治体施策(「近く」の地図お知らせピル用)。
 * localityCandidates にはリバースジオコーディング結果の市区町村名候補を渡す
 * (東京23区・一般市は locality、政令市の行政区は subLocality に入るため複数候補)。
 */
fun municipalCampaignsForLocation(
    campaigns: List<Campaign>,
    prefecture: String,
    localityCandidates: List<String>,
): List<Campaign> {
    if (prefecture.isBlank()) return emptyList()
    val candidates = localityCandidates.filter { it.isNotBlank() }.toSet()
    if (candidates.isEmpty()) return emptyList()
    return campaigns.filter { campaign ->
        campaign.campaignType == CampaignType.MUNICIPAL &&
            campaign.region?.let { it.prefecture == prefecture && it.name in candidates } == true
    }
}
