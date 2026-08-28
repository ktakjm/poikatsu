package com.ktakjm.poikatsu.data

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.ktakjm.poikatsu.domain.DEFAULT_NOTIFY_TIME_MINUTES
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

// AppSettings ⇄ DataStore Preferences の変換(#90)。Android(Context/DataStore)に依存しない
// datastore-preferences-core だけで書き、SettingsCodecTest で往復を検証する。
// 設定項目を足すときは AppSettings / SettingsKeys / readSettings / writeSettings の 4 箇所を同期する
// (バックアップに載せるなら SettingsBackup.kt も)。漏れは SettingsCodecTest が捕まえる。

internal object SettingsKeys {
    val THEME = stringPreferencesKey("theme_mode")
    val DYNAMIC = booleanPreferencesKey("dynamic_color")
    val AUTO_REFRESH = booleanPreferencesKey("auto_refresh")
    val NOTIFICATIONS_ENABLED = booleanPreferencesKey("notifications_enabled")
    val NOTIFICATION_TIME = intPreferencesKey("notification_time_minutes")

    /** 通知済みキー(CampaignNotification.dedupKey)のリスト。AppSettings には載せない(設定値ではないため) */
    val NOTIFIED_CAMPAIGNS = stringPreferencesKey("notified_campaigns")
    val CARD_OVERRIDES = stringPreferencesKey("card_overrides")
    val DATA_COMMIT_REF = stringPreferencesKey("data_commit_ref")
    val USE_TEST_DATA = booleanPreferencesKey("use_test_data")
    val USE_BUNDLED_DATA = booleanPreferencesKey("use_bundled_data")
    val DEVELOPER_MODE = booleanPreferencesKey("developer_mode")
    val QR_ENABLED = stringPreferencesKey("qr_enabled")
    val OWNED_BRANDS = stringPreferencesKey("owned_brands")

    /** ポイント倍率(ウエル活等)を有効にしている通貨 id の Set(#39)。旧 CardOverride.welcatsu は移行せず廃止 */
    val ENABLED_POINT_MULTIPLIERS = stringPreferencesKey("enabled_point_multipliers")

    /** ユーザーが選んだポイント倍率。通貨 id → 倍率の Map(#83) */
    val POINT_MULTIPLIER_FACTORS = stringPreferencesKey("point_multiplier_factors")

    /** 会員になっているポイントプログラムの通貨 id の Set(#39) */
    val POINT_PROGRAM_MEMBERSHIPS = stringPreferencesKey("point_program_memberships")

    /** 1pt の価値(円)。通貨 id → 円の Map(#13) */
    val POINT_CURRENCY_VALUES = stringPreferencesKey("point_currency_values")

    /** 期間限定ポイントの残高・失効日。通貨 id → PointBalance の Map(#13) */
    val POINT_BALANCES = stringPreferencesKey("point_balances")
    // 旧キー "municipalities"(RegisteredMunicipality のリスト)は公開前のスキーマ刷新で廃止。
    // 移行せず捨てる(登録し直してもらう)
    val REGISTERED_AREAS = stringPreferencesKey("registered_areas")
    val CUSTOM_CARDS = stringPreferencesKey("custom_cards")
    val CUSTOM_CAMPAIGNS = stringPreferencesKey("custom_campaigns")

    /** テストデータ利用中(useTestData)のカスタムキャンペーン(#65)。通常側とはリストごと分離する */
    val CUSTOM_CAMPAIGNS_TEST = stringPreferencesKey("custom_campaigns_test")
    val EXCLUDED_STORE_PAIRS = stringPreferencesKey("excluded_store_pairs")

    /** テストデータ利用中(useTestData)の対象外ペア(#68)。通常側とはリストごと分離する */
    val EXCLUDED_STORE_PAIRS_TEST = stringPreferencesKey("excluded_store_pairs_test")
}

/** 保存 JSON の共通設定。知らないキーは読み捨てる(廃止フィールドの残存値を無視する。encodeDefaults=false) */
internal val settingsJson = Json { ignoreUnknownKeys = true }

/** JSON 文字列キーを読む。未設定・壊れた JSON は [default](その項目だけ既定値に落ち、他は影響しない) */
internal inline fun <reified T> Preferences.decodeJson(key: Preferences.Key<String>, default: T): T =
    this[key]?.let { runCatching { settingsJson.decodeFromString<T>(it) }.getOrNull() } ?: default

