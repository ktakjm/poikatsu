package com.ktakjm.poikatsu.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json

@Serializable
data class Merchant(
    val id: String,
    val name: String,
    val reading: String = "",
    val aliases: List<String> = emptyList(),
    val category: String = "",
    // 位置情報を持たない発行体(自販機など)の案内。これがあると「地図」探索が行き止まりになるため、
    // 判定詳細では「近くのこの店を探す」を出さず、代わりに外部アプリ/サイトでの確認を促す。
    @SerialName("location_hint") val locationHint: LocationHint? = null,
    @SerialName("yolp_search") val yolpSearch: String = "gc",
    @SerialName("yolp_keyword") val yolpKeyword: String? = null,
)

/** 位置情報を持たない発行体で、店舗位置を確認できる外部導線(例: Coke ON 公式アプリ)を示す。 */
@Serializable
data class LocationHint(
    val text: String,
    val label: String,
    val url: String,
)

/**
 * 周辺検索で得た店舗(POI)。データ源(YOLP)に依存しない中立な形にしておき、
 * 取得元を差し替えても matchStore/judge 以降が無変更で済むようにする。
 * name は支店名込みの表示名(YOLP の Name そのまま)。
 */
data class Poi(
    val name: String,
    val lat: Double,
    val lon: Double,
)

@Serializable
data class GcGroup(
    val gc: String,
    val categories: List<String> = emptyList(),
    @SerialName("max_pages") val maxPages: Int = 5,
    val note: String = "",
)

@Serializable
data class YolpConfig(
    @SerialName("gc_groups") val gcGroups: List<GcGroup> = emptyList(),
    @SerialName("max_keyword_sources") val maxKeywordSources: Int = 20,
)

data class YolpSearchConfig(
    val gcGroups: List<GcGroup>,
    val keywordQueries: List<String>,
    val maxKeywordSources: Int = 20,
) {
    companion object {
        fun build(
            yolpConfig: YolpConfig,
            merchants: List<Merchant>,
            activeMerchantIds: Set<String>,
        ): YolpSearchConfig {
            val activeMerchants = merchants.filter { it.id in activeMerchantIds }
            val activeCategories = activeMerchants
                .filter { it.yolpSearch == "gc" }
                .map { it.category }
                .toSet()
            val gcGroups = yolpConfig.gcGroups.filter { group ->
                group.categories.any { it in activeCategories }
            }
            val keywordQueries = activeMerchants
                .filter { it.yolpSearch == "keyword" }
                .map { it.yolpKeyword ?: it.name }
                .take(yolpConfig.maxKeywordSources)
            return YolpSearchConfig(gcGroups, keywordQueries, yolpConfig.maxKeywordSources)
        }
    }
}

@Serializable
data class MerchantsFile(
    @SerialName("schema_version") val schemaVersion: Int = 1,
    @SerialName("updated_at") val updatedAt: String = "",
    @SerialName("yolp_config") val yolpConfig: YolpConfig? = null,
    val merchants: List<Merchant> = emptyList(),
)

/**
 * 公式が対象/対象外を店舗名で言い切っているリスト。これがある merchant_rule だけ、
 * 別画面で店舗名を入力して判定する。
 * - ineligible_stores に一致 → 対象外
 * - eligible_stores に一致 → 対象
 * - どちらにも無い → 要確認(公式リスト外。一部対象外店舗があるため断定しない)
 * exclusion(ineligible)を優先判定する。
 */
@Serializable
data class OfficialStoreList(
    @SerialName("eligible_stores") val eligibleStores: List<String> = emptyList(),
    @SerialName("ineligible_stores") val ineligibleStores: List<String> = emptyList(),
    /** 断定の鮮度として表示する日付。official=true なら公式情報自体の更新日、false なら当方の確認日 */
    @SerialName("updated_date") val updatedDate: String = "",
    @SerialName("date_is_official") val dateIsOfficial: Boolean = false,
    @SerialName("source_url") val sourceUrl: String? = null,
)

