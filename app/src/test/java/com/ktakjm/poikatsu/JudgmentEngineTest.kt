package com.ktakjm.poikatsu

import com.ktakjm.poikatsu.data.Campaign
import com.ktakjm.poikatsu.data.ExcludedStorePair
import com.ktakjm.poikatsu.data.Merchant
import com.ktakjm.poikatsu.data.MerchantRule
import com.ktakjm.poikatsu.data.OfficialStoreList
import com.ktakjm.poikatsu.data.PaymentCard
import com.ktakjm.poikatsu.data.StoreScope
import com.ktakjm.poikatsu.data.PointMultiplier
import com.ktakjm.poikatsu.data.PointCurrency
import com.ktakjm.poikatsu.data.PoikatsuData
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
import com.ktakjm.poikatsu.domain.allStoreListsExhaustive
import com.ktakjm.poikatsu.domain.WALLET_APP_PACKAGE
import com.ktakjm.poikatsu.domain.bestBenefitLabel
import com.ktakjm.poikatsu.domain.formatBenefit
import com.ktakjm.poikatsu.domain.isTimeLimited
import com.ktakjm.poikatsu.domain.nextTargetDay
import com.ktakjm.poikatsu.domain.recurrenceLabel
import com.ktakjm.poikatsu.domain.resolveCardCampaignRate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
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
        assertEquals("マクドナルド", engine.search("マック").first().merchant.name)
        assertTrue(engine.search("サイゼ").any { it.merchant.name == "サイゼリヤ" })
        assertTrue(engine.search("KFC").any { it.merchant.id == "kfc" })
    }

    @Test
    fun `ひらがな入力でカタカナ店名にヒットする`() {
        assertTrue(engine.search("ろーそん").any { it.merchant.id == "lawson" })
        assertTrue(engine.search("すたば").any { it.merchant.id == "starbucks" })
    }

    @Test
    fun `ハイフン有無は無視される`() {
        assertTrue(engine.search("セブンイレブン").any { it.merchant.id == "seven_eleven" })
    }

    @Test
    fun `前方一致が部分一致より上に来る`() {
        val results = engine.search("ガスト")
        assertEquals("ガスト", results.first().merchant.name)
        assertTrue(results.any { it.merchant.name == "ステーキガスト" })
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
        assertTrue(results.all { it.merchant.category == "コンビニ" })
        assertTrue(results.any { it.merchant.id == "seven_eleven" })
    }

    @Test
    fun `カテゴリは複数選択できる`() {
        val results = engine.search("", setOf("コンビニ", "カフェ"))
        assertEquals(setOf("コンビニ", "カフェ"), results.map { it.merchant.category }.toSet())
    }

    @Test
    fun `店名とカテゴリの組み合わせで絞り込める`() {
        // 「す」はスシロー(回転寿司)にもヒットするが、カフェに絞ればスタバ系のみ
        val results = engine.search("す", setOf("カフェ"))
        assertTrue(results.all { it.merchant.category == "カフェ" })
        assertTrue(results.any { it.merchant.id == "starbucks" })
    }

    @Test
    fun `カテゴリ未選択かつ店名空なら空リスト`() {
        assertTrue(engine.search("", emptySet()).isEmpty())
    }

    @Test
    fun `検索は施策の有無に関係なくマスタ全体にヒットする`() {
        // 名前検索の 0 件 =「マスタ未収録」と言い切る前提(#70 の施策1)。施策を 1 件も持たない
        // データでも search はヒットする(施策でのフィルタは ViewModel の searchRewarded 側)
        val merchant = Merchant(id = "m1", name = "テスト店", reading = "てすとてん")
        val noCampaigns = JudgmentEngine(
            PoikatsuData(merchants = listOf(merchant), campaigns = emptyList(), cards = emptyList(), updatedAt = "2026-06-01"),
        )
        assertEquals("m1", noCampaigns.search("テスト").single().merchant.id)
    }

    @Test
    fun `カテゴリ一覧がデータから取れる`() {
        assertTrue(engine.categories.containsAll(listOf("コンビニ", "ファストフード", "ファミレス", "カフェ", "回転寿司", "スーパー", "その他")))
    }

    @Test
    fun `具体店舗名のフル入力でもチェーンにヒットする`() {
        assertEquals("マクドナルド", engine.search("マクドナルド渋谷駅前店").first().merchant.name)
        assertTrue(engine.search("くら寿司ららぽーとTOKYO-BAY店").any { it.merchant.id == "kurazushi" })
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
        // 網羅リストだけのチェーンも調べる導線を出す(#70。#64 では「対象店しか表示されない
        // ため不要」としたが、掲載のない店が理由なく消えたように見えるため方針を変更した)
        val (exhaustiveOnly, mExhaustive) = storeCheckEngine(eligible = listOf("浦和店"), exhaustive = true)
        assertTrue(exhaustiveOnly.canCheckStore(mExhaustive))
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

    // ---- 網羅リスト(list_is_exhaustive。#64) ----

    @Test
    fun `網羅リストでは掲載のない店舗を対象外と断定する`() {
        val (eng, m) = storeCheckEngine(eligible = listOf("浦和店", "川口店"), exhaustive = true)
        val verdict = eng.checkStore(m, "テスト店 大宮店").single()
        assertEquals(StoreEligibility.INELIGIBLE, verdict.eligibility)
        assertNull(verdict.matched)
        assertTrue(verdict.listIsExhaustive)
        // 掲載店は従来どおり対象
        assertEquals(StoreEligibility.ELIGIBLE, eng.checkStore(m, "テスト店 浦和店").single().eligibility)
    }

    @Test
    fun `非網羅リストは従来どおり掲載なしを要確認にする`() {
        val (eng, m) = storeCheckEngine(eligible = listOf("浦和店"))
        assertEquals(StoreEligibility.UNKNOWN, eng.checkStore(m, "テスト店 大宮店").single().eligibility)
        assertTrue(eng.exhaustiveListIneligibleCampaignIds(m, "テスト店 大宮店").isEmpty())
    }

    @Test
    fun `網羅リストの掲載なしは店舗ごと除外でなく施策単位で間引く`() {
        val (eng, m) = storeCheckEngine(eligible = listOf("浦和店"), exhaustive = true)
        // ピンごと消す isExcludedStore は発火しない(その店に他の施策があれば残すため)
        assertFalse(eng.isExcludedStore(m, "テスト店 大宮店"))
        // 施策単位の間引き集合には入る
        assertEquals(setOf("c1"), eng.exhaustiveListIneligibleCampaignIds(m, "テスト店 大宮店"))
        assertTrue(eng.exhaustiveListIneligibleCampaignIds(m, "テスト店 浦和店").isEmpty())
    }

    @Test
    fun `網羅リストでも明示的対象外は従来どおり店舗ごと除外にかかる`() {
        val (eng, m) = storeCheckEngine(
            eligible = listOf("浦和店"),
            ineligible = listOf("大宮店"),
            exhaustive = true,
        )
        assertTrue(eng.isExcludedStore(m, "テスト店 大宮店"))
    }

    @Test
    fun `storeIneligibleCampaignIdsの施策は判定から黙って消える`() {
        val (eng, m) = storeCheckEngine(eligible = listOf("浦和店"), exhaustive = true)
        val ineligibleIds = eng.exhaustiveListIneligibleCampaignIds(m, "テスト店 大宮店")
        val result = eng.judgeAll(m, today, storeIneligibleCampaignIds = ineligibleIds)
        assertTrue(result.judgments.isEmpty())
        // ユーザー登録の対象外(#63)と違い解除の概念が無いため、excludedJudgments にも載せない
        assertTrue(result.excludedJudgments.isEmpty())
        // 掲載店では通常どおり判定に出る
        assertEquals(listOf("c1"), eng.judgeAll(m, today).judgments.map { it.campaign.id })
    }

    @Test
    fun `網羅リストの施策は判定カードにexhaustiveStoreListフラグが立つ`() {
        val (eng, m) = storeCheckEngine(eligible = listOf("浦和店"), exhaustive = true)
        assertTrue(eng.judgeAll(m, today).judgments.single().exhaustiveStoreList)
        // 非網羅リスト・リスト無しでは立たない
        val (engNonEx, m2) = storeCheckEngine(eligible = listOf("浦和店"))
        assertFalse(engNonEx.judgeAll(m2, today).judgments.single().exhaustiveStoreList)
        val (engNoList, m3) = storeCheckEngine(hasList = false)
        assertFalse(engNoList.judgeAll(m3, today).judgments.single().exhaustiveStoreList)
    }

    @Test
    fun `施策単位の網羅性は全ルールが網羅リストのときだけtrue`() {
        fun rule(id: String, exhaustive: Boolean?) = MerchantRule(
            merchantId = id,
            officialStoreList = exhaustive?.let {
                OfficialStoreList(eligibleStores = listOf("浦和店"), listIsExhaustive = it, updatedDate = "2026-05-01")
            },
        )
        fun campaignWith(rules: List<MerchantRule>) = Campaign(
            id = "c1", operator = "test", name = "テスト施策", merchantRules = rules,
        )
        // 全ルール網羅 → true(コジマ×ビックカメラ型)
        assertTrue(campaignWith(listOf(rule("m1", true), rule("m2", true))).allStoreListsExhaustive)
        // 一部ルールだけ網羅 → false(J-POINTパートナー型。施策単位でバッジを付けない)
        assertFalse(campaignWith(listOf(rule("m1", true), rule("m2", false))).allStoreListsExhaustive)
        assertFalse(campaignWith(listOf(rule("m1", true), rule("m2", null))).allStoreListsExhaustive)
        // ルール無し(自治体施策等) → false
        assertFalse(campaignWith(emptyList()).allStoreListsExhaustive)
    }

    // ---- ユーザー登録の対象外ペア(#63) ----

    @Test
    fun `対象外ペアはその施策だけ判定から間引かれ別枠で返る`() {
        val seven = data.merchants.first { it.id == "seven_eleven" }
        val pairs = listOf(
            ExcludedStorePair("smcc_combini_restaurant", "seven_eleven", "セブン-イレブン 与野本町駅前店"),
        )
        val excludedIds = engine.excludedCampaignIdsFor(seven, "セブン-イレブン与野本町駅前店", pairs)
        assertEquals(setOf("smcc_combini_restaurant"), excludedIds)
        val result = engine.judgeAll(seven, today, excludedCampaignIds = excludedIds)
        // 該当施策は judgments から消え、excludedJudgments に分けて返る(判定詳細の畳み表示用)
        assertTrue(result.judgments.none { it.campaign.id == "smcc_combini_restaurant" })
        assertEquals(listOf("smcc_combini_restaurant"), result.excludedJudgments.map { it.campaign.id })
        // 他の施策は残り、bestOption も残った施策から選び直される
        assertTrue(result.judgments.any { it.campaign.id == "mufg_point_up_program" })
        assertEquals("MUFGカード", result.bestOption?.method)
    }

    @Test
    fun `支店名が違う店は間引かれない`() {
        val seven = data.merchants.first { it.id == "seven_eleven" }
        val pairs = listOf(
            ExcludedStorePair("smcc_combini_restaurant", "seven_eleven", "セブン-イレブン 与野本町駅前店"),
        )
        assertTrue(engine.excludedCampaignIdsFor(seven, "セブン-イレブン 大宮駅前店", pairs).isEmpty())
    }

    @Test
    fun `保存した店舗名とPOI名の表記ゆれは支店名の正規化で一致する`() {
        val kfc = data.merchants.first { it.id == "kfc" }
        // 別名(KFC)+空白違いで保存されていても、同じ支店なら一致する
        val pairs = listOf(ExcludedStorePair("smcc_combini_restaurant", "kfc", "KFC 与野店"))
        assertEquals(
            setOf("smcc_combini_restaurant"),
            engine.excludedCampaignIdsFor(kfc, "ケンタッキーフライドチキン与野店", pairs),
        )
    }

    @Test
    fun `別チェーンの登録は影響しない`() {
        val steakGusto = data.merchants.first { it.id == "steak_gusto" }
        val pairs = listOf(ExcludedStorePair("smcc_combini_restaurant", "gusto", "ガスト与野店"))
        assertTrue(engine.excludedCampaignIdsFor(steakGusto, "ステーキガスト与野店", pairs).isEmpty())
    }

    @Test
    fun `登録ペアが無ければ判定は変わらない`() {
        val seven = data.merchants.first { it.id == "seven_eleven" }
        val result = engine.judgeAll(seven, today)
        assertTrue(result.excludedJudgments.isEmpty())
        assertEquals(2, result.judgments.size)
    }

    /** official_store_list を組んだ最小データで JudgmentEngine を作る。hasList=false で公式リスト無し */
    private fun storeCheckEngine(
        eligible: List<String> = emptyList(),
        ineligible: List<String> = emptyList(),
        hasList: Boolean = true,
        exhaustive: Boolean = false,
    ): Pair<JudgmentEngine, Merchant> {
        val merchant = Merchant(id = "m1", name = "テスト店", reading = "てすとてん")
        val campaign = Campaign(
            id = "c1",
            operator = "test",
            cardId = "test_card",
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
                        listIsExhaustive = exhaustive,
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
            cards = listOf(PaymentCard(id = "test_card", cardName = "テストカード", effectiveRateDefault = 5.0)),
            updatedAt = "2026-06-01",
        )
        return JudgmentEngine(data) to merchant
    }

    // ---- 検索 0 件時の matchStore フォールバック(#77 施策6) ----

    private val engineWithMatsuya = JudgmentEngine(
        data.copy(merchants = data.merchants + Merchant(id = "matsuya", name = "松屋", reading = "まつや", category = "ファストフード")),
    )

    @Test
    fun `検索が0件でも matchStore で系列を特定できれば案内用ヒットを返す`() {
        // 漢字 2 文字キーは search の 3 文字制限で具体店舗名入力(「松屋渋谷店」)に当たらないが、
        // matchStore(地図 POI 照合)は漢字 2 文字キーを許可しているため拾える
        assertTrue(engineWithMatsuya.search("松屋渋谷店").isEmpty())
        val hit = engineWithMatsuya.searchFallback("松屋渋谷店")
        assertEquals("matsuya", hit?.merchant?.id)
        // 代表看板への一致は search と同じくグループとしてのヒット(bannerId なし)
        assertNull(hit?.bannerId)
    }

    @Test
    fun `フォールバックは無関係な入力には当たらない`() {
        assertNull(engineWithMatsuya.searchFallback("だるま食堂"))
        assertNull(engineWithMatsuya.searchFallback(""))
    }

    @Test
    fun `フォールバックもカテゴリ絞り込みを守る`() {
        assertNull(engineWithMatsuya.searchFallback("松屋渋谷店", setOf("コンビニ")))
        assertEquals("matsuya", engineWithMatsuya.searchFallback("松屋渋谷店", setOf("ファストフード"))?.merchant?.id)
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
        storeScope: StoreScope = StoreScope.MANAGED,
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
        storeScopeRaw = storeScope.jsonValue,
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
        pointCurrencies: List<PointCurrency> = emptyList(),
    ): JudgmentEngine =
        JudgmentEngine(
            PoikatsuData(
                merchants = listOf(testMerchant),
                campaigns = listOf(campaign),
                cards = cards,
                qrPayments = qrPayments,
                pointCurrencies = pointCurrencies,
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
        val campaign = campaignWithPeriod(storeScope = StoreScope.EXTERNAL, type = CampaignType.MUNICIPAL)
        val engine = periodTestEngine(campaign)
        assertTrue(engine.judgeCards(testMerchant, today).isEmpty())
    }

    @Test
    fun `store_scope_managed は judge に含まれる`() {
        val campaign = campaignWithPeriod(storeScope = StoreScope.MANAGED)
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
        val welcatsuCard = testCard.copy(pointCurrencyId = "vp")
        val engine = JudgmentEngine(
            PoikatsuData(
                merchants = listOf(testMerchant),
                campaigns = listOf(discount300, discount500),
                cards = listOf(
                    welcatsuCard,
                    PaymentCard(id = "low_rate_card", cardName = "低率カード", effectiveRateDefault = 1.0),
                ),
                pointCurrencies = listOf(vpointLike),
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

    // ---- 損益分岐額(#13 rebate vs coupon の損益分岐)のテスト ----

    @Test
    fun `judgeAll_定率と定額が同居するとき定額側にだけ損益分岐額が付く`() {
        val paypay = QrPayment(id = "paypay", name = "PayPay", brandColor = "#FF0033")
        val fixedCoupon = campaignWithPeriod(
            type = CampaignType.PROMOTION,
            benefitType = BenefitType.DISCOUNT,
            cardId = null,
            paymentMethodId = "paypay",
            rateBase = null,
            discountAmount = 100,
        ).copy(id = "fixed")
        val rateCampaign = campaignWithPeriod(rateBase = 7.0).copy(id = "rate")
        val engine = JudgmentEngine(
            PoikatsuData(
                merchants = listOf(testMerchant),
                campaigns = listOf(fixedCoupon, rateCampaign),
                cards = listOf(testCard.copy(effectiveRateDefault = 7.0)),
                qrPayments = listOf(paypay),
                updatedAt = "2026-06-01",
            ),
        )
        val result = engine.judgeAll(testMerchant, today, setOf("paypay"))
        val fixedJudgment = result.judgments.first { it.campaign.id == "fixed" }
        val rateJudgment = result.judgments.first { it.campaign.id == "rate" }
        assertEquals(1430, fixedJudgment.breakevenAmount) // 100÷0.07=1428.6→1430
        assertNull(rateJudgment.breakevenAmount)
        // バナー2行目のアドバイス(#13 実機フィードバック)も定額判定から組み立てられる
        val advice = result.fixedAdvice!!
        assertEquals("PayPay", advice.method)
        assertEquals(100, advice.discountAmount)
        assertEquals(1430, advice.breakevenAmount)
        assertNull(advice.minPurchase)
    }

    @Test
    fun `judgeAll_定率施策が無いチェーンでは定額判定の損益分岐額はnullのまま`() {
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
        assertNull(result.judgments.single().breakevenAmount)
        assertNull(result.fixedAdvice)
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

    // ---- presentation_only(カード現物提示型の優待。#80)のテスト ----

    @Test
    fun `bestOptionは提示のみ施策を除外する`() {
        val unconditional = campaignWithPeriod(rateBase = 7.0).copy(id = "base")
        val presentation = campaignWithPeriod(
            benefitType = BenefitType.DISCOUNT,
            rateBase = 10.0,
        ).copy(id = "teiji10", presentationOnly = true)
        val engine = JudgmentEngine(
            PoikatsuData(
                merchants = listOf(testMerchant),
                campaigns = listOf(unconditional, presentation),
                cards = listOf(testCard.copy(effectiveRateDefault = 7.0)),
                updatedAt = "2026-06-01",
            ),
        )
        val result = engine.judgeAll(testMerchant, today)
        // 提示のみ施策は支払い方法の選択肢ではないため、判定リストから「あわせて提示」の並記枠
        // (presentationJudgments)へ分離する(#39 で様式統一)。「最良」は決済で受けられる7%。
        // 提示のみ10% OFFを最良にすると「このカードで払え」に読め、実際の最適解
        // (提示しつつ別の高還元手段で払う)と矛盾する
        assertEquals(listOf("base"), result.judgments.map { it.campaign.id })
        assertEquals(listOf("teiji10"), result.presentationJudgments.map { it.campaign.id })
        assertEquals(7.0, result.bestOption!!.rate!!, 0.001)
        assertEquals("7% 還元", result.bestBenefitLabel().toString())
    }

    @Test
    fun `bestBenefitLabel_提示のみ施策しか無いチェーンは提示のみの付記つきラベル`() {
        val presentation = campaignWithPeriod(
            benefitType = BenefitType.DISCOUNT,
            rateBase = 10.0,
        ).copy(presentationOnly = true)
        val engine = periodTestEngine(presentation)
        val result = engine.judgeAll(testMerchant, today)
        assertNull(result.bestOption)
        assertTrue(result.judgments.isEmpty())
        assertEquals("10% OFF(提示のみ)", result.bestBenefitLabel().toString())
    }

    @Test
    fun `judgeAll_提示のみの定額施策は最良比較の対象外のため損益分岐額を持たない`() {
        // 定率施策が無く提示のみの定額(500円引き)だけのチェーン。提示のみは支払いの選択肢で
        // ないため損益分岐の対象にしてはならない
        // (breakevenAmount は judgeAll が active にしか付与しないため presentation 側は常に null)
        val presentation = campaignWithPeriod(
            benefitType = BenefitType.DISCOUNT,
            rateBase = null,
            discountAmount = 500,
        ).copy(presentationOnly = true)
        val engine = periodTestEngine(presentation)
        val result = engine.judgeAll(testMerchant, today)
        assertNull(result.bestOption)
        assertTrue(result.judgments.isEmpty())
        assertNull(result.presentationJudgments.single().breakevenAmount)
    }

    @Test
    fun `resolveCardCampaignRate_提示のみのcard_programは施策側の率を使う`() {
        // 常設 card_program はカードの通常還元率を採るのが既定だが、提示のみ施策で
        // それをやると「エポスの通常還元0.5%」が出て提示特典10%OFFが消える
        val presentation = campaignWithPeriod(
            benefitType = BenefitType.DISCOUNT,
            rateBase = 10.0,
        ).copy(presentationOnly = true)
        val resolved = resolveCardCampaignRate(presentation, testCard.copy(effectiveRateDefault = 0.5))
        assertEquals(10.0, resolved.effectiveRate!!, 0.001)
        assertFalse(resolved.usesCardRate)
    }

    // ---- ポイント通貨マスタ(point_currencies。#39)のテスト ----

    private val testMultiplier = PointMultiplier(
        label = "ウエル活利用時の還元率を表示",
        factor = 1.5,
        badgeLabel = "ウエル活利用可",
        appliedNote = "還元率はウエル活利用時の実質還元率",
    )
    private val vpointLike = PointCurrency(
        id = "vp",
        name = "テストVポイント",
        pointMultiplier = testMultiplier,
        multiplierEnabled = true,
    )

    @Test
    fun `promotionの率にも払い出し通貨の倍率が掛かる`() {
        // #35 B-1「promotion の率にはウエル活を掛けない」の原理的置き換え(#39):
        // 払い出し通貨が分かるなら掛けるのが正しい(Vポイント払いの15%はウエル活で実質22.5%)
        val promo = campaignWithPeriod(type = CampaignType.PROMOTION, rateBase = 15.0)
        val engine = periodTestEngine(
            promo,
            cards = listOf(testCard.copy(pointCurrencyId = "vp")),
            pointCurrencies = listOf(vpointLike),
        )
        val judgment = engine.judgeAll(testMerchant, today).judgments.single()
        assertEquals(22.5, judgment.effectiveRate!!, 1e-9)
        assertTrue(judgment.welcatsuApplied)
        assertEquals("ウエル活利用可", judgment.pointMultiplier?.badgeLabel)
    }

    @Test
    fun `倍率が無効なら施策の率はそのまま(バッジは出る)`() {
        val promo = campaignWithPeriod(type = CampaignType.PROMOTION, rateBase = 15.0)
        val engine = periodTestEngine(
            promo,
            cards = listOf(testCard.copy(pointCurrencyId = "vp")),
            pointCurrencies = listOf(vpointLike.copy(multiplierEnabled = false)),
        )
        val judgment = engine.judgeAll(testMerchant, today).judgments.single()
        assertEquals(15.0, judgment.effectiveRate!!, 1e-9)
        assertFalse(judgment.welcatsuApplied)
        assertNotNull("倍率を持つ通貨で払い出される事実は無効時もバッジで示す", judgment.pointMultiplier)
    }

    @Test
    fun `rebate施策の判定は払い出し通貨名を持つ`() {
        // 「還元: Pontaポイント」行の出所。倍率の有無と独立で、通貨が解決できれば必ず載る
        val promo = campaignWithPeriod(type = CampaignType.PROMOTION, rateBase = 15.0)
        val engine = periodTestEngine(
            promo,
            cards = listOf(testCard.copy(pointCurrencyId = "vp")),
            pointCurrencies = listOf(vpointLike.copy(multiplierEnabled = false)),
        )
        assertEquals("テストVポイント", engine.judgeAll(testMerchant, today).judgments.single().payoutCurrencyName)
    }

    @Test
    fun `通貨が解決できないrebate施策は払い出し通貨名を持たない`() {
        // カタログに point_currency_id が無い発行体(MUFG・エポス等)。誤った通貨名を出すより行を省く
        val promo = campaignWithPeriod(type = CampaignType.PROMOTION, rateBase = 15.0)
        val engine = periodTestEngine(promo, cards = listOf(testCard), pointCurrencies = listOf(vpointLike))
        assertNull(engine.judgeAll(testMerchant, today).judgments.single().payoutCurrencyName)
    }

    @Test
    fun `discount施策の判定は払い出し通貨名を持たない`() {
        // 即時割引にポイント還元先は無い(「還元:」行を出すと誤り)
        val discount = campaignWithPeriod(type = CampaignType.PROMOTION, rateBase = 15.0)
            .copy(benefitType = "discount")
        val engine = periodTestEngine(
            discount,
            cards = listOf(testCard.copy(pointCurrencyId = "vp")),
            pointCurrencies = listOf(vpointLike),
        )
        assertNull(engine.judgeAll(testMerchant, today).judgments.single().payoutCurrencyName)
    }

    @Test
    fun `card_brand施策は明示のpoint_currency_idがあるときだけ倍率が掛かる`() {
        // 継承元が無いため明示必須: resolveCard がブランド一致で返すカードは「支払いに使うカード」で
        // 払い出し元ではない(報酬通貨は施策が決める)
        val visaCard = testCard.copy(brand = "Visa", pointCurrencyId = "vp")
        fun judge(campaign: Campaign) = periodTestEngine(
            campaign,
            cards = listOf(visaCard),
            pointCurrencies = listOf(vpointLike),
        ).judgeAll(testMerchant, today).judgments.single()

        val explicit = campaignWithPeriod(type = CampaignType.PROMOTION, cardId = null, cardBrand = "Visa", rateBase = 15.0)
            .copy(pointCurrencyId = "vp")
        assertEquals(22.5, judge(explicit).effectiveRate!!, 1e-9)

        val implicit = campaignWithPeriod(type = CampaignType.PROMOTION, cardId = null, cardBrand = "Visa", rateBase = 15.0)
        val judgment = judge(implicit)
        assertEquals("カードの通貨は継承しない", 15.0, judgment.effectiveRate!!, 1e-9)
        assertNull(judgment.pointMultiplier)
        assertFalse(judgment.welcatsuApplied)
    }

    @Test
    fun `QR施策の率にも払い出し通貨の倍率が掛かる`() {
        val qr = QrPayment(id = "qr1", name = "テストペイ", brandColor = "#000000", pointCurrencyId = "vp")
        val promo = campaignWithPeriod(type = CampaignType.PROMOTION, cardId = null, paymentMethodId = "qr1", rateBase = 10.0)
        val engine = periodTestEngine(promo, cards = emptyList(), qrPayments = listOf(qr), pointCurrencies = listOf(vpointLike))
        val judgment = engine.judgeAll(testMerchant, today, setOf("qr1")).judgments.single()
        assertEquals(15.0, judgment.effectiveRate!!, 1e-9)
        assertEquals("名目率は円換算前のまま持つ", 10.0, judgment.nominalRate!!, 0.0)
        assertTrue(judgment.welcatsuApplied)
    }

    @Test
    fun `カードの実効率には倍率を二重適用しない`() {
        // マージ後を模す: 実効率は名目 7.0(マージは倍率を掛けない。#13)。倍率はスコア層で1回だけ
        val mergedCard = testCard.copy(effectiveRateDefault = 7.0, pointCurrencyId = "vp")
        val program = campaignWithPeriod(rateBase = 7.0)
        val engine = periodTestEngine(program, cards = listOf(mergedCard), pointCurrencies = listOf(vpointLike))
        val judgment = engine.judgeAll(testMerchant, today).judgments.single()
        assertEquals(10.5, judgment.effectiveRate!!, 1e-9)
        assertEquals("名目率は円換算前のまま持つ", 7.0, judgment.nominalRate!!, 0.0)
        assertTrue(judgment.welcatsuApplied)
    }

    @Test
    fun `即時割引の施策には通貨の倍率が掛からない`() {
        // discount は即時割引=ポイント払い出しが無いため通貨の概念がない
        val discount = campaignWithPeriod(type = CampaignType.PROMOTION, benefitType = BenefitType.DISCOUNT, rateBase = 10.0)
        val engine = periodTestEngine(
            discount,
            cards = listOf(testCard.copy(pointCurrencyId = "vp")),
            pointCurrencies = listOf(vpointLike),
        )
        val judgment = engine.judgeAll(testMerchant, today).judgments.single()
        assertEquals(10.0, judgment.effectiveRate!!, 1e-9)
        assertFalse(judgment.welcatsuApplied)
        assertNull(judgment.pointMultiplier)
    }

    // ---- プログラム会員提示施策(point_program_id。#39)のテスト ----

    private val testProgram = PointCurrency(
        id = "dp",
        name = "テストdポイント",
        brandColor = "#E60033",
        membershipProgram = true,
    )
    private val programPresentation = campaignWithPeriod(cardId = null, rateBase = 3.0)
        .copy(id = "dp_teiji", pointProgramId = "dp", presentationOnly = true)

    @Test
    fun `プログラム提示施策は会員のときだけ並記枠に出る`() {
        val engine = periodTestEngine(programPresentation, cards = emptyList(), pointCurrencies = listOf(testProgram))
        val member = engine.judgeAll(testMerchant, today, memberships = setOf("dp"))
        assertTrue(member.judgments.isEmpty())
        val judgment = member.presentationJudgments.single()
        assertEquals("dp_teiji", judgment.campaign.id)
        assertEquals("テストdポイント", judgment.badgeLabel)
        assertEquals("#E60033", judgment.brandColor)
        assertEquals(3.0, judgment.effectiveRate!!, 1e-9)
        assertNull("提示施策は最良比較に載せない", member.bestOption)
        assertEquals("3% 還元(提示のみ)", member.bestBenefitLabel().toString())

        val nonMember = engine.judgeAll(testMerchant, today)
        assertTrue(nonMember.judgments.isEmpty())
        assertTrue(nonMember.presentationJudgments.isEmpty())
    }

    @Test
    fun `プログラム提示施策は決済施策と共存し最良は決済側から選ぶ`() {
        val base = campaignWithPeriod(rateBase = 7.0).copy(id = "base")
        val engine = JudgmentEngine(
            PoikatsuData(
                merchants = listOf(testMerchant),
                campaigns = listOf(base, programPresentation),
                cards = listOf(testCard.copy(effectiveRateDefault = 7.0)),
                pointCurrencies = listOf(testProgram),
                updatedAt = "2026-06-01",
            ),
        )
        val result = engine.judgeAll(testMerchant, today, memberships = setOf("dp"))
        assertEquals(listOf("base"), result.judgments.map { it.campaign.id })
        assertEquals(listOf("dp_teiji"), result.presentationJudgments.map { it.campaign.id })
        assertEquals(7.0, result.bestOption!!.rate!!, 0.001)
    }

    @Test
    fun `提示施策があると決済分と合算した実質率が返る`() {
        // :1311 と同じデータ構成(決済 7% + プログラム提示 3%)
        val base = campaignWithPeriod(rateBase = 7.0).copy(id = "base")
        val engine = JudgmentEngine(
            PoikatsuData(
                merchants = listOf(testMerchant),
                campaigns = listOf(base, programPresentation),
                cards = listOf(testCard.copy(effectiveRateDefault = 7.0)),
                pointCurrencies = listOf(testProgram),
                updatedAt = "2026-06-01",
            ),
        )
        val result = engine.judgeAll(testMerchant, today, memberships = setOf("dp"))
        val stacked = result.stackedRate!!
        assertEquals(7.0, stacked.paymentRate, 0.0)
        assertEquals(3.0, stacked.presentationRate, 0.0)
        assertEquals(10.0, stacked.totalRate, 1e-9)
    }

    @Test
    fun `提示施策が無ければ合算はnull`() {
        // 決済施策のみのチェーン(既存フィクスチャの mcdonalds)。best はあるが提示が無いので null
        val mcdonalds = data.merchants.first { it.id == "mcdonalds" }
        assertNull(engine.judgeAll(mcdonalds, today).stackedRate)
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
    fun `recurrence施策は期間内なら非対象日でもおトクタブ用のactiveに残る`() {
        val engine = periodTestEngine(weeklyCampaign)
        // 日曜: campaignStatus は期間の外枠だけで判定(おトクタブは「次の対象日」を案内する)
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
        assertNull("抽選は名目率も持たない(円換算の対象にしない)", result.judgments.single().nominalRate)
        assertNull(result.bestOption)
        assertNull(result.bestBenefitLabel())
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

/**
 * カード施策の表示レート解決(resolveCardCampaignRate)。judgeCards とおトクタブが共有する
 * 率の優先基準: promotion=施策の率 / card_program=カードの実効率(未所有は施策側へフォールバック)。
 */
class ResolveCardCampaignRateTest {

    private fun campaign(type: CampaignType, rateBase: Double? = null, discountAmount: Int? = null) =
        Campaign(
            id = "x",
            operator = "テスト",
            cardId = "c1",
            name = "施策",
            type = type.jsonValue,
            rateBase = rateBase,
            discountAmount = discountAmount,
        )

    // マージ後を模す: 実効率は名目(クラス加算まで)。1pt価値・倍率の円換算はスコア層(#13)
    private val card = PaymentCard(
        id = "c1",
        cardName = "テストカード",
        effectiveRateDefault = 1.5,
    )

    @Test
    fun `card_programはカードの実効率(名目)を優先する`() {
        val r = resolveCardCampaignRate(campaign(CampaignType.CARD_PROGRAM, rateBase = 7.0), card)
        assertEquals(1.5, r.effectiveRate!!, 0.0)
        assertTrue("おトクタブの個別レート判定に使うカード率使用フラグが立つ", r.usesCardRate)
    }

    @Test
    fun `card_programでも未所有(card=null)は施策側のrate_baseへフォールバック`() {
        val r = resolveCardCampaignRate(campaign(CampaignType.CARD_PROGRAM, rateBase = 7.0), card = null)
        assertEquals(7.0, r.effectiveRate!!, 0.0)
        assertFalse("フォールバック時はユーザー個別の率でないのでフラグは立たない", r.usesCardRate)
    }

    @Test
    fun `promotionは施策の率がカードの実効率より優先される`() {
        val r = resolveCardCampaignRate(campaign(CampaignType.PROMOTION, rateBase = 10.0), card)
        assertEquals(10.0, r.effectiveRate!!, 0.0)
        assertFalse(r.usesCardRate)
    }

    @Test
    fun `店舗別rate_overrideは施策の率よりさらに優先される`() {
        val r = resolveCardCampaignRate(campaign(CampaignType.PROMOTION, rateBase = 10.0), card, rateOverride = 15.0)
        assertEquals(15.0, r.effectiveRate!!, 0.0)
    }

    @Test
    fun `定額施策と率なしpromotionには率を出さない`() {
        // 定額: 率を残すと定額同士の金額降順ソートが崩れる
        val discount = resolveCardCampaignRate(campaign(CampaignType.PROMOTION, discountAmount = 500), card)
        assertNull(discount.effectiveRate)
        // 率も定額も無い promotion(メモのみのカスタム等): カードの常設率で代替しない(率の捏造防止)
        val memoOnly = resolveCardCampaignRate(campaign(CampaignType.PROMOTION), card)
        assertNull(memoOnly.effectiveRate)
    }

    // ---- card_program の店舗別レート(#52。J-POINT パートナー型) ----

    @Test
    fun `card_programの店舗別rate_overrideはクラス加算を合成する(名目)`() {
        // マージ後を模す: W 選択(+0.5)→ effectiveRateDefault = 10 + 0.5(1pt価値の乗算はスコア層。#13)
        val jcbLike = PaymentCard(
            id = "c1",
            cardName = "テストJCB",
            effectiveRateDefault = 10.5,
            rateBonus = 0.5,
        )
        val program = campaign(CampaignType.CARD_PROGRAM, rateBase = 10.0)
        // 低倍率店(セブン 1.5%収録): 1.5 + 0.5 = 2.0%(1pt=0.7円なら判定で 1.4% になる)
        val low = resolveCardCampaignRate(program, jcbLike, rateOverride = 1.5)
        assertEquals(2.0, low.effectiveRate!!, 1e-9)
        assertTrue("カード由来の率なのでカード率使用フラグが立つ", low.usesCardRate)
        // 最大レート店(rate_base と同値の override): カードの実効率と一致する
        val top = resolveCardCampaignRate(program, jcbLike, rateOverride = 10.0)
        assertEquals(jcbLike.effectiveRateDefault!!, top.effectiveRate!!, 1e-9)
    }

    @Test
    fun `card_programの店舗別rate_overrideは既定設定なら収録値そのまま`() {
        // クラス概念の無いカード(rateBonus 0)は収録値がそのまま出る
        val plain = PaymentCard(id = "c1", cardName = "テスト", effectiveRateDefault = 10.0)
        val r = resolveCardCampaignRate(campaign(CampaignType.CARD_PROGRAM, rateBase = 10.0), plain, rateOverride = 2.5)
        assertEquals(2.5, r.effectiveRate!!, 0.0)
    }

    @Test
    fun `card_programの店舗別rate_overrideは未所有なら施策側の値へフォールバック`() {
        val r = resolveCardCampaignRate(campaign(CampaignType.CARD_PROGRAM, rateBase = 10.0), card = null, rateOverride = 1.5)
        assertEquals(1.5, r.effectiveRate!!, 0.0)
        assertFalse(r.usesCardRate)
    }
}
