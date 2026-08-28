package com.ktakjm.poikatsu.domain

import com.ktakjm.poikatsu.data.Attribution
import com.ktakjm.poikatsu.data.Campaign
import com.ktakjm.poikatsu.data.ExcludedStorePair
import com.ktakjm.poikatsu.data.Merchant
import com.ktakjm.poikatsu.data.MerchantRule
import com.ktakjm.poikatsu.data.PaymentCard
import com.ktakjm.poikatsu.data.PointCurrency
import com.ktakjm.poikatsu.data.PointMultiplier
import com.ktakjm.poikatsu.data.PoikatsuData
import com.ktakjm.poikatsu.data.QrPayment
import com.ktakjm.poikatsu.data.Recurrence
import com.ktakjm.poikatsu.data.StoreScope
import com.ktakjm.poikatsu.util.JapaneseText
import java.time.LocalDate
import java.time.format.DateTimeFormatter

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
    /** 一致した公式リストの店舗名。網羅リストの「掲載なし=対象外」(#64)では INELIGIBLE でも null */
    val matched: String?,
    val updatedDate: String,
    val dateIsOfficial: Boolean,
    val sourceUrl: String?,
    /** 網羅リスト(list_is_exhaustive)由来の判定か。UI の理由文の出し分けに使う */
    val listIsExhaustive: Boolean = false,
)

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
 * false は終了予定の無い常設施策(card_program、常設 promotion、終了日なしのカスタム)。
 * おトクタブの「常設」セクションへの振り分けにもこの値を使う。
 */
val Campaign.isTimeLimited: Boolean get() = periodEnd != null || mayEndEarly

/**
 * 施策全体が「対象のお店のみ」(特定店舗限定)バッジの対象か。全 merchant_rule が網羅リスト
 * (list_is_exhaustive。#64)のときだけ true にする。any でなく all なのは、J-POINTパートナーの
 * ように一部チェーンだけが網羅リストの施策に施策単位でバッジを付けると、他チェーンでは
 * 全店対象なのに「対象のお店のみ」と過剰に読めてしまうため。チェーン単位の表示
 * (お店タブの判定カード)はこの値でなく、そのチェーンの rule 側の網羅性で判定する。
 */
val Campaign.allStoreListsExhaustive: Boolean
    get() = merchantRules.isNotEmpty() &&
        merchantRules.all { it.officialStoreList?.listIsExhaustive == true }

/**
 * 店舗別レート(rate_override)が rate_base と併せて2値以上か。施策全体ビュー(おトクタブの
 * 一覧・詳細)は最大値の率を表示するため、true なら「最大」を冠して一律の率と誤認されない
 * ようにする(エポス提示優待のビッグエコー30%/ジャンカラ20%等)。お店タブ・地図の判定は
 * その店の実際の率を出すので使わない。
 */
val Campaign.storeRatesVary: Boolean
    get() = (merchantRules.mapNotNull { it.rateOverride } + listOfNotNull(rateBase)).distinct().size > 1

/** [resolveCardCampaignRate] の結果。effectiveRate は名目率(円換算前)で、usesCardRate は率の出どころ(カードの率を実際に出したか)を示す */
data class ResolvedCardRate(
    val effectiveRate: Double?,
    /** カードの実効率を表示したか(施策の率でも定額でもなく)。card=null のフォールバック時は false */
    val usesCardRate: Boolean,
)

/**
 * カード施策(card_id 持ち)の表示レート解決。judgeCards(お店/地図)とおトクタブの一覧・詳細
 * (MainViewModel)で共有し、率の優先基準がずれないようにする(店舗非依存。店舗別の
 * rate_override は呼び出し側が [rateOverride] で渡す)。返すのは**名目率**で、1pt 価値・
 * 条件付き倍率の円換算は呼び出し側がスコア層([effectiveValueRate])で一度だけ掛ける(#13):
 * - promotion で施策に率がある → 施策の率(逆にするとカードの常設7%が期間限定10%を上書きする)
 * - 提示のみ施策(presentation_only。#80)で施策に率がある → 施策の率。常設 card_program でも
 *   カードの通常還元率を採らない(エポス優待でカードの0.5%が出て提示特典10%OFFが消えるため)
 * - card_program で店舗別レート(rate_override)がある → [scaledStoreRate](店舗別レートに
 *   ユーザー設定のクラス加算を合成した名目率)。JCB J-POINTパートナーのような
 *   「1施策内で店舗ごとに率が異なる」常設プログラム用(#52)。店舗指定のない施策全体ビュー
 *   (おトクタブ一覧・詳細サマリー)では施策の最大値(rate_base)を同じ式でスケールする——
 *   カードのカタログ既定値を使うと、エポスのように 1 カードに最大値の異なる店舗別レート施策が
 *   複数ぶら下がる場合に別施策の率が出てしまう(#59: カラオケ館 30% OFF が 2.5% 表示になる不具合)
 * - card_program 等(rate_override なし) → カードの実効率(ユーザー設定の手入力率・クラス加算を
 *   マージ済みの名目率)。card が null(未所有カードの施策をおトクタブで見る場合)は
 *   施策側の率へフォールバック
 * - 定額施策と「率も定額も無い」promotion には率を出さない(率の捏造・ソート崩れ防止)
 */
