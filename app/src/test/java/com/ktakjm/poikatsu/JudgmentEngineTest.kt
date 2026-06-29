package com.ktakjm.poikatsu

import com.ktakjm.poikatsu.data.Campaign
import com.ktakjm.poikatsu.data.Merchant
import com.ktakjm.poikatsu.data.MerchantRule
import com.ktakjm.poikatsu.data.OfficialStoreList
import com.ktakjm.poikatsu.data.PoikatsuData
import com.ktakjm.poikatsu.data.PoikatsuJson
import com.ktakjm.poikatsu.data.Profile
import com.ktakjm.poikatsu.data.ProfileCard
import com.ktakjm.poikatsu.data.QrPayment
import com.ktakjm.poikatsu.data.Region
import com.ktakjm.poikatsu.data.RegisteredMunicipality
import com.ktakjm.poikatsu.domain.BenefitType
import com.ktakjm.poikatsu.domain.CampaignStatus
import com.ktakjm.poikatsu.domain.JudgmentEngine
import com.ktakjm.poikatsu.domain.StoreEligibility
import com.ktakjm.poikatsu.util.JapaneseText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

/**
 * リポジトリ直下 data/ の実データを読み込んで検証する。
 * データ整合性チェック(参照切れ等)も兼ねる。
 */
class JudgmentEngineTest {

    private val data = PoikatsuJson.parse(
        merchantsJson = File("../data/merchants.json").readText(),
        campaignsJson = File("../data/campaigns.json").readText(),
        profileJson = File("../data/profile.json").readText(),
    )
    private val engine = JudgmentEngine(data)
    private val today = LocalDate.of(2026, 6, 28)

    @Test
    fun `エイリアスで検索できる`() {
        assertEquals("マクドナルド", engine.search("マック").first().name)
        assertTrue(engine.search("サイゼ").any { it.name == "サイゼリヤ" })
        assertTrue(engine.search("KFC").any { it.id == "kfc" })
    }

    @Test
    fun `ひらがな入力でカタカナ店名にヒットする`() {
        assertTrue(engine.search("ろーそん").any { it.id == "lawson" })
        assertTrue(engine.search("すたば").any { it.id == "starbucks" })
    }

    @Test
    fun `ハイフン有無は無視される`() {
        assertTrue(engine.search("セブンイレブン").any { it.id == "seven_eleven" })
    }

    @Test
    fun `前方一致が部分一致より上に来る`() {
        val results = engine.search("ガスト")
        assertEquals("ガスト", results.first().name)
        assertTrue(results.any { it.name == "ステーキガスト" })
    }

    @Test
    fun `セブンイレブンは両施策の対象`() {
        val merchant = data.merchants.first { it.id == "seven_eleven" }
        val judgments = engine.judge(merchant, today)
        assertEquals(2, judgments.size)
    }

    @Test
    fun `マクドナルドは三井住友のみ対象`() {
        val merchant = data.merchants.first { it.id == "mcdonalds" }
        val judgments = engine.judge(merchant, today)
        assertEquals(listOf("smcc_combini_restaurant"), judgments.map { it.campaign.id })
    }

    @Test
    fun `MUFGはプロファイル前提で還元率7パーセント・警告なし`() {
        val merchant = data.merchants.first { it.id == "sushiro" }
        val judgment = engine.judge(merchant, today).single()
        assertEquals("mufg_point_up_program", judgment.campaign.id)
        assertEquals(7.0, judgment.effectiveRate, 0.001)
        assertTrue(judgment.warnings.isEmpty())
    }

    @Test
    fun `カテゴリのみで絞り込める`() {
        val results = engine.search("", setOf("コンビニ"))
        assertTrue(results.isNotEmpty())
        assertTrue(results.all { it.category == "コンビニ" })
        assertTrue(results.any { it.id == "seven_eleven" })
    }

    @Test
    fun `カテゴリは複数選択できる`() {
        val results = engine.search("", setOf("コンビニ", "カフェ"))
        assertEquals(setOf("コンビニ", "カフェ"), results.map { it.category }.toSet())
    }

    @Test
    fun `店名とカテゴリの組み合わせで絞り込める`() {
        // 「す」はすき家(ファストフード)やスシロー(回転寿司)にもヒットするが、カフェに絞ればスタバ系のみ
        val results = engine.search("す", setOf("カフェ"))
        assertTrue(results.all { it.category == "カフェ" })
        assertTrue(results.any { it.id == "starbucks" })
    }

