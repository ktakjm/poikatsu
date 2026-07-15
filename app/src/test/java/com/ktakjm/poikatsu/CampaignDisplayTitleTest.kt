package com.ktakjm.poikatsu

import com.ktakjm.poikatsu.data.Campaign
import com.ktakjm.poikatsu.data.MerchantRule
import com.ktakjm.poikatsu.data.Region
import com.ktakjm.poikatsu.ui.campaignGroupDisplayTitle
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * キャンペーンカードの表示タイトル(campaignGroupDisplayTitle)のフォールバック連鎖を検証する。
 * display_name → 単一チェーンは merchant 名 → 複数チェーンは「{先頭チェーン} 他Nチェーン」→ campaign.name
 */
class CampaignDisplayTitleTest {

    private val merchantNames = mapOf(
        "welcia" to "ウエルシア",
        "sugi" to "スギ薬局",
        "tsuruha" to "ツルハドラッグ",
    )

    private fun promotion(
        displayName: String? = null,
        merchantIds: List<String> = emptyList(),
    ) = Campaign(
        id = "c1",
        operator = "テスト",
        name = "公式表記のキャンペーン名(第2弾・最大20%)",
        displayName = displayName,
        type = "promotion",
        merchantRules = merchantIds.map { MerchantRule(merchantId = it) },
    )

    @Test
    fun `display_nameがあれば最優先で使う`() {
        val c = promotion(displayName = "花王×ウエル/スギ", merchantIds = listOf("welcia", "sugi"))
        assertEquals("花王×ウエル/スギ", campaignGroupDisplayTitle(c, merchantNames))
    }

    @Test
    fun `単一チェーンはmerchant名`() {
        val c = promotion(merchantIds = listOf("welcia"))
        assertEquals("ウエルシア", campaignGroupDisplayTitle(c, merchantNames))
    }

    @Test
    fun `複数チェーンは先頭チェーンと他Nチェーン`() {
        val c = promotion(merchantIds = listOf("welcia", "sugi", "tsuruha"))
        assertEquals("ウエルシア 他2チェーン", campaignGroupDisplayTitle(c, merchantNames))
    }

    @Test
    fun `同一merchantの複数ルールは1チェーンと数える`() {
        // 店舗別 rate_override 等で同じ merchant に複数ルールが並ぶことがある
        val c = promotion(merchantIds = listOf("welcia", "welcia"))
        assertEquals("ウエルシア", campaignGroupDisplayTitle(c, merchantNames))
    }

    @Test
    fun `merchant_rulesが無ければcampaign名`() {
        val c = promotion()
        assertEquals("公式表記のキャンペーン名(第2弾・最大20%)", campaignGroupDisplayTitle(c, merchantNames))
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
        assertEquals("東京都 杉並区", campaignGroupDisplayTitle(c, merchantNames))
    }
}
