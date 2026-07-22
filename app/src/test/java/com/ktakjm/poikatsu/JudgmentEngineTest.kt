package com.ktakjm.poikatsu

import com.ktakjm.poikatsu.data.Campaign
import com.ktakjm.poikatsu.data.MIN_PURCHASE_SCOPE_PERIOD_TOTAL
import com.ktakjm.poikatsu.data.MIN_PURCHASE_SCOPE_TRANSACTION
import com.ktakjm.poikatsu.data.Merchant
import com.ktakjm.poikatsu.data.MerchantRule
import com.ktakjm.poikatsu.data.OfficialStoreList
import com.ktakjm.poikatsu.data.PaymentCard
import com.ktakjm.poikatsu.data.PointMultiplier
import com.ktakjm.poikatsu.data.PoikatsuData
import com.ktakjm.poikatsu.data.PoikatsuJson
import com.ktakjm.poikatsu.data.ProductScope
import com.ktakjm.poikatsu.data.QrAppPackage
import com.ktakjm.poikatsu.data.QrPayment
import com.ktakjm.poikatsu.data.Recurrence
import com.ktakjm.poikatsu.data.Region
import com.ktakjm.poikatsu.domain.AppLink
import com.ktakjm.poikatsu.domain.BenefitType
import com.ktakjm.poikatsu.domain.CampaignStatus
import com.ktakjm.poikatsu.domain.CampaignType
import com.ktakjm.poikatsu.domain.JudgmentEngine
import com.ktakjm.poikatsu.domain.StoreEligibility
import com.ktakjm.poikatsu.domain.WALLET_APP_LABEL
import com.ktakjm.poikatsu.domain.WALLET_APP_PACKAGE
import com.ktakjm.poikatsu.domain.bestBenefitLabel
import com.ktakjm.poikatsu.domain.campaignType
import com.ktakjm.poikatsu.domain.formatBenefit
import com.ktakjm.poikatsu.domain.isTimeLimited
import com.ktakjm.poikatsu.domain.nextTargetDay
import com.ktakjm.poikatsu.domain.recurrenceLabel
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
 * フィクスチャデータでロジックを検証する。実データには依存しない。
 */
class JudgmentEngineTest {

    private val data = PoikatsuData(
        merchants = listOf(
            Merchant(id = "mcdonalds", name = "マクドナルド", reading = "まくどなるど", aliases = listOf("マック"), category = "ファストフード"),
            Merchant(id = "seven_eleven", name = "セブン-イレブン", reading = "せぶんいれぶん", aliases = listOf("セブンイレブン"), category = "コンビニ"),
            Merchant(id = "lawson", name = "ローソン", reading = "ろーそん", category = "コンビニ"),
            Merchant(id = "starbucks", name = "スターバックス", reading = "すたーばっくす", aliases = listOf("スタバ"), category = "カフェ"),
            Merchant(id = "gusto", name = "ガスト", reading = "がすと", category = "ファミレス"),
            Merchant(id = "steak_gusto", name = "ステーキガスト", reading = "すてーきがすと", category = "ファミレス"),
            Merchant(id = "saizeriya", name = "サイゼリヤ", reading = "さいぜりや", aliases = listOf("サイゼ"), category = "ファミレス"),
            Merchant(id = "kfc", name = "ケンタッキーフライドチキン", reading = "けんたっきーふらいどちきん", aliases = listOf("KFC"), category = "ファストフード"),
            Merchant(id = "sushiro", name = "スシロー", reading = "すしろー", category = "回転寿司"),
            Merchant(id = "kurazushi", name = "くら寿司", reading = "くらずし", category = "回転寿司"),
            Merchant(id = "test_super", name = "テストスーパー", reading = "てすとすーぱー", category = "スーパー"),
            Merchant(id = "test_other", name = "テストその他", reading = "てすとそのた", category = "その他"),
        ),
        campaigns = listOf(
            Campaign(
                id = "smcc_combini_restaurant",
                operator = "三井住友カード",
                cardId = "smcc",
                name = "三井住友コンビニ・飲食",
                paymentInstruction = "タッチ決済",
                rateBase = 7.0,
                verifiedDate = "2026-06-01",
                merchantRules = listOf(
                    MerchantRule(merchantId = "mcdonalds"),
                    MerchantRule(merchantId = "seven_eleven"),
                    MerchantRule(merchantId = "lawson"),
                    MerchantRule(merchantId = "starbucks"),
                    MerchantRule(merchantId = "gusto"),
                    MerchantRule(merchantId = "steak_gusto"),
                    MerchantRule(merchantId = "saizeriya"),
                    MerchantRule(merchantId = "kfc"),
                ),
            ),
            Campaign(
                id = "mufg_point_up_program",
                operator = "三菱UFJカード",
                cardId = "mufg",
                name = "MUFGポイントアップ",
                paymentInstruction = "カード利用",
                rateBase = 5.5,
                verifiedDate = "2026-06-01",
                merchantRules = listOf(
                    MerchantRule(merchantId = "seven_eleven"),
                    MerchantRule(merchantId = "sushiro"),
                    MerchantRule(merchantId = "kurazushi", ineligibleBrands = listOf("Amex")),
                ),
            ),
        ),
        cards = listOf(
            PaymentCard(id = "smcc", cardName = "三井住友カード", effectiveRateDefault = 7.0),
            PaymentCard(id = "mufg", cardName = "MUFGカード", brand = "Mastercard", effectiveRateDefault = 7.0),
        ),
        updatedAt = "2026-06-01",
    )
    private val engine = JudgmentEngine(data)
    private val today = LocalDate.of(2026, 6, 28)

