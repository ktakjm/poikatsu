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

    val codeToName = master.prefectures
        .flatMap { pref -> pref.municipalities.map { it.code to (pref.name to it.name) } }
        .toMap()
    val groupById = master.prefectures
        .flatMap { it.groups }
        .associateBy { it.id }

    val allowed = buildSet {
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
    val knownMunicipalities = codeToName.values.toSet()

    return campaigns.filter { campaign ->
        val region = campaign.region ?: return@filter true
        val key = region.prefecture to region.name
        key in allowed || key !in knownMunicipalities
    }
}