    @Test
    fun `カテゴリ未選択かつ店名空なら空リスト`() {
        assertTrue(engine.search("", emptySet()).isEmpty())
    }

    @Test
    fun `カテゴリ一覧がデータから取れる`() {
        assertTrue(engine.categories.containsAll(listOf("コンビニ", "ファストフード", "ファミレス", "カフェ", "回転寿司", "スーパー", "その他")))
    }

    @Test
    fun `具体店舗名のフル入力でもチェーンにヒットする`() {
        assertEquals("マクドナルド", engine.search("マクドナルド渋谷駅前店").first().name)
        assertTrue(engine.search("くら寿司ららぽーとTOKYO-BAY店").any { it.id == "kurazushi" })
    }

    @Test
    fun `対象外リストに一致すると対象外`() {
        val (eng, merchant) = storeCheckEngine(ineligible = listOf("ららぽーとTOKYO-BAY"))
        val verdict = eng.checkStore(merchant, "ららぽーとTOKYO-BAY店").single()
        assertEquals(StoreEligibility.INELIGIBLE, verdict.eligibility)
        assertEquals("ららぽーとTOKYO-BAY", verdict.matched)
    }

    @Test
    fun `対象リストに一致すると対象`() {
        val (eng, merchant) = storeCheckEngine(eligible = listOf("アリオ札幌"))
        assertEquals(StoreEligibility.ELIGIBLE, eng.checkStore(merchant, "アリオ札幌店").single().eligibility)
    }

    @Test
    fun `どちらのリストにも無ければ要確認`() {
        val (eng, merchant) = storeCheckEngine(eligible = listOf("アリオ札幌"), ineligible = listOf("ららぽーとTOKYO-BAY"))
        val verdict = eng.checkStore(merchant, "どこか別の店").single()
        assertEquals(StoreEligibility.UNKNOWN, verdict.eligibility)
        assertNull(verdict.matched)
    }

    @Test
    fun `対象外は対象より優先される`() {
        val (eng, merchant) = storeCheckEngine(eligible = listOf("川口"), ineligible = listOf("ララガーデン川口"))
        assertEquals(StoreEligibility.INELIGIBLE, eng.checkStore(merchant, "ララガーデン川口店").single().eligibility)
    }

    @Test
    fun `店舗名未入力なら判定結果は出ない`() {
        val (eng, merchant) = storeCheckEngine(ineligible = listOf("ららぽーとTOKYO-BAY"))
        assertTrue(eng.checkStore(merchant, "").isEmpty())
        assertTrue(eng.checkStore(merchant, "  ").isEmpty())
    }

    @Test
    fun `公式リストの有無で対象判定画面への遷移可否が決まる`() {
        val (withList, mWith) = storeCheckEngine(ineligible = listOf("X"))
        assertTrue(withList.canCheckStore(mWith))
        val (without, mWithout) = storeCheckEngine(hasList = false)
        assertFalse(without.canCheckStore(mWithout))
    }

    @Test
    fun `実データ_アカチャンホンポは公式リストで3状態判定できる`() {
        val merchant = data.merchants.first { it.id == "akachan_honpo" }
        assertTrue(engine.canCheckStore(merchant))
        // 公式の対象外店舗(ららぽーとTOKYO-BAY内)→ 対象外
        assertEquals(StoreEligibility.INELIGIBLE, engine.checkStore(merchant, "ららぽーとTOKYO-BAY店").single().eligibility)
        // 公式の対象店舗 → 対象
        assertEquals(StoreEligibility.ELIGIBLE, engine.checkStore(merchant, "アリオ札幌店").single().eligibility)
        // どちらのリストにも無い → 要確認
        assertEquals(StoreEligibility.UNKNOWN, engine.checkStore(merchant, "架空のどこか店").single().eligibility)
    }