fun resolveCardCampaignRate(
    campaign: Campaign,
    card: PaymentCard?,
    rateOverride: Double? = null,
): ResolvedCardRate {
    val campaignRate = rateOverride ?: campaign.rateBase
    val usesCampaignRate = campaignRate != null &&
        (campaign.campaignType == CampaignType.PROMOTION || campaign.presentationOnly)
    val usesCardRate = !usesCampaignRate && campaign.discountAmount == null &&
        campaign.campaignType != CampaignType.PROMOTION && !campaign.presentationOnly
    val effectiveRate = when {
        usesCampaignRate -> campaignRate
        usesCardRate && card != null && rateOverride != null -> scaledStoreRate(rateOverride, card)
        // 店舗別レート施策の施策全体ビュー(店舗指定なし): 施策の最大値(rate_base)をスケールする。
        // 1カード1施策(JCB/dカード)の間は effective_rate_default(=rate_base)と同値
        usesCardRate && card != null && campaign.merchantRules.any { it.rateOverride != null } ->
            campaign.rateBase?.let { scaledStoreRate(it, card) } ?: card.effectiveRateDefault
        usesCardRate -> card?.effectiveRateDefault ?: campaignRate ?: 0.0
        else -> null
    }
    return ResolvedCardRate(effectiveRate, usesCardRate && card != null)
}

/**
 * rebate 施策の払い出し通貨の解決(#39)。優先順:
 * 1. 施策の明示(point_currency_id)
 * 2. プログラム帰属(point_program_id。提示施策はプログラム自体が払い出し先)
 * 3. card_id 施策 → カードの通貨(card_brand 施策で resolveCard がブランド一致で返すカードは
 *    「支払いに使うカード」であって払い出し元ではないため継承しない=明示必須)
 * 4. QR 施策 → サービスの通貨
 * rebate 以外(discount=即時割引・lottery)には通貨の概念が無いため常に null。
 * judgeCards/judgeQr(お店・地図)とおトクタブの施策詳細(MainViewModel)で共有する。
 */
fun payoutCurrency(
    campaign: Campaign,
    currencies: List<PointCurrency>,
    card: PaymentCard?,
    qr: QrPayment? = null,
): PointCurrency? {
    if (BenefitType.fromString(campaign.benefitType) != BenefitType.REBATE) return null
    val id = campaign.pointCurrencyId
        ?: when (val a = campaign.attribution) {
            is Attribution.Program -> a.id
            is Attribution.Card -> card?.pointCurrencyId
            is Attribution.Qr -> qr?.pointCurrencyId
            is Attribution.Brand, null -> null
        }
        ?: return null
    return currencies.firstOrNull { it.id == id }
}

/**
 * card_program の店舗別レート(rate_override。基準構成=カタログ既定クラス・1pt=既定価値の絶対%で
 * 収録)に、ユーザー設定のクラス加算を合成した名目率: (店舗別レート + クラス加算)。
 * 1pt 価値・条件付き倍率の円換算はここではなくスコア層(ExpectedValueScoring。#13)で掛ける
 * ——クラス加算はポイント数の加算なので、価値の乗算より先に足すこの順序が要る。
 * カタログの effective_rate_default = rate_override の最大値にしておくと(整合性テストで強制)、
 * 最大レート店の値がカードの実効率(マージ済みの名目率)と一致し、一覧の「最大○%」とも整合する。
 */
fun scaledStoreRate(rateOverride: Double, card: PaymentCard): Double =
    rateOverride + card.rateBonus

/**
 * 設定画面で還元率を手入力できるカードか。手入力に意味があるのは「単一率でユーザーごとに
 * 実際の率が違う」プログラム(SMCC/MUFG)だけ。クラスを持つカード(JCB)は率が設定からの
 * 導出値、店舗別レートプログラム(rate_override。JCB/dカード #52/#58)のカードは率が店舗ごとの
 * 収録値で決まり、どちらも設定の余地が無い——UI は還元率行自体を出さず、
 * マージ(UserDataMerge)も保存済みの手入力値を無視する。
 * 1pt 価値は通貨単位の設定(#13)でカードの属性ではないため、ここでは見ない。
 */
fun PaymentCard.allowsManualRate(campaigns: List<Campaign>): Boolean =
    cardClasses.isEmpty() && campaigns.none { c ->
        c.cardId == id && c.campaignType == CampaignType.CARD_PROGRAM &&
            c.merchantRules.any { it.rateOverride != null }
    }

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

// ---- recurrence(繰り返し日付条件) ----
// campaignStatus(期間の外枠)とは独立に「その日が対象日か」を判定する。「お店」「地図」の判定は
// 期間内かつ対象日のみ、おトクタブは期間内なら非対象日でも出して「次の対象日」を案内する。

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

data class JudgmentResult(
    val judgments: List<CampaignJudgment>,
    val bestOption: BestPaymentOption?,
    /**
     * ユーザーが「このお店では対象外」と登録したため判定から間引いた施策(#63)。
     * 判定詳細で「登録済み」として畳み表示し、その場で解除できるようにするために別枠で返す
     * (黙って消すと「施策がどこに行ったか」が分からない)。bestOption・一覧の集計には含まれない。
     */
    val excludedJudgments: List<CampaignJudgment> = emptyList(),
    /**
     * 提示のみ施策(presentation_only。カード現物提示 #80・プログラム会員提示 #39)。
     * 支払い方法の選択肢ではない(提示しつつ別の高還元手段で払うのが最適解)ため、判定リストと
     * 分けて「あわせて提示」の並記枠に出し、bestOption の比較にも載せない。
     */
    val presentationJudgments: List<CampaignJudgment> = emptyList(),
    /**
     * 提示スタック合算(#13。決済分 + 併用可能な提示分の実質%)。bestOption が定率かつ
     * presentationJudgments に合算可能な提示が無ければ null([stackedRate] 参照)。
     */
    val stackedRate: StackedRate? = null,
    /**
     * 定額特典のアドバイス(#13。「{下限}円以上{分岐額}円未満のお買い物なら{手段} {N}円引き」)。
     * 最大おトク率バナーの2行目に出す。損益分岐額の付いた定額判定が無ければ null([fixedBenefitAdvice] 参照)。
     */
    val fixedAdvice: FixedBenefitAdvice? = null,
)

