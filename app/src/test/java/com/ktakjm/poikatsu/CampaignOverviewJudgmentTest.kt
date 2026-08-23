package com.ktakjm.poikatsu

import com.ktakjm.poikatsu.data.Campaign
import com.ktakjm.poikatsu.data.MerchantRule
import com.ktakjm.poikatsu.data.OfficialStoreList
import com.ktakjm.poikatsu.data.PaymentCard
import com.ktakjm.poikatsu.data.PointCurrency
import com.ktakjm.poikatsu.data.PointMultiplier
import com.ktakjm.poikatsu.data.PoikatsuData
import com.ktakjm.poikatsu.data.QrAppPackage
import com.ktakjm.poikatsu.data.QrPayment
import com.ktakjm.poikatsu.data.Recurrence
import com.ktakjm.poikatsu.domain.AppLink
import com.ktakjm.poikatsu.domain.JudgmentEngine
import com.ktakjm.poikatsu.domain.WALLET_APP_LABEL
import com.ktakjm.poikatsu.domain.WALLET_APP_PACKAGE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 店舗文脈なし(merchant/rule 抜き)の施策詳細判定 [JudgmentEngine.judgeCampaignOverview] と
 * 払い出し通貨解決 [JudgmentEngine.payoutCurrencyOf] のテスト(#85)。
 * 従来 MainViewModel.onSelectCampaignGroup が手組みしていた挙動を engine 側の仕様として固定する。
 * フィクスチャデータのみで実データには依存しない。
 */
class CampaignOverviewJudgmentTest {

    private val vpoint = PointCurrency(
        id = "vpoint",
        name = "Vポイント",
        pointMultiplier = PointMultiplier(label = "ウエル活", factor = 1.5),
        multiplierEnabled = true,
    )
    private val ponta = PointCurrency(id = "ponta", name = "Pontaポイント")
    private val dpoint = PointCurrency(
        id = "dpoint",
        name = "dポイント",
        brandColor = "#CC0033",
        membershipProgram = true,
    )

    // エンジンに渡すのは所有カードのみ(MainViewModel.rebuild の engineData と同じ構図)
    private val ownedCard = PaymentCard(
        id = "smcc",
        cardName = "三井住友カード",
        effectiveRateDefault = 7.0,
        brandColor = "#00611F",
        pointCurrencyId = "vpoint",
    )

    // 未所有カードは表示用カタログ(displayData)にだけ存在する
    private val unownedCard = PaymentCard(id = "epos", cardName = "エポスカード", brandColor = "#BE0028")

    private val aupay = QrPayment(
        id = "aupay",
        name = "au PAY",
        brandColor = "#EB5505",
        pointCurrencyId = "ponta",
        appPackages = listOf(QrAppPackage(packageName = "com.kddi.android.au_wallet", label = "au PAY")),
    )

    private val today = LocalDate.of(2026, 6, 28) // 日曜

    private fun engineDataOf(vararg campaigns: Campaign) = PoikatsuData(
        merchants = emptyList(),
        campaigns = campaigns.toList(),
        cards = listOf(ownedCard),
        qrPayments = listOf(aupay),
        pointCurrencies = listOf(vpoint, ponta, dpoint),
        updatedAt = "2026-06-01",
    )

    /** 表示用カタログ: 所有に関わらず全カードを持つ */
    private fun catalogOf(engineData: PoikatsuData) =
        engineData.copy(cards = listOf(ownedCard, unownedCard))

    // ---- judgeCampaignOverview ----

    @Test
    fun `QR施策はバッジ・通貨・起動リンクをQRカタログから解決する`() {
        val campaign = Campaign(
            id = "qr1",
            operator = "au",
            name = "au PAY還元",
            type = "promotion",
            paymentMethodId = "aupay",
            rateBase = 10.0,
            periodEnd = "2026-06-30",
            usageLimit = 1,
            ineligibleNotes = listOf("一部店舗対象外"),
            overviewIneligibleNotes = listOf("請求書払いは対象外"),
        )
        val engine = JudgmentEngine(engineDataOf(campaign))

        val j = engine.judgeCampaignOverview(listOf(campaign), today).single()

        assertEquals("au PAY", j.badgeLabel)
        assertEquals("#EB5505", j.brandColor)
        assertEquals("Pontaポイント", j.payoutCurrencyName)
        assertEquals(10.0, j.effectiveRate!!, 0.0)
        assertEquals(10.0, j.nominalRate!!, 0.0)
        assertEquals(listOf(AppLink("com.kddi.android.au_wallet", "au PAYアプリ")), j.appLinks)
        assertTrue(j.warnings.contains("残り2日"))
        // 施策全体ビュー専用の注記(overview_ineligible_notes)は campaign 直下に連結される
        assertEquals(listOf("一部店舗対象外", "請求書払いは対象外"), j.ineligibleNotes)
        assertEquals("お一人様1回まで", j.usageLimitText)
        assertNull(j.storeListUrl)
    }

    @Test
    fun `所有カードのcard_program施策はカード実効率に通貨換算を掛けて判定する`() {
        val campaign = Campaign(
            id = "card1",
            operator = "三井住友カード",
            name = "タッチ決済還元",
            cardId = "smcc",
            eligibleWallets = listOf("google_pay"),
        )
        val engine = JudgmentEngine(engineDataOf(campaign))

        val j = engine.judgeCampaignOverview(listOf(campaign), today).single()

        // QR でもブランドでも提示でもない施策のバッジは運営者名
        assertEquals("三井住友カード", j.badgeLabel)
        assertEquals("#00611F", j.brandColor)
        assertEquals(7.0, j.nominalRate!!, 0.0)
        // ウエル活(×1.5)有効: 実質率は名目 × 倍率
        assertEquals(10.5, j.effectiveRate!!, 0.0)
        assertTrue(j.welcatsuApplied)
        assertNotNull(j.pointMultiplier)
        assertEquals("Vポイント", j.payoutCurrencyName)
        // QR 起動リンクの無い施策は Google Pay 対象明記時のみウォレットリンクを出す
        assertEquals(listOf(AppLink(WALLET_APP_PACKAGE, WALLET_APP_LABEL)), j.appLinks)
    }

    @Test
    fun `未所有カードの施策は施策側の率と表示カタログの色で判定する`() {
        val campaign = Campaign(
            id = "epos1",
            operator = "エポスカード",
            name = "エポス優待",
            type = "promotion",
            cardId = "epos",
            rateBase = 5.0,
        )
        val engineData = engineDataOf(campaign)
        val engine = JudgmentEngine(engineData)

        val j = engine.judgeCampaignOverview(listOf(campaign), today, catalogOf(engineData)).single()

        // 所有カードに無い card_id: 率は施策側へフォールバック、通貨は解決されない
        assertEquals(5.0, j.nominalRate!!, 0.0)
        assertEquals(5.0, j.effectiveRate!!, 0.0)
        assertNull(j.payoutCurrencyName)
        assertFalse(j.welcatsuApplied)
        // 色は表示用カタログ(未所有カード込み)から引く
        assertEquals("#BE0028", j.brandColor)
    }

    @Test
    fun `ブランド施策はブランド名・提示施策はプログラム名をバッジに出す`() {
        val brandCampaign = Campaign(
            id = "brand1",
            operator = "Visaブランド",
            name = "Visaタッチ還元",
            type = "promotion",
            cardBrand = "Visa",
            rateBase = 3.0,
        )
        val programCampaign = Campaign(
            id = "program1",
            operator = "NTTドコモ",
            name = "dポイント提示",
            type = "promotion",
            pointProgramId = "dpoint",
            rateBase = 3.0,
        )
        val engine = JudgmentEngine(engineDataOf(brandCampaign, programCampaign))

        val judgments = engine.judgeCampaignOverview(listOf(brandCampaign, programCampaign), today)

        assertEquals("Visa", judgments[0].badgeLabel)
        assertEquals("dポイント", judgments[1].badgeLabel)
        // 提示施策の通貨・色はプログラム(point_currencies)から解決される
        assertEquals("dポイント", judgments[1].payoutCurrencyName)
        assertEquals("#CC0033", judgments[1].brandColor)
    }

    @Test
    fun `抽選施策は率・額を判定に載せない`() {
        val campaign = Campaign(
            id = "lottery1",
            operator = "au",
            name = "抽選還元",
            type = "promotion",
            paymentMethodId = "aupay",
            benefitType = "lottery",
            rateBase = 10.0,
            discountAmount = 500,
        )
        val engine = JudgmentEngine(engineDataOf(campaign))

        val j = engine.judgeCampaignOverview(listOf(campaign), today).single()

        assertNull(j.effectiveRate)
        assertNull(j.nominalRate)
        assertNull(j.discountAmount)
    }

    @Test
    fun `非対象日は次の対象日を案内する`() {
        val campaign = Campaign(
            id = "sat1",
            operator = "au",
            name = "土曜還元",
            type = "promotion",
            paymentMethodId = "aupay",
            rateBase = 10.0,
            periodStart = "2026-06-01",
            periodEnd = "2026-07-31",
            recurrence = Recurrence(daysOfWeek = listOf("SAT")),
        )
        val engine = JudgmentEngine(engineDataOf(campaign))

        val j = engine.judgeCampaignOverview(listOf(campaign), today).single()

        assertFalse(j.todayIsTarget)
        assertEquals(LocalDate.of(2026, 7, 4), j.nextTargetDate)
    }

    @Test
    fun `施策全体ビューは施策単位の網羅性と店舗別レートの有無を反映する`() {
        val campaign = Campaign(
            id = "vary1",
            operator = "エポスカード",
            name = "店舗別優待",
            type = "promotion",
            cardBrand = "Visa",
            rateBase = 5.0,
            merchantRules = listOf(
                MerchantRule(
                    merchantId = "m1",
                    rateOverride = 3.0,
                    storeListUrl = "https://example.com/stores",
                    officialStoreList = OfficialStoreList(
                        eligibleStores = listOf("浦和店"),
                        listIsExhaustive = true,
                        updatedDate = "2026-06-01",
                    ),
                ),
                MerchantRule(
                    merchantId = "m2",
                    rateOverride = 5.0,
                    officialStoreList = OfficialStoreList(
                        eligibleStores = listOf("大宮店"),
                        listIsExhaustive = true,
                        updatedDate = "2026-06-01",
                    ),
                ),
            ),
        )
        val engine = JudgmentEngine(engineDataOf(campaign))

        val j = engine.judgeCampaignOverview(listOf(campaign), today).single()

        assertTrue(j.exhaustiveStoreList)
        assertTrue(j.rateVariesByStore)
        // チェーン非依存のビューに特定チェーンの店舗リスト URL は出さない
        assertNull(j.storeListUrl)
    }

    // ---- payoutCurrencyOf ----

    @Test
    fun `payoutCurrencyOfはカード・QR・明示指定の通貨を解決する`() {
        val cardCampaign = Campaign(id = "c1", operator = "三井住友カード", name = "カード施策", cardId = "smcc")
        val qrCampaign = Campaign(
            id = "q1", operator = "au", name = "QR施策",
            type = "promotion", paymentMethodId = "aupay", rateBase = 1.0,
        )
        val explicitCampaign = Campaign(
            id = "e1", operator = "au", name = "明示施策",
            type = "promotion", paymentMethodId = "aupay", rateBase = 1.0, pointCurrencyId = "dpoint",
        )
        val discountCampaign = Campaign(
            id = "d1", operator = "au", name = "割引施策",
            type = "promotion", paymentMethodId = "aupay", benefitType = "discount", discountAmount = 100,
        )
        val engine = JudgmentEngine(engineDataOf(cardCampaign, qrCampaign, explicitCampaign, discountCampaign))

        assertEquals("vpoint", engine.payoutCurrencyOf(cardCampaign)?.id)
        assertEquals("ponta", engine.payoutCurrencyOf(qrCampaign)?.id)
        assertEquals("dpoint", engine.payoutCurrencyOf(explicitCampaign)?.id)
        // rebate 以外に通貨の概念は無い
        assertNull(engine.payoutCurrencyOf(discountCampaign))
    }
}