    @Test
    fun `明示的対象外の店舗だけ近隣リストから除外される`() {
        val (eng, m) = storeCheckEngine(eligible = listOf("アリオ札幌"), ineligible = listOf("ららぽーと豊洲"))
        // 公式の対象外 → 除外する
        assertTrue(eng.isExcludedStore(m, "テスト店 ららぽーと豊洲店"))
        // 公式の対象 → 除外しない
        assertFalse(eng.isExcludedStore(m, "テスト店 アリオ札幌店"))
        // どちらにも無い(要確認) → 除外しない(現状仕様どおり表示)
        assertFalse(eng.isExcludedStore(m, "テスト店 どこか別の店"))
        // 公式リストの無いチェーン → 除外しない
        val (eng2, m2) = storeCheckEngine(hasList = false)
        assertFalse(eng2.isExcludedStore(m2, "何でも"))
    }

    /** official_store_list を組んだ最小データで JudgmentEngine を作る。hasList=false で公式リスト無し */
    private fun storeCheckEngine(
        eligible: List<String> = emptyList(),
        ineligible: List<String> = emptyList(),
        hasList: Boolean = true,
    ): Pair<JudgmentEngine, Merchant> {
        val merchant = Merchant(id = "m1", name = "テスト店", reading = "てすとてん")
        val campaign = Campaign(
            id = "c1",
            issuer = "test",
            name = "テスト施策",
            paymentInstruction = "タッチ決済",
            rateBase = 5.0,
            verifiedDate = "2026-06-01",
            merchantRules = listOf(
                MerchantRule(
                    merchantId = "m1",
                    officialStoreList = if (!hasList) null else OfficialStoreList(
                        eligibleStores = eligible,
                        ineligibleStores = ineligible,
                        updatedDate = "2026-05-01",
                        dateIsOfficial = false,
                        sourceUrl = "https://example.com",
                    ),
                ),
            ),
        )
        val data = PoikatsuData(
            merchants = listOf(merchant),
            campaigns = listOf(campaign),
            profile = Profile(),
            updatedAt = "2026-06-01",
        )
        return JudgmentEngine(data) to merchant
    }

    @Test
    fun `チェーン名そのままの入力は完全一致と判定される`() {
        val mcdonalds = data.merchants.first { it.id == "mcdonalds" }
        assertTrue(engine.isExactNameMatch(mcdonalds, "マック"))
        assertTrue(engine.isExactNameMatch(mcdonalds, "マクドナルド"))
        assertFalse(engine.isExactNameMatch(mcdonalds, "マクドナルド渋谷店"))
    }

    @Test
    fun `merchant_rules の参照切れがない`() {
        val ids = data.merchants.map { it.id }.toSet()
        val broken = data.campaigns.flatMap { c -> c.merchantRules.map { c.id to it.merchantId } }
            .filter { (_, mid) -> mid !in ids }
        assertEquals(emptyList<Pair<String, String>>(), broken)
    }

    @Test
    fun `プロファイルのカードは実在する施策を参照している`() {
        val campaignIds = data.campaigns.map { it.id }.toSet()
        assertTrue(data.profile.cards.all { it.campaignId in campaignIds })
    }

    private fun engineWithProfile(profile: Profile) = JudgmentEngine(data.copy(profile = profile))

    private fun profileWithMufgBrand(brand: String) = Profile(
        cards = data.profile.cards.map {
            if (it.campaignId == "mufg_point_up_program") it.copy(brand = brand) else it
        },
    )

    @Test
    fun `Amex は amex_excluded の店舗で MUFG が対象外になる`() {
        val kurazushi = data.merchants.first { it.id == "kurazushi" } // amex_excluded = true
        val amexEngine = engineWithProfile(profileWithMufgBrand("Amex"))
        assertTrue(amexEngine.judge(kurazushi, today).none { it.campaign.id == "mufg_point_up_program" })
        // 非 Amex(既定プロファイル=Mastercard)では従来どおり MUFG が出る
        assertTrue(engine.judge(kurazushi, today).any { it.campaign.id == "mufg_point_up_program" })
    }

    @Test
    fun `Amex でも amex_excluded でない店舗では MUFG が残る`() {
        val sevenEleven = data.merchants.first { it.id == "seven_eleven" } // amex_excluded = false
        val amexEngine = engineWithProfile(profileWithMufgBrand("Amex"))
        assertTrue(amexEngine.judge(sevenEleven, today).any { it.campaign.id == "mufg_point_up_program" })
    }

