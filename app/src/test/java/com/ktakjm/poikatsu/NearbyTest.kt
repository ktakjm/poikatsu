package com.ktakjm.poikatsu

import com.ktakjm.poikatsu.data.Campaign
import com.ktakjm.poikatsu.data.GcGroup
import com.ktakjm.poikatsu.data.Merchant
import com.ktakjm.poikatsu.data.MerchantRule
import com.ktakjm.poikatsu.data.OverpassClient
import com.ktakjm.poikatsu.data.Poi
import com.ktakjm.poikatsu.data.PoikatsuData
import com.ktakjm.poikatsu.data.PoikatsuJson
import com.ktakjm.poikatsu.data.YolpClient
import com.ktakjm.poikatsu.data.YolpConfig
import com.ktakjm.poikatsu.data.YolpSearchConfig
import com.ktakjm.poikatsu.domain.JudgmentEngine
import com.ktakjm.poikatsu.util.GeoMath
import java.io.File
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * フィクスチャデータで店舗マッチングロジックを検証する。
 */
class StoreMatchTest {

    private val data = PoikatsuData(
        merchants = listOf(
            Merchant(id = "mcdonalds", name = "マクドナルド", reading = "まくどなるど", aliases = listOf("マック")),
            Merchant(id = "seven_eleven", name = "セブン-イレブン", reading = "せぶんいれぶん", aliases = listOf("セブンイレブン", "7-ELEVEN")),
            Merchant(id = "gusto", name = "ガスト", reading = "がすと"),
            Merchant(id = "steak_gusto", name = "ステーキガスト", reading = "すてーきがすと"),
            Merchant(id = "lawson", name = "ローソン", reading = "ろーそん", aliases = listOf("LAWSON")),
            Merchant(id = "hanamasa", name = "肉のハナマサ", reading = "にくのはなまさ", aliases = listOf("ハナマサ")),
            Merchant(id = "ok_store", name = "オーケー", reading = "おーけー", aliases = listOf("OK"), yolpSearch = "keyword", yolpKeyword = "オーケー"),
            Merchant(id = "akachan_honpo", name = "アカチャンホンポ", reading = "あかちゃんほんぽ", yolpSearch = "keyword", yolpKeyword = "アカチャンホンポ"),
            Merchant(id = "kfc", name = "ケンタッキーフライドチキン", reading = "けんたっきーふらいどちきん", aliases = listOf("KFC")),
            Merchant(id = "starbucks", name = "スターバックスコーヒー", reading = "すたーばっくすこーひー", aliases = listOf("スタバ")),
            Merchant(id = "dommy", name = "ドミー", reading = "どみー", category = "スーパー"),
            Merchant(id = "ueshima", name = "上島珈琲店", reading = "うえしまこーひーてん", category = "カフェ"),
        ),
        campaigns = emptyList(),
        updatedAt = "2026-06-01",
    )
    private val engine = JudgmentEngine(data)

    @Test
    fun `POI名からチェーンを特定できる`() {
        assertEquals("mcdonalds", engine.matchStore("マクドナルド 渋谷駅前店")?.id)
        assertEquals("seven_eleven", engine.matchStore("セブン-イレブン 横浜北幸1丁目店")?.id)
    }

    @Test
    fun `最長一致でステーキガストがガストに誤マッチしない`() {
        assertEquals("steak_gusto", engine.matchStore("ステーキガスト 町田店")?.id)
        assertEquals("gusto", engine.matchStore("ガスト 町田店")?.id)
    }

    @Test
    fun `brandタグでもマッチする`() {
        assertEquals("lawson", engine.matchStore("三田二丁目店", brand = "ローソン")?.id)
    }

    @Test
    fun `無関係の店はマッチしない`() {
        assertNull(engine.matchStore("個人経営の喫茶店ポエム"))
    }

    @Test
    fun `マックスバリュがマック(マクドナルド)に誤マッチしない`() {
        assertNull(engine.matchStore("マックスバリュ 渋谷店"))
        assertNull(engine.matchStore("マックスバリュエクスプレス川崎店"))
    }

    @Test
    fun `英字のbrandタグでもマッチする`() {
        // OSMのbrandは "7-ELEVEN" "LAWSON" のような英字表記が多い
        assertEquals("seven_eleven", engine.matchStore("名称不明", brand = "7-ELEVEN")?.id)
        assertEquals("lawson", engine.matchStore("名称不明", brand = "LAWSON")?.id)
    }

