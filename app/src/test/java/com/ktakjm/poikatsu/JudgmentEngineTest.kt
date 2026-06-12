package com.ktakjm.poikatsu

import com.ktakjm.poikatsu.data.PoikatsuJson
import com.ktakjm.poikatsu.domain.BranchWarningLevel
import com.ktakjm.poikatsu.domain.JudgmentEngine
import com.ktakjm.poikatsu.util.JapaneseText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `MUFGはプロファイル前提のエントリー済みで警告なし還元率7パーセント`() {
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
    fun `公式の対象外店舗パターンに一致すると強警告が出る`() {
        val merchant = data.merchants.first { it.id == "kurazushi" }
        val judgment = engine.judge(merchant, "ららぽーとTOKYO-BAY店").single()
        assertTrue(judgment.branchWarnings.any { it.level == BranchWarningLevel.EXCLUDED })
    }

    @Test
    fun `商業施設系の店舗名はリスク警告が出る`() {
        val merchant = data.merchants.first { it.id == "seven_eleven" }
        val judgments = engine.judge(merchant, "イオンモール幕張新都心店")
        // 三井住友・MUFG両施策でリスク警告
        assertEquals(2, judgments.size)
        assertTrue(judgments.all { j -> j.branchWarnings.any { it.level == BranchWarningLevel.RISK } })
    }

    @Test
    fun `通常の店舗名なら店舗警告は出ない`() {
        val merchant = data.merchants.first { it.id == "mcdonalds" }
        val judgment = engine.judge(merchant, "渋谷駅前店").single()
        assertTrue(judgment.branchWarnings.isEmpty())
    }

    @Test
    fun `店舗名未入力なら店舗警告は出ない`() {
        val merchant = data.merchants.first { it.id == "kurazushi" }
        assertTrue(engine.judge(merchant).single().branchWarnings.isEmpty())
        assertTrue(engine.judge(merchant, "").single().branchWarnings.isEmpty())
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