@Serializable
data class MerchantRule(
    @SerialName("merchant_id") val merchantId: String,
    /** この merchant だけ還元率が異なる場合の上書き値(%)。非 null なら rate_base の代わりに使う */
    @SerialName("rate_override") val rateOverride: Double? = null,
    /** 対象の拡張・追加・明確化(「〜も含む」等)。見落としても損しない安心情報として通常ロールで表示 */
    @SerialName("eligible_notes") val eligibleNotes: List<String> = emptyList(),
    /**
     * 対象外・限定の言い切り(「〜以外は対象外」形に言い換えて登録)。見落とすと損する情報として
     * warning 面で表示。店舗ごとに実態・呼び名が異なるものはここに店舗別で持つ(campaign 直下に集約しない)
     */
    @SerialName("ineligible_notes") val ineligibleNotes: List<String> = emptyList(),
    @SerialName("amex_excluded") val amexExcluded: Boolean = false,
    @SerialName("store_list_url") val storeListUrl: String? = null,
    @SerialName("official_store_list") val officialStoreList: OfficialStoreList? = null,
)

/**
 * 店舗に紐づかない条件別の還元率(external 施策の段階制。例: 中小20%・大手10%)。
 * managed の rate_base + merchant_rules[].rate_override と対になる構造で、これがある施策の
 * rate_base にはリスト中の最大値を入れる(整合性テストで強制。「最大○%」表示の根拠)。
 * 表示専用で、判定エンジンの最良比較は従来どおり rate_base(=最大の楽観値)を使う。
 */
@Serializable
data class RateRule(
    /** 率が適用される条件(例: "中小企業・小規模企業の店舗")。表示にそのまま使う */
    val condition: String,
    val rate: Double,
)

@Serializable
data class Region(
    val name: String,
    val prefecture: String,
)

/** min_purchase_scope: 最低購入額が 1 決済ごとに掛かる(省略時) */
const val MIN_PURCHASE_SCOPE_TRANSACTION = "transaction"

/** min_purchase_scope: 最低購入額が期間中の購入合計に掛かる(累積可) */
const val MIN_PURCHASE_SCOPE_PERIOD_TOTAL = "period_total"

/**
 * 対象商品限定(メーカー×小売×決済連動キャンペーンの「花王商品のみ」等)。
 * これがある施策は店の全商品に効かないため、「最良特典」比較(determineBest)から分離し、
 * 一覧ラベル・判定詳細には対象商品の条件を明示する。
 */
@Serializable
data class ProductScope(
    /** 対象商品の表示ラベル(例: "花王商品(メリーズ・キュレル・ソフィーナ・カネボウ除く)") */
    val label: String,
)

/**
 * 繰り返し日付条件(毎週金土・毎月20日30日等)。period_start/end(外枠の開催期間)と併用し、
 * 「お店」「地図」の判定には期間内かつ対象日のみ出す。days_of_week / days_of_month はどちらか一方
 * (併用パターンは実在確認できるまで未対応)。
 */
@Serializable
data class Recurrence(
    /** 対象曜日("MON"〜"SUN" の3文字表記) */
    @SerialName("days_of_week") val daysOfWeek: List<String> = emptyList(),
    /** 対象日(1〜31) */
    @SerialName("days_of_month") val daysOfMonth: List<Int> = emptyList(),
)

