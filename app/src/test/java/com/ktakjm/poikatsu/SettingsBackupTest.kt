package com.ktakjm.poikatsu

import com.ktakjm.poikatsu.data.AppSettings
import com.ktakjm.poikatsu.data.CardOverride
import com.ktakjm.poikatsu.data.CustomCampaign
import com.ktakjm.poikatsu.data.CustomCard
import com.ktakjm.poikatsu.data.CustomPayment
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
            "epos_gold" to CardOverride(rate = 1.5, brand = "Visa", welcatsu = true),
        ),
        enabledQrPaymentIds = setOf("paypay", "rakuten_pay"),
        ownedBrands = setOf("Amex"),
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
        assertEquals(settings.registeredAreas, restored.registeredAreas)
        assertEquals(settings.customCards, restored.customCards)
        assertEquals(settings.customCampaigns, restored.customCampaigns)
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
        )
        val restored = roundTrip(duplicated)
        assertEquals(1, restored.customCards.size)
        assertEquals(1, restored.customCampaigns.size)
    }

    // 旧スキーマ(単一 cardId)の登録も、読み込み時に payments へ折り畳まれた形で復元される
    @Test
    fun `旧スキーマのカスタムキャンペーンは payments へ正規化される`() {
        val legacy = """
            {"schemaVersion":1,"customCampaigns":[{"id":"custom:x","name":"旧","cardId":"olive"}]}
        """.trimIndent()
        val restored = decodeSettingsBackup(legacy)!!.toSettings().customCampaigns.single()
        assertEquals(listOf(CustomPayment(cardId = "olive")), restored.payments)
        assertNull(restored.cardId)
    }

    // ---- UI 表示 ----

    @Test
    fun `確認ダイアログの要約は登録のある種別だけ並べる`() {
        val backup = settings.toBackup("2026-07-27T10:00:00", "0.9.0")
        assertEquals(
            "マイカードの設定2件・カスタムカード1枚・国際ブランド1件・コード決済2件・マイエリア1件・自分で登録したキャンペーン1件",
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
