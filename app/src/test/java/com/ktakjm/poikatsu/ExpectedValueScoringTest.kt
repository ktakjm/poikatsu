package com.ktakjm.poikatsu

import com.ktakjm.poikatsu.data.Campaign
import com.ktakjm.poikatsu.data.MIN_PURCHASE_SCOPE_PERIOD_TOTAL
import com.ktakjm.poikatsu.data.MIN_PURCHASE_SCOPE_TRANSACTION
import com.ktakjm.poikatsu.data.PointBalance
import com.ktakjm.poikatsu.data.PointCurrency
import com.ktakjm.poikatsu.data.PointMultiplier
import com.ktakjm.poikatsu.data.ProductScope
import com.ktakjm.poikatsu.domain.BenefitType
import com.ktakjm.poikatsu.domain.BestPaymentOption
import com.ktakjm.poikatsu.domain.CampaignJudgment
import com.ktakjm.poikatsu.domain.breakevenAmount
import com.ktakjm.poikatsu.domain.compositeValueYen
import com.ktakjm.poikatsu.domain.currencyValueFactor
import com.ktakjm.poikatsu.domain.nominalRateNote
import com.ktakjm.poikatsu.domain.effectiveValueRate
import com.ktakjm.poikatsu.domain.expiringPointNotices
import com.ktakjm.poikatsu.domain.fixedBenefitAdvice
import com.ktakjm.poikatsu.domain.isExpired
import com.ktakjm.poikatsu.domain.multiplierBadgeLabel
import com.ktakjm.poikatsu.domain.stackedRate
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

/** 期待価値スコア(円換算の実質還元率)の純関数(#13)。フィクスチャのみで実データに依存しない */
class ExpectedValueScoringTest {

    private val multiplier = PointMultiplier(label = "倍率", factor = 1.5)

    @Test
    fun `係数は1pt価値と有効な倍率の積`() {
        assertEquals(1.0, currencyValueFactor(null), 0.0)
        assertEquals(0.5, currencyValueFactor(PointCurrency(id = "c", name = "", valueYen = 0.5)), 0.0)
        assertEquals(
            0.75,
            currencyValueFactor(
                PointCurrency(id = "c", name = "", valueYen = 0.5, pointMultiplier = multiplier, multiplierEnabled = true),
            ),
            1e-9,
        )
        // 倍率OFFなら factor は掛からない
        assertEquals(
            0.5,
            currencyValueFactor(
                PointCurrency(id = "c", name = "", valueYen = 0.5, pointMultiplier = multiplier, multiplierEnabled = false),
            ),
            1e-9,
        )
    }

    @Test
    fun `合成後の1pt価値は1pt価値と倍率の両方が効いているときだけ返る`() {
        // 設定画面の注記用(#83)。1pt価値と倍率は積になるため、両方が中立でないときは
        // 合成後の値を出して二重適用を黙らせない
        assertEquals(
            0.75,
            compositeValueYen(
                PointCurrency(id = "c", name = "", valueYen = 0.5, pointMultiplier = multiplier, multiplierEnabled = true),
            )!!,
            1e-9,
        )
        // 1pt価値が等価なら合成値は倍率そのもので、説明する数字が無い
        assertNull(
            compositeValueYen(
                PointCurrency(id = "c", name = "", valueYen = 1.0, pointMultiplier = multiplier, multiplierEnabled = true),
            ),
        )
        // 倍率OFF・倍率定義なしはそもそも合成されない
        assertNull(
            compositeValueYen(
                PointCurrency(id = "c", name = "", valueYen = 0.5, pointMultiplier = multiplier, multiplierEnabled = false),
            ),
        )
        assertNull(compositeValueYen(PointCurrency(id = "c", name = "", valueYen = 0.5)))
    }

    @Test
    fun `倍率バッジは実際に適用されているときだけ倍率を併記する`() {
        // 選択肢を持つ通貨(Ponta の交換所 1.1/1.5)では、未設定のまま既定値が併記されると
        // ユーザーが選んでいない倍率を提示してしまう。併記の条件は「表示中の率に実際に
        // 掛かっているか」= appliedNote と同じ不変条件に揃える
        val exchange = PointMultiplier(
            label = "ポイント交換所を利用時の還元率を表示",
            factor = 1.1,
            factorOptions = listOf(1.1, 1.5),
            badgeLabel = "ポイント交換所",
        )
        assertEquals("ポイント交換所 ×1.1", multiplierBadgeLabel(exchange, applied = true))
        assertEquals("ポイント交換所", multiplierBadgeLabel(exchange, applied = false))
        // 選択肢を持たない通貨(ウエル活)も同じ扱い: OFF なら倍率を出さない
        val welcatsu = PointMultiplier(label = "ウエル活", factor = 1.5, badgeLabel = "ウエル活利用可")
        assertEquals("ウエル活利用可 ×1.5", multiplierBadgeLabel(welcatsu, applied = true))
        assertEquals("ウエル活利用可", multiplierBadgeLabel(welcatsu, applied = false))
        // バッジ文言が無い / 通貨が無いならバッジ自体を出さない
        assertNull(multiplierBadgeLabel(PointMultiplier(label = "x", factor = 1.5), applied = true))
        assertNull(multiplierBadgeLabel(null, applied = true))
    }

