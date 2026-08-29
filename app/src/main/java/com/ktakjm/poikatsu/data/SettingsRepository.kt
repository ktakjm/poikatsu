package com.ktakjm.poikatsu.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.ktakjm.poikatsu.domain.clampNotifyTimeMinutes
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore("settings")

/**
 * 設定の永続化(DataStore Preferences)。テーマ/データ取得とカード差分を保持する。
 * カード差分はカード id(payment_methods.json の cards.id)をキーにした Map を
 * JSON 文字列として1キーに格納する(キー数が可変でも Preferences のキーを増やさずに済む)。
 * キー定義と AppSettings ⇄ Preferences の変換は SettingsCodec.kt(純 JVM。往復テストあり)、
 * ここは DataStore への個別更新(setter)だけを持つ。
 */
class SettingsRepository(private val context: Context) {


    val settings: Flow<AppSettings> = context.settingsDataStore.data.map { it.readSettings() }

    /** 現在の設定を1回だけ読む(バックアップの書き出し用) */
    suspend fun current(): AppSettings = settings.first()

    /**
     * バックアップから設定を復元する(#50)。方針は全上書き: バックアップが持つキーは
     * 既定値であっても書き込み、書き出し元の状態をそのまま再現する(マージはしない)。
     * バックアップに含まれない開発者向け設定・通知済みキーは触らない。
     * 1回の edit にまとめるので settings Flow の emission は1度で済み、rebuild も1回。
     */
    suspend fun importSettings(settings: AppSettings) {
        context.settingsDataStore.edit { it.writeSettings(settings) }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.settingsDataStore.edit { it[SettingsKeys.THEME] = mode.name }
    }

    suspend fun setDynamicColor(enabled: Boolean) {
        context.settingsDataStore.edit { it[SettingsKeys.DYNAMIC] = enabled }
    }

    suspend fun setAutoRefresh(enabled: Boolean) {
        context.settingsDataStore.edit { it[SettingsKeys.AUTO_REFRESH] = enabled }
    }

    suspend fun setShowIneligibleStorePins(enabled: Boolean) {
        context.settingsDataStore.edit { it[SettingsKeys.SHOW_INELIGIBLE_STORE_PINS] = enabled }
    }

