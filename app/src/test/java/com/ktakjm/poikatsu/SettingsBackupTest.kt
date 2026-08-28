package com.ktakjm.poikatsu

import com.ktakjm.poikatsu.data.AppSettings
import com.ktakjm.poikatsu.data.CardOverride
import com.ktakjm.poikatsu.data.CustomCampaign
import com.ktakjm.poikatsu.data.CustomCard
import com.ktakjm.poikatsu.data.CustomPayment
import com.ktakjm.poikatsu.data.ExcludedStorePair
import com.ktakjm.poikatsu.data.PointBalance
import com.ktakjm.poikatsu.data.RegisteredArea
import com.ktakjm.poikatsu.data.RegisteredAreaType
import com.ktakjm.poikatsu.data.SETTINGS_BACKUP_SCHEMA_VERSION
import com.ktakjm.poikatsu.data.ThemeMode
import com.ktakjm.poikatsu.data.decodeSettingsBackup
import com.ktakjm.poikatsu.data.encodeSettingsBackup
import com.ktakjm.poikatsu.data.toBackup
import com.ktakjm.poikatsu.data.toSettings
import com.ktakjm.poikatsu.ui.backupContentSummary
import com.ktakjm.poikatsu.ui.backupFileName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 設定のエクスポート/インポート(#50)を検証する。機種変更で失われては困るユーザー資産
 * (マイカード差分・マイエリア・カスタムカード/キャンペーン)が JSON を往復しても
 * そのまま戻ること、壊れた・想定外のファイルで設定を消してしまわないことが要点。
 */
class SettingsBackupTest {

    private val settings = AppSettings(
        themeMode = ThemeMode.DARK,
        dynamicColor = false,
        autoRefresh = false,
        notificationsEnabled = true,
        notificationTimeMinutes = 7 * 60 + 30,
        cardOverrides = mapOf(
            "olive" to CardOverride(owned = false),
            "epos_gold" to CardOverride(rate = 1.5, brand = "Visa"),
            // カードクラス(#52。JCB W/S 等)も機種変更で失われないこと
            "jcb_original" to CardOverride(cardClass = "w"),
        ),
        enabledQrPaymentIds = setOf("paypay", "rakuten_pay"),
        ownedBrands = setOf("Amex"),
        // ポイント通貨の設定(#39)も機種変更で失われないこと
        enabledPointMultipliers = setOf("vpoint"),
        pointProgramMemberships = setOf("dpoint"),
        registeredAreas = listOf(
            RegisteredArea(RegisteredAreaType.MUNICIPALITY, "13113", "渋谷区", "東京都"),
        ),
        customCards = listOf(CustomCard(id = "custom:card-1", name = "地元カード", color = "#123456")),
        customCampaigns = listOf(
            CustomCampaign(
                id = "custom:camp-1",
                name = "会員限定10%",
                payments = listOf(CustomPayment(cardId = "custom:card-1")),
                storeNames = listOf("駅前ストア"),
                rate = 10.0,
                endDate = "2026-12-31",
            ),
        ),
        excludedStorePairs = listOf(
            ExcludedStorePair("smcc_combini_restaurant", "saizeriya", "サイゼリヤ 与野店", "2026-08-01"),
        ),
        // 開発者向け設定。バックアップには含めない
        dataCommitRef = "abc1234",
        useTestData = true,
        useBundledData = true,
        developerMode = true,
    )

    private fun roundTrip(source: AppSettings = settings): AppSettings {
        val text = encodeSettingsBackup(source.toBackup(exportedAt = "2026-07-27T10:00:00", appVersion = "0.9.0"))
        return decodeSettingsBackup(text)!!.toSettings()
    }

    @Test
    fun `ユーザー資産は JSON を往復しても変わらない`() {
        val restored = roundTrip()
        assertEquals(settings.cardOverrides, restored.cardOverrides)
        assertEquals(settings.enabledQrPaymentIds, restored.enabledQrPaymentIds)
        assertEquals(settings.ownedBrands, restored.ownedBrands)
        assertEquals(settings.enabledPointMultipliers, restored.enabledPointMultipliers)
        assertEquals(settings.pointProgramMemberships, restored.pointProgramMemberships)
        assertEquals(settings.registeredAreas, restored.registeredAreas)
        assertEquals(settings.customCards, restored.customCards)
        assertEquals(settings.customCampaigns, restored.customCampaigns)
        assertEquals(settings.excludedStorePairs, restored.excludedStorePairs)
    }

    @Test
    fun `表示・通知の設定も往復する`() {
        val restored = roundTrip()
        assertEquals(ThemeMode.DARK, restored.themeMode)
        assertEquals(false, restored.dynamicColor)
        assertEquals(false, restored.autoRefresh)
        assertEquals(true, restored.notificationsEnabled)
        assertEquals(7 * 60 + 30, restored.notificationTimeMinutes)
    }

    @Test
    fun `開発者向け設定は書き出さず復元でも既定値のまま`() {
        val text = encodeSettingsBackup(settings.toBackup("2026-07-27T10:00:00", "0.9.0"))
        assertTrue("開発者向けの値が JSON に混ざっている", !text.contains("abc1234"))
        val restored = decodeSettingsBackup(text)!!.toSettings()
        assertEquals("", restored.dataCommitRef)
        assertEquals(false, restored.useTestData)
        assertEquals(false, restored.useBundledData)
        assertEquals(false, restored.developerMode)
    }

    @Test
    fun `書き出した JSON はスキーマ版を持つ`() {
        val backup = decodeSettingsBackup(encodeSettingsBackup(settings.toBackup("2026-07-27T10:00:00", "0.9.0")))
        assertNotNull(backup)
        assertEquals(SETTINGS_BACKUP_SCHEMA_VERSION, backup!!.schemaVersion)
        assertEquals("2026-07-27T10:00:00", backup.exportedAt)
        assertEquals("0.9.0", backup.appVersion)
    }

    // 1pt 価値・期間限定ポイント残高は通貨単位で保持する(#13。schemaVersion 3)
    @Test
    fun `1pt価値と残高がバックアップに含まれ復元できる`() {
        val settings = AppSettings(
            pointCurrencyValues = mapOf("vpoint" to 1.5, "j_point" to 0.7),
            pointBalances = mapOf("rakuten_point" to PointBalance(balancePt = 500, expiryDate = "2026-09-01")),
        )
        val backup = settings.toBackup(exportedAt = "2026-08-19T00:00:00", appVersion = "0.5.0")
        assertEquals(3, backup.schemaVersion)
        val restored = decodeSettingsBackup(encodeSettingsBackup(backup))!!.toSettings()
        assertEquals(1.5, restored.pointCurrencyValues["vpoint"]!!, 0.0)
        assertEquals(500, restored.pointBalances["rakuten_point"]!!.balancePt)
        assertEquals("2026-09-01", restored.pointBalances["rakuten_point"]!!.expiryDate)
    }

    // 選択した倍率も機種変更で失われないこと(#83)。既存キーの追加なのでスキーマ版は上げない
    @Test
    fun `選択した倍率がバックアップに含まれ復元できる`() {
        val settings = AppSettings(
            enabledPointMultipliers = setOf("ponta"),
            pointMultiplierFactors = mapOf("ponta" to 1.5),
        )
        val restored = roundTrip(settings)
        assertEquals(setOf("ponta"), restored.enabledPointMultipliers)
        assertEquals(1.5, restored.pointMultiplierFactors["ponta"]!!, 0.0)
    }

    @Test
    fun `選択した倍率が無いバックアップは空で読める`() {
        val restored = decodeSettingsBackup("""{"schemaVersion":3,"ownedBrands":["JCB"]}""")!!.toSettings()
        assertTrue(restored.pointMultiplierFactors.isEmpty())
    }

    // 旧 v2 ファイルは新フィールド無しで読める。CardOverride.pointValue(カード単位の 1pt 価値)は
    // 通貨単位(pointCurrencyValues)へ置き換えて削除済み(#13/#90)なので、v2 ファイルにあっても
    // 読み捨てられる(ignoreUnknownKeys。エラーにはしない)
    @Test
    fun `v2バックアップは読めて新フィールドは空になる`() {
        val v2Json = """{"schemaVersion": 2, "cardOverrides": {"jcb_original": {"pointValue": 0.8}}}"""
        val restored = decodeSettingsBackup(v2Json)!!.toSettings()
        assertTrue(restored.pointCurrencyValues.isEmpty())
        assertTrue(restored.pointBalances.isEmpty())
        // 旧 CardOverride.pointValue は読み捨て(既定値の CardOverride だけが残る)
        assertEquals(CardOverride(), restored.cardOverrides["jcb_original"])
    }

    @Test
    fun `キーが増える前の古いファイルも既定値で読める`() {
        val old = """{"schemaVersion":1,"ownedBrands":["JCB"]}"""
        val restored = decodeSettingsBackup(old)!!.toSettings()
        assertEquals(setOf("JCB"), restored.ownedBrands)
        assertEquals(ThemeMode.SYSTEM, restored.themeMode)
        assertEquals(8 * 60, restored.notificationTimeMinutes)
    }

    @Test
    fun `知らないキーがあっても読める`() {
        val future = """{"schemaVersion":1,"ownedBrands":["JCB"],"futureKey":{"a":1}}"""
        assertEquals(setOf("JCB"), decodeSettingsBackup(future)!!.toSettings().ownedBrands)
    }

    // 旧 CardOverride.welcatsu(カード単位。#39 で通貨単位へ正規化して廃止)を含む v1 ファイルも
    // 読める(welcatsu は捨てられ、他の上書き値は残る)
    @Test
    fun `旧welcatsu付きのカード差分も読める`() {
        val legacy = """{"schemaVersion":1,"cardOverrides":{"smcc":{"rate":8.0,"welcatsu":true}}}"""
        val restored = decodeSettingsBackup(legacy)!!.toSettings()
        assertEquals(8.0, restored.cardOverrides.getValue("smcc").rate!!, 0.0)
        assertTrue(restored.enabledPointMultipliers.isEmpty())
    }

    // schemaVersion 無しを弾けないと、無関係な JSON が「全部既定値のバックアップ」として
    // 読めてしまい、復元で設定を消す事故になる
    @Test
    fun `このアプリのファイルでなければ読まない`() {
        assertNull(decodeSettingsBackup("""{"foo":"bar"}"""))
        assertNull(decodeSettingsBackup("{}"))
        assertNull(decodeSettingsBackup("これは JSON ではない"))
        assertNull(decodeSettingsBackup(""))
    }

    @Test
    fun `手で壊された値は既定値へ丸める`() {
        val broken = """{"schemaVersion":1,"themeMode":"NEON","notificationTimeMinutes":9999}"""
        val restored = decodeSettingsBackup(broken)!!.toSettings()
        assertEquals(ThemeMode.SYSTEM, restored.themeMode)
        assertEquals(24 * 60 - 1, restored.notificationTimeMinutes)
    }

    @Test
    fun `id が重複していても先勝ちで1件にする`() {
        val duplicated = settings.copy(
            customCards = settings.customCards + settings.customCards,
            customCampaigns = settings.customCampaigns + settings.customCampaigns,
            excludedStorePairs = settings.excludedStorePairs + settings.excludedStorePairs,
        )
        val restored = roundTrip(duplicated)
        assertEquals(1, restored.customCards.size)
        assertEquals(1, restored.customCampaigns.size)
        assertEquals(1, restored.excludedStorePairs.size)
    }

    // カスタムキャンペーン(#65)・対象外ペア(#68)は useTestData で保存が分かれる。テスト側は
    // 端末ごとの検証用の一時データなので、開発者向け設定と同様にバックアップへ書き出さない
    @Test
    fun `テストデータ側のカスタムキャンペーンと対象外ペアは書き出さない`() {
        val withTest = settings.copy(
            customCampaignsTest = listOf(CustomCampaign(id = "custom:test-1", name = "テスト側の登録")),
            excludedStorePairsTest = listOf(
                ExcludedStorePair("test_campaign", "test_merchant", "テスト店 与野店", "2026-08-09"),
            ),
        )
        val text = encodeSettingsBackup(withTest.toBackup("2026-07-27T10:00:00", "0.9.0"))
        assertTrue("テスト側の登録が JSON に混ざっている", !text.contains("custom:test-1"))
        assertTrue("テスト側の対象外ペアが JSON に混ざっている", !text.contains("test_merchant"))
        val restored = decodeSettingsBackup(text)!!.toSettings()
        assertEquals(settings.customCampaigns, restored.customCampaigns)
        assertEquals(settings.excludedStorePairs, restored.excludedStorePairs)
        assertTrue(restored.customCampaignsTest.isEmpty())
        assertTrue(restored.excludedStorePairsTest.isEmpty())
    }

    // 旧スキーマ(単一 cardId / qrPaymentId)のカスタムキャンペーンは #90 で残置フィールドを削除した。
    // 旧ファイルの登録はエラーにせず読めるが、決済手段は復元されない(payments 空。再設定してもらう)
    @Test
    fun `旧スキーマのカスタムキャンペーンは決済手段なしで読める`() {
        val legacy = """
            {"schemaVersion":1,"customCampaigns":[{"id":"custom:x","name":"旧","cardId":"olive"}]}
        """.trimIndent()
        val restored = decodeSettingsBackup(legacy)!!.toSettings().customCampaigns.single()
        assertEquals("custom:x", restored.id)
        assertTrue(restored.payments.isEmpty())
    }

    // ---- UI 表示 ----

    @Test
    fun `確認ダイアログの要約は登録のある種別だけ並べる`() {
        val backup = settings.toBackup("2026-07-27T10:00:00", "0.9.0")
        assertEquals(
            "マイカードの設定3件・カスタムカード1枚・国際ブランド1件・コード決済2件・ポイント2件・" +
                "マイエリア1件・自分で登録したキャンペーン1件・対象外に登録したお店1件",
            backupContentSummary(backup),
        )
        assertEquals(
            "登録内容なし(表示・通知の設定のみ)",
            backupContentSummary(AppSettings().toBackup("2026-07-27T10:00:00", "0.9.0")),
        )
    }

    @Test
    fun `既定ファイル名は日付入り`() {
        assertEquals("poikatsu-settings-20260727.json", backupFileName(LocalDate.of(2026, 7, 27)))
    }
}