@Serializable
data class Campaign(
    val id: String,
    /** 施策の運営者(カード会社・QR決済事業者・自治体キャンペーンの決済事業者)。バッジ表示のフォールバックに使う */
    val operator: String,
    /** 紐づくカード(payment_methods.json の cards.id)。card_program / promotion で使い、card_brand / payment_method_id と排他 */
    @SerialName("card_id") val cardId: String? = null,
    /** ブランド施策(イシュアー不問。例: Amex 30% OFF)の対象ブランド。card_id / payment_method_id と排他 */
    @SerialName("card_brand") val cardBrand: String? = null,
    val name: String,
    /**
     * 期間限定タブのカード表示用の短いタイトル(任意)。実質、多チェーン promotion 専用
     * (単一チェーンは merchant 名、自治体系は region タイトルで足りる)。name は公式表記の写し
     * (照合キー+判定詳細の説明文)の役割を持つため、略記の編集判断はこちらに分離する。
     * 未指定時のフォールバック: 単一チェーンは merchant 名 → 複数チェーンは「{先頭チェーン} 他Nチェーン」。
     * 登録規則(率・期間は入れない等)は collect-campaigns スキルの mapping.md 参照。
     */
    @SerialName("display_name") val displayName: String? = null,
    @SerialName("payment_instruction") val paymentInstruction: String = "",
    @SerialName("rate_base") val rateBase: Double? = null,
    /** 条件別の還元率(段階制)。非空なら rate_base はこの最大値で、表示は「最大○%」+内訳になる */
    @SerialName("rate_rules") val rateRules: List<RateRule> = emptyList(),
    @SerialName("period_start") val periodStart: String? = null,
    @SerialName("period_end") val periodEnd: String? = null,
    /**
     * 施策全体に一様に効く「対象」の言い切り(「〜も対象」「在住不問」等)。
     * merchant_rules[].eligible_notes(店舗固有)とレベル横断で連結して「対象」セクションに表示する
     */
    @SerialName("eligible_notes") val eligibleNotes: List<String> = emptyList(),
    /**
     * 施策全体に一様に効く「対象外・限定」の言い切り(「〜以外は対象外」形)。
     * 線引きは「見落とすとユーザーが損するか」: 損する情報はここ、雑多な補足は memo(非表示)
     */
    @SerialName("ineligible_notes") val ineligibleNotes: List<String> = emptyList(),
    /**
     * 収集時の内部メモ(UI 非表示)。公式との照合台帳・付与時期・集計期間・操作のコツ等、
     * 表示フィールドに行き場の無い事実を残す(旧 conditions)
     */
    val memo: List<String> = emptyList(),
    @SerialName("merchant_rules") val merchantRules: List<MerchantRule> = emptyList(),
    @SerialName("verified_date") val verifiedDate: String = "",
    val type: String = "card_program",
    /** 特典の型: "rebate"(後日還元) | "discount"(即時割引) | "lottery"(抽選。最良特典比較には載せない) */
    @SerialName("benefit_type") val benefitType: String = "rebate",
    @SerialName("payment_method_id") val paymentMethodId: String? = null,
    @SerialName("per_transaction_cap") val perTransactionCap: Int? = null,
    @SerialName("period_total_cap") val periodTotalCap: Int? = null,
    @SerialName("cap_note") val capNote: String? = null,
    @SerialName("discount_amount") val discountAmount: Int? = null,
    @SerialName("min_purchase") val minPurchase: Int? = null,
    /**
     * min_purchase の集計単位: "transaction"(1決済ごと。省略時) | "period_total"(期間中の購入合計。
     * PayPay×花王等の「期間累計3,000円以上」型)。表示文言の切り替えに使う
     */
    @SerialName("min_purchase_scope") val minPurchaseScope: String = MIN_PURCHASE_SCOPE_TRANSACTION,
    /** 対象商品限定(メーカー縛り等)。非 null なら「最良特典」比較から分離する */
    @SerialName("product_scope") val productScope: ProductScope? = null,
    /** 事前エントリーしないと還元されない施策(楽天ペイ×花王等)。判定詳細に警告を出す */
    @SerialName("requires_entry") val requiresEntry: Boolean = false,
    @SerialName("usage_limit") val usageLimit: Int? = null,
    @SerialName("usage_limit_note") val usageLimitNote: String? = null,
    /** 予算到達次第の早期終了があり得るか(自治体系はほぼ全件 true)。判定詳細・期間限定タブに注記を出す */
    @SerialName("may_end_early") val mayEndEarly: Boolean = false,
    /**
     * 公式が「還元対象」と明記しているウォレット("apple_pay" / "google_pay")。
     * 未掲載は不明扱い(3状態)。official_store_list と同じく断定できる事実だけを登録する。
     */
    @SerialName("eligible_wallets") val eligibleWallets: List<String> = emptyList(),
    /** 公式が「還元対象外」と明記しているウォレット。google_pay があれば判定詳細に警告を出す */
    @SerialName("ineligible_wallets") val ineligibleWallets: List<String> = emptyList(),
    /** 繰り返し日付条件。null なら期間内の全日が対象 */
    val recurrence: Recurrence? = null,
    val region: Region? = null,
    @SerialName("detail_url") val detailUrl: String? = null,
    @SerialName("store_search_url") val storeSearchUrl: String? = null,
    @SerialName("store_scope") val storeScope: String = "managed",
)

@Serializable
data class CampaignsFile(
    @SerialName("schema_version") val schemaVersion: Int = 1,
    @SerialName("updated_at") val updatedAt: String = "",
    val campaigns: List<Campaign> = emptyList(),
)

