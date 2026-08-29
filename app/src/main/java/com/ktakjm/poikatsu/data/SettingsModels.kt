package com.ktakjm.poikatsu.data

import com.ktakjm.poikatsu.domain.DEFAULT_NOTIFY_TIME_MINUTES
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ユーザー設定のモデル(DataStore に JSON で保存する型と、その集約スナップショット AppSettings)。
// 同梱データ(data/*.json)のモデルは Models.kt、読み書きは SettingsRepository.kt / SettingsCodec.kt。
// 保存済み JSON との互換のためフィールド名は永続化キーそのもの(改名は旧データの読み捨てになる)。

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
    // 旧フィールド welcatsu(カード単位のウエル活)は #39 で通貨単位の enabled_point_multipliers へ
    // 正規化して廃止。保存済み JSON の残存値は ignoreUnknownKeys で無視される(移行せず再設定)
    /** カードクラス(カタログ card_classes の id。JCB W/S 等)。null ならカタログ先頭(保守側)。 */
    val cardClass: String? = null,
    // 旧フィールド pointValue(カード単位の 1pt 価値)は #13 で通貨単位(AppSettings.pointCurrencyValues)へ
    // 移行し、移行コードごと #90 で削除。保存済み JSON・旧バックアップの残存値は ignoreUnknownKeys で無視される
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
 * 帰属に加えて「この決済手段だけの差分」(#91)を持てる。同梱 municipal の payment_variants と
 * 同じ合成規則で、変換時に施策共通の値へ**単値は上書き・注記は末尾に連結**される
 * (空/null は共通側のまま)。自治体キャンペーンの「PayPay だけ告知ページが違う・
 * au PAY は残高還元」のような差分を 1 登録に収めるためのもの。
 */
@Serializable
data class CustomPayment(
    /** カード(カタログ cards.id または CustomCard.id) */
    val cardId: String? = null,
    /** QR 決済(カタログ qr_payments.id) */
    val qrPaymentId: String? = null,
    /** ブランド指定(カード会社不問。card_brands の name。Amex 会員限定施策等) */
    val cardBrand: String? = null,
    /** この決済手段の詳細ページ URL。null なら施策共通の [CustomCampaign.detailUrl] */
    val detailUrl: String? = null,
    /** この決済手段だけの対象・特典メモ(改行区切り)。共通の [CustomCampaign.note] の後ろに連結 */
    val note: String = "",
    /** この決済手段だけの対象外・注意(改行区切り)。共通の [CustomCampaign.ineligibleNote] の後ろに連結 */
    val ineligibleNote: String = "",
    /**
     * 払い出し通貨の明示(point_currencies.id)。null なら決済手段の既定通貨を継承する。
     * au PAY の残高還元(既定は Ponta)のように、既定のままだと交換所倍率が掛かって過大評価に
     * なる例外を正す用途(#83 と同じ理由)
     */
    val pointCurrencyId: String? = null,
) {
    /** 差分(帰属以外)を 1 つでも持つか。エディタの「支払い方法ごとの設定」を初期展開する判定に使う */
    val hasOverrides: Boolean
        get() = detailUrl != null || note.isNotBlank() || ineligibleNote.isNotBlank() || pointCurrencyId != null
}

/** カスタム決済手段の帰属先(#86)。campaigns.json 側と同じ [Attribution] に写して分岐を共用する */
val CustomPayment.attribution: Attribution?
    get() = when {
        cardId != null -> Attribution.Card(cardId)
        qrPaymentId != null -> Attribution.Qr(qrPaymentId)
        cardBrand != null -> Attribution.Brand(cardBrand)
        else -> null
    }

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
 * 対象は「お店」(チェーン/看板/自由入力店名/全店舗)か「地域」([region]。自治体キャンペーン。#91)の
 * どちらか一方。
 */
@Serializable
data class CustomCampaign(
    /** 「custom:<UUID>」形式。同梱 campaigns.json の id と衝突しない採番 */
    val id: String,
    /** キャンペーン名(おトクタブ・判定カードのタイトル) */
    val name: String,
    // 旧スキーマ(単一決済の cardId / qrPaymentId)は #90 で削除。旧データの残存値は読み捨て(決済手段は再設定)
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
    /**
     * 自治体キャンペーン(#91)の対象地域。非 null なら同梱の municipal 施策と同じ形
     * (type=municipal・store_scope=external・merchant_rules 空)に変換され、おトクタブの自治体
     * グルーピング・地域フィルタ・地図のお知らせピル・通知・QR サービス既定文言の補完まで
     * 同じ経路に乗る。name は municipalities.json の自治体名と一致させる(ピッカーで選ぶ。
     * 自由入力にしないのは名称不一致で地域フィルタ・ピル・通知が効かなくなるため)。
     * 県全域は name == prefecture(同梱と同じ規約。[com.ktakjm.poikatsu.domain.isPrefectureWide])。
     * [allStores] / [merchantIds] / [bannerSelections] / [storeNames] とは排他(自治体はお店を列挙しない)
     */
    val region: Region? = null,
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
    companion object {
        const val ID_PREFIX = "custom:"
    }
}

/** 自治体キャンペーンか(#91)。地域を持つ登録がそれで、お店の指定([allStores] 含む)とは排他 */
val CustomCampaign.isMunicipal: Boolean get() = region != null

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

/**
 * 期間限定ポイントの残高と失効日(通貨ごとに1件=直近失効分。#13)。
 * 公式 API が無いため手入力。失効したら次の塊を入れ直す運用。
 */
@Serializable
data class PointBalance(
    /** 残高(pt) */
    val balancePt: Int,
    /** 失効日(YYYY-MM-DD)。この日までは利用可能、翌日以降は失効済み扱い */
    val expiryDate: String,
)

/** アプリ全体の設定スナップショット。DataStore から1本の Flow で配る。 */
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val autoRefresh: Boolean = true,
    /** キャンペーン通知(#6)。ON の間、日次の通知ジョブ(CampaignNotificationWorker)を登録する */
    val notificationsEnabled: Boolean = false,
    /** 通知時刻(0時からの分。既定 8:00)。15分刻みは設定 UI 側の制約で、保存値は分単位で持つ */
    val notificationTimeMinutes: Int = DEFAULT_NOTIFY_TIME_MINUTES,
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
    /**
     * 地図で間引いた店(公式対象外・網羅リスト外・ユーザー登録の対象外)を薄いピンで残すか(#77)。
     * 既定 ON。「対象店だけ見たい」人向けに OFF にできる(分類自体は常に行い、描画だけ抑止する)。
     */
    val showIneligibleStorePins: Boolean = true,
    /** 利用中の QR 決済 ID。payment_methods.json の qr_payments カタログからユーザーが選択 */
    val enabledQrPaymentIds: Set<String> = emptySet(),
    /**
     * カタログのカード以外で保有している国際ブランド(例: "Visa")。イシュアー不問の
     * ブランド施策(campaigns.json の card_brand)の判定にだけ使う。選択肢は施策データ側の
     * card_brand 値から出すため、カタログ(payment_methods.json)にスキーマ追加は不要。
     */
    val ownedBrands: Set<String> = emptySet(),
    /**
     * ポイント倍率(ウエル活等)を有効にしている通貨 id(payment_methods.json の point_currencies)。
     * 倍率は通貨の価値特性(#39)なので、Vポイントを稼ぐカードが複数あっても設定はここ1箇所。
     */
    val enabledPointMultipliers: Set<String> = emptySet(),
    /**
     * ユーザーが選んだポイント倍率(通貨 id → 倍率)。#83。選択肢(point_multiplier.factor_options)を
     * 持つ通貨だけが値を持ち、無い通貨・選択肢外の値はマージで無視してカタログの factor に落ちる。
     * 「乗り継ぎで 1.3 倍」のような自由な値は選択肢では表せないため [pointCurrencyValues] 側で入れる。
     */
    val pointMultiplierFactors: Map<String, Double> = emptyMap(),
    /**
     * 会員になっているポイントプログラムの通貨 id(#39)。プログラム会員提示型施策
     * (point_program_id)の判定フィルタに使う(所有カードと同じ opt-in の構図)。
     */
    val pointProgramMemberships: Set<String> = emptySet(),
    /**
     * 1pt の価値(円)。通貨単位(payment_methods.json の point_currencies)で保持する(#13)。
     * 値が無い通貨はカタログの pointValueConfig.default、それも無ければ 1.0 円を使う
     * (解決は domain/UserDataMerge.kt の mergeUserData で行う)。
     */
    val pointCurrencyValues: Map<String, Double> = emptyMap(),
    /**
     * 期間限定ポイントの残高・失効日(通貨単位。#13)。公式 API が無いため手入力で、
     * 通貨ごとに直近失効分1件だけを保持する(失効したら次の塊を入れ直す運用)。
     */
    val pointBalances: Map<String, PointBalance> = emptyMap(),
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
