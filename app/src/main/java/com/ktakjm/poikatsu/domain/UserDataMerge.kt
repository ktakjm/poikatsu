package com.ktakjm.poikatsu.domain

import com.ktakjm.poikatsu.data.CardOverride
import com.ktakjm.poikatsu.data.CustomCampaign
import com.ktakjm.poikatsu.data.CustomCard
import com.ktakjm.poikatsu.data.CustomPayment
import com.ktakjm.poikatsu.data.PaymentCard
import com.ktakjm.poikatsu.data.PoikatsuData

// 同梱データ(カタログ)とユーザー設定(DataStore 差分)のマージ。MainViewModel.rebuild から
// 抽出した純 Kotlin(#6)。UI のエンジン再構築と通知ジョブ(CampaignNotificationWorker)が
// 同じマージを共有し、「アプリに出る施策」と「通知される施策」の基準がずれないようにする。

/** マージ結果。エンジン用と表示用でカードの母集団が異なる(施策・Merchant は共通) */
data class MergedUserData(
    /**
     * 判定エンジン用: 所有カードのみ+還元率/ブランド/ウエル活の上書きを反映したデータ。
     * カスタムキャンペーン由来の Campaign / 合成 Merchant も合流済み。
     */
    val engineData: PoikatsuData,
    /**
     * 表示・変換用の統合データ。エンジン用(engineData)との違いはカードが「所有のみ」でなく
     * 全カタログな点(未所有カード施策の色解決等、表示は全候補が要る)。
     */
    val displayData: PoikatsuData,
)

fun mergeUserData(
    base: PoikatsuData,
    cardOverrides: Map<String, CardOverride>,
    ownedBrands: Set<String>,
    customCards: List<CustomCard>,
    customCampaigns: List<CustomCampaign>,
): MergedUserData {
    val baseCards = base.cards

    // エンジン用: 所有カードのみ、上書きを反映したカード一覧。
    // 実ブランドはユーザー設定(CardOverride.brand)が唯一の情報源で、カタログが単一ブランド製品の
    // ときだけ自動確定する。複数ブランド製品で未選択なら空文字=どのブランドとも断定せず
    // 好条件側に倒さない(ineligible_brands は除外ブランドを取りうる限り発動し、
    // card_brand 施策には一致しない。JudgmentEngine.excludedByBrand 参照)。
    val mergedCards = baseCards.mapNotNull { card ->
        val ov = cardOverrides[card.id]
        if (ov?.owned == false) return@mapNotNull null
        val rawRate = ov?.rate ?: card.effectiveRateDefault
        val welcatsuOn = ov?.welcatsu == true && card.pointMultiplier != null
        val factor = if (welcatsuOn) card.pointMultiplier?.factor ?: 1.0 else 1.0
        card.copy(
            brand = ov?.brand ?: card.brands.singleOrNull().orEmpty(),
            effectiveRateDefault = rawRate?.let { it * factor },
            welcatsuApplied = welcatsuOn,
        )
    }
    // カスタムカード(カタログ外)は登録内容をそのまま PaymentCard に写してエンジンへ渡す。
    // 判定はキャンペーン駆動なので、施策が参照しない限り判定結果には現れない。現状効くのは
    // ブランド付き登録が card_brand 施策に一致する経路のみで、card_id 施策はカスタムキャンペーン
    // (#7)が custom: id を参照し始めた時点で自然に効く。
    val customPaymentCards = customCards.map { c ->
        PaymentCard(
            id = c.id,
            cardName = c.name,
            brandColor = c.color ?: CustomCard.DEFAULT_COLOR,
            brands = listOfNotNull(c.brand.takeIf { it.isNotBlank() }),
            brand = c.brand,
        )
    }
    // ブランド単位の登録(カタログ外のカード)は仮想カードとしてエンジンに渡す。card_brand 施策の
    // resolveCard がブランド一致でマッチするだけで、card_id 施策には一致しない(id が衝突しないため)。
    // カタログのカードの後ろに置き、複数一致時の代表はカタログのカード(具体名)を優先する。
    val brandCards = ownedBrands.sorted().map { brand ->
        PaymentCard(
            id = "owned_brand_${brand.lowercase()}",
            cardName = "${brand}カード",
            brands = listOf(brand),
            brand = brand,
        )
    }
    // カスタムキャンペーン(#7): 登録内容を Campaign(決済手段ごとに展開) / 合成 Merchant に
    // 変換して同梱データへ合流させる。以降は同梱施策と同じ経路で判定・表示される
    // (お店/地図/期間限定)。operator には紐付け先決済手段の表示名を入れる
    // (期間限定タブ詳細のバッジに使われる。ブランド指定はブランド名がバッジになるため未使用)
    val paymentNames = (
        baseCards.map { it.id to it.cardName } +
            customCards.map { it.id to it.name } +
            base.qrPayments.map { it.id to it.name }
        ).toMap()
    val operatorFor = { p: CustomPayment ->
        p.cardBrand ?: paymentNames[p.cardId ?: p.qrPaymentId] ?: "カスタム"
    }
    val customMerchants = buildCustomMerchants(customCampaigns)
    val convertedCustomCampaigns = customCampaigns.flatMap { it.toCampaigns(operatorFor) }
    val mergedMerchants = base.merchants + customMerchants
    val mergedCampaigns = base.campaigns + convertedCustomCampaigns

    val engineData = base.copy(
        cards = mergedCards + customPaymentCards + brandCards,
        merchants = mergedMerchants,
        campaigns = mergedCampaigns,
    )
    // 表示用(色解決・名前引き・地図の検索設定)は所有に関わらず全カタログのカードで組む
    val displayData = base.copy(
        cards = baseCards + customPaymentCards,
        merchants = mergedMerchants,
        campaigns = mergedCampaigns,
    )
    return MergedUserData(engineData = engineData, displayData = displayData)
}
