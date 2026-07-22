package com.ktakjm.poikatsu

import com.ktakjm.poikatsu.data.CardBrand
import com.ktakjm.poikatsu.data.CustomCampaign
import com.ktakjm.poikatsu.data.CustomPayment
import com.ktakjm.poikatsu.data.MIN_PURCHASE_SCOPE_PERIOD_TOTAL
import com.ktakjm.poikatsu.data.Merchant
import com.ktakjm.poikatsu.data.MerchantRule
import com.ktakjm.poikatsu.data.PaymentCard
import com.ktakjm.poikatsu.data.PoikatsuData
import com.ktakjm.poikatsu.data.QrPayment
import com.ktakjm.poikatsu.data.YolpConfig
import com.ktakjm.poikatsu.data.YolpSearchConfig
import com.ktakjm.poikatsu.domain.BenefitType
import com.ktakjm.poikatsu.domain.CampaignStatus
import com.ktakjm.poikatsu.domain.CampaignType
import com.ktakjm.poikatsu.domain.JudgmentEngine
import com.ktakjm.poikatsu.domain.buildCustomMerchants
import com.ktakjm.poikatsu.domain.campaignType
import com.ktakjm.poikatsu.domain.customCampaignBaseId
import com.ktakjm.poikatsu.domain.customStoreMerchantId
import com.ktakjm.poikatsu.domain.isCustom
import com.ktakjm.poikatsu.domain.toCampaigns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * カスタムキャンペーン(#7)の変換(toCampaigns / buildCustomMerchants)と、変換後が
 * 既存エンジンの検索・POI照合・判定・期間フィルタに同梱施策と同様に乗ることを検証する。
 */
class CustomCampaignTest {

    private val today = LocalDate.of(2026, 7, 20)

    private val cardPayment = CustomPayment(cardId = "custom:card1")

    private fun custom(
        id: String = "custom:test",
        payments: List<CustomPayment> = listOf(cardPayment),
        merchantIds: List<String> = emptyList(),
        storeNames: List<String> = emptyList(),
        benefitType: String = "rebate",
        rate: Double? = 10.0,
        discountAmount: Int? = null,
        note: String = "",
        startDate: String? = null,
        endDate: String? = null,
        daysOfWeek: List<String> = emptyList(),
    ) = CustomCampaign(
        id = id,
        name = "テストキャンペーン",
        payments = payments,
        merchantIds = merchantIds,
        storeNames = storeNames,
        benefitType = benefitType,
        rate = rate,
        discountAmount = discountAmount,
        note = note,
        startDate = startDate,
        endDate = endDate,
        daysOfWeek = daysOfWeek,
    )

    private fun operatorFor(p: CustomPayment): String =
        p.cardBrand ?: when {
            p.cardId != null -> "百貨店カード"
            else -> "テストペイ"
        }

    private fun engineWith(
        customCampaigns: List<CustomCampaign>,
        merchants: List<Merchant> = listOf(
            Merchant(id = "mcdonalds", name = "マクドナルド", reading = "まくどなるど", category = "ファストフード"),
        ),
        cards: List<PaymentCard> = listOf(
            PaymentCard(id = "custom:card1", cardName = "百貨店カード", brandColor = "#123456"),
        ),
        qrPayments: List<QrPayment> = emptyList(),
    ): JudgmentEngine {
        val data = PoikatsuData(
            merchants = merchants + buildCustomMerchants(customCampaigns),
            campaigns = customCampaigns.flatMap { it.toCampaigns(::operatorFor) },
            cards = cards,
            cardBrands = listOf(CardBrand(name = "Amex", color = "#016FD0")),
            qrPayments = qrPayments,
            updatedAt = "2026-07-01",
        )
        return JudgmentEngine(data)
    }

    // ---- 変換 ----

    @Test
    fun `toCampaigns はチェーンと自由入力店名の両方を merchant_rules に写す`() {
        val campaign = custom(
            merchantIds = listOf("mcdonalds"),
            storeNames = listOf("駅前ベーカリー"),
            rate = 5.0,
            note = "アプリ会員限定",
            endDate = "2026-08-31",
        ).toCampaigns(::operatorFor).single()

        assertEquals(CampaignType.PROMOTION, campaign.campaignType)
        assertTrue(campaign.isCustom)
        assertEquals("custom:card1", campaign.cardId)
        assertEquals(5.0, campaign.rateBase!!, 0.0)
        assertEquals("2026-08-31", campaign.periodEnd)
        assertEquals(listOf("アプリ会員限定"), campaign.eligibleNotes)
        assertEquals(
            listOf("mcdonalds", customStoreMerchantId("駅前ベーカリー")),
            campaign.merchantRules.map { it.merchantId },
        )
    }

    @Test
    fun `複数決済は決済ごとの Campaign に展開され base id で逆引きできる`() {
        val campaigns = custom(
            payments = listOf(
                CustomPayment(cardId = "custom:card1"),
                CustomPayment(qrPaymentId = "paypay"),
                CustomPayment(cardBrand = "Amex"),
            ),
            merchantIds = listOf("mcdonalds"),
        ).toCampaigns(::operatorFor)

        assertEquals(3, campaigns.size)
        assertEquals(listOf("custom:test:p0", "custom:test:p1", "custom:test:p2"), campaigns.map { it.id })
        assertTrue(campaigns.all { customCampaignBaseId(it.id) == "custom:test" })
        // 決済ごとの排他リンク
        assertEquals("custom:card1", campaigns[0].cardId)
        assertEquals("paypay", campaigns[1].paymentMethodId)
        assertEquals("Amex", campaigns[2].cardBrand)
        // operator は決済ごとに解決される
        assertEquals(listOf("百貨店カード", "テストペイ", "Amex"), campaigns.map { it.operator })
        // 率・条件は全決済で共通
        assertTrue(campaigns.all { it.rateBase == 10.0 })
    }

    @Test
    fun `単一決済の Campaign id はサフィックスなし(既存登録と互換)`() {
        val campaign = custom(merchantIds = listOf("mcdonalds")).toCampaigns(::operatorFor).single()
        assertEquals("custom:test", campaign.id)
        assertEquals("custom:test", customCampaignBaseId(campaign.id))
    }

    @Test
    fun `詳細条件が Campaign の各フィールドに写る`() {
        val campaign = CustomCampaign(
            id = "custom:test",
            name = "詳細つき",
            payments = listOf(cardPayment),
            merchantIds = listOf("mcdonalds"),
            benefitType = "discount",
            discountAmount = 500,
            productScope = "対象の化粧品のみ",
            note = "クーポン提示で適用\n アプリ会員限定 ",
            ineligibleNote = "セール品は対象外",
            startDate = "2026-08-01",
            daysOfMonth = listOf(20, 30),
            minPurchase = 3000,
            minPurchaseScope = MIN_PURCHASE_SCOPE_PERIOD_TOTAL,
            usageLimit = 1,
            perTransactionCap = 500,
            periodTotalCap = 2000,
            capNote = "ポイントは翌月付与",
            detailUrl = "https://example.com/coupon",
        ).toCampaigns(::operatorFor).single()

        assertEquals(BenefitType.DISCOUNT, BenefitType.fromString(campaign.benefitType))
        assertEquals(500, campaign.discountAmount)
        assertEquals("対象の化粧品のみ", campaign.productScope?.label)
        // 改行区切りメモは1行=1項目(空白トリム)
        assertEquals(listOf("クーポン提示で適用", "アプリ会員限定"), campaign.eligibleNotes)
        assertEquals(listOf("セール品は対象外"), campaign.ineligibleNotes)
        assertEquals("2026-08-01", campaign.periodStart)
        assertEquals(listOf(20, 30), campaign.recurrence?.daysOfMonth)
        assertEquals(3000, campaign.minPurchase)
        assertEquals(MIN_PURCHASE_SCOPE_PERIOD_TOTAL, campaign.minPurchaseScope)
        assertEquals(1, campaign.usageLimit)
        assertEquals(500, campaign.perTransactionCap)
        assertEquals(2000, campaign.periodTotalCap)
        assertEquals("ポイントは翌月付与", campaign.capNote)
        assertEquals("https://example.com/coupon", campaign.detailUrl)
    }

    @Test
    fun `旧スキーマ(単一決済フィールド)は normalized で payments へ折り畳まれる`() {
        val legacy = CustomCampaign(id = "custom:old", name = "旧形式", cardId = "smcc").normalized()
        assertEquals(listOf(CustomPayment(cardId = "smcc")), legacy.payments)
        assertNull(legacy.cardId)
        // 新形式はそのまま
        val current = custom()
        assertEquals(current, current.normalized())
    }

    @Test
    fun `同じ店名は正規化して1つの合成 Merchant に集約される`() {
        val merchants = buildCustomMerchants(
            listOf(
                custom(id = "custom:a", storeNames = listOf("駅前ベーカリー")),
                custom(id = "custom:b", storeNames = listOf("駅前ﾍﾞｰｶﾘｰ")), // 半角カナ違い
            )
        )
        assertEquals(1, merchants.size)
        assertEquals("駅前ベーカリー", merchants[0].name)
        assertEquals("keyword", merchants[0].yolpSearch)
    }

    // ---- エンジン統合 ----

    @Test
    fun `カタログのチェーンに紐付けたカスタム施策が判定に出る(施策の率を優先)`() {
        val engine = engineWith(listOf(custom(merchantIds = listOf("mcdonalds"), rate = 10.0)))
        val merchant = engine.search("マクドナルド").first()
        val result = engine.judgeAll(merchant, today)
        val judgment = result.judgments.single()
        assertEquals("custom:test", judgment.campaign.id)
        assertEquals("百貨店カード", judgment.badgeLabel)
        assertEquals(10.0, judgment.effectiveRate!!, 0.0)
        assertEquals("#123456", judgment.brandColor)
    }

    @Test
    fun `ブランド指定(Amex)の施策はブランド保有カードで判定に出る`() {
        val engine = engineWith(
            listOf(custom(payments = listOf(CustomPayment(cardBrand = "Amex")), merchantIds = listOf("mcdonalds"))),
            cards = listOf(
                // 設定「カードブランド」の保有登録で作られる仮想カードに相当
                PaymentCard(id = "owned_brand_amex", cardName = "Amexカード", brands = listOf("Amex"), brand = "Amex"),
            ),
        )
        val merchant = engine.search("マクドナルド").first()
        val judgment = engine.judgeAll(merchant, today).judgments.single()
        // ブランド施策のバッジはブランド名、色は card_brands カタログから
        assertEquals("Amex", judgment.badgeLabel)
        assertEquals("#016FD0", judgment.brandColor)
    }

    @Test
    fun `複数決済の登録はカードとQRの両方の判定に出る`() {
        val engine = engineWith(
            listOf(
                custom(
                    payments = listOf(CustomPayment(cardId = "custom:card1"), CustomPayment(qrPaymentId = "testpay")),
                    merchantIds = listOf("mcdonalds"),
                )
            ),
            qrPayments = listOf(QrPayment(id = "testpay", name = "テストペイ", brandColor = "#00AA00")),
        )
        val merchant = engine.search("マクドナルド").first()
        val result = engine.judgeAll(merchant, today, enabledQrIds = setOf("testpay"))
        assertEquals(setOf("百貨店カード", "テストペイ"), result.judgments.map { it.badgeLabel }.toSet())
        assertTrue(result.judgments.all { customCampaignBaseId(it.campaign.id) == "custom:test" })
    }

    @Test
    fun `割引型の定額はソート・表示用フィールドに乗る`() {
        val engine = engineWith(
            listOf(custom(merchantIds = listOf("mcdonalds"), benefitType = "discount", rate = null, discountAmount = 500)),
        )
        val merchant = engine.search("マクドナルド").first()
        val judgment = engine.judgeAll(merchant, today).judgments.single()
        assertEquals(BenefitType.DISCOUNT, judgment.benefitType)
        assertEquals(500, judgment.discountAmount)
        assertNull(judgment.effectiveRate)
    }

    @Test
    fun `自由入力店名は検索とPOI照合の部分一致でマッチする`() {
        val engine = engineWith(listOf(custom(storeNames = listOf("駅前ベーカリー"), rate = 8.0)))
        // お店タブ: 店名検索
        assertTrue(engine.search("駅前ベーカリー").any { it.id == customStoreMerchantId("駅前ベーカリー") })
        // 地図タブ: POI 名(支店付き)からの照合
        val matched = engine.matchStore("駅前ベーカリー 渋谷店")
        assertNotNull(matched)
        val result = engine.judgeAll(matched!!, today)
        assertEquals("custom:test", result.judgments.single().campaign.id)
    }

    @Test
    fun `同じ店名を対象にした2施策はどちらも判定に出る`() {
        val engine = engineWith(
            listOf(
                custom(id = "custom:a", storeNames = listOf("駅前ベーカリー"), rate = 5.0),
                custom(id = "custom:b", storeNames = listOf("駅前ベーカリー"), rate = 3.0),
            )
        )
        val matched = engine.matchStore("駅前ベーカリー本店")
        assertNotNull(matched)
        assertEquals(
            setOf("custom:a", "custom:b"),
            engine.judgeAll(matched!!, today).judgments.map { it.campaign.id }.toSet(),
        )
    }

    @Test
    fun `開始前はもうすぐ開始扱いで判定に出ない`() {
        val engine = engineWith(listOf(custom(merchantIds = listOf("mcdonalds"), startDate = "2026-08-01")))
        val merchant = engine.search("マクドナルド").first()
        assertTrue(engine.judgeAll(merchant, today).judgments.isEmpty())
        assertEquals(1, engine.upcomingCampaigns(today).size)
        // 開始後は判定に出る
        assertEquals(1, engine.judgeAll(merchant, LocalDate.of(2026, 8, 1)).judgments.size)
    }

    @Test
    fun `曜日指定(recurrence)は対象日のみ判定に出る`() {
        // 2026-07-20 は月曜
        val engine = engineWith(listOf(custom(merchantIds = listOf("mcdonalds"), daysOfWeek = listOf("SAT", "SUN"))))
        val merchant = engine.search("マクドナルド").first()
        assertTrue(engine.judgeAll(merchant, today).judgments.isEmpty())
        val saturday = LocalDate.of(2026, 7, 25)
        val judgment = engine.judgeAll(merchant, saturday).judgments.single()
        assertTrue(judgment.todayIsTarget)
    }

    @Test
    fun `終了日を過ぎると判定と開催中一覧から消える`() {
        val engine = engineWith(listOf(custom(merchantIds = listOf("mcdonalds"), endDate = "2026-07-19")))
        val merchant = engine.search("マクドナルド").first()
        assertTrue(engine.judgeAll(merchant, today).judgments.isEmpty())
        assertTrue(engine.activeCampaigns(today).isEmpty())
    }

    @Test
    fun `終了日なしは常設として判定に出続ける(期間限定バッジなし)`() {
        val engine = engineWith(listOf(custom(merchantIds = listOf("mcdonalds"), endDate = null)))
        val merchant = engine.search("マクドナルド").first()
        val judgment = engine.judgeAll(merchant, today).judgments.single()
        assertNull(judgment.daysRemaining)
        assertFalse(judgment.campaign.periodEnd != null)
    }

    @Test
    fun `率なし(メモのみ)の施策はカードの常設率で代替されず率なしのまま出る`() {
        val engine = engineWith(
            listOf(custom(merchantIds = listOf("mcdonalds"), rate = null, note = "ケーキ1個無料")),
            cards = listOf(
                // 常設実効率を持つカードに紐付けても、施策の率としては表示しない
                PaymentCard(id = "custom:card1", cardName = "百貨店カード", effectiveRateDefault = 1.0),
            ),
        )
        val merchant = engine.search("マクドナルド").first()
        val result = engine.judgeAll(merchant, today)
        val judgment = result.judgments.single()
        assertNull(judgment.effectiveRate)
        assertEquals(listOf("ケーキ1個無料"), judgment.eligibleNotes)
        // 率が無いので「最良特典」比較にも載らない
        assertNull(result.bestOption)
    }

    @Test
    fun `自由入力店名は有効期間中だけ YOLP キーワード検索の対象になる`() {
        val customCampaigns = listOf(custom(storeNames = listOf("駅前ベーカリー"), endDate = "2026-08-31"))
        val merchants = buildCustomMerchants(customCampaigns)
        val engine = engineWith(customCampaigns)
        val activeIds = engine.activeManagedMerchantIds(today)
        assertTrue(customStoreMerchantId("駅前ベーカリー") in activeIds)
        val config = YolpSearchConfig.build(YolpConfig(), merchants, activeIds)
        assertEquals(listOf("駅前ベーカリー"), config.keywordQueries)
        // 期限切れ後は検索対象からも消える
        val expiredIds = engine.activeManagedMerchantIds(LocalDate.of(2026, 9, 1))
        assertTrue(customStoreMerchantId("駅前ベーカリー") !in expiredIds)
    }

    @Test
    fun `既存の promotion(率あり)の挙動は変わらない`() {
        // usesCardRate の変更(promotion は率なしでもカード率で代替しない)が
        // 率ありの promotion に影響しないことの確認
        val data = PoikatsuData(
            merchants = listOf(Merchant(id = "mcdonalds", name = "マクドナルド", reading = "まくどなるど", category = "ファストフード")),
            campaigns = listOf(
                com.ktakjm.poikatsu.data.Campaign(
                    id = "promo1",
                    operator = "テスト",
                    cardId = "card1",
                    name = "期間限定10%",
                    rateBase = 10.0,
                    type = "promotion",
                    merchantRules = listOf(MerchantRule(merchantId = "mcdonalds")),
                ),
            ),
            cards = listOf(PaymentCard(id = "card1", cardName = "テストカード", effectiveRateDefault = 1.0)),
            updatedAt = "2026-07-01",
        )
        val engine = JudgmentEngine(data)
        val merchant = engine.search("マクドナルド").first()
        // 施策の率(10%)がカードの常設率(1%)より優先される(従来どおり)
        assertEquals(10.0, engine.judgeAll(merchant, today).judgments.single().effectiveRate!!, 0.0)
    }

    @Test
    fun `期限切れ判定は登録単位で揃う(CampaignStatus)`() {
        val expired = custom(
            payments = listOf(CustomPayment(cardId = "custom:card1"), CustomPayment(qrPaymentId = "testpay")),
            merchantIds = listOf("mcdonalds"),
            endDate = "2026-07-01",
        )
        val engine = engineWith(listOf(expired), qrPayments = listOf(QrPayment(id = "testpay", name = "テストペイ", brandColor = "#00AA00")))
        val statuses = expired.toCampaigns(::operatorFor).map { engine.campaignStatus(it, today) }
        assertTrue(statuses.all { it == CampaignStatus.EXPIRED })
    }
}
