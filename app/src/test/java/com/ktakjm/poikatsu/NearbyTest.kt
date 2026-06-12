package com.ktakjm.poikatsu

import com.ktakjm.poikatsu.data.OverpassClient
import com.ktakjm.poikatsu.data.PoikatsuJson
import com.ktakjm.poikatsu.domain.JudgmentEngine
import com.ktakjm.poikatsu.util.GeoMath
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StoreMatchTest {

    private val data = PoikatsuJson.parse(
        merchantsJson = File("../data/merchants.json").readText(),
        campaignsJson = File("../data/campaigns.json").readText(),
        profileJson = File("../data/profile.json").readText(),
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