/**
 * ポイント価値の倍率(例: 三井住友カードの V ポイントはウエル活で 1.5 倍価値)。
 * 設定画面でこのカードに表示するチェック(label)、判定カードに出すバッジ(badgeLabel)・
 * 適用時注記(appliedNote)をデータ側に持たせ、UI に特定施策名をハードコードしない。
 */
@Serializable
data class PointMultiplier(
    val label: String,
    val factor: Double,
    /** バッジ等に使う識別色(例: ウエルシアのコーポレートカラー)。"#RRGGBB" 形式 */
    val color: String? = null,
    @SerialName("badge_label") val badgeLabel: String = "",
    @SerialName("applied_note") val appliedNote: String = "",
)

@Serializable
data class PaymentCard(
    /** カードの識別子(例: "smcc")。campaigns.json の card_id と DataStore card_overrides のキーから参照される */
    val id: String,
    @SerialName("card_name") val cardName: String,
    /**
     * 発行体の識別色(#RRGGBB)。このカードに紐づく施策のストライプ/バッジ/地図ピンに使う。
     * 施策側には色を持たせない(同一発行体の施策で色がぶれないよう発行体側で一元管理する)。
     */
    @SerialName("brand_color") val brandColor: String? = null,
    /**
     * このカード製品で選べるブランドの選択肢(カタログの事実)。単一なら固定ブランド。
     * ユーザーが実際にどのブランドを持っているかはカタログに置かず CardOverride.brand(DataStore)で持つ。
     */
    val brands: List<String> = emptyList(),
    @SerialName("effective_rate_default") val effectiveRateDefault: Double? = null,
    @SerialName("point_multiplier") val pointMultiplier: PointMultiplier? = null,
    /**
     * 実行時フィールド: 判定に使う実ブランド。VM のマージで CardOverride.brand(なければ brands が
     * 単一のときその値)を設定し JSON には現れない。空文字は「未選択」= どのブランドとも断定しない。
     */
    @Transient val brand: String = "",
    /** 実行時フラグ: ウエル活(×factor)を適用済みか。VM のマージで設定し JSON には現れない */
    @Transient val welcatsuApplied: Boolean = false,
)

/** 国際ブランド1件(payment_methods.json の card_brands)。name は campaigns.json の card_brand から参照される */
@Serializable
data class CardBrand(
    val name: String,
    /** ブランドの識別色(#RRGGBB)。card_brand 施策のストライプ/バッジ/地図ピンに使う */
    val color: String? = null,
)

/**
 * QR 決済サービスの決済アプリ 1 件(qr_payments[].app_packages)。1 サービスを複数アプリが担える
 * (AEON Pay = 単独アプリ / iAEON の 2 本立て等)ため、サービス:アプリ = 1:N で持つ。
 * label はボタン表示に使う起動先アプリの実名(サービス名と一致するとは限らない。メルペイ→メルカリ)。
 * パッケージ名は AndroidManifest の <queries> と対で管理する(宣言が無いと Android 11+ で起動検出できない)
 */
@Serializable
data class QrAppPackage(
    @SerialName("package") val packageName: String,
    val label: String,
)

@Serializable
data class QrPayment(
    val id: String,
    val name: String,
    @SerialName("brand_color") val brandColor: String,
    @SerialName("app_packages") val appPackages: List<QrAppPackage> = emptyList(),
    @SerialName("store_search_label") val storeSearchLabel: String = "",
    @SerialName("enabled_default") val enabledDefault: Boolean = false,
)

/** 決済手段カタログ(payment_methods.json)。カードと QR 決済のマスタで、ユーザー差分は DataStore に持つ */
@Serializable
data class PaymentMethodsFile(
    @SerialName("schema_version") val schemaVersion: Int = 1,
    @SerialName("updated_at") val updatedAt: String = "",
    val cards: List<PaymentCard> = emptyList(),
    /**
     * 登録できるカードブランドの選択肢(国際ブランドのマスタ)。設定画面「カードブランド」に常時出し、
     * カタログに無いカードの保有ブランドを登録しておくと、ブランド施策(card_brand)の開始と同時に
     * 判定へ表示される。campaigns.json の card_brand はこのリストの name を使う
     */
    @SerialName("card_brands") val cardBrands: List<CardBrand> = emptyList(),
    @SerialName("qr_payments") val qrPayments: List<QrPayment> = emptyList(),
)

