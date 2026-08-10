package com.ktakjm.poikatsu

import com.ktakjm.poikatsu.data.Banner
import com.ktakjm.poikatsu.data.Campaign
import com.ktakjm.poikatsu.data.Merchant
import com.ktakjm.poikatsu.data.MerchantRule
import com.ktakjm.poikatsu.data.Region
import com.ktakjm.poikatsu.ui.campaignGroupDisplayTitle
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * キャンペーンカードの表示タイトル(campaignGroupDisplayTitle)のフォールバック連鎖を検証する。
 * display_name → 単一チェーンは看板名(banner_ids が 1 看板に絞るとき。#69)/merchant 名 →
 * 複数チェーンは「{先頭チェーン} 他Nチェーン」→ campaign.name
 */
class CampaignDisplayTitleTest {

    private val merchants = mapOf(
        "welcia" to Merchant(id = "welcia", name = "ウエルシア"),
        "sugi" to Merchant(id = "sugi", name = "スギ薬局"),
        "tsuruha" to Merchant(
            id = "tsuruha",
            name = "ツルハドラッグ",
            groupLabel = "ツルハグループ",
            banners = listOf(
                Banner(id = "kyorindo", name = "杏林堂薬局"),
                Banner(id = "fukutaro", name = "くすりの福太郎"),
            ),
        ),
    )

    private fun promotion(
        displayName: String? = null,
        merchantIds: List<String> = emptyList(),
    ) = promotionWithRules(displayName, merchantIds.map { MerchantRule(merchantId = it) })

    private fun promotionWithRules(
        displayName: String? = null,
        rules: List<MerchantRule>,
    ) = Campaign(
        id = "c1",
        operator = "テスト",
        name = "公式表記のキャンペーン名(第2弾・最大20%)",
        displayName = displayName,
        type = "promotion",
        merchantRules = rules,
    )

    @Test
    fun `display_nameがあれば最優先で使う`() {
        val c = promotion(displayName = "花王×ウエル/スギ", merchantIds = listOf("welcia", "sugi"))
        assertEquals("花王×ウエル/スギ", campaignGroupDisplayTitle(c, merchants))
    }

    @Test
    fun `単一チェーンはmerchant名`() {
        val c = promotion(merchantIds = listOf("welcia"))
        assertEquals("ウエルシア", campaignGroupDisplayTitle(c, merchants))
    }

    @Test
    fun `複数チェーンは先頭チェーンと他Nチェーン`() {
        val c = promotion(merchantIds = listOf("welcia", "sugi", "tsuruha"))
        assertEquals("ウエルシア 他2チェーン", campaignGroupDisplayTitle(c, merchants))
    }

    @Test
    fun `同一merchantの複数ルールは1チェーンと数える`() {
        // 店舗別 rate_override 等で同じ merchant に複数ルールが並ぶことがある
        val c = promotion(merchantIds = listOf("welcia", "welcia"))
        assertEquals("ウエルシア", campaignGroupDisplayTitle(c, merchants))
    }

    @Test
    fun `merchant_rulesが無ければcampaign名`() {
        val c = promotion()
        assertEquals("公式表記のキャンペーン名(第2弾・最大20%)", campaignGroupDisplayTitle(c, merchants))
    }

    // ---- 看板スコープ(#69): 系列の 1 看板だけが対象の施策を系列名で出さない ----

    @Test
    fun `banner_idsが1看板ならその看板名`() {
        // aupay_fukutaro_coupon_2026_08 型: ツルハ系列のうち福太郎だけが対象
        val c = promotionWithRules(
            rules = listOf(MerchantRule(merchantId = "tsuruha", bannerIds = listOf("fukutaro"))),
        )
        assertEquals("くすりの福太郎", campaignGroupDisplayTitle(c, merchants))
    }

    @Test
    fun `banner_idsが代表看板1件ならmerchant名`() {
        // 代表看板(banner id = merchant.id)への限定は bannerName が merchant 名を返す
        val c = promotionWithRules(
            rules = listOf(MerchantRule(merchantId = "tsuruha", bannerIds = listOf("tsuruha"))),
        )
        assertEquals("ツルハドラッグ", campaignGroupDisplayTitle(c, merchants))
    }

    @Test
    fun `banner_idsが2看板以上ならmerchant名`() {
        val c = promotionWithRules(
            rules = listOf(MerchantRule(merchantId = "tsuruha", bannerIds = listOf("fukutaro", "kyorindo"))),
        )
        assertEquals("ツルハドラッグ", campaignGroupDisplayTitle(c, merchants))
    }

    @Test
    fun `banner_ids未指定ルールが混在すれば全看板対象としてmerchant名`() {
        // rate_override 等で同じ merchant に複数ルールが並ぶとき、1 つでも全看板対象なら系列全体
        val c = promotionWithRules(
            rules = listOf(
                MerchantRule(merchantId = "tsuruha", bannerIds = listOf("fukutaro")),
                MerchantRule(merchantId = "tsuruha"),
            ),
        )
        assertEquals("ツルハドラッグ", campaignGroupDisplayTitle(c, merchants))
    }

    @Test
    fun `一部除外(ineligible_banner_ids)はmerchant名のまま`() {
        val c = promotionWithRules(
            rules = listOf(MerchantRule(merchantId = "tsuruha", ineligibleBannerIds = listOf("kyorindo"))),
        )
        assertEquals("ツルハドラッグ", campaignGroupDisplayTitle(c, merchants))
    }

    @Test
    fun `看板スコープでもdisplay_nameがあれば最優先`() {
        val c = promotionWithRules(
            displayName = "福太郎限定クーポン",
            rules = listOf(MerchantRule(merchantId = "tsuruha", bannerIds = listOf("fukutaro"))),
        )
        assertEquals("福太郎限定クーポン", campaignGroupDisplayTitle(c, merchants))
    }

    @Test
    fun `複数チェーンなら看板スコープがあっても他Nチェーン`() {
        val c = promotionWithRules(
            rules = listOf(
                MerchantRule(merchantId = "tsuruha", bannerIds = listOf("fukutaro")),
                MerchantRule(merchantId = "welcia"),
            ),
        )
        assertEquals("ツルハドラッグ 他1チェーン", campaignGroupDisplayTitle(c, merchants))
    }

    @Test
    fun `card_programは多チェーンでも他Nチェーンにせずcampaign名`() {
        // 常設プログラムは固有名で呼ぶ(チェーン列挙のタイトルにすると施策の正体が分からない)
        val c = promotion(merchantIds = listOf("welcia", "sugi", "tsuruha")).copy(type = "card_program")
        assertEquals("公式表記のキャンペーン名(第2弾・最大20%)", campaignGroupDisplayTitle(c, merchants))
    }

    @Test
    fun `card_programもdisplay_nameがあれば最優先`() {
        val c = promotion(displayName = "○○カード ポイントアップ", merchantIds = listOf("welcia"))
            .copy(type = "card_program")
        assertEquals("○○カード ポイントアップ", campaignGroupDisplayTitle(c, merchants))
    }

    @Test
    fun `自治体はregionタイトルでdisplay_nameを参照しない`() {
        val c = Campaign(
            id = "m1",
            operator = "テストPay",
            name = "杉並区で最大20%戻ってくる",
            displayName = "使われない略記",
            type = "municipal",
            region = Region(name = "杉並区", prefecture = "東京都"),
        )
        assertEquals("東京都 杉並区", campaignGroupDisplayTitle(c, merchants))
    }
}
