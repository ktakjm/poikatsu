package com.ktakjm.poikatsu.domain

import com.ktakjm.poikatsu.data.Attribution
import com.ktakjm.poikatsu.data.Campaign
import com.ktakjm.poikatsu.data.Merchant
import com.ktakjm.poikatsu.data.MerchantRule
import com.ktakjm.poikatsu.data.StoreScope

/**
 * 施策表示の純ロジック(#87)。タイトル解決・対象チェーン列挙・最良特典計算など、
 * 画面へ出す文言の組み立てのうち Compose 非依存の部分(表示用の Composable 部品は
 * ui/UiHelpers.kt 側)。domain は純 Kotlin 維持のため Android/Compose を import しない。
 */

/**
 * 段階制(rate_rules)の施策か。rate_base は段階の最大値なので、表示する率には「最大」を冠する
 * (施策詳細 BenefitDisplay とおトクタブ [campaignGroupMaxBenefit] の共通条件。他の「最大」条件は
 * 施策単位/グループ単位で異なるため各所で追加する)
 */
val Campaign.hasTieredRate: Boolean get() = rateRules.isNotEmpty()

// ---- タイトル解決 ----

/**
 * merchant のグループ名(未設定なら「{代表看板}グループ」)。検索結果の従属表示・
 * 地図の束ね見出し・判定詳細の業態行・施策詳細の対象ラベルで共用する。
 */
fun groupLabelOf(merchant: Merchant): String =
    merchant.groupLabel ?: "${merchant.name}グループ"

/**
 * 自治体施策の併催グループ(県全域+市区町村の同時開催)の地域併記ラベル。
 * 全施策が自治体施策で地域名が複数あるときだけ「千葉県・千葉市」(県全域は県名が region.name。
 * 県→市区町村の順)を返し、それ以外(単独・自治体以外を含む)は null。
 * 地図のお知らせピルと施策詳細タイトルで共用し、ピル「千葉市」/タイトル「千葉県」のように
 * 同じグループの文言が食い違わないようにする。
 */
fun municipalRegionsLabel(campaigns: List<Campaign>): String? {
    if (campaigns.isEmpty() || !campaigns.all { it.campaignType == CampaignType.MUNICIPAL }) return null
    val regions = campaigns.mapNotNull { it.region }
    val names = (regions.filter { it.isPrefectureWide } + regions.filterNot { it.isPrefectureWide })
        .map { it.name }
        .distinct()
    return if (names.size >= 2) names.joinToString("・") else null
}

/**
 * 施策グループ(同一カード/詳細にまとめて出す施策列)の表示タイトル。
 * 自治体の併催グループ(地図のお知らせピル発)は地域併記([municipalRegionsLabel])、
 * 発行体束ね(#81)は「{operator} 優待・特典」(施策ごとの display_name はサブ行・内訳側で出す)、
 * それ以外は先頭施策のタイトル(単数版の [campaignGroupDisplayTitle])。
 */
fun campaignGroupDisplayTitle(group: List<Campaign>, merchants: Map<String, Merchant>): String =
    municipalRegionsLabel(group)
        ?: (if (isCardProgramBundle(group)) "${group.first().operator} 優待・特典" else null)
        ?: campaignGroupDisplayTitle(group.first(), merchants)

/**
 * キャンペーングループの表示タイトル。
 * 自治体: "都道府県名 自治体名"(県全域施策は県名のみ。「神奈川県 神奈川県」にしない)、
 * card_program: display_name → campaign.name(常設プログラムは固有名で呼ぶ。多チェーンでも
 * 「{先頭チェーン} 他Nチェーン」にしない)、
 * それ以外はフォールバック連鎖: display_name → 単一チェーンは看板名/merchant 名([singleMerchantTitle]) →
 * 複数チェーンは「{先頭チェーン} 他Nチェーン」(→ merchant_rules が無ければ campaign.name)。
 * 多チェーン施策が先頭 merchant 名だけで「1チェーンの施策」に見えないようにする。
 */
fun campaignGroupDisplayTitle(first: Campaign, merchants: Map<String, Merchant>): String =
    if (first.campaignType == CampaignType.MUNICIPAL) {
        val prefecture = first.region?.prefecture ?: ""
        val name = first.region?.name ?: first.name
        if (prefecture.isNotBlank() && name != prefecture) "$prefecture $name" else name
    } else if (first.campaignType == CampaignType.CARD_PROGRAM) {
        first.displayName ?: first.name
    } else {
        first.displayName ?: run {
            val chainIds = first.merchantRules.map { it.merchantId }.distinct()
            val head = chainIds.firstOrNull()?.let { merchants[it] }
            when {
                head == null -> first.name
                chainIds.size == 1 -> singleMerchantTitle(first, head)
                else -> "${head.name} 他${chainIds.size - 1}チェーン"
            }
        }
    }

