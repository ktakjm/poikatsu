package com.ktakjm.poikatsu.domain

import com.ktakjm.poikatsu.data.Campaign
import com.ktakjm.poikatsu.data.Merchant
import com.ktakjm.poikatsu.data.MerchantRule
import com.ktakjm.poikatsu.data.PoikatsuData
import com.ktakjm.poikatsu.data.ProfileCard
import com.ktakjm.poikatsu.data.QrPayment
import com.ktakjm.poikatsu.util.JapaneseText
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

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

enum class BenefitType {
    REBATE,
    COUPON_PERCENT,
    COUPON_FIXED;

    companion object {
        fun fromString(s: String): BenefitType = when (s) {
            "coupon_percent" -> COUPON_PERCENT
            "coupon_fixed" -> COUPON_FIXED
            else -> REBATE
        }
    }
}

enum class CampaignStatus { ACTIVE, UPCOMING, EXPIRED }

data class QrJudgment(
    val campaign: Campaign,
    val paymentMethod: QrPayment,
    val benefitType: BenefitType,
    val effectiveRate: Double?,
    val discountAmount: Int?,
    val minPurchase: Int?,
    val usageLimit: Int?,
    val daysRemaining: Int?,
    val perTransactionCap: Int?,
    val periodTotalCap: Int?,
)

data class BestPaymentOption(
    val method: String,
    val rate: Double?,
    val discountAmount: Int?,
    val benefitType: BenefitType,
    val isTimeLimited: Boolean,
    val daysRemaining: Int?,
)

data class JudgmentResult(
    val cardJudgments: List<Judgment>,
    val qrJudgments: List<QrJudgment>,
    val bestOption: BestPaymentOption?,
)

class JudgmentEngine(private val data: PoikatsuData) {

    private val searchIndex: List<Pair<Merchant, List<String>>> = data.merchants.map { m ->
        m to buildList {
            add(JapaneseText.normalize(m.name))
            if (m.reading.isNotBlank()) add(JapaneseText.normalize(m.reading))
            m.aliases.forEach { add(JapaneseText.normalize(it)) }
        }.distinct()
    }