    // ---- 検索ロジック ----

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
        val judgments = engine.judgeCards(merchant, today)
        assertEquals(2, judgments.size)
    }

    @Test
    fun `マクドナルドは三井住友のみ対象`() {
        val merchant = data.merchants.first { it.id == "mcdonalds" }
        val judgments = engine.judgeCards(merchant, today)
        assertEquals(listOf("smcc_combini_restaurant"), judgments.map { it.campaign.id })
    }

    @Test
    fun `MUFGはプロファイル前提で還元率7パーセント・警告なし`() {
        val merchant = data.merchants.first { it.id == "sushiro" }
        val judgment = engine.judgeCards(merchant, today).single()
        assertEquals("mufg_point_up_program", judgment.campaign.id)
        assertEquals(7.0, judgment.effectiveRate!!, 0.001)
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
        // 「す」はスシロー(回転寿司)にもヒットするが、カフェに絞ればスタバ系のみ
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

    // ---- 店舗対象判定(公式リスト) ----

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
            operator = "test",
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
            updatedAt = "2026-06-01",
        )
        return JudgmentEngine(data) to merchant
    }

    // ---- isExactNameMatch ----

    @Test
    fun `チェーン名そのままの入力は完全一致と判定される`() {
        val mcdonalds = data.merchants.first { it.id == "mcdonalds" }
        assertTrue(engine.isExactNameMatch(mcdonalds, "マック"))
        assertTrue(engine.isExactNameMatch(mcdonalds, "マクドナルド"))
        assertFalse(engine.isExactNameMatch(mcdonalds, "マクドナルド渋谷店"))
    }

    // ---- Amex / カード所有フィルタ ----

    private fun engineWithCards(cards: List<PaymentCard>) = JudgmentEngine(data.copy(cards = cards))

    private fun cardsWithMufgBrand(brand: String) = data.cards.map {
        if (it.id == "mufg") it.copy(brand = brand) else it
    }

    @Test
    fun `除外ブランドは ineligible_brands の店舗で MUFG が対象外になる`() {
        val kurazushi = data.merchants.first { it.id == "kurazushi" } // ineligible_brands = ["Amex"]
        val amexEngine = engineWithCards(cardsWithMufgBrand("Amex"))
        assertTrue(amexEngine.judgeCards(kurazushi, today).none { it.campaign.id == "mufg_point_up_program" })
        // リストに無いブランド(既定カタログ=Mastercard)では従来どおり MUFG が出る
        assertTrue(engine.judgeCards(kurazushi, today).any { it.campaign.id == "mufg_point_up_program" })
    }

    @Test
    fun `除外ブランドでも ineligible_brands の無い店舗では MUFG が残る`() {
        val sevenEleven = data.merchants.first { it.id == "seven_eleven" } // ineligible_brands なし
        val amexEngine = engineWithCards(cardsWithMufgBrand("Amex"))
        assertTrue(amexEngine.judgeCards(sevenEleven, today).any { it.campaign.id == "mufg_point_up_program" })
    }

    @Test
    fun `ineligible_brandsは複数ブランドを除外できる`() {
        val kurazushi = data.merchants.first { it.id == "kurazushi" }
        val multiBrandData = data.copy(
            campaigns = data.campaigns.map { c ->
                if (c.id != "mufg_point_up_program") c else c.copy(
                    merchantRules = c.merchantRules.map { r ->
                        if (r.merchantId == "kurazushi") r.copy(ineligibleBrands = listOf("Amex", "JCB")) else r
                    },
                )
            },
        )
        val jcbEngine = JudgmentEngine(multiBrandData.copy(cards = cardsWithMufgBrand("JCB")))
        assertTrue(jcbEngine.judgeCards(kurazushi, today).none { it.campaign.id == "mufg_point_up_program" })
        // リストに無いブランドは従来どおり対象
        val visaEngine = JudgmentEngine(multiBrandData.copy(cards = cardsWithMufgBrand("Visa")))
        assertTrue(visaEngine.judgeCards(kurazushi, today).any { it.campaign.id == "mufg_point_up_program" })
    }

    @Test
    fun `ブランド未選択で除外ブランドを取りうるカードはineligible_brandsの店を除外する(不利側に倒す)`() {
        val kurazushi = data.merchants.first { it.id == "kurazushi" }
        fun mufgWith(brands: List<String>) = data.cards.map {
            if (it.id == "mufg") it.copy(brand = "", brands = brands) else it
        }
        // 除外ブランド(Amex)を選択肢に含むカードが未選択 → 好条件を誤提示しないよう除外
        val couldBeAmex = engineWithCards(mufgWith(listOf("Visa", "Mastercard", "JCB", "Amex")))
        assertTrue(couldBeAmex.judgeCards(kurazushi, today).none { it.campaign.id == "mufg_point_up_program" })
        // カタログに選択肢情報が無い(旧データ等)場合も保守的に除外
        val unknownBrands = engineWithCards(mufgWith(emptyList()))
        assertTrue(unknownBrands.judgeCards(kurazushi, today).none { it.campaign.id == "mufg_point_up_program" })
        // ineligible_brands の無い店には未選択でも出る
        val sevenEleven = data.merchants.first { it.id == "seven_eleven" }
        assertTrue(couldBeAmex.judgeCards(sevenEleven, today).any { it.campaign.id == "mufg_point_up_program" })
    }

    @Test
    fun `ブランド未選択でも除外ブランドを取り得ないカードはineligible_brandsの店を除外しない`() {
        val kurazushi = data.merchants.first { it.id == "kurazushi" }
        val visaOrMaster = engineWithCards(
            data.cards.map {
                if (it.id == "mufg") it.copy(brand = "", brands = listOf("Visa", "Mastercard")) else it
            },
        )
        assertTrue(visaOrMaster.judgeCards(kurazushi, today).any { it.campaign.id == "mufg_point_up_program" })
    }

    @Test
    fun `未所有カードの施策は判定に出ない`() {
        val kurazushi = data.merchants.first { it.id == "kurazushi" }
        // MUFG カードを所有していない(カード一覧から除外)ケース
        val onlySmcc = data.cards.filter { it.id == "smcc" }
        assertTrue(engineWithCards(onlySmcc).judgeCards(kurazushi, today).none { it.campaign.id == "mufg_point_up_program" })
        // どのカードも所有していなければ判定は空
        assertTrue(engineWithCards(emptyList()).judgeCards(kurazushi, today).isEmpty())
    }

    // ---- 期間フィルタのテスト ----

    private fun campaignWithPeriod(
        start: String? = null,
        end: String? = null,
        type: CampaignType = CampaignType.CARD_PROGRAM,
        storeScope: String = "managed",
        benefitType: BenefitType = BenefitType.REBATE,
        // 施策の帰属は cardId / cardBrand / paymentMethodId のちょうど1つ: 他を使うときは cardId = null を渡す
        cardId: String? = "test_card",
        cardBrand: String? = null,
        paymentMethodId: String? = null,
        rateBase: Double? = 10.0,
        discountAmount: Int? = null,
        minPurchase: Int? = null,
        usageLimit: Int? = null,
        perTransactionCap: Int? = null,
        periodTotalCap: Int? = null,
        region: Region? = null,
        mayEndEarly: Boolean = false,
        recurrence: Recurrence? = null,
        eligibleWallets: List<String> = emptyList(),
        ineligibleWallets: List<String> = emptyList(),
        merchantRules: List<MerchantRule> = listOf(MerchantRule(merchantId = "m1")),
    ) = Campaign(
        id = "test_campaign",
        operator = "test",
        cardId = cardId,
        cardBrand = cardBrand,
        name = "テスト",
        paymentInstruction = "テスト",
        rateBase = rateBase,
        verifiedDate = "2026-06-01",
        periodStart = start,
        periodEnd = end,
        type = type.jsonValue,
        storeScope = storeScope,
        benefitType = benefitType.jsonValue,
        paymentMethodId = paymentMethodId,
        discountAmount = discountAmount,
        minPurchase = minPurchase,
        usageLimit = usageLimit,
        perTransactionCap = perTransactionCap,
        periodTotalCap = periodTotalCap,
        region = region,
        mayEndEarly = mayEndEarly,
        recurrence = recurrence,
        eligibleWallets = eligibleWallets,
        ineligibleWallets = ineligibleWallets,
        merchantRules = merchantRules,
    )

    private val testMerchant = Merchant(id = "m1", name = "テスト店", reading = "てすとてん")
    private val testCard = PaymentCard(id = "test_card", cardName = "テストカード", effectiveRateDefault = 10.0)

    private fun periodTestEngine(
        campaign: Campaign,
        cards: List<PaymentCard> = listOf(testCard),
        qrPayments: List<QrPayment> = emptyList(),
    ): JudgmentEngine =
        JudgmentEngine(
            PoikatsuData(
                merchants = listOf(testMerchant),
                campaigns = listOf(campaign),
                cards = cards,
                qrPayments = qrPayments,
                updatedAt = "2026-06-01",
            ),
        )

    @Test
    fun `常設施策(period null)はアクティブ`() {
        val campaign = campaignWithPeriod()
        val engine = periodTestEngine(campaign)
        assertEquals(CampaignStatus.ACTIVE, engine.campaignStatus(campaign, today))
        assertEquals(1, engine.judgeCards(testMerchant, today).size)
    }

    @Test
    fun `期間中の施策はアクティブ`() {
        val campaign = campaignWithPeriod(start = "2026-06-01", end = "2026-07-31")
        val engine = periodTestEngine(campaign)
        assertEquals(CampaignStatus.ACTIVE, engine.campaignStatus(campaign, today))
        assertEquals(1, engine.judgeCards(testMerchant, today).size)
    }

    @Test
    fun `期限切れの施策は非表示`() {
        val campaign = campaignWithPeriod(start = "2026-05-01", end = "2026-06-15")
        val engine = periodTestEngine(campaign)
        assertEquals(CampaignStatus.EXPIRED, engine.campaignStatus(campaign, today))
        assertTrue(engine.judgeCards(testMerchant, today).isEmpty())
    }

    @Test
    fun `未来開始の施策はもうすぐ開始`() {
        val campaign = campaignWithPeriod(start = "2026-07-01", end = "2026-07-31")
        val engine = periodTestEngine(campaign)
        assertEquals(CampaignStatus.UPCOMING, engine.campaignStatus(campaign, today))
        // judge からはフィルタされる(探す/地図タブには出さない)
        assertTrue(engine.judgeCards(testMerchant, today).isEmpty())
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
        val judgments = engine.judgeCards(testMerchant, LocalDate.of(2026, 6, 28))
        assertTrue(judgments.first().warnings.any { it.contains("残り") })
    }

    @Test
    fun `残り4日以上では警告なし`() {
        val campaign = campaignWithPeriod(start = "2026-06-01", end = "2026-07-31")
        val engine = periodTestEngine(campaign)
        val judgments = engine.judgeCards(testMerchant, today)
        assertTrue(judgments.first().warnings.isEmpty())
    }

    // ---- ウォレット(Google Pay)対応のテスト ----

    @Test
    fun `google_payがeligibleならウォレット起動リンクが付く`() {
        val campaign = campaignWithPeriod(eligibleWallets = listOf("apple_pay", "google_pay"))
        val judgment = periodTestEngine(campaign).judgeCards(testMerchant, today).first()
        assertEquals(listOf(AppLink(WALLET_APP_PACKAGE, WALLET_APP_LABEL)), judgment.appLinks)
        assertTrue(judgment.warnings.isEmpty())
    }

    @Test
    fun `google_payがineligibleなら警告が出て起動リンクは付かない`() {
        val campaign = campaignWithPeriod(ineligibleWallets = listOf("google_pay"))
        val judgment = periodTestEngine(campaign).judgeCards(testMerchant, today).first()
        assertTrue(judgment.appLinks.isEmpty())
        assertTrue(judgment.warnings.any { it.contains("Google Pay") })
        // apple_pay が eligible と分かっていないときは Apple Pay に言及しない(断定しない)
        assertTrue(judgment.warnings.none { it.contains("Apple Pay") })
    }

    @Test
    fun `google_pay対象外かつapple_pay対象なら警告にApple Payは対象と付記される`() {
        val campaign = campaignWithPeriod(
            eligibleWallets = listOf("apple_pay"),
            ineligibleWallets = listOf("google_pay"),
        )
        val judgment = periodTestEngine(campaign).judgeCards(testMerchant, today).first()
        assertTrue(judgment.appLinks.isEmpty())
        assertTrue(judgment.warnings.any { it.contains("Google Pay") && it.contains("Apple Payは対象") })
    }

    @Test
    fun `ウォレット未指定なら起動リンクも警告も出ない`() {
        // 3状態の「不明」: 断定できないので何も出さない(payment_instruction の文章が担う)
        val judgment = periodTestEngine(campaignWithPeriod()).judgeCards(testMerchant, today).first()
        assertTrue(judgment.appLinks.isEmpty())
        assertTrue(judgment.warnings.isEmpty())
    }

    @Test
    fun `apple_payのみeligibleでは起動リンクを出さない`() {
        // apple_pay は起動リンクには使わない(Google Pay 対象外警告の付記にのみ使う)
        val campaign = campaignWithPeriod(eligibleWallets = listOf("apple_pay"))
        val judgment = periodTestEngine(campaign).judgeCards(testMerchant, today).first()
        assertTrue(judgment.appLinks.isEmpty())
        assertTrue(judgment.warnings.isEmpty())
    }

    // ---- store_scope フィルタのテスト ----

    @Test
    fun `store_scope_external は judge に含まれない`() {
        val campaign = campaignWithPeriod(storeScope = "external", type = CampaignType.MUNICIPAL)
        val engine = periodTestEngine(campaign)
        assertTrue(engine.judgeCards(testMerchant, today).isEmpty())
    }

    @Test
    fun `store_scope_managed は judge に含まれる`() {
        val campaign = campaignWithPeriod(storeScope = "managed")
        val engine = periodTestEngine(campaign)
        assertEquals(1, engine.judgeCards(testMerchant, today).size)
    }

    // ---- QR 判定のテスト ----

    @Test
    fun `QR決済の判定_利用中のQRのみ返る`() {
        val paypay = QrPayment(id = "paypay", name = "PayPay", brandColor = "#FF0033")
        val campaign = campaignWithPeriod(
            type = CampaignType.PROMOTION,
            cardId = null,
            paymentMethodId = "paypay",
            rateBase = 20.0,
            start = "2026-07-01",
            end = "2026-07-31",
        )
        val engine = periodTestEngine(campaign, cards = emptyList(), qrPayments = listOf(paypay))

        // 7月中はアクティブ
        val julyToday = LocalDate.of(2026, 7, 15)
        val results = engine.judgeQr(testMerchant, julyToday, setOf("paypay"))
        assertEquals(1, results.size)
        assertEquals("PayPay", results.first().badgeLabel)
        // app_packages の無いカタログでは起動リンク(appLinks)は付かない
        assertTrue(results.first().appLinks.isEmpty())
        assertEquals(20.0, results.first().effectiveRate!!, 0.001)
        assertEquals(BenefitType.REBATE, results.first().benefitType)

        // 未登録のQR決済では出ない
        assertTrue(engine.judgeQr(testMerchant, julyToday, setOf("aupay")).isEmpty())

        // 空セットでは出ない
        assertTrue(engine.judgeQr(testMerchant, julyToday, emptySet()).isEmpty())
    }

    @Test
    fun `QR決済のapp_packagesがあれば起動リンクは全アプリぶん_ラベルはアプリ実名`() {
        // AEON Pay のように 1 サービスを複数アプリが担うケース: 起動リンクは候補全部を順に出す
        val aeonPay = QrPayment(
            id = "aeon_pay",
            name = "AEON Pay",
            brandColor = "#B60081",
            appPackages = listOf(
                QrAppPackage(packageName = "jp.co.aeon.credit.android.wallet", label = "AEON Pay"),
                QrAppPackage(packageName = "jp.co.aeonst.app.myaeon", label = "iAEON"),
            ),
        )
        val campaign = campaignWithPeriod(
            type = CampaignType.PROMOTION,
            cardId = null,
            paymentMethodId = "aeon_pay",
            start = "2026-07-01",
            end = "2026-07-31",
        )
        val engine = periodTestEngine(campaign, cards = emptyList(), qrPayments = listOf(aeonPay))
        val judgment = engine.judgeQr(testMerchant, LocalDate.of(2026, 7, 15), setOf("aeon_pay")).first()
        assertEquals(
            listOf(
                AppLink("jp.co.aeon.credit.android.wallet", "AEON Payアプリ"),
                AppLink("jp.co.aeonst.app.myaeon", "iAEONアプリ"),
            ),
            judgment.appLinks,
        )
    }

    @Test
    fun `即時割引の判定_定額`() {
        val dpay = QrPayment(id = "dpay", name = "d払い", brandColor = "#E60033")
        val campaign = campaignWithPeriod(
            type = CampaignType.PROMOTION,
            benefitType = BenefitType.DISCOUNT,
            cardId = null,
            paymentMethodId = "dpay",
            rateBase = null,
            discountAmount = 100,
            minPurchase = 200,
            usageLimit = 1,
            start = "2026-07-01",
            end = "2026-07-15",
        )
        val engine = periodTestEngine(campaign, cards = emptyList(), qrPayments = listOf(dpay))
        val julyToday = LocalDate.of(2026, 7, 10)
        val results = engine.judgeQr(testMerchant, julyToday, setOf("dpay"))
        assertEquals(1, results.size)
        val q = results.first()
        assertEquals(BenefitType.DISCOUNT, q.benefitType)
        assertEquals(100, q.discountAmount)
        assertEquals(200, q.minPurchase)
        assertEquals("お一人様1回まで", q.usageLimitText)
        assertNull(q.effectiveRate)
        assertEquals(5, q.daysRemaining)
    }

    @Test
    fun `即時割引の判定_定率`() {
        val dpay = QrPayment(id = "dpay", name = "d払い", brandColor = "#E60033")
        val campaign = campaignWithPeriod(
            type = CampaignType.PROMOTION,
            benefitType = BenefitType.DISCOUNT,
            cardId = null,
            paymentMethodId = "dpay",
            rateBase = 10.0,
            perTransactionCap = 500,
            start = "2026-07-01",
            end = "2026-07-31",
        )
        val engine = periodTestEngine(campaign, cards = emptyList(), qrPayments = listOf(dpay))
        val julyToday = LocalDate.of(2026, 7, 15)
        val results = engine.judgeQr(testMerchant, julyToday, setOf("dpay"))
        assertEquals(1, results.size)
        val q = results.first()
        assertEquals(BenefitType.DISCOUNT, q.benefitType)
        assertEquals(10.0, q.effectiveRate!!, 0.001)
        assertEquals(500, q.perTransactionCap)
    }

    // ---- judgeAll のテスト ----

    @Test
    fun `judgeAll はカードとQRを統合する`() {
        val paypay = QrPayment(id = "paypay", name = "PayPay", brandColor = "#FF0033")
        val cardCampaign = campaignWithPeriod().copy(id = "card1")
        val qrCampaign = campaignWithPeriod(
            type = CampaignType.PROMOTION,
            cardId = null,
            paymentMethodId = "paypay",
            rateBase = 20.0,
        ).copy(id = "qr1")
        val engine = JudgmentEngine(
            PoikatsuData(
                merchants = listOf(testMerchant),
                campaigns = listOf(cardCampaign, qrCampaign),
                cards = listOf(testCard),
                qrPayments = listOf(paypay),
                updatedAt = "2026-06-01",
            ),
        )
        val result = engine.judgeAll(testMerchant, today, setOf("paypay"))
        assertEquals(2, result.judgments.size)
        assertNotNull(result.bestOption)
        assertEquals("PayPay", result.bestOption!!.method)
        assertEquals(20.0, result.bestOption!!.rate!!, 0.001)
    }

    @Test
    fun `bestOption は定率で最高のものを選ぶ`() {
        val paypay = QrPayment(id = "paypay", name = "PayPay", brandColor = "#FF0033")
        val campaign5 = campaignWithPeriod(rateBase = 5.0).copy(id = "c5")
        val campaign20 = campaignWithPeriod(
            type = CampaignType.PROMOTION,
            cardId = null,
            paymentMethodId = "paypay",
            rateBase = 20.0,
        ).copy(id = "c20")
        val testCard5pct = testCard.copy(effectiveRateDefault = 5.0)
        val engine = JudgmentEngine(
            PoikatsuData(
                merchants = listOf(testMerchant),
                campaigns = listOf(campaign5, campaign20),
                cards = listOf(testCard5pct),
                qrPayments = listOf(paypay),
                updatedAt = "2026-06-01",
            ),
        )
        val result = engine.judgeAll(testMerchant, today, setOf("paypay"))
        assertEquals("PayPay", result.bestOption!!.method)
        assertEquals(20.0, result.bestOption!!.rate!!, 0.001)
    }

    @Test
    fun `カードの定額施策に常設率が混ざらず_定額同士は金額降順`() {
        // 常設率の高いカード(10%+ウエル活)の300円引きと、率の低いカード(1%)の500円引き。
        // カードの常設率が effectiveRate に漏れるとソートが率比較で決まり300円引きが先に並ぶ
        // (定額同士は金額降順が正)。ウエル活注記もカード率を表示しない定額施策では出さない
        val discount300 = campaignWithPeriod(
            type = CampaignType.PROMOTION,
            benefitType = BenefitType.DISCOUNT,
            rateBase = null,
            discountAmount = 300,
        ).copy(id = "d300")
        val discount500 = campaignWithPeriod(
            type = CampaignType.PROMOTION,
            benefitType = BenefitType.DISCOUNT,
            cardId = "low_rate_card",
            rateBase = null,
            discountAmount = 500,
        ).copy(id = "d500")
        val welcatsuCard = testCard.copy(
            pointMultiplier = PointMultiplier(label = "ウエル活", factor = 1.5),
            welcatsuApplied = true,
        )
        val engine = JudgmentEngine(
            PoikatsuData(
                merchants = listOf(testMerchant),
                campaigns = listOf(discount300, discount500),
                cards = listOf(
                    welcatsuCard,
                    PaymentCard(id = "low_rate_card", cardName = "低率カード", effectiveRateDefault = 1.0),
                ),
                updatedAt = "2026-06-01",
            ),
        )
        val result = engine.judgeAll(testMerchant, today)
        assertTrue(result.judgments.all { it.effectiveRate == null })
        assertEquals(listOf("d500", "d300"), result.judgments.map { it.campaign.id })
        assertTrue(result.judgments.none { it.welcatsuApplied })
    }

    // ---- BenefitType / CampaignType のテスト ----

    @Test
    fun `BenefitType の文字列変換`() {
        assertEquals(BenefitType.REBATE, BenefitType.fromString("rebate"))
        assertEquals(BenefitType.DISCOUNT, BenefitType.fromString("discount"))
        assertEquals(BenefitType.REBATE, BenefitType.fromString("unknown"))
    }

    @Test
    fun `CampaignType の文字列変換`() {
        assertEquals(CampaignType.CARD_PROGRAM, CampaignType.fromString("card_program"))
        assertEquals(CampaignType.PROMOTION, CampaignType.fromString("promotion"))
        assertEquals(CampaignType.MUNICIPAL, CampaignType.fromString("municipal"))
        assertEquals(CampaignType.CARD_PROGRAM, CampaignType.fromString("unknown"))
    }

    // ---- formatBenefit のテスト ----

    @Test
    fun `formatBenefit_4象限の網羅`() {
        assertEquals("20% 還元", formatBenefit(BenefitType.REBATE, 20.0, null).toString())
        assertEquals("500円還元", formatBenefit(BenefitType.REBATE, null, 500).toString())
        assertEquals("10% OFF", formatBenefit(BenefitType.DISCOUNT, 10.0, null).toString())
        assertEquals("300円引き", formatBenefit(BenefitType.DISCOUNT, null, 300).toString())
    }

    @Test
    fun `formatBenefit_rebate定額は円還元`() {
        val label = formatBenefit(BenefitType.REBATE, null, 500)
        assertNotNull(label)
        assertEquals("500円", label!!.value)
        assertEquals("還元", label.suffix)
        assertEquals("500円還元", label.toString())
    }

    @Test
    fun `formatBenefit_両方nullならnull`() {
        assertNull(formatBenefit(BenefitType.REBATE, null, null))
        assertNull(formatBenefit(BenefitType.DISCOUNT, null, null))
    }

    @Test
    fun `formatBenefit_rebate両方ありならdiscountを優先`() {
        val label = formatBenefit(BenefitType.REBATE, 10.0, 500)
        assertEquals("500円還元", label.toString())
    }

    // ---- bestBenefitLabel(一覧・プレビューの「最良特典」)のテスト ----

    @Test
    fun `bestBenefitLabel_定率があればbestOption由来のラベル`() {
        val campaign = campaignWithPeriod(rateBase = 7.0)
        val engine = periodTestEngine(campaign, cards = listOf(testCard.copy(effectiveRateDefault = 7.0)))
        val label = engine.judgeAll(testMerchant, today).bestBenefitLabel()
        assertEquals("7% 還元", label.toString())
    }

    @Test
    fun `bestBenefitLabel_定額クーポンのみのチェーンは円引きラベル(0パーセント表示にならない)`() {
        val paypay = QrPayment(id = "paypay", name = "PayPay", brandColor = "#FF0033")
        val campaign = campaignWithPeriod(
            type = CampaignType.PROMOTION,
            benefitType = BenefitType.DISCOUNT,
            cardId = null,
            paymentMethodId = "paypay",
            rateBase = null,
            discountAmount = 300,
        )
        val engine = periodTestEngine(campaign, cards = emptyList(), qrPayments = listOf(paypay))
        val result = engine.judgeAll(testMerchant, today, setOf("paypay"))
        assertNull(result.bestOption) // 定額は還元率比較の対象にしないポリシーは維持
        assertEquals("300円引き", result.bestBenefitLabel().toString())
    }

    @Test
    fun `bestBenefitLabel_定額還元のみのチェーンは円還元ラベル`() {
        val paypay = QrPayment(id = "paypay", name = "PayPay", brandColor = "#FF0033")
        val campaign = campaignWithPeriod(
            type = CampaignType.PROMOTION,
            benefitType = BenefitType.REBATE,
            cardId = null,
            paymentMethodId = "paypay",
            rateBase = null,
            discountAmount = 500,
        )
        val engine = periodTestEngine(campaign, cards = emptyList(), qrPayments = listOf(paypay))
        val label = engine.judgeAll(testMerchant, today, setOf("paypay")).bestBenefitLabel()
        assertEquals("500円還元", label.toString())
    }

    @Test
    fun `bestBenefitLabel_定率と定額が混在すれば定率(bestOption)を優先`() {
        val paypay = QrPayment(id = "paypay", name = "PayPay", brandColor = "#FF0033")
        val fixedCoupon = campaignWithPeriod(
            type = CampaignType.PROMOTION,
            benefitType = BenefitType.DISCOUNT,
            cardId = null,
            paymentMethodId = "paypay",
            rateBase = null,
            discountAmount = 1000,
        ).copy(id = "fixed")
        val rateCampaign = campaignWithPeriod(rateBase = 5.0).copy(id = "rate")
        val engine = JudgmentEngine(
            PoikatsuData(
                merchants = listOf(testMerchant),
                campaigns = listOf(fixedCoupon, rateCampaign),
                cards = listOf(testCard.copy(effectiveRateDefault = 5.0)),
                qrPayments = listOf(paypay),
                updatedAt = "2026-06-01",
            ),
        )
        val label = engine.judgeAll(testMerchant, today, setOf("paypay")).bestBenefitLabel()
        assertEquals("5% 還元", label.toString())
    }

    // ---- product_scope(対象商品限定。メーカー×小売×決済連動キャンペーン #43)のテスト ----

    @Test
    fun `bestOptionは対象商品限定の施策を除外する`() {
        val paypay = QrPayment(id = "paypay", name = "PayPay", brandColor = "#FF0033")
        val unconditional = campaignWithPeriod(rateBase = 7.0).copy(id = "base")
        val productScoped = campaignWithPeriod(
            type = CampaignType.PROMOTION,
            cardId = null,
            paymentMethodId = "paypay",
            rateBase = 30.0,
        ).copy(id = "maker30", productScope = ProductScope(label = "花王商品(一部除く)"))
        val engine = JudgmentEngine(
            PoikatsuData(
                merchants = listOf(testMerchant),
                campaigns = listOf(unconditional, productScoped),
                cards = listOf(testCard.copy(effectiveRateDefault = 7.0)),
                qrPayments = listOf(paypay),
                updatedAt = "2026-06-01",
            ),
        )
        val result = engine.judgeAll(testMerchant, today, setOf("paypay"))
        // 判定カードには両方出るが、「最良」は全商品に効く7%(対象商品を買わない人に30%と誤提示しない)
        assertEquals(2, result.judgments.size)
        assertEquals("テストカード", result.bestOption!!.method)
        assertEquals(7.0, result.bestOption!!.rate!!, 0.001)
        assertEquals("7% 還元", result.bestBenefitLabel().toString())
    }

    @Test
    fun `bestBenefitLabel_対象商品限定しか無いチェーンは対象商品の付記つきラベル`() {
        val paypay = QrPayment(id = "paypay", name = "PayPay", brandColor = "#FF0033")
        val productScoped = campaignWithPeriod(
            type = CampaignType.PROMOTION,
            cardId = null,
            paymentMethodId = "paypay",
            rateBase = 30.0,
        ).copy(productScope = ProductScope(label = "花王商品(一部除く)"))
        val engine = periodTestEngine(productScoped, cards = emptyList(), qrPayments = listOf(paypay))
        val result = engine.judgeAll(testMerchant, today, setOf("paypay"))
        assertNull(result.bestOption)
        assertEquals("30% 還元(対象商品)", result.bestBenefitLabel().toString())
    }

    @Test
    fun `bestBenefitLabel_定額同士は金額が大きいものを出す`() {
        val paypay = QrPayment(id = "paypay", name = "PayPay", brandColor = "#FF0033")
        fun coupon(id: String, amount: Int) = campaignWithPeriod(
            type = CampaignType.PROMOTION,
            benefitType = BenefitType.DISCOUNT,
            cardId = null,
            paymentMethodId = "paypay",
            rateBase = null,
            discountAmount = amount,
        ).copy(id = id)
        val engine = JudgmentEngine(
            PoikatsuData(
                merchants = listOf(testMerchant),
                campaigns = listOf(coupon("c300", 300), coupon("c500", 500)),
                cards = emptyList(),
                qrPayments = listOf(paypay),
                updatedAt = "2026-06-01",
            ),
        )
        val label = engine.judgeAll(testMerchant, today, setOf("paypay")).bestBenefitLabel()
        assertEquals("500円引き", label.toString())
    }

    @Test
    fun `bestBenefitLabel_判定なしならnull`() {
        val campaign = campaignWithPeriod(start = "2026-01-01", end = "2026-01-31") // 終了済み
        val engine = periodTestEngine(campaign)
        assertNull(engine.judgeAll(testMerchant, today).bestBenefitLabel())
    }

    // ---- rebate+定額の判定テスト ----

    @Test
    fun `rebate定額の判定_discountAmountで判定結果が出る`() {
        val paypay = QrPayment(id = "paypay", name = "PayPay", brandColor = "#FF0033")
        val campaign = campaignWithPeriod(
            type = CampaignType.PROMOTION,
            benefitType = BenefitType.REBATE,
            cardId = null,
            paymentMethodId = "paypay",
            rateBase = null,
            discountAmount = 500,
            start = "2026-07-01",
            end = "2026-07-31",
        )
        val engine = periodTestEngine(campaign, cards = emptyList(), qrPayments = listOf(paypay))
        val julyToday = LocalDate.of(2026, 7, 15)
        val results = engine.judgeQr(testMerchant, julyToday, setOf("paypay"))
        assertEquals(1, results.size)
        val q = results.first()
        assertEquals(BenefitType.REBATE, q.benefitType)
        assertEquals(500, q.discountAmount)
        assertNull(q.effectiveRate)
        assertEquals("500円還元", formatBenefit(q.benefitType, q.effectiveRate, q.discountAmount).toString())
    }

    // ---- B-1: promotion の還元率はカードの常設実効率より施策側を優先 ----

    @Test
    fun `promotionでは施策の率がカードの常設実効率を上書きする`() {
        // カード常設 10% のカードに 15% の期間限定施策 → 15% が出る(逆だと常設が期間限定を上書きする潜在バグ)
        val campaign = campaignWithPeriod(
            type = CampaignType.PROMOTION,
            rateBase = 15.0,
            start = "2026-06-01",
            end = "2026-07-31",
        )
        val engine = periodTestEngine(campaign)
        assertEquals(15.0, engine.judgeCards(testMerchant, today).single().effectiveRate!!, 0.001)
    }

    @Test
    fun `card_programでは従来どおりカードの実効率を優先する`() {
        val campaign = campaignWithPeriod(rateBase = 7.0) // カードは 10.0
        val engine = periodTestEngine(campaign)
        assertEquals(10.0, engine.judgeCards(testMerchant, today).single().effectiveRate!!, 0.001)
    }

    @Test
    fun `promotionに率が無ければカードの実効率にフォールバックする`() {
        val campaign = campaignWithPeriod(
            type = CampaignType.PROMOTION,
            benefitType = BenefitType.DISCOUNT,
            rateBase = null,
            discountAmount = 300,
            start = "2026-06-01",
            end = "2026-07-31",
        )
        val engine = periodTestEngine(campaign)
        val judgment = engine.judgeCards(testMerchant, today).single()
        assertEquals(300, judgment.discountAmount)
        assertEquals("300円引き", formatBenefit(judgment.benefitType, judgment.effectiveRate, judgment.discountAmount).toString())
    }

    // ---- B-2: card_brand(ブランド施策) ----

    private val brandCampaign = campaignWithPeriod(
        type = CampaignType.PROMOTION,
        benefitType = BenefitType.DISCOUNT,
        cardId = null,
        cardBrand = "Amex",
        rateBase = 30.0,
        start = "2026-06-01",
        end = "2026-07-31",
    )

    @Test
    fun `card_brand施策は実ブランド一致の所有カードにマッチしバッジはブランド名になる`() {
        val engine = periodTestEngine(
            brandCampaign,
            cards = listOf(
                testCard.copy(brand = "Visa"),
                PaymentCard(id = "amex1", cardName = "Amexカード", brand = "Amex", effectiveRateDefault = 1.0),
            ),
        )
        val judgment = engine.judgeCards(testMerchant, today).single()
        // イシュアー不問の施策なので、バッジは特定カード名でなくブランド名
        assertEquals("Amex", judgment.badgeLabel)
        assertEquals(30.0, judgment.effectiveRate!!, 0.001)
        assertEquals("30% OFF", formatBenefit(judgment.benefitType, judgment.effectiveRate, judgment.discountAmount).toString())
    }

    @Test
    fun `card_brand施策はブランド未選択・不一致のカードにはマッチしない`() {
        val engine = periodTestEngine(
            brandCampaign,
            cards = listOf(testCard.copy(brand = ""), PaymentCard(id = "v1", cardName = "Visaカード", brand = "Visa")),
        )
        assertTrue(engine.judgeCards(testMerchant, today).isEmpty())
    }

    @Test
    fun `card_brand施策に複数カードが一致しても判定は1件`() {
        val engine = periodTestEngine(
            brandCampaign,
            cards = listOf(
                PaymentCard(id = "amex1", cardName = "Amexカード1", brand = "Amex"),
                PaymentCard(id = "amex2", cardName = "Amexカード2", brand = "Amex"),
            ),
        )
        val judgments = engine.judgeCards(testMerchant, today)
        assertEquals(1, judgments.size)
        assertEquals("Amex", judgments.single().badgeLabel)
    }

    // ---- B-3: merchant_rules[].rate_override(店舗別還元率) ----

    @Test
    fun `rate_overrideがその店舗のrate_baseを上書きする`() {
        val otherMerchant = Merchant(id = "m2", name = "テスト店2", reading = "てすとてんつー")
        val campaign = campaignWithPeriod(
            type = CampaignType.PROMOTION,
            rateBase = 10.0,
            start = "2026-06-01",
            end = "2026-07-31",
            merchantRules = listOf(
                MerchantRule(merchantId = "m1", rateOverride = 20.0),
                MerchantRule(merchantId = "m2"),
            ),
        )
        val engine = JudgmentEngine(
            PoikatsuData(
                merchants = listOf(testMerchant, otherMerchant),
                campaigns = listOf(campaign),
                cards = listOf(testCard),
                updatedAt = "2026-06-01",
            ),
        )
        assertEquals(20.0, engine.judgeCards(testMerchant, today).single().effectiveRate!!, 0.001)
        assertEquals(10.0, engine.judgeCards(otherMerchant, today).single().effectiveRate!!, 0.001)
    }

    @Test
    fun `rate_overrideはQR施策でも効く`() {
        val paypay = QrPayment(id = "paypay", name = "PayPay", brandColor = "#FF0033")
        val campaign = campaignWithPeriod(
            type = CampaignType.PROMOTION,
            cardId = null,
            paymentMethodId = "paypay",
            rateBase = 10.0,
            start = "2026-06-01",
            end = "2026-07-31",
            merchantRules = listOf(MerchantRule(merchantId = "m1", rateOverride = 20.0)),
        )
        val engine = periodTestEngine(campaign, cards = emptyList(), qrPayments = listOf(paypay))
        assertEquals(20.0, engine.judgeQr(testMerchant, today, setOf("paypay")).single().effectiveRate!!, 0.001)
    }

    // ---- B-4: may_end_early(早期終了フラグ) ----

    @Test
    fun `may_end_earlyが判定結果に伝わる`() {
        val campaign = campaignWithPeriod(start = "2026-06-01", end = "2026-07-31", mayEndEarly = true)
        val engine = periodTestEngine(campaign)
        assertTrue(engine.judgeCards(testMerchant, today).single().mayEndEarly)
        assertFalse(periodTestEngine(campaignWithPeriod()).judgeCards(testMerchant, today).single().mayEndEarly)
    }

    // ---- B-5: recurrence(繰り返し日付条件) ----
    // today = 2026-06-28(日)。6/26(金)・7/3(金) が FRI。

    private val weeklyCampaign = campaignWithPeriod(
        start = "2026-06-01",
        end = "2026-07-31",
        recurrence = Recurrence(daysOfWeek = listOf("FRI", "SAT")),
    )

    @Test
    fun `recurrenceの曜日条件は対象日のみ判定に出す`() {
        val engine = periodTestEngine(weeklyCampaign)
        val friday = LocalDate.of(2026, 7, 3)
        assertEquals(1, engine.judgeCards(testMerchant, friday).size)
        val judgment = engine.judgeCards(testMerchant, friday).single()
        assertTrue(judgment.todayIsTarget)
        assertNull(judgment.nextTargetDate)
        // 日曜(非対象日)は判定に出ない
        assertTrue(engine.judgeCards(testMerchant, today).isEmpty())
    }

    @Test
    fun `recurrenceの日付条件は対象日のみ判定に出す`() {
        val campaign = campaignWithPeriod(
            start = "2026-06-01",
            end = "2026-07-31",
            recurrence = Recurrence(daysOfMonth = listOf(20, 30)),
        )
        val engine = periodTestEngine(campaign)
        assertEquals(1, engine.judgeCards(testMerchant, LocalDate.of(2026, 6, 30)).size)
        assertTrue(engine.judgeCards(testMerchant, LocalDate.of(2026, 6, 28)).isEmpty())
        assertEquals(LocalDate.of(2026, 6, 30), nextTargetDay(campaign, LocalDate.of(2026, 6, 28)))
    }

    @Test
    fun `recurrence施策は期間内なら非対象日でも期間限定タブ用のactiveに残る`() {
        val engine = periodTestEngine(weeklyCampaign)
        // 日曜: campaignStatus は期間の外枠だけで判定(期間限定タブは「次の対象日」を案内する)
        assertEquals(CampaignStatus.ACTIVE, engine.campaignStatus(weeklyCampaign, today))
        assertTrue(engine.activeCampaigns(today).isNotEmpty())
        // 一方、YOLP 検索対象(判定に出る店)からは外れる
        assertTrue(engine.activeManagedMerchantIds(today).isEmpty())
        assertEquals(setOf("m1"), engine.activeManagedMerchantIds(LocalDate.of(2026, 7, 3)))
    }

    @Test
    fun `nextTargetDayは翌日以降の直近対象日を返し期間末を超えない`() {
        assertEquals(LocalDate.of(2026, 7, 3), nextTargetDay(weeklyCampaign, today))
        // 金曜当日の「次」は翌日の土曜
        assertEquals(LocalDate.of(2026, 7, 4), nextTargetDay(weeklyCampaign, LocalDate.of(2026, 7, 3)))
        // 期間内に対象日が残っていなければ null
        val ending = campaignWithPeriod(
            start = "2026-06-01",
            end = "2026-06-30",
            recurrence = Recurrence(daysOfWeek = listOf("FRI")),
        )
        assertNull(nextTargetDay(ending, LocalDate.of(2026, 6, 27)))
        // recurrence が無ければ null
        assertNull(nextTargetDay(campaignWithPeriod(), today))
    }

    @Test
    fun `recurrenceLabelは曜日と日付を人間向けに整形する`() {
        assertEquals("毎週金・土曜", recurrenceLabel(Recurrence(daysOfWeek = listOf("FRI", "SAT"))))
        assertEquals("毎月20日・30日", recurrenceLabel(Recurrence(daysOfMonth = listOf(20, 30))))
    }

    // ---- B-6: lottery(抽選型) ----

    private val lotteryCampaign = campaignWithPeriod(
        benefitType = BenefitType.LOTTERY,
        rateBase = null,
        start = "2026-06-01",
        end = "2026-07-31",
    ).copy(id = "lottery")

    @Test
    fun `lotteryはformatBenefitがnull(比較用ラベルを持たない)`() {
        assertNull(formatBenefit(BenefitType.LOTTERY, 100.0, null))
        assertNull(formatBenefit(BenefitType.LOTTERY, null, 1000))
    }

    @Test
    fun `lotteryは判定に出るが最良特典の比較には載らない`() {
        val rateCampaign = campaignWithPeriod(rateBase = 7.0).copy(id = "rate")
        val engine = JudgmentEngine(
            PoikatsuData(
                merchants = listOf(testMerchant),
                campaigns = listOf(lotteryCampaign, rateCampaign),
                cards = listOf(testCard.copy(effectiveRateDefault = 7.0)),
                updatedAt = "2026-06-01",
            ),
        )
        val result = engine.judgeAll(testMerchant, today)
        assertEquals(2, result.judgments.size)
        assertEquals("rate", result.judgments.first { it.effectiveRate != null }.campaign.id)
        assertEquals(7.0, result.bestOption!!.rate!!, 0.001)
        assertEquals("7% 還元", result.bestBenefitLabel().toString())
    }

    @Test
    fun `lotteryのみのチェーンは判定は出るが最良特典はnull`() {
        val engine = periodTestEngine(lotteryCampaign)
        val result = engine.judgeAll(testMerchant, today)
        assertEquals(1, result.judgments.size)
        assertEquals(BenefitType.LOTTERY, result.judgments.single().benefitType)
        assertNull(result.judgments.single().effectiveRate)
        assertNull(result.bestOption)
        assertNull(result.bestBenefitLabel())
    }
}