// ==================== 自治体マスタ(municipalities.json) ====================

/**
 * 自治体マスタ。scripts/generate_municipalities.py が気象庁の予報区データから生成する
 * (スキーマの詳細は data/README.md)。設定画面のピッカーと、期間限定タブの
 * 地域フィルタ(グループ→自治体コードの展開)に使う。
 */
@Serializable
data class MunicipalityMaster(
    val version: Int = 2,
    val prefectures: List<Prefecture> = emptyList(),
) {
    fun isEmpty(): Boolean = prefectures.isEmpty()
}

@Serializable
data class Prefecture(
    /** 都道府県コード(2桁) */
    val code: String,
    val name: String,
    val municipalities: List<Municipality> = emptyList(),
    val groups: List<AreaGroup> = emptyList(),
)

@Serializable
data class Municipality(
    /** 全国地方公共団体コード5桁(チェックディジットなし) */
    val code: String,
    val name: String,
)

/**
 * 自治体グループ(「東京23区」「埼玉県南部」等)。level は粒度:
 * custom(補完定義) / primary(気象庁一次細分) / detail(市町村等をまとめた地域)。
 * 表示順はマスタの並びのまま使う(custom → primary → その配下の detail)。
 */
@Serializable
data class AreaGroup(
    val id: String,
    val name: String,
    val level: String = "primary",
    /** 構成自治体のコード */
    val municipalities: List<String> = emptyList(),
)

/**
 * ユーザーが設定画面で登録した居住地・行動圏(DataStore へ保存)。
 * 自治体単体(type=municipality, code=自治体コード)とグループ(type=group, code=グループid)の
 * どちらも取れる。name/prefecture は表示用のスナップショット(マスタ未ロードでも一覧表示できる)。
 */
@Serializable
data class RegisteredArea(
    val type: RegisteredAreaType,
    /** 自治体コード(5桁) or グループ id */
    val code: String,
    val name: String,
    val prefecture: String,
)

@Serializable
enum class RegisteredAreaType {
    @SerialName("municipality") MUNICIPALITY,
    @SerialName("group") GROUP,
}

data class PoikatsuData(
    val merchants: List<Merchant>,
    val campaigns: List<Campaign>,
    val cards: List<PaymentCard> = emptyList(),
    val cardBrands: List<CardBrand> = emptyList(),
    val qrPayments: List<QrPayment> = emptyList(),
    val updatedAt: String,
    val yolpConfig: YolpConfig? = null,
) {
    /**
     * 施策の識別色。発行体(カード / ブランド / QR)のカタログから引く。施策側に色を持たせないのは、
     * 同一発行体の施策間で色がぶれる(例: 三井住友の2種の緑が混在する)のを防ぐため。
     */
    fun brandColorOf(campaign: Campaign): String? = when {
        campaign.cardId != null -> cards.firstOrNull { it.id == campaign.cardId }?.brandColor
        campaign.cardBrand != null ->
            cardBrands.firstOrNull { it.name.equals(campaign.cardBrand, ignoreCase = true) }?.color
        campaign.paymentMethodId != null ->
            qrPayments.firstOrNull { it.id == campaign.paymentMethodId }?.brandColor
        else -> null
    }
}

object PoikatsuJson {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    fun parse(merchantsJson: String, campaignsJson: String, paymentMethodsJson: String): PoikatsuData {
        val merchantsFile = json.decodeFromString<MerchantsFile>(merchantsJson)
        val campaignsFile = json.decodeFromString<CampaignsFile>(campaignsJson)
        val paymentMethodsFile = json.decodeFromString<PaymentMethodsFile>(paymentMethodsJson)
        return PoikatsuData(
            merchants = merchantsFile.merchants,
            campaigns = campaignsFile.campaigns,
            cards = paymentMethodsFile.cards,
            cardBrands = paymentMethodsFile.cardBrands,
            qrPayments = paymentMethodsFile.qrPayments,
            updatedAt = campaignsFile.updatedAt,
            yolpConfig = merchantsFile.yolpConfig,
        )
    }

    fun parseMunicipalities(municipalitiesJson: String): MunicipalityMaster =
        json.decodeFromString<MunicipalityMaster>(municipalitiesJson)
}