    @Test
    fun `未所有カードの施策は判定に出ない`() {
        val kurazushi = data.merchants.first { it.id == "kurazushi" }
        // MUFG カードを所有していない(profile から除外)ケース
        val onlySmcc = Profile(cards = data.profile.cards.filter { it.campaignId == "smcc_combini_restaurant" })
        assertTrue(engineWithProfile(onlySmcc).judge(kurazushi, today).none { it.campaign.id == "mufg_point_up_program" })
        // どのカードも所有していなければ判定は空
        assertTrue(engineWithProfile(Profile()).judge(kurazushi, today).isEmpty())
    }

    // ---- 期間フィルタのテスト ----

    private fun campaignWithPeriod(
        start: String? = null,
        end: String? = null,
        type: String = "card_program",
        storeScope: String = "managed",
        benefitType: String = "rebate",
        paymentMethodId: String? = null,
        rateBase: Double? = 10.0,
        discountAmount: Int? = null,
        minPurchase: Int? = null,
        usageLimit: Int? = null,
        perTransactionCap: Int? = null,
        periodTotalCap: Int? = null,
        region: Region? = null,
    ) = Campaign(
        id = "test_campaign",
        issuer = "test",
        name = "テスト",
        paymentInstruction = "テスト",
        rateBase = rateBase,
        verifiedDate = "2026-06-01",
        periodStart = start,
        periodEnd = end,
        type = type,
        storeScope = storeScope,
        benefitType = benefitType,
        paymentMethodId = paymentMethodId,
        discountAmount = discountAmount,
        minPurchase = minPurchase,
        usageLimit = usageLimit,
        perTransactionCap = perTransactionCap,
        periodTotalCap = periodTotalCap,
        region = region,
        merchantRules = listOf(MerchantRule(merchantId = "m1")),
    )

    private val testMerchant = Merchant(id = "m1", name = "テスト店", reading = "てすとてん")
    private val testCard = ProfileCard(campaignId = "test_campaign", cardName = "テストカード", effectiveRateDefault = 10.0)

    private fun periodTestEngine(campaign: Campaign, profile: Profile = Profile(cards = listOf(testCard))): JudgmentEngine =
        JudgmentEngine(
            PoikatsuData(
                merchants = listOf(testMerchant),
                campaigns = listOf(campaign),
                profile = profile,
                updatedAt = "2026-06-01",
            ),
        )

    @Test
    fun `常設施策(period null)はアクティブ`() {
        val campaign = campaignWithPeriod()
        val engine = periodTestEngine(campaign)
        assertEquals(CampaignStatus.ACTIVE, engine.campaignStatus(campaign, today))
        assertEquals(1, engine.judge(testMerchant, today).size)
    }

    @Test
    fun `期間中の施策はアクティブ`() {
        val campaign = campaignWithPeriod(start = "2026-06-01", end = "2026-07-31")
        val engine = periodTestEngine(campaign)
        assertEquals(CampaignStatus.ACTIVE, engine.campaignStatus(campaign, today))
        assertEquals(1, engine.judge(testMerchant, today).size)
    }

    @Test
    fun `期限切れの施策は非表示`() {
        val campaign = campaignWithPeriod(start = "2026-05-01", end = "2026-06-15")
        val engine = periodTestEngine(campaign)
        assertEquals(CampaignStatus.EXPIRED, engine.campaignStatus(campaign, today))
        assertTrue(engine.judge(testMerchant, today).isEmpty())
    }

    @Test
    fun `未来開始の施策はもうすぐ開始`() {
        val campaign = campaignWithPeriod(start = "2026-07-01", end = "2026-07-31")
        val engine = periodTestEngine(campaign)
        assertEquals(CampaignStatus.UPCOMING, engine.campaignStatus(campaign, today))
        // judge からはフィルタされる(探す/近くタブには出さない)
        assertTrue(engine.judge(testMerchant, today).isEmpty())
        // upcomingCampaigns には含まれる
        assertEquals(1, engine.upcomingCampaigns(today).size)
    }

    @Test
    fun `開始日当日はアクティブ`() {
        val campaign = campaignWithPeriod(start = "2026-06-28", end = "2026-07-31")
        assertEquals(CampaignStatus.ACTIVE, periodTestEngine(campaign).campaignStatus(campaign, today))
    }

    @Test
    fun `終了日当日はアクティブ`() {
        val campaign = campaignWithPeriod(start = "2026-06-01", end = "2026-06-28")
        assertEquals(CampaignStatus.ACTIVE, periodTestEngine(campaign).campaignStatus(campaign, today))
    }

