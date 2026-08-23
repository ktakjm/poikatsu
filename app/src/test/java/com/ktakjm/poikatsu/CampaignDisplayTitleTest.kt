package com.ktakjm.poikatsu

import com.ktakjm.poikatsu.data.Banner
import com.ktakjm.poikatsu.data.Campaign
import com.ktakjm.poikatsu.data.Merchant
import com.ktakjm.poikatsu.data.MerchantRule
import com.ktakjm.poikatsu.data.Region
import com.ktakjm.poikatsu.domain.campaignGroupDisplayTitle
import com.ktakjm.poikatsu.domain.cardProgramBundleSubtitle
import com.ktakjm.poikatsu.domain.isCardProgramBundle
import com.ktakjm.poikatsu.domain.municipalRegionsLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    private fun municipal(id: String, name: String, prefecture: String) = Campaign(
        id = id,
        operator = "テストPay",
        name = "${name}で最大20%戻ってくる",
        type = "municipal",
        region = Region(name = name, prefecture = prefecture),
    )

    @Test
    fun `自治体の併催グループは県と市区町村を併記する`() {
        // 県全域(region.name == prefecture)+市の同時開催(地図のお知らせピル発のグループ)。
        // データ順に依らず県→市区町村の順に並ぶ
        val group = listOf(
            municipal("m-city", "千葉市", "千葉県"),
            municipal("m-pref", "千葉県", "千葉県"),
        )
        assertEquals("千葉県・千葉市", municipalRegionsLabel(group))
        assertEquals("千葉県・千葉市", campaignGroupDisplayTitle(group, merchants))
    }

    @Test
    fun `自治体施策が単独のグループは併記せず従来のタイトル`() {
        val single = listOf(municipal("m-city", "杉並区", "東京都"))
        assertNull(municipalRegionsLabel(single))
        assertEquals("東京都 杉並区", campaignGroupDisplayTitle(single, merchants))
    }

    @Test
    fun `同一自治体の複数施策は併記しない`() {
        // 同じ市で複数の施策が併催されても地域名は1つ(distinct)なので従来タイトル
        val group = listOf(
            municipal("m1", "千葉市", "千葉県"),
            municipal("m2", "千葉市", "千葉県"),
        )
        assertNull(municipalRegionsLabel(group))
        assertEquals("千葉県 千葉市", campaignGroupDisplayTitle(group, merchants))
    }

    @Test
    fun `自治体以外のグループは併記対象外で先頭施策のタイトル`() {
        val group = listOf(promotion(merchantIds = listOf("welcia")))
        assertNull(municipalRegionsLabel(group))
        assertEquals("ウエルシア", campaignGroupDisplayTitle(group, merchants))
    }

    // ---- 発行体束ね(#81): 同一カードの常設 card_program 複数施策を1カードに ----

    private fun eposProgram(id: String, displayName: String?) = Campaign(
        id = id,
        operator = "エポスカード",
        name = "エポトクプラザ($id)",
        displayName = displayName,
        type = "card_program",
        cardId = "epos",
    )

    private val eposBundle = listOf(
        eposProgram("epos_yutai_monteroza", "エポスカード モンテローザ優待"),
        eposProgram("epos_yutai_keyuca", "エポスカード KEYUCA優待"),
        eposProgram("epos_yutai_karaokekan", "エポスカード カラオケ館優待"),
        eposProgram("epos_yutai_bigecho_course", "エポスカード ビッグエコー優待"),
        eposProgram("epos_yutai_presentation", "エポスカード 提示優待"),
    )

    @Test
    fun `発行体束ねグループのタイトルはoperatorと優待特典`() {
        assertEquals("エポスカード 優待・特典", campaignGroupDisplayTitle(eposBundle, merchants))
    }

    @Test
    fun `発行体束ねの判定は同一card_idのcard_programが2件以上`() {
        assertTrue(isCardProgramBundle(eposBundle))
        assertFalse(isCardProgramBundle(eposBundle.take(1))) // 1件なら従来表示
        assertFalse(isCardProgramBundle(listOf(promotion(merchantIds = listOf("welcia")), promotion())))
    }

    @Test
    fun `単独のcard_programグループは従来どおりdisplay_name`() {
        val single = listOf(eposProgram("dcard_tokuyakuten", "dカード特約店"))
        assertEquals("dカード特約店", campaignGroupDisplayTitle(single, merchants))
    }

    @Test
    fun `発行体束ねのサブ行はoperator接頭辞を除いた施策名を3件と残数で出す`() {
        assertEquals(
            "モンテローザ優待 / KEYUCA優待 / カラオケ館優待 ほか2件",
            cardProgramBundleSubtitle(eposBundle),
        )
    }

    @Test
    fun `発行体束ねのサブ行は3件以下なら残数を付けない`() {
        assertEquals(
            "モンテローザ優待 / KEYUCA優待",
            cardProgramBundleSubtitle(eposBundle.take(2)),
        )
    }

    @Test
    fun `発行体束ねのサブ行はdisplay_nameが無ければnameで出す`() {
        val group = listOf(
            eposProgram("a", null),
            eposProgram("b", "エポスカード KEYUCA優待"),
        )
        assertEquals("エポトクプラザ(a) / KEYUCA優待", cardProgramBundleSubtitle(group))
    }

    @Test
    fun `束ねでないグループのサブ行はnull`() {
        assertNull(cardProgramBundleSubtitle(eposBundle.take(1)))
    }
}
