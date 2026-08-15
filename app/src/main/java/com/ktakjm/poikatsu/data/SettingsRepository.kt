package com.ktakjm.poikatsu.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
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
    /** 国際ブランド(MUFG の Amex/Mastercard/Visa/JCB 等)。null ならカタログの既定値。 */
    val brand: String? = null,
    /** ウエル活(ポイント価値 ×倍率)で表示するか。 */
    val welcatsu: Boolean = false,
    /** カードクラス(カタログ card_classes の id。JCB W/S 等)。null ならカタログ先頭(保守側)。 */
    val cardClass: String? = null,
    /** 1pt の価値(円)。null ならカタログ(point_value.default)の既定値。 */
    val pointValue: Double? = null,
)

/**
 * ユーザーが登録するカタログ外カード(カスタムカード)。カタログ(payment_methods.json)未収録の
 * カードを、カスタムキャンペーン(#7)の紐付け先エンティティとして持つ。カタログとは分離して
 * DataStore に保存する(同梱データの JSON は汎用に保つ方針のため)。
 * 後日そのカードがカタログに収録された場合は、カスタム側を手動削除して乗り換える運用。
 */
@Serializable
data class CustomCard(
    /** 「custom:<UUID>」形式。カタログの cards.id と衝突しない採番 */
    val id: String,
    val name: String,
    /** 識別色(#RRGGBB)。ロゴは使わない方針のため色で識別する。null は未選択= [DEFAULT_COLOR] */
    val color: String? = null,
    /** 国際ブランド(例: "Visa")。空文字は未選択。イシュアー不問のブランド施策(card_brand)に一致する */
    val brand: String = "",
) {
    companion object {
        const val ID_PREFIX = "custom:"

        /** 色未選択時のデフォルト色(ニュートラルグレー。どのカタログ発行体色とも紛れにくい) */
        const val DEFAULT_COLOR = "#9E9E9E"
    }
}

/**
 * カスタムキャンペーンの紐付け先決済手段1件。cardId / qrPaymentId / cardBrand のいずれか
 * 1つだけが入る(campaigns.json の card_id / payment_method_id / card_brand の排他と同じ)。
 */
@Serializable
data class CustomPayment(
    /** カード(カタログ cards.id または CustomCard.id) */
    val cardId: String? = null,
    /** QR 決済(カタログ qr_payments.id) */
    val qrPaymentId: String? = null,
    /** ブランド指定(カード会社不問。card_brands の name。Amex 会員限定施策等) */
    val cardBrand: String? = null,
)

/**
 * カスタムキャンペーンの業態(看板)単位の選択1件(#60)。系列まるごとではなく
 * 「杏林堂薬局だけ」のような対象を表す。bannerId は merchants.json の banners[].id
 * (代表看板は merchant.id)。変換時に banner_ids 付きの MerchantRule になる。
 */
@Serializable
data class BannerSelection(
    val merchantId: String,
    val bannerId: String,
)

/**
 * ユーザーが登録するカスタムキャンペーン(#7)。会員ポータル限定クーポン等、同梱データ
 * (campaigns.json)で配信できない施策を本人が登録し、同梱施策と同様に判定・表示する。
 * 判定エンジンへは domain の変換(toCampaigns / buildCustomMerchants)で Campaign / Merchant に
 * 写して渡すため、エンジン側にカスタム専用の分岐は無い。複数決済手段は変換時に決済ごとの
 * Campaign へ展開される(1登録=1「率・条件」。決済ごとに率が異なる施策は別登録する)。
 */
