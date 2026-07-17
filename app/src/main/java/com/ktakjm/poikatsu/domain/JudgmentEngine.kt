package com.ktakjm.poikatsu.domain

import com.ktakjm.poikatsu.data.Campaign
import com.ktakjm.poikatsu.data.Merchant
import com.ktakjm.poikatsu.data.MerchantRule
import com.ktakjm.poikatsu.data.PaymentCard
import com.ktakjm.poikatsu.data.PointMultiplier
import com.ktakjm.poikatsu.data.PoikatsuData
import com.ktakjm.poikatsu.data.QrPayment
import com.ktakjm.poikatsu.data.Recurrence
import com.ktakjm.poikatsu.util.JapaneseText
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * ある店舗に対する 1 施策分の判定結果。カード決済・QR 決済・キャンペーン詳細で共用する。
 * 各フィールドは null / 空ならカード側で非表示になるため、カード種別ごとの分岐は不要。
 */
data class CampaignJudgment(
    val campaign: Campaign,
    val badgeLabel: String,
    val brandColor: String?,
    val benefitType: BenefitType,
    val effectiveRate: Double?,
    val discountAmount: Int?,
    val daysRemaining: Int?,
    /** 「対象」セクション(campaign 直下+その店の merchant_rules をレベル横断で連結)。通常ロールで表示 */
    val eligibleNotes: List<String>,
    /** 「対象外」セクション(同上の連結)。warning 面 1 コンテナに箇条書きで表示 */
    val ineligibleNotes: List<String>,
    val storeListUrl: String?,
    val warnings: List<String>,
    val minPurchase: Int?,
    val usageLimitText: String?,
    val perTransactionCap: Int?,
    val periodTotalCap: Int?,
    val capNote: String?,
    val storeSearchUrl: String?,
    val detailUrl: String?,
    /** 起動リンク(0〜N 件)。QR は決済アプリ(AEON Pay のように複数あり得る)、カードはウォレット(Google Pay) */
    val appLinks: List<AppLink> = emptyList(),
    val pointMultiplier: PointMultiplier?,
    val welcatsuApplied: Boolean,
    /** 予算到達次第の早期終了があり得る施策か。true なら注記を出す */
    val mayEndEarly: Boolean = false,
    /** recurrence 施策で今日が対象日か。recurrence の無い施策は常に true */
    val todayIsTarget: Boolean = true,
    /** recurrence 施策で今日が非対象日のときの次の対象日。対象日当日・recurrence 無しは null */
    val nextTargetDate: LocalDate? = null,
)

/**
 * 判定詳細の起動リンク 1 件。label は「◯◯を開く」の◯◯で、バッジ(カード/サービス名)でなく
 * 起動先アプリの名前を入れる(「三井住友カードアプリを開く」でウォレットが起動する齟齬を避ける)
 */