    @Test
    fun `YOLPの連結店名(支店名がひらがな始まり)でもマッチする`() {
        // YOLP は支店名を区切りなく連結する。正規化後「肉のはなまさ|ひばりが丘店」のように
        // チェーン名の直後がひらがなでも、長いキーなら支店名の一部として許容する(取りこぼし防止)。
        assertEquals("hanamasa", engine.matchStore("肉のハナマサひばりヶ丘店")?.id)
        // 漢字始まりの支店名は従来から境界OK
        assertEquals("ok_store", engine.matchStore("オーケー大泉インター店")?.id)
    }

    @Test
    fun `重複排除キーは空白違い・別名違いを同一、別支店を別とする`() {
        val aka = engine.matchStore("アカチャンホンポ和光イトーヨーカドー店")!!
        // 空白の有無だけ違う同一店舗 → 同じ支店キー
        assertEquals(
            engine.normalizedBranch(aka, "アカチャンホンポ和光 イトーヨーカドー店"),
            engine.normalizedBranch(aka, "アカチャンホンポ和光イトーヨーカドー店"),
        )
        // 「KFC」と「ケンタッキーフライドチキン」(別名違い)→ 支店名が同じなら同じキー
        val kfc = engine.matchStore("KFC渋谷店")!!
        assertEquals(
            engine.normalizedBranch(kfc, "KFC渋谷店"),
            engine.normalizedBranch(kfc, "ケンタッキーフライドチキン渋谷店"),
        )
        // 同一モール内の別店舗(支店名が違う)→ 別キー(誤って1件に潰さない)
        val sbux = engine.matchStore("スターバックスコーヒーイオンレイクタウンkaze店")!!
        assertNotEquals(
            engine.normalizedBranch(sbux, "スターバックスコーヒーイオンレイクタウンkaze店"),
            engine.normalizedBranch(sbux, "スターバックスコーヒーイオンレイクタウンmori店"),
        )
    }

    @Test
    fun `施設内テナントの誤検知を弾く`() {
        // 「店」の後ろに別業種名が続く=施設(対象チェーン)内テナント → 除外
        assertTrue(engine.isFacilityTenant("ドミー", "ドミー安城横山店大嶽クリーニング"))
        // 正規の店舗(末尾が「店」)は除外しない
        assertFalse(engine.isFacilityTenant("ドミー", "ドミー安城横山店"))
        assertFalse(engine.isFacilityTenant("肉のハナマサ", "肉のハナマサひばりヶ丘店"))
        // チェーン名自体に「店」を含む場合も、チェーン名より後ろだけ見るので誤判定しない
        assertFalse(engine.isFacilityTenant("上島珈琲店", "上島珈琲店渋谷店"))
        // 施設名込みでも末尾が「店」なら正規(アカチャンホンポの実店舗)
        assertFalse(engine.isFacilityTenant("アカチャンホンポ", "アカチャンホンポ和光 イトーヨーカドー店"))
    }
}

class OverpassParseTest {

    @Test
    fun `nodeとway(center)の両方をパースできる`() {
        val body = """
            {
              "elements": [
                {"type": "node", "id": 1, "lat": 35.658, "lon": 139.701,
                 "tags": {"name": "マクドナルド 渋谷店", "brand": "マクドナルド", "amenity": "fast_food"}},
                {"type": "way", "id": 2, "center": {"lat": 35.659, "lon": 139.702},
                 "tags": {"name": "サイゼリヤ 渋谷店", "amenity": "restaurant"}},
                {"type": "node", "id": 3, "lat": 35.660, "lon": 139.703, "tags": {"amenity": "cafe"}}
              ]
            }
        """.trimIndent()
        val pois = OverpassClient.parse(body)
        // name のない要素(id=3)は除外される
        assertEquals(2, pois.size)
        assertEquals("マクドナルド 渋谷店", pois[0].name)
        assertEquals("マクドナルド", pois[0].brand)
        assertEquals(35.659, pois[1].lat, 0.0001)
        assertNull(pois[1].brand)
    }

    @Test
    fun `branchタグがあれば表示名に支店名が付く`() {
        val body = """
            {
              "elements": [
                {"type": "node", "id": 1, "lat": 35.658, "lon": 139.701,
                 "tags": {"name": "セブン-イレブン", "branch": "渋谷一丁目店", "shop": "convenience"}},
                {"type": "node", "id": 2, "lat": 35.659, "lon": 139.702,
                 "tags": {"name": "セブン-イレブン", "shop": "convenience"}}
              ]
            }
        """.trimIndent()
        val pois = OverpassClient.parse(body)
        assertEquals("セブン-イレブン 渋谷一丁目店", pois[0].displayName)
        assertEquals("セブン-イレブン", pois[1].displayName)
    }
}

class YolpParseTest {

