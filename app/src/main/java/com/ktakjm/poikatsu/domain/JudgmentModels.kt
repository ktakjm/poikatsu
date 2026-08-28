package com.ktakjm.poikatsu.domain

import com.ktakjm.poikatsu.data.Campaign
import com.ktakjm.poikatsu.data.PointMultiplier
import java.time.LocalDate

// 判定結果の共有型(#90)。JudgmentEngine(判定)と ExpectedValueScoring(円換算・提示スタック・定額アドバイス)の
// 両方が使う DTO・列挙・ラベル整形をここに置き、依存を JudgmentModels ← ExpectedValueScoring ← JudgmentEngine の
// 一方向にする(以前は 2 ファイルが互いの型を参照していた)。JudgmentResult はスコアリングの結果型を
// 抱えるためエンジン側に残す。

/**
 * ある店舗に対する 1 施策分の判定結果。カード決済・QR 決済・キャンペーン詳細で共用する。
 * 各フィールドは null / 空ならカード側で非表示になるため、カード種別ごとの分岐は不要。
 */
data class CampaignJudgment(
    val campaign: Campaign,
    val badgeLabel: String,
    val brandColor: String?,
    val benefitType: BenefitType,
    /** 実質還元率(%)。名目率に払い出し通貨の円価値係数(1pt価値 × 条件付き倍率)を掛けた値(#13) */
    val effectiveRate: Double?,
    /** 名目還元率(円換算前)。[effectiveRate](実質%)と異なるとき UI が「実質○%相当」を併記する(#13) */
    val nominalRate: Double? = null,
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
    /**
     * rebate の払い出し通貨名(「還元: Pontaポイント」行に出す)。[payoutCurrency] が解決できた
     * ときだけ非 null で、倍率の有無とは独立。discount(即時割引)・lottery には通貨の概念が
     * 無いため常に null、カタログに `point_currency_id` の無い発行体(MUFG・エポス等)も null
     * ——誤った通貨名を出すより行を省く。
     */
    val payoutCurrencyName: String? = null,
    /**
     * 表示中の [effectiveRate] に条件付き倍率(ウエル活等)が実際に掛かっているか。
     * 払い出し通貨の倍率が有効で、率のある施策のときだけ true(適用時注記の表示条件)。
     */
    val welcatsuApplied: Boolean,
    /** 予算到達次第の早期終了があり得る施策か。true なら注記を出す */
    val mayEndEarly: Boolean = false,
    /** recurrence 施策で今日が対象日か。recurrence の無い施策は常に true */
    val todayIsTarget: Boolean = true,
    /** recurrence 施策で今日が非対象日のときの次の対象日。対象日当日・recurrence 無しは null */
    val nextTargetDate: LocalDate? = null,
    /**
     * 「対象のお店のみ」(網羅リスト #64)バッジ・注記を出すか。お店タブ(チェーン文脈あり)は
     * そのチェーンの rule の網羅性、おトクタブの施策詳細(チェーン非依存)は施策単位
     * ([allStoreListsExhaustive])で呼び出し側が設定する
     */
    val exhaustiveStoreList: Boolean = false,
    /**
     * effectiveRate が「店舗によって異なる率の最大値」か。施策全体ビュー(おトクタブの施策詳細)
     * だけが [Campaign.storeRatesVary] で設定し、率表示に「最大」を冠する(#81)。
     * お店タブ・地図(その店の実際の率を表示)は false のまま
     */
    val rateVariesByStore: Boolean = false,
    /** この金額(円)未満の買い物ならこの定額特典が最良の定率より得(#13)。定率の最良が無いチェーンは null */
    val breakevenAmount: Int? = null,
)

/**
 * 判定詳細の起動リンク 1 件。label は「◯◯を開く」の◯◯で、バッジ(カード/サービス名)でなく
 * 起動先アプリの名前を入れる(「三井住友カードアプリを開く」でウォレットが起動する齟齬を避ける)
 */
data class AppLink(
    val packageName: String,
    val label: String,
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

data class BestPaymentOption(
    val method: String,
    val rate: Double?,
    val discountAmount: Int?,
    val benefitType: BenefitType,
    val isTimeLimited: Boolean,
    val daysRemaining: Int?,
    /** 名目還元率(円換算前)。[rate](実質%)と異なるとき UI が「実質○%相当」を併記する(#13) */
    val nominalRate: Double? = null,
)

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
