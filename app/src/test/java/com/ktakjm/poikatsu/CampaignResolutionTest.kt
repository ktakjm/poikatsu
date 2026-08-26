package com.ktakjm.poikatsu

import com.ktakjm.poikatsu.data.Attribution
import com.ktakjm.poikatsu.data.Campaign
import com.ktakjm.poikatsu.data.MunicipalDefaults
import com.ktakjm.poikatsu.data.PaymentCard
import com.ktakjm.poikatsu.data.PaymentVariant
import com.ktakjm.poikatsu.data.PointCurrency
import com.ktakjm.poikatsu.data.QrPayment
import com.ktakjm.poikatsu.domain.applyMunicipalDefaults
import com.ktakjm.poikatsu.domain.deriveOperator
import com.ktakjm.poikatsu.domain.expandPaymentVariants
import com.ktakjm.poikatsu.domain.resolveCampaigns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * campaigns.json の記述形 → 解決済み Campaign への変換(#89)。
 * municipal の payment_variants 展開・QR サービス既定の補完・operator 導出を、
 * エンジン・UI が前提にする「1 施策 = 1 決済手段・全フィールド解決済み」の形に写すことを固定する。
 */
class CampaignResolutionTest {

    private val cards = listOf(
        PaymentCard(id = "dcard", cardName = "dカード"),
        PaymentCard(id = "epos", cardName = "エポスカード"),
    )
    private val qrPayments = listOf(
        QrPayment(
            id = "paypay", name = "PayPay", brandColor = "#FF0033",
            municipalDefaults = MunicipalDefaults(
                paymentInstruction = "PayPay残高・PayPayクレジットで支払う",
                ineligibleNotes = listOf("クレジットカード(PayPayクレジット以外)の併用は対象外"),
            ),
        ),
        QrPayment(id = "aupay", name = "au PAY", brandColor = "#FF5722"),
    )
    private val currencies = listOf(PointCurrency(id = "dpoint", name = "dポイント"))

    private fun municipal(vararg variants: PaymentVariant) = Campaign(
        id = "yuzawa_2026_07",
        type = "municipal",
        storeScopeRaw = "external",
        name = "湯沢市キャッシュレスキャンペーン",
        rateBase = 10.0,
        detailUrl = "https://example.com/shared",
        eligibleNotes = listOf("市外在住も対象"),
        ineligibleNotes = listOf("公共料金は対象外"),
        memo = listOf("共通メモ"),
        verifiedDate = "2026-08-01",
        paymentVariants = variants.toList(),
    )

    @Test
    fun `variantsが無い施策は展開せずそのまま1件`() {
        val c = Campaign(id = "x", name = "n", paymentMethodId = "paypay", type = "promotion")
        assertEquals(listOf(c), expandPaymentVariants(c))
    }

    @Test
    fun `variantごとに1施策へ展開しidは施策id_決済id`() {
        val expanded = expandPaymentVariants(
            municipal(PaymentVariant("paypay"), PaymentVariant("aupay")),
        )
        assertEquals(listOf("yuzawa_2026_07_paypay", "yuzawa_2026_07_aupay"), expanded.map { it.id })
        assertEquals(listOf("paypay", "aupay"), expanded.map { it.paymentMethodId })
        assertTrue("展開後は paymentVariants を持たない", expanded.all { it.paymentVariants.isEmpty() })
    }

    @Test
    fun `共通フィールドは全variantに継承される`() {
        val c = expandPaymentVariants(municipal(PaymentVariant("paypay"))).single()
        assertEquals("湯沢市キャッシュレスキャンペーン", c.name)
        assertEquals(10.0, c.rateBase)
        assertEquals("municipal", c.type)
        assertEquals("https://example.com/shared", c.detailUrl)
        assertEquals("2026-08-01", c.verifiedDate)
    }

    @Test
    fun `単値フィールドはvariant側がnon-nullなら上書き`() {
        val c = expandPaymentVariants(
            municipal(
                PaymentVariant(
                    paymentMethodId = "aupay",
                    detailUrl = "https://example.com/aupay",
                    storeSearchUrl = "https://example.com/aupay/stores",
                    verifiedDate = "2026-08-20",
                    pointCurrencyId = "aupay_balance",
                    paymentInstruction = "au PAYのコード支払いで支払う",
                ),
            ),
        ).single()
        assertEquals("https://example.com/aupay", c.detailUrl)
        assertEquals("https://example.com/aupay/stores", c.storeSearchUrl)
        assertEquals("2026-08-20", c.verifiedDate)
        assertEquals("aupay_balance", c.pointCurrencyId)
        assertEquals("au PAYのコード支払いで支払う", c.paymentInstruction)
    }

    @Test
    fun `notesとmemoは共通側の後ろにvariant分を連結`() {
        val c = expandPaymentVariants(
            municipal(
                PaymentVariant(
                    paymentMethodId = "paypay",
                    eligibleNotes = listOf("PayPay限定の対象"),
                    ineligibleNotes = listOf("PayPay商品券は対象外"),
                    memo = listOf("PayPay側メモ"),
                ),
            ),
        ).single()
        assertEquals(listOf("市外在住も対象", "PayPay限定の対象"), c.eligibleNotes)
        assertEquals(listOf("公共料金は対象外", "PayPay商品券は対象外"), c.ineligibleNotes)
        assertEquals(listOf("共通メモ", "PayPay側メモ"), c.memo)
    }

    @Test
    fun `municipalの空payment_instructionはサービス既定で補われる`() {
        val c = expandPaymentVariants(municipal(PaymentVariant("paypay"))).single()
        assertEquals("PayPay残高・PayPayクレジットで支払う", applyMunicipalDefaults(c, qrPayments).paymentInstruction)
    }

    @Test
    fun `施策側のpayment_instructionは既定より優先`() {
        val c = expandPaymentVariants(
            municipal(PaymentVariant("paypay", paymentInstruction = "PayPay残高・PayPayクレジット・PayPayポイントで支払う")),
        ).single()
        assertEquals(
            "PayPay残高・PayPayクレジット・PayPayポイントで支払う",
            applyMunicipalDefaults(c, qrPayments).paymentInstruction,
        )
    }

    @Test
    fun `既定のineligible_notesは施策側の末尾に連結し同文は重複させない`() {
        val fresh = expandPaymentVariants(municipal(PaymentVariant("paypay"))).single()
        assertEquals(
            listOf("公共料金は対象外", "クレジットカード(PayPayクレジット以外)の併用は対象外"),
            applyMunicipalDefaults(fresh, qrPayments).ineligibleNotes,
        )
        val dup = expandPaymentVariants(
            municipal(PaymentVariant("paypay", ineligibleNotes = listOf("クレジットカード(PayPayクレジット以外)の併用は対象外"))),
        ).single()
        assertEquals(
            listOf("公共料金は対象外", "クレジットカード(PayPayクレジット以外)の併用は対象外"),
            applyMunicipalDefaults(dup, qrPayments).ineligibleNotes,
        )
    }

    @Test
    fun `既定を持たないサービス・municipal以外には何もしない`() {
        val aupay = expandPaymentVariants(municipal(PaymentVariant("aupay"))).single()
        assertEquals(aupay, applyMunicipalDefaults(aupay, qrPayments))
        // promotion は PayPay 帰属でも既定を掛けない(キャンペーンごとに条件が違う)
        val promo = Campaign(id = "p", name = "n", type = "promotion", paymentMethodId = "paypay", paymentInstruction = "PayPayアプリで支払う")
        assertEquals(promo, applyMunicipalDefaults(promo, qrPayments))
    }

    @Test
    fun `operatorは帰属先カタログの名前から導出される`() {
        assertEquals("dカード", deriveOperator(Attribution.Card("dcard"), cards, qrPayments, currencies))
        assertEquals("Amex", deriveOperator(Attribution.Brand("Amex"), cards, qrPayments, currencies))
        assertEquals("au PAY", deriveOperator(Attribution.Qr("aupay"), cards, qrPayments, currencies))
        assertEquals("dポイント", deriveOperator(Attribution.Program("dpoint"), cards, qrPayments, currencies))
        assertNull(deriveOperator(Attribution.Card("unknown"), cards, qrPayments, currencies))
        assertNull(deriveOperator(null, cards, qrPayments, currencies))
    }

    @Test
    fun `resolveCampaignsは展開_既定補完_operator導出を一括で行い明示operatorは残す`() {
        val explicit = Campaign(id = "e", name = "n", type = "card_program", cardId = "dcard", operator = "NTTドコモ", paymentInstruction = "dカードで支払う")
        val derived = Campaign(id = "d", name = "n", type = "card_program", cardId = "epos", paymentInstruction = "エポスカードで支払う")
        val resolved = resolveCampaigns(
            listOf(explicit, derived, municipal(PaymentVariant("paypay"), PaymentVariant("aupay", paymentInstruction = "au PAYのコード支払いで支払う"))),
            cards, qrPayments, currencies,
        )
        assertEquals(listOf("e", "d", "yuzawa_2026_07_paypay", "yuzawa_2026_07_aupay"), resolved.map { it.id })
        assertEquals(listOf("NTTドコモ", "エポスカード", "PayPay", "au PAY"), resolved.map { it.operator })
        val paypay = resolved[2]
        assertEquals("PayPay残高・PayPayクレジットで支払う", paypay.paymentInstruction)
        assertEquals(listOf("公共料金は対象外", "クレジットカード(PayPayクレジット以外)の併用は対象外"), paypay.ineligibleNotes)
        assertEquals(listOf("公共料金は対象外"), resolved[3].ineligibleNotes)
    }
}