    suspend fun setNotificationsEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[SettingsKeys.NOTIFICATIONS_ENABLED] = enabled }
    }

    suspend fun setNotificationTime(minutesOfDay: Int) {
        context.settingsDataStore.edit {
            it[SettingsKeys.NOTIFICATION_TIME] = clampNotifyTimeMinutes(minutesOfDay)
        }
    }

    /** 通知済みキーの読み出し。通知ジョブの再通知抑止に使う */
    suspend fun notifiedCampaignKeys(): Set<String> =
        context.settingsDataStore.data.first().decodeJson(SettingsKeys.NOTIFIED_CAMPAIGNS, emptyList<String>()).toSet()

    /**
     * 通知済みキーを空にする(開発者向け)。同じキャンペーンで通知を繰り返し検証するための操作で、
     * 消しても失われるのは「二度通知しない」記録だけ(設定値ではない)。
     */
    suspend fun clearNotifiedCampaignKeys() {
        context.settingsDataStore.edit { it.remove(SettingsKeys.NOTIFIED_CAMPAIGNS) }
    }

    /**
     * 通知済みキーを追記する。施策の入れ替わりで増え続けないよう直近 [NOTIFIED_KEYS_MAX] 件に
     * 丸める(古いキーの施策はとうに通知ウィンドウ外で、消しても再通知は起きない)。
     */
    suspend fun addNotifiedCampaignKeys(keys: Collection<String>) {
        if (keys.isEmpty()) return
        context.settingsDataStore.edit { prefs ->
            val updated = (prefs.decodeJson(SettingsKeys.NOTIFIED_CAMPAIGNS, emptyList<String>()) + keys).distinct().takeLast(NOTIFIED_KEYS_MAX)
            prefs[SettingsKeys.NOTIFIED_CAMPAIGNS] = settingsJson.encodeToString(updated)
        }
    }

    suspend fun setDataCommitRef(ref: String) {
        context.settingsDataStore.edit { it[SettingsKeys.DATA_COMMIT_REF] = ref.trim() }
    }

    suspend fun setUseTestData(enabled: Boolean) {
        context.settingsDataStore.edit { it[SettingsKeys.USE_TEST_DATA] = enabled }
    }

    suspend fun setUseBundledData(enabled: Boolean) {
        context.settingsDataStore.edit { it[SettingsKeys.USE_BUNDLED_DATA] = enabled }
    }

    suspend fun setDeveloperMode(enabled: Boolean) {
        context.settingsDataStore.edit { it[SettingsKeys.DEVELOPER_MODE] = enabled }
    }

    /**
     * 開発者モード OFF: 開発者向け設定を既定値に戻し、モード自体も OFF にする。
     * 1回の edit にまとめることで settings Flow の emission が1度で済み、
     * 変更検知(ref/testData/bundled → refresh(force=true))が二重に走らない。
     */
    suspend fun resetDeveloperSettings() {
        context.settingsDataStore.edit { prefs ->
            prefs.remove(SettingsKeys.DATA_COMMIT_REF)
            prefs.remove(SettingsKeys.USE_TEST_DATA)
            prefs.remove(SettingsKeys.USE_BUNDLED_DATA)
            prefs[SettingsKeys.DEVELOPER_MODE] = false
        }
    }

    suspend fun setOwned(cardId: String, owned: Boolean) =
        updateOverride(cardId) { it.copy(owned = owned) }

    suspend fun setRate(cardId: String, rate: Double?) =
        updateOverride(cardId) { it.copy(rate = rate) }

    suspend fun setBrand(cardId: String, brand: String) =
        updateOverride(cardId) { it.copy(brand = brand) }

    suspend fun setCardClass(cardId: String, cardClass: String?) =
        updateOverride(cardId) { it.copy(cardClass = cardClass) }

    private suspend fun updateOverride(cardId: String, transform: (CardOverride) -> CardOverride) {
        context.settingsDataStore.edit { prefs ->
            val current = prefs.decodeJson(SettingsKeys.CARD_OVERRIDES, emptyMap<String, CardOverride>())
            val updated = current.toMutableMap()
            updated[cardId] = transform(current[cardId] ?: CardOverride())
            prefs[SettingsKeys.CARD_OVERRIDES] = settingsJson.encodeToString(updated)
        }
    }

    suspend fun setQrEnabled(id: String, enabled: Boolean) {
        context.settingsDataStore.edit { prefs ->
            val current = prefs.decodeJson(SettingsKeys.QR_ENABLED, emptySet<String>()).toMutableSet()
            if (enabled) current.add(id) else current.remove(id)
            prefs[SettingsKeys.QR_ENABLED] = settingsJson.encodeToString(current)
        }
    }

    suspend fun setBrandOwned(brand: String, owned: Boolean) {
        context.settingsDataStore.edit { prefs ->
            val current = prefs.decodeJson(SettingsKeys.OWNED_BRANDS, emptySet<String>()).toMutableSet()
            if (owned) current.add(brand) else current.remove(brand)
            prefs[SettingsKeys.OWNED_BRANDS] = settingsJson.encodeToString(current)
        }
    }

    /**
     * ポイント倍率(ウエル活等)の有効/無効。通貨単位(#39。旧カード単位の setWelcatsu を置換)。
     * 倍率グループ(#84)を持つ通貨は同一グループ全員の id をまとめて書くため Set で受ける
     * (呼び出し側が multiplierToggleIds で解決する)
     */
    suspend fun setPointMultipliersEnabled(currencyIds: Set<String>, enabled: Boolean) {
        context.settingsDataStore.edit { prefs ->
            val current = prefs.decodeJson(SettingsKeys.ENABLED_POINT_MULTIPLIERS, emptySet<String>()).toMutableSet()
            if (enabled) current.addAll(currencyIds) else current.removeAll(currencyIds)
            prefs[SettingsKeys.ENABLED_POINT_MULTIPLIERS] = settingsJson.encodeToString(current)
        }
    }

    /**
     * ポイント倍率の選択(#83)。選択肢(factor_options)を持つ通貨だけで意味を持ち、
     * null で既定(カタログの factor = 選択肢の最小値)に戻す。倍率の ON/OFF とは直交で、
     * OFF のまま選び直せる(次に ON にしたとき選択が残っている方が自然)
     */
    suspend fun setPointMultiplierFactor(currencyId: String, factor: Double?) {
        context.settingsDataStore.edit { prefs ->
            val current = prefs.decodeJson(SettingsKeys.POINT_MULTIPLIER_FACTORS, emptyMap<String, Double>()).toMutableMap()
            if (factor == null) current.remove(currencyId) else current[currencyId] = factor
            prefs[SettingsKeys.POINT_MULTIPLIER_FACTORS] = settingsJson.encodeToString(current)
        }
    }

    /** ポイントプログラムの会員登録(#39)。提示型施策(point_program_id)の判定フィルタに効く */
    suspend fun setPointProgramMembership(currencyId: String, member: Boolean) {
        context.settingsDataStore.edit { prefs ->
            val current = prefs.decodeJson(SettingsKeys.POINT_PROGRAM_MEMBERSHIPS, emptySet<String>()).toMutableSet()
            if (member) current.add(currencyId) else current.remove(currencyId)
            prefs[SettingsKeys.POINT_PROGRAM_MEMBERSHIPS] = settingsJson.encodeToString(current)
        }
    }

    /** 1pt の価値(円)。通貨単位(#13)。null で既定(カタログ default または 1.0 円)に戻す */
    suspend fun setPointCurrencyValue(currencyId: String, value: Double?) {
        context.settingsDataStore.edit { prefs ->
            val current = prefs.decodeJson(SettingsKeys.POINT_CURRENCY_VALUES, emptyMap<String, Double>()).toMutableMap()
            if (value == null) current.remove(currencyId) else current[currencyId] = value
            prefs[SettingsKeys.POINT_CURRENCY_VALUES] = settingsJson.encodeToString(current)
        }
    }

    /** 期間限定ポイントの残高・失効日。null で削除(#13) */
    suspend fun setPointBalance(currencyId: String, balance: PointBalance?) {
        context.settingsDataStore.edit { prefs ->
            val current = prefs.decodeJson(SettingsKeys.POINT_BALANCES, emptyMap<String, PointBalance>()).toMutableMap()
            if (balance == null) current.remove(currencyId) else current[currencyId] = balance
            prefs[SettingsKeys.POINT_BALANCES] = settingsJson.encodeToString(current)
        }
    }

    suspend fun addRegisteredArea(area: RegisteredArea) {
        context.settingsDataStore.edit { prefs ->
            val current = prefs.decodeJson(SettingsKeys.REGISTERED_AREAS, emptyList<RegisteredArea>()).toMutableList()
            if (current.none { it.type == area.type && it.code == area.code }) {
                current.add(area)
            }
            prefs[SettingsKeys.REGISTERED_AREAS] = settingsJson.encodeToString(current)
        }
    }

    suspend fun removeRegisteredArea(area: RegisteredArea) {
        context.settingsDataStore.edit { prefs ->
            val current = prefs.decodeJson(SettingsKeys.REGISTERED_AREAS, emptyList<RegisteredArea>())
                .filter { it.type != area.type || it.code != area.code }
            prefs[SettingsKeys.REGISTERED_AREAS] = settingsJson.encodeToString(current)
        }
    }

    suspend fun addCustomCard(card: CustomCard) {
        context.settingsDataStore.edit { prefs ->
            prefs[SettingsKeys.CUSTOM_CARDS] = settingsJson.encodeToString(prefs.decodeJson(SettingsKeys.CUSTOM_CARDS, emptyList<CustomCard>()) + card)
        }
    }

    suspend fun updateCustomCard(card: CustomCard) {
        context.settingsDataStore.edit { prefs ->
            val updated = prefs.decodeJson(SettingsKeys.CUSTOM_CARDS, emptyList<CustomCard>()).map { if (it.id == card.id) card else it }
            prefs[SettingsKeys.CUSTOM_CARDS] = settingsJson.encodeToString(updated)
        }
    }

    suspend fun removeCustomCard(id: String) {
        context.settingsDataStore.edit { prefs ->
            val updated = prefs.decodeJson(SettingsKeys.CUSTOM_CARDS, emptyList<CustomCard>()).filterNot { it.id == id }
            prefs[SettingsKeys.CUSTOM_CARDS] = settingsJson.encodeToString(updated)
        }
    }

    /**
     * カスタムキャンペーンの読み書き先キー。現在のデータモード(useTestData)側を返す(#65)。
     * 通常/テストで参照する ID 体系(payment_methods / merchants)が異なるためリストごと分離し、
     * モード判定は同じトランザクション内の値で行う(トグル直後の書き込みと競合しない)。
     */
    private fun Preferences.customCampaignsKey() =
        if (this[SettingsKeys.USE_TEST_DATA] == true) SettingsKeys.CUSTOM_CAMPAIGNS_TEST else SettingsKeys.CUSTOM_CAMPAIGNS

    suspend fun addCustomCampaign(campaign: CustomCampaign) {
        context.settingsDataStore.edit { prefs ->
            val key = prefs.customCampaignsKey()
            prefs[key] = settingsJson.encodeToString(prefs.decodeJson(key, emptyList<CustomCampaign>()) + campaign)
        }
    }

    suspend fun updateCustomCampaign(campaign: CustomCampaign) {
        context.settingsDataStore.edit { prefs ->
            val key = prefs.customCampaignsKey()
            val updated = prefs.decodeJson(key, emptyList<CustomCampaign>()).map { if (it.id == campaign.id) campaign else it }
            prefs[key] = settingsJson.encodeToString(updated)
        }
    }

    suspend fun removeCustomCampaign(id: String) {
        context.settingsDataStore.edit { prefs ->
            val key = prefs.customCampaignsKey()
            val updated = prefs.decodeJson(key, emptyList<CustomCampaign>()).filterNot { it.id == id }
            prefs[key] = settingsJson.encodeToString(updated)
        }
    }

    /** 対象外ペアの読み書き先キー。カスタムキャンペーンと同じモード分離(#68。[customCampaignsKey] 参照) */
    private fun Preferences.excludedStorePairsKey() =
        if (this[SettingsKeys.USE_TEST_DATA] == true) SettingsKeys.EXCLUDED_STORE_PAIRS_TEST else SettingsKeys.EXCLUDED_STORE_PAIRS

    /** 対象外ペアの登録。同じ (施策, 店舗名) の再登録は無視する */
    suspend fun addExcludedStorePair(pair: ExcludedStorePair) {
        context.settingsDataStore.edit { prefs ->
            val key = prefs.excludedStorePairsKey()
            val current = prefs.decodeJson(key, emptyList<ExcludedStorePair>())
            if (current.none { it.sameTarget(pair) }) {
                prefs[key] = settingsJson.encodeToString(current + pair)
            }
        }
    }

    suspend fun removeExcludedStorePairs(pairs: Collection<ExcludedStorePair>) {
        if (pairs.isEmpty()) return
        context.settingsDataStore.edit { prefs ->
            val key = prefs.excludedStorePairsKey()
            val updated = prefs.decodeJson(key, emptyList<ExcludedStorePair>())
                .filterNot { current -> pairs.any { it.sameTarget(current) } }
            prefs[key] = settingsJson.encodeToString(updated)
        }
    }

    private companion object {
        const val NOTIFIED_KEYS_MAX = 200
    }
}
