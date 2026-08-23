package com.ktakjm.poikatsu.domain

import com.ktakjm.poikatsu.data.CardOverride
import com.ktakjm.poikatsu.data.CustomCampaign
import com.ktakjm.poikatsu.data.CustomCard
import com.ktakjm.poikatsu.data.Attribution
import com.ktakjm.poikatsu.data.CustomPayment
import com.ktakjm.poikatsu.data.attribution
import com.ktakjm.poikatsu.data.PaymentCard
import com.ktakjm.poikatsu.data.PointCurrency
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

/**
 * ポイント倍率のトグルで DataStore に書く通貨 id の集合(#84)。倍率グループを持つ通貨は
 * 同一グループの全通貨を返し、設定画面のどの通貨のチェックから切り替えても
 * ON/OFF が連動する(マージ側の「誰か有効なら全員有効」と対で、常に全員そろった状態を保つ)。
 * グループ無し・カタログに無い id は自分だけ(空集合だと DataStore から消せなくなる)。
 */
fun multiplierToggleIds(currencies: List<PointCurrency>, currencyId: String): Set<String> {
    val group = currencies.firstOrNull { it.id == currencyId }?.pointMultiplier?.group
        ?: return setOf(currencyId)
    return currencies
        .filter { it.pointMultiplier?.group == group }
        .map { it.id }
        .toSet()
}