/**
 * 単一チェーン施策のタイトル。看板スコープ(#69): 全ルールが `banner_ids` 持ちで対象看板が
 * 1 つに絞られていれば**その看板名**(福太郎限定クーポンを系列代表の「ツルハドラッグ」と
 * 出さない)。看板 2 つ以上・全看板(banner_ids 未指定)・一部除外は従来どおり merchant 名。
 * 判定詳細の「対象:」([ruleTargetLabels])が業態名で出すのと表記を揃える。
 */
private fun singleMerchantTitle(first: Campaign, merchant: Merchant): String {
    if (first.merchantRules.any { it.bannerIds.isEmpty() }) return merchant.name
    val bannerId = first.merchantRules.flatMap { it.bannerIds }.distinct().singleOrNull()
    return bannerId?.let { merchant.bannerName(it) } ?: merchant.name
}

// ---- 対象チェーン列挙 ----

/**
 * 施策詳細の「対象:」に出すラベル一覧(#60)。managed 施策の merchant_rules を解決する:
 * - `banner_ids` で看板(業態)を限定したルールは**業態名**(「杏林堂だけ」のカスタムで
 *   「ツルハドラッグ」と出す誤解を避ける)
 * - 業態を持つ系列の全業態ルールは**グループ名**(「マツモトキヨシ」だと業態名なのか
 *   グループなのか区別できないため。一部除外(`ineligible_banner_ids`)もグループ扱いで、
 *   除外の内訳は判定カードの注記が示す)
 * - 業態を持たない merchant は従来どおり merchant 名
 */
fun campaignTargetLabels(
    campaigns: List<Campaign>,
    merchants: Map<String, Merchant>,
): List<String> = campaignTargetLabelGroups(campaigns, merchants).flatMap { it.labels }.distinct()

/** merchant_rule 1 件分の対象ラベル(業態限定は業態名 / 業態持ちの全業態はグループ名 / それ以外は merchant 名) */
private fun ruleTargetLabels(rule: MerchantRule, merchants: Map<String, Merchant>): List<String> {
    val merchant = merchants[rule.merchantId] ?: return emptyList()
    return when {
        rule.bannerIds.isNotEmpty() ->
            rule.bannerIds.mapNotNull { merchant.bannerName(it) }.ifEmpty { listOf(merchant.name) }
        merchant.banners.isNotEmpty() -> listOf(groupLabelOf(merchant))
        else -> listOf(merchant.name)
    }
}

/** 率別の対象ラベルグループ(#52)。rate = null は「率の区別なし」(単一グループ=従来表示) */
data class TargetLabelGroup(val rate: Double?, val labels: List<String>)

/**
 * 施策詳細の「対象:」を率別にグルーピングしたラベル一覧(#52)。J-POINT パートナーのように
 * 1 施策内で店舗ごとに率が異なる場合、「最大10%」+全店列挙だと低率店(セブン 1.5% 等)も
 * 最大率と誤読されるため、率ごとに分けて見せる。storeRates は merchant_id → 実効率
 * (UiState.campaignStoreRates。所有カードならクラス加算・1pt価値の合成済み)。
 * 率が 2 種類未満なら従来どおり単一グループ(rate = null)に畳む。
 * 率の無いルールが混在する場合は rate = null のグループとして末尾に置く。
 */
fun campaignTargetLabelGroups(
    campaigns: List<Campaign>,
    merchants: Map<String, Merchant>,
    storeRates: Map<String, Double> = emptyMap(),
): List<TargetLabelGroup> {
    val labeled = campaigns
        .filter { it.storeScope == StoreScope.MANAGED }
        .flatMap { it.merchantRules }
        .flatMap { rule -> ruleTargetLabels(rule, merchants).map { label -> label to storeRates[rule.merchantId] } }
    val distinctRates = labeled.mapNotNull { it.second }.distinct()
    if (distinctRates.size < 2) {
        return listOf(TargetLabelGroup(null, labeled.map { it.first }.distinct()))
            .filter { it.labels.isNotEmpty() }
    }
    val (rated, unrated) = labeled.groupBy { it.second }.entries.partition { it.key != null }
    return rated.sortedByDescending { it.key!! }
        .map { TargetLabelGroup(it.key, it.value.map { p -> p.first }.distinct()) } +
        unrated.map { TargetLabelGroup(null, it.value.map { p -> p.first }.distinct()) }
}

// ---- 最良特典計算・発行体束ね(#81) ----

/**
 * グループの最大還元率/特典テキスト(サマリーカード右側用)。抽選は比較に載せず「抽選」と表示する。
 * 表示する数字が「変動する率の最大値」のとき(店舗別 rate_override・条件別 rate_rules・
 * グループ内で率の異なる複数施策・rebate/discount の型混在)と、対象商品限定(product_scope。
 * 全商品には効かない)のときは「最大」を冠し、一律の率と誤認されないようにする。
 * [personalRates] に載っている施策(所有カードの card_program)は rate_base の代わりに
 * 円換算済みの実質率(1pt価値・倍率込み)で出す(お店タブの判定と同じ値になる)。
 *
 * 発行体束ね(#81)で rebate と discount が混在するグループは、%が大きい方の型だけを代表で
 * 出す(2.5%還元と30%OFFを「最大30%還元」に合成すると割引率が還元率に化ける)。同率は
 * 会計にその場で効く OFF を優先する。
 */