    @Test
    fun `実質率は名目率×係数でnullは素通し`() {
        val welcatsu = PointCurrency(id = "vp", name = "", pointMultiplier = multiplier, multiplierEnabled = true)
        assertEquals(10.5, effectiveValueRate(7.0, welcatsu)!!, 1e-9)
        assertNull(effectiveValueRate(null, welcatsu))
        assertEquals(7.0, effectiveValueRate(7.0, null)!!, 0.0)
    }

    @Test
    fun `額面併記の注記は名目と実質が異なるときだけ`() {
        assertEquals("額面7%", nominalRateNote(7.0, 10.5))
        assertNull(nominalRateNote(7.0, 7.0))
        assertNull(nominalRateNote(null, 10.5))
        assertNull(nominalRateNote(7.0, null))
    }

    @Test
    fun `失効30日以内の残高だけ通知になり近い順に並ぶ`() {
        val currencies = listOf(
            PointCurrency(id = "rp", name = "楽天ポイント"),
            PointCurrency(id = "dp", name = "dポイント"),
            PointCurrency(id = "vp", name = "Vポイント"),
        )
        val today = LocalDate.of(2026, 8, 19)
        val notices = expiringPointNotices(
            balances = mapOf(
                "rp" to PointBalance(balancePt = 500, expiryDate = "2026-08-22"), // 残り3日
                "dp" to PointBalance(balancePt = 300, expiryDate = "2026-09-10"), // 残り22日
                "vp" to PointBalance(balancePt = 900, expiryDate = "2026-12-01"), // 30日超=通知しない
            ),
            currencies = currencies,
            today = today,
        )
        assertEquals(listOf("rp", "dp"), notices.map { it.currencyId })
        assertEquals(3L, notices[0].daysLeft)
        assertTrue(notices[0].warn) // 7日以内は強調
        assertFalse(notices[1].warn)
        assertEquals("楽天ポイント", notices[0].currencyName)
    }

    @Test
    fun `失効日当日は通知され翌日からは失効済みで通知されない`() {
        val currencies = listOf(PointCurrency(id = "rp", name = "楽天ポイント"))
        val balance = mapOf("rp" to PointBalance(balancePt = 100, expiryDate = "2026-08-19"))
        assertEquals(1, expiringPointNotices(balance, currencies, LocalDate.of(2026, 8, 19)).size)
        assertEquals(0, expiringPointNotices(balance, currencies, LocalDate.of(2026, 8, 20)).size)
        assertTrue(balance["rp"]!!.isExpired(LocalDate.of(2026, 8, 20)))
        assertFalse(balance["rp"]!!.isExpired(LocalDate.of(2026, 8, 19)))
    }

    @Test
    fun `残高0や不正な日付や未知の通貨は通知しない`() {
        val currencies = listOf(PointCurrency(id = "rp", name = "楽天ポイント"))
        val today = LocalDate.of(2026, 8, 19)
        assertEquals(0, expiringPointNotices(mapOf("rp" to PointBalance(0, "2026-08-22")), currencies, today).size)
        assertEquals(0, expiringPointNotices(mapOf("rp" to PointBalance(100, "not-a-date")), currencies, today).size)
        assertEquals(0, expiringPointNotices(mapOf("unknown" to PointBalance(100, "2026-08-22")), currencies, today).size)
    }

    // ---- 提示スタック合算(#13) ----

    /** 提示のみ施策の最小 CampaignJudgment。合算判定に関わる effectiveRate/discountAmount/productScope 以外は仮値 */
    private fun presentationJudgment(
        rate: Double?,
        discount: Int? = null,
        productScope: ProductScope? = null,
    ): CampaignJudgment = CampaignJudgment(
        campaign = Campaign(
            id = "presentation",
            operator = "テスト事業者",
            name = "テスト提示施策",
            presentationOnly = true,
            productScope = productScope,
        ),
        badgeLabel = "テストプログラム",
        brandColor = null,
        benefitType = BenefitType.REBATE,
        effectiveRate = rate,
        discountAmount = discount,
        daysRemaining = null,
        eligibleNotes = emptyList(),
        ineligibleNotes = emptyList(),
        storeListUrl = null,
        warnings = emptyList(),
        minPurchase = null,
        usageLimitText = null,
        perTransactionCap = null,
        periodTotalCap = null,
        capNote = null,
        storeSearchUrl = null,
        detailUrl = null,
        pointMultiplier = null,
        welcatsuApplied = false,
    )