data class AppLink(
    val packageName: String,
    val label: String,
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

enum class BenefitType(val jsonValue: String) {
    REBATE("rebate"),
    DISCOUNT("discount"),

    /** 抽選型。確定還元ではないため「最良特典」比較には載せない(表示のみ) */
    LOTTERY("lottery");

    companion object {
        fun fromString(s: String): BenefitType = entries.find { it.jsonValue == s } ?: REBATE
    }
}

enum class CampaignType(val jsonValue: String) {
    CARD_PROGRAM("card_program"),
    PROMOTION("promotion"),
    MUNICIPAL("municipal");

    companion object {
        fun fromString(s: String): CampaignType = entries.find { it.jsonValue == s } ?: CARD_PROGRAM
    }
}

val Campaign.campaignType: CampaignType get() = CampaignType.fromString(type)

/**
 * 期間限定バッジの対象か。「終了日が決まっている」だけでなく「終了日未定でも予算到達で
 * 早期終了があり得る」(かなトク等の may_end_early)も期間限定として扱う。
 * false になるのは実質、終了予定の無い常設施策(card_program)のみ。
 */
val Campaign.isTimeLimited: Boolean get() = periodEnd != null || mayEndEarly

// ---- ウォレット(スマホのタッチ決済)対応 ----
// eligible_wallets / ineligible_wallets は「公式がウォレット単位で対象/対象外を言い切っている」
// 事実だけを持つ(未掲載は不明の3状態)。Android 固有の消費(Google Pay → ウォレットアプリ起動)は
// ここに閉じる。apple_pay は起動リンクには使わないが、Google Pay 対象外警告の付記
// (iPhone 併用者向けの「Apple Payは対象」)に使う。

/** Android のウォレット(Google Pay)アプリのパッケージ名(文字列定数のみなので domain の純 Kotlin は維持される) */
const val WALLET_APP_PACKAGE = "com.google.android.apps.walletnfcrel"

/** eligible_wallets / ineligible_wallets でのウォレット識別子 */
const val WALLET_GOOGLE_PAY = "google_pay"
const val WALLET_APPLE_PAY = "apple_pay"

/** ウォレット起動リンクのラベル。バッジ(カード名)でなく起動先アプリの名前を出す */
const val WALLET_APP_LABEL = "ウォレット(Google Pay)"

/** 公式が Google Pay を還元対象と明記している施策ならウォレットアプリのパッケージ名。それ以外は null */
val Campaign.walletAppPackage: String?
    get() = WALLET_APP_PACKAGE.takeIf { WALLET_GOOGLE_PAY in eligibleWallets }

/** ウォレット起動リンク(walletAppPackage の AppLink 形) */
val Campaign.walletAppLink: AppLink?
    get() = walletAppPackage?.let { AppLink(it, WALLET_APP_LABEL) }

/** QR 決済サービスの起動リンク一覧。ラベルは「{アプリ実名}アプリ」 */
val QrPayment.appLinks: List<AppLink>
    get() = appPackages.map { AppLink(it.packageName, "${it.label}アプリ") }

/** 公式が Google Pay を還元対象外と明記している施策への警告文。該当しなければ null */
val Campaign.googlePayIneligibleWarning: String?
    get() = when {
        WALLET_GOOGLE_PAY !in ineligibleWallets -> null
        // 非対称なケース(MUFG 等)は「Apple Pay なら対象」まで言い切る
        WALLET_APPLE_PAY in eligibleWallets -> "Google Pay(スマホのタッチ決済)での支払いは還元対象外(Apple Payは対象)"
        else -> "Google Pay(スマホのタッチ決済)での支払いは還元対象外"
    }

data class BenefitLabel(val value: String, val suffix: String) {
    override fun toString() = "$value$suffix"
}

fun formatBenefit(benefitType: BenefitType, rate: Double?, discount: Int?): BenefitLabel? =
    when (benefitType) {
        BenefitType.DISCOUNT -> when {
            discount != null -> BenefitLabel("%,d円".format(discount), "引き")
            rate != null -> BenefitLabel("${trimRate(rate)}%", " OFF")
            else -> null
        }
        BenefitType.REBATE -> when {
            discount != null -> BenefitLabel("%,d円".format(discount), "還元")
            rate != null -> BenefitLabel("${trimRate(rate)}%", " 還元")
            else -> null
        }
        // 抽選は確定特典ではないため定率・定額と同列のラベルにしない(最良特典の比較からも自然に外れる)
        BenefitType.LOTTERY -> null
    }

fun trimRate(rate: Double): String =
    if (rate == rate.toLong().toDouble()) rate.toLong().toString() else rate.toString()

// ---- recurrence(繰り返し日付条件) ----
// campaignStatus(期間の外枠)とは独立に「その日が対象日か」を判定する。「お店」「地図」の判定は
// 期間内かつ対象日のみ、期間限定タブは期間内なら非対象日でも出して「次の対象日」を案内する。

/** recurrence 条件に date が一致するか。recurrence の無い施策は常に true(全日対象) */
fun isTargetDay(campaign: Campaign, date: LocalDate): Boolean =
    campaign.recurrence?.matches(date) ?: true

private fun Recurrence.matches(date: LocalDate): Boolean = when {
    daysOfWeek.isNotEmpty() -> date.dayOfWeek.name.take(3) in daysOfWeek.map { it.uppercase() }
    daysOfMonth.isNotEmpty() -> date.dayOfMonth in daysOfMonth
    else -> true
}

/** 次の対象日(明日以降・期間内)。recurrence が無い、または期間内に対象日が残っていなければ null */
fun nextTargetDay(campaign: Campaign, today: LocalDate): LocalDate? {
    val recurrence = campaign.recurrence ?: return null
    val end = campaign.periodEnd?.let { JudgmentEngine.parseDate(it) }
    // days_of_month でも最長約1ヶ月先までに一致するはずだが、31日等の存在しない日指定に備えて2ヶ月で打ち切る
    var date = today.plusDays(1)
    val limit = today.plusDays(62)
    while (date <= limit && (end == null || date <= end)) {
        if (recurrence.matches(date)) return date
        date = date.plusDays(1)
    }
    return null
}

/** recurrence の人間向けラベル(「毎週金・土曜」「毎月20日・30日」) */
fun recurrenceLabel(recurrence: Recurrence): String = when {
    recurrence.daysOfWeek.isNotEmpty() ->
        "毎週" + recurrence.daysOfWeek.joinToString("・") { dayOfWeekJa(it) } + "曜"
    recurrence.daysOfMonth.isNotEmpty() ->
        "毎月" + recurrence.daysOfMonth.joinToString("・") { "${it}日" }
    else -> ""
}

private fun dayOfWeekJa(day: String): String = when (day.uppercase()) {
    "MON" -> "月"
    "TUE" -> "火"
    "WED" -> "水"
    "THU" -> "木"
    "FRI" -> "金"
    "SAT" -> "土"
    "SUN" -> "日"
    else -> day
}

enum class CampaignStatus { ACTIVE, UPCOMING, EXPIRED }

data class BestPaymentOption(
    val method: String,
    val rate: Double?,
    val discountAmount: Int?,
    val benefitType: BenefitType,
    val isTimeLimited: Boolean,
    val daysRemaining: Int?,
)

data class JudgmentResult(
    val judgments: List<CampaignJudgment>,
    val bestOption: BestPaymentOption?,
)

/**
 * 一覧(検索・近くリスト・地図プレビュー)に出す「最良特典」ラベル。
 * 定率の最大(bestOption)があればそれを、定額特典しか無いチェーンでは判定リスト先頭
 * (judgeAll のソートで定額同士は金額降順)の特典を整形する。
 * 定額は購入額に依存し定率と比較できないため、比較ポリシー(determineBest)は変えず
 * 見せ方だけをラベル化する(定額のみのチェーンが「0%」表示になる問題への対処。#29)。
 * 対象商品限定(product_scope)の特典しか無いチェーンは「(対象商品)」を付記し、
 * 全商品に効く率と誤認されないようにする(#43)。
 */
fun JudgmentResult.bestBenefitLabel(): BenefitLabel? {
    bestOption?.let { return formatBenefit(it.benefitType, it.rate, it.discountAmount) }
    judgments.filter { it.campaign.productScope == null }
        .firstNotNullOfOrNull { formatBenefit(it.benefitType, it.effectiveRate, it.discountAmount) }
        ?.let { return it }
    return judgments.firstNotNullOfOrNull {
        formatBenefit(it.benefitType, it.effectiveRate, it.discountAmount)
    }?.let { BenefitLabel(it.value, "${it.suffix}(対象商品)") }
}

class JudgmentEngine(private val data: PoikatsuData) {

    private val searchIndex: List<Pair<Merchant, List<String>>> = data.merchants.map { m ->
        m to buildList {
            add(JapaneseText.normalize(m.name))
            if (m.reading.isNotBlank()) add(JapaneseText.normalize(m.reading))
            m.aliases.forEach { add(JapaneseText.normalize(it)) }
        }.distinct()
    }

    private val qrPaymentMap: Map<String, QrPayment> =
        data.qrPayments.associateBy { it.id }

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
     * 地図POIの店舗名(例: "マクドナルド 渋谷駅前店")から該当チェーンを特定する。
     * 「ステーキガスト」が「ガスト」に誤マッチしないよう、一致したキーが最長のチェーンを採用する。
     */
    fun matchStore(storeName: String): Merchant? {
        val normalizedName = JapaneseText.normalize(storeName)
        return searchIndex.mapNotNull { (merchant, keys) ->
            val best = keys.filter { key -> isMatchableKey(key) && containsAsWord(normalizedName, key) }
                .maxOfOrNull { it.length }
            if (best == null) null else merchant to best
        }.maxByOrNull { it.second }?.first
    }

    /**
     * POI 名との照合に使えるキーか。3 文字未満は「もす」のような別単語の接頭辞に誤爆しやすいので
     * 照合しない。ただし**漢字のみ 2 文字**(松屋・夢庵・藍屋・桃菜・三和)はかなより情報密度が
     * 高く誤爆しにくいため許可する(「小松屋」等への誤爆は containsAsWord の漢字境界判定で防ぐ)。
     */
    private fun isMatchableKey(key: String): Boolean =
        key.length >= 3 || (key.length == 2 && key.all { isKanji(it) })

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
     *
     * 漢字は**前方境界のみ**連結とみなす(前後非対称)。キー先頭が漢字で直前も漢字なら別の名前の
     * 一部(「小松屋」「浜松屋」の「松屋」)だが、直後の漢字は支店名の始まり(「松屋渋谷店」の
     * 「渋谷」)であることが多く、後方まで厳格にすると取りこぼすため。
     */
    private fun containsAsWord(text: String, key: String): Boolean {
        var index = text.indexOf(key)
        while (index >= 0) {
            val before = text.getOrNull(index - 1)
            val beforeJoined = isSameWord(before, key.first()) ||
                (before != null && isKanji(before) && isKanji(key.first()))
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

    private fun isKanji(c: Char): Boolean = c.code in 0x4E00..0x9FFF

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
    // recurrence(対象日)は含まない期間の外枠だけの判定。期間限定タブは期間内なら
    // 非対象日でも「開催中」に出す(次の対象日を案内する)ため、対象日は isTargetDay で別判定する。

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

    fun daysUntilStart(campaign: Campaign, today: LocalDate): Int? {
        val start = campaign.periodStart?.let { parseDate(it) } ?: return null
        val days = ChronoUnit.DAYS.between(today, start).toInt()
        return if (days > 0) days else null
    }

    /** アクティブな campaign のみ返す */
    fun activeCampaigns(today: LocalDate): List<Campaign> =
        data.campaigns.filter { campaignStatus(it, today) == CampaignStatus.ACTIVE }

    /** もうすぐ開始の campaign を返す */
    fun upcomingCampaigns(today: LocalDate): List<Campaign> =
        data.campaigns.filter { campaignStatus(it, today) == CampaignStatus.UPCOMING }

    /**
     * アクティブな managed 施策が参照する merchant ID の集合(YOLP 検索対象の決定に使う)。
     * recurrence 施策は今日が対象日のときだけ含める(非対象日は判定にも出ないため検索しても無駄)。
     */
    fun activeManagedMerchantIds(today: LocalDate): Set<String> =
        activeCampaigns(today)
            .filter { it.storeScope == "managed" && isTargetDay(it, today) }
            .flatMap { it.merchantRules }
            .map { it.merchantId }
            .toSet()

    /** この施策におけるそのチェーンのルール(なければ対象外) */
    private fun Campaign.ruleFor(merchant: Merchant): MerchantRule? =
        merchantRules.firstOrNull { it.merchantId == merchant.id }

    private fun usageLimitText(campaign: Campaign): String? =
        campaign.usageLimitNote
            ?: campaign.usageLimit?.let { "お一人様${it}回まで" }

    private fun buildJudgment(
        campaign: Campaign,
        rule: MerchantRule?,
        badgeLabel: String,
        effectiveRate: Double?,
        discountAmount: Int?,
        pointMultiplier: PointMultiplier?,
        welcatsuApplied: Boolean,
        appLinks: List<AppLink>,
        today: LocalDate,
    ): CampaignJudgment {
        val days = daysRemaining(campaign, today)
        val benefitType = BenefitType.fromString(campaign.benefitType)
        val isLottery = benefitType == BenefitType.LOTTERY
        val todayIsTarget = isTargetDay(campaign, today)
        return CampaignJudgment(
            campaign = campaign,
            badgeLabel = badgeLabel,
            brandColor = data.brandColorOf(campaign),
            benefitType = benefitType,
            // 抽選は確定還元ではないので率・額を持たせない(ソート・最良比較に混ざらないように)
            effectiveRate = effectiveRate.takeUnless { isLottery },
            discountAmount = discountAmount.takeUnless { isLottery },
            daysRemaining = days,
            // 出どころ(施策全体か店舗固有か)は読者には関係ないため、レベル横断で連結して1セクションずつにする
            eligibleNotes = campaign.eligibleNotes + rule?.eligibleNotes.orEmpty(),
            ineligibleNotes = campaign.ineligibleNotes + rule?.ineligibleNotes.orEmpty(),
            storeListUrl = rule?.storeListUrl,
            warnings = buildList {
                if (days != null && days <= 3) add("残り${days}日")
                // Android ユーザーは自然に Google Pay でタッチしがちなので、対象外なら積極的に注意喚起する
                campaign.googlePayIneligibleWarning?.let { add(it) }
            },
            minPurchase = campaign.minPurchase,
            usageLimitText = usageLimitText(campaign),
            perTransactionCap = campaign.perTransactionCap,
            periodTotalCap = campaign.periodTotalCap,
            capNote = campaign.capNote,
            storeSearchUrl = if (campaign.storeScope == "external") campaign.storeSearchUrl else null,
            detailUrl = campaign.detailUrl,
            appLinks = appLinks,
            pointMultiplier = pointMultiplier,
            welcatsuApplied = welcatsuApplied,
            mayEndEarly = campaign.mayEndEarly,
            todayIsTarget = todayIsTarget,
            nextTargetDate = if (todayIsTarget) null else nextTargetDay(campaign, today),
        )
    }

    /**
     * 施策に紐づくカードを所有カードから解決する。card_id は id 一致、card_brand はブランド一致
     * (CardOverride での上書き反映後の実ブランド)。card_brand は「そのブランドのカードを1枚でも
     * 持っているか」の判定で、複数一致しても判定は施策につき1件(バッジはブランド名を出すため
     * どのカードが解決されたかは表示に影響しない)。
     */
    private fun resolveCard(campaign: Campaign): PaymentCard? = when {
        campaign.cardId != null -> data.cards.firstOrNull { it.id == campaign.cardId }
        campaign.cardBrand != null ->
            data.cards.firstOrNull { it.brand.equals(campaign.cardBrand, ignoreCase = true) }
        else -> null
    }

    /**
     * ブランド条件によりこの店を判定から除外するか。方針は「不確かな情報で実際より好条件を
     * 提示しない」: 実ブランドが Amex のときに加え、**未選択でもこのカードが Amex を取りうる**
     * (brands に Amex を含む、またはカタログに選択肢情報が無い)なら除外側に倒す。
     * ブランド未選択で好条件側に倒すと、実際は Amex のユーザーに対象外店を対象と誤提示してしまう。
     * card_brand 施策側は未選択だとマッチしない(resolveCard)ので、こちらも一貫して保守的。
     */
    private fun excludedByBrand(rule: MerchantRule, card: PaymentCard): Boolean {
        if (!rule.amexExcluded) return false
        if (card.brand.isNotBlank()) return card.brand.equals("Amex", ignoreCase = true)
        return card.brands.isEmpty() || card.brands.any { it.equals("Amex", ignoreCase = true) }
    }

    /**
     * カード施策の判定を返す。期間 + 対象日(recurrence)フィルタ適用済み。
     * store_scope == "managed" の施策のみ対象。ソートは judgeAll で一括。
     */
    fun judgeCards(merchant: Merchant, today: LocalDate): List<CampaignJudgment> =
        data.campaigns
            .filter { campaignStatus(it, today) == CampaignStatus.ACTIVE && isTargetDay(it, today) }
            .filter { it.storeScope == "managed" }
            .filter { it.paymentMethodId == null }
            .mapNotNull { campaign ->
                val rule = campaign.ruleFor(merchant) ?: return@mapNotNull null
                val card = resolveCard(campaign) ?: return@mapNotNull null
                if (excludedByBrand(rule, card)) return@mapNotNull null
                // 施策側の率(店舗別の上書きがあればそちら)。promotion では施策の率がカードの常設
                // 実効率より優先(逆にすると常設7%が期間限定10%を上書きしてしまう)。card_program は
                // 従来どおりユーザー設定の実効率を優先し、施策の率は既定値扱い。
                val campaignRate = rule.rateOverride ?: campaign.rateBase
                val usesCampaignRate =
                    campaign.campaignType == CampaignType.PROMOTION && campaignRate != null
                val effectiveRate = if (usesCampaignRate) {
                    campaignRate
                } else {
                    card.effectiveRateDefault ?: campaignRate ?: 0.0
                }
                // ブランド施策はどのカード会社のカードでも使えるため、バッジは特定カード名でなく
                // ブランド名(Visa 等)を出す。ポイント倍率もカード固有の話なので出さない
                val isBrandCampaign = campaign.cardBrand != null
                buildJudgment(
                    campaign = campaign,
                    rule = rule,
                    badgeLabel = campaign.cardBrand ?: card.cardName,
                    effectiveRate = effectiveRate,
                    discountAmount = campaign.discountAmount,
                    pointMultiplier = card.pointMultiplier.takeUnless { isBrandCampaign },
                    // ウエル活倍率はカードの実効率にだけ掛かっている。施策側の率を採用したときに
                    // 「ウエル活利用時の実質還元率」の注記が出ると誤りなのでフラグを落とす
                    welcatsuApplied = card.welcatsuApplied && !usesCampaignRate && !isBrandCampaign,
                    // Google Pay が還元対象と公式が明記している施策だけウォレット起動リンクを出す
                    appLinks = listOfNotNull(campaign.walletAppLink),
                    today = today,
                )
            }

    /**
     * QR 決済の判定を返す。ユーザーが利用中の QR 決済でフィルタ済み。
     * store_scope == "managed" のみ。期間 + 対象日(recurrence)フィルタ適用済み。
     */
    fun judgeQr(merchant: Merchant, today: LocalDate, enabledQrIds: Set<String>): List<CampaignJudgment> =
        data.campaigns
            .filter { campaignStatus(it, today) == CampaignStatus.ACTIVE && isTargetDay(it, today) }
            .filter { it.storeScope == "managed" }
            .filter { it.paymentMethodId != null && it.paymentMethodId in enabledQrIds }
            .mapNotNull { campaign ->
                val rule = campaign.ruleFor(merchant) ?: return@mapNotNull null
                val qr = qrPaymentMap[campaign.paymentMethodId] ?: return@mapNotNull null
                buildJudgment(
                    campaign = campaign,
                    rule = rule,
                    badgeLabel = qr.name,
                    effectiveRate = rule.rateOverride ?: campaign.rateBase,
                    discountAmount = campaign.discountAmount,
                    pointMultiplier = null,
                    welcatsuApplied = false,
                    appLinks = qr.appLinks,
                    today = today,
                )
            }

    /**
     * カード + QR をまとめた包括判定。
     */
    fun judgeAll(merchant: Merchant, today: LocalDate, enabledQrIds: Set<String> = emptySet()): JudgmentResult {
        val all = (judgeCards(merchant, today) + judgeQr(merchant, today, enabledQrIds))
            .sortedWith(
                compareBy<CampaignJudgment> { it.discountAmount != null }
                    .thenByDescending { it.effectiveRate ?: 0.0 }
                    .thenByDescending { it.discountAmount ?: 0 },
            )
        val bestOption = determineBest(all)
        return JudgmentResult(all, bestOption)
    }

    private fun determineBest(judgments: List<CampaignJudgment>): BestPaymentOption? {
        // 抽選は確定還元でないため比較に載せない(buildJudgment で率を null にしているが意図を明示)。
        // 対象商品限定(product_scope)も店の全商品には効かないため載せない(対象商品を買わない人に
        // 「この店は30%」と誤提示しないため。#43)
        val best = judgments
            .filter { it.benefitType != BenefitType.LOTTERY }
            .filter { it.campaign.productScope == null }
            .filter { it.effectiveRate != null && it.discountAmount == null }
            .maxByOrNull { it.effectiveRate!! }
            ?: return null
        return BestPaymentOption(
            method = best.badgeLabel,
            rate = best.effectiveRate,
            discountAmount = best.discountAmount,
            benefitType = best.benefitType,
            isTimeLimited = best.campaign.periodEnd != null,
            daysRemaining = best.daysRemaining,
        )
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
