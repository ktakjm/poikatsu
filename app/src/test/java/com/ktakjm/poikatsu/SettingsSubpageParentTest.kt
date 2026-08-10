package com.ktakjm.poikatsu

import com.ktakjm.poikatsu.ui.SettingsSubpage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 設定サブページの親子関係([SettingsSubpage.parent])を検証する。
 * この対応表は戻る操作(2 階層目→親カテゴリ)と、二ペイン時に一覧側でハイライトする行の算出(#56)の
 * 両方が参照する単一の出所なので、ここが崩れると「戻ると設定タブのトップまで飛ぶ」
 * 「2 階層目でハイライトが消える」の両方が同時に起きる。
 */
class SettingsSubpageParentTest {

    @Test
    fun `2階層目のサブページは親カテゴリを持つ`() {
        assertEquals(SettingsSubpage.ABOUT, SettingsSubpage.LICENSES.parent)
        assertEquals(SettingsSubpage.DEVELOPER, SettingsSubpage.DEVELOPER_POIS.parent)
    }

    @Test
    fun `1階層目のカテゴリは親を持たない`() {
        val secondLevel = setOf(SettingsSubpage.LICENSES, SettingsSubpage.DEVELOPER_POIS)
        SettingsSubpage.entries.filterNot { it in secondLevel }.forEach {
            assertNull("${it.name} は 1 階層目なので親を持たない", it.parent)
        }
    }

    @Test
    fun `親は必ず1階層目のカテゴリ(2段より深い階層は作らない)`() {
        SettingsSubpage.entries.mapNotNull { it.parent }.forEach {
            assertNull("${it.name} は親カテゴリなので、それ自身は親を持たない", it.parent)
        }
    }
}
