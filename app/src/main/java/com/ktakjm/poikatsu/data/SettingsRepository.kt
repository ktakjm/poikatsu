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
 * ユーザーがカードごとに上書きする差分。payment_methods.json(カタログ=既定値)に重ねる。
 * 値が null/既定ならカタログの値を使う。
 */
@Serializable
data class CardOverride(
    /** このカードを所有しているか。null=既定(所有)。false で施策ごと判定から外す。 */
    val owned: Boolean? = null,
    /** 公式アプリ表示の実効還元率。null ならカタログの既定値。 */
    val rate: Double? = null,
    /** カードブランド(MUFG の Amex/Mastercard/Visa/JCB 等)。null ならカタログの既定値。 */
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
    /** データ取得先の Git ref(short commit hash 等)。空文字列は main を使う */
    val dataCommitRef: String = "",
    /** テストデータ(data-test/)を使うか。true なら取得パスが data/ → data-test/ に切り替わる */
    val useTestData: Boolean = false,
    /**
     * APK 同梱の assets を直接読むか(開発者向け)。true の間はキャッシュ・リモート取得を使わず、
     * ローカル編集した JSON を push なしで実機検証できる(反映には installDebug が必要)。
     */
    val useBundledData: Boolean = false,
    /** 利用中の QR 決済 ID。payment_methods.json の qr_payments カタログからユーザーが選択 */
    val enabledQrPaymentIds: Set<String> = emptySet(),
    /**
     * カタログのカード以外で保有しているカードブランド(例: "Visa")。イシュアー不問の
     * ブランド施策(campaigns.json の card_brand)の判定にだけ使う。選択肢は施策データ側の
     * card_brand 値から出すため、カタログ(payment_methods.json)にスキーマ追加は不要。
     */
    val ownedBrands: Set<String> = emptySet(),
    /** 登録エリア(自治体単体 or グループ)。キャンペーンタブの地域フィルタに使う */
    val registeredAreas: List<RegisteredArea> = emptyList(),
)

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore("settings")

/**
 * 設定の永続化(DataStore Preferences)。テーマ/データ取得とカード差分を保持する。
 * カード差分はカード id(payment_methods.json の cards.id)をキーにした Map を
 * JSON 文字列として1キーに格納する(キー数が可変でも Preferences のキーを増やさずに済む)。
 */
