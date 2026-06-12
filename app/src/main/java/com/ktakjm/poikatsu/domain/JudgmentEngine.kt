package com.ktakjm.poikatsu.domain

import com.ktakjm.poikatsu.data.Campaign
import com.ktakjm.poikatsu.data.Merchant
import com.ktakjm.poikatsu.data.MerchantRule
import com.ktakjm.poikatsu.data.PoikatsuData
import com.ktakjm.poikatsu.data.ProfileCard
import com.ktakjm.poikatsu.util.JapaneseText

/** ある店舗に対する 1 施策分の判定結果 */
data class Judgment(
    val campaign: Campaign,
    val rule: MerchantRule,
    val card: ProfileCard?,
    val effectiveRate: Double,
    val warnings: List<String>,
)

class JudgmentEngine(private val data: PoikatsuData) {

    private val searchIndex: List<Pair<Merchant, List<String>>> = data.merchants.map { m ->
        m to buildList {
            add(JapaneseText.normalize(m.name))
            if (m.reading.isNotBlank()) add(JapaneseText.normalize(m.reading))
            m.aliases.forEach { add(JapaneseText.normalize(it)) }
        }.distinct()
    }

    /** データ定義順のカテゴリ一覧 */
    val categories: List<String> = data.merchants.map { it.category }.distinct()

    /**
     * 店名とカテゴリで検索する。カテゴリ未選択(空セット)は全カテゴリ扱い。
     * 店名が空でもカテゴリが選択されていればそのカテゴリの全店舗を返す。
     * 店名検索は前方一致を部分一致より優先する。
     */
    fun search(query: String, selectedCategories: Set<String> = emptySet()): List<Merchant> {
        val pool = if (selectedCategories.isEmpty()) searchIndex
        else searchIndex.filter { it.first.category in selectedCategories }

        val q = JapaneseText.normalize(query)
        if (q.isBlank()) {
            // カテゴリのみの絞り込み。未選択なら何も表示しない(入力前のヒント画面を出す)
            return if (selectedCategories.isEmpty()) emptyList() else pool.map { it.first }
        }
        return pool.mapNotNull { (merchant, keys) ->
            val score = keys.minOf { key ->
                when {
                    key.startsWith(q) -> 0
                    key.contains(q) -> 1
                    else -> Int.MAX_VALUE
                }
            }
            if (score == Int.MAX_VALUE) null else merchant to score
        }
            .sortedWith(compareBy({ it.second }, { it.first.reading }))
            .map { it.first }
    }

    /** 該当施策を還元率の高い順に返す。対象外ならば空リスト */
    fun judge(merchant: Merchant): List<Judgment> =
        data.campaigns.mapNotNull { campaign ->
            val rule = campaign.merchantRules.firstOrNull { it.merchantId == merchant.id }
                ?: return@mapNotNull null
            val card = data.profile.cards.firstOrNull { it.campaignId == campaign.id }
            val isAmex = card?.brand?.equals("Amex", ignoreCase = true) == true
            val warnings = buildList {
                if (campaign.entryRequired && card?.entryDone != true) {
                    add("エントリー必須の施策です。未エントリーの場合は通常還元のみになります")
                }
                if (rule.amexExcluded && isAmex) {
                    add("Amexブランドはこの店舗では優遇対象外です")
                }
            }
            Judgment(
                campaign = campaign,
                rule = rule,
                card = card,
                effectiveRate = card?.effectiveRateDefault ?: campaign.rateBase,
                warnings = warnings,
            )
        }.sortedByDescending { it.effectiveRate }
}
