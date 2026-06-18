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

enum class StoreEligibility {
    /** 公式の対象店舗リストに一致 */
    ELIGIBLE,
    /** 公式の対象外店舗リストに一致 */
    INELIGIBLE,
    /** どちらのリストにも無い(公式リスト外・要確認) */
    UNKNOWN,
}

/**
 * 公式が対象/対象外を言い切っているチェーンについて、特定店舗の判定結果。
 * official_store_list を持つ施策ごとに 1 件返る。
 */
data class StoreVerdict(
    val campaign: Campaign,
    val eligibility: StoreEligibility,
    val matched: String?,
    val updatedDate: String,
    val dateIsOfficial: Boolean,
    val sourceUrl: String?,
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
                    // 短いキー(OK等)の誤爆を避けるため3文字以上+単語境界判定
                    key.length >= 3 && containsAsWord(q, key) -> 2
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
     * 地図POIの店舗名(例: "マクドナルド 渋谷駅前店")やブランドタグから該当チェーンを特定する。
     * 「ステーキガスト」が「ガスト」に誤マッチしないよう、一致したキーが最長のチェーンを採用する。
     */
    fun matchStore(storeName: String, brand: String? = null): Merchant? {
        val normalizedName = JapaneseText.normalize(storeName)
        val normalizedBrand = brand?.let { JapaneseText.normalize(it) }
        return searchIndex.mapNotNull { (merchant, keys) ->
            val best = keys.filter { key ->
                (key.length >= 3 && containsAsWord(normalizedName, key)) || key == normalizedBrand
            }.maxOfOrNull { it.length }
            if (best == null) null else merchant to best
        }.maxByOrNull { it.second }?.first
    }

    /**
     * 単語っぽい境界での包含判定。「マックスバリュ」が「マック」にヒットしないよう、
     * キーの端と隣接文字が同じ文字種(カナ同士・英数同士)で続く場合は別単語の一部とみなす。
     * 文字種が変わる位置(「くら寿司|ららぽーと」の漢字→かな等)は単語境界として許容する。
     * 正規化後はカタカナがひらがなになっている前提。
     */
    private fun containsAsWord(text: String, key: String): Boolean {
        var index = text.indexOf(key)
        while (index >= 0) {
            val beforeJoined = isSameWord(text.getOrNull(index - 1), key.first())
            val afterJoined = isSameWord(text.getOrNull(index + key.length), key.last())
            if (!beforeJoined && !afterJoined) return true
            index = text.indexOf(key, index + 1)
        }
        return false
    }

    private fun isSameWord(adjacent: Char?, keyEdge: Char): Boolean {
        if (adjacent == null) return false
        return (isKana(adjacent) && isKana(keyEdge)) || (isAsciiAlnum(adjacent) && isAsciiAlnum(keyEdge))
    }

    private fun isKana(c: Char): Boolean = c in 'ぁ'..'ゖ' || c == 'ー'

    private fun isAsciiAlnum(c: Char): Boolean = c.code < 128 && c.isLetterOrDigit()

    /** この施策におけるそのチェーンのルール(なければ対象外) */
    private fun Campaign.ruleFor(merchant: Merchant): MerchantRule? =
        merchantRules.firstOrNull { it.merchantId == merchant.id }

    /**
     * 該当施策を還元率の高い順に返す。対象外ならば空リスト。
     */
    fun judge(merchant: Merchant): List<Judgment> =
        data.campaigns.mapNotNull { campaign ->
            val rule = campaign.ruleFor(merchant) ?: return@mapNotNull null
            // 所有していないカードの施策は対象外。設定で所有 OFF にしたカードは
            // VM のマージ層で profile から外れるため、ここに来ない(= null スキップ)。
            val card = data.profile.cards.firstOrNull { it.campaignId == campaign.id }
                ?: return@mapNotNull null
            // Amex ブランドかつ店舗が amex_excluded のときは、その店ではこの施策を対象外にする
            // (優遇還元の適用なし)。地図・検索・詳細すべてからこの施策が消える。
            if (rule.amexExcluded && card.brand.equals("Amex", ignoreCase = true)) {
                return@mapNotNull null
            }
            Judgment(
                campaign = campaign,
                rule = rule,
                card = card,
                effectiveRate = card.effectiveRateDefault ?: campaign.rateBase,
                // 還元率はユーザーが公式アプリの実効値を手入力する前提なので、エントリー要否の警告は持たない。
                // warnings は将来の用途(例: period_end 期限切れ)のために残す。
                warnings = emptyList(),
            )
        }.sortedByDescending { it.effectiveRate }

    /**
     * 公式が対象/対象外を言い切っている店舗リスト(official_store_list)を持つ施策が
     * 1 つでもあれば、店舗単位の対象判定画面に遷移できる。
     */
    fun canCheckStore(merchant: Merchant): Boolean =
        data.campaigns.any { it.ruleFor(merchant)?.officialStoreList != null }

    /**
     * 特定店舗の判定を、公式リストを持つ施策ごとに返す。
     * 対象外(ineligible)を優先し、対象(eligible)、どちらにも無ければ要確認(UNKNOWN)。
     */
    fun checkStore(merchant: Merchant, storeName: String): List<StoreVerdict> {
        val normalized = JapaneseText.normalize(storeName)
        if (normalized.isBlank()) return emptyList()
        return data.campaigns.mapNotNull { campaign ->
            val list = campaign.ruleFor(merchant)?.officialStoreList ?: return@mapNotNull null
            fun match(stores: List<String>) =
                stores.firstOrNull { normalized.contains(JapaneseText.normalize(it)) }
            val ineligible = match(list.ineligibleStores)
            val eligible = if (ineligible == null) match(list.eligibleStores) else null
            val (eligibility, matched) = when {
                ineligible != null -> StoreEligibility.INELIGIBLE to ineligible
                eligible != null -> StoreEligibility.ELIGIBLE to eligible
                else -> StoreEligibility.UNKNOWN to null
            }
            StoreVerdict(
                campaign = campaign,
                eligibility = eligibility,
                matched = matched,
                updatedDate = list.updatedDate,
                dateIsOfficial = list.dateIsOfficial,
                sourceUrl = list.sourceUrl,
            )
        }
    }

    /**
     * 近隣リスト用: その店舗が公式に「対象外」と明示されているか。
     * 対象(eligible)明示がある場合は除外扱いにしない。official_store_list が無いチェーンは常に false。
     */
    fun isExcludedStore(merchant: Merchant, storeName: String): Boolean {
        val verdicts = checkStore(merchant, storeName)
        return verdicts.any { it.eligibility == StoreEligibility.INELIGIBLE } &&
            verdicts.none { it.eligibility == StoreEligibility.ELIGIBLE }
    }
}