/** Preferences のスナップショットを AppSettings に組み立てる(settings Flow の変換) */
internal fun Preferences.readSettings(): AppSettings = AppSettings(
    themeMode = this[SettingsKeys.THEME]
        ?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
        ?: ThemeMode.SYSTEM,
    dynamicColor = this[SettingsKeys.DYNAMIC] ?: true,
    autoRefresh = this[SettingsKeys.AUTO_REFRESH] ?: true,
    notificationsEnabled = this[SettingsKeys.NOTIFICATIONS_ENABLED] ?: false,
    notificationTimeMinutes = this[SettingsKeys.NOTIFICATION_TIME] ?: DEFAULT_NOTIFY_TIME_MINUTES,
    cardOverrides = decodeJson(SettingsKeys.CARD_OVERRIDES, emptyMap()),
    dataCommitRef = this[SettingsKeys.DATA_COMMIT_REF] ?: "",
    useTestData = this[SettingsKeys.USE_TEST_DATA] ?: false,
    useBundledData = this[SettingsKeys.USE_BUNDLED_DATA] ?: false,
    developerMode = this[SettingsKeys.DEVELOPER_MODE] ?: false,
    enabledQrPaymentIds = decodeJson(SettingsKeys.QR_ENABLED, emptySet()),
    ownedBrands = decodeJson(SettingsKeys.OWNED_BRANDS, emptySet()),
    enabledPointMultipliers = decodeJson(SettingsKeys.ENABLED_POINT_MULTIPLIERS, emptySet()),
    pointMultiplierFactors = decodeJson(SettingsKeys.POINT_MULTIPLIER_FACTORS, emptyMap()),
    pointProgramMemberships = decodeJson(SettingsKeys.POINT_PROGRAM_MEMBERSHIPS, emptySet()),
    pointCurrencyValues = decodeJson(SettingsKeys.POINT_CURRENCY_VALUES, emptyMap()),
    pointBalances = decodeJson(SettingsKeys.POINT_BALANCES, emptyMap()),
    registeredAreas = decodeJson(SettingsKeys.REGISTERED_AREAS, emptyList()),
    customCards = decodeJson(SettingsKeys.CUSTOM_CARDS, emptyList()),
    customCampaigns = decodeJson(SettingsKeys.CUSTOM_CAMPAIGNS, emptyList()),
    customCampaignsTest = decodeJson(SettingsKeys.CUSTOM_CAMPAIGNS_TEST, emptyList()),
    excludedStorePairs = decodeJson(SettingsKeys.EXCLUDED_STORE_PAIRS, emptyList()),
    excludedStorePairsTest = decodeJson(SettingsKeys.EXCLUDED_STORE_PAIRS_TEST, emptyList()),
)

/**
 * AppSettings を Preferences へ全上書きで書く(バックアップ復元 #50)。バックアップが持つキーは
 * 既定値であっても書き込み、書き出し元の状態をそのまま再現する(マージはしない)。
 * 書かないもの: 開発者向け設定(dataCommitRef / useTestData / useBundledData / developerMode)・
 * 通知済みキー・テストデータ側のカスタムキャンペーン/対象外ペア(#65/#68)。いずれも端末ごとの
 * 一時状態でバックアップに含まれないため、現状維持にする。
 * 呼び出し側が 1 回の edit にまとめるので settings Flow の emission は 1 度で済み、rebuild も 1 回。
 */
internal fun MutablePreferences.writeSettings(settings: AppSettings) {
    this[SettingsKeys.THEME] = settings.themeMode.name
    this[SettingsKeys.DYNAMIC] = settings.dynamicColor
    this[SettingsKeys.AUTO_REFRESH] = settings.autoRefresh
    this[SettingsKeys.NOTIFICATIONS_ENABLED] = settings.notificationsEnabled
    this[SettingsKeys.NOTIFICATION_TIME] = settings.notificationTimeMinutes
    this[SettingsKeys.CARD_OVERRIDES] = settingsJson.encodeToString(settings.cardOverrides)
    this[SettingsKeys.QR_ENABLED] = settingsJson.encodeToString(settings.enabledQrPaymentIds)
    this[SettingsKeys.OWNED_BRANDS] = settingsJson.encodeToString(settings.ownedBrands)
    this[SettingsKeys.ENABLED_POINT_MULTIPLIERS] = settingsJson.encodeToString(settings.enabledPointMultipliers)
    this[SettingsKeys.POINT_MULTIPLIER_FACTORS] = settingsJson.encodeToString(settings.pointMultiplierFactors)
    this[SettingsKeys.POINT_PROGRAM_MEMBERSHIPS] = settingsJson.encodeToString(settings.pointProgramMemberships)
    this[SettingsKeys.POINT_CURRENCY_VALUES] = settingsJson.encodeToString(settings.pointCurrencyValues)
    this[SettingsKeys.POINT_BALANCES] = settingsJson.encodeToString(settings.pointBalances)
    this[SettingsKeys.REGISTERED_AREAS] = settingsJson.encodeToString(settings.registeredAreas)
    this[SettingsKeys.CUSTOM_CARDS] = settingsJson.encodeToString(settings.customCards)
    this[SettingsKeys.CUSTOM_CAMPAIGNS] = settingsJson.encodeToString(settings.customCampaigns)
    this[SettingsKeys.EXCLUDED_STORE_PAIRS] = settingsJson.encodeToString(settings.excludedStorePairs)
}
