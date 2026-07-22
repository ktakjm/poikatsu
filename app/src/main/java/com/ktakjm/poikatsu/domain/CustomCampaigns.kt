package com.ktakjm.poikatsu.domain

import com.ktakjm.poikatsu.data.Campaign
import com.ktakjm.poikatsu.data.CustomCampaign
import com.ktakjm.poikatsu.data.CustomPayment
import com.ktakjm.poikatsu.data.Merchant
import com.ktakjm.poikatsu.data.MerchantRule
import com.ktakjm.poikatsu.data.ProductScope
import com.ktakjm.poikatsu.data.Recurrence
import com.ktakjm.poikatsu.util.JapaneseText

// カスタムキャンペーン(#7)の変換。DataStore の登録内容(CustomCampaign)を既存の
// Campaign / Merchant に写して同梱データへ合流させる。エンジン(JudgmentEngine)には
// カスタム専用の分岐を作らず、変換後は同梱施策と完全に同じ経路で判定・表示される。

/** ユーザー自作(カスタム)の施策か。id の採番規則(custom:)で判定する */
val Campaign.isCustom: Boolean get() = id.startsWith(CustomCampaign.ID_PREFIX)

/**
 * 変換後の Campaign id から登録単位の CustomCampaign id を引く。複数決済の登録は
 * 「custom:<UUID>:p<N>」に展開されるため、決済サフィックスを剥がす(単一決済はそのまま)。
 * 期間限定タブのグルーピング(1登録=1カード)と編集・削除の逆引きに使う。
 */
fun customCampaignBaseId(campaignId: String): String =
    campaignId.replace(Regex(":p\\d+$"), "")

/** 自由入力店名の合成 Merchant のカテゴリ(お店・地図タブの絞り込みチップにもこの名で出る) */
const val CUSTOM_MERCHANT_CATEGORY = "カスタム"

/**
 * 自由入力店名に対応する合成 Merchant の id。正規化名ベースにすることで、複数の施策が
 * 同じ店名を登録しても同一 id に集約される(matchStore は 1 POI に 1 merchant しか返さない
 * ため、店名ごとに別 merchant を作ると片方の施策しか判定に出なくなる)。
 */
fun customStoreMerchantId(storeName: String): String =
    "custom:store:" + JapaneseText.normalize(storeName)

/**
 * 自由入力店名から合成 Merchant を作る。既存の検索(search)・POI 照合(matchStore)に
 * そのまま乗せることで「店名の部分一致マッチ」を実現する(専用のマッチャは持たない)。
 * yolp_search=keyword: 施策が有効な間、店名そのままの YOLP キーワード検索が地図タブの
 * 取得対象に自動で加わる(activeManagedMerchantIds → YolpSearchConfig.build の既存経路)。
 */
fun buildCustomMerchants(customCampaigns: List<CustomCampaign>): List<Merchant> =
    customCampaigns
        .flatMap { it.storeNames }
        .filter { it.isNotBlank() }
        .distinctBy { JapaneseText.normalize(it) }
        .map { name ->
            Merchant(
                id = customStoreMerchantId(name),
                name = name,
                category = CUSTOM_MERCHANT_CATEGORY,
                yolpSearch = "keyword",
            )
        }

/** 改行区切りのメモを注記リスト(1行=1項目)へ。空行は捨てる */
private fun splitNotes(s: String): List<String> =
    s.lines().map { it.trim() }.filter { it.isNotEmpty() }

/**
 * 判定エンジンに渡す Campaign へ変換する(決済手段ごとに1件へ展開)。type=promotion にする理由:
 * - 期間限定タブは card_program 以外を一覧に出す(常設のカード施策と区別)
 * - judgeCards は promotion のとき施策の率をカードの常設実効率より優先する
 * 率も定額も無い特典(メモのみ)は promotion の率なし扱いになり、率を表示せず名前とメモだけ出る。
 *
 * 複数決済の展開 id は「<id>:p<N>」(単一決済はそのまま)。1登録の全展開が率・条件を共有する
 * (決済ごとに率が異なる施策は別登録)。逆引きは [customCampaignBaseId]。
 *
 * @param operatorFor 決済手段ごとの表示名の解決。期間限定タブ詳細のバッジ(判定を経ない表示)に使う
 */
fun CustomCampaign.toCampaigns(operatorFor: (CustomPayment) -> String): List<Campaign> {
    val merchantRules =
        (merchantIds + storeNames.filter { it.isNotBlank() }.map { customStoreMerchantId(it) })
            .distinct()
            .map { MerchantRule(merchantId = it) }
    val recurrence = Recurrence(daysOfWeek = daysOfWeek, daysOfMonth = daysOfMonth)
        .takeIf { daysOfWeek.isNotEmpty() || daysOfMonth.isNotEmpty() }
    return payments.mapIndexed { index, payment ->
        Campaign(
            id = if (payments.size == 1) id else "$id:p$index",
            operator = operatorFor(payment),
            cardId = payment.cardId,
            cardBrand = payment.cardBrand,
            paymentMethodId = payment.qrPaymentId,
            name = name,
            displayName = name,
            benefitType = benefitType,
            rateBase = rate,
            discountAmount = discountAmount,
            productScope = productScope?.trim()?.takeIf { it.isNotEmpty() }?.let { ProductScope(it) },
            periodStart = startDate,
            periodEnd = endDate,
            recurrence = recurrence,
            eligibleNotes = splitNotes(note),
            ineligibleNotes = splitNotes(ineligibleNote),
            minPurchase = minPurchase,
            minPurchaseScope = minPurchaseScope,
            usageLimit = usageLimit,
            perTransactionCap = perTransactionCap,
            periodTotalCap = periodTotalCap,
            capNote = capNote?.trim()?.takeIf { it.isNotEmpty() },
            detailUrl = detailUrl?.trim()?.takeIf { it.isNotEmpty() },
            merchantRules = merchantRules,
            type = "promotion",
        )
    }
}
