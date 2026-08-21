package com.ktakjm.poikatsu.domain

import com.ktakjm.poikatsu.data.MIN_PURCHASE_SCOPE_TRANSACTION
import com.ktakjm.poikatsu.data.PointBalance
import com.ktakjm.poikatsu.data.PointCurrency
import com.ktakjm.poikatsu.data.PointMultiplier
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.ceil

// 期待価値スコア(#13): 円換算の実質還元率。円価値換算はこのファイルに一本化する
// (マージ層とエンジン層で同じ係数を掛ける二重適用の罠を防ぐ。設計書 §2)。

/** 通貨価値係数 = 1pt価値(円) × 有効な条件付き倍率(ウエル活等)。通貨不明(null)は等価=1.0 */
fun currencyValueFactor(currency: PointCurrency?): Double {
    if (currency == null) return 1.0
    val factor = currency.takeIf { it.multiplierEnabled }?.pointMultiplier?.factor ?: 1.0
    return currency.valueYen * factor
}

/**
 * 1pt 価値と倍率の**両方**が効いているときだけ、合成後の 1pt 価値(円)を返す(#83)。
 * この 2 つは [currencyValueFactor] で積になるため、乗り継ぎルートで 1pt=1.3円 に設定した
 * 通貨の倍率も ON だと 1.43 になる。重ねられるルートは実在するので禁止も排他もしないが、
 * 設定画面が両方を別の行に出すだけだとユーザーが気付けないため、合成値を注記に出す。
 * どちらかが中立(倍率 OFF・factor 1.0・1pt=1円)なら説明する数字が無いので null。
 */
fun compositeValueYen(currency: PointCurrency): Double? {
    val factor = currency.takeIf { it.multiplierEnabled }?.pointMultiplier?.factor ?: return null
    if (factor == 1.0 || currency.valueYen == 1.0) return null
    return currencyValueFactor(currency)
}

/** 実質%(スコア) = 名目還元率 × 通貨価値係数 */
fun effectiveValueRate(nominalRate: Double?, currency: PointCurrency?): Double? =
    nominalRate?.let { it * currencyValueFactor(currency) }

/**
 * 名目と実質が異なるときだけ「額面○%」の併記文を返す(UI の率表示・最大おトク率で共用)。
 * 主表示はユーザーが実際に得る価値=実質率で、収録上の名目率を添え書きに落とす
 * (当初は名目が主だったが、実機フィードバックで主従を逆転。#13)。
 */
fun nominalRateNote(nominalRate: Double?, effectiveRate: Double?): String? {
    if (nominalRate == null || effectiveRate == null) return null
    if (nominalRate == effectiveRate) return null
    return "額面${trimRate(nominalRate)}%"
}

/**
 * 倍率バッジの文言。`×{factor}` を併記するのは **[applied] が true のとき、つまり表示中の率に
 * その倍率が実際に掛かっているときだけ**(判定詳細の適用時注記と同じ不変条件)。
 * 選択肢を持つ通貨(Ponta の交換所 ×1.1/×1.5)では、倍率 OFF のまま既定値を併記すると
 * ユーザーが選んでいない片方の倍率を提示してしまうため。OFF でもバッジ自体は出す——
 * 「この通貨は条件次第で増価する」事実の告知で、率が動いていないことは倍率の非表示で伝わる。
 * バッジ文言が空の倍率定義・倍率なしは null(バッジを出さない)。
 */
fun multiplierBadgeLabel(multiplier: PointMultiplier?, applied: Boolean): String? {
    val label = multiplier?.badgeLabel?.takeIf { it.isNotBlank() } ?: return null
    return if (applied) "$label ×${trimRate(multiplier.factor)}" else label
}

/** 失効通知を出す残り日数のしきい値(この日数以内で表示) */
const val EXPIRY_NOTICE_DAYS = 30L

/** warning 強調に切り替える残り日数のしきい値 */
const val EXPIRY_WARN_DAYS = 7L

/**
 * 期間限定ポイントの失効通知 1 件(#13)。施策の有無・決済手段と独立に、判定結果画面へ
 * 「残り3日で失効する楽天ポイント 500pt あり」を出すための算出結果。
 * 施策開催店でのポイント払いは施策対象か確認が要るため、判定(最良比較)には効かせない(設計書 §4)。
 */
data class ExpiringPointNotice(
    val currencyId: String,
    val currencyName: String,
    val balancePt: Int,
    val expiryDate: LocalDate,
    val daysLeft: Long,
    /** 残り EXPIRY_WARN_DAYS 日以内(warning 系ロールで強調) */
    val warn: Boolean,
)

/** 失効日を過ぎたか(失効日当日までは利用可能)。設定画面の「失効済み」表示と通知の除外で共用 */
fun PointBalance.isExpired(today: LocalDate): Boolean {
    val expiry = runCatching { LocalDate.parse(expiryDate) }.getOrNull() ?: return false
    return today.isAfter(expiry)
}

