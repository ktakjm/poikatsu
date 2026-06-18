package com.ktakjm.poikatsu.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** テーマの選び方。SYSTEM は端末のダーク設定に追従する。 */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * ユーザーがカードごとに上書きする差分。profile.json(カタログ=既定値)に重ねる。
 * 値が null/既定なら profile.json の値を使う。
 */
@Serializable
data class CardOverride(
    /** このカードを所有しているか。null=既定(所有)。false で施策ごと判定から外す。 */
    val owned: Boolean? = null,
    /** 公式アプリ表示の実効還元率。null なら profile の既定値。 */
    val rate: Double? = null,
    /** カードブランド(MUFG の Amex/Mastercard/Visa/JCB 等)。null なら profile の既定値。 */
    val brand: String? = null,
    /** ウエル活(ポイント価値 ×倍率)で表示するか。 */
    val welcatsu: Boolean = false,
)

/** アプリ全体の設定スナップショット。DataStore から1本の Flow で配る。 */
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val autoRefresh: Boolean = true,
    val cardOverrides: Map<String, CardOverride> = emptyMap(),
)

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore("settings")

/**
 * 設定の永続化(DataStore Preferences)。テーマ/データ取得とカード差分を保持する。
 * カード差分は campaign_id をキーにした Map を JSON 文字列として1キーに格納する
 * (キー数が可変でも Preferences のキーを増やさずに済む)。
 */
class SettingsRepository(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }

    private object Keys {
        val THEME = stringPreferencesKey("theme_mode")
        val DYNAMIC = booleanPreferencesKey("dynamic_color")
        val AUTO_REFRESH = booleanPreferencesKey("auto_refresh")
        val CARD_OVERRIDES = stringPreferencesKey("card_overrides")
    }

    val settings: Flow<AppSettings> = context.settingsDataStore.data.map { prefs ->
        AppSettings(
            themeMode = prefs[Keys.THEME]
                ?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
                ?: ThemeMode.SYSTEM,
            dynamicColor = prefs[Keys.DYNAMIC] ?: true,
            autoRefresh = prefs[Keys.AUTO_REFRESH] ?: true,
            cardOverrides = prefs.decodeOverrides(),
        )
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.settingsDataStore.edit { it[Keys.THEME] = mode.name }
    }

    suspend fun setDynamicColor(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.DYNAMIC] = enabled }
    }

    suspend fun setAutoRefresh(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.AUTO_REFRESH] = enabled }
    }

    suspend fun setOwned(campaignId: String, owned: Boolean) =
        updateOverride(campaignId) { it.copy(owned = owned) }

    suspend fun setRate(campaignId: String, rate: Double?) =
        updateOverride(campaignId) { it.copy(rate = rate) }

    suspend fun setBrand(campaignId: String, brand: String) =
        updateOverride(campaignId) { it.copy(brand = brand) }

    suspend fun setWelcatsu(campaignId: String, enabled: Boolean) =
        updateOverride(campaignId) { it.copy(welcatsu = enabled) }

    private suspend fun updateOverride(campaignId: String, transform: (CardOverride) -> CardOverride) {
        context.settingsDataStore.edit { prefs ->
            val current = prefs.decodeOverrides()
            val updated = current.toMutableMap()
            updated[campaignId] = transform(current[campaignId] ?: CardOverride())
            prefs[Keys.CARD_OVERRIDES] = json.encodeToString(updated)
        }
    }

    private fun Preferences.decodeOverrides(): Map<String, CardOverride> =
        this[Keys.CARD_OVERRIDES]
            ?.let { runCatching { json.decodeFromString<Map<String, CardOverride>>(it) }.getOrNull() }
            ?: emptyMap()
}