    @Test
    fun `終了日翌日は期限切れ`() {
        val campaign = campaignWithPeriod(start = "2026-06-01", end = "2026-06-27")
        assertEquals(CampaignStatus.EXPIRED, periodTestEngine(campaign).campaignStatus(campaign, today))
    }

    @Test
    fun `残り日数の計算`() {
        val engine = periodTestEngine(campaignWithPeriod(end = "2026-07-01"))
        assertEquals(3, engine.daysRemaining(campaignWithPeriod(end = "2026-07-01"), today))
        assertEquals(0, engine.daysRemaining(campaignWithPeriod(end = "2026-06-28"), today))
        assertNull(engine.daysRemaining(campaignWithPeriod(), today))
        assertNull(engine.daysRemaining(campaignWithPeriod(end = "2026-06-27"), today))
    }

    @Test
    fun `残り3日以下で警告が出る`() {
        val campaign = campaignWithPeriod(start = "2026-06-01", end = "2026-06-30")
        val engine = periodTestEngine(campaign)
        val judgments = engine.judge(testMerchant, LocalDate.of(2026, 6, 28))
        assertTrue(judgments.first().warnings.any { it.contains("残り") })
    }

    @Test
    fun `残り4日以上では警告なし`() {
        val campaign = campaignWithPeriod(start = "2026-06-01", end = "2026-07-31")
        val engine = periodTestEngine(campaign)
        val judgments = engine.judge(testMerchant, today)
        assertTrue(judgments.first().warnings.isEmpty())
    }

    // ---- store_scope フィルタのテスト ----

    @Test
    fun `store_scope_external は judge に含まれない`() {
        val campaign = campaignWithPeriod(storeScope = "external", type = "municipal")
        val engine = periodTestEngine(campaign)
        assertTrue(engine.judge(testMerchant, today).isEmpty())
    }

    @Test
    fun `store_scope_managed は judge に含まれる`() {
        val campaign = campaignWithPeriod(storeScope = "managed")
        val engine = periodTestEngine(campaign)
        assertEquals(1, engine.judge(testMerchant, today).size)
    }

    // ---- QR 判定のテスト ----

    @Test
    fun `QR決済の判定_利用中のQRのみ返る`() {
        val paypay = QrPayment(id = "paypay", name = "PayPay", brandColor = "#FF0033")
        val campaign = campaignWithPeriod(
            type = "card_promotion",
            paymentMethodId = "paypay",
            rateBase = 20.0,
            start = "2026-07-01",
            end = "2026-07-31",
        )
        val profile = Profile(qrPayments = listOf(paypay))
        val engine = periodTestEngine(campaign, profile)

        // 7月中はアクティブ
        val julyToday = LocalDate.of(2026, 7, 15)
        val results = engine.judgeQr(testMerchant, julyToday, setOf("paypay"))
        assertEquals(1, results.size)
        assertEquals("PayPay", results.first().paymentMethod.name)
        assertEquals(20.0, results.first().effectiveRate!!, 0.001)
        assertEquals(BenefitType.REBATE, results.first().benefitType)

        // 未登録のQR決済では出ない
        assertTrue(engine.judgeQr(testMerchant, julyToday, setOf("aupay")).isEmpty())

        // 空セットでは出ない
        assertTrue(engine.judgeQr(testMerchant, julyToday, emptySet()).isEmpty())
    }

    @Test
    fun `クーポン割引の判定_coupon_fixed`() {
        val dpay = QrPayment(id = "dpay", name = "d払い", brandColor = "#E60033")
        val campaign = campaignWithPeriod(
            type = "card_promotion",
            benefitType = "coupon_fixed",
            paymentMethodId = "dpay",
            rateBase = null,
            discountAmount = 100,
            minPurchase = 200,
            usageLimit = 1,
            start = "2026-07-01",
            end = "2026-07-15",
        )
        val profile = Profile(qrPayments = listOf(dpay))
        val engine = periodTestEngine(campaign, profile)
        val julyToday = LocalDate.of(2026, 7, 10)
        val results = engine.judgeQr(testMerchant, julyToday, setOf("dpay"))
        assertEquals(1, results.size)
        val q = results.first()
        assertEquals(BenefitType.COUPON_FIXED, q.benefitType)
        assertEquals(100, q.discountAmount)
        assertEquals(200, q.minPurchase)
        assertEquals(1, q.usageLimit)
        assertNull(q.effectiveRate)
        assertEquals(5, q.daysRemaining)
    }