fun campaignGroupMaxBenefit(
    campaigns: List<Campaign>,
    personalRates: Map<String, Double> = emptyMap(),
): String? {
    val comparable = campaigns.filter { BenefitType.fromString(it.benefitType) != BenefitType.LOTTERY }
    if (comparable.isEmpty()) return "抽選"
    val byType = comparable.groupBy { BenefitType.fromString(it.benefitType) }
    val subset = if (byType.size == 1) {
        byType.values.first()
    } else {
        val rebateMax = byType[BenefitType.REBATE]?.let { effectiveRates(it, personalRates).maxOrNull() }
        val discountMax = byType[BenefitType.DISCOUNT]?.let { effectiveRates(it, personalRates).maxOrNull() }
        // 率を持たない型(定額のみ等)は%比較に載らない。両型とも率が無ければ OFF 側に倒す
        if ((rebateMax ?: Double.NEGATIVE_INFINITY) > (discountMax ?: Double.NEGATIVE_INFINITY)) {
            byType.getValue(BenefitType.REBATE)
        } else {
            byType.getValue(BenefitType.DISCOUNT)
        }
    }
    val type = BenefitType.fromString(subset.first().benefitType)
    val allRates = effectiveRates(subset, personalRates)
    val maxRate = allRates.maxOrNull()
    val maxDiscount = subset.mapNotNull { it.discountAmount }.maxOrNull()
    val label = formatBenefit(type, maxRate, maxDiscount)?.toString() ?: return null
    // 店舗別レートのばらつきは personalRates で allRates を実効率 1 値に絞った後も検知できるよう
    // 収録値(rate_override + rate_base)側で判定する(Campaign.storeRatesVary。施策詳細の「最大」と共通)
    val storeRatesVary = subset.any { it.storeRatesVary }
    val ratesVary = allRates.distinct().size > 1 || storeRatesVary || byType.size > 1 ||
        subset.any { it.hasTieredRate || it.productScope != null }
    return if (maxRate != null && ratesVary) "最大$label" else label
}

/**
 * 施策列の表示候補レート一覧。所有カードの card_program はユーザー実効率が最大値
 * (店舗別 rate_override はカードのクラス加算・1pt価値でこれ以下にスケールされる。#52)。
 * 収録値の rate_override を混ぜると 1pt価値 < 1円 等の設定時に実際より大きい「最大◯%」が
 * 出るため、personalRates に載っている施策は実効率だけを使う
 */
private fun effectiveRates(campaigns: List<Campaign>, personalRates: Map<String, Double>): List<Double> =
    campaigns.flatMap { c ->
        personalRates[c.id]?.let { return@flatMap listOf(it) }
        c.merchantRules.mapNotNull { it.rateOverride } +
            c.rateRules.map { it.rate } +
            listOfNotNull(c.rateBase)
    }

/**
 * 発行体束ね(#81)のグループか: 同一 card_id の常設 card_program が2件以上。
 * エポス優待のように1カードへ複数の常設施策がぶら下がる場合、おトクタブの一覧では
 * 1カードに束ねて出す([campaignGroupKey] の cardProgram 分岐で畳まれたグループ)。
 * 同一 point_program_id の常設プログラム提示施策(#39)も同じ扱い。
 * 1件だけのカード(dカード特約店等)は従来表示のまま。
 */
fun isCardProgramBundle(campaigns: List<Campaign>): Boolean {
    if (campaigns.size < 2 || campaigns.any { it.campaignType != CampaignType.CARD_PROGRAM }) return false
    // 「全施策が同一の card_id(または point_program_id)」= 帰属が単一の Card / Program に畳める
    val attribution = campaigns.mapTo(HashSet()) { it.attribution }.singleOrNull()
    return attribution is Attribution.Card || attribution is Attribution.Program
}

/**
 * 発行体束ねカードの内訳サブ行。「モンテローザ優待 / KEYUCA優待 / カラオケ館優待 ほか2件」。
 * 施策名は display_name から operator 接頭辞(「エポスカード 」)を除いた短縮形
 * (束ねタイトルが発行体名を出すため重複させない)。束ねグループ以外は null。
 */
fun cardProgramBundleSubtitle(campaigns: List<Campaign>): String? {
    if (!isCardProgramBundle(campaigns)) return null
    val names = campaigns.map { c ->
        (c.displayName ?: c.name).removePrefix("${c.operator} ").ifBlank { c.name }
    }
    val shown = names.take(3).joinToString(" / ")
    val rest = names.size - 3
    return if (rest > 0) "$shown ほか${rest}件" else shown
}
