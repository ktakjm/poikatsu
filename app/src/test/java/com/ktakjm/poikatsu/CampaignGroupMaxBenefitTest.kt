package com.ktakjm.poikatsu

import com.ktakjm.poikatsu.data.Campaign
import com.ktakjm.poikatsu.data.MerchantRule
import com.ktakjm.poikatsu.data.RateRule
import com.ktakjm.poikatsu.domain.storeRatesVary
import com.ktakjm.poikatsu.domain.campaignGroupMaxBenefit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * おトクタブ一覧カード右側の最大特典表示(campaignGroupMaxBenefit)を検証する。
 * 発行体束ね(#81)で rebate と discount が混在するグループは、%が大きい方の型だけを
 * 代表で出す(2.5%還元と30%OFFを「最大30%還元」に合成しない)。同率は OFF 優先。
 */
class CampaignGroupMaxBenefitTest {

    private fun program(
        id: String,
        benefitType: String,
        rateBase: Double? = null,
        rules: List<MerchantRule> = emptyList(),
        rateRules: List<RateRule> = emptyList(),
        discountAmount: Int? = null,
    ) = Campaign(
        id = id,
        operator = "エポスカード",
        name = "テスト施策 $id",
        type = "card_program",
        cardId = "epos",
        benefitType = benefitType,
        rateBase = rateBase,
        merchantRules = rules,
        rateRules = rateRules,
        discountAmount = discountAmount,
    )

    @Test
    fun `単一型の単一率は最大を冠さない`() {
        val c = program("a", "rebate", rateBase = 2.5)
        assertEquals("2.5% 還元", campaignGroupMaxBenefit(listOf(c)))
    }

    @Test
    fun `単一型でも店舗別レートがばらつけば最大を冠する`() {
        // dカード特約店型: 店舗ごとの rate_override が異なる
        val c = program(
            "a", "rebate", rateBase = 4.0,
            rules = listOf(
                MerchantRule(merchantId = "m1", rateOverride = 4.0),
                MerchantRule(merchantId = "m2", rateOverride = 2.0),
            ),
        )
        assertEquals("最大4% 還元", campaignGroupMaxBenefit(listOf(c)))
    }

    @Test
    fun `型混在はパーセンテージが大きい方の型で出す_OFFが大きい場合`() {
        val group = listOf(
            program("rebate25", "rebate", rateBase = 2.5),
            program("off30", "discount", rateBase = 30.0),
        )
        assertEquals("最大30% OFF", campaignGroupMaxBenefit(group))
    }

    @Test
    fun `型混在はパーセンテージが大きい方の型で出す_還元が大きい場合`() {
        val group = listOf(
            program("rebate30", "rebate", rateBase = 30.0),
            program("off10", "discount", rateBase = 10.0),
        )
        assertEquals("最大30% 還元", campaignGroupMaxBenefit(group))
    }

    @Test
    fun `型混在で同率ならOFFを優先する`() {
        val group = listOf(
            program("rebate30", "rebate", rateBase = 30.0),
            program("off30", "discount", rateBase = 30.0),
        )
        assertEquals("最大30% OFF", campaignGroupMaxBenefit(group))
    }

    @Test
    fun `型混在は選ばれた型が単一率でも最大を冠する`() {
        // もう一方の型の特典もあるため一律の率と誤認されないように
        val group = listOf(
            program("rebate25", "rebate", rateBase = 2.5),
            program("off30", "discount", rateBase = 30.0),
        )
        assertEquals("最大30% OFF", campaignGroupMaxBenefit(group))
    }

    @Test
    fun `型混在で定額割引しか無ければ率を持つ還元側で出す`() {
        val group = listOf(
            program("rebate25", "rebate", rateBase = 2.5),
            program("off500yen", "discount", discountAmount = 500),
        )
        assertEquals("最大2.5% 還元", campaignGroupMaxBenefit(group))
    }

    @Test
    fun `personalRatesがあれば実効率で出す`() {
        // 店舗別レートのばらつきが無い(override == rate_base)ので「最大」は付かない
        val c = program(
            "a", "rebate", rateBase = 2.5,
            rules = listOf(MerchantRule(merchantId = "m1", rateOverride = 2.5)),
        )
        assertEquals("3% 還元", campaignGroupMaxBenefit(listOf(c), mapOf("a" to 3.0)))
    }

    @Test
    fun `型混在の比較もpersonalRatesの実効率で行う`() {
        // 実効率がOFF率を上回れば還元側が代表になる
        val group = listOf(
            program("rebate", "rebate", rateBase = 2.5),
            program("off10", "discount", rateBase = 10.0),
        )
        assertEquals("最大12% 還元", campaignGroupMaxBenefit(group, mapOf("rebate" to 12.0)))
    }

    @Test
    fun `抽選のみのグループは抽選と出す`() {
        val c = program("a", "lottery")
        assertEquals("抽選", campaignGroupMaxBenefit(listOf(c)))
    }

    @Test
    fun `rate_rules持ちは単一施策でも最大を冠する`() {
        val c = program(
            "a", "discount", rateBase = 30.0,
            rateRules = listOf(RateRule(condition = "ルーム料金", rate = 30.0)),
        )
        assertEquals("最大30% OFF", campaignGroupMaxBenefit(listOf(c)))
    }

    // ---- Campaign.storeRatesVary: 施策全体ビューで「最大」を冠する根拠(店舗別レートのばらつき) ----

    @Test
    fun `店舗別レートが2値以上ならstoreRatesVaryはtrue`() {
        // エポス提示優待型: ビッグエコー30%・ジャンカラ20%(rate_base は最大の30)
        val c = program(
            "a", "discount", rateBase = 30.0,
            rules = listOf(
                MerchantRule(merchantId = "big_echo", rateOverride = 30.0),
                MerchantRule(merchantId = "jankara", rateOverride = 20.0),
            ),
        )
        assertTrue(c.storeRatesVary)
    }

    @Test
    fun `全店同率ならstoreRatesVaryはfalse`() {
        val c = program(
            "a", "rebate", rateBase = 2.5,
            rules = listOf(MerchantRule(merchantId = "m1", rateOverride = 2.5)),
        )
        assertFalse(c.storeRatesVary)
    }

    @Test
    fun `rate_overrideが無ければstoreRatesVaryはfalse`() {
        val c = program("a", "rebate", rateBase = 2.5)
        assertFalse(c.storeRatesVary)
    }
}