/** 失効30日以内・残高ありの通知を失効日の近い順に返す。不正な日付・未知の通貨は黙って落とす */
fun expiringPointNotices(
    balances: Map<String, PointBalance>,
    currencies: List<PointCurrency>,
    today: LocalDate,
): List<ExpiringPointNotice> {
    val currencyById = currencies.associateBy { it.id }
    return balances.mapNotNull { (id, balance) ->
        val currency = currencyById[id] ?: return@mapNotNull null
        if (balance.balancePt <= 0) return@mapNotNull null
        val expiry = runCatching { LocalDate.parse(balance.expiryDate) }.getOrNull() ?: return@mapNotNull null
        val daysLeft = ChronoUnit.DAYS.between(today, expiry)
        if (daysLeft < 0 || daysLeft > EXPIRY_NOTICE_DAYS) return@mapNotNull null
        ExpiringPointNotice(
            currencyId = id,
            currencyName = currency.name,
            balancePt = balance.balancePt,
            expiryDate = expiry,
            daysLeft = daysLeft,
            warn = daysLeft <= EXPIRY_WARN_DAYS,
        )
    }.sortedBy { it.daysLeft }
}

/**
 * 提示スタック合算(#13 設計書 §5): 最良の決済手段の実質% + 併用可能な提示施策の実質%。
 * 異なる通貨の足し算は各判定が円換算済み(1pt価値 × 条件付き倍率)の実質%であるため正当
 * (1pt=1円の暗黙仮定を置かない)。合算は二重取り(決済1+提示N)まで。
 * 定額(discountAmount)・率なし(effectiveRate null)・対象商品限定(productScope)の提示は足さない。
 */
data class StackedRate(
    val totalRate: Double,
    val paymentRate: Double,
    val presentationRate: Double,
)

/** best が定率かつ合算可能な提示施策(実質% あり・定額でない・対象商品限定でない)が1つ以上あるときだけ返す */
fun stackedRate(best: BestPaymentOption?, presentation: List<CampaignJudgment>): StackedRate? {
    val paymentRate = best?.rate ?: return null
    val presentationRate = presentation
        .filter { it.discountAmount == null && it.campaign.productScope == null }
        .mapNotNull { it.effectiveRate }
        .sum()
    if (presentationRate <= 0.0) return null
    return StackedRate(
        totalRate = paymentRate + presentationRate,
        paymentRate = paymentRate,
        presentationRate = presentationRate,
    )
}

/**
 * rebate vs coupon の損益分岐額(#13 設計書 §6): この金額未満の買い物なら定額(割引・定額還元)が得。
 * 比較相手は最良の実質%(決済分のみ。提示分は定額を使う場合でも併用でき両辺に等しく乗るため除外)。
 * 10 円単位で切り上げ(端数の分岐点を「得」側に誤らせない保守側の丸め)。
 */
fun breakevenAmount(discountAmount: Int, bestRate: Double): Int? {
    if (bestRate <= 0.0) return null
    val raw = discountAmount * 100.0 / bestRate
    return (ceil(raw / 10.0) * 10.0).toInt()
}

/**
 * 最大おトク率バナーに出す定額特典のアドバイス 1 件(#13 実機フィードバック: カード内の
 * 小さな注記では気付けないため、バナーで「{下限}円以上{分岐額}円未満のお買い物なら
 * {決済手段} {N}円引き」の形で案内する)。
 */
data class FixedBenefitAdvice(
    /** 決済手段のラベル(判定カードのバッジと同じ) */
    val method: String,
    /** 定額の種別(discount=円引き / rebate=円還元。表記は formatBenefit に委ねる) */
    val benefitType: BenefitType,
    val discountAmount: Int,
    /**
     * 購入額の下限(円)。1決済ごとの最低購入額(min_purchase_scope=transaction)があるときだけ。
     * 期間合計条件は1回の買い物の下限にならないため範囲に含めない。null = 下限なし
     */
    val minPurchase: Int?,
    /** 購入額の上限(円)。この金額未満なら定率の最良より定額が得(=breakevenAmount) */
    val breakevenAmount: Int,
)

/**
 * 損益分岐額の付いた定額判定から、バナーに出す 1 件を選ぶ。範囲が空(下限 >= 分岐額)の
 * ものは除外し、割引額が最大のもの(同額なら下限が緩いもの)を代表にする。
 * 定率の最良が無いチェーン(breakevenAmount 未付与)は対象外 — 比較相手が無ければ
 * 定額が得なのは自明なため、UI では何も注記しない。
 */
fun fixedBenefitAdvice(judgments: List<CampaignJudgment>): FixedBenefitAdvice? =
    judgments.mapNotNull { j ->
        val breakeven = j.breakevenAmount ?: return@mapNotNull null
        val discount = j.discountAmount ?: return@mapNotNull null
        val minPurchase = j.campaign.minPurchase
            ?.takeIf { j.campaign.minPurchaseScope == MIN_PURCHASE_SCOPE_TRANSACTION }
        if (minPurchase != null && minPurchase >= breakeven) return@mapNotNull null
        FixedBenefitAdvice(
            method = j.badgeLabel,
            benefitType = j.benefitType,
            discountAmount = discount,
            minPurchase = minPurchase,
            breakevenAmount = breakeven,
        )
    }.maxWithOrNull(compareBy({ it.discountAmount }, { -(it.minPurchase ?: 0) }))