    @Test
    fun `Feature の Name と Coordinates(lon,lat)をパースできる`() {
        val body = """
            {
              "ResultInfo": { "Count": 2, "Total": 2, "Start": 1 },
              "Feature": [
                {
                  "Id": "1",
                  "Name": "セブン-イレブン渋谷道玄坂店",
                  "Geometry": { "Type": "point", "Coordinates": "139.6976,35.6580" }
                },
                {
                  "Id": "2",
                  "Name": "マクドナルド渋谷店",
                  "Geometry": { "Type": "point", "Coordinates": "139.7002,35.6591" }
                }
              ]
            }
        """.trimIndent()
        val pois = YolpClient.parse(body)
        assertEquals(2, pois.size)
        // 支店名は Name に内包される(branch は分けない) → displayName は Name と一致
        assertEquals("セブン-イレブン渋谷道玄坂店", pois[0].name)
        assertEquals("セブン-イレブン渋谷道玄坂店", pois[0].displayName)
        assertNull(pois[0].branch)
        // Coordinates は "経度,緯度" の順
        assertEquals(35.6580, pois[0].lat, 0.0001)
        assertEquals(139.6976, pois[0].lon, 0.0001)
    }

    @Test
    fun `Name や座標が欠けた Feature は除外される`() {
        val body = """
            {
              "Feature": [
                { "Id": "1", "Geometry": { "Coordinates": "139.70,35.65" } },
                { "Id": "2", "Name": "座標なし店" },
                { "Id": "3", "Name": "正常店", "Geometry": { "Coordinates": "139.71,35.66" } }
              ]
            }
        """.trimIndent()
        val pois = YolpClient.parse(body)
        assertEquals(1, pois.size)
        assertEquals("正常店", pois[0].name)
    }

    @Test
    fun `Feature が無い空レスポンスは空リスト`() {
        assertEquals(0, YolpClient.parse("""{ "ResultInfo": { "Count": 0 } }""").size)
    }
}

class YolpClipTest {

    // 中心(35.0, 135.0)から北に m メートルの POI(緯度1度≒111195m)
    private fun poi(name: String, meters: Double) = Poi(
        name = name, branch = null, brand = null,
        lat = 35.0 + meters / 111195.0, lon = 135.0,
    )

    @Test
    fun `打ち切りソースの最遠距離で全ソースを共通半径に切り捨てる`() {
        // 高密度ソース: 上限到達(truncated)。最遠は 200m → 共通カバー半径 200m
        val dense = YolpClient.SourceResult(
            pois = listOf(poi("A", 100.0), poi("B", 200.0)),
            truncated = true,
        )
        // 疎ソース: 上限未到達。近い C(150m)は残り、遠い D(5000m)は切り捨て
        val sparse = YolpClient.SourceResult(
            pois = listOf(poi("C", 150.0), poi("D", 5000.0)),
            truncated = false,
        )
        val merged = YolpClient.mergeAndClip(35.0, 135.0, listOf(dense, sparse))
        assertEquals(setOf("A", "B", "C"), merged.map { it.name }.toSet())
    }

    @Test
    fun `打ち切りソースが無ければ切り捨てない`() {
        val s = YolpClient.SourceResult(
            pois = listOf(poi("X", 100.0), poi("Y", 8000.0)),
            truncated = false,
        )
        val merged = YolpClient.mergeAndClip(35.0, 135.0, listOf(s))
        assertEquals(2, merged.size)
    }

    @Test
    fun `共通カバー半径は打ち切りソースの最遠距離の最小値`() {
        // 2 つの打ち切りソース。最遠 300m と 150m → 共通半径は小さい方の 150m
        val a = YolpClient.SourceResult(listOf(poi("A", 300.0)), truncated = true)
        val b = YolpClient.SourceResult(listOf(poi("B", 150.0)), truncated = true)
        // 別の疎ソースに 200m の点 → 150m 超で切り捨て、A(300m)も切り捨て、B(150m)は残る
        val c = YolpClient.SourceResult(listOf(poi("C", 200.0)), truncated = false)
        val merged = YolpClient.mergeAndClip(35.0, 135.0, listOf(a, b, c))
        assertEquals(setOf("B"), merged.map { it.name }.toSet())
    }

    @Test
    fun `座標と名前が同じ POI はソースを跨いで重複排除される`() {
        val a = YolpClient.SourceResult(listOf(poi("Same", 100.0)), truncated = false)
        val b = YolpClient.SourceResult(listOf(poi("Same", 100.0)), truncated = false)
        val merged = YolpClient.mergeAndClip(35.0, 135.0, listOf(a, b))
        assertEquals(1, merged.size)
    }
}