/**
 * 一覧(検索・近くリスト・地図プレビュー)に出す「最良特典」ラベル。
 * 定率の最大(bestOption)があればそれを、定額特典しか無いチェーンでは判定リスト先頭
 * (judgeAll のソートで定額同士は金額降順)の特典を整形する。
 * 定額は購入額に依存し定率と比較できないため、比較ポリシー(determineBest)は変えず
 * 見せ方だけをラベル化する(定額のみのチェーンが「0%」表示になる問題への対処。#29)。
 * 対象商品限定(product_scope)・提示のみ(presentation_only)の特典しか無いチェーンは
 * 「(対象商品)」「(提示のみ)」を付記し、支払うだけで全商品に効く率と誤認されないようにする(#43/#80)。
 */
fun JudgmentResult.bestBenefitLabel(): BenefitLabel? {
    bestOption?.let { return formatBenefit(it.benefitType, it.rate, it.discountAmount) }
    judgments.filter { it.campaign.productScope == null }
        .firstNotNullOfOrNull { formatBenefit(it.benefitType, it.effectiveRate, it.discountAmount) }
        ?.let { return it }
    // 最良比較から分離した施策しか無いチェーン(対象商品限定・提示のみ=並記枠)。
    // judgeAll のソート先頭(率の高い順)を採り、付記はその施策のフラグで選ぶ
    // (両立時はより限定の強い「対象商品」を優先)
    return (judgments + presentationJudgments).firstNotNullOfOrNull { j ->
        formatBenefit(j.benefitType, j.effectiveRate, j.discountAmount)?.let { it to j.campaign }
    }?.let { (label, campaign) ->
        val note = if (campaign.productScope != null) "(対象商品)" else "(提示のみ)"
        BenefitLabel(label.value, "${label.suffix}$note")
    }
}

/**
 * POI 名の照合結果。どの merchant(系列)のどの看板(業態)に一致したか。
 * 代表看板(merchant の name/reading/aliases に一致)は bannerId = merchant.id。
 */
data class StoreMatch(
    val merchant: Merchant,
    val bannerId: String,
    val bannerName: String,
)

/**
 * お店タブ検索のヒット 1 件。bannerId = null は「系列(グループ)としてのヒット」
 * (代表看板のキーに一致・カテゴリのみの絞り込み)で、判定はグループ視点(全ルール)になる。
 * 非 null は傘下看板のキーに一致したヒットで、表示ラベル・判定は看板単位になる。
 */
data class SearchHit(
    val merchant: Merchant,
    val bannerId: String? = null,
    val bannerName: String? = null,
)

class JudgmentEngine(private val data: PoikatsuData) {

    /** 照合キー1束(merchant × 看板)。代表看板は bannerId = merchant.id */
    private data class IndexEntry(
        val merchant: Merchant,
        val bannerId: String,
        val bannerName: String,
        val keys: List<String>,
    )

    private fun buildKeys(name: String, reading: String, aliases: List<String>): List<String> =
        buildList {
            add(JapaneseText.normalize(name))
            if (reading.isNotBlank()) add(JapaneseText.normalize(reading))
            aliases.forEach { add(JapaneseText.normalize(it)) }
        }.distinct()

    // 代表看板を先頭に置く(検索スコアが同点のとき代表看板ヒットを優先し、グループ視点で開くため)
    private val searchIndex: List<IndexEntry> = data.merchants.flatMap { m ->
        buildList {
            add(IndexEntry(m, m.id, m.name, buildKeys(m.name, m.reading, m.aliases)))
            m.banners.forEach { b ->
                add(IndexEntry(m, b.id, b.name, buildKeys(b.name, b.reading, b.aliases)))
            }
        }
    }

    private val qrPaymentMap: Map<String, QrPayment> =
        data.qrPayments.associateBy { it.id }

    /**
     * カテゴリ一覧(データ定義順)。「その他」だけは常に末尾へ送る(雑多カテゴリが
     * チップ列の途中に挟まらないように。定義順ソートは安定なので他の並びは維持される)
     */
    val categories: List<String> = data.merchants.map { it.category }.distinct()
        .sortedBy { it == MISC_CATEGORY }

    /**
     * 店名とカテゴリで検索する。カテゴリ未選択(空セット)は全カテゴリ扱い。
     * 店名が空でもカテゴリが選択されていればそのカテゴリの全店舗(系列単位)を返す。
     * 店名検索は前方一致を部分一致より優先する。傘下看板のキーにも一致し、その場合は
     * ヒットに看板情報が付く(1 merchant につき最良の 1 ヒットに集約する)。
     */
    fun search(query: String, selectedCategories: Set<String> = emptySet()): List<SearchHit> {
        val pool = if (selectedCategories.isEmpty()) searchIndex
        else searchIndex.filter { it.merchant.category in selectedCategories }

        val q = JapaneseText.normalize(query)
        if (q.isBlank()) {
            // カテゴリのみの絞り込み(系列単位=代表看板の行だけ)。未選択なら何も表示しない
            return if (selectedCategories.isEmpty()) {
                emptyList()
            } else {
                pool.filter { it.bannerId == it.merchant.id }.map { SearchHit(it.merchant) }
            }
        }
        return pool.mapNotNull { entry ->
            val score = entry.keys.minOf { key ->
                when {
                    key.startsWith(q) -> 0
                    key.contains(q) -> 1
                    // 「マクドナルド渋谷店」のような具体店舗名入力でもチェーンにヒットさせる。
                    // 短いキー(OK等)の誤爆を避けるため3文字以上+単語境界判定
                    key.length >= 3 && containsAsWord(q, key) -> 2
                    else -> Int.MAX_VALUE
                }
            }
            if (score == Int.MAX_VALUE) null else entry to score
        }
            // merchant ごとに最良スコアの1エントリへ集約(同点は searchIndex 順=代表看板を優先)
            .groupBy { it.first.merchant.id }
            .map { (_, entries) -> entries.minBy { it.second } }
            .sortedWith(compareBy({ it.second }, { it.first.merchant.reading }))
            .map { (entry, _) ->
                if (entry.bannerId == entry.merchant.id) {
                    SearchHit(entry.merchant) // 代表看板ヒットはグループとしてのヒット扱い
                } else {
                    SearchHit(entry.merchant, entry.bannerId, entry.bannerName)
                }
            }
    }