    private val qrPaymentMap: Map<String, QrPayment> =
        data.profile.qrPayments.associateBy { it.id }

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
     *
     * ただし**後方境界は長いキー(5文字以上=ほぼ完全なチェーン名)では緩める**。YOLP の店名は
     * 支店名を区切りなく連結する(例「肉のハナマサひばりヶ丘店」)ため、チェーン名の直後が同字種
     * (はなまさ|ひ…)でも支店名の一部とみなして許容しないと取りこぼす。短いキー(「マック」等)は
     * 「マックスバリュ」のような別単語の接頭辞になりやすいので従来どおり厳格に見る。
     */
    private fun containsAsWord(text: String, key: String): Boolean {
        var index = text.indexOf(key)
        while (index >= 0) {
            val beforeJoined = isSameWord(text.getOrNull(index - 1), key.first())
            val afterJoined = key.length < 5 && isSameWord(text.getOrNull(index + key.length), key.last())
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

    /**
     * 商業施設(=対象チェーン)内テナントの誤検知を判定する。施設内テナントは YOLP 上で
     * 「<施設名>店<テナント業種>」という名前になり、かつ施設のジャンルコードを継ぐため、
     * 施設名(=対象チェーン)で誤マッチしてしまう。例:「ドミー安城横山店大嶽クリーニング」は
     * ドミー(スーパー)ではなくクリーニング店。
     *
     * チェーン名の**後ろ**に「店」があり、さらにその後ろに業種名らしき和文(かな/漢字)が
     * 2 文字以上続く場合にテナントとみなす。「上島珈琲店渋谷店」のようにチェーン名自体が
     * 「店」を含む場合も、チェーン名より後ろだけを見るので誤判定しない(後ろは「渋谷店」で
     * 末尾が「店」=テナント無し)。チェーン名が見つからない(別名/ブランド一致)ときは判定しない。
     */
    fun isFacilityTenant(chainName: String, poiName: String): Boolean {
        val idx = poiName.indexOf(chainName)
        if (idx < 0) return false
        val after = poiName.substring(idx + chainName.length)
        val tenIndex = after.indexOf('店')
        if (tenIndex < 0) return false
        val suffix = after.substring(tenIndex + 1).trim()
        return suffix.length >= 2 && suffix.any { isKanaOrKanji(it) }
    }

    private fun isKanaOrKanji(c: Char): Boolean =
        c.code in 0x3040..0x30FF || c.code in 0x4E00..0x9FFF

    /**
     * 同一店舗の重複排除に使う「支店名」キー。POI 名から、そのチェーンの識別子(店名・読み・別名)を
     * 取り除いて残った部分を正規化・空白除去して返す。
     *
     * これにより重複判定を **チェーン一致だけでなくチェーン+支店名一致**で行える:
     * - 「アカチャンホンポ和光 イトーヨーカドー店」と「…和光イトーヨーカドー店」(空白違い)→ 同じ支店キー → 重複
     * - 「KFC◯◯店」と「ケンタッキーフライドチキン◯◯店」(別名違い)→ どちらも支店キー「◯◯店」→ 重複
     * - 同一モール内の別店舗(例: レイクタウンの複数スターバックス)→ 支店名が異なる → 別物として残す
     */
    fun normalizedBranch(merchant: Merchant, poiName: String): String {
        var s = JapaneseText.normalize(poiName)
        val keys = searchIndex.firstOrNull { it.first.id == merchant.id }?.second.orEmpty()
        // 長いキーから順に 1 回だけ除去(短いキーの部分一致で支店名を削りすぎないため)
        for (key in keys.filter { it.isNotBlank() }.sortedByDescending { it.length }) {
            val i = s.indexOf(key)
            if (i >= 0) s = s.removeRange(i, i + key.length)
        }
        return s.filterNot { it.isWhitespace() }
    }

    // ---- 期間フィルタ ----

    fun campaignStatus(campaign: Campaign, today: LocalDate): CampaignStatus {
        val start = campaign.periodStart?.let { parseDate(it) }
        val end = campaign.periodEnd?.let { parseDate(it) }
        return when {
            end != null && today > end -> CampaignStatus.EXPIRED
            start != null && today < start -> CampaignStatus.UPCOMING
            else -> CampaignStatus.ACTIVE
        }
    }

    fun daysRemaining(campaign: Campaign, today: LocalDate): Int? {
        val end = campaign.periodEnd?.let { parseDate(it) } ?: return null
        val days = ChronoUnit.DAYS.between(today, end).toInt()
        return if (days >= 0) days else null
    }

    /** アクティブな campaign のみ返す(store_scope フィルタなし) */
    fun activeCampaigns(today: LocalDate): List<Campaign> =
        data.campaigns.filter { campaignStatus(it, today) == CampaignStatus.ACTIVE }

    /** もうすぐ開始の campaign を返す */
    fun upcomingCampaigns(today: LocalDate): List<Campaign> =
        data.campaigns.filter { campaignStatus(it, today) == CampaignStatus.UPCOMING }

    /** この施策におけるそのチェーンのルール(なければ対象外) */
    private fun Campaign.ruleFor(merchant: Merchant): MerchantRule? =
        merchantRules.firstOrNull { it.merchantId == merchant.id }

    /**
     * カード施策の判定を還元率の高い順に返す。期間フィルタ適用済み。
     * store_scope == "managed" の施策のみ対象。
     */
    fun judge(merchant: Merchant, today: LocalDate): List<Judgment> =
        data.campaigns
            .filter { campaignStatus(it, today) == CampaignStatus.ACTIVE }
            .filter { it.storeScope == "managed" }
            .filter { it.type == "card_program" || it.type == "card_promotion" }
            .filter { it.paymentMethodId == null }
            .mapNotNull { campaign ->
                val rule = campaign.ruleFor(merchant) ?: return@mapNotNull null
                val card = data.profile.cards.firstOrNull { it.campaignId == campaign.id }
                    ?: return@mapNotNull null
                if (rule.amexExcluded && card.brand.equals("Amex", ignoreCase = true)) {
                    return@mapNotNull null
                }
                Judgment(
                    campaign = campaign,
                    rule = rule,
                    card = card,
                    effectiveRate = card.effectiveRateDefault ?: campaign.rateBase ?: 0.0,
                    warnings = buildList {
                        val days = daysRemaining(campaign, today)
                        if (days != null && days <= 3) add("残り${days}日")
                    },
                )
            }.sortedByDescending { it.effectiveRate }

    /**
     * QR 決済の判定を返す。ユーザーが利用中の QR 決済でフィルタ済み。
     * store_scope == "managed" のみ。municipal(external) は探すタブには含めない。
     */
    fun judgeQr(merchant: Merchant, today: LocalDate, enabledQrIds: Set<String>): List<QrJudgment> =
        data.campaigns
            .filter { campaignStatus(it, today) == CampaignStatus.ACTIVE }
            .filter { it.storeScope == "managed" }
            .filter { it.paymentMethodId != null && it.paymentMethodId in enabledQrIds }
            .mapNotNull { campaign ->
                campaign.ruleFor(merchant) ?: return@mapNotNull null
                val qr = qrPaymentMap[campaign.paymentMethodId] ?: return@mapNotNull null
                QrJudgment(
                    campaign = campaign,
                    paymentMethod = qr,
                    benefitType = BenefitType.fromString(campaign.benefitType),
                    effectiveRate = campaign.rateBase,
                    discountAmount = campaign.discountAmount,
                    minPurchase = campaign.minPurchase,
                    usageLimit = campaign.usageLimit,
                    daysRemaining = daysRemaining(campaign, today),
                    perTransactionCap = campaign.perTransactionCap,
                    periodTotalCap = campaign.periodTotalCap,
                )
            }

    /**
     * カード + QR をまとめた包括判定。
     */
    fun judgeAll(merchant: Merchant, today: LocalDate, enabledQrIds: Set<String> = emptySet()): JudgmentResult {
        val cardJudgments = judge(merchant, today)
        val qrJudgments = judgeQr(merchant, today, enabledQrIds)
        val bestOption = determineBest(cardJudgments, qrJudgments, today)
        return JudgmentResult(cardJudgments, qrJudgments, bestOption)
    }

    private fun determineBest(
        cards: List<Judgment>,
        qrs: List<QrJudgment>,
        today: LocalDate,
    ): BestPaymentOption? {
        data class Candidate(
            val method: String,
            val rate: Double?,
            val discountAmount: Int?,
            val benefitType: BenefitType,
            val isTimeLimited: Boolean,
            val daysRemaining: Int?,
        )

        val candidates = buildList {
            cards.forEach { j ->
                add(
                    Candidate(
                        method = j.card?.cardName ?: j.campaign.issuer,
                        rate = j.effectiveRate,
                        discountAmount = null,
                        benefitType = BenefitType.fromString(j.campaign.benefitType),
                        isTimeLimited = j.campaign.periodEnd != null,
                        daysRemaining = daysRemaining(j.campaign, today),
                    ),
                )
            }
            qrs.forEach { q ->
                add(
                    Candidate(
                        method = q.paymentMethod.name,
                        rate = q.effectiveRate,
                        discountAmount = q.discountAmount,
                        benefitType = q.benefitType,
                        isTimeLimited = q.campaign.periodEnd != null,
                        daysRemaining = q.daysRemaining,
                    ),
                )
            }
        }
        // 定率(rebate/coupon_percent)で最高のものを選ぶ。定額(coupon_fixed)は比較不能なので並列表示
        val bestRate = candidates
            .filter { it.rate != null && it.benefitType != BenefitType.COUPON_FIXED }
            .maxByOrNull { it.rate!! }
        return bestRate?.let {
            BestPaymentOption(it.method, it.rate, it.discountAmount, it.benefitType, it.isTimeLimited, it.daysRemaining)
        }
    }

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

    companion object {
        private val dateFormatter = DateTimeFormatter.ISO_LOCAL_DATE
        fun parseDate(s: String): LocalDate = LocalDate.parse(s, dateFormatter)
    }
}