@Serializable
data class CustomCampaign(
    /** 「custom:<UUID>」形式。同梱 campaigns.json の id と衝突しない採番 */
    val id: String,
    /** キャンペーン名(おトクタブ・判定カードのタイトル) */
    val name: String,
    /** 旧スキーマ(単一決済)の残置。読み込み時に [normalized] で [payments] へ折り畳む */
    val cardId: String? = null,
    /** 旧スキーマ(単一決済)の残置。読み込み時に [normalized] で [payments] へ折り畳む */
    val qrPaymentId: String? = null,
    /** 紐付け先の決済手段(1件以上)。決済ごとに Campaign へ展開される */
    val payments: List<CustomPayment> = emptyList(),
    /** 対象チェーン(merchants.json の id)。系列まるごとの選択。[storeNames] と併用可 */
    val merchantIds: List<String> = emptyList(),
    /** 業態(看板)単位の選択(#60)。同じ merchant の [merchantIds](系列まるごと)とは排他で保存する */
    val bannerSelections: List<BannerSelection> = emptyList(),
    /** カタログに無い店の自由入力店名。店名の部分一致でお店・地図タブにマッチさせる */
    val storeNames: List<String> = emptyList(),
    /**
     * 全店舗対象(#44)。決済手段が使える全加盟店対象の施策(抽選会等)で、お店を列挙できないもの。
     * true のとき [merchantIds] / [storeNames] は持たせず、変換時に store_scope=external の
     * 「おトクタブ専用施策」になる(お店・地図タブの判定には出ない)
     */
    val allStores: Boolean = false,
    /** 特典の型: "rebate"(後日還元) | "discount"(即時割引) | "lottery"(抽選) */
    val benefitType: String = "rebate",
    /** 還元率(%)。率で表せない特典は null にして [note] に書く */
    val rate: Double? = null,
    /** 定額特典(円)。「500円引き」等 */
    val discountAmount: Int? = null,
    /** 対象商品限定のラベル(例: "対象の化粧品のみ")。非空なら最良比較から分離+商品限定バッジ */
    val productScope: String? = null,
    /**
     * 提示のみで受けられる特典か(campaigns.json の presentation_only と同じ意味。#80)。
     * true なら最良比較から分離+「提示のみ」バッジ+支払いは別でも対象の注記
     */
    val presentationOnly: Boolean = false,
    /** 対象・特典のメモ(判定カードの「対象」に表示)。改行区切りで複数項目 */
    val note: String = "",
    /** 対象外・注意のメモ(warning 面で表示)。改行区切りで複数項目 */
    val ineligibleNote: String = "",
    /** 開始日(YYYY-MM-DD)。null は開始済み扱い */
    val startDate: String? = null,
    /** 終了日(YYYY-MM-DD)。null は [mayEndEarly] が無ければ常設(おトクタブの常設セクション) */
    val endDate: String? = null,
    /**
     * 早期終了があり得るか(campaigns.json の may_end_early と同じ意味)。終了日の有無と直交:
     * [endDate] あり+true=期限より早く終わり得る注記、[endDate] なし+true=「終了日未定」の
     * 期間限定扱い(予告なく終了の注記)、[endDate] なし+false=常設扱い
     */
    val mayEndEarly: Boolean = false,
    /** 対象曜日("MON"〜"SUN")。[daysOfMonth] と排他(campaigns.json の recurrence と同じ) */
    val daysOfWeek: List<String> = emptyList(),
    /** 対象日(1〜31) */
    val daysOfMonth: List<Int> = emptyList(),
    /** 最低購入額(円) */
    val minPurchase: Int? = null,
    /** 最低購入額の集計単位: "transaction"(1決済ごと) | "period_total"(期間合計) */
    val minPurchaseScope: String = MIN_PURCHASE_SCOPE_TRANSACTION,
    /** 利用回数上限(「お一人様N回まで」表示) */
    val usageLimit: Int? = null,
    /** 還元上限: 1決済あたり(円) */
    val perTransactionCap: Int? = null,
    /** 還元上限: 期間合計(円) */
    val periodTotalCap: Int? = null,
    /** 上限の補足メモ */
    val capNote: String? = null,
    /** 詳細ページ URL(会員ポータル等。判定カードの「詳細を見る」ボタン) */
    val detailUrl: String? = null,
) {
    /**
     * 旧スキーマ(単一の cardId / qrPaymentId)を payments へ折り畳む。読み込み時に必ず通し、
     * 以降のコードは payments だけを見ればよい状態にする。
     */
    fun normalized(): CustomCampaign =
        if (payments.isNotEmpty() || (cardId == null && qrPaymentId == null)) this
        else copy(
            payments = listOf(CustomPayment(cardId = cardId, qrPaymentId = qrPaymentId)),
            cardId = null,
            qrPaymentId = null,
        )

    companion object {
        const val ID_PREFIX = "custom:"
    }
}

