package com.ktakjm.poikatsu

import com.ktakjm.poikatsu.data.Campaign
import com.ktakjm.poikatsu.data.Merchant
import com.ktakjm.poikatsu.data.MerchantRule
import com.ktakjm.poikatsu.data.OfficialStoreList
import com.ktakjm.poikatsu.data.PoikatsuData
import com.ktakjm.poikatsu.data.PoikatsuJson
import com.ktakjm.poikatsu.data.Profile
import com.ktakjm.poikatsu.domain.JudgmentEngine
import com.ktakjm.poikatsu.domain.StoreEligibility
import com.ktakjm.poikatsu.util.JapaneseText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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
        val judgments = engine.judge(merchant)
        assertEquals(2, judgments.size)
    }

    @Test
    fun `マクドナルドは三井住友のみ対象`() {
        val merchant = data.merchants.first { it.id == "mcdonalds" }
        val judgments = engine.judge(merchant)
        assertEquals(listOf("smcc_combini_restaurant"), judgments.map { it.campaign.id })
    }

    @Test
    fun `MUFGはプロファイル前提で還元率7パーセント・警告なし`() {
        val merchant = data.merchants.first { it.id == "sushiro" }
        val judgment = engine.judge(merchant).single()
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
        data.profile.cards.map {
            if (it.campaignId == "mufg_point_up_program") it.copy(brand = brand) else it
        },
    )

    @Test
    fun `Amex は amex_excluded の店舗で MUFG が対象外になる`() {
        val kurazushi = data.merchants.first { it.id == "kurazushi" } // amex_excluded = true
        val amexEngine = engineWithProfile(profileWithMufgBrand("Amex"))
        assertTrue(amexEngine.judge(kurazushi).none { it.campaign.id == "mufg_point_up_program" })
        // 非 Amex(既定プロファイル=Mastercard)では従来どおり MUFG が出る
        assertTrue(engine.judge(kurazushi).any { it.campaign.id == "mufg_point_up_program" })
    }

    @Test
    fun `Amex でも amex_excluded でない店舗では MUFG が残る`() {
        val sevenEleven = data.merchants.first { it.id == "seven_eleven" } // amex_excluded = false
        val amexEngine = engineWithProfile(profileWithMufgBrand("Amex"))
        assertTrue(amexEngine.judge(sevenEleven).any { it.campaign.id == "mufg_point_up_program" })
    }

    @Test
    fun `未所有カードの施策は判定に出ない`() {
        val kurazushi = data.merchants.first { it.id == "kurazushi" }
        // MUFG カードを所有していない(profile から除外)ケース
        val onlySmcc = Profile(data.profile.cards.filter { it.campaignId == "smcc_combini_restaurant" })
        assertTrue(engineWithProfile(onlySmcc).judge(kurazushi).none { it.campaign.id == "mufg_point_up_program" })
        // どのカードも所有していなければ判定は空
        assertTrue(engineWithProfile(Profile()).judge(kurazushi).isEmpty())
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
