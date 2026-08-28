package com.ktakjm.poikatsu

import com.ktakjm.poikatsu.data.Campaign
import com.ktakjm.poikatsu.data.CardClass
import com.ktakjm.poikatsu.data.CardOverride
import com.ktakjm.poikatsu.data.Merchant
import com.ktakjm.poikatsu.data.MerchantRule
import com.ktakjm.poikatsu.data.PaymentCard
import com.ktakjm.poikatsu.data.PointMultiplier
import com.ktakjm.poikatsu.data.PointValueConfig
import com.ktakjm.poikatsu.data.PointCurrency
import com.ktakjm.poikatsu.data.PoikatsuData
import com.ktakjm.poikatsu.domain.JudgmentEngine
import com.ktakjm.poikatsu.domain.currencyValueFactor
import com.ktakjm.poikatsu.domain.mergeUserData
import com.ktakjm.poikatsu.domain.multiplierToggleIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * カードクラス(JCB W/S 等)のマージ(UserDataMerge)と、1pt 価値との合成。
 * マージが組むのは名目率 (率 + クラス加算) までで、1pt 価値の円換算はスコア層(judgeAll。#13)。
 * 店舗別レート用の rateBonus はマージ後カードに載る(#52)。
 */
class CardClassMergeTest {

    private val jcbLikeCard = PaymentCard(
        id = "jcb",
        cardName = "テストJCB",
        brands = listOf("JCB"),
        effectiveRateDefault = 10.0,
        pointCurrencyId = "jp",
        cardClasses = listOf(
            CardClass(id = "s", label = "S", rateBonus = 0.0),
            CardClass(id = "w", label = "W", rateBonus = 0.5),
        ),
    )
    private val jpoint = PointCurrency(
        id = "jp",
        name = "テストJポイント",
        pointValueConfig = PointValueConfig(label = "Jポイントの価値", default = 1.0),
    )

    private fun merged(
        overrides: Map<String, CardOverride>,
        pointCurrencyValues: Map<String, Double> = emptyMap(),
    ) = mergeUserData(
        PoikatsuData(
            merchants = emptyList(),
            campaigns = emptyList(),
            cards = listOf(jcbLikeCard),
            pointCurrencies = listOf(jpoint),
            updatedAt = "2026-08-07",
        ),
        cardOverrides = overrides,
        ownedBrands = emptySet(),
        customCards = emptyList(),
        customCampaigns = emptyList(),
        pointCurrencyValues = pointCurrencyValues,
    ).engineData.cards.single()

    @Test
    fun `未選択はカタログ先頭クラス(保守側)`() {
        val card = merged(emptyMap())
        assertEquals(0.0, card.rateBonus, 0.0)
        assertEquals(10.0, card.effectiveRateDefault!!, 0.0)
    }

    @Test
    fun `クラスWの加算がマージ後の名目率と店舗別レート加算に載る`() {
        val card = merged(mapOf("jcb" to CardOverride(cardClass = "w")))
        assertEquals(0.5, card.rateBonus, 0.0)
        // 1pt 価値(0.7 等)はここでは掛からない: 名目は 10.0 + 0.5
        assertEquals(10.5, card.effectiveRateDefault!!, 1e-9)
    }

    @Test
    fun `クラス加算と1pt価値は判定レベルで合成される`() {
        // マージ(名目 = 店舗別レート + クラス加算)× 通貨価値係数(1pt=0.7円)= 実質率
        val merchant = Merchant(id = "m", name = "テスト店", reading = "てすとてん", category = "その他")
        val program = Campaign(
            id = "jcb_partner",
            operator = "テスト",
            cardId = "jcb",
            name = "テストパートナー",
            paymentInstruction = "カード利用",
            rateBase = 10.0,
            verifiedDate = "2026-06-01",
            merchantRules = listOf(MerchantRule(merchantId = "m", rateOverride = 1.5)),
        )
        val engineData = mergeUserData(
            PoikatsuData(
                merchants = listOf(merchant),
                campaigns = listOf(program),
                cards = listOf(jcbLikeCard),
                pointCurrencies = listOf(jpoint),
                updatedAt = "",
            ),
            cardOverrides = mapOf("jcb" to CardOverride(cardClass = "w")),
            ownedBrands = emptySet(),
            customCards = emptyList(),
            customCampaigns = emptyList(),
            pointCurrencyValues = mapOf("jp" to 0.7),
        ).engineData
        val judgment = JudgmentEngine(engineData)
            .judgeAll(merchant, LocalDate.of(2026, 6, 28)).judgments.single()
        // (1.5 + 0.5) × 0.7 = 1.4
        assertEquals(1.4, judgment.effectiveRate!!, 1e-9)
        assertEquals(2.0, judgment.nominalRate!!, 1e-9)
    }

    @Test
    fun `クラスの無いカードは従来どおり率のみ`() {
        val plain = PaymentCard(id = "p", cardName = "テスト", effectiveRateDefault = 7.0)
        val card = mergeUserData(
            PoikatsuData(merchants = emptyList(), campaigns = emptyList(), cards = listOf(plain), updatedAt = ""),
            cardOverrides = emptyMap(),
            ownedBrands = emptySet(),
            customCards = emptyList(),
            customCampaigns = emptyList(),
        ).engineData.cards.single()
        assertEquals(0.0, card.rateBonus, 0.0)
        assertEquals(7.0, card.effectiveRateDefault!!, 0.0)
    }

    @Test
    fun `手入力レートは単一率プログラムのカードだけに効く`() {
        // 手入力に意味があるのは「単一率でユーザーごとに実際の率が違う」プログラム(SMCC/MUFG)だけ。
        // 店舗別レートプログラム(dカード #58)のカードとクラスを持つカード(JCB)は率が
        // 収録値・導出値で決まるため、保存済みの手入力値が残っていても無視する
        val plainCard = PaymentCard(id = "plain", cardName = "単一率", effectiveRateDefault = 7.0)
        val storeRateCard = PaymentCard(id = "dcard_like", cardName = "店舗別レート", effectiveRateDefault = 4.0)
        val storeRateCampaign = Campaign(
            id = "prog",
            operator = "テスト",
            cardId = "dcard_like",
            name = "店舗別レートプログラム",
            paymentInstruction = "カードで支払う",
            rateBase = 4.0,
            merchantRules = listOf(MerchantRule(merchantId = "m1", rateOverride = 4.0)),
        )
        val cards = mergeUserData(
            PoikatsuData(
                merchants = emptyList(),
                campaigns = listOf(storeRateCampaign),
                cards = listOf(plainCard, storeRateCard),
                updatedAt = "",
            ),
            cardOverrides = mapOf(
                "plain" to CardOverride(rate = 5.5),
                "dcard_like" to CardOverride(rate = 1.0),
            ),
            ownedBrands = emptySet(),
            customCards = emptyList(),
            customCampaigns = emptyList(),
        ).engineData.cards.associateBy { it.id }
        assertEquals(5.5, cards.getValue("plain").effectiveRateDefault!!, 0.0)
        assertEquals(4.0, cards.getValue("dcard_like").effectiveRateDefault!!, 0.0)
    }

    @Test
    fun `クラス持ちカードの手入力レートも無視される`() {
        val card = merged(mapOf("jcb" to CardOverride(rate = 1.0)))
        assertEquals(10.0, card.effectiveRateDefault!!, 0.0)
    }
}

/**
 * ポイント通貨マスタ(point_currencies。#39)のマージ(UserDataMerge)。
 * ウエル活等の倍率は通貨の価値特性で、有効/無効はユーザー設定
 * (enabled_point_multipliers: Set<通貨id>)から通貨単位で決まる。
 * 円換算(1pt価値 × 倍率)の適用点はマージ層でなくスコア層(judgeAll。#13)なので、
 * マージ後のカード実効率は名目のままで、実質率は判定レベルで検証する。
 */
class PointCurrencyMergeTest {

    private val vpoint = PointCurrency(
        id = "vp",
        name = "テストVポイント",
        pointMultiplier = PointMultiplier(label = "ウエル活利用時の還元率を表示", factor = 1.5),
    )
    private val smccLike = PaymentCard(
        id = "smcc",
        cardName = "テストカード",
        effectiveRateDefault = 7.0,
        pointCurrencyId = "vp",
    )

    private fun merged(enabled: Set<String>, currencies: List<PointCurrency> = listOf(vpoint)) = mergeUserData(
        PoikatsuData(
            merchants = emptyList(),
            campaigns = emptyList(),
            cards = listOf(smccLike),
            pointCurrencies = currencies,
            updatedAt = "",
        ),
        cardOverrides = emptyMap(),
        ownedBrands = emptySet(),
        customCards = emptyList(),
        customCampaigns = emptyList(),
        enabledPointMultipliers = enabled,
    )

    private val testMerchant = Merchant(id = "m", name = "テスト店", reading = "てすとてん", category = "その他")
    private val cardProgram = Campaign(
        id = "c1",
        operator = "テスト",
        cardId = "smcc",
        name = "テスト施策",
        paymentInstruction = "カード利用",
        rateBase = 7.0,
        verifiedDate = "2026-06-01",
        merchantRules = listOf(MerchantRule(merchantId = "m")),
    )

    @Test
    fun `倍率を有効にしてもマージ後のカード実効率は名目のまま`() {
        // 円換算(1pt価値 × 倍率)の適用点はスコア層(judgeAll)に一本化した(#13)。
        // マージ層はクラス加算までの名目率を組む
        val card = merged(setOf("vp")).engineData.cards.single()
        assertEquals(7.0, card.effectiveRateDefault!!, 0.0)
    }

    @Test
    fun `倍率が無効でもマージ後のカード実効率は名目のまま`() {
        val card = merged(emptySet()).engineData.cards.single()
        assertEquals(7.0, card.effectiveRateDefault!!, 0.0)
    }

    @Test
    fun `判定レベルでは倍率ONのカード施策が実質率になり名目率も持つ`() {
        val engineData = merged(setOf("vp")).engineData.copy(
            merchants = listOf(testMerchant),
            campaigns = listOf(cardProgram),
        )
        val judgment = JudgmentEngine(engineData)
            .judgeAll(testMerchant, LocalDate.of(2026, 6, 28)).judgments.single()
        assertEquals(10.5, judgment.effectiveRate!!, 1e-9)
        assertEquals(7.0, judgment.nominalRate!!, 0.0)
        assertTrue(judgment.welcatsuApplied)
    }

    @Test
    fun `判定レベルでは倍率OFFなら実質率と名目率が一致する`() {
        val engineData = merged(emptySet()).engineData.copy(
            merchants = listOf(testMerchant),
            campaigns = listOf(cardProgram),
        )
        val judgment = JudgmentEngine(engineData)
            .judgeAll(testMerchant, LocalDate.of(2026, 6, 28)).judgments.single()
        assertEquals(7.0, judgment.effectiveRate!!, 0.0)
        assertEquals(7.0, judgment.nominalRate!!, 0.0)
        assertFalse(judgment.welcatsuApplied)
    }

    @Test
    fun `判定レベルでは1pt価値0円の通貨の施策は実質0パーセントになる`() {
        // 「貯まるが使わない」層(設計書 §3): 名目率は残り実質が 0 になる
        val engineData = mergeUserData(
            PoikatsuData(
                merchants = listOf(testMerchant),
                campaigns = listOf(cardProgram),
                cards = listOf(smccLike),
                pointCurrencies = listOf(vpoint),
                updatedAt = "",
            ),
            cardOverrides = emptyMap(),
            ownedBrands = emptySet(),
            customCards = emptyList(),
            customCampaigns = emptyList(),
            enabledPointMultipliers = emptySet(),
            pointCurrencyValues = mapOf("vp" to 0.0),
        ).engineData
        val judgment = JudgmentEngine(engineData)
            .judgeAll(testMerchant, LocalDate.of(2026, 6, 28)).judgments.single()
        assertEquals(0.0, judgment.effectiveRate!!, 0.0)
        assertEquals(7.0, judgment.nominalRate!!, 0.0)
    }

    @Test
    fun `マージ後の通貨マスタに有効フラグが立ちエンジンへ渡る`() {
        assertTrue(merged(setOf("vp")).engineData.pointCurrencies.single().multiplierEnabled)
        assertFalse(merged(emptySet()).engineData.pointCurrencies.single().multiplierEnabled)
    }

    @Test
    fun `倍率定義の無い通貨は有効にしても何も起きない`() {
        val noMultiplier = listOf(vpoint.copy(pointMultiplier = null))
        val result = merged(setOf("vp"), currencies = noMultiplier)
        assertEquals(7.0, result.engineData.cards.single().effectiveRateDefault!!, 0.0)
        assertFalse(result.engineData.pointCurrencies.single().multiplierEnabled)
    }

    @Test
    fun `通貨の1pt価値はマージでは通貨マスタに載るだけでカード率は名目のまま`() {
        // valueYen=0.5 は通貨マスタに載り、実際に率へ掛かるのは判定時(スコア層。#13)
        val result = mergeUserData(
            PoikatsuData(
                merchants = emptyList(),
                campaigns = emptyList(),
                cards = listOf(smccLike),
                pointCurrencies = listOf(vpoint),
                updatedAt = "",
            ),
            cardOverrides = emptyMap(),
            ownedBrands = emptySet(),
            customCards = emptyList(),
            customCampaigns = emptyList(),
            enabledPointMultipliers = emptySet(),
            pointCurrencyValues = mapOf("vp" to 0.5),
        )
        assertEquals(7.0, result.engineData.cards.single().effectiveRateDefault!!, 0.0)
        assertEquals(0.5, result.engineData.pointCurrencies.single().valueYen, 1e-9)
    }

    @Test
    fun `通貨の1pt価値の既定はカタログのdefaultで未設定なら1円`() {
        val jpointLike = PointCurrency(
            id = "jp",
            name = "テストJポイント",
            pointValueConfig = PointValueConfig(label = "Jポイントの価値", default = 1.0, note = ""),
        )
        val card = smccLike.copy(pointCurrencyId = "jp")
        val result = mergeUserData(
            PoikatsuData(merchants = emptyList(), campaigns = emptyList(), cards = listOf(card), pointCurrencies = listOf(jpointLike), updatedAt = ""),
            cardOverrides = emptyMap(), ownedBrands = emptySet(), customCards = emptyList(),
            customCampaigns = emptyList(), enabledPointMultipliers = emptySet(),
        )
        assertEquals(1.0, result.engineData.pointCurrencies.single().valueYen, 0.0)
        assertEquals(7.0, result.engineData.cards.single().effectiveRateDefault!!, 0.0)
    }

    @Test
    fun `bestOptionは名目率も持ち実質率で選ばれる`() {
        // 最大おトク率(BestPaymentOption)の比較・選出は実質率(rate)のまま、UI 併記用に
        // 名目率(nominalRate)も一緒に持ち回る(#13)
        val engineData = merged(setOf("vp")).engineData.copy(
            merchants = listOf(testMerchant),
            campaigns = listOf(cardProgram),
        )
        val best = JudgmentEngine(engineData)
            .judgeAll(testMerchant, LocalDate.of(2026, 6, 28)).bestOption!!
        assertEquals(10.5, best.rate!!, 1e-9)
        assertEquals(7.0, best.nominalRate!!, 0.0)
    }
}

/**
 * ポイント倍率のユーザー選択(factor_options。#83)と円建て通貨(value_fixed)のマージ。
 * 倍率は「発行体が定めた離散的な条件付き増価」なので選択肢から選ぶ形で、自由入力は
 * 恒常的な 1pt 価値(point_value)側が担う。選択値はカタログの factor を差し替える形で
 * 載せ、スコア層(currencyValueFactor)に分岐を足さない(#13 の一本化を維持)。
 */
class PointMultiplierFactorMergeTest {

    /** 交換所倍率 1.1(既定=保守側) / 1.5 の二択を持つ Ponta 相当 */
    private val ponta = PointCurrency(
        id = "pt",
        name = "テストPontaポイント",
        pointMultiplier = PointMultiplier(
            label = "ポイント交換所を利用時の還元率を表示",
            factor = 1.1,
            factorOptions = listOf(1.1, 1.5),
        ),
    )

    private fun merged(
        enabled: Set<String> = setOf("pt"),
        factors: Map<String, Double> = emptyMap(),
        values: Map<String, Double> = emptyMap(),
        currencies: List<PointCurrency> = listOf(ponta),
    ) = mergeUserData(
        PoikatsuData(
            merchants = emptyList(),
            campaigns = emptyList(),
            cards = emptyList(),
            pointCurrencies = currencies,
            updatedAt = "",
        ),
        cardOverrides = emptyMap(),
        ownedBrands = emptySet(),
        customCards = emptyList(),
        customCampaigns = emptyList(),
        enabledPointMultipliers = enabled,
        pointCurrencyValues = values,
        pointMultiplierFactors = factors,
    ).engineData.pointCurrencies.single()

    @Test
    fun `選択した倍率がマージ後の通貨のfactorになる`() {
        assertEquals(1.5, merged(factors = mapOf("pt" to 1.5)).pointMultiplier!!.factor, 0.0)
    }

    @Test
    fun `倍率未選択ならカタログのfactorが既定になる`() {
        // カタログの factor は factor_options の最小値(保守側)に揃える約束(整合性テストで強制)
        assertEquals(1.1, merged().pointMultiplier!!.factor, 0.0)
    }

    @Test
    fun `factor_optionsに無い選択倍率は無視してカタログのfactorに落ちる`() {
        // DataStore に残った選択肢外の値(カタログ改定で選択肢が減った等)への防御
        assertEquals(1.1, merged(factors = mapOf("pt" to 3.0)).pointMultiplier!!.factor, 0.0)
    }

    @Test
    fun `factor_optionsを持たない通貨は選択倍率を無視する`() {
        // ウエル活(単一 factor)は従来どおり ON/OFF だけ。選択の余地が無いものに値を効かせない
        val welcatsu = listOf(
            PointCurrency(
                id = "pt",
                name = "テストVポイント",
                pointMultiplier = PointMultiplier(label = "ウエル活", factor = 1.5),
            ),
        )
        val currency = merged(factors = mapOf("pt" to 1.1), currencies = welcatsu)
        assertEquals(1.5, currency.pointMultiplier!!.factor, 0.0)
    }

    @Test
    fun `倍率OFFなら選択倍率は価値係数に効かない`() {
        val currency = merged(enabled = emptySet(), factors = mapOf("pt" to 1.5))
        assertEquals(1.0, currencyValueFactor(currency), 0.0)
    }

    @Test
    fun `value_fixedの通貨は保存済みの1pt価値を無視して1円になる`() {
        // au PAY残高のような円建て通貨(#83)。設定画面にも出さないため、DataStore に
        // 残った値(通貨が円建てに変わる前の設定等)を効かせない
        val balance = listOf(PointCurrency(id = "pt", name = "テストau PAY残高", valueFixed = true))
        assertEquals(1.0, merged(values = mapOf("pt" to 1.5), currencies = balance).valueYen, 0.0)
    }

    @Test
    fun `value_fixedでない通貨は保存済みの1pt価値がそのまま載る`() {
        assertEquals(1.5, merged(values = mapOf("pt" to 1.5)).valueYen, 0.0)
    }
}

/**
 * 倍率グループ(#84)。ウエル活 ×1.5 のように同じ事実を複数通貨(Vポイント・WAON POINT)が
 * 持つとき、point_multiplier.group で束ねて ON/OFF を連動させる——片方だけ切り替える事故を
 * 構造的に防ぐ。マージは「グループの誰かが有効なら全員有効」(グループ導入前の DataStore に
 * 片方の id しか残っていない状態への防御を兼ねる)、設定画面のトグルは multiplierToggleIds で
 * グループ全員の id を書く。
 */
class PointMultiplierGroupTest {

    private fun welcatsu(group: String?) = PointMultiplier(label = "ウエル活", factor = 1.5, group = group)

    private val vpoint = PointCurrency(id = "vp", name = "テストVポイント", pointMultiplier = welcatsu("welcia"))
    private val waon = PointCurrency(id = "wp", name = "テストWAON POINT", pointMultiplier = welcatsu("welcia"))
    private val solo = PointCurrency(id = "solo", name = "テスト単独倍率", pointMultiplier = welcatsu(null))

    private fun merged(
        enabled: Set<String>,
        currencies: List<PointCurrency> = listOf(vpoint, waon, solo),
    ): Map<String, PointCurrency> = mergeUserData(
        PoikatsuData(
            merchants = emptyList(),
            campaigns = emptyList(),
            cards = emptyList(),
            pointCurrencies = currencies,
            updatedAt = "",
        ),
        cardOverrides = emptyMap(),
        ownedBrands = emptySet(),
        customCards = emptyList(),
        customCampaigns = emptyList(),
        enabledPointMultipliers = enabled,
    ).engineData.pointCurrencies.associateBy { it.id }

    @Test
    fun `同一グループは片方の有効化で全員有効になる`() {
        val currencies = merged(enabled = setOf("vp"))
        assertTrue(currencies.getValue("vp").multiplierEnabled)
        assertTrue(currencies.getValue("wp").multiplierEnabled)
    }

    @Test
    fun `グループ外の通貨は他通貨の有効化に影響されない`() {
        val currencies = merged(enabled = setOf("vp"))
        assertFalse(currencies.getValue("solo").multiplierEnabled)
    }

    @Test
    fun `誰も有効でなければグループ全員が無効のまま`() {
        val currencies = merged(enabled = emptySet())
        assertFalse(currencies.getValue("vp").multiplierEnabled)
        assertFalse(currencies.getValue("wp").multiplierEnabled)
    }

    @Test
    fun `倍率を持たない通貨は同名グループがあっても有効にならない`() {
        // group は point_multiplier の中のフィールドなので倍率なし通貨には付かないが、
        // 「有効な誰か」の巻き添えで multiplierEnabled が立たないことを保証する
        val plain = PointCurrency(id = "plain", name = "テスト無倍率")
        val currencies = merged(enabled = setOf("vp"), currencies = listOf(vpoint, waon, plain))
        assertFalse(currencies.getValue("plain").multiplierEnabled)
    }

    @Test
    fun `multiplierToggleIdsはグループ全員のidを返す`() {
        assertEquals(setOf("vp", "wp"), multiplierToggleIds(listOf(vpoint, waon, solo), "vp"))
        assertEquals(setOf("vp", "wp"), multiplierToggleIds(listOf(vpoint, waon, solo), "wp"))
    }

    @Test
    fun `multiplierToggleIdsはグループ無しなら自分だけ`() {
        assertEquals(setOf("solo"), multiplierToggleIds(listOf(vpoint, waon, solo), "solo"))
    }

    @Test
    fun `multiplierToggleIdsはカタログに無いidでも自分を返す`() {
        // カタログ改定で通貨が消えた直後の防御(空集合だと DataStore から消せなくなる)
        assertEquals(setOf("gone"), multiplierToggleIds(listOf(vpoint, waon), "gone"))
    }
}