class SettingsRepository(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }

    private object Keys {
        val THEME = stringPreferencesKey("theme_mode")
        val DYNAMIC = booleanPreferencesKey("dynamic_color")
        val AUTO_REFRESH = booleanPreferencesKey("auto_refresh")
        val CARD_OVERRIDES = stringPreferencesKey("card_overrides")
        val DATA_COMMIT_REF = stringPreferencesKey("data_commit_ref")
        val USE_TEST_DATA = booleanPreferencesKey("use_test_data")
        val USE_BUNDLED_DATA = booleanPreferencesKey("use_bundled_data")
        val QR_ENABLED = stringPreferencesKey("qr_enabled")
        val OWNED_BRANDS = stringPreferencesKey("owned_brands")
        // 旧キー "municipalities"(RegisteredMunicipality のリスト)は公開前のスキーマ刷新で廃止。
        // 移行せず捨てる(登録し直してもらう)
        val REGISTERED_AREAS = stringPreferencesKey("registered_areas")
    }

    val settings: Flow<AppSettings> = context.settingsDataStore.data.map { prefs ->
        AppSettings(
            themeMode = prefs[Keys.THEME]
                ?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
                ?: ThemeMode.SYSTEM,
            dynamicColor = prefs[Keys.DYNAMIC] ?: true,
            autoRefresh = prefs[Keys.AUTO_REFRESH] ?: true,
            cardOverrides = prefs.decodeOverrides(),
            dataCommitRef = prefs[Keys.DATA_COMMIT_REF] ?: "",
            useTestData = prefs[Keys.USE_TEST_DATA] ?: false,
            useBundledData = prefs[Keys.USE_BUNDLED_DATA] ?: false,
            enabledQrPaymentIds = prefs.decodeQrEnabled(),
            ownedBrands = prefs.decodeOwnedBrands(),
            registeredAreas = prefs.decodeRegisteredAreas(),
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

    suspend fun setDataCommitRef(ref: String) {
        context.settingsDataStore.edit { it[Keys.DATA_COMMIT_REF] = ref.trim() }
    }

    suspend fun setUseTestData(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.USE_TEST_DATA] = enabled }
    }

    suspend fun setUseBundledData(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.USE_BUNDLED_DATA] = enabled }
    }

    suspend fun setOwned(cardId: String, owned: Boolean) =
        updateOverride(cardId) { it.copy(owned = owned) }

    suspend fun setRate(cardId: String, rate: Double?) =
        updateOverride(cardId) { it.copy(rate = rate) }

    suspend fun setBrand(cardId: String, brand: String) =
        updateOverride(cardId) { it.copy(brand = brand) }

    suspend fun setWelcatsu(cardId: String, enabled: Boolean) =
        updateOverride(cardId) { it.copy(welcatsu = enabled) }

    private suspend fun updateOverride(cardId: String, transform: (CardOverride) -> CardOverride) {
        context.settingsDataStore.edit { prefs ->
            val current = prefs.decodeOverrides()
            val updated = current.toMutableMap()
            updated[cardId] = transform(current[cardId] ?: CardOverride())
            prefs[Keys.CARD_OVERRIDES] = json.encodeToString(updated)
        }
    }

    suspend fun setQrEnabled(id: String, enabled: Boolean) {
        context.settingsDataStore.edit { prefs ->
            val current = prefs.decodeQrEnabled().toMutableSet()
            if (enabled) current.add(id) else current.remove(id)
            prefs[Keys.QR_ENABLED] = json.encodeToString(current)
        }
    }

    suspend fun setBrandOwned(brand: String, owned: Boolean) {
        context.settingsDataStore.edit { prefs ->
            val current = prefs.decodeOwnedBrands().toMutableSet()
            if (owned) current.add(brand) else current.remove(brand)
            prefs[Keys.OWNED_BRANDS] = json.encodeToString(current)
        }
    }

    suspend fun addRegisteredArea(area: RegisteredArea) {
        context.settingsDataStore.edit { prefs ->
            val current = prefs.decodeRegisteredAreas().toMutableList()
            if (current.none { it.type == area.type && it.code == area.code }) {
                current.add(area)
            }
            prefs[Keys.REGISTERED_AREAS] = json.encodeToString(current)
        }
    }

    suspend fun removeRegisteredArea(area: RegisteredArea) {
        context.settingsDataStore.edit { prefs ->
            val current = prefs.decodeRegisteredAreas()
                .filter { it.type != area.type || it.code != area.code }
            prefs[Keys.REGISTERED_AREAS] = json.encodeToString(current)
        }
    }

    private fun Preferences.decodeOverrides(): Map<String, CardOverride> =
        this[Keys.CARD_OVERRIDES]
            ?.let { runCatching { json.decodeFromString<Map<String, CardOverride>>(it) }.getOrNull() }
            ?: emptyMap()

    private fun Preferences.decodeQrEnabled(): Set<String> =
        this[Keys.QR_ENABLED]
            ?.let { runCatching { json.decodeFromString<Set<String>>(it) }.getOrNull() }
            ?: emptySet()

    private fun Preferences.decodeOwnedBrands(): Set<String> =
        this[Keys.OWNED_BRANDS]
            ?.let { runCatching { json.decodeFromString<Set<String>>(it) }.getOrNull() }
            ?: emptySet()

    private fun Preferences.decodeRegisteredAreas(): List<RegisteredArea> =
        this[Keys.REGISTERED_AREAS]
            ?.let { runCatching { json.decodeFromString<List<RegisteredArea>>(it) }.getOrNull() }
            ?: emptyList()
}