fun mergeUserData(
    base: PoikatsuData,
    cardOverrides: Map<String, CardOverride>,
    ownedBrands: Set<String>,
    customCards: List<CustomCard>,
    customCampaigns: List<CustomCampaign>,
    enabledPointMultipliers: Set<String> = emptySet(),
    pointCurrencyValues: Map<String, Double> = emptyMap(),
    pointMultiplierFactors: Map<String, Double> = emptyMap(),
): MergedUserData {
    val baseCards = base.cards

    // ポイント通貨マスタ(#39): 倍率(ウエル活等)の有効/無効はユーザー設定(通貨 id の Set)から
    // 通貨単位で決まる。有効フラグを立てた通貨マスタをエンジン・表示の両方へ渡し、
    // 率への円換算(judgeCards/judgeQr/judgePrograms)とバッジ表示が同じ状態を参照するようにする。
    // 1pt 価値(#13)も同じく通貨単位: ユーザー設定(pointCurrencyValues)→カタログの
    // pointValueConfig.default → 1.0円 の順で解決し valueYen に載せる。
    // 倍率が選択肢を持つ通貨(factor_options。#83)は、選んだ倍率をカタログの factor に
    // 差し替える形で載せる——スコア層(currencyValueFactor)に「ユーザー選択があれば」の分岐を
    // 足さずに済み、バッジ・注記も同じ値を読む(#13 の円換算一本化を維持)。
    // 倍率グループ(#84): グループの誰かが有効なら全員有効。設定画面のトグルは
    // multiplierToggleIds でグループ全員の id を書くが、グループ導入前の DataStore に
    // 片方の id しか残っていない状態でもここで連動させる(片方だけ有効の状態を作らない)
    val enabledMultiplierGroups = base.pointCurrencies
        .filter { it.id in enabledPointMultipliers }
        .mapNotNull { it.pointMultiplier?.group }
        .toSet()
    val mergedCurrencies = base.pointCurrencies.map { currency ->
        // 選択肢外の値は無視(カタログ改定で選択肢が減った後に DataStore に残った値への防御)。
        // 選択肢を持たない通貨(ウエル活)は選択の余地が無いので常にカタログ値
        val chosenFactor = pointMultiplierFactors[currency.id]
            ?.takeIf { it in currency.pointMultiplier?.factorOptions.orEmpty() }
        currency.copy(
            multiplierEnabled = currency.pointMultiplier != null &&
                (currency.id in enabledPointMultipliers ||
                    currency.pointMultiplier.group?.let { it in enabledMultiplierGroups } == true),
            pointMultiplier = chosenFactor
                ?.let { currency.pointMultiplier?.copy(factor = it) }
                ?: currency.pointMultiplier,
            // 円建て通貨(value_fixed)は設定画面に出さないため、保存済みの値も効かせない
            valueYen = if (currency.valueFixed) {
                1.0
            } else {
                pointCurrencyValues[currency.id] ?: currency.pointValueConfig?.default ?: 1.0
            },
        )
    }

    // エンジン用: 所有カードのみ、上書きを反映したカード一覧。
    // 実ブランドはユーザー設定(CardOverride.brand)が唯一の情報源で、カタログが単一ブランド製品の
    // ときだけ自動確定する。複数ブランド製品で未選択なら空文字=どのブランドとも断定せず
    // 好条件側に倒さない(ineligible_brands は除外ブランドを取りうる限り発動し、
    // card_brand 施策には一致しない。JudgmentEngine.excludedByBrand 参照)。
    val mergedCards = baseCards.mapNotNull { card ->
        val ov = cardOverrides[card.id]
        if (ov?.owned == false) return@mapNotNull null
        // 手入力レートは単一率プログラムのカード(SMCC/MUFG)だけに効く。導出値・店舗別レートの
        // カードは保存済みの手入力値が残っていても無視する(allowsManualRate 参照)
        val manualRate = ov?.rate?.takeIf { card.allowsManualRate(base.campaigns) }
        val rawRate = manualRate ?: card.effectiveRateDefault
        // 1pt 価値・条件付き倍率(ウエル活等)の円換算はスコア層(ExpectedValueScoring)で判定時に
        // 一括適用する(#13)。マージが組むのは名目率(クラス加算まで)で、通貨側の価値は
        // mergedCurrencies(valueYen / multiplierEnabled)に載せてエンジンへ渡す。
        // 適用点を1箇所に寄せることで、マージ層とエンジン層の二重適用を原理的に防ぐ。
        // カードクラス(JCB W/S 等): 未選択はカタログ先頭(保守側=加算の小さい方)を既定にする。
        // クラス加算はポイント数の加算なので価値の乗算より先に足す必要があり(= (率 + 加算) × 価値)、
        // 加算だけをここで済ませておけばスコア層は掛けるだけでよい
        val classBonus = (card.cardClasses.firstOrNull { it.id == ov?.cardClass }
            ?: card.cardClasses.firstOrNull())?.rateBonus ?: 0.0
        card.copy(
            brand = ov?.brand ?: card.brands.singleOrNull().orEmpty(),
            effectiveRateDefault = rawRate?.let { it + classBonus },
            rateBonus = classBonus,
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
    // (お店/地図/おトク)。operator には紐付け先決済手段の表示名を入れる
    // (おトクタブ詳細のバッジに使われる。ブランド指定はブランド名がバッジになるため未使用)
    val paymentNames = (
        baseCards.map { it.id to it.cardName } +
            customCards.map { it.id to it.name } +
            base.qrPayments.map { it.id to it.name }
        ).toMap()
    val operatorFor = { p: CustomPayment ->
        when (val a = p.attribution) {
            is Attribution.Brand -> a.name
            is Attribution.Card -> paymentNames[a.id] ?: "カスタム"
            is Attribution.Qr -> paymentNames[a.id] ?: "カスタム"
            else -> "カスタム"
        }
    }
    val customMerchants = buildCustomMerchants(customCampaigns)
    val convertedCustomCampaigns = customCampaigns.flatMap { it.toCampaigns(operatorFor) }
    val mergedMerchants = base.merchants + customMerchants
    val mergedCampaigns = base.campaigns + convertedCustomCampaigns

    val engineData = base.copy(
        cards = mergedCards + customPaymentCards + brandCards,
        merchants = mergedMerchants,
        campaigns = mergedCampaigns,
        pointCurrencies = mergedCurrencies,
    )
    // 表示用(色解決・名前引き・地図の検索設定)は所有に関わらず全カタログのカードで組む
    val displayData = base.copy(
        cards = baseCards + customPaymentCards,
        merchants = mergedMerchants,
        campaigns = mergedCampaigns,
        pointCurrencies = mergedCurrencies,
    )
    return MergedUserData(engineData = engineData, displayData = displayData)
}
