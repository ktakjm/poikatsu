package com.ktakjm.poikatsu

import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.ktakjm.poikatsu.data.AppSettings
import com.ktakjm.poikatsu.data.BannerSelection
import com.ktakjm.poikatsu.data.CardOverride
import com.ktakjm.poikatsu.data.CustomCampaign
import com.ktakjm.poikatsu.data.CustomCard
import com.ktakjm.poikatsu.data.CustomPayment
import com.ktakjm.poikatsu.data.ExcludedStorePair
import com.ktakjm.poikatsu.data.PointBalance
import com.ktakjm.poikatsu.data.RegisteredArea
import com.ktakjm.poikatsu.data.RegisteredAreaType
import com.ktakjm.poikatsu.data.ThemeMode
import com.ktakjm.poikatsu.data.readSettings
import com.ktakjm.poikatsu.data.writeSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DataStore Preferences ⇄ AppSettings の読み書き(SettingsCodec)の往復検証(#90)。
 * 設定項目を 1 つ足すたびに AppSettings / Keys / readSettings / writeSettings を同期する構造の
 * 「漏れ」をテストで捕まえるのが目的: [full] は全フィールドを非既定値にしたスナップショットで、
 * 追加フィールドを既定値のまま残すと [フィクスチャは全フィールドが非既定値] が落ち、
 * writeSettings / readSettings のどちらかを忘れると往復が一致しなくなる。
 * datastore-preferences-core は純 JVM なので Android 無しで動く。
 */
class SettingsCodecTest {

    private val full = AppSettings(
        themeMode = ThemeMode.DARK,
        dynamicColor = false,
        autoRefresh = false,
        notificationsEnabled = true,
        notificationTimeMinutes = 7 * 60 + 15,
        cardOverrides = mapOf("olive" to CardOverride(owned = false, rate = 1.5, brand = "Visa", cardClass = "w")),
        dataCommitRef = "abc1234",
        useTestData = true,
        useBundledData = true,
        developerMode = true,
        enabledQrPaymentIds = setOf("paypay"),
        ownedBrands = setOf("Amex"),
        enabledPointMultipliers = setOf("vpoint"),
        pointMultiplierFactors = mapOf("vpoint" to 1.5),
        pointProgramMemberships = setOf("dpoint"),
        pointCurrencyValues = mapOf("vpoint" to 0.8),
        pointBalances = mapOf("vpoint" to PointBalance(1200, "2026-12-31")),
        registeredAreas = listOf(RegisteredArea(RegisteredAreaType.MUNICIPALITY, "13113", "渋谷区", "東京都")),
        customCards = listOf(CustomCard(id = "custom:card-1", name = "地元カード", color = "#123456")),
        customCampaigns = listOf(
            CustomCampaign(
                id = "custom:camp-1",
                name = "会員限定10%",
                payments = listOf(CustomPayment(cardId = "custom:card-1")),
                bannerSelections = listOf(BannerSelection("tsuruha", "kyorindo")),
                rate = 10.0,
            ),
        ),
        customCampaignsTest = listOf(CustomCampaign(id = "custom:camp-t", name = "テスト側")),
        excludedStorePairs = listOf(ExcludedStorePair("c1", "saizeriya", "渋谷店", "2026-08-01")),
        excludedStorePairsTest = listOf(ExcludedStorePair("c2", "test_merchant", "テスト店")),
    )

    /** 追加フィールドを既定値のまま [full] に足し忘れると、往復テストがその項目を検証しなくなるので防ぐ */
    @Test
    fun `フィクスチャは全フィールドが非既定値`() {
        val defaults = AppSettings()
        AppSettings::class.java.declaredFields
            .filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .forEach { field ->
                field.isAccessible = true
                assertNotEquals("AppSettings.${field.name} が既定値のまま", field.get(defaults), field.get(full))
            }
    }

    @Test
    fun `writeSettings→readSettings で全設定が往復する(開発者向け設定とテスト側リストは書かない)`() {
        val prefs = mutablePreferencesOf()
        prefs.writeSettings(full)
        val expected = full.copy(
            dataCommitRef = "",
            useTestData = false,
            useBundledData = false,
            developerMode = false,
            customCampaignsTest = emptyList(),
            excludedStorePairsTest = emptyList(),
        )
        assertEquals(expected, prefs.readSettings())
    }

    @Test
    fun `空の Preferences は既定値の AppSettings になる`() {
        assertEquals(AppSettings(), mutablePreferencesOf().readSettings())
    }

    @Test
    fun `壊れた JSON のキーだけ既定値に落ち他は読める`() {
        val prefs = mutablePreferencesOf()
        prefs.writeSettings(full)
        prefs[stringPreferencesKey("card_overrides")] = "{not json"
        val read = prefs.readSettings()
        assertTrue(read.cardOverrides.isEmpty())
        assertEquals(full.enabledQrPaymentIds, read.enabledQrPaymentIds)
    }
}