/**
 * リポジトリ直下 data/ の実データを読み込み、パース成功・構造整合性・
 * 施策固有の振る舞いを検証する。ロジック自体の網羅は JudgmentEngineTest で行う。
 */
class JudgmentEngineRealDataTest {

    private val campaignsRaw = File("../data/campaigns.json").readText()
    private val data = PoikatsuJson.parse(
        merchantsJson = File("../data/merchants.json").readText(),
        campaignsJson = campaignsRaw,
        paymentMethodsJson = File("../data/payment_methods.json").readText(),
    )
    private val engine = JudgmentEngine(data)
    private val today = LocalDate.of(2026, 6, 28)

    @Test
    fun `実データ_merchant_rulesの参照切れがない`() {
        val ids = data.merchants.map { it.id }.toSet()
        val broken = data.campaigns.flatMap { c -> c.merchantRules.map { c.id to it.merchantId } }
            .filter { (_, mid) -> mid !in ids }
        assertEquals(emptyList<Pair<String, String>>(), broken)
    }

    @Test
    fun `実データ_card_idの参照切れがない`() {
        val cardIds = data.cards.map { it.id }.toSet()
        val broken = data.campaigns.filter { it.cardId != null && it.cardId !in cardIds }.map { it.id }
        assertEquals(emptyList<String>(), broken)
    }