    /** 入力がチェーン名そのもの(店舗名部分なし)かどうか。傘下看板の名前も含めて見る */
    fun isExactNameMatch(merchant: Merchant, query: String): Boolean {
        val q = JapaneseText.normalize(query)
        return searchIndex.any { it.merchant.id == merchant.id && q in it.keys }
    }

    /**
     * 地図POIの店舗名(例: "マクドナルド 渋谷駅前店")から該当チェーンを特定する。
     * 「ステーキガスト」が「ガスト」に誤マッチしないよう、一致したキーが最長のものを採用する。
     * 傘下看板(banners)のキーにも一致し、どの看板に一致したかを返す(判定の看板スコープと
     * 地図の業態絞り込みに使う)。
     */
    fun matchStore(storeName: String): StoreMatch? {
        val normalizedName = JapaneseText.normalize(storeName)
        return searchIndex.mapNotNull { entry ->
            val best = entry.keys.filter { key -> isMatchableKey(key) && containsAsWord(normalizedName, key) }
                .maxOfOrNull { it.length }
            if (best == null) null else entry to best
        }.maxByOrNull { it.second }?.first?.let { StoreMatch(it.merchant, it.bannerId, it.bannerName) }
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
        // 傘下看板のキーも含めて剥がす(同じ店が看板名の別表記で重複登録されている場合に効く)
        val keys = searchIndex.filter { it.merchant.id == merchant.id }.flatMap { it.keys }
        // 長いキーから順に 1 回だけ除去(短いキーの部分一致で支店名を削りすぎないため)
        for (key in keys.filter { it.isNotBlank() }.sortedByDescending { it.length }) {
            val i = s.indexOf(key)
            if (i >= 0) s = s.removeRange(i, i + key.length)
        }
        return s.filterNot { it.isWhitespace() }
    }

    // ---- 期間フィルタ ----
    // recurrence(対象日)は含まない期間の外枠だけの判定(境界の定義は CampaignPeriod.kt)。おトクタブは
    // 期間内なら非対象日でも一覧に出す(「本日対象外」セクションで次の対象日を案内する)ため、対象日は isTargetDay で別判定する。

    fun campaignStatus(campaign: Campaign, today: LocalDate): CampaignStatus =
        campaignStatus(campaign.periodStart?.let { parseDate(it) }, campaign.periodEnd?.let { parseDate(it) }, today)

    fun daysRemaining(campaign: Campaign, today: LocalDate): Int? =
        daysRemaining(campaign.periodEnd?.let { parseDate(it) }, today)

    fun daysUntilStart(campaign: Campaign, today: LocalDate): Int? =
        daysUntilStart(campaign.periodStart?.let { parseDate(it) }, today)

    /** アクティブな campaign のみ返す */
    fun activeCampaigns(today: LocalDate): List<Campaign> =
        data.campaigns.filter { campaignStatus(it, today) == CampaignStatus.ACTIVE }

    /** もうすぐ開始の campaign を返す */
    fun upcomingCampaigns(today: LocalDate): List<Campaign> =
        data.campaigns.filter { campaignStatus(it, today) == CampaignStatus.UPCOMING }

    /**
     * アクティブな managed 施策が参照する merchant ID の集合(YOLP 検索対象の決定に使う)。
     * recurrence 施策は今日が対象日のときだけ含める(非対象日は判定にも出ないため検索しても無駄)。
     * 未所有カードの施策も除く(判定に出ない = その施策だけが参照する店を検索してもピンは立たず、
     * keyword ソース・取得コストの浪費になるため。#52 で未所有カード専用チェーンが増え顕在化)。
     * QR 施策は enabledQrIds をここでは知れないため従来どおり常に含める。
     */
    fun activeManagedMerchantIds(today: LocalDate): Set<String> =
        activeCampaigns(today)
            .filter { it.storeScope == StoreScope.MANAGED && isTargetDay(it, today) }
            .filter { it.attribution is Attribution.Qr || resolveCard(it) != null }
            .flatMap { it.merchantRules }
            .map { it.merchantId }
            .toSet()

    /**
     * この施策におけるそのチェーンのルール(なければ対象外)。bannerId を渡すとその看板(業態)に
     * 適用されるルールだけを返す(看板スコープ外なら null = その店は対象外)。
     * bannerId = null はグループ視点(スコープ付きルールも返し、内訳は注記で示す)。
     */
    private fun Campaign.ruleFor(merchant: Merchant, bannerId: String? = null): MerchantRule? =
        merchantRules.firstOrNull { it.merchantId == merchant.id && it.appliesToBanner(bannerId) }

    /**
     * 看板スコープ(banner_ids / ineligible_banner_ids)の内訳を「対象外・限定」の言い切りに合成する。
     * データ側の ineligible_notes と同じ「見落とすと損する情報」として warning 面に出す。
     */
    private fun bannerScopeNote(merchant: Merchant, rule: MerchantRule): String? = when {
        rule.bannerIds.isNotEmpty() ->
            rule.bannerIds.mapNotNull { merchant.bannerName(it) }
                .takeIf { it.isNotEmpty() }?.let { "対象は${it.joinToString("・")}のみ" }
        rule.ineligibleBannerIds.isNotEmpty() ->
            rule.ineligibleBannerIds.mapNotNull { merchant.bannerName(it) }
                .takeIf { it.isNotEmpty() }?.let { "${it.joinToString("・")}は対象外" }
        else -> null
    }