/**
 * ユーザーが「このお店ではこの施策は対象外だった」と登録した (施策, 店舗) ペア(#63)。
 * チェーン全店施策のうち生活圏の特定店舗だけ対象外(例: SMCC 7% のサイゼリヤ一部店舗)を
 * 判定・地図から取り除くために使う。
 *
 * 店舗は一意 ID を持たない(YOLP の ID は保持しない方針)ため、storeName は
 * 「ユーザーが確認・編集した店舗名の生文字列」を保存し、照合は毎回
 * JudgmentEngine.normalizedBranch(チェーン識別子を剥がした支店名)で行う。
 * POI 名をそのまま永続化しない(プリフィル後にユーザーが確定した申告データとして扱う)のは
 * YOLP 規約(店舗データの永続キャッシュ禁止。docs/map-data-stack.md)への配慮。座標も保存しない。
 */
@Serializable
data class ExcludedStorePair(
    /** campaigns.json(またはカスタムキャンペーン展開後)の施策 id */
    val campaignId: String,
    /** merchants.json の系列 id。照合の前提(normalizedBranch は merchant のキーに依存する) */
    val merchantId: String,
    /** ユーザーが確認・編集した店舗名(生文字列)。照合時に正規化する */
    val storeName: String,
    /** 登録日(YYYY-MM-DD)。管理一覧の表示用 */
    val registeredDate: String = "",
) {
    /** 重複登録の判定キー(同じ施策×同じ店舗名は 1 件に保つ。登録日は同一性に含めない) */
    fun sameTarget(other: ExcludedStorePair): Boolean =
        campaignId == other.campaignId && merchantId == other.merchantId && storeName == other.storeName
}

