package com.ktakjm.poikatsu

import com.ktakjm.poikatsu.data.Attribution
import com.ktakjm.poikatsu.data.Campaign
import com.ktakjm.poikatsu.data.CustomPayment
import com.ktakjm.poikatsu.data.StoreScope
import com.ktakjm.poikatsu.data.attribution
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Campaign の導出プロパティ(#86)のテスト。JSON スキーマ(生 String / nullable 4種)は不変で、
 * パース後の型付き導出(StoreScope / Attribution)が正しく写ることを確認する。
 */
class CampaignModelTest {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    @Test
    fun `fromString_managedとexternalを対応するenumへ`() {
        assertEquals(StoreScope.MANAGED, StoreScope.fromString("managed"))
        assertEquals(StoreScope.EXTERNAL, StoreScope.fromString("external"))
    }

    @Test
    fun `fromString_不明値はMANAGEDにフォールバック`() {
        assertEquals(StoreScope.MANAGED, StoreScope.fromString("unknown_value"))
        assertEquals(StoreScope.MANAGED, StoreScope.fromString(""))
    }

    @Test
    fun `JSONのstore_scopeが導出プロパティでenumになる`() {
        val c = json.decodeFromString<Campaign>(
            """{"id":"c1","operator":"op","name":"n","verified_date":"2026-01-01","store_scope":"external"}""",
        )
        assertEquals(StoreScope.EXTERNAL, c.storeScope)
    }

    private fun campaign(
        cardId: String? = null,
        cardBrand: String? = null,
        paymentMethodId: String? = null,
        pointProgramId: String? = null,
    ) = Campaign(
        id = "c1", operator = "op", name = "n", verifiedDate = "2026-01-01",
        cardId = cardId, cardBrand = cardBrand,
        paymentMethodId = paymentMethodId, pointProgramId = pointProgramId,
    )

    @Test
    fun `attribution_帰属4種がそれぞれ対応するAttributionになる`() {
        assertEquals(Attribution.Card("epos"), campaign(cardId = "epos").attribution)
        assertEquals(Attribution.Brand("Amex"), campaign(cardBrand = "Amex").attribution)
        assertEquals(Attribution.Qr("paypay"), campaign(paymentMethodId = "paypay").attribution)
        assertEquals(Attribution.Program("dpoint"), campaign(pointProgramId = "dpoint").attribution)
    }

    @Test
    fun `attribution_帰属なしはnull`() {
        assertNull(campaign().attribution)
    }

    @Test
    fun `CustomPaymentのattributionも同じ型に写る`() {
        assertEquals(Attribution.Card("epos"), CustomPayment(cardId = "epos").attribution)
        assertEquals(Attribution.Qr("paypay"), CustomPayment(qrPaymentId = "paypay").attribution)
        assertEquals(Attribution.Brand("Visa"), CustomPayment(cardBrand = "Visa").attribution)
        assertNull(CustomPayment().attribution)
    }

    @Test
    fun `store_scope省略時はMANAGED`() {
        val c = json.decodeFromString<Campaign>(
            """{"id":"c1","operator":"op","name":"n","verified_date":"2026-01-01"}""",
        )
        assertEquals(StoreScope.MANAGED, c.storeScope)
    }
}
