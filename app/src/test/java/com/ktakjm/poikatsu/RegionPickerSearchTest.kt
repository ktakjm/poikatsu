package com.ktakjm.poikatsu

import com.ktakjm.poikatsu.data.Region
import com.ktakjm.poikatsu.ui.regionDisplayName
import com.ktakjm.poikatsu.ui.searchRegions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * カスタム自治体キャンペーン(#91)の地域ピッカーの横断検索(searchRegions)と表示名。
 * 実マスタ(municipalities.json)を読む。設定側の searchAreas と違いグループは返さず、
 * 都道府県名の一致は県全域(name == prefecture)として返す。
 */
class RegionPickerSearchTest {

    private val master = RealData.municipalities

    @Test
    fun `自治体名の部分一致で都道府県横断の候補が返る`() {
        val results = searchRegions(master, "湯沢")
        assertTrue(results.contains(Region(name = "湯沢市", prefecture = "秋田県")))
        // 新潟県湯沢町も拾う(都道府県横断)
        assertTrue(results.contains(Region(name = "湯沢町", prefecture = "新潟県")))
        // 返るのは県全域か実在の市区町村だけ(グループは返さない)
        val municipalities = master.prefectures.flatMap { p -> p.municipalities.map { p.name to it.name } }.toSet()
        assertTrue(results.all { it.name == it.prefecture || (it.prefecture to it.name) in municipalities })
    }

    @Test
    fun `都道府県名の一致は県全域として返り県内の市区町村より先に並ぶ`() {
        val results = searchRegions(master, "神奈川")
        assertEquals(Region(name = "神奈川県", prefecture = "神奈川県"), results.first())
        // 神奈川区(横浜市の行政区)はマスタに無いため、県全域のみ
        assertEquals(1, results.size)
    }

    @Test
    fun `空クエリと前後空白は空リストと同じ扱い`() {
        assertTrue(searchRegions(master, "").isEmpty())
        assertTrue(searchRegions(master, "   ").isEmpty())
        assertEquals(searchRegions(master, "湯沢"), searchRegions(master, " 湯沢 "))
    }

    @Test
    fun `表示名は都道府県+自治体名で県全域は全域と明示する`() {
        assertEquals("秋田県 湯沢市", regionDisplayName(Region(name = "湯沢市", prefecture = "秋田県")))
        assertEquals("神奈川県 全域", regionDisplayName(Region(name = "神奈川県", prefecture = "神奈川県")))
    }
}
