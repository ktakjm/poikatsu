package com.ktakjm.poikatsu

import com.ktakjm.poikatsu.data.Campaign
import com.ktakjm.poikatsu.data.PoikatsuJson
import com.ktakjm.poikatsu.domain.campaignGroupKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.io.File

/**
 * おトクタブ一覧・通知で共有するグルーピングキー(campaignGroupKey)を検証する。
 * 自治体は地域単位、カスタムは登録単位に加え、常設 card_program は発行体(card_id)単位に
 * 束ねる(#81。エポス優待のように1カードへ複数の常設施策がぶら下がると、おトクタブの
 * 常設セクションに同じカードが5枚並ぶため)。
 */
class CampaignGroupingTest {

    private fun cardProgram(
        id: String,
        cardId: String? = "epos",
        periodEnd: String? = null,
        mayEndEarly: Boolean = false,
        type: String = "card_program",
    ) = Campaign(
        id = id,
        operator = "エポスカード",
        name = "テスト施策 $id",
        type = type,
        cardId = cardId,
        periodEnd = periodEnd,
        mayEndEarly = mayEndEarly,
    )

    @Test
    fun `常設card_programは同一card_idで同じキーになる`() {
        val a = cardProgram("epos_yutai_a")
        val b = cardProgram("epos_yutai_b")
        assertEquals(campaignGroupKey(a), campaignGroupKey(b))
    }

    @Test
    fun `別カードの常設card_programは別キー`() {
        val a = cardProgram("epos_yutai_a", cardId = "epos")
        val b = cardProgram("dcard_tokuyakuten", cardId = "dcard")
        assertNotEquals(campaignGroupKey(a), campaignGroupKey(b))
    }

    @Test
    fun `期間限定のcard_programは束ねず施策id単位`() {
        // 終了日あり・早期終了あり得るものは常設セクションに出ないため従来どおり
        val ends = cardProgram("epos_time_limited", periodEnd = "2026-12-31")
        val early = cardProgram("epos_may_end", mayEndEarly = true)
        assertEquals("epos_time_limited", campaignGroupKey(ends))
        assertEquals("epos_may_end", campaignGroupKey(early))
    }

    @Test
    fun `card_idを持たないcard_programは施策id単位`() {
        val c = cardProgram("jcb_brand_program", cardId = null)
        assertEquals("jcb_brand_program", campaignGroupKey(c))
    }

    @Test
    fun `常設promotionは束ねず施策id単位`() {
        val c = cardProgram("promo_permanent", type = "promotion")
        assertEquals("promo_permanent", campaignGroupKey(c))
    }

    @Test
    fun `card_idと施策idが衝突しても混ざらない`() {
        // 接頭辞なしだと id="epos" の施策と card_id="epos" の束ねキーが同じ文字列になる
        val bundled = cardProgram("epos_yutai_a", cardId = "epos")
        val plain = cardProgram("epos", cardId = null)
        assertNotEquals(campaignGroupKey(plain), campaignGroupKey(bundled))
    }
}

/** 実データ(data/campaigns.json)でのグルーピングを検証する */
class CampaignGroupingRealDataTest {

    private val data = PoikatsuJson.parse(
        merchantsJson = File("../data/merchants.json").readText(),
        campaignsJson = File("../data/campaigns.json").readText(),
        paymentMethodsJson = File("../data/payment_methods.json").readText(),
    )

    @Test
    fun `実データ_エポスの常設5施策は1グループに束なる`() {
        val epos = data.campaigns.filter { it.id.startsWith("epos_yutai_") }
        assertEquals(5, epos.size)
        assertEquals(1, epos.map { campaignGroupKey(it) }.distinct().size)
    }
}
