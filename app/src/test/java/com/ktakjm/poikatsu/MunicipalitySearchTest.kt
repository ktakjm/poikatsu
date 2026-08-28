package com.ktakjm.poikatsu

import com.ktakjm.poikatsu.data.MunicipalityMaster
import com.ktakjm.poikatsu.data.RegisteredAreaType
import com.ktakjm.poikatsu.ui.searchAreas
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ピッカーの自治体名検索(searchAreas・#49)の検証。
 * マスタは実データを読む(RegionFilterTest と同じく、生成スクリプトの出力が壊れたら気付けるように)。
 */
class MunicipalitySearchTest {

    private val master: MunicipalityMaster = RealData.municipalities

    @Test
    fun `自治体名の部分一致で都道府県横断に検索できる`() {
        val results = searchAreas(master, "札幌")
        assertTrue(
            results.any {
                it.type == RegisteredAreaType.MUNICIPALITY &&
                    it.name == "札幌市" && it.prefecture == "北海道"
            }
        )
    }

    @Test
    fun `同名を含む自治体が複数の都道府県にあれば全部出る`() {
        // 府中: 東京都府中市・広島県府中市・広島県府中町
        val prefectures = searchAreas(master, "府中")
            .filter { it.type == RegisteredAreaType.MUNICIPALITY }
            .map { it.prefecture }
            .toSet()
        assertTrue("東京都" in prefectures && "広島県" in prefectures)
    }

    @Test
    fun `グループ名も部分一致で出る`() {
        val results = searchAreas(master, "23区")
        assertTrue(
            results.any { it.type == RegisteredAreaType.GROUP && it.name == "東京23区" }
        )
    }

    @Test
    fun `前後の空白は無視して一致させる`() {
        assertEquals(searchAreas(master, "札幌"), searchAreas(master, " 札幌 "))
    }

    @Test
    fun `空・空白のみのクエリと一致なしは空リスト`() {
        assertTrue(searchAreas(master, "").isEmpty())
        assertTrue(searchAreas(master, "   ").isEmpty())
        assertTrue(searchAreas(master, "存在しない自治体名").isEmpty())
    }
}