    @Test
    fun `クーポン割引の判定_coupon_percent`() {
        val dpay = QrPayment(id = "dpay", name = "d払い", brandColor = "#E60033")
        val campaign = campaignWithPeriod(
            type = "card_promotion",
            benefitType = "coupon_percent",
            paymentMethodId = "dpay",
            rateBase = 10.0,
            perTransactionCap = 500,
            start = "2026-07-01",
            end = "2026-07-31",
        )
        val profile = Profile(qrPayments = listOf(dpay))
        val engine = periodTestEngine(campaign, profile)
        val julyToday = LocalDate.of(2026, 7, 15)
        val results = engine.judgeQr(testMerchant, julyToday, setOf("dpay"))
        assertEquals(1, results.size)
        val q = results.first()
        assertEquals(BenefitType.COUPON_PERCENT, q.benefitType)
        assertEquals(10.0, q.effectiveRate!!, 0.001)
        assertEquals(500, q.perTransactionCap)
    }

    // ---- judgeAll のテスト ----

    @Test
    fun `judgeAll はカードとQRを統合する`() {
        val paypay = QrPayment(id = "paypay", name = "PayPay", brandColor = "#FF0033")
        val cardCampaign = campaignWithPeriod().copy(id = "card1")
        val qrCampaign = campaignWithPeriod(
            type = "card_promotion",
            paymentMethodId = "paypay",
            rateBase = 20.0,
        ).copy(id = "qr1")
        val testCardForCard1 = testCard.copy(campaignId = "card1")
        val engine = JudgmentEngine(
            PoikatsuData(
                merchants = listOf(testMerchant),
                campaigns = listOf(cardCampaign, qrCampaign),
                profile = Profile(cards = listOf(testCardForCard1), qrPayments = listOf(paypay)),
                updatedAt = "2026-06-01",
            ),
        )
        val result = engine.judgeAll(testMerchant, today, setOf("paypay"))
        assertEquals(1, result.cardJudgments.size)
        assertEquals(1, result.qrJudgments.size)
        assertNotNull(result.bestOption)
        assertEquals("PayPay", result.bestOption!!.method)
        assertEquals(20.0, result.bestOption!!.rate!!, 0.001)
    }

    @Test
    fun `bestOption は定率で最高のものを選ぶ`() {
        val paypay = QrPayment(id = "paypay", name = "PayPay", brandColor = "#FF0033")
        val campaign5 = campaignWithPeriod(rateBase = 5.0).copy(id = "c5")
        val campaign20 = campaignWithPeriod(
            type = "card_promotion",
            paymentMethodId = "paypay",
            rateBase = 20.0,
        ).copy(id = "c20")
        val testCardForC5 = testCard.copy(campaignId = "c5", effectiveRateDefault = 5.0)
        val engine = JudgmentEngine(
            PoikatsuData(
                merchants = listOf(testMerchant),
                campaigns = listOf(campaign5, campaign20),
                profile = Profile(cards = listOf(testCardForC5), qrPayments = listOf(paypay)),
                updatedAt = "2026-06-01",
            ),
        )
        val result = engine.judgeAll(testMerchant, today, setOf("paypay"))
        assertEquals("PayPay", result.bestOption!!.method)
        assertEquals(20.0, result.bestOption!!.rate!!, 0.001)
    }

    // ---- BenefitType のテスト ----

    @Test
    fun `BenefitType の文字列変換`() {
        assertEquals(BenefitType.REBATE, BenefitType.fromString("rebate"))
        assertEquals(BenefitType.COUPON_PERCENT, BenefitType.fromString("coupon_percent"))
        assertEquals(BenefitType.COUPON_FIXED, BenefitType.fromString("coupon_fixed"))
        assertEquals(BenefitType.REBATE, BenefitType.fromString("unknown"))
    }

    // ---- 実データの新フィールド検証 ----