    private fun usageLimitText(campaign: Campaign): String? =
        campaign.usageLimitNote
            ?: campaign.usageLimit?.let { "お一人様${it}回まで" }

    /**
     * 判定 DTO の組み立て共通部。merchant/rule が非 null なら店舗文脈(お店・地図の判定カード)、
     * どちらも null なら施策全体ビュー(おトクタブの施策詳細。#85)として、注記の連結・網羅性の
     * 判定基準を切り替える。catalog は識別色の解決元(施策全体ビューは未所有カードも表示する
     * ため、所有カードのみの engine 保有データでなく表示用の全カタログを渡す)。
     */
    private fun buildJudgment(
        campaign: Campaign,
        merchant: Merchant?,
        rule: MerchantRule?,
        badgeLabel: String,
        effectiveRate: Double?,
        nominalRate: Double?,
        discountAmount: Int?,
        pointMultiplier: PointMultiplier?,
        payoutCurrencyName: String?,
        welcatsuApplied: Boolean,
        appLinks: List<AppLink>,
        today: LocalDate,
        catalog: PoikatsuData = data,
    ): CampaignJudgment {
        val days = daysRemaining(campaign, today)
        val benefitType = BenefitType.fromString(campaign.benefitType)
        val isLottery = benefitType == BenefitType.LOTTERY
        val todayIsTarget = isTargetDay(campaign, today)
        val isOverview = merchant == null
        return CampaignJudgment(
            campaign = campaign,
            badgeLabel = badgeLabel,
            brandColor = catalog.brandColorOf(campaign),
            benefitType = benefitType,
            // 抽選は確定還元ではないので率・額を持たせない(ソート・最良比較に混ざらないように)
            effectiveRate = effectiveRate.takeUnless { isLottery },
            nominalRate = nominalRate.takeUnless { isLottery },
            discountAmount = discountAmount.takeUnless { isLottery },
            daysRemaining = days,
            // 出どころ(施策全体か店舗固有か)は読者には関係ないため、レベル横断で連結して1セクションずつにする。
            // 施策全体ビュー専用の注記(収録範囲の説明等。#52)は overview でだけ連結する
            // (店舗判定カードには出さない。特定のお店を見ているユーザーには無関係な情報のため)
            eligibleNotes = campaign.eligibleNotes + rule?.eligibleNotes.orEmpty(),
            ineligibleNotes = campaign.ineligibleNotes +
                (if (isOverview) campaign.overviewIneligibleNotes else emptyList()) +
                rule?.ineligibleNotes.orEmpty() +
                listOfNotNull(if (merchant != null && rule != null) bannerScopeNote(merchant, rule) else null),
            storeListUrl = rule?.storeListUrl,
            warnings = buildList {
                if (isEndingSoon(days)) add("残り${days}日")
                // Android ユーザーは自然に Google Pay でタッチしがちなので、対象外なら積極的に注意喚起する
                campaign.googlePayIneligibleWarning?.let { add(it) }
            },
            minPurchase = campaign.minPurchase,
            usageLimitText = usageLimitText(campaign),
            perTransactionCap = campaign.perTransactionCap,
            periodTotalCap = campaign.periodTotalCap,
            capNote = campaign.capNote,
            storeSearchUrl = if (campaign.storeScope == StoreScope.EXTERNAL) campaign.storeSearchUrl else null,
            detailUrl = campaign.detailUrl,
            appLinks = appLinks,
            pointMultiplier = pointMultiplier,
            payoutCurrencyName = payoutCurrencyName,
            welcatsuApplied = welcatsuApplied,
            mayEndEarly = campaign.mayEndEarly,
            todayIsTarget = todayIsTarget,
            nextTargetDate = if (todayIsTarget) null else nextTargetDay(campaign, today),
            // 網羅性は店舗文脈ならそのチェーンの rule 単位、施策全体ビューなら施策単位(#64)
            exhaustiveStoreList = if (isOverview) {
                campaign.allStoreListsExhaustive
            } else {
                rule?.officialStoreList?.listIsExhaustive == true
            },
            // 施策全体ビューは最大値の率を出すため、店舗別レートがばらつく施策は「最大」を冠する(#81)
            rateVariesByStore = isOverview && campaign.storeRatesVary,
        )
    }

    /**
     * 施策に紐づくカードを所有カードから解決する。card_id は id 一致、card_brand はブランド一致
     * (CardOverride での上書き反映後の実ブランド)。card_brand は「そのブランドのカードを1枚でも
     * 持っているか」の判定で、複数一致しても判定は施策につき1件(バッジはブランド名を出すため
     * どのカードが解決されたかは表示に影響しない)。
     */
    private fun resolveCard(campaign: Campaign): PaymentCard? = when (val a = campaign.attribution) {
        is Attribution.Card -> data.cards.firstOrNull { it.id == a.id }
        is Attribution.Brand -> data.cards.firstOrNull { it.brand.equals(a.name, ignoreCase = true) }
        else -> null
    }

    /**
     * 施策の払い出し通貨を engine 保有データで解決する(#85)。[payoutCurrency] の引数
     * (card_id→所有カード / payment_method_id→QR)の引き当てを呼び出し側で複製しないための入口。
     */
    fun payoutCurrencyOf(campaign: Campaign): PointCurrency? =
        payoutCurrency(campaign, data.pointCurrencies, resolveCard(campaign), qrPaymentMap[campaign.paymentMethodId])

