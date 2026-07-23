package com.ktakjm.poikatsu

import com.ktakjm.poikatsu.data.DataSource
import com.ktakjm.poikatsu.data.RegisteredArea
import com.ktakjm.poikatsu.data.RegisteredAreaType
import com.ktakjm.poikatsu.data.ThemeMode
import com.ktakjm.poikatsu.ui.areaDisplayName
import com.ktakjm.poikatsu.ui.dataRowSummary
import com.ktakjm.poikatsu.ui.developerRowSummary
import com.ktakjm.poikatsu.ui.displaySettingsSummary
import com.ktakjm.poikatsu.ui.municipalitySummary
import com.ktakjm.poikatsu.ui.paymentMethodsSummary
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 設定トップのカテゴリ行サマリ(#47)を検証する。
 * 階層化で畳んだ情報がトップで一望できる文言になっていること。
 */
class SettingsSummaryTest {

    private fun area(
        name: String,
        prefecture: String,
        type: RegisteredAreaType = RegisteredAreaType.MUNICIPALITY,
    ) = RegisteredArea(type = type, code = "x", name = name, prefecture = prefecture)

    // ---- 支払い方法 ----

    @Test
    fun `支払い方法-登録のある種別だけ列挙する`() {
        assertEquals("カード3枚・コード決済2件", paymentMethodsSummary(cardCount = 3, brandCount = 0, qrCount = 2))
        assertEquals("カード1枚・ブランド2件・コード決済1件", paymentMethodsSummary(1, 2, 1))
        assertEquals("ブランド1件", paymentMethodsSummary(0, 1, 0))
    }

    @Test
    fun `支払い方法-すべて未登録なら未登録`() {
        assertEquals("未登録", paymentMethodsSummary(0, 0, 0))
    }

    // ---- 自治体 ----

    @Test
    fun `自治体-表示名は都道府県+名前`() {
        assertEquals("埼玉県 南部", areaDisplayName(area("南部", "埼玉県", RegisteredAreaType.GROUP)))
        assertEquals("北海道 札幌市", areaDisplayName(area("札幌市", "北海道")))
    }

    @Test
    fun `自治体-1件なら表示名のみ`() {
        assertEquals("北海道 札幌市", municipalitySummary(listOf(area("札幌市", "北海道"))))
    }

    @Test
    fun `自治体-複数件は先頭+ほかN件`() {
        val areas = listOf(
            area("南部", "埼玉県", RegisteredAreaType.GROUP),
            area("札幌市", "北海道"),
            area("川崎市", "神奈川県"),
        )
        assertEquals("埼玉県 南部 ほか2件", municipalitySummary(areas))
    }

    @Test
    fun `自治体-未登録なら効果説明を添える`() {
        assertEquals("未登録(登録すると地域のキャンペーンが届きます)", municipalitySummary(emptyList()))
    }

    // ---- キャンペーンデータ ----

    @Test
    fun `データ-行タイトルと重複する接頭辞なしの短縮形`() {
        assertEquals(
            "2026/07/20・最新データ取得済み",
            dataRowSummary("2026/07/20", DataSource.REMOTE),
        )
        assertEquals(
            "2026/07/20・同梱データ表示中(開発者設定) [テストデータ]",
            dataRowSummary("2026/07/20", DataSource.BUNDLED, useTestData = true, useBundledData = true),
        )
    }

    @Test
    fun `データ-空の要素は・で繋がない`() {
        assertEquals("2026/07/20", dataRowSummary("2026/07/20", source = null))
        assertEquals("最新データ取得済み", dataRowSummary("", DataSource.REMOTE))
    }

    // ---- 表示 ----

    @Test
    fun `表示-テーマと壁紙の色を併記する`() {
        assertEquals(
            "テーマ: システム・壁紙の色 ON",
            displaySettingsSummary(ThemeMode.SYSTEM, dynamicColor = true, dynamicSupported = true),
        )
        assertEquals(
            "テーマ: ダーク・壁紙の色 OFF",
            displaySettingsSummary(ThemeMode.DARK, dynamicColor = false, dynamicSupported = true),
        )
    }

    @Test
    fun `表示-dynamic color 非対応端末では壁紙の色を出さない`() {
        assertEquals(
            "テーマ: ライト",
            displaySettingsSummary(ThemeMode.LIGHT, dynamicColor = true, dynamicSupported = false),
        )
    }

    // ---- 開発者向け ----

    @Test
    fun `開発者向け-オフ`() {
        assertEquals(
            "開発者モード オフ",
            developerRowSummary(developerMode = false, dataCommitRef = "abc", useTestData = true, useBundledData = false),
        )
    }

    @Test
    fun `開発者向け-オン中は非既定値まで出す`() {
        assertEquals(
            "開発者モード オン・すべて既定値",
            developerRowSummary(true, "", useTestData = false, useBundledData = false),
        )
        assertEquals(
            "開発者モード オン・テストデータ ON・ref=abc1234",
            developerRowSummary(true, "abc1234", useTestData = true, useBundledData = false),
        )
    }
}