    @Test
    fun `実データ_payment_method_idの参照切れがない`() {
        val qrIds = data.qrPayments.map { it.id }.toSet()
        val broken = data.campaigns.filter { it.paymentMethodId != null && it.paymentMethodId !in qrIds }.map { it.id }
        assertEquals(emptyList<String>(), broken)
    }

    @Test
    fun `実データ_施策の帰属はcard_id_card_brand_payment_method_idのちょうど1つ`() {
        data.campaigns.forEach { c ->
            val owners = listOfNotNull(c.cardId, c.cardBrand, c.paymentMethodId)
            assertEquals(
                "${c.id}: card_id(${c.cardId}) / card_brand(${c.cardBrand}) / payment_method_id(${c.paymentMethodId}) はちょうど1つが non-null",
                1,
                owners.size,
            )
        }
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

    // ---- 実データの新フィールド検証 ----

    @Test
    fun `実データ_各施策のtype_benefitType_storeScopeが有効な値`() {
        val validTypes = CampaignType.entries.map { it.jsonValue }.toSet()
        val validBenefitTypes = BenefitType.entries.map { it.jsonValue }.toSet()
        val validScopes = setOf("managed", "external")
        data.campaigns.forEach { c ->
            assertTrue("${c.id}: invalid type '${c.type}'", c.type in validTypes)
            assertTrue("${c.id}: invalid benefitType '${c.benefitType}'", c.benefitType in validBenefitTypes)
            assertTrue("${c.id}: invalid storeScope '${c.storeScope}'", c.storeScope in validScopes)
        }
    }

    @Test
    fun `実データ_rate_baseとdiscount_amountはちょうど一方がnon-null`() {
        data.campaigns.forEach { c ->
            val hasRate = c.rateBase != null
            val hasDiscount = c.discountAmount != null
            // 抽選は確定特典ではないため率・額を持たない(当選確率・最大額は memo の文章)
            if (BenefitType.fromString(c.benefitType) == BenefitType.LOTTERY) {
                assertTrue("${c.id}: lottery は rate_base / discount_amount を持たない", !hasRate && !hasDiscount)
            } else {
                assertTrue(
                    "${c.id}: rate_base(${c.rateBase}) と discount_amount(${c.discountAmount}) はちょうど一方が non-null",
                    hasRate xor hasDiscount,
                )
            }
        }
    }

    @Test
    fun `実データ_recurrenceはdays_of_weekかdays_of_monthのどちらか一方`() {
        val validDays = setOf("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN")
        data.campaigns.forEach { c ->
            val r = c.recurrence ?: return@forEach
            assertTrue(
                "${c.id}: days_of_week と days_of_month はどちらか一方だけ指定する",
                r.daysOfWeek.isEmpty() xor r.daysOfMonth.isEmpty(),
            )
            r.daysOfWeek.forEach { d -> assertTrue("${c.id}: invalid day_of_week '$d'", d in validDays) }
            r.daysOfMonth.forEach { d -> assertTrue("${c.id}: invalid day_of_month $d", d in 1..31) }
        }
    }

    @Test
    fun `実データ_walletsの値が既知でeligibleとineligibleが重複しない`() {
        val known = setOf("apple_pay", "google_pay")
        data.campaigns.forEach { c ->
            (c.eligibleWallets + c.ineligibleWallets).forEach { w ->
                assertTrue("${c.id}: unknown wallet '$w'", w in known)
            }
            val overlap = c.eligibleWallets.intersect(c.ineligibleWallets.toSet())
            assertTrue("${c.id}: eligible/ineligible が重複 $overlap", overlap.isEmpty())
        }
    }

    @Test
    fun `実データ_旧スキーマのキーが残っていない`() {
        // #41 で note/exclusion_note → eligible_notes/ineligible_notes、conditions → memo に改名した。
        // ignoreUnknownKeys のため旧キーはパース時に黙って捨てられる(静かに壊れる)ので生テキストで検出する
        listOf("\"note\":", "\"exclusion_note\":", "\"conditions\":").forEach { key ->
            assertTrue("旧スキーマのキー $key が残っている", key !in campaignsRaw)
        }
    }

    @Test
    fun `実データ_payment_instructionが空でない`() {
        // 支払い手段は必ず明示する(同名ブランドで対象決済手段が別物になり得る: au PAY(QR) と au PAY カード)
        data.campaigns.forEach { c ->
            assertTrue("${c.id}: payment_instruction が空", c.paymentInstruction.isNotBlank())
        }
    }

    @Test
    fun `実データ_notesとmemoの線引きが守られている`() {
        // 線引き: 見落とすと損する言い切りは eligible/ineligible_notes(表示)、memo は非表示の補足のみ。
        // 「反映済み」注記(事実の本体が別フィールドにある印)だけは memo に対象外文言を書いてよい
        data.campaigns.forEach { c ->
            (c.eligibleNotes + c.ineligibleNotes + c.memo).forEach { n ->
                assertTrue("${c.id}: 空白の note がある", n.isNotBlank())
            }
            c.merchantRules.forEach { r ->
                (r.eligibleNotes + r.ineligibleNotes).forEach { n ->
                    assertTrue("${c.id}/${r.merchantId}: 空白の note がある", n.isNotBlank())
                }
            }
            c.memo.forEach { m ->
                if ("反映済み" in m) return@forEach
                assertTrue(
                    "${c.id}: memo に対象外/のみ対象の言い切りが残っている(表示フィールドへ移す): $m",
                    "対象外" !in m && "のみ対象" !in m,
                )
            }
        }
    }

    @Test
    fun `実データ_三井住友はウォレット起動リンク_MUFGはGoogle Pay警告`() {
        val merchant = data.merchants.first { it.id == "seven_eleven" }
        val judgments = engine.judgeCards(merchant, LocalDate.of(2026, 7, 8))
        val smcc = judgments.first { it.campaign.id == "smcc_combini_restaurant" }
        assertEquals(listOf(AppLink(WALLET_APP_PACKAGE, WALLET_APP_LABEL)), smcc.appLinks)
        val mufg = judgments.first { it.campaign.id == "mufg_point_up_program" }
        assertTrue(mufg.appLinks.isEmpty())
        // MUFG は apple_pay が eligible なので「Apple Payは対象」の付記まで出る
        assertTrue(mufg.warnings.any { it.contains("Google Pay") && it.contains("Apple Payは対象") })
    }

    @Test
    fun `実データ_常設施策はcard_program_managed`() {
        data.campaigns.filter { it.campaignType == CampaignType.CARD_PROGRAM }.forEach { c ->
            assertTrue("${c.id}: card_program should be managed", c.storeScope == "managed")
            assertNull("${c.id}: card_program should not have period_end", c.periodEnd)
        }
    }

    @Test
    fun `実データ_自治体施策はmunicipal_external`() {
        val municipal = data.campaigns.filter { it.campaignType == CampaignType.MUNICIPAL }
        assertTrue("自治体施策が1件以上存在する", municipal.isNotEmpty())
        municipal.forEach { c ->
            assertTrue("${c.id}: municipal should be external", c.storeScope == "external")
            assertNotNull("${c.id}: municipal should have region", c.region)
            assertNotNull("${c.id}: municipal should have period_start", c.periodStart)
            // 終了日は明示されるか、未定なら早期終了型(予算上限到達で終了=かなトク等)であること
            assertTrue(
                "${c.id}: municipal should have period_end or be may_end_early",
                c.periodEnd != null || c.mayEndEarly,
            )
            assertNotNull("${c.id}: municipal should have payment_method_id", c.paymentMethodId)
            assertTrue("${c.id}: municipal merchant_rules should be empty", c.merchantRules.isEmpty())
        }
    }

    @Test
    fun `実データ_rate_rulesがある施策はrate_baseがその最大値`() {
        // 段階制(中小20%/大手10%等)の登録規則: 全条件を rate_rules に列挙し、
        // rate_base にはその最大値を入れる(表示は「最大○%」)。AI 収集時の登録ゆれをここで検出する
        data.campaigns.filter { it.rateRules.isNotEmpty() }.forEach { c ->
            c.rateRules.forEach { r ->
                assertTrue("${c.id}: rate_rules の condition が空", r.condition.isNotBlank())
                assertTrue("${c.id}: rate_rules の rate($r) は正の値", r.rate > 0)
            }
            assertEquals(
                "${c.id}: rate_base(${c.rateBase}) は rate_rules の最大値であること",
                c.rateRules.maxOf { it.rate },
                c.rateBase,
            )
        }
    }

    @Test
    fun `実データ_min_purchase_scopeとproduct_scopeが整合している`() {
        val validScopes = setOf(MIN_PURCHASE_SCOPE_TRANSACTION, MIN_PURCHASE_SCOPE_PERIOD_TOTAL)
        data.campaigns.forEach { c ->
            assertTrue(
                "${c.id}: invalid min_purchase_scope '${c.minPurchaseScope}'",
                c.minPurchaseScope in validScopes,
            )
            if (c.minPurchaseScope != MIN_PURCHASE_SCOPE_TRANSACTION) {
                assertNotNull("${c.id}: min_purchase_scope を指定するなら min_purchase が必要", c.minPurchase)
            }
            c.productScope?.let {
                assertTrue("${c.id}: product_scope の label が空", it.label.isNotBlank())
            }
        }
    }

    @Test
    fun `実データ_display_nameは空白でなく自治体施策には持たせない`() {
        data.campaigns.forEach { c ->
            c.displayName?.let { dn ->
                assertTrue("${c.id}: display_name が空文字・空白", dn.isNotBlank())
                // 自治体は region タイトル固定で display_name を参照しない(登録しても表示されない)
                assertTrue(
                    "${c.id}: municipal は display_name を持たせない",
                    c.campaignType != CampaignType.MUNICIPAL,
                )
            }
        }
    }

    @Test
    fun `実データ_同一自治体の複数決済手段がマージ可能`() {
        val municipal = data.campaigns.filter { it.campaignType == CampaignType.MUNICIPAL }
        val grouped = municipal.groupBy { it.region?.name }
        val multiProvider = grouped.filter { it.value.size > 1 }
        assertTrue("複数決済手段の自治体施策が存在する", multiProvider.isNotEmpty())
        multiProvider.forEach { (name, campaigns) ->
            val providers = campaigns.map { it.paymentMethodId }.distinct()
            assertEquals("$name: 各レコードは異なる決済手段", campaigns.size, providers.size)
        }
    }

    @Test
    fun `実データ_promotionは期間とmerchant_rulesを持つ`() {
        val promotions = data.campaigns.filter { it.campaignType == CampaignType.PROMOTION }
        assertTrue("promotion が1件以上存在する", promotions.isNotEmpty())
        promotions.forEach { c ->
            assertTrue("${c.id}: promotion should be managed", c.storeScope == "managed")
            assertNotNull("${c.id}: promotion should have period_start", c.periodStart)
            assertNotNull("${c.id}: promotion should have period_end", c.periodEnd)
            assertTrue("${c.id}: promotion should have merchant_rules", c.merchantRules.isNotEmpty())
        }
    }

    @Test
    fun `実データ_楽天ペイ松屋プロモーションのQR判定`() {
        val matsuya = data.merchants.first { it.id == "matsuya" }
        val julyToday = LocalDate.of(2026, 7, 10)
        val results = engine.judgeQr(matsuya, julyToday, setOf("rakuten_pay"))
        assertTrue("松屋で楽天ペイの判定が出る", results.any { it.campaign.paymentMethodId == "rakuten_pay" })
        val qr = results.first { it.campaign.paymentMethodId == "rakuten_pay" }
        assertEquals(15.0, qr.effectiveRate!!, 0.001)
        assertEquals(800, qr.minPurchase)
    }

    @Test
    fun `実データ_期間限定タブ用_6月30日にactiveとupcomingが存在する`() {
        val june30 = LocalDate.of(2026, 6, 30)
        val active = engine.activeCampaigns(june30).filter { it.campaignType != CampaignType.CARD_PROGRAM }
        val upcoming = engine.upcomingCampaigns(june30).filter { it.campaignType != CampaignType.CARD_PROGRAM }
        assertTrue("6/30にactiveまたはupcomingが存在する", active.isNotEmpty() || upcoming.isNotEmpty())
    }

    @Test
    fun `実データ_期間限定タブ用_7月1日にactive campaignsが存在する`() {
        val july1 = LocalDate.of(2026, 7, 1)
        val timeLimited = engine.activeCampaigns(july1).filter { it.campaignType != CampaignType.CARD_PROGRAM }
        assertTrue("time-limited active not empty on 7/1: ${timeLimited.map { it.id }}", timeLimited.isNotEmpty())
    }

    @Test
    fun `実データ_QRなしカタログでもupcomingCampaignsは動く`() {
        val noQrEngine = JudgmentEngine(data.copy(qrPayments = emptyList()))
        val june30 = LocalDate.of(2026, 6, 30)
        val upcoming = noQrEngine.upcomingCampaigns(june30).filter { it.campaignType != CampaignType.CARD_PROGRAM }
        assertTrue("upcoming should work without QR payments: ${upcoming.map { it.id }}", upcoming.isNotEmpty())
    }

    @Test
    fun `実データ_カードブランドカタログが読み込めていて施策の参照先がある`() {
        assertTrue("card_brands が空", data.cardBrands.isNotEmpty())
        data.campaigns.mapNotNull { it.cardBrand }.forEach { brand ->
            assertTrue(
                "card_brand '$brand' がカタログの card_brands に無い(設定画面で登録できない)",
                data.cardBrands.any { it.name.equals(brand, ignoreCase = true) },
            )
        }
    }

    @Test
    fun `実データ_merchant_rulesのineligible_brandsがカタログのcard_brandsを参照している`() {
        data.campaigns.forEach { c ->
            c.merchantRules.flatMap { it.ineligibleBrands }.forEach { brand ->
                assertTrue(
                    "${c.id}: ineligible_brands '$brand' がカタログの card_brands に無い(typo だと除外が効かない)",
                    data.cardBrands.any { it.name.equals(brand, ignoreCase = true) },
                )
            }
        }
    }

    @Test
    fun `実データ_QR決済カタログが読み込めている`() {
        val qr = data.qrPayments
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
        assertEquals(3, config!!.gcGroups.size)
        assertEquals("0123,0115,0101013", config.gcGroups[0].gc)
        assertEquals("0205", config.gcGroups[1].gc)
        assertEquals("0202001", config.gcGroups[2].gc)
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

// 自治体マスタ・登録エリア・地域フィルタのテストは RegionFilterTest.kt を参照

/**
 * data-test/ のショーケースデータの整合性テスト。
 * 実データ(JudgmentEngineRealDataTest)と同じ検証を通し、スキーマ変更で腐るのを CI で防ぐ。
 */
class TestDataIntegrityTest {

    private val campaignsRaw = File("../data-test/campaigns.json").readText()
    private val data = PoikatsuJson.parse(
        merchantsJson = File("../data-test/merchants.json").readText(),
        campaignsJson = campaignsRaw,
        paymentMethodsJson = File("../data-test/payment_methods.json").readText(),
    )

    @Test
    fun `テストデータ_パースに成功する`() {
        assertTrue("merchants が空", data.merchants.isNotEmpty())
        assertTrue("campaigns が空", data.campaigns.isNotEmpty())
        assertTrue("cards が空", data.cards.isNotEmpty())
        assertTrue("card_brands が空", data.cardBrands.isNotEmpty())
    }

    @Test
    fun `テストデータ_merchant_rulesの参照切れがない`() {
        val ids = data.merchants.map { it.id }.toSet()
        val broken = data.campaigns.flatMap { c -> c.merchantRules.map { c.id to it.merchantId } }
            .filter { (_, mid) -> mid !in ids }
        assertEquals(emptyList<Pair<String, String>>(), broken)
    }

    @Test
    fun `テストデータ_施策の帰属の参照と排他が正しい`() {
        val cardIds = data.cards.map { it.id }.toSet()
        val qrIds = data.qrPayments.map { it.id }.toSet()
        data.campaigns.forEach { c ->
            val owners = listOfNotNull(c.cardId, c.cardBrand, c.paymentMethodId)
            assertEquals(
                "${c.id}: card_id(${c.cardId}) / card_brand(${c.cardBrand}) / payment_method_id(${c.paymentMethodId}) はちょうど1つが non-null",
                1,
                owners.size,
            )
            c.cardId?.let { assertTrue("${c.id}: card_id '$it' が cards に無い", it in cardIds) }
            c.cardBrand?.let { brand ->
                assertTrue(
                    "${c.id}: card_brand '$brand' がカタログの card_brands に無い(設定画面で登録できない)",
                    data.cardBrands.any { it.name.equals(brand, ignoreCase = true) },
                )
            }
            c.paymentMethodId?.let { assertTrue("${c.id}: payment_method_id '$it' が qr_payments に無い", it in qrIds) }
        }
    }

    @Test
    fun `テストデータ_merchant_rulesのineligible_brandsがカタログのcard_brandsを参照している`() {
        data.campaigns.forEach { c ->
            c.merchantRules.flatMap { it.ineligibleBrands }.forEach { brand ->
                assertTrue(
                    "${c.id}: ineligible_brands '$brand' がカタログの card_brands に無い(typo だと除外が効かない)",
                    data.cardBrands.any { it.name.equals(brand, ignoreCase = true) },
                )
            }
        }
    }

    @Test
    fun `テストデータ_rate_baseとdiscount_amountはちょうど一方がnon-null`() {
        data.campaigns.forEach { c ->
            val hasRate = c.rateBase != null
            val hasDiscount = c.discountAmount != null
            if (BenefitType.fromString(c.benefitType) == BenefitType.LOTTERY) {
                assertTrue("${c.id}: lottery は rate_base / discount_amount を持たない", !hasRate && !hasDiscount)
            } else {
                assertTrue(
                    "${c.id}: rate_base(${c.rateBase}) と discount_amount(${c.discountAmount}) はちょうど一方が non-null",
                    hasRate xor hasDiscount,
                )
            }
        }
    }

    @Test
    fun `テストデータ_recurrenceはdays_of_weekかdays_of_monthのどちらか一方`() {
        val validDays = setOf("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN")
        data.campaigns.forEach { c ->
            val r = c.recurrence ?: return@forEach
            assertTrue(
                "${c.id}: days_of_week と days_of_month はどちらか一方だけ指定する",
                r.daysOfWeek.isEmpty() xor r.daysOfMonth.isEmpty(),
            )
            r.daysOfWeek.forEach { d -> assertTrue("${c.id}: invalid day_of_week '$d'", d in validDays) }
            r.daysOfMonth.forEach { d -> assertTrue("${c.id}: invalid day_of_month $d", d in 1..31) }
        }
    }

    @Test
    fun `テストデータ_walletsの値が既知でeligibleとineligibleが重複しない`() {
        val known = setOf("apple_pay", "google_pay")
        data.campaigns.forEach { c ->
            (c.eligibleWallets + c.ineligibleWallets).forEach { w ->
                assertTrue("${c.id}: unknown wallet '$w'", w in known)
            }
            val overlap = c.eligibleWallets.intersect(c.ineligibleWallets.toSet())
            assertTrue("${c.id}: eligible/ineligible が重複 $overlap", overlap.isEmpty())
        }
    }

    @Test
    fun `テストデータ_旧スキーマのキーが残っていない`() {
        listOf("\"note\":", "\"exclusion_note\":", "\"conditions\":").forEach { key ->
            assertTrue("旧スキーマのキー $key が残っている", key !in campaignsRaw)
        }
    }

    @Test
    fun `テストデータ_payment_instructionが空でない`() {
        data.campaigns.forEach { c ->
            assertTrue("${c.id}: payment_instruction が空", c.paymentInstruction.isNotBlank())
        }
    }

    @Test
    fun `テストデータ_両階層のnotes併用ショーケースを含む`() {
        // campaign 直下(施策全体)と merchant_rules(店舗固有)の対象/対象外がレベル横断で連結される
        // パターン(SMCC/MUFG 相当)を data-test でも検証できること
        val both = data.campaigns.filter { c ->
            (c.eligibleNotes.isNotEmpty() || c.ineligibleNotes.isNotEmpty()) &&
                c.merchantRules.any { it.eligibleNotes.isNotEmpty() || it.ineligibleNotes.isNotEmpty() }
        }
        assertTrue("両階層併用のショーケース施策が存在する", both.isNotEmpty())
        // memo(非表示)のショーケースも維持する
        assertTrue("memo を持つショーケース施策が存在する", data.campaigns.any { it.memo.isNotEmpty() })
    }

    @Test
    fun `テストデータ_rate_rulesの段階制パターンを含み整合している`() {
        val tiered = data.campaigns.filter { it.rateRules.isNotEmpty() }
        assertTrue("段階制(rate_rules)のショーケース施策が存在する", tiered.isNotEmpty())
        tiered.forEach { c ->
            c.rateRules.forEach { r ->
                assertTrue("${c.id}: rate_rules の condition が空", r.condition.isNotBlank())
                assertTrue("${c.id}: rate_rules の rate($r) は正の値", r.rate > 0)
            }
            assertEquals(
                "${c.id}: rate_base(${c.rateBase}) は rate_rules の最大値であること",
                c.rateRules.maxOf { it.rate },
                c.rateBase,
            )
        }
    }

    @Test
    fun `テストデータ_product_scopeのショーケースを含み整合している`() {
        val validScopes = setOf(MIN_PURCHASE_SCOPE_TRANSACTION, MIN_PURCHASE_SCOPE_PERIOD_TOTAL)
        data.campaigns.forEach { c ->
            assertTrue(
                "${c.id}: invalid min_purchase_scope '${c.minPurchaseScope}'",
                c.minPurchaseScope in validScopes,
            )
            if (c.minPurchaseScope != MIN_PURCHASE_SCOPE_TRANSACTION) {
                assertNotNull("${c.id}: min_purchase_scope を指定するなら min_purchase が必要", c.minPurchase)
            }
            c.productScope?.let {
                assertTrue("${c.id}: product_scope の label が空", it.label.isNotBlank())
            }
        }
        // ショーケース(対象商品限定 + 期間累計の最低購入額 + 要エントリー)が揃っていること
        val showcase = data.campaigns.first { it.id == "test_product_scope" }
        assertNotNull("product_scope ショーケースが必要", showcase.productScope)
        assertEquals(MIN_PURCHASE_SCOPE_PERIOD_TOTAL, showcase.minPurchaseScope)
        assertNotNull(showcase.minPurchase)
        assertTrue("requires_entry のショーケースが必要", showcase.requiresEntry)
    }

    @Test
    fun `テストデータ_display_nameのショーケースを含み空白でない`() {
        data.campaigns.forEach { c ->
            c.displayName?.let { dn ->
                assertTrue("${c.id}: display_name が空文字・空白", dn.isNotBlank())
                assertTrue(
                    "${c.id}: municipal は display_name を持たせない",
                    c.campaignType != CampaignType.MUNICIPAL,
                )
            }
        }
        // 多チェーン + display_name のショーケース(カードタイトルの手動略記)が揃っていること
        val showcase = data.campaigns.first { it.id == "test_product_scope" }
        assertNotNull("display_name ショーケースが必要", showcase.displayName)
        assertTrue(
            "display_name ショーケースは多チェーン施策であること",
            showcase.merchantRules.map { it.merchantId }.distinct().size >= 2,
        )
    }

    @Test
    fun `テストデータ_type_benefitType_storeScopeが有効な値`() {
        val validTypes = CampaignType.entries.map { it.jsonValue }.toSet()
        val validBenefitTypes = BenefitType.entries.map { it.jsonValue }.toSet()
        val validScopes = setOf("managed", "external")
        data.campaigns.forEach { c ->
            assertTrue("${c.id}: invalid type '${c.type}'", c.type in validTypes)
            assertTrue("${c.id}: invalid benefitType '${c.benefitType}'", c.benefitType in validBenefitTypes)
            assertTrue("${c.id}: invalid storeScope '${c.storeScope}'", c.storeScope in validScopes)
        }
    }
}

class CampaignFlagsTest {

    @Test
    fun `期間限定は終了日ありまたは早期終了型(終了日未定のかなトク型も含む)`() {
        val base = Campaign(id = "x", operator = "テスト", name = "施策")
        assertFalse("終了日なし・早期終了なし(常設)は期間限定でない", base.isTimeLimited)
        assertTrue(base.copy(periodEnd = "2026-12-31").isTimeLimited)
        assertTrue("終了日未定でも予算到達で終わり得るなら期間限定", base.copy(mayEndEarly = true).isTimeLimited)
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