    /**
     * 店舗文脈なし(merchant/rule 抜き)の判定を組み立てる(#85)。おトクタブの施策詳細カード用で、
     * merchant 未特定のため店舗固有の注記・rate_override は乗らず、施策全体に一様に効く事実だけが出る。
     * 率の基準は judgeCards/judgeQr と同じ(resolveCardCampaignRate + 払い出し通貨の円換算)。
     * catalog には表示用の全カタログ(displayData)を渡す——おトクタブは未所有カードの施策も表示する
     * ため、識別色を所有カードのみの engine 保有データでは解決できない。
     */
    fun judgeCampaignOverview(
        group: List<Campaign>,
        today: LocalDate,
        catalog: PoikatsuData = data,
    ): List<CampaignJudgment> = group.map { campaign ->
        val qr = qrPaymentMap[campaign.paymentMethodId]
        // カード施策の率はお店タブと同じ基準で名目率を解決する: 所有カードの card_program は
        // ユーザー実効率、未所有は施策側の率へフォールバック。QR・自治体・ブランド施策
        // (cardId 無し)は従来どおり施策側の率。円換算はスコア層で一度だけ掛ける(#13)
        val resolved = if (campaign.cardId != null) resolveCardCampaignRate(campaign, resolveCard(campaign)) else null
        val currency = payoutCurrencyOf(campaign)
        val nominalRate = if (resolved != null) resolved.effectiveRate else campaign.rateBase
        // 提示施策(point_program_id)のバッジはプログラム名(dポイント等)を出す
        val programName = campaign.pointProgramId?.let { id ->
            data.pointCurrencies.firstOrNull { it.id == id }?.name
        }
        buildJudgment(
            campaign = campaign,
            merchant = null,
            rule = null,
            // ブランド施策はイシュアー不問なので、バッジは運営者でなくブランド名を出す
            badgeLabel = qr?.name ?: campaign.cardBrand ?: programName ?: campaign.operator,
            effectiveRate = effectiveValueRate(nominalRate, currency),
            nominalRate = nominalRate,
            discountAmount = campaign.discountAmount,
            pointMultiplier = currency?.pointMultiplier,
            payoutCurrencyName = currency?.name,
            // 「実質還元率」の適用時注記は、倍率が実際に掛かった率を表示したときだけ出す(#39)
            welcatsuApplied = currency?.multiplierEnabled == true &&
                currency.pointMultiplier != null && nominalRate != null,
            appLinks = qr?.appLinks.orEmpty().ifEmpty { listOfNotNull(campaign.walletAppLink) },
            today = today,
            catalog = catalog,
        )
    }

    /**
     * ブランド条件によりこの店を判定から除外するか。方針は「不確かな情報で実際より好条件を
     * 提示しない」: 実ブランドが除外リスト(ineligible_brands)に一致するときに加え、
     * **未選択でもこのカードが除外ブランドを取りうる**(brands に除外ブランドを含む、または
     * カタログに選択肢情報が無い)なら除外側に倒す。ブランド未選択で好条件側に倒すと、
     * 実際は除外ブランドのユーザーに対象外店を対象と誤提示してしまう。
     * card_brand 施策側は未選択だとマッチしない(resolveCard)ので、こちらも一貫して保守的。
     */
    private fun excludedByBrand(rule: MerchantRule, card: PaymentCard): Boolean {
        if (rule.ineligibleBrands.isEmpty()) return false
        fun excluded(brand: String) = rule.ineligibleBrands.any { it.equals(brand, ignoreCase = true) }
        if (card.brand.isNotBlank()) return excluded(card.brand)
        return card.brands.isEmpty() || card.brands.any(::excluded)
    }

    /**
     * カード施策の判定を返す。期間 + 対象日(recurrence)フィルタ適用済み。
     * store_scope == "managed" の施策のみ対象。ソートは judgeAll で一括。
     * bannerId はその看板(業態)としての判定(POI 照合・看板ヒットの検索)。null はグループ視点。
     */
    fun judgeCards(merchant: Merchant, today: LocalDate, bannerId: String? = null): List<CampaignJudgment> =
        data.campaigns
            .filter { campaignStatus(it, today) == CampaignStatus.ACTIVE && isTargetDay(it, today) }
            .filter { it.storeScope == StoreScope.MANAGED }
            .filter { it.attribution !is Attribution.Qr }
            .mapNotNull { campaign ->
                val rule = campaign.ruleFor(merchant, bannerId) ?: return@mapNotNull null
                val card = resolveCard(campaign) ?: return@mapNotNull null
                if (excludedByBrand(rule, card)) return@mapNotNull null
                // 率の優先基準(promotion=施策の率 / card_program=カードの実効率、定額・率なし
                // promotion は率を出さない)は resolveCardCampaignRate に集約(おトクタブと共有)。
                // 返るのは名目率で、円換算(1pt価値 × 条件付き倍率)はここで一度だけ掛ける(#13)
                val nominal = resolveCardCampaignRate(campaign, card, rule.rateOverride).effectiveRate
                // 換算係数は払い出し通貨の価値特性(#39/#13)。card_brand 施策は明示
                // (point_currency_id)が無い限り通貨が解決されず、換算もバッジも自然に出ない
                val currency = payoutCurrency(campaign, data.pointCurrencies, card)
                buildJudgment(
                    campaign = campaign,
                    merchant = merchant,
                    rule = rule,
                    // ブランド施策はどのカード会社のカードでも使えるため、バッジは特定カード名でなく
                    // ブランド名(Visa 等)を出す
                    badgeLabel = campaign.cardBrand ?: card.cardName,
                    effectiveRate = effectiveValueRate(nominal, currency),
                    nominalRate = nominal,
                    discountAmount = campaign.discountAmount,
                    pointMultiplier = currency?.pointMultiplier,
                    payoutCurrencyName = currency?.name,
                    // 「実質還元率」の適用時注記は、倍率が実際に掛かった率を表示したときだけ出す
                    welcatsuApplied = currency?.multiplierEnabled == true &&
                        currency.pointMultiplier != null && nominal != null,
                    // Google Pay が還元対象と公式が明記している施策だけウォレット起動リンクを出す
                    appLinks = listOfNotNull(campaign.walletAppLink),
                    today = today,
                )
            }

