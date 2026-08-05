package com.ktakjm.poikatsu.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * バックアップ JSON のスキーマ版(#50)。キーの追加だけなら旧ファイルは既定値で読めるので上げない。
 * 既存キーの意味を変える・消す等の非互換変更をしたときだけ上げ、[SettingsBackup.schemaVersion] が
 * これより新しいファイルは「新しいアプリで書き出したファイル」として読み込みを断る。
 */
const val SETTINGS_BACKUP_SCHEMA_VERSION = 1

/**
 * 設定のエクスポート/インポート(#50)で読み書きする JSON 1 ファイルの中身。
 *
 * Auto Backup(Google バックアップ)が効かない環境でも機種変更・再インストール時に登録情報を
 * 引き継げるようにするための明示的な保険。DataStore に入っているユーザー設定のうち、
 * **端末をまたいで持っていく意味があるものだけ**を持つ:
 * - 含める: 表示・通知・マイカード差分・国際ブランド・コード決済・マイエリア・カスタムカード/キャンペーン
 * - 含めない: 開発者向け設定(dataCommitRef / useTestData / useBundledData / developerMode)と
 *   通知済みキー。前者は端末ごとの検証用の一時状態で、引き継ぐと実データとの取り違えを招く。
 *   後者は設定値ではなく通知ジョブの内部状態
 *
 * キー名は同梱データの JSON(snake_case)ではなく camelCase。埋め込む [CustomCampaign] 等は
 * DataStore に camelCase で保存済みのモデルをそのまま使い回すため、ファイル全体で表記を揃える。
 */
@Serializable
data class SettingsBackup(
    /**
     * 既定値を持たせない = 必須キー。無関係な JSON を選ばれたときにパースを失敗させ、
     * 「全部既定値のバックアップ」として設定を消してしまう事故を防ぐ
     */
    val schemaVersion: Int,
    /** 書き出した日時(ISO-8601 のローカル日時)。復元前の確認ダイアログに出す表示専用の値 */
    val exportedAt: String = "",
    /** 書き出したアプリのバージョン(BuildConfig.VERSION_NAME)。不具合報告時の手掛かり */
    val appVersion: String = "",
    val themeMode: String = ThemeMode.SYSTEM.name,
    val dynamicColor: Boolean = true,
    val autoRefresh: Boolean = true,
    val notificationsEnabled: Boolean = false,
    val notificationTimeMinutes: Int = 8 * 60,
    val cardOverrides: Map<String, CardOverride> = emptyMap(),
    val enabledQrPaymentIds: Set<String> = emptySet(),
    val ownedBrands: Set<String> = emptySet(),
    val registeredAreas: List<RegisteredArea> = emptyList(),
    val customCards: List<CustomCard> = emptyList(),
    val customCampaigns: List<CustomCampaign> = emptyList(),
    val excludedStorePairs: List<ExcludedStorePair> = emptyList(),
)

/** 現在の設定をバックアップに写す。開発者向け設定は [SettingsBackup] の方針どおり落とす */
fun AppSettings.toBackup(exportedAt: String, appVersion: String): SettingsBackup = SettingsBackup(
    schemaVersion = SETTINGS_BACKUP_SCHEMA_VERSION,
    exportedAt = exportedAt,
    appVersion = appVersion,
    themeMode = themeMode.name,
    dynamicColor = dynamicColor,
    autoRefresh = autoRefresh,
    notificationsEnabled = notificationsEnabled,
    notificationTimeMinutes = notificationTimeMinutes,
    cardOverrides = cardOverrides,
    enabledQrPaymentIds = enabledQrPaymentIds,
    ownedBrands = ownedBrands,
    registeredAreas = registeredAreas,
    customCards = customCards,
    customCampaigns = customCampaigns,
    excludedStorePairs = excludedStorePairs,
)

/**
 * バックアップを設定へ戻す。手で書き換えられる可能性のあるファイルなので、範囲外の値は
 * 既定値へ丸め、id 重複(編集・削除の対象が二重になる)は先勝ちで落とす。
 * 開発者向け設定は含まれないため既定値のまま = 復元時も現在の値を書き換えない。
 */
fun SettingsBackup.toSettings(): AppSettings = AppSettings(
    themeMode = runCatching { ThemeMode.valueOf(themeMode) }.getOrDefault(ThemeMode.SYSTEM),
    dynamicColor = dynamicColor,
    autoRefresh = autoRefresh,
    notificationsEnabled = notificationsEnabled,
    notificationTimeMinutes = notificationTimeMinutes.coerceIn(0, 24 * 60 - 1),
    cardOverrides = cardOverrides,
    enabledQrPaymentIds = enabledQrPaymentIds,
    ownedBrands = ownedBrands,
    registeredAreas = registeredAreas.distinctBy { it.type to it.code },
    customCards = customCards.distinctBy { it.id },
    customCampaigns = customCampaigns.distinctBy { it.id }.map { it.normalized() },
    excludedStorePairs = excludedStorePairs
        .distinctBy { Triple(it.campaignId, it.merchantId, it.storeName) },
)

// 中身を目で追える(不具合報告時にそのまま読める)よう整形し、既定値も省略せず書き出す
private val backupJson = Json {
    prettyPrint = true
    encodeDefaults = true
    ignoreUnknownKeys = true
}

fun encodeSettingsBackup(backup: SettingsBackup): String = backupJson.encodeToString(backup)

/** バックアップ JSON をパースする。このアプリが書き出したものでなければ null */
fun decodeSettingsBackup(text: String): SettingsBackup? =
    runCatching { backupJson.decodeFromString<SettingsBackup>(text) }.getOrNull()