    @Test
    fun `実データ_既存施策のtype_benefitType_storeScopeが設定されている`() {
        data.campaigns.forEach { c ->
            assertTrue("${c.id}: type should be card_program", c.type == "card_program")
            assertTrue("${c.id}: benefitType should be rebate", c.benefitType == "rebate")
            assertTrue("${c.id}: storeScope should be managed", c.storeScope == "managed")
        }
    }

    @Test
    fun `実データ_QR決済カタログが読み込めている`() {
        val qr = data.profile.qrPayments
        assertTrue(qr.isNotEmpty())
        assertTrue(qr.any { it.id == "paypay" })
        assertTrue(qr.any { it.id == "aupay" })
        assertTrue(qr.any { it.id == "dpay" })
        assertTrue(qr.any { it.id == "rakuten_pay" })
    }

    @Test
    fun `実データ_yolpConfigが読み込めている`() {
        val config = data.yolpConfig
        assertNotNull(config)
        assertEquals(2, config!!.gcGroups.size)
        assertEquals("0123,0115,0101013", config.gcGroups[0].gc)
        assertEquals("0205", config.gcGroups[1].gc)
    }

    @Test
    fun `実データ_keyword検索のmerchantが正しく設定されている`() {
        val keywordMerchants = data.merchants.filter { it.yolpSearch == "keyword" }
        val keywordIds = keywordMerchants.map { it.id }.toSet()
        assertTrue("curves" in keywordIds)
        assertTrue("akachan_honpo" in keywordIds)
        assertTrue("ok_store" in keywordIds)
        assertTrue("pizza_hut" in keywordIds)
        assertTrue("ueshima_coffee" in keywordIds)
        assertTrue("hamazushi" in keywordIds)
    }

    @Test
    fun `実データ_coke_onはyolp_search_none`() {
        val cokeOn = data.merchants.first { it.id == "coke_on" }
        assertEquals("none", cokeOn.yolpSearch)
    }

    @Test
    fun `実データ_gc検索のmerchantはデフォルトのgc`() {
        val gcMerchants = data.merchants.filter { it.yolpSearch == "gc" }
        assertTrue(gcMerchants.any { it.id == "seven_eleven" })
        assertTrue(gcMerchants.any { it.id == "mcdonalds" })
        assertTrue(gcMerchants.any { it.id == "gusto" })
    }
}

class MunicipalitiesTest {

    @Test
    fun `自治体マスタは全47都道府県を含む`() {
        val file = File("../data/municipalities.json")
        if (!file.exists()) return
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val master = json.decodeFromString<Map<String, List<String>>>(file.readText())
        assertEquals(47, master.size)
        assertTrue("北海道" in master)
        assertTrue("東京都" in master)
        assertTrue("沖縄県" in master)
    }

    @Test
    fun `東京都は23区を含む`() {
        val file = File("../data/municipalities.json")
        if (!file.exists()) return
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val master = json.decodeFromString<Map<String, List<String>>>(file.readText())
        val tokyo = master["東京都"] ?: return
        val wards = tokyo.filter { it.endsWith("区") }
        assertEquals(23, wards.size)
        assertTrue("渋谷区" in wards)
        assertTrue("千代田区" in wards)
    }

    @Test
    fun `RegisteredMunicipalityのシリアライズが往復する`() {
        val json = kotlinx.serialization.json.Json
        val m = RegisteredMunicipality("東京都", "渋谷区")
        val encoded = json.encodeToString(RegisteredMunicipality.serializer(), m)
        val decoded = json.decodeFromString(RegisteredMunicipality.serializer(), encoded)
        assertEquals(m, decoded)
    }
}

class JapaneseTextTest {

    @Test
    fun `カタカナはひらがなに正規化される`() {
        assertEquals("まくどなるど", JapaneseText.normalize("マクドナルド"))
    }

    @Test
    fun `半角カナと全角英数はNFKCで統一される`() {
        assertEquals("せぶん", JapaneseText.normalize("ｾﾌﾞﾝ"))
        assertEquals("kfc", JapaneseText.normalize("ＫＦＣ"))
    }

    @Test
    fun `記号と空白は無視され長音は残る`() {
        assertEquals("せぶんいれぶん", JapaneseText.normalize("セブン-イレブン"))
        assertEquals("かふぇどくりえ", JapaneseText.normalize("カフェ・ド・クリエ"))
        assertEquals("ろーそん", JapaneseText.normalize("ロー ソン"))
    }
}