/** アプリ全体の設定スナップショット。DataStore から1本の Flow で配る。 */
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val autoRefresh: Boolean = true,
    /** キャンペーン通知(#6)。ON の間、日次の通知ジョブ(CampaignNotificationWorker)を登録する */
    val notificationsEnabled: Boolean = false,
    /** 通知時刻(0時からの分。既定 8:00)。15分刻みは設定 UI 側の制約で、保存値は分単位で持つ */
    val notificationTimeMinutes: Int = 8 * 60,
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
    /**
     * 開発者モード。ON の間だけ設定画面に「開発者向け設定」(dataCommitRef / useTestData /
     * useBundledData)への導線を出す。OFF 操作時は [SettingsRepository.resetDeveloperSettings] で
     * 開発者向け設定を一括で既定値に戻す(戻し忘れによる実データとの取り違え防止)。
     */
    val developerMode: Boolean = false,
    /** 利用中の QR 決済 ID。payment_methods.json の qr_payments カタログからユーザーが選択 */
    val enabledQrPaymentIds: Set<String> = emptySet(),
    /**
     * カタログのカード以外で保有している国際ブランド(例: "Visa")。イシュアー不問の
     * ブランド施策(campaigns.json の card_brand)の判定にだけ使う。選択肢は施策データ側の
     * card_brand 値から出すため、カタログ(payment_methods.json)にスキーマ追加は不要。
     */
    val ownedBrands: Set<String> = emptySet(),
    /** 登録エリア(自治体単体 or グループ)。おトクタブの地域フィルタに使う */
    val registeredAreas: List<RegisteredArea> = emptyList(),
    /** カタログ外のカスタムカード(登録順) */
    val customCards: List<CustomCard> = emptyList(),
    /**
     * ユーザー登録のカスタムキャンペーン(登録順)。通常データ(data/)前提の本体。
     * 参照する ID 体系(payment_methods / merchants)がテストデータとは異なるため、
     * テストデータ利用中の登録は [customCampaignsTest] に分けて保持する(#65)。
     * 表示・判定には現在のモード側を返す [activeCustomCampaigns] を使う。
     */
    val customCampaigns: List<CustomCampaign> = emptyList(),
    /** テストデータ(data-test/)前提のカスタムキャンペーン(#65)。バックアップには含めない */
    val customCampaignsTest: List<CustomCampaign> = emptyList(),
    /**
     * ユーザーが対象外として登録した (施策, 店舗) ペア(#63。登録順)。通常データ(data/)前提の本体。
     * campaignId / merchantId でデータセットの ID を参照するため、カスタムキャンペーン(#65)と同様に
     * テストデータ利用中の登録は [excludedStorePairsTest] に分けて保持する(#68)。
     * 表示・判定には現在のモード側を返す [activeExcludedStorePairs] を使う。
     */
    val excludedStorePairs: List<ExcludedStorePair> = emptyList(),
    /** テストデータ(data-test/)前提の対象外ペア(#68)。バックアップには含めない */
    val excludedStorePairsTest: List<ExcludedStorePair> = emptyList(),
) {
    /** 現在のデータモード(useTestData)に対応するカスタムキャンペーン。表示・判定・通知はこちらを使う(#65) */
    val activeCustomCampaigns: List<CustomCampaign>
        get() = if (useTestData) customCampaignsTest else customCampaigns

    /** 現在のデータモード(useTestData)に対応する対象外ペア。表示・判定はこちらを使う(#68) */
    val activeExcludedStorePairs: List<ExcludedStorePair>
        get() = if (useTestData) excludedStorePairsTest else excludedStorePairs
}

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

    val settings: Flow<AppSettings> = context.settingsDataStore.data.map { prefs ->
        AppSettings(
            themeMode = prefs[Keys.THEME]
                ?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
                ?: ThemeMode.SYSTEM,
            dynamicColor = prefs[Keys.DYNAMIC] ?: true,
            autoRefresh = prefs[Keys.AUTO_REFRESH] ?: true,
            notificationsEnabled = prefs[Keys.NOTIFICATIONS_ENABLED] ?: false,
            notificationTimeMinutes = prefs[Keys.NOTIFICATION_TIME] ?: 8 * 60,
            cardOverrides = prefs.decodeOverrides(),
            dataCommitRef = prefs[Keys.DATA_COMMIT_REF] ?: "",
            useTestData = prefs[Keys.USE_TEST_DATA] ?: false,
            useBundledData = prefs[Keys.USE_BUNDLED_DATA] ?: false,
            developerMode = prefs[Keys.DEVELOPER_MODE] ?: false,
            enabledQrPaymentIds = prefs.decodeQrEnabled(),
            ownedBrands = prefs.decodeOwnedBrands(),
            registeredAreas = prefs.decodeRegisteredAreas(),
            customCards = prefs.decodeCustomCards(),
            customCampaigns = prefs.decodeCustomCampaigns(Keys.CUSTOM_CAMPAIGNS),
            customCampaignsTest = prefs.decodeCustomCampaigns(Keys.CUSTOM_CAMPAIGNS_TEST),
            excludedStorePairs = prefs.decodeExcludedStorePairs(Keys.EXCLUDED_STORE_PAIRS),
            excludedStorePairsTest = prefs.decodeExcludedStorePairs(Keys.EXCLUDED_STORE_PAIRS_TEST),
        )
    }

    /** 現在の設定を1回だけ読む(バックアップの書き出し用) */
    suspend fun current(): AppSettings = settings.first()

    /**
     * バックアップから設定を復元する(#50)。方針は全上書き: バックアップが持つキーは
     * 既定値であっても書き込み、書き出し元の状態をそのまま再現する(マージはしない)。
     * バックアップに含まれない開発者向け設定・通知済みキーは触らない。
     * 1回の edit にまとめるので settings Flow の emission は1度で済み、rebuild も1回。
     */
    suspend fun importSettings(settings: AppSettings) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.THEME] = settings.themeMode.name
            prefs[Keys.DYNAMIC] = settings.dynamicColor
            prefs[Keys.AUTO_REFRESH] = settings.autoRefresh
            prefs[Keys.NOTIFICATIONS_ENABLED] = settings.notificationsEnabled
            prefs[Keys.NOTIFICATION_TIME] = settings.notificationTimeMinutes
            prefs[Keys.CARD_OVERRIDES] = json.encodeToString(settings.cardOverrides)
            prefs[Keys.QR_ENABLED] = json.encodeToString(settings.enabledQrPaymentIds)
            prefs[Keys.OWNED_BRANDS] = json.encodeToString(settings.ownedBrands)
            prefs[Keys.REGISTERED_AREAS] = json.encodeToString(settings.registeredAreas)
            prefs[Keys.CUSTOM_CARDS] = json.encodeToString(settings.customCards)
            // カスタムキャンペーンは通常データ側のみ復元する。テスト側(CUSTOM_CAMPAIGNS_TEST)は
            // 端末ごとの検証用の一時データでバックアップに含まれないため、触らず現状維持(#65)
            prefs[Keys.CUSTOM_CAMPAIGNS] = json.encodeToString(settings.customCampaigns)
            // 対象外ペアも通常データ側のみ復元する。テスト側(EXCLUDED_STORE_PAIRS_TEST)は
            // 端末ごとの検証用の一時データでバックアップに含まれないため、触らず現状維持(#68)
            prefs[Keys.EXCLUDED_STORE_PAIRS] = json.encodeToString(settings.excludedStorePairs)
        }
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

    suspend fun setNotificationsEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.NOTIFICATIONS_ENABLED] = enabled }
    }

    suspend fun setNotificationTime(minutesOfDay: Int) {
        context.settingsDataStore.edit {
            it[Keys.NOTIFICATION_TIME] = minutesOfDay.coerceIn(0, 24 * 60 - 1)
        }
    }

    /** 通知済みキーの読み出し。通知ジョブの再通知抑止に使う */
    suspend fun notifiedCampaignKeys(): Set<String> =
        context.settingsDataStore.data.first().decodeNotifiedKeys().toSet()

    /**
     * 通知済みキーを空にする(開発者向け)。同じキャンペーンで通知を繰り返し検証するための操作で、
     * 消しても失われるのは「二度通知しない」記録だけ(設定値ではない)。
     */
    suspend fun clearNotifiedCampaignKeys() {
        context.settingsDataStore.edit { it.remove(Keys.NOTIFIED_CAMPAIGNS) }
    }

    /**
     * 通知済みキーを追記する。施策の入れ替わりで増え続けないよう直近 [NOTIFIED_KEYS_MAX] 件に
     * 丸める(古いキーの施策はとうに通知ウィンドウ外で、消しても再通知は起きない)。
     */
    suspend fun addNotifiedCampaignKeys(keys: Collection<String>) {
        if (keys.isEmpty()) return
        context.settingsDataStore.edit { prefs ->
            val updated = (prefs.decodeNotifiedKeys() + keys).distinct().takeLast(NOTIFIED_KEYS_MAX)
            prefs[Keys.NOTIFIED_CAMPAIGNS] = json.encodeToString(updated)
        }
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

    suspend fun setDeveloperMode(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.DEVELOPER_MODE] = enabled }
    }

    /**
     * 開発者モード OFF: 開発者向け設定を既定値に戻し、モード自体も OFF にする。
     * 1回の edit にまとめることで settings Flow の emission が1度で済み、
     * 変更検知(ref/testData/bundled → refresh(force=true))が二重に走らない。
     */
    suspend fun resetDeveloperSettings() {
        context.settingsDataStore.edit { prefs ->
            prefs.remove(Keys.DATA_COMMIT_REF)
            prefs.remove(Keys.USE_TEST_DATA)
            prefs.remove(Keys.USE_BUNDLED_DATA)
            prefs[Keys.DEVELOPER_MODE] = false
        }
    }

    suspend fun setOwned(cardId: String, owned: Boolean) =
        updateOverride(cardId) { it.copy(owned = owned) }

    suspend fun setRate(cardId: String, rate: Double?) =
        updateOverride(cardId) { it.copy(rate = rate) }

    suspend fun setBrand(cardId: String, brand: String) =
        updateOverride(cardId) { it.copy(brand = brand) }

    suspend fun setWelcatsu(cardId: String, enabled: Boolean) =
        updateOverride(cardId) { it.copy(welcatsu = enabled) }

    suspend fun setCardClass(cardId: String, cardClass: String?) =
        updateOverride(cardId) { it.copy(cardClass = cardClass) }

    suspend fun setPointValue(cardId: String, pointValue: Double?) =
        updateOverride(cardId) { it.copy(pointValue = pointValue) }

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

    suspend fun addCustomCard(card: CustomCard) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.CUSTOM_CARDS] = json.encodeToString(prefs.decodeCustomCards() + card)
        }
    }

    suspend fun updateCustomCard(card: CustomCard) {
        context.settingsDataStore.edit { prefs ->
            val updated = prefs.decodeCustomCards().map { if (it.id == card.id) card else it }
            prefs[Keys.CUSTOM_CARDS] = json.encodeToString(updated)
        }
    }

    suspend fun removeCustomCard(id: String) {
        context.settingsDataStore.edit { prefs ->
            val updated = prefs.decodeCustomCards().filterNot { it.id == id }
            prefs[Keys.CUSTOM_CARDS] = json.encodeToString(updated)
        }
    }

    /**
     * カスタムキャンペーンの読み書き先キー。現在のデータモード(useTestData)側を返す(#65)。
     * 通常/テストで参照する ID 体系(payment_methods / merchants)が異なるためリストごと分離し、
     * モード判定は同じトランザクション内の値で行う(トグル直後の書き込みと競合しない)。
     */
    private fun Preferences.customCampaignsKey() =
        if (this[Keys.USE_TEST_DATA] == true) Keys.CUSTOM_CAMPAIGNS_TEST else Keys.CUSTOM_CAMPAIGNS

    suspend fun addCustomCampaign(campaign: CustomCampaign) {
        context.settingsDataStore.edit { prefs ->
            val key = prefs.customCampaignsKey()
            prefs[key] = json.encodeToString(prefs.decodeCustomCampaigns(key) + campaign)
        }
    }

    suspend fun updateCustomCampaign(campaign: CustomCampaign) {
        context.settingsDataStore.edit { prefs ->
            val key = prefs.customCampaignsKey()
            val updated = prefs.decodeCustomCampaigns(key).map { if (it.id == campaign.id) campaign else it }
            prefs[key] = json.encodeToString(updated)
        }
    }

    suspend fun removeCustomCampaign(id: String) {
        context.settingsDataStore.edit { prefs ->
            val key = prefs.customCampaignsKey()
            val updated = prefs.decodeCustomCampaigns(key).filterNot { it.id == id }
            prefs[key] = json.encodeToString(updated)
        }
    }

    /** 対象外ペアの読み書き先キー。カスタムキャンペーンと同じモード分離(#68。[customCampaignsKey] 参照) */
    private fun Preferences.excludedStorePairsKey() =
        if (this[Keys.USE_TEST_DATA] == true) Keys.EXCLUDED_STORE_PAIRS_TEST else Keys.EXCLUDED_STORE_PAIRS

    /** 対象外ペアの登録。同じ (施策, 店舗名) の再登録は無視する */
    suspend fun addExcludedStorePair(pair: ExcludedStorePair) {
        context.settingsDataStore.edit { prefs ->
            val key = prefs.excludedStorePairsKey()
            val current = prefs.decodeExcludedStorePairs(key)
            if (current.none { it.sameTarget(pair) }) {
                prefs[key] = json.encodeToString(current + pair)
            }
        }
    }

    suspend fun removeExcludedStorePairs(pairs: Collection<ExcludedStorePair>) {
        if (pairs.isEmpty()) return
        context.settingsDataStore.edit { prefs ->
            val key = prefs.excludedStorePairsKey()
            val updated = prefs.decodeExcludedStorePairs(key)
                .filterNot { current -> pairs.any { it.sameTarget(current) } }
            prefs[key] = json.encodeToString(updated)
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

    private fun Preferences.decodeCustomCards(): List<CustomCard> =
        this[Keys.CUSTOM_CARDS]
            ?.let { runCatching { json.decodeFromString<List<CustomCard>>(it) }.getOrNull() }
            ?: emptyList()

    private fun Preferences.decodeCustomCampaigns(key: Preferences.Key<String>): List<CustomCampaign> =
        this[key]
            ?.let { runCatching { json.decodeFromString<List<CustomCampaign>>(it) }.getOrNull() }
            ?.map { it.normalized() }
            ?: emptyList()

    private fun Preferences.decodeExcludedStorePairs(key: Preferences.Key<String>): List<ExcludedStorePair> =
        this[key]
            ?.let { runCatching { json.decodeFromString<List<ExcludedStorePair>>(it) }.getOrNull() }
            ?: emptyList()

    private fun Preferences.decodeNotifiedKeys(): List<String> =
        this[Keys.NOTIFIED_CAMPAIGNS]
            ?.let { runCatching { json.decodeFromString<List<String>>(it) }.getOrNull() }
            ?: emptyList()

    private companion object {
        const val NOTIFIED_KEYS_MAX = 200
    }
}
