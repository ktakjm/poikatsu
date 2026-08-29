package com.ktakjm.poikatsu

import com.ktakjm.poikatsu.data.Campaign
import com.ktakjm.poikatsu.data.ExcludedStorePair
import com.ktakjm.poikatsu.data.Merchant
import com.ktakjm.poikatsu.data.MerchantRule
import com.ktakjm.poikatsu.data.OfficialStoreList
import com.ktakjm.poikatsu.data.PaymentCard
import com.ktakjm.poikatsu.data.PoikatsuData
import com.ktakjm.poikatsu.domain.HiddenReason
import com.ktakjm.poikatsu.domain.JudgmentEngine
import com.ktakjm.poikatsu.domain.StoreVisibility
import com.ktakjm.poikatsu.domain.classifyStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 地図 POI の表示分類(通常ピン / 薄いピン+理由 / 描かない)を検証する(#77 施策5)。
 * 薄いピンは「除外が無ければ特典が出ていた店」だけに限定し、除外に関係なく判定の無い店は
 * 従来どおり描かない。
 */
class StoreVisibilityTest {

    private val today = LocalDate.of(2026, 6, 15)
    private val merchant = Merchant(id = "m1", name = "テスト店", reading = "てすとてん")

    /**
     * c1 = 公式リスト付き施策(所有カード)。c2 = リスト無しの別施策(任意)。
     * ownedC1=false で c1 のカードを未所有にする(除外が無くても c1 は出ない状態)。
     */
    private fun engine(
        eligible: List<String> = emptyList(),
        ineligible: List<String> = emptyList(),
        exhaustive: Boolean = false,
        withC2: Boolean = false,
        ownedC1: Boolean = true,
    ): JudgmentEngine {
        val c1 = Campaign(
            id = "c1",
            operator = "test",
            cardId = "card1",
            name = "リスト付き施策",
            paymentInstruction = "タッチ決済",
            rateBase = 5.0,
            verifiedDate = "2026-06-01",
            merchantRules = listOf(
                MerchantRule(
                    merchantId = "m1",
                    officialStoreList = OfficialStoreList(
                        eligibleStores = eligible,
                        ineligibleStores = ineligible,
                        listIsExhaustive = exhaustive,
                        updatedDate = "2026-05-01",
                        dateIsOfficial = false,
                        sourceUrl = "https://example.com",
                    ),
                ),
            ),
        )
        val c2 = Campaign(
            id = "c2",
            operator = "test",
            cardId = "card2",
            name = "リスト無し施策",
            paymentInstruction = "タッチ決済",
            rateBase = 3.0,
            verifiedDate = "2026-06-01",
            merchantRules = listOf(MerchantRule(merchantId = "m1")),
        )
        val cards = buildList {
            if (ownedC1) add(PaymentCard(id = "card1", cardName = "カード1", effectiveRateDefault = 5.0))
            add(PaymentCard(id = "card2", cardName = "カード2", effectiveRateDefault = 3.0))
        }
        return JudgmentEngine(
            PoikatsuData(
                merchants = listOf(merchant),
                campaigns = if (withC2) listOf(c1, c2) else listOf(c1),
                cards = cards,
                updatedAt = "2026-06-01",
            ),
        )
    }

    private fun JudgmentEngine.classify(
        storeName: String,
        pairs: List<ExcludedStorePair> = emptyList(),
        previewMerchantIds: Set<String> = emptySet(),
    ) = classifyStore(
        merchant = merchant,
        bannerId = merchant.id,
        storeName = storeName,
        today = today,
        enabledQrIds = emptySet(),
        excludedPairs = pairs,
        memberships = emptySet(),
        previewMerchantIds = previewMerchantIds,
    )

    private fun hiddenReason(v: StoreVisibility): HiddenReason = (v as StoreVisibility.Hidden).reason

    @Test
    fun `公式に対象外と明示された店は薄いピン(公式対象外)`() {
        val v = engine(ineligible = listOf("大宮店")).classify("テスト店 大宮店")
        assertEquals(HiddenReason.OFFICIALLY_EXCLUDED, hiddenReason(v))
    }

    @Test
    fun `網羅リストに掲載がなく全施策が消えた店は薄いピン(網羅リスト外)`() {
        val v = engine(eligible = listOf("浦和店"), exhaustive = true).classify("テスト店 大宮店")
        assertEquals(HiddenReason.EXHAUSTIVE_INELIGIBLE, hiddenReason(v))
    }

    @Test
    fun `ユーザー登録の対象外で全施策が消えた店は薄いピン(登録対象外)`() {
        val pairs = listOf(ExcludedStorePair("c1", "m1", "テスト店 大宮店"))
        val v = engine(eligible = listOf("浦和店")).classify("テスト店 大宮店", pairs)
        assertEquals(HiddenReason.USER_EXCLUDED, hiddenReason(v))
    }

    @Test
    fun `他の施策が残る店は間引きがあっても通常ピン`() {
        val v = engine(eligible = listOf("浦和店"), exhaustive = true, withC2 = true).classify("テスト店 大宮店")
        val shown = v as StoreVisibility.Shown
        // 網羅リスト外の c1 は消え、c2 だけが見える
        assertEquals(listOf("c2"), shown.visible.map { it.campaign.id })
    }

    @Test
    fun `掲載店は通常ピンで施策が見える`() {
        val shown = engine(eligible = listOf("浦和店"), exhaustive = true).classify("テスト店 浦和店") as StoreVisibility.Shown
        assertEquals(listOf("c1"), shown.visible.map { it.campaign.id })
    }

    @Test
    fun `除外が無くても出ない店(カード未所有)は薄いピンにせず描かない`() {
        val v = engine(eligible = listOf("浦和店"), exhaustive = true, ownedC1 = false).classify("テスト店 大宮店")
        val dropped = v as StoreVisibility.Dropped
        // ブリッジ 0 件文言の計数・開発者一覧の理由用に「網羅リスト外の間引きがあった」ことは残す
        assertTrue(dropped.exhaustiveIneligible)
    }

    @Test
    fun `除外に一切かからず判定も無い店は描かない`() {
        val v = engine(eligible = listOf("浦和店"), ownedC1 = false).classify("テスト店 大宮店")
        val dropped = v as StoreVisibility.Dropped
        assertTrue(!dropped.exhaustiveIneligible)
    }

    @Test
    fun `ブリッジ中のチェーンは判定なしでも下見用に通常ピンで残す`() {
        val v = engine(eligible = listOf("浦和店"), ownedC1 = false).classify("テスト店 大宮店", previewMerchantIds = setOf("m1"))
        val shown = v as StoreVisibility.Shown
        assertTrue(shown.visible.isEmpty())
    }

    @Test
    fun `ブリッジ中でも網羅リスト外の店は下見に残さず薄いピン`() {
        val v = engine(eligible = listOf("浦和店"), exhaustive = true).classify("テスト店 大宮店", previewMerchantIds = setOf("m1"))
        assertEquals(HiddenReason.EXHAUSTIVE_INELIGIBLE, hiddenReason(v))
    }

    @Test
    fun `ブリッジ中でも網羅リスト外かつカード未所有の店は描かない`() {
        val v = engine(eligible = listOf("浦和店"), exhaustive = true, ownedC1 = false)
            .classify("テスト店 大宮店", previewMerchantIds = setOf("m1"))
        assertTrue((v as StoreVisibility.Dropped).exhaustiveIneligible)
    }

    @Test
    fun `理由の優先順は公式対象外が網羅リスト外より先`() {
        val v = engine(eligible = listOf("浦和店"), ineligible = listOf("大宮店"), exhaustive = true).classify("テスト店 大宮店")
        assertEquals(HiddenReason.OFFICIALLY_EXCLUDED, hiddenReason(v))
    }

    @Test
    fun `実際に施策を消した除外だけを理由にする(未所有の網羅リスト外は理由にならない)`() {
        // c1 は網羅リスト外だが未所有=除外が無くても出ない。c2 はユーザー登録で消えた → 理由は登録対象外
        val pairs = listOf(ExcludedStorePair("c2", "m1", "テスト店 大宮店"))
        val v = engine(eligible = listOf("浦和店"), exhaustive = true, withC2 = true, ownedC1 = false)
            .classify("テスト店 大宮店", pairs)
        assertEquals(HiddenReason.USER_EXCLUDED, hiddenReason(v))
    }
}
