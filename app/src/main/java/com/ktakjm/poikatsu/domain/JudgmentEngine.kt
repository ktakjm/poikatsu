package com.ktakjm.poikatsu.domain

import com.ktakjm.poikatsu.data.Campaign
import com.ktakjm.poikatsu.data.Merchant
import com.ktakjm.poikatsu.data.MerchantRule
import com.ktakjm.poikatsu.data.PoikatsuData
import com.ktakjm.poikatsu.data.ProfileCard
import com.ktakjm.poikatsu.util.JapaneseText

enum class BranchWarningLevel {
    /** 公式の対象外店舗パターンに一致(対象外の可能性が高い) */
    EXCLUDED,
    /** 商業施設内など、対象外になりがちな立地パターンに一致 */
    RISK,
}

data class BranchWarning(val level: BranchWarningLevel, val message: String)

/** ある店舗に対する 1 施策分の判定結果 */
data class Judgment(
    val campaign: Campaign,
    val rule: MerchantRule,
    val card: ProfileCard?,
    val effectiveRate: Double,
    val warnings: List<String>,
    val branchWarnings: List<BranchWarning> = emptyList(),
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
                    // 「マクドナルド渋谷店」のような具体店舗名入力でもチェーンにヒットさせる。
                    // 短いキー(OK等)の誤爆を避けるため3文字以上に限定
                    key.length >= 3 && q.contains(key) -> 2
                    else -> Int.MAX_VALUE
                }
            }
            if (score == Int.MAX_VALUE) null else merchant to score
        }
            .sortedWith(compareBy({ it.second }, { it.first.reading }))
            .map { it.first }
    }

    /** 入力がチェーン名そのもの(店舗名部分なし)かどうか */
    fun isExactNameMatch(merchant: Merchant, query: String): Boolean {
        val q = JapaneseText.normalize(query)
        return searchIndex.firstOrNull { it.first.id == merchant.id }?.second?.contains(q) == true
    }

    /**
     * 該当施策を還元率の高い順に返す。対象外ならば空リスト。
     * branchName(「○○駅前店」等)を渡すと、店舗単位の対象外チェックを行う。
     */
    fun judge(merchant: Merchant, branchName: String? = null): List<Judgment> =
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
                branchWarnings = checkBranch(campaign, rule, branchName),
            )
        }.sortedByDescending { it.effectiveRate }

    private fun checkBranch(campaign: Campaign, rule: MerchantRule, branchName: String?): List<BranchWarning> {
        if (branchName.isNullOrBlank()) return emptyList()
        val normalized = JapaneseText.normalize(branchName)
        return buildList {
            rule.exclusionPatterns
                .firstOrNull { normalized.contains(JapaneseText.normalize(it)) }
                ?.let {
                    add(
                        BranchWarning(
                            BranchWarningLevel.EXCLUDED,
                            "「$it」は公式の対象外店舗パターンに一致します。この店舗では適用されない可能性が高いです",
                        )
                    )
                }
            campaign.facilityRiskPatterns
                .firstOrNull { normalized.contains(JapaneseText.normalize(it)) }
                ?.let {
                    add(
                        BranchWarning(
                            BranchWarningLevel.RISK,
                            "「$it」を含む店舗(商業施設内など)は対象外の場合があります。店頭または公式サイトで要確認です",
                        )
                    )
                }
        }
    }
}