    /**
     * QR 決済の判定を返す。ユーザーが利用中の QR 決済でフィルタ済み。
     * store_scope == "managed" のみ。期間 + 対象日(recurrence)フィルタ適用済み。
     */
    fun judgeQr(
        merchant: Merchant,
        today: LocalDate,
        enabledQrIds: Set<String>,
        bannerId: String? = null,
    ): List<CampaignJudgment> =
        data.campaigns
            .filter { campaignStatus(it, today) == CampaignStatus.ACTIVE && isTargetDay(it, today) }
            .filter { it.storeScope == StoreScope.MANAGED }
            .filter { val a = it.attribution; a is Attribution.Qr && a.id in enabledQrIds }
            .mapNotNull { campaign ->
                val rule = campaign.ruleFor(merchant, bannerId) ?: return@mapNotNull null
                val qr = qrPaymentMap[campaign.paymentMethodId] ?: return@mapNotNull null
                // QR の rebate もサービスが稼ぐ通貨の円価値(1pt価値 × 倍率)で換算する(#39/#13)
                val currency = payoutCurrency(campaign, data.pointCurrencies, card = null, qr = qr)
                val nominal = rule.rateOverride ?: campaign.rateBase
                buildJudgment(
                    campaign = campaign,
                    merchant = merchant,
                    rule = rule,
                    badgeLabel = qr.name,
                    effectiveRate = effectiveValueRate(nominal, currency),
                    nominalRate = nominal,
                    discountAmount = campaign.discountAmount,
                    pointMultiplier = currency?.pointMultiplier,
                    payoutCurrencyName = currency?.name,
                    welcatsuApplied = currency?.multiplierEnabled == true &&
                        currency.pointMultiplier != null && nominal != null,
                    appLinks = qr.appLinks,
                    today = today,
                )
            }

    /**
     * プログラム会員提示型施策(point_program_id。#39)の判定を返す。dポイントカード提示 +3% のような
     * 「カード所有でなくプログラム会員かどうか」に紐づく施策で、会員登録済み(memberships)の
     * プログラムのものだけ判定する(所有カードのフィルタと同じ opt-in の構図)。
     * presentation_only 必須(整合性テストで強制)のため、judgeAll で並記枠へ振り分けられる。
     */
    fun judgePrograms(
        merchant: Merchant,
        today: LocalDate,
        memberships: Set<String>,
        bannerId: String? = null,
    ): List<CampaignJudgment> =
        data.campaigns
            .filter { campaignStatus(it, today) == CampaignStatus.ACTIVE && isTargetDay(it, today) }
            .filter { it.storeScope == StoreScope.MANAGED }
            .filter { val a = it.attribution; a is Attribution.Program && a.id in memberships }
            .mapNotNull { campaign ->
                val rule = campaign.ruleFor(merchant, bannerId) ?: return@mapNotNull null
                val program = data.pointCurrencies.firstOrNull { it.id == campaign.pointProgramId }
                    ?: return@mapNotNull null
                val currency = payoutCurrency(campaign, data.pointCurrencies, card = null)
                val nominal = rule.rateOverride ?: campaign.rateBase
                buildJudgment(
                    campaign = campaign,
                    merchant = merchant,
                    rule = rule,
                    badgeLabel = program.name,
                    effectiveRate = effectiveValueRate(nominal, currency),
                    nominalRate = nominal,
                    discountAmount = campaign.discountAmount,
                    pointMultiplier = currency?.pointMultiplier,
                    payoutCurrencyName = currency?.name,
                    welcatsuApplied = currency?.multiplierEnabled == true &&
                        currency.pointMultiplier != null && nominal != null,
                    appLinks = emptyList(),
                    today = today,
                )
            }

    /**
     * カード + QR をまとめた包括判定。bannerId はその看板(業態)としての判定(看板スコープの
     * 施策はスコープ外なら出ない)。null はグループ視点(全ルールを出し、内訳は注記で示す)。
     * excludedCampaignIds はこの店舗で間引く施策 id(#63。[excludedCampaignIdsFor] で算出)。
     * 該当施策は judgments でなく excludedJudgments に分けて返し、bestOption の比較にも載せない。
     * storeIneligibleCampaignIds は網羅リスト由来の店舗対象外(#64。
     * [exhaustiveListIneligibleCampaignIds] で算出)。ユーザー登録と違い解除の概念が無いため、
     * excludedJudgments には載せず看板スコープ外と同じ扱いで黙って間引く。
     * memberships は会員登録済みのポイントプログラム id(#39。提示型施策のフィルタ)。
     * 提示のみ施策(presentation_only)は判定リストと分けて presentationJudgments(並記枠)で返す。
     */
    fun judgeAll(
        merchant: Merchant,
        today: LocalDate,
        enabledQrIds: Set<String> = emptySet(),
        bannerId: String? = null,
        excludedCampaignIds: Set<String> = emptySet(),
        storeIneligibleCampaignIds: Set<String> = emptySet(),
        memberships: Set<String> = emptySet(),
    ): JudgmentResult {
        val all = (
            judgeCards(merchant, today, bannerId) +
                judgeQr(merchant, today, enabledQrIds, bannerId) +
                judgePrograms(merchant, today, memberships, bannerId)
            )
            .filterNot { it.campaign.id in storeIneligibleCampaignIds }
            .sortedWith(
                compareBy<CampaignJudgment> { it.discountAmount != null }
                    .thenByDescending { it.effectiveRate ?: 0.0 }
                    .thenByDescending { it.discountAmount ?: 0 },
            )
        val (excluded, remaining) = all.partition { it.campaign.id in excludedCampaignIds }
        val (presentation, active) = remaining.partition { it.campaign.presentationOnly }
        val bestOption = determineBest(active)
        // 定額判定に損益分岐額(#13 設計書 §6)を付与。比較相手は決済分のみの実質%(bestOption.rate。
        // stackedRate は提示分を含み両辺に等しく乗るため使わない)
        val annotated = bestOption?.rate?.let { bestRate ->
            active.map { j ->
                if (j.discountAmount != null) j.copy(breakevenAmount = breakevenAmount(j.discountAmount, bestRate))
                else j
            }
        } ?: active
        return JudgmentResult(
            annotated,
            bestOption,
            excluded,
            presentation,
            stackedRate(bestOption, presentation),
            fixedBenefitAdvice(annotated),
        )
    }

