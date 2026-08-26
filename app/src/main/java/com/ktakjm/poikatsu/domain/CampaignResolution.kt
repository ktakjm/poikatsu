package com.ktakjm.poikatsu.domain

import com.ktakjm.poikatsu.data.Attribution
import com.ktakjm.poikatsu.data.Campaign
import com.ktakjm.poikatsu.data.PaymentCard
import com.ktakjm.poikatsu.data.PointCurrency
import com.ktakjm.poikatsu.data.QrPayment

/**
 * campaigns.json の記述形をエンジン・UI が見る解決済みの Campaign 列へ変換する(#89)。
 * PoikatsuJson.parse から 1 度だけ呼ばれ、以降のコードは「1 施策 = 1 決済手段・operator /
 * payment_instruction 解決済み・payment_variants 空」を前提にできる。順序は
 * 展開 → サービス既定の補完 → operator 導出(既定の補完は展開後の帰属に依存するため)。
 */
fun resolveCampaigns(
    campaigns: List<Campaign>,
    cards: List<PaymentCard>,
    qrPayments: List<QrPayment>,
    pointCurrencies: List<PointCurrency>,
): List<Campaign> =
    campaigns
        .flatMap { expandPaymentVariants(it) }
        .map { applyMunicipalDefaults(it, qrPayments) }
        .map { c ->
            if (c.operator.isNotBlank()) c
            else c.copy(operator = deriveOperator(c.attribution, cards, qrPayments, pointCurrencies) ?: "")
        }

/**
 * municipal の payment_variants を「1 施策 = 1 決済手段」の Campaign へ展開する。
 * variants が空ならそのまま 1 件(promotion / card_program、旧形式の municipal)。
 * 展開後 id は `{施策id}_{payment_method_id}`。notes / memo は共通側の後ろに variant 分を連結、
 * 単値フィールドは variant 側が non-null なら上書き。展開後は paymentVariants を空にする。
 */
fun expandPaymentVariants(campaign: Campaign): List<Campaign> {
    if (campaign.paymentVariants.isEmpty()) return listOf(campaign)
    return campaign.paymentVariants.map { v ->
        campaign.copy(
            id = "${campaign.id}_${v.paymentMethodId}",
            paymentMethodId = v.paymentMethodId,
            detailUrl = v.detailUrl ?: campaign.detailUrl,
            storeSearchUrl = v.storeSearchUrl ?: campaign.storeSearchUrl,
            verifiedDate = v.verifiedDate.ifBlank { campaign.verifiedDate },
            pointCurrencyId = v.pointCurrencyId ?: campaign.pointCurrencyId,
            paymentInstruction = v.paymentInstruction ?: campaign.paymentInstruction,
            eligibleNotes = campaign.eligibleNotes + v.eligibleNotes,
            ineligibleNotes = campaign.ineligibleNotes + v.ineligibleNotes,
            memo = campaign.memo + v.memo,
            paymentVariants = emptyList(),
        )
    }
}

/**
 * municipal に帰属 QR サービスの既定文言(qr_payments[].municipal_defaults)を補う。
 * payment_instruction は施策側が空のときだけ、ineligible_notes は施策側の末尾に連結(同文は重複排除)。
 * municipal 以外・既定を持たないサービスの施策はそのまま返す。
 */
fun applyMunicipalDefaults(campaign: Campaign, qrPayments: List<QrPayment>): Campaign {
    if (campaign.campaignType != CampaignType.MUNICIPAL) return campaign
    val qrId = (campaign.attribution as? Attribution.Qr)?.id ?: return campaign
    val defaults = qrPayments.firstOrNull { it.id == qrId }?.municipalDefaults ?: return campaign
    return campaign.copy(
        paymentInstruction = campaign.paymentInstruction.ifBlank { defaults.paymentInstruction },
        ineligibleNotes = (campaign.ineligibleNotes + defaults.ineligibleNotes).distinct(),
    )
}

/**
 * 帰属先カタログから運営者名を導出する。カード → card_name、ブランド → ブランド名、
 * QR → サービス名、提示プログラム → 通貨名。カタログに無い id・帰属なしは null。
 * 同梱施策(operator 省略時)とカスタムキャンペーン(UserDataMerge)で共用する。
 */
fun deriveOperator(
    attribution: Attribution?,
    cards: List<PaymentCard>,
    qrPayments: List<QrPayment>,
    pointCurrencies: List<PointCurrency>,
): String? = when (attribution) {
    is Attribution.Card -> cards.firstOrNull { it.id == attribution.id }?.cardName
    is Attribution.Brand -> attribution.name
    is Attribution.Qr -> qrPayments.firstOrNull { it.id == attribution.id }?.name
    is Attribution.Program -> pointCurrencies.firstOrNull { it.id == attribution.id }?.name
    null -> null
}