    private fun bestPaymentOption(rate: Double?) = BestPaymentOption(
        method = "テストカード",
        rate = rate,
        discountAmount = null,
        benefitType = BenefitType.REBATE,
        isTimeLimited = false,
        daysRemaining = null,
    )

    @Test
    fun `合算は定率の提示だけを足し定額と対象商品限定は無視する`() {
        val best = bestPaymentOption(rate = 7.0)
        val presentation = listOf(
            presentationJudgment(rate = 3.0),
            presentationJudgment(rate = null, discount = 100),
            presentationJudgment(rate = null),
            presentationJudgment(rate = 5.0, productScope = ProductScope(label = "対象商品")),
        )
        val stacked = stackedRate(best, presentation)!!
        assertEquals(7.0, stacked.paymentRate, 0.0)
        assertEquals(3.0, stacked.presentationRate, 0.0)
        assertEquals(10.0, stacked.totalRate, 1e-9)
        assertNull("最良が無ければ合算しない", stackedRate(null, presentation))
        assertNull("提示が無ければ合算しない", stackedRate(best, emptyList()))
    }

    // ---- 損益分岐額(#13 rebate vs coupon の損益分岐) ----

    @Test
    fun `損益分岐額は割引額÷実質率を10円単位で切り上げ`() {
        assertEquals(2000, breakevenAmount(discountAmount = 100, bestRate = 5.0))
        assertEquals(1340, breakevenAmount(discountAmount = 100, bestRate = 7.5)) // 1333.3→1340
        assertEquals(960, breakevenAmount(discountAmount = 100, bestRate = 10.5)) // 952.4→960
        assertNull(breakevenAmount(discountAmount = 100, bestRate = 0.0))
    }

    // ---- 定額特典アドバイス(#13 実機フィードバック: バナー2行目) ----

    private fun fixedJudgment(
        discount: Int,
        breakeven: Int?,
        minPurchase: Int? = null,
        minPurchaseScope: String = MIN_PURCHASE_SCOPE_TRANSACTION,
        badge: String = "au PAY",
    ): CampaignJudgment = CampaignJudgment(
        campaign = Campaign(
            id = "fixed",
            operator = "テスト事業者",
            name = "テスト定額施策",
            minPurchase = minPurchase,
            minPurchaseScope = minPurchaseScope,
        ),
        badgeLabel = badge,
        brandColor = null,
        benefitType = BenefitType.DISCOUNT,
        effectiveRate = null,
        discountAmount = discount,
        daysRemaining = null,
        eligibleNotes = emptyList(),
        ineligibleNotes = emptyList(),
        storeListUrl = null,
        warnings = emptyList(),
        minPurchase = minPurchase,
        usageLimitText = null,
        perTransactionCap = null,
        periodTotalCap = null,
        capNote = null,
        storeSearchUrl = null,
        detailUrl = null,
        pointMultiplier = null,
        welcatsuApplied = false,
        breakevenAmount = breakeven,
    )

    @Test
    fun `アドバイスは1決済ごとの最低購入額を下限にし期間合計条件は下限にしない`() {
        val advice = fixedBenefitAdvice(listOf(fixedJudgment(discount = 300, breakeven = 1000, minPurchase = 500)))!!
        assertEquals(500, advice.minPurchase)
        assertEquals(1000, advice.breakevenAmount)
        assertEquals("au PAY", advice.method)
        // 期間合計の最低購入額は1回の買い物の下限にならない
        val periodTotal = fixedBenefitAdvice(
            listOf(
                fixedJudgment(
                    discount = 300,
                    breakeven = 1000,
                    minPurchase = 5000,
                    minPurchaseScope = MIN_PURCHASE_SCOPE_PERIOD_TOTAL,
                ),
            ),
        )!!
        assertNull(periodTotal.minPurchase)
    }

    @Test
    fun `アドバイスは範囲が空の定額と分岐額なしの定額を除外し割引額最大を選ぶ`() {
        // 下限 >= 分岐額(1,000円以上でしか使えないのに 800円未満でないと得にならない)は案内しない
        assertNull(fixedBenefitAdvice(listOf(fixedJudgment(discount = 100, breakeven = 800, minPurchase = 1000))))
        // 定率の最良が無い(breakevenAmount 未付与)判定は対象外
        assertNull(fixedBenefitAdvice(listOf(fixedJudgment(discount = 100, breakeven = null))))
        // 複数あれば割引額最大を代表にする
        val advice = fixedBenefitAdvice(
            listOf(
                fixedJudgment(discount = 100, breakeven = 1430, badge = "PayPay"),
                fixedJudgment(discount = 300, breakeven = 4290, badge = "au PAY"),
            ),
        )!!
        assertEquals(300, advice.discountAmount)
        assertEquals("au PAY", advice.method)
    }
}