/**
 * フィクスチャデータで YOLP 検索設定ロジックを検証する。
 */
class YolpSearchConfigTest {

    private val fixtureYolpConfig = YolpConfig(
        gcGroups = listOf(
            GcGroup(gc = "0123", categories = listOf("ファストフード", "ファミレス"), maxPages = 5),
            GcGroup(gc = "0205", categories = listOf("コンビニ", "スーパー"), maxPages = 3),
        ),
    )
    private val fixtureMerchants = listOf(
        Merchant(id = "seven_eleven", name = "セブン-イレブン", category = "コンビニ", yolpSearch = "gc"),
        Merchant(id = "mcdonalds", name = "マクドナルド", category = "ファストフード", yolpSearch = "gc"),
        Merchant(id = "ok_store", name = "オーケー", category = "スーパー", yolpSearch = "keyword", yolpKeyword = "オーケー"),
        Merchant(id = "coke_on", name = "Coke ON", category = "その他", yolpSearch = "none"),
    )

    @Test
    fun `該当merchantがいないgc_groupはスキップされる`() {
        val config = YolpSearchConfig.build(fixtureYolpConfig, fixtureMerchants, setOf("seven_eleven"))
        assertEquals(1, config.gcGroups.size)
        assertEquals("0205", config.gcGroups[0].gc)
    }

    @Test
    fun `yolp_search_noneのmerchantは検索対象にならない`() {
        val config = YolpSearchConfig.build(fixtureYolpConfig, fixtureMerchants, setOf("coke_on"))
        assertTrue(config.keywordQueries.isEmpty())
        assertTrue(config.gcGroups.isEmpty())
    }

    @Test
    fun `gc_groupごとのmax_pagesが反映される`() {
        val allIds = fixtureMerchants.map { it.id }.toSet()
        val config = YolpSearchConfig.build(fixtureYolpConfig, fixtureMerchants, allIds)
        assertEquals(5, config.gcGroups[0].maxPages)
        assertEquals(3, config.gcGroups[1].maxPages)
    }

    @Test
    fun `空のactiveMerchantIdsでは全gc_groupがスキップされキーワードも空`() {
        val config = YolpSearchConfig.build(fixtureYolpConfig, fixtureMerchants, emptySet())
        assertTrue(config.gcGroups.isEmpty())
        assertTrue(config.keywordQueries.isEmpty())
    }
}

/**
 * 実データで YOLP 検索設定の整合性を検証する。
 */
class YolpSearchConfigRealDataTest {

    private val data = PoikatsuJson.parse(
        merchantsJson = File("../data/merchants.json").readText(),
        campaignsJson = File("../data/campaigns.json").readText(),
        paymentMethodsJson = File("../data/payment_methods.json").readText(),
    )
    private val engine = JudgmentEngine(data)
    private val today = LocalDate.of(2026, 6, 28)

    @Test
    fun `実データ_configは旧ハードコードと同じgcグループを使う`() {
        val activeMerchantIds = engine.activeManagedMerchantIds(today)
        val config = YolpSearchConfig.build(data.yolpConfig!!, data.merchants, activeMerchantIds)
        assertEquals(listOf("0123,0115,0101013", "0205"), config.gcGroups.map { it.gc })
    }

    @Test
    fun `実データ_configは旧ハードコードと同じキーワードを使う`() {
        val activeMerchantIds = engine.activeManagedMerchantIds(today)
        val config = YolpSearchConfig.build(data.yolpConfig!!, data.merchants, activeMerchantIds)
        val expected = setOf("カーブス", "アカチャンホンポ", "オーケー", "ピザハット", "上島珈琲", "はま寿司")
        assertEquals(expected, config.keywordQueries.toSet())
    }

    @Test
    fun `実データ_全keyword_merchantがアクティブな施策にカバーされている`() {
        val activeMerchantIds = engine.activeManagedMerchantIds(today)
        val keywordMerchants = data.merchants.filter { it.yolpSearch == "keyword" }
        assertTrue(keywordMerchants.all { it.id in activeMerchantIds })
    }
}

class GeoMathTest {

    @Test
    fun `既知の2地点間の距離が概ね正しい`() {
        // 東京駅(35.6812,139.7671) → 有楽町駅(35.6749,139.7628) は約800m
        val d = GeoMath.distanceMeters(35.6812, 139.7671, 35.6749, 139.7628)
        assertTrue("distance was $d", d in 700..900)
    }

    @Test
    fun `同一地点は0m`() {
        assertEquals(0, GeoMath.distanceMeters(35.0, 139.0, 35.0, 139.0))
    }
}