    /**
     * ユーザー登録の対象外ペア(#63)のうち、この店舗(poiName)に一致するもの。
     * 店舗の同定は重複排除と同じ「merchant + 支店名([normalizedBranch])」で行う。保存された
     * 店舗名も毎回正規化して比較するため、エイリアス・空白の表記ゆれや merchants.json の
     * キー変更に追従する。解除操作(この店舗の登録だけ消す)にも使う。
     */
    fun excludedPairsFor(
        merchant: Merchant,
        poiName: String,
        pairs: List<ExcludedStorePair>,
    ): List<ExcludedStorePair> {
        val relevant = pairs.filter { it.merchantId == merchant.id }
        if (relevant.isEmpty()) return emptyList()
        val branch = normalizedBranch(merchant, poiName)
        return relevant.filter { normalizedBranch(merchant, it.storeName) == branch }
    }

    /** [excludedPairsFor] の施策 id 集合。judgeAll の excludedCampaignIds に渡す */
    fun excludedCampaignIdsFor(
        merchant: Merchant,
        poiName: String,
        pairs: List<ExcludedStorePair>,
    ): Set<String> =
        excludedPairsFor(merchant, poiName, pairs).map { it.campaignId }.toSet()

    private fun determineBest(judgments: List<CampaignJudgment>): BestPaymentOption? {
        // 抽選は確定還元でないため比較に載せない(buildJudgment で率を null にしているが意図を明示)。
        // 対象商品限定(product_scope)も店の全商品には効かないため載せない(対象商品を買わない人に
        // 「この店は30%」と誤提示しないため。#43)。提示のみ(presentation_only)は judgeAll で
        // 並記枠(presentationJudgments)へ分離済みだが、意図(「最大おトク率: エポスカード10% OFF」は
        // 「エポスで払え」に読める。#80)の明示と防御を兼ねてここでも外す
        val best = judgments
            .filter { it.benefitType != BenefitType.LOTTERY }
            .filter { it.campaign.productScope == null && !it.campaign.presentationOnly }
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
            nominalRate = best.nominalRate,
        )
    }

    /**
     * 公式が対象/対象外を言い切っている店舗リスト(official_store_list)を持つ施策が
     * 1 つでもあれば、店舗単位の対象判定画面に遷移できる。
     * 網羅リスト(list_is_exhaustive)だけのチェーンも対象(#70): #64 では「対象店しか
     * 表示されないため導線不要」としたが、掲載のない店が理由なく消えたように見えて
     * 原因(公式に対象外)をユーザーが確かめる手段が無かったため、意図的に方針を変更した。
     */
    fun canCheckStore(merchant: Merchant): Boolean =
        data.campaigns.any { it.ruleFor(merchant)?.officialStoreList != null }

    /**
     * 特定店舗の判定を、公式リストを持つ施策ごとに返す。
     * 対象外(ineligible)を優先し、対象(eligible)、どちらにも無ければ要確認(UNKNOWN)。
     * 網羅リスト(list_is_exhaustive。#64)では「掲載なし=対象外」と断定する(matched = null)。
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
                list.listIsExhaustive -> StoreEligibility.INELIGIBLE to null
                else -> StoreEligibility.UNKNOWN to null
            }
            StoreVerdict(
                campaign = campaign,
                eligibility = eligibility,
                matched = matched,
                updatedDate = list.updatedDate,
                dateIsOfficial = list.dateIsOfficial,
                sourceUrl = list.sourceUrl,
                listIsExhaustive = list.listIsExhaustive,
            )
        }
    }

    /**
     * 近隣リスト用: その店舗が公式に「対象外」と明示されているか(店舗ごと除外)。
     * 対象(eligible)明示がある場合は除外扱いにしない。official_store_list が無いチェーンは常に false。
     * 網羅リストの「掲載なし=対象外」(matched = null)はここに含めない — その店に他の施策が
     * あり得るため、ピンごと消さず [exhaustiveListIneligibleCampaignIds] で施策単位に間引く(#64)。
     */
    fun isExcludedStore(merchant: Merchant, storeName: String): Boolean {
        val verdicts = checkStore(merchant, storeName)
        return verdicts.any { it.eligibility == StoreEligibility.INELIGIBLE && it.matched != null } &&
            verdicts.none { it.eligibility == StoreEligibility.ELIGIBLE }
    }

    /**
     * 網羅リスト(list_is_exhaustive)を持つ施策のうち、この店舗が対象と確認できない
     * (eligible に一致しない)ものの施策 id(#64)。judgeAll の storeIneligibleCampaignIds に
     * 渡して店舗単位で間引く。非網羅リスト(既定)は「掲載なし=要確認」のため間引かない。
     */
    fun exhaustiveListIneligibleCampaignIds(merchant: Merchant, storeName: String): Set<String> =
        checkStore(merchant, storeName)
            .filter { it.listIsExhaustive && it.eligibility == StoreEligibility.INELIGIBLE }
            .map { it.campaign.id }
            .toSet()

    companion object {
        private val dateFormatter = DateTimeFormatter.ISO_LOCAL_DATE
        fun parseDate(s: String): LocalDate = LocalDate.parse(s, dateFormatter)

        /** 雑多カテゴリ(merchants.json の category)。カテゴリ一覧では常に末尾に置く */
        private const val MISC_CATEGORY = "その他"
    }
}
