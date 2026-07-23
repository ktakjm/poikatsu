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
    /** 国際ブランド(MUFG の Amex/Mastercard/Visa/JCB 等)。null ならカタログの既定値。 */
    val brand: String? = null,
    /** ウエル活(ポイント価値 ×倍率)で表示するか。 */
    val welcatsu: Boolean = false,
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
    /** キャンペーン名(期間限定タブ・判定カードのタイトル) */
    val name: String,
    /** 旧スキーマ(単一決済)の残置。読み込み時に [normalized] で [payments] へ折り畳む */
    val cardId: String? = null,
    /** 旧スキーマ(単一決済)の残置。読み込み時に [normalized] で [payments] へ折り畳む */
    val qrPaymentId: String? = null,
    /** 紐付け先の決済手段(1件以上)。決済ごとに Campaign へ展開される */
    val payments: List<CustomPayment> = emptyList(),
    /** 対象チェーン(merchants.json の id)。[storeNames] と併用可 */
    val merchantIds: List<String> = emptyList(),
    /** カタログに無い店の自由入力店名。店名の部分一致でお店・地図タブにマッチさせる */
    val storeNames: List<String> = emptyList(),
    /** 特典の型: "rebate"(後日還元) | "discount"(即時割引) | "lottery"(抽選) */
    val benefitType: String = "rebate",
    /** 還元率(%)。率で表せない特典は null にして [note] に書く */
    val rate: Double? = null,
    /** 定額特典(円)。「500円引き」等 */
    val discountAmount: Int? = null,
    /** 対象商品限定のラベル(例: "対象の化粧品のみ")。非空なら最良比較から分離+商品限定バッジ */
    val productScope: String? = null,
    /** 対象・特典のメモ(判定カードの「対象」に表示)。改行区切りで複数項目 */
    val note: String = "",
    /** 対象外・注意のメモ(warning 面で表示)。改行区切りで複数項目 */
    val ineligibleNote: String = "",
    /** 開始日(YYYY-MM-DD)。null は開始済み扱い */
    val startDate: String? = null,
    /** 終了日(YYYY-MM-DD)。null は期限なし(期限バッジを出さない) */
    val endDate: String? = null,
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
    /** 登録エリア(自治体単体 or グループ)。期間限定タブの地域フィルタに使う */
    val registeredAreas: List<RegisteredArea> = emptyList(),
    /** カタログ外のカスタムカード(登録順) */
    val customCards: List<CustomCard> = emptyList(),
    /** ユーザー登録のカスタムキャンペーン(登録順) */
    val customCampaigns: List<CustomCampaign> = emptyList(),
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
        val DEVELOPER_MODE = booleanPreferencesKey("developer_mode")
        val QR_ENABLED = stringPreferencesKey("qr_enabled")
        val OWNED_BRANDS = stringPreferencesKey("owned_brands")
        // 旧キー "municipalities"(RegisteredMunicipality のリスト)は公開前のスキーマ刷新で廃止。
        // 移行せず捨てる(登録し直してもらう)
        val REGISTERED_AREAS = stringPreferencesKey("registered_areas")
        val CUSTOM_CARDS = stringPreferencesKey("custom_cards")
        val CUSTOM_CAMPAIGNS = stringPreferencesKey("custom_campaigns")
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
            developerMode = prefs[Keys.DEVELOPER_MODE] ?: false,
            enabledQrPaymentIds = prefs.decodeQrEnabled(),
            ownedBrands = prefs.decodeOwnedBrands(),
            registeredAreas = prefs.decodeRegisteredAreas(),
            customCards = prefs.decodeCustomCards(),
            customCampaigns = prefs.decodeCustomCampaigns(),
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

    suspend fun addCustomCampaign(campaign: CustomCampaign) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.CUSTOM_CAMPAIGNS] = json.encodeToString(prefs.decodeCustomCampaigns() + campaign)
        }
    }

    suspend fun updateCustomCampaign(campaign: CustomCampaign) {
        context.settingsDataStore.edit { prefs ->
            val updated = prefs.decodeCustomCampaigns().map { if (it.id == campaign.id) campaign else it }
            prefs[Keys.CUSTOM_CAMPAIGNS] = json.encodeToString(updated)
        }
    }

    suspend fun removeCustomCampaign(id: String) {
        context.settingsDataStore.edit { prefs ->
            val updated = prefs.decodeCustomCampaigns().filterNot { it.id == id }
            prefs[Keys.CUSTOM_CAMPAIGNS] = json.encodeToString(updated)
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

    private fun Preferences.decodeCustomCampaigns(): List<CustomCampaign> =
        this[Keys.CUSTOM_CAMPAIGNS]
            ?.let { runCatching { json.decodeFromString<List<CustomCampaign>>(it) }.getOrNull() }
            ?.map { it.normalized() }
            ?: emptyList()
}
