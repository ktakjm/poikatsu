package com.ktakjm.poikatsu

import com.ktakjm.poikatsu.data.Campaign
import com.ktakjm.poikatsu.data.MunicipalityMaster
import com.ktakjm.poikatsu.data.PoikatsuJson
import com.ktakjm.poikatsu.data.Region
import com.ktakjm.poikatsu.data.RegisteredArea
import com.ktakjm.poikatsu.data.RegisteredAreaType
import com.ktakjm.poikatsu.domain.filterCampaignsByArea
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 地域フィルタ(filterCampaignsByArea)と自治体マスタ(municipalities.json v2)の検証。
 * マスタは実データを読む(生成スクリプトの出力が壊れたら CI で気付けるように)。
 */
class RegionFilterTest {

    private val master: MunicipalityMaster = PoikatsuJson.parseMunicipalities(
        File("../data/municipalities.json").readText()
    )

    private fun municipalCampaign(id: String, prefecture: String, name: String) = Campaign(
        id = id,
        operator = "テスト",
        name = "$name のテスト施策",
        type = "municipal",
        region = Region(name = name, prefecture = prefecture),
    )

    private val national = Campaign(id = "national", operator = "テスト", name = "全国施策")
    private val suginami = municipalCampaign("suginami", "東京都", "杉並区")
    private val yuzawa = municipalCampaign("yuzawa", "秋田県", "湯沢市")
    private val campaigns = listOf(national, suginami, yuzawa)

    private fun registeredGroup(id: String): RegisteredArea {
        val (pref, group) = master.prefectures
            .firstNotNullOf { p -> p.groups.firstOrNull { it.id == id }?.let { p to it } }
        return RegisteredArea(RegisteredAreaType.GROUP, group.id, group.name, pref.name)
    }

    // ---------- マスタの整合性 ----------

    @Test
    fun `自治体マスタは全47都道府県と全自治体を含む`() {
        assertEquals(47, master.prefectures.size)
        assertEquals(1741, master.prefectures.sumOf { it.municipalities.size })
        val names = master.prefectures.map { it.name }
        assertTrue("北海道" in names)
        assertTrue("東京都" in names)
        assertTrue("沖縄県" in names)
    }

    @Test
    fun `東京都には東京23区グループがあり23区を含む`() {
        val tokyo = master.prefectures.first { it.name == "東京都" }
        val tokyo23 = tokyo.groups.first { it.name == "東京23区" }
        assertEquals(23, tokyo23.municipalities.size)
        val nameOf = tokyo.municipalities.associate { it.code to it.name }
        assertTrue("渋谷区" in tokyo23.municipalities.map { nameOf[it] })
        assertTrue("千代田区" in tokyo23.municipalities.map { nameOf[it] })
    }

    @Test
    fun `埼玉県には南部グループがある`() {
        val saitama = master.prefectures.first { it.name == "埼玉県" }
        val south = saitama.groups.first { it.name == "南部" }
        val nameOf = saitama.municipalities.associate { it.code to it.name }
        assertTrue("さいたま市" in south.municipalities.map { nameOf[it] })
    }

    @Test
    fun `グループidはマスタ全体で一意`() {
        val ids = master.prefectures.flatMap { it.groups }.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `グループの構成自治体コードは全て同一都道府県内に解決できる`() {
        master.prefectures.forEach { pref ->
            val codes = pref.municipalities.map { it.code }.toSet()
            pref.groups.forEach { group ->
                assertTrue(
                    "${pref.name} ${group.name} に他県コードか未知コードがある",
                    group.municipalities.all { it in codes },
                )
            }
        }
    }

    // ---------- フィルタ ----------

    @Test
    fun `登録が無ければフィルタしない`() {
        assertEquals(campaigns, filterCampaignsByArea(campaigns, emptyList(), master))
    }

    @Test
    fun `マスタ未ロードならフィルタしない`() {
        val registered = listOf(
            RegisteredArea(RegisteredAreaType.MUNICIPALITY, "13115", "杉並区", "東京都"),
        )
        assertEquals(campaigns, filterCampaignsByArea(campaigns, registered, MunicipalityMaster()))
    }

    @Test
    fun `自治体登録で一致する施策と全国施策だけ通る`() {
        val suginamiCode = master.prefectures.first { it.name == "東京都" }
            .municipalities.first { it.name == "杉並区" }.code
        val registered = listOf(
            RegisteredArea(RegisteredAreaType.MUNICIPALITY, suginamiCode, "杉並区", "東京都"),
        )
        assertEquals(
            listOf(national, suginami),
            filterCampaignsByArea(campaigns, registered, master),
        )
    }

    @Test
    fun `東京23区グループ登録で杉並区の施策が通り湯沢市は落ちる`() {
        val registered = listOf(registeredGroup("custom-tokyo23"))
        assertEquals(
            listOf(national, suginami),
            filterCampaignsByArea(campaigns, registered, master),
        )
    }

    @Test
    fun `マスタに無い地域の施策は防御的に通す`() {
        val unknown = municipalCampaign("unknown", "東京都", "存在しない市")
        val registered = listOf(registeredGroup("custom-tokyo23"))
        val result = filterCampaignsByArea(campaigns + unknown, registered, master)
        assertTrue(unknown in result)
    }

    // ---------- 永続化形式 ----------

    @Test
    fun `RegisteredAreaのシリアライズが往復しtypeは小文字文字列になる`() {
        val json = kotlinx.serialization.json.Json
        val area = RegisteredArea(RegisteredAreaType.GROUP, "custom-tokyo23", "東京23区", "東京都")
        val encoded = json.encodeToString(RegisteredArea.serializer(), area)
        assertTrue(encoded.contains("\"group\""))
        assertEquals(area, json.decodeFromString(RegisteredArea.serializer(), encoded))
    }
}
