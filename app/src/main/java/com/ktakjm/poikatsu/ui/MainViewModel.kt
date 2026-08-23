package com.ktakjm.poikatsu.ui

import android.app.Application
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.net.Uri
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ktakjm.poikatsu.BuildConfig
import com.ktakjm.poikatsu.data.Campaign
import com.ktakjm.poikatsu.data.DataRepository
import com.ktakjm.poikatsu.data.DataSource
import com.ktakjm.poikatsu.data.GithubRawClient
import com.ktakjm.poikatsu.data.LoadedData
import com.ktakjm.poikatsu.data.LocationProvider
import com.ktakjm.poikatsu.data.Merchant
import com.ktakjm.poikatsu.data.MerchantRule
import com.ktakjm.poikatsu.data.YolpClient
import com.ktakjm.poikatsu.data.YolpSearchConfig
import com.ktakjm.poikatsu.data.AppSettings
import com.ktakjm.poikatsu.data.CustomCampaign
import com.ktakjm.poikatsu.data.CustomCard
import com.ktakjm.poikatsu.data.ExcludedStorePair
import com.ktakjm.poikatsu.data.MunicipalityMaster
import com.ktakjm.poikatsu.data.CardClass
import com.ktakjm.poikatsu.data.PaymentCard
import com.ktakjm.poikatsu.data.PointBalance
import com.ktakjm.poikatsu.data.PointMultiplier
import com.ktakjm.poikatsu.data.PointValueConfig
import com.ktakjm.poikatsu.data.PoikatsuData
import com.ktakjm.poikatsu.data.PoikatsuJson
import com.ktakjm.poikatsu.data.RegisteredArea
import com.ktakjm.poikatsu.data.SETTINGS_BACKUP_SCHEMA_VERSION
import com.ktakjm.poikatsu.data.SettingsBackup
import com.ktakjm.poikatsu.data.SettingsRepository
import com.ktakjm.poikatsu.data.ThemeMode
import com.ktakjm.poikatsu.data.decodeSettingsBackup
import com.ktakjm.poikatsu.data.encodeSettingsBackup
import com.ktakjm.poikatsu.data.toBackup
import com.ktakjm.poikatsu.data.toSettings
import com.ktakjm.poikatsu.domain.BenefitLabel
import com.ktakjm.poikatsu.domain.BestPaymentOption
import com.ktakjm.poikatsu.domain.CampaignJudgment
import com.ktakjm.poikatsu.domain.CampaignStatus
import com.ktakjm.poikatsu.domain.CampaignType
import com.ktakjm.poikatsu.domain.ExpiringPointNotice
import com.ktakjm.poikatsu.domain.JudgmentEngine
import com.ktakjm.poikatsu.domain.FixedBenefitAdvice
import com.ktakjm.poikatsu.domain.StackedRate
import com.ktakjm.poikatsu.domain.StoreVerdict
import com.ktakjm.poikatsu.domain.allowsManualRate
import com.ktakjm.poikatsu.domain.bestBenefitLabel
import com.ktakjm.poikatsu.domain.campaignType
import com.ktakjm.poikatsu.domain.campaignsInGroup
import com.ktakjm.poikatsu.domain.compositeValueYen
import com.ktakjm.poikatsu.domain.effectiveValueRate
import com.ktakjm.poikatsu.domain.expiringPointNotices
import com.ktakjm.poikatsu.domain.filterCampaignsByArea
import com.ktakjm.poikatsu.domain.mergeUserData
import com.ktakjm.poikatsu.domain.multiplierToggleIds
import com.ktakjm.poikatsu.domain.municipalCampaignsForAreas
import com.ktakjm.poikatsu.domain.municipalCampaignsForLocation
import com.ktakjm.poikatsu.domain.isCustom
import com.ktakjm.poikatsu.domain.isExpired
import com.ktakjm.poikatsu.domain.isPrefectureWide
import com.ktakjm.poikatsu.domain.isTimeLimited
import com.ktakjm.poikatsu.domain.resolveCardCampaignRate
import com.ktakjm.poikatsu.notification.CampaignNotifications
import com.ktakjm.poikatsu.util.GeoMath
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import timber.log.Timber

/** 「地図」初回・「現在地で検索」時の既定半径(m)。以降の「このエリアを検索」は地図の可視範囲から算出する */
private const val NEARBY_DEFAULT_RADIUS_M = 2000

/** 「地図」初回・「現在地で検索」時の既定ズーム。可視範囲検索では各回の地図ズームを引き継ぐ */
private const val NEARBY_DEFAULT_ZOOM = 16.0

/** 500m 以内の店舗がこの件数未満なら引きズーム(NEARBY_WIDE_ZOOM)にする */
private const val NEARBY_DENSE_THRESHOLD = 10
private const val NEARBY_DENSE_RADIUS_M = 500
private const val NEARBY_WIDE_ZOOM = 15.0

/**
 * 2段階表示の補正しきい値(m)。キャッシュ位置で先に地図を出した後、新鮮な測位がこれ以上
 * ずれていたら検索し直す。未満なら青ドットだけ直す(検索半径2kmに対し誤差として許容できる範囲)
 */
private const val LOCATION_CORRECTION_M = 100

/**
 * インポートで読むファイルサイズの上限(#50)。設定 JSON は大きくても数十 KB なので、
 * 誤って動画等を選ばれたときに全部メモリへ載せないための歯止め
 */
private const val BACKUP_MAX_BYTES = 1024 * 1024

/** 位置情報を取得できないときのフォールバック地点(新宿駅) */
private val FALLBACK_PLACE = MainViewModel.GeocodedPlace(
    name = "新宿駅",
    fullAddress = "東京都新宿区新宿三丁目",
    lat = 35.6896,
    lon = 139.7006,
)

enum class AppTab { SEARCH, NEARBY, CAMPAIGNS, SETTINGS }
enum class CampaignFilter { ALL, MUNICIPAL, NON_MUNICIPAL }

/**
 * 設定タブのサブページ(#47)。トップはカテゴリ行のみで、詳細は各サブページ
 * (設定タブ上のオーバーレイ+戻る)に置く。title は topBar のタイトル表示に使う。
 */
enum class SettingsSubpage(val title: String) {
    DISPLAY("表示"),
    PAYMENT_METHODS("お支払い方法"),
    // 「自治体」だと登録する動機が伝わらないため、「受け取りたくて登録している地域」の
    // ニュアンスでマイエリアと呼ぶ(マイカードと命名を揃える)
    MUNICIPALITIES("マイエリア"),
    NOTIFICATIONS("通知"),
    DATA("キャンペーンデータ"),

    /** ユーザーが「この施策はこのお店では対象外」と登録したペアの管理一覧(#63) */
    EXCLUDED_STORES("対象外に登録したお店"),
    BACKUP("バックアップ"),
    DEVELOPER("開発者向け"),
    ABOUT("このアプリ"),

    /** このアプリ配下の 2 階層目(#48)。戻る操作は [MainViewModel.onCloseSettingsSubpage] が ABOUT へ戻す */
    LICENSES("オープンソースライセンス"),

    /** 開発者向け配下の 2 階層目(#70)。戻る操作は DEVELOPER へ戻す */
    DEVELOPER_POIS("取得した地図データ"),
    ;

    /**
     * 2 階層目のサブページの親カテゴリ(1 階層目は null)。戻る操作
     * ([MainViewModel.onCloseSettingsSubpage])と、二ペイン時に一覧側でハイライトする行の算出
     * (2 階層目を開いている間は親カテゴリの行を選択中として見せる。#56)で共用する。
     */
    val parent: SettingsSubpage?
        get() = when (this) {
            LICENSES -> ABOUT
            DEVELOPER_POIS -> DEVELOPER
            else -> null
        }
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    data class SearchResult(
        val merchant: Merchant,
        /**
         * 傘下看板(業態)のキーに一致したヒットならその id / 表示名(例: 杏林堂薬局)。
         * null は系列(グループ)としてのヒット(代表看板の名前・カテゴリのみの絞り込み)。
         */
        val bannerId: String? = null,
        val bannerName: String? = null,
        /** 最良特典のラベル(定率なら「7% 還元」、定額なら「300円引き」等)。特典を整形できない場合のみ null */
        val bestBenefit: BenefitLabel?,
        val campaignCount: Int,
        /** ブランドカラー("#RRGGBB")を還元率の高い順に最大3色 */
        val brandColors: List<String>,
        val hasTimeLimited: Boolean = false,
    )

    data class Selection(
        val merchant: Merchant,
        val judgments: List<CampaignJudgment>,
        val bestOption: BestPaymentOption? = null,
        /**
         * どの看板(業態)としての判定か(POI 照合・看板ヒットの検索由来)。null はグループ視点
         * (看板スコープの施策も注記付きで全部出す)。地図ブリッジのレンズにも引き継ぐ。
         */
        val bannerId: String? = null,
        /** 公式が対象/対象外を言い切っているチェーンか。true のときだけ対象判定画面へ遷移できる */
        val canCheckStore: Boolean = false,
        /** 店舗対象判定画面を開くときのプリフィル(検索クエリやNearbyのPOI名の店舗名部分) */
        val storeNameHint: String = "",
        /**
         * 画面タイトルに出す表示名(近隣リストの POI 名=支店付き)。
         * null のときはチェーン名(merchant.name)を使う。
         */
        val displayName: String? = null,
        /**
         * ユーザーが「このお店では対象外」と登録して間引かれた施策(#63)。
         * displayName あり(具体的なお店として開いた)のときだけ入り、「登録済み」の
         * 畳み表示+解除の導線に使う。
         */
        val excludedJudgments: List<CampaignJudgment> = emptyList(),
        /**
         * 提示のみ施策(カード現物提示 #80・プログラム会員提示 #39)。判定リストとは分けて
         * 「あわせて提示」の並記枠に出す(支払い方法の選択肢ではないため)。
         */
        val presentationJudgments: List<CampaignJudgment> = emptyList(),
        /** 提示スタック合算(#13)。bestOption + presentationJudgments から judgeAll が算出したもの */
        val stackedRate: StackedRate? = null,
        /** 定額特典のアドバイス(#13)。最大おトク率バナーの2行目(○○円未満なら定額が得)に使う */
        val fixedAdvice: FixedBenefitAdvice? = null,
    )

    data class StoreCheckState(
        val merchant: Merchant,
        val input: String,
        val verdicts: List<StoreVerdict>,
    )

    data class NearbyPlace(
        val name: String,
        /** 起点(現在地 or 地名検索地点)からの距離(m)。距離ラベル表示用 */
        val distanceMeters: Int,
        /** 地図中心(検索時のカメラ中心)からの距離(m)。リストのソート用 */
        val distanceFromCenter: Int,
        val merchant: Merchant?,
        /** POI が一致した看板(業態)の id(代表看板は merchant.id)。業態レンズの絞り込みに使う */
        val bannerId: String? = null,
        /** 最良特典のラベル(定率なら「7% 還元」、定額なら「300円引き」等)。null なら特典表示なし */
        val bestBenefit: BenefitLabel?,
        val lat: Double,
        val lon: Double,
        /**
         * 対応する全施策のブランドカラー("#RRGGBB")を還元率の高い順・重複排除で並べたもの。
         * 地図ピンの着色に使い、複数あれば発行体ごとに色を分けて描く(両対応なら 2 色)。
         */
        val brandColors: List<String> = emptyList(),
        val hasTimeLimited: Boolean = false,
    )

    /**
     * 開発者向け「取得した地図データ」の 1 行(#70)。YOLP の生 POI と照合・間引きの結果。
     * 重複集約の前なので YOLP の同一店舗の重複登録もそのまま並ぶ(それ自体が実測情報)。
     * 収集運用での alias 補完の要否判断・yolp_coverage_note の実測根拠に使う。
     */
    data class DebugPoi(
        val name: String,
        val lat: Double,
        val lon: Double,
        /** matchStore の照合結果(系列名。看板ヒットは「系列名(看板名)」)。null = 一致なし */
        val matchLabel: String?,
        val status: DebugPoiStatus,
    )

    /** [DebugPoi] の表示可否と間引き理由 */
    enum class DebugPoiStatus(val label: String) {
        SHOWN("表示"),
        NO_MATCH("一致なし"),
        FACILITY_TENANT("テナント除外"),
        OFFICIALLY_EXCLUDED("公式対象外"),
        EXHAUSTIVE_INELIGIBLE("網羅リスト外"),
        NO_JUDGMENT("判定なし"),
    }

    /**
     * 「地図」の絞り込みレンズ 1 件。bannerId = null は系列(グループ)全体、
     * 非 null はその看板(業態)だけに絞る。等価判定は (merchant.id, bannerId) で行う
     * (Merchant はデータ更新で別インスタンスになり得るため)。
     */
    data class NearbyLens(
        val merchant: Merchant,
        val bannerId: String? = null,
    ) {
        /** ピル・見出しの表示名。業態レンズは業態名、グループレンズは merchant 名 */
        val label: String
            get() = bannerId?.let { merchant.bannerName(it) } ?: merchant.name

        fun matches(place: NearbyPlace): Boolean =
            place.merchant?.id == merchant.id && (bannerId == null || place.bannerId == bannerId)

        fun sameTarget(other: NearbyLens): Boolean =
            merchant.id == other.merchant.id && bannerId == other.bannerId
    }

    /**
     * 近隣取得のローディング段階。リングの待ち時間が何待ちかを表示で出し分けるために持つ。
     * 地図(Google Maps)はこの間まだ描画されていない=「地図の読み込み」ではない点に注意。
     */
    enum class NearbyLoadPhase {
        /**
         * 現在地の測位中(LocationProvider、最大10秒)。searchHere 経由では通らない。
         * FLP のキャッシュ位置が新鮮なとき(2段階表示の1段目)はここを通らず即 SEARCHING になる
         */
        LOCATING,

        /** YOLP で周辺店舗を取得中(待ち時間が最も読めない主因) */
        SEARCHING,
    }

    /** 地名検索で得たジオコーディング候補。候補リストから選択して起点にする */
    data class GeocodedPlace(
        val name: String,
        val fullAddress: String,
        val lat: Double,
        val lon: Double,
    )

    /**
     * 「地図」の地図に出す自治体施策のお知らせ(検索中心の所在自治体で開催中の施策)。
     * タップでキャンペーン詳細(施策別カード)を開くため、グループの施策一覧ごと持つ。
     */
    data class MunicipalNotice(
        /** 自治体名(例: "杉並区")。ピルの文言に使う */
        val label: String,
        val campaigns: List<Campaign>,
    )

    data class NearbyUi(
        val loading: Boolean = false,
        /** loading 中の段階。表示メッセージの出し分けに使う(loading=false のときは無意味) */
        val loadingPhase: NearbyLoadPhase = NearbyLoadPhase.SEARCHING,
        val error: String? = null,
        val places: List<NearbyPlace> = emptyList(),
        /**
         * ブリッジ(チェーン絞り込み)中のチェーンだが、網羅リストで対象外と確定して間引いた
         * 店舗の数(#70。重複排除と同じ「チェーン+支店名」単位)。0 件表示のとき
         * 「この範囲に無い」でなく「対象のお店ではないため表示していない」と案内するために持つ。
         */
        val ineligibleHiddenCount: Int = 0,
        /** YOLP 取得の生件数(照合・重複集約の前。#70)。空状態の取得サマリの出し分けに使う */
        val rawPoiCount: Int = 0,
        /** 検索の中心(=地図カメラ中心)。距離計算の起点。取得前は null */
        val centerLat: Double? = null,
        val centerLon: Double? = null,
        /** 実際の現在地(地図上の青ドット)。「このエリアを検索」しても保持する */
        val userLat: Double? = null,
        val userLon: Double? = null,
        /**
         * 一覧/ピンで選択中の店舗(プレビュー表示対象)。null なら一覧表示。
         * 地図はこの店にセンタリングしピンを強調する。判定詳細へはプレビューから明示遷移する。
         * 再検索(searchHere/fetchNearby/半径変更)で NearbyUi を作り直すたびに null に戻る。
         */
        val selectedPlace: NearbyPlace? = null,
        /**
         * 地図カメラのズーム。初回/現在地検索は既定値、「このエリアを検索」では検索時の地図ズームを
         * そのまま引き継ぐ(再センタリングで勝手にズームを変えないため。可視範囲=検索範囲の要)。
         */
        val zoom: Double = NEARBY_DEFAULT_ZOOM,
        /**
         * 検索が完了するたびに変わる世代スタンプ(=nearbyGeneration)。center/zoom が前回と同値でも
         * (現在地ボタンで GPS が同じ座標を返す等)カメラを検索中心へ寄せ直すためのキー。
         */
        val searchStamp: Int = 0,
        /**
         * 検索中心の所在自治体で開催中の自治体施策のお知らせ。検索完了後に非同期の
         * リバースジオコーディングで解決するため、検索直後は null → 解決できたら入る。
         * 解決失敗・該当なしは null のまま(参考情報なのでエラーは出さない)。
         */
        val municipalNotice: MunicipalNotice? = null,
    )

    data class UiState(
        val loading: Boolean = true,
        val error: String? = null,
        val query: String = "",
        val categories: List<String> = emptyList(),
        val selectedCategories: Set<String> = emptySet(),
        val results: List<SearchResult> = emptyList(),
        /** 検索にヒットしたが判定 0 件で一覧から落としたチェーンの表示名(0 件時の案内の出し分け用。#70) */
        val unrewardedNames: List<String> = emptyList(),
        /**
         * 期間限定ポイントの失効通知(#13)。施策・お店と独立の内容のためどのお店の判定画面でも
         * 同じ一覧を出す(施策 0 件でも表示。設計書 §4)。rebuild で算出する
         */
        val expiringPointNotices: List<ExpiringPointNotice> = emptyList(),
        val selection: Selection? = null,
        val storeCheck: StoreCheckState? = null,
        val dataUpdatedAt: String = "",
        val dataSource: DataSource? = null,
        /** 表示中データのフル commit SHA(開発者向け設定の表示用)。同梱データ・解決失敗時は null */
        val dataCommitSha: String? = null,
        val refreshing: Boolean = false,
        val refreshFailed: Boolean = false,
        val nearby: NearbyUi? = null,
        /** 近隣の再検索が失敗したときの Snackbar 文言(地図は残す一時失敗)。表示後に null へ戻す */
        val nearbySearchFailed: String? = null,
        /**
         * 「地図」のジャンル絞り込み。お店側の selectedCategories とは独立に持つ
         * (一方の絞り込みが他方に波及しないように)。空セットは全ジャンル。
         * クライアント側フィルタなので YOLP 再取得は不要で、再検索(searchHere/半径変更/
         * fetchNearby)をまたいで保持したいため NearbyUi(毎回作り直す)でなくここに置く。
         */
        val nearbySelectedCategories: Set<String> = emptySet(),
        /**
         * 「地図」のお店絞り込み(レンズ2段目)。非空なら ジャンル絞り込みより優先し、地図/一覧を
         * これらのレンズ(系列 or 業態)だけに絞る。在チェーン選択((2))とブリッジ(探す→近く・
         * おトクの施策詳細,(3))の着地状態を共有する(単一チェーンのブリッジは要素1個の Set)。
         * 空セットで未絞り込み。表示名にのみ Merchant を使う(フィルタは id 比較)。
         */
        val nearbyMerchantFilters: Set<NearbyLens> = emptySet(),
        /**
         * 「地図」の起点(地名検索)。null は GPS 起点(既定)。設定中は距離・並び順をこの地点から測る。
         * 再検索(searchHere/fetchNearby)をまたいで保持し、「現在地で検索」/検索バーの✕で null に戻す。
         */
        val nearbyOrigin: GeocodedPlace? = null,
        /** ジオコーディング候補リスト。検索バーで地名を入力→送信後に結果が入る */
        val geocodeCandidates: List<GeocodedPlace> = emptyList(),
        /** ジオコーディング中フラグ */
        val isGeocoding: Boolean = false,
        val selectedTab: AppTab = AppTab.SEARCH,
        /**
         * 登録地域で開催中の自治体施策がある自治体名(お店タブ初期画面のお知らせバナー用)。
         * 施策の詳細は出さず「あること」だけ知らせ、タップでおトクタブへ送る。
         */
        val searchMunicipalAreaNames: List<String> = emptyList(),
        val campaignFilter: CampaignFilter = CampaignFilter.ALL,
        /** おトクタブの開催中一覧(常設 card_program 含む。セクション分けは CampaignScreen 側) */
        val campaignsActive: List<Campaign> = emptyList(),
        val campaignsUpcoming: List<Campaign> = emptyList(),
        /**
         * おトクタブ一覧の表示レート上書き(施策 id → 円換算済みの実質率。1pt価値・倍率込み)。
         * 所有カードの card_program のカード実効率由来に加え、換算で値が変わる施策(QR/promotion
         * 含む)も載る(お店タブと同じ基準=resolveCardCampaignRate + effectiveValueRate)。
         * 載っていない施策は施策側の率(rate_base 等)で表示する
         */
        val campaignPersonalRates: Map<String, Double> = emptyMap(),
        /**
         * 施策詳細の率別グルーピング用(#52): 店舗別レート(rate_override)を持つ managed 施策の
         * 施策 id → (merchant_id → 実効率)。所有カードの card_program はクラス加算・1pt価値を
         * 合成済み(お店タブの判定と同じ値)
         */
        val campaignStoreRates: Map<String, Map<String, Double>> = emptyMap(),
        val merchantNames: Map<String, String> = emptyMap(),
        /** id → Merchant(統合データ)。施策詳細の「対象:」で banner_ids を業態名に解決するのに使う(#60) */
        val merchantsById: Map<String, Merchant> = emptyMap(),
        /** 施策 id → 発行体の識別色(#RRGGBB)。色は施策でなく発行体カタログ側に持つため、ここで解決して配る */
        val campaignBrandColors: Map<String, String> = emptyMap(),
        val selectedCampaignGroup: List<CampaignJudgment>? = null,
        /**
         * 施策詳細→地図ブリッジの復元先。ブリッジ時に閉じた施策詳細(selectedCampaignGroup)を保持し、
         * 地図タブで戻る操作をしたときにおトクタブ+施策詳細へ復帰する。下部ナビでの手動タブ切替は
         * 通常のタブ移動なので破棄する(onSelectTab)。
         */
        val campaignBridgeReturn: List<CampaignJudgment>? = null,
        /**
         * 判定詳細→地図ブリッジの復元先(campaignBridgeReturn のお店タブ版)。「近くのこのお店を探す」で
         * 閉じた判定詳細(selection)を保持し、地図タブで戻る操作をしたときにお店タブ+判定詳細へ復帰する。
         */
        val selectionBridgeReturn: Selection? = null,
        // --- 設定値(DataStore 由来) ---
        val themeMode: ThemeMode = ThemeMode.SYSTEM,
        val dynamicColor: Boolean = true,
        val autoRefresh: Boolean = true,
        /** キャンペーン通知(#6)の ON/OFF */
        val notificationsEnabled: Boolean = false,
        /** キャンペーン通知の時刻(0時からの分。既定 8:00)。UI は15分刻みで編集する */
        val notificationTimeMinutes: Int = 8 * 60,
        /** 設定画面の「マイカード」用カタログ(未所有カードも含む全候補) */
        val cardSettings: List<CardSetting> = emptyList(),
        val pointCurrencySettings: List<PointCurrencySetting> = emptyList(),
        /** 設定画面の「国際ブランド」用(カタログの card_brands 由来。常時表示) */
        val brandSettings: List<BrandSetting> = emptyList(),
        /** 設定画面の「QR 決済」用カタログ(payment_methods.json のカタログ + ユーザー差分) */
        val qrPaymentSettings: List<QrPaymentSetting> = emptyList(),
        /** 設定画面「マイカード」に出すカスタムカード(カタログ外。DataStore 由来) */
        val customCards: List<CustomCard> = emptyList(),
        /**
         * カスタムキャンペーンの登録内容(DataStore 由来)。おトクタブの編集ダイアログが参照する。
         * 現在のデータモード(useTestData)側のリストのみ(#65。通常/テストで保存が分かれる)。
         */
        val customCampaigns: List<CustomCampaign> = emptyList(),
        /**
         * 終了日を過ぎたカスタムキャンペーン(Campaign 変換済み)。期限切れの同梱施策は一覧から
         * 消えるだけでよいが、カスタムは消えると編集・削除の入口を失うため専用セクションに出す。
         */
        val expiredCustomCampaigns: List<Campaign> = emptyList(),
        /**
         * ユーザー登録の対象外 (施策, 店舗) ペア(#63。DataStore 由来)。設定画面の管理一覧が参照する。
         * 現在のデータモード(useTestData)側のリストのみ(#68。通常/テストで保存が分かれる)。
         */
        val excludedStorePairs: List<ExcludedStorePair> = emptyList(),
        /**
         * 統合データの全施策(期限切れ・非アクティブ含む)の id → 名前。対象外ペア管理一覧の
         * 施策名解決に使い、ここに無い id は「終了したキャンペーン」(データから消えた)とみなす。
         */
        val allCampaignNames: Map<String, String> = emptyMap(),
        /**
         * 統合データ中で期間終了(EXPIRED)している施策の id。データにはまだ残っているが判定には
         * 出ないため、対象外ペア管理一覧ではデータから消えた登録と同じ「終了したキャンペーン」扱いにする。
         */
        val expiredCampaignIds: Set<String> = emptySet(),
        /** カタログのチェーン一覧(カスタムキャンペーン編集ダイアログの対象店舗ピッカー用) */
        val catalogMerchants: List<Merchant> = emptyList(),
        /** 登録済みエリア(自治体単体・グループ) */
        val registeredAreas: List<RegisteredArea> = emptyList(),
        /** 自治体マスタ。設定画面のピッカーとおトクタブの地域フィルタに使う(起動時に assets から読む) */
        val municipalityMaster: MunicipalityMaster = MunicipalityMaster(),
        /** おトクタブで「登録地域のみ」を解除して全件表示中か(セッション内のみ。永続化しない) */
        val showAllCampaigns: Boolean = false,
        val dataCommitRef: String = "",
        val useTestData: Boolean = false,
        val useBundledData: Boolean = false,
        val developerMode: Boolean = false,
        /** 表示中の設定サブページ(設定タブ上のオーバーレイ)。null ならトップページ */
        val settingsSubpage: SettingsSubpage? = null,
        /**
         * 開発者向け「取得した地図データ」の生 POI 一覧(#70)。開発者モード ON の検索時だけ
         * 記録される。設定画面から見るため、nearby と違い**地図タブを離れても保持**する
         * (検索のたびに上書き。開発者モード OFF で消去)。
         */
        val nearbyDebugPois: List<DebugPoi> = emptyList(),
        /**
         * 読み込み済みで、まだ適用していないバックアップ(#50)。非 null の間だけ復元の確認
         * ダイアログを出す。中身の要約を確認してから上書きさせるため、選択と適用を分ける。
         */
        val pendingSettingsImport: SettingsBackup? = null,
        /** エクスポート/インポートの結果通知(Snackbar)。表示後に消費する */
        val settingsBackupMessage: String? = null,
        /** 開発者向け操作(テスト通知等)の結果通知(Snackbar)。表示後に消費する */
        val developerMessage: String? = null,
        /**
         * 通知ディープリンク(#82)の引き当て失敗(終了済み・データ改定・テストデータ切替)の
         * Snackbar 文言。表示後に消費する
         */
        val notificationLinkMessage: String? = null,
    )

    /**
     * 設定画面の「国際ブランド」1件分。カタログのカードとは別に、イシュアー不問のブランド施策
     * (card_brand)向けに「このブランドのカードを持っている」を登録する。選択肢はカタログ
     * (payment_methods.json の card_brands)から常時出し、事前登録→施策開始と同時に判定へ反映する。
     */
    data class BrandSetting(
        val brand: String,
        val owned: Boolean,
        /** ブランドの識別色(#RRGGBB)。カタログ(card_brands)由来。防御的に追加した項目は null */
        val color: String? = null,
    )

    /** 設定画面の QR 決済1件分の表示・編集状態 */
    data class QrPaymentSetting(
        val id: String,
        val name: String,
        val brandColor: String,
        val enabled: Boolean,
    )

    /**
     * 設定画面「ポイント」1件分(#39)。会員チェック(membershipProgram の通貨のみ)と
     * ポイント倍率チェック(pointMultiplier を持つ通貨のみ)を通貨単位で出す。
     */
    data class PointCurrencySetting(
        val id: String,
        val name: String,
        /** プログラムの識別色(#RRGGBB)。名前の左のドット表示に使う */
        val brandColor: String?,
        /** 会員チェックを出すか(カード/アプリ提示の会員プログラムがある通貨) */
        val membershipProgram: Boolean,
        val member: Boolean,
        /**
         * ポイント倍率チェックの定義(null ならチェックを出さない)。factor は
         * ユーザーが選んだ倍率で差し替え済み(マージ層。#83)なので、UI はそのまま表示すればよい
         */
        val pointMultiplier: PointMultiplier?,
        val multiplierEnabled: Boolean,
        /** 倍率が掛かる決済手段名(この通貨を稼ぐ所有カード・利用中QR)。有効時の「○○の還元率を×1.5で表示中」注記に使う */
        val multiplierCardNames: List<String> = emptyList(),
        /**
         * 1pt 価値と倍率の合成後の 1pt 価値(円)。両方が効いているときだけ非 null(#83)。
         * 積になることに気付けるよう設定画面の注記に出す
         */
        val compositeValueYen: Double? = null,
        /** 1pt の価値(円)。上書きがあれば上書き値、無ければカタログ既定(#13: 通貨単位・全通貨で設定可能) */
        val valueYen: Double = 1.0,
        /** 1pt 価値の設定定義(カタログ)。label/note は J-POINT のように説明が要る通貨だけ持つ。null でも設定行は出す */
        val pointValueConfig: PointValueConfig? = null,
        /** valueYen がユーザー上書きでなくカタログ既定のままか(設定画面での表示・判定分岐に使う予定) */
        val valueIsDefault: Boolean = true,
        /** 期間限定ポイントの残高・失効日(通貨ごとに1件=直近失効分。#13)。null は未登録 */
        val balance: PointBalance? = null,
        /** balance の失効日を今日基準で過ぎているか(設定画面の「失効済み」表示に使う) */
        val balanceExpired: Boolean = false,
    )

    /** 設定画面のカード1枚分の表示・編集状態(payment_methods カタログ + ユーザー差分のマージ結果) */
    data class CardSetting(
        val cardId: String,
        val cardName: String,
        /** 発行体の識別色(#RRGGBB)。カード名の左のドット表示に使う */
        val brandColor: String?,
        val owned: Boolean,
        /**
         * 表示・編集する還元率(手入力可のカードは上書きがあれば上書き値、無ければ既定)。
         * ウエル活チェックの「○% で表示中」注記と手入力ダイアログの初期値に使う
         */
        val rate: Double,
        /**
         * 還元率を手入力できるカードか(単一率プログラム=SMCC/MUFG のみ true。allowsManualRate)。
         * false のカードは設定の余地が無いため還元率行自体を出さない
         */
        val rateEditable: Boolean,
        /** 実ブランド(ユーザー設定。単一ブランド製品は自動確定)。空文字は未選択 */
        val brand: String,
        /** この製品で選べるブランドの選択肢(カタログ) */
        val brands: List<String>,
        /** ブランド選択 UI を出すか。ブランドが判定に効き(ブランド除外/ブランド施策)かつ選択肢が複数のカードのみ true */
        val showBrandPicker: Boolean,
        /** このカードの施策で優遇対象外になり得るブランド(ineligible_brands の全ルール集約)。設定画面の警告文に使う */
        val ineligibleBrands: List<String>,
        /** カードクラスの選択肢(カタログ。JCB W/S 等)。空 = クラス概念なし(選択 UI を出さない) */
        val cardClasses: List<CardClass> = emptyList(),
        /** 選択中クラスの id(未選択はカタログ先頭=保守側) */
        val cardClassId: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    // applyData(IO) と設定購読(Main)の両方から書き換わるので可視性を確保する
    @Volatile
    private var engine: JudgmentEngine? = null

    /**
     * 通知タップ(#82)で開くべきキャンペーンのグループキー。コールドスタート時はデータロードが
     * 非同期のため、rebuild でデータが揃った時点で消費する(起動中のタップは即消費)
     */
    private var pendingNotificationGroupKey: String? = null

    private val locationProvider = LocationProvider(app)

    private val repository = DataRepository(
        readAsset = { path ->
            app.assets.open(path).bufferedReader().use { it.readText() }
        },
        cacheDir = File(app.filesDir, "remote_data"),
        fetchRemote = { fileName, ref, dataDir -> GithubRawClient.fetch(fileName, ref, dataDir) },
        resolveSha = { ref -> GithubRawClient.resolveCommitSha(ref) },
    )

    private val settingsRepo = SettingsRepository(app)

    /** 直近にロードしたデータと設定。どちらかが変わるたびに rebuild() でエンジン再構築する */
    @Volatile
    private var lastLoaded: LoadedData? = null

    @Volatile
    private var lastSettings: AppSettings = AppSettings()

    /**
     * 表示・変換用の統合データ(rebuild で構築)。同梱データにカスタムキャンペーン由来の
     * Campaign / 合成 Merchant と、カスタムカードを加えたもの。エンジン用(engineData)との違いは
     * カードが「所有のみ」でなく全カタログな点(未所有カード施策の色解決等、表示は全候補が要る)。
     */
    @Volatile
    private var displayData: PoikatsuData? = null

    private var lastFetchSucceededAt = 0L

    /** 起動後の通知ジョブ突き合わせ([CampaignNotifications.ensureScheduled])を 1 回だけにするフラグ */
    private var notificationJobChecked = false

    /**
     * 近隣取得の世代。タブ移動(onCloseNearby)や再取得のたびに進め、進行中の取得が
     * 完了しても古い世代なら結果を捨てる。読込中に「地図」タブを離れたのに、取得完了の
     * タイミングで勝手に地図タブへ戻されるのを防ぐ。書込はUIスレッドのみ・IOスレッドは
     * 読むだけなので @Volatile で可視性だけ確保する。
     */
    @Volatile
    private var nearbyGeneration = 0

    // リモート取得は init ではなく ON_START(onAppForeground)起点。
    // 初回起動時も Activity の ON_START で必ず一度走る。
    private val initialLoad: Job = viewModelScope.launch(Dispatchers.IO) {
        try {
            applyData(repository.loadLocal())
        } catch (e: Exception) {
            _state.update { it.copy(loading = false, error = "データの読み込みに失敗しました: ${e.message}") }
        }
    }

    // 自治体マスタ(assets 同梱)は起動時に読む。設定画面のピッカーに加え、おトクタブの
    // 地域フィルタ(rebuild)がグループ展開に使うため遅延ロードにしない。読めなければ空のまま
    // =フィルタ無効(全表示)に倒す。リモート取得の対象外だが data/⇔data-test/ の切替には追従する
    private val masterLoad: Job = loadMunicipalityMaster()

    private fun loadMunicipalityMaster(): Job = viewModelScope.launch(Dispatchers.IO) {
        val path = "${dataDir()}/municipalities.json"
        val master = try {
            val app = getApplication<Application>()
            val text = app.assets.open(path).bufferedReader().use { it.readText() }
            PoikatsuJson.parseMunicipalities(text)
        } catch (e: Exception) {
            Timber.w(e, "$path の読み込みに失敗")
            MunicipalityMaster()
        }
        _state.update { it.copy(municipalityMaster = master) }
        rebuild() // マスタ到着後にフィルタを適用し直す
    }

    // DataStore の設定変更を購読し、変わるたびにエンジン・状態を作り直す。
    // 初回 emission も既定値との差分として扱われる(useTestData 等が永続化済みならここで反映される)
    private val settingsJob: Job = settingsRepo.settings
        .onEach { new ->
            val refChanged = lastSettings.dataCommitRef != new.dataCommitRef
            val testDataChanged = lastSettings.useTestData != new.useTestData
            val bundledChanged = lastSettings.useBundledData != new.useBundledData
            lastSettings = new
            rebuild()
            // 通知ジョブは WorkManager の DB(no_backup 配下)にあり Auto Backup で復元されない。
            // 設定だけ復元された端末で「オンなのに来ない」状態になるため、起動後の初回 emission で
            // 設定と突き合わせて埋める(既に登録済みなら何もしない)
            if (!notificationJobChecked) {
                notificationJobChecked = true
                if (new.notificationsEnabled) {
                    CampaignNotifications.ensureScheduled(app, new.notificationTimeMinutes)
                }
            }
            if (testDataChanged) loadMunicipalityMaster() // マスタも data/⇔data-test/ を読み分ける
            when {
                // 同梱モード中はリモートを見ない。ON 直後と ON 中の data/⇔data-test/ 切替は assets 再読
                new.useBundledData && (bundledChanged || testDataChanged) -> loadBundled()
                // OFF 復帰は dataCommitRef 変更と同じ扱いで通常運用へ(キャッシュをリモートで上書き)
                !new.useBundledData && (bundledChanged || refChanged || testDataChanged) ->
                    refresh(force = true)
            }
        }
        .launchIn(viewModelScope)

    /** アプリがフォアグラウンドに来るたびに呼ぶ。自動更新 OFF のときは取得しない */
    fun onAppForeground() {
        if (lastSettings.autoRefresh) refresh(force = false)
    }

    /** 更新ボタン。スキップせず必ず取得を試みる */
    fun onManualRefresh() = refresh(force = true)

    /** 再取得失敗の Snackbar を表示し終えたらフラグを消費する(同じ失敗を再表示しない) */
    fun onRefreshFailedShown() = _state.update { it.copy(refreshFailed = false) }

    private fun refresh(force: Boolean) {
        // 同梱モード中はリモートで上書きしない(自動・手動とも。UI 側でも「今すぐ更新」を無効化)
        if (lastSettings.useBundledData) return
        if (_state.value.refreshing) return
        if (!force && System.currentTimeMillis() - lastFetchSucceededAt < AUTO_REFRESH_MIN_INTERVAL_MS) return
        viewModelScope.launch(Dispatchers.IO) {
            initialLoad.join() // ローカルロード完了前にリモート結果で上書きされない順序を保証
            _state.update { it.copy(refreshing = true) }
            val ref = lastSettings.dataCommitRef.ifBlank { "main" }
            val loaded = repository.refresh(ref, dataDir())
            if (loaded != null) {
                lastFetchSucceededAt = System.currentTimeMillis()
                applyData(loaded)
            }
            _state.update { it.copy(refreshing = false, refreshFailed = loaded == null) }
        }
    }

    /**
     * 同梱 assets を直読して反映する(「同梱データを使う」ON 時)。キャッシュは見ないため、
     * installDebug で焼き直した JSON がそのまま出る。パース失敗(編集ミス等)は直前のデータを
     * 残して refreshFailed の Snackbar で知らせる
     */
    private fun loadBundled() {
        viewModelScope.launch(Dispatchers.IO) {
            initialLoad.join()
            val loaded = runCatching { repository.loadBundled(dataDir()) }
                .onFailure { Timber.w(it, "同梱データの読み込みに失敗") }
                .getOrNull()
            if (loaded != null) applyData(loaded)
            _state.update { it.copy(refreshFailed = loaded == null) }
        }
    }

    /** データ取得元のディレクトリ。リモート(GitHub raw)・同梱 assets とも同じ構造で切り替わる */
    private fun dataDir() = if (lastSettings.useTestData) "data-test" else "data"

    companion object {
        // 施策データの更新は月数回程度なので、自動再取得は1時間に1回で十分
        private const val AUTO_REFRESH_MIN_INTERVAL_MS = 60 * 60_000L
    }

    private fun enabledQrIds(): Set<String> = lastSettings.enabledQrPaymentIds

    /**
     * チェーンと店舗名ヒントから判定詳細用の Selection を組む(判定・遷移可否をまとめて引く)。
     * bannerId はどの看板(業態)としての判定か(POI 照合・看板ヒットの検索由来)。null はグループ視点。
     */
    private fun JudgmentEngine.selectionFor(
        merchant: Merchant,
        storeNameHint: String,
        displayName: String? = null,
        bannerId: String? = null,
    ): Selection {
        // 具体的なお店として開いたとき(displayName = POI 名)だけ、ユーザー登録の対象外ペア(#63)と
        // 網羅リストの店舗対象外(#64)を適用する。チェーン視点(お店タブの検索ヒット)は
        // 店舗が特定できないため適用しない
        val excludedIds = displayName
            ?.let { excludedCampaignIdsFor(merchant, it, lastSettings.activeExcludedStorePairs) }
            ?: emptySet()
        val ineligibleIds = displayName
            ?.let { exhaustiveListIneligibleCampaignIds(merchant, it) }
            ?: emptySet()
        val result = judgeAll(
            merchant,
            LocalDate.now(),
            enabledQrIds(),
            bannerId,
            excludedIds,
            ineligibleIds,
            memberships = lastSettings.pointProgramMemberships,
        )
        return Selection(
            merchant = merchant,
            judgments = result.judgments,
            bestOption = result.bestOption,
            bannerId = bannerId,
            canCheckStore = canCheckStore(merchant),
            storeNameHint = storeNameHint,
            displayName = displayName,
            excludedJudgments = result.excludedJudgments,
            presentationJudgments = result.presentationJudgments,
            stackedRate = result.stackedRate,
            fixedAdvice = result.fixedAdvice,
        )
    }

    /** [searchRewarded] の結果。表示する結果と、ヒットしたが判定 0 件で一覧から落としたチェーンの表示名 */
    data class SearchOutcome(
        val results: List<SearchResult> = emptyList(),
        val unrewardedNames: List<String> = emptyList(),
    )

    /**
     * 検索結果のうち、所有カードで対象になる施策が1つ以上あるチェーンだけ残す(reward 無しは一覧に出さない)。
     * 落としたヒットの表示名は unrewardedNames として別枠で返す — 検索 0 件時に
     * 「アプリ未収録」と「収録済みだが今出せるキャンペーンが無い」を区別して案内するため(#70)
     */
    private fun JudgmentEngine.searchRewarded(query: String, categories: Set<String>): SearchOutcome {
        val today = LocalDate.now()
        val results = mutableListOf<SearchResult>()
        val unrewarded = mutableListOf<String>()
        search(query, categories).forEach { hit ->
            // 業態ヒットはその業態としての判定(看板スコープ外の施策は数えない)
            val result = judgeAll(
                hit.merchant,
                today,
                enabledQrIds(),
                hit.bannerId,
                memberships = lastSettings.pointProgramMemberships,
            )
            // 並記枠(提示のみ)しか無いチェーンも「特典あり」として一覧に残す
            val visible = result.judgments + result.presentationJudgments
            if (visible.isEmpty()) {
                unrewarded += hit.bannerName ?: hit.merchant.name
                return@forEach
            }
            val allCampaigns = visible.map { it.campaign }
            results += SearchResult(
                merchant = hit.merchant,
                bannerId = hit.bannerId,
                bannerName = hit.bannerName,
                bestBenefit = result.bestBenefitLabel(),
                campaignCount = allCampaigns.distinctBy { it.id }.size,
                brandColors = visible.mapNotNull { it.brandColor }.distinct().take(3),
                hasTimeLimited = allCampaigns.any { it.isTimeLimited },
            )
        }
        return SearchOutcome(results, unrewarded)
    }

    private fun applyData(loaded: LoadedData) {
        lastLoaded = loaded
        rebuild()
        // 旧カード単位の 1pt 価値を通貨単位へ移行(#13)。対応表はカタログから引く。
        // 何度呼んでも安全な処理(migrateCardPointValues 参照)なのでデータロードのたびに発火してよい
        viewModelScope.launch { settingsRepo.migrateCardPointValues(cardToCurrencyMap(loaded)) }
    }

    /** カード→ポイント通貨 ID の対応表をカタログから作る(applyData と onConfirmSettingsImport で共用)。 */
    private fun cardToCurrencyMap(loaded: LoadedData): Map<String, String> =
        loaded.data.cards.mapNotNull { c -> c.pointCurrencyId?.let { c.id to it } }.toMap()

    /**
     * 直近のデータ(lastLoaded)とユーザー設定(lastSettings)からエンジンを作り直し状態へ反映する。
     * エンジンへは「所有カードのみ + 還元率/ブランド/ウエル活上書き」をマージしたカード一覧を渡す
     * (JudgmentEngine 自体は純 Kotlin のまま=実データテストを維持)。設定画面用カタログ(未所有も含む)は別に組む。
     */
    private fun rebuild() {
        val loaded = lastLoaded ?: return
        val settings = lastSettings
        val baseCards = loaded.data.cards

        // カタログ+ユーザー設定のマージは通知ジョブ(CampaignNotificationWorker)と共通の
        // 純関数に委譲する(アプリの表示と通知の判定基準を揃える。詳細は domain/UserDataMerge.kt)
        val merged = mergeUserData(
            base = loaded.data,
            cardOverrides = settings.cardOverrides,
            ownedBrands = settings.ownedBrands,
            customCards = settings.customCards,
            customCampaigns = settings.activeCustomCampaigns,
            enabledPointMultipliers = settings.enabledPointMultipliers,
            pointCurrencyValues = settings.pointCurrencyValues,
            pointMultiplierFactors = settings.pointMultiplierFactors,
        )
        val newEngine = JudgmentEngine(merged.engineData)
        engine = newEngine
        val newDisplayData = merged.displayData
        displayData = newDisplayData
        // 所有カードの id → マージ済みカード。ユーザー設定の実効率を持ち、おトクタブの
        // card_program 表示レート解決(resolveCardCampaignRate)に使う。未所有は施策側の率へフォールバック
        val newOwnedCardsById = merged.engineData.cards.associateBy { it.id }

        val today = LocalDate.now()
        // おトクタブの一覧。登録エリアがあれば既定で絞り込む(「すべて表示」トグルで解除可)
        val applyAreaFilter: (List<Campaign>) -> List<Campaign> = { campaigns ->
            if (_state.value.showAllCampaigns) campaigns
            else filterCampaignsByArea(campaigns, settings.registeredAreas, _state.value.municipalityMaster)
        }
        // card_program(常設)も含めて全部出す(常設セクションへの振り分けは CampaignScreen 側で
        // isTimeLimited を見て行う)。通知対象は従来どおり NotificationPlanner 側で card_program を除外
        val campaignsActive = applyAreaFilter(newEngine.activeCampaigns(today))
        val campaignsUpcoming = applyAreaFilter(newEngine.upcomingCampaigns(today))
        // おトクタブ一覧の表示レートをお店タブと同じ基準(resolveCardCampaignRate + 円換算)にする:
        // 所有カードの card_program はユーザー実効率で出す。施策側の率を使う施策も、払い出し通貨の
        // 円価値(1pt価値 × 倍率)で換算した値にする(#39/#13。judgeCards/judgeQr と同じ基準)。
        // どちらにも載らない施策(未所有カード・換算で値が変わらない QR/自治体)は施策側の率のまま
        val mergedCurrencies = merged.engineData.pointCurrencies
        // 期間限定ポイントの失効通知(#13): 施策・お店と独立の内容のためどのお店の判定画面でも
        // 同じ一覧を出す(設計書 §4)。判定(Selection)には手を入れず UiState 側で持つ
        val expiringNotices = expiringPointNotices(settings.pointBalances, mergedCurrencies, today)
        // 払い出し通貨の引数解決(cardId→所有カード / paymentMethodId→QR)は engine に一本化(#85)
        val payoutCurrencyOf = { c: Campaign -> newEngine.payoutCurrencyOf(c) }
        val campaignPersonalRates = (campaignsActive + campaignsUpcoming)
            .mapNotNull { c ->
                val resolved = if (c.cardId != null) resolveCardCampaignRate(c, newOwnedCardsById[c.cardId]) else null
                val nominal = resolved?.effectiveRate ?: c.rateBase
                val effective = effectiveValueRate(nominal, payoutCurrencyOf(c))
                when {
                    // 所有カードの実効率(ユーザー個別の値)は常に載せる
                    resolved?.usesCardRate == true -> c.id to (effective ?: 0.0)
                    // 施策側の率は円換算で収録値と変わるときだけ載せる(等価なら収録値のままでよい)
                    effective != null && effective != nominal -> c.id to effective
                    else -> null
                }
            }
            .toMap()
        // 施策詳細の率別グルーピング用(#52): 店舗別レート(rate_override)を持つ施策の
        // merchant_id → 実効率。お店タブの判定と同じ基準(resolveCardCampaignRate + 円換算)で
        // 解決するため、所有カードの card_program はクラス加算を合成した名目率に払い出し通貨の
        // 円価値を掛けた値、未所有・QR は収録値を同じ係数で換算した値になる(#39/#13)
        val campaignStoreRates = (campaignsActive + campaignsUpcoming)
            .filter { c -> c.storeScope == "managed" && c.merchantRules.any { it.rateOverride != null } }
            .associate { c ->
                val currency = payoutCurrencyOf(c)
                c.id to c.merchantRules.mapNotNull { r ->
                    val resolved = if (c.cardId != null) {
                        resolveCardCampaignRate(c, newOwnedCardsById[c.cardId], r.rateOverride)
                    } else {
                        null
                    }
                    val nominal = if (resolved != null) resolved.effectiveRate else (r.rateOverride ?: c.rateBase)
                    effectiveValueRate(nominal, currency)?.let { r.merchantId to it }
                }.toMap()
            }
        // 終了日を過ぎたカスタムキャンペーンは判定・一覧から消えるが、編集・削除の入口を残すため
        // おトクタブの専用セクション用に別で持つ(同梱施策の期限切れは単に出さない)
        val expiredCustomCampaigns = merged.engineData.campaigns.filter {
            it.isCustom && newEngine.campaignStatus(it, today) == CampaignStatus.EXPIRED
        }

        // お店タブ初期画面のお知らせバナー: 登録地域で開催中の自治体施策がある自治体名。
        // フィルタと違い厳密一致(未登録・マスタ未ロードなら出さない)
        val searchMunicipalAreaNames = municipalCampaignsForAreas(
            newEngine.activeCampaigns(today),
            settings.registeredAreas,
            _state.value.municipalityMaster,
        ).mapNotNull { it.region?.name }.distinct()

        // 設定画面「マイカード」カタログ: 未所有カードも含む全候補 + 現在の上書き値
        val hasBrandCampaign = loaded.data.campaigns.any { it.cardBrand != null }
        val cardSettings = baseCards.map { card ->
            val ov = settings.cardOverrides[card.id]
            // 1カード:N施策なので、紐づくどれかの施策に ineligible_brands ルールがあればブランド選択を出す
            val cardCampaigns = loaded.data.campaigns.filter { it.cardId == card.id }
            val ineligibleBrands = cardCampaigns
                .flatMap { c -> c.merchantRules.flatMap { it.ineligibleBrands } }
                .distinct()
            val brandAffectsJudgment = ineligibleBrands.isNotEmpty() || hasBrandCampaign
            // クラスを持つカード(JCB W/S 等)の表示レートは UserDataMerge と同じ式で導出する:
            // (率 + クラス加算)の名目率。1pt 価値の円換算はスコア層に一本化したためここでは掛けない
            // (#13。この行は還元率の手入力ダイアログの初期値で、手入力値と同じ土俵=名目である必要がある)。
            // 手入力レートは単一率プログラムのカードだけに効く(UserDataMerge と同じガード)
            val selectedClass = card.cardClasses.firstOrNull { it.id == ov?.cardClass }
                ?: card.cardClasses.firstOrNull()
            val rateEditable = card.allowsManualRate(loaded.data.campaigns)
            val manualRate = ov?.rate?.takeIf { rateEditable }
            CardSetting(
                cardId = card.id,
                cardName = card.cardName,
                brandColor = card.brandColor,
                owned = ov?.owned ?: true,
                rate = (manualRate ?: card.effectiveRateDefault ?: 0.0) +
                    (selectedClass?.rateBonus ?: 0.0),
                rateEditable = rateEditable,
                brand = ov?.brand ?: card.brands.singleOrNull().orEmpty(),
                brands = card.brands,
                // ブランドが判定に効き(ブランド除外 or ブランド施策あり)、かつ製品として選択肢が複数
                // あるカードだけ選択 UI を出す(単一ブランド製品は固定なので出さない)
                showBrandPicker = brandAffectsJudgment && card.brands.size > 1,
                ineligibleBrands = ineligibleBrands,
                cardClasses = card.cardClasses,
                cardClassId = selectedClass?.id,
            )
        }

        // 設定画面「国際ブランド」: カタログ(card_brands)の選択肢を常時出す。事前に登録しておけば
        // ブランド施策の開始と同時に(設定画面を見なくても)判定に現れる。施策側が参照しているのに
        // カタログに無いブランドがあれば防御的に追加する(データ不整合時も登録手段を失わないように)
        val brandSettings = (loaded.data.cardBrands.map { it.name } + loaded.data.campaigns.mapNotNull { it.cardBrand })
            .distinct()
            .map { brand ->
                BrandSetting(
                    brand = brand,
                    owned = brand in settings.ownedBrands,
                    color = loaded.data.cardBrands
                        .firstOrNull { it.name.equals(brand, ignoreCase = true) }?.color,
                )
            }

        val qrPaymentSettings = loaded.data.qrPayments.map { qr ->
            QrPaymentSetting(
                id = qr.id,
                name = qr.name,
                brandColor = qr.brandColor,
                enabled = qr.id in settings.enabledQrPaymentIds,
            )
        }

        // 設定画面「ポイント」: 会員登録・倍率チェック・1pt価値の3役を担うセクション(#39/#13)。
        // 1pt価値はカタログの membership_program・point_multiplier・point_value 定義の有無に
        // 依存せず全通貨で編集可能(既定 1.0 円)なため、定義でフィルタはしない
        // (membership/multiplier 行は各通貨の定義有無で個別に出し分ける)。
        // 唯一の例外は円建て通貨(value_fixed。au PAY残高等。#83): 増価の概念が無く調整の余地も
        // 無いため行自体を出さない(#58「設定の余地があるものだけを置く」と同じ判断)。
        // マージ済みの通貨マスタから組むので factor はユーザー選択が反映済み・valueYen は
        // 円建てなら 1.0 に固定済みで、判定と設定表示が同じ値を見る
        val pointCurrencySettings = merged.engineData.pointCurrencies
            .filterNot { it.valueFixed }
            .map { currency ->
                PointCurrencySetting(
                    id = currency.id,
                    name = currency.name,
                    brandColor = currency.brandColor,
                    membershipProgram = currency.membershipProgram,
                    member = currency.id in settings.pointProgramMemberships,
                    pointMultiplier = currency.pointMultiplier,
                    multiplierEnabled = currency.multiplierEnabled,
                    // カードだけでなく QR も見る。Ponta のように「稼ぐ手段が QR だけ」の通貨で
                    // 適用中の注記が出ないため(#83)
                    multiplierCardNames = merged.engineData.cards
                        .filter { it.pointCurrencyId == currency.id }
                        .map { it.cardName } +
                        merged.engineData.qrPayments
                            .filter { it.pointCurrencyId == currency.id && it.id in settings.enabledQrPaymentIds }
                            .map { it.name },
                    compositeValueYen = compositeValueYen(currency),
                    valueYen = currency.valueYen,
                    pointValueConfig = currency.pointValueConfig,
                    valueIsDefault = currency.id !in settings.pointCurrencyValues,
                    balance = settings.pointBalances[currency.id],
                    balanceExpired = settings.pointBalances[currency.id]
                        ?.isExpired(LocalDate.now()) == true,
                )
            }

        _state.update {
            val searchOutcome = newEngine.searchRewarded(it.query, it.selectedCategories)
            it.copy(
                loading = false,
                error = null,
                dataUpdatedAt = loaded.data.updatedAt,
                dataSource = loaded.source,
                dataCommitSha = loaded.commitSha,
                categories = newEngine.categories,
                results = searchOutcome.results,
                unrewardedNames = searchOutcome.unrewardedNames,
                expiringPointNotices = expiringNotices,
                selection = it.selection?.let { sel ->
                    newDisplayData.merchants.firstOrNull { m -> m.id == sel.merchant.id }
                        ?.let { m -> newEngine.selectionFor(m, sel.storeNameHint, sel.displayName, sel.bannerId) }
                },
                storeCheck = it.storeCheck?.let { sc ->
                    newDisplayData.merchants.firstOrNull { m -> m.id == sc.merchant.id }
                        ?.takeIf { m -> newEngine.canCheckStore(m) }
                        ?.let { m -> StoreCheckState(m, sc.input, newEngine.checkStore(m, sc.input)) }
                },
                themeMode = settings.themeMode,
                dynamicColor = settings.dynamicColor,
                autoRefresh = settings.autoRefresh,
                notificationsEnabled = settings.notificationsEnabled,
                notificationTimeMinutes = settings.notificationTimeMinutes,
                cardSettings = cardSettings,
                pointCurrencySettings = pointCurrencySettings,
                brandSettings = brandSettings,
                qrPaymentSettings = qrPaymentSettings,
                customCards = settings.customCards,
                customCampaigns = settings.activeCustomCampaigns,
                expiredCustomCampaigns = expiredCustomCampaigns,
                excludedStorePairs = settings.activeExcludedStorePairs,
                allCampaignNames = merged.engineData.campaigns.associate { c -> c.id to c.name },
                expiredCampaignIds = merged.engineData.campaigns
                    .filter { c -> newEngine.campaignStatus(c, today) == CampaignStatus.EXPIRED }
                    .map { c -> c.id }
                    .toSet(),
                // 開いている地図があれば判定由来の表示(ラベル・ピン色・表示可否)を新しい
                // エンジン・設定で作り直す(対象外ペアの登録・解除を YOLP 再検索なしで即反映する)
                nearby = it.nearby?.let { n ->
                    recomputeNearbyPlaces(n, newEngine, settings, it.nearbyMerchantFilters)
                },
                catalogMerchants = loaded.data.merchants,
                registeredAreas = settings.registeredAreas,
                dataCommitRef = settings.dataCommitRef,
                useTestData = settings.useTestData,
                useBundledData = settings.useBundledData,
                developerMode = settings.developerMode,
                // 開発者モード OFF は開発者向け設定の一括リセット。生 POI 記録もここで消す
                nearbyDebugPois = if (settings.developerMode) it.nearbyDebugPois else emptyList(),
                campaignsActive = campaignsActive,
                campaignsUpcoming = campaignsUpcoming,
                campaignPersonalRates = campaignPersonalRates,
                campaignStoreRates = campaignStoreRates,
                searchMunicipalAreaNames = searchMunicipalAreaNames,
                // 名前・色の解決はカスタム分(合成 Merchant・カスタムカードの色)も含む統合データから引く
                merchantNames = newDisplayData.merchants.associate { it.id to it.name },
                merchantsById = newDisplayData.merchants.associateBy { it.id },
                campaignBrandColors = newDisplayData.campaigns
                    .mapNotNull { c -> newDisplayData.brandColorOf(c)?.let { c.id to it } }
                    .toMap(),
            )
        }
        // 通知タップ(#82)がデータロード待ちだったら、揃った今開く
        consumePendingNotificationLink()
    }

    fun onQueryChange(query: String) {
        _state.update {
            val outcome = engine?.searchRewarded(query, it.selectedCategories) ?: SearchOutcome()
            it.copy(
                query = query,
                results = outcome.results,
                unrewardedNames = outcome.unrewardedNames,
                selection = null,
                storeCheck = null,
            )
        }
    }

    /** 位置情報パーミッション取得済みの前提で呼ぶ(UI側でリクエスト) */
    fun fetchNearby() {
        if (engine == null) return
        val isInitial = _state.value.nearby?.centerLat == null
        val gen = ++nearbyGeneration
        // 再取得(📍)中も直前の地図・一覧は残し loading だけ立てる(画面をまっさらにしない)。
        // 初回は prev が無い=NearbyUi() なので center も null となり全画面ローディングになる。
        // nearbyOrigin は位置情報の取得に成功してからクリアする(失敗時に起点表示が変わらないように)。
        _state.update {
            val base = it.nearby ?: NearbyUi()
            it.copy(
                nearby = base.copy(
                    loading = true,
                    loadingPhase = NearbyLoadPhase.LOCATING,
                    error = null,
                    selectedPlace = null,
                ),
                selection = null,
                storeCheck = null,
                geocodeCandidates = emptyList(),
                isGeocoding = false,
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            if (!locationProvider.hasPermission()) {
                val msg = "位置情報の許可が必要です。端末の設定からこのアプリに位置情報を許可してください"
                if (isInitial) fallbackToDefaultPlace(gen, msg) else failNearby(gen, msg)
                return@launch
            }
            // 2段階表示: FLP のキャッシュ位置(新鮮なときだけ返る)があればまずそれで即座に
            // 地図・検索を出し、並行して取っている新鮮な測位が大きくずれていたら検索し直す。
            // キャッシュが無ければ従来どおり測位を待つ(LOCATING 表示)。
            val freshDeferred = async { locationProvider.currentLocation() }
            val cached = locationProvider.lastLocation()
            if (cached != null) {
                searchAroundLocation(gen, cached)
                val fresh = freshDeferred.await() ?: return@launch
                // 測位待ちの間に別の検索(このエリアを検索・地名検索・タブ移動)が始まっていたら
                // 補正で上書きしない
                if (gen != nearbyGeneration) return@launch
                val movedM = GeoMath.distanceMeters(
                    cached.latitude, cached.longitude, fresh.latitude, fresh.longitude,
                )
                if (movedM >= LOCATION_CORRECTION_M) {
                    // キャッシュ位置がずれていた: 同じ操作の続きとして新鮮な位置で取り直す
                    // (loading・カメラ寄せは searchAroundLocation → showMapAt が立て直す)
                    searchAroundLocation(gen, fresh)
                } else {
                    // ずれが小さければ青ドットだけ実測位置に直す(地図・一覧はそのまま)
                    updateUserLocation(fresh)
                }
            } else {
                val fresh = freshDeferred.await()
                if (fresh != null) {
                    searchAroundLocation(gen, fresh)
                } else {
                    val msg = "現在地を取得できませんでした。位置情報設定を確認してください"
                    if (isInitial) fallbackToDefaultPlace(gen, msg) else failNearby(gen, msg)
                }
            }
        }
    }

    /** 現在地(GPS 起点)を中心に既定半径で検索する。fetchNearby の1段目・2段目補正の共通処理 */
    private fun searchAroundLocation(gen: Int, location: Location) {
        _state.update { it.copy(nearbyOrigin = null) }
        showMapAt(gen, location.latitude, location.longitude, location.latitude, location.longitude)
        loadNearbyAround(
            gen, location.latitude, location.longitude, location.latitude, location.longitude,
            location.latitude, location.longitude,
            radiusM = NEARBY_DEFAULT_RADIUS_M, zoom = NEARBY_DEFAULT_ZOOM,
            adaptZoom = true,
        )
    }

    /**
     * 検索中心が確定した時点で、YOLP 取得を待たずに先に地図を出す。loading は立てたままにし、
     * 進捗(「周辺の店舗を探しています…」)はボトムシートと地図上のピルが表示する。
     * searchStamp をこの世代に進めてカメラを新しい中心へ寄せる(結果反映時は同値なので再移動しない。
     * adaptZoom による引きズームだけは zoom の変化で反映される)。
     */
    private fun showMapAt(gen: Int, centerLat: Double, centerLon: Double, userLat: Double?, userLon: Double?) {
        _state.update {
            if (gen != nearbyGeneration) return@update it
            val base = it.nearby ?: NearbyUi()
            it.copy(
                nearby = base.copy(
                    loading = true,
                    loadingPhase = NearbyLoadPhase.SEARCHING,
                    error = null,
                    selectedPlace = null,
                    centerLat = centerLat,
                    centerLon = centerLon,
                    userLat = userLat ?: base.userLat,
                    userLon = userLon ?: base.userLon,
                    zoom = NEARBY_DEFAULT_ZOOM,
                    searchStamp = gen,
                ),
            )
        }
    }

    /**
     * 青ドット(現在地表示)だけを実測位置に更新する。カメラ・検索結果・距離ラベルには触らない
     * (距離の再計算・YOLP 再検索はしない。再検索は「このエリアを検索」で明示的に行う方針)
     */
    private fun updateUserLocation(location: Location) {
        _state.update { st ->
            val nearby = st.nearby ?: return@update st
            st.copy(nearby = nearby.copy(userLat = location.latitude, userLon = location.longitude))
        }
    }

    /**
     * 現在地の継続購読。「地図」タブ表示中のみ UI 側(PoikatsuApp)から lifecycle スコープで呼び、
     * タブ離脱・バックグラウンドで collect ごとキャンセルされて購読解除される。
     * 青ドットを追従させるだけで、カメラ移動・YOLP 再検索は行わない。
     */
    suspend fun observeLocationUpdates() {
        if (!locationProvider.hasPermission()) return
        locationProvider.locationUpdates().collect { updateUserLocation(it) }
    }

    /**
     * 地図の中心を起点に再検索(「このエリアを検索」)。半径は地図の可視範囲から、ズームは現在の
     * 地図ズームを受け取り、結果反映時にカメラを動かさず可視範囲そのままで取り直す。青ドットは維持。
     */
    fun searchHere(lat: Double, lon: Double, radiusM: Int, zoom: Double) {
        if (engine == null) return
        val prev = _state.value.nearby
        // 現在地は「実際に測位できた値」だけを引き継ぐ(取れていないときに地図中心で捏造すると
        // 青ドットが偽の場所に出る)。距離の起点だけは 起点指定 → 現在地 → 地図中心 の順で決める
        val userLat = prev?.userLat
        val userLon = prev?.userLon
        val origin = _state.value.nearbyOrigin
        val originLat = origin?.lat ?: userLat ?: lat
        val originLon = origin?.lon ?: userLon ?: lon
        val gen = ++nearbyGeneration
        // 再検索中も直前の地図・一覧を残し loading だけ立てる(画面をまっさらにしない)。
        // center は prev のまま保持して完了までカメラを動かさず、結果反映時に新しい中心へ寄せる。
        _state.update {
            val base = it.nearby ?: NearbyUi()
            it.copy(
                nearby = base.copy(
                    loading = true,
                    loadingPhase = NearbyLoadPhase.SEARCHING,
                    error = null,
                    selectedPlace = null,
                ),
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            loadNearbyAround(gen, lat, lon, userLat, userLon, originLat, originLon, radiusM, zoom)
        }
    }

    /**
     * centerLat/centerLon を起点に YOLP で周辺店舗を取得する。
     * リストのソートは地図中心(centerLat/centerLon)からの距離順。
     * 距離表示は起点(originLat/originLon = GPS or 地名検索地点)基準。
     */
    private fun loadNearbyAround(
        gen: Int,
        centerLat: Double,
        centerLon: Double,
        userLat: Double?,
        userLon: Double?,
        originLat: Double,
        originLon: Double,
        radiusM: Int,
        zoom: Double,
        adaptZoom: Boolean = false,
    ) {
        val engine = engine ?: return
        // 合成 Merchant(カスタムキャンペーンの自由入力店名)も YOLP 検索対象に含めるため統合データを使う
        val data = displayData
        // チェーン絞り込み中の merchant は、非対象日・開始前でも YOLP 検索対象に加える
        // (施策詳細からのブリッジで「場所の下見」ができるように。判定が無い店は還元率ラベルなしで出す)
        val filterIds = _state.value.nearbyMerchantFilters.map { it.merchant.id }.toSet()
        val config = data?.yolpConfig?.let { yolpConfig ->
            YolpSearchConfig.build(yolpConfig, data.merchants, engine.activeManagedMerchantIds(LocalDate.now()) + filterIds)
        }
        if (config == null) {
            failNearby(gen, "検索設定を構築できませんでした。データを更新してください")
            return
        }
        val pois = YolpClient.fetchNearby(config, centerLat, centerLon, radiusM = radiusM)
        if (pois == null) {
            failNearby(
                gen,
                "周辺のお店を取得できませんでした。地図サーバが混雑しているか、通信が不安定な可能性があります。少し時間をおいて再度お試しください。",
            )
            return
        }
        val today = LocalDate.now()
        val qrIds = enabledQrIds()
        // ブリッジ中チェーンの網羅リスト対象外で間引いた店(0 件表示の案内の出し分け用。#70)。
        // YOLP の同一店舗の重複登録を二重に数えないよう、重複排除と同じ「チェーン+支店名」で数える
        val ineligibleHidden = mutableSetOf<String>()
        // 開発者モード中だけ生 POI と照合・間引き結果を記録する(#70。設定→開発者向け→
        // 「取得した地図データ」で表示。OFF 時は記録しない=オーバーヘッドなし)
        val debugPois = if (lastSettings.developerMode) mutableListOf<DebugPoi>() else null
        val places = pois
            .mapNotNull { poi ->
                fun record(matchLabel: String?, status: DebugPoiStatus) {
                    debugPois?.add(DebugPoi(poi.name, poi.lat, poi.lon, matchLabel, status))
                }
                val match = engine.matchStore(poi.name)
                if (match == null) {
                    record(null, DebugPoiStatus.NO_MATCH)
                    return@mapNotNull null
                }
                val merchant = match.merchant
                val matchLabel = merchant.name +
                    if (match.bannerId != merchant.id) "(${match.bannerName})" else ""
                if (engine.isFacilityTenant(match.bannerName, poi.name)) {
                    record(matchLabel, DebugPoiStatus.FACILITY_TENANT)
                    return@mapNotNull null
                }
                if (engine.isExcludedStore(merchant, poi.name)) {
                    record(matchLabel, DebugPoiStatus.OFFICIALLY_EXCLUDED)
                    return@mapNotNull null
                }
                // POI は具体的な看板(業態)なので看板スコープで判定する(対象外業態はここで判定なしになり消える)。
                // ユーザー登録の対象外ペア(#63)と網羅リストの店舗対象外(#64)も店舗単位でここで間引く
                // (該当施策だけ判定から外れ、全施策が間引かれた店は下の判定なしと同じ扱いで消える)
                val excludedIds =
                    engine.excludedCampaignIdsFor(merchant, poi.name, lastSettings.activeExcludedStorePairs)
                val ineligibleIds = engine.exhaustiveListIneligibleCampaignIds(merchant, poi.name)
                val result = engine.judgeAll(
                    merchant,
                    today,
                    qrIds,
                    match.bannerId,
                    excludedIds,
                    ineligibleIds,
                    memberships = lastSettings.pointProgramMemberships,
                )
                // 並記枠(提示のみ)しか無い店も「特典あり」としてピンを出す
                val visible = result.judgments + result.presentationJudgments
                // 判定なしは通常出さないが、チェーン絞り込み中(ブリッジ由来)の merchant は
                // 非対象日の場所確認用に残す(bestBenefit なし=還元率ラベルなしで表示)。
                // ただし網羅リストで対象外と確定した店(ineligibleIds に間引かれた店。#64)は
                // 下見の意味が無いため、明示対象外(isExcludedStore)と同様ブリッジ中でも出さない
                // (施策詳細から「近くの対象のお店を探す」と全国の非対象店が並んでしまう。#70)
                if (visible.isEmpty() && (merchant.id !in filterIds || ineligibleIds.isNotEmpty())) {
                    if (merchant.id in filterIds && ineligibleIds.isNotEmpty()) {
                        ineligibleHidden += "${merchant.id}:${engine.normalizedBranch(merchant, poi.name)}"
                    }
                    record(
                        matchLabel,
                        if (ineligibleIds.isNotEmpty()) DebugPoiStatus.EXHAUSTIVE_INELIGIBLE
                        else DebugPoiStatus.NO_JUDGMENT,
                    )
                    return@mapNotNull null
                }
                record(matchLabel, DebugPoiStatus.SHOWN)
                val allCampaigns = visible.map { it.campaign }
                NearbyPlace(
                    name = poi.name,
                    distanceMeters = GeoMath.distanceMeters(originLat, originLon, poi.lat, poi.lon),
                    distanceFromCenter = GeoMath.distanceMeters(centerLat, centerLon, poi.lat, poi.lon),
                    merchant = merchant,
                    bannerId = match.bannerId,
                    bestBenefit = result.bestBenefitLabel(),
                    lat = poi.lat,
                    lon = poi.lon,
                    brandColors = visible.mapNotNull { it.brandColor }.distinct(),
                    hasTimeLimited = allCampaigns.any { it.isTimeLimited },
                )
            }
            // 同一店舗の重複を排除(YOLP は同じ店を別名・空白違いで複数返すことがある。
            // 例: 「KFC…店」と「ケンタッキーフライドチキン…店」、空白有無違いの同名)。
            // 「チェーン + 支店名」がともに一致するものを同一店舗とみなし、1件だけ残す。
            // 座標基準にしないのは、同一モール内に同チェーンの別店舗(例: レイクタウンの複数スタバ)が
            // 入る場合に誤って1件へ潰さないため(支店名が異なれば別物として残る)。
            // 残す1件は座標の辞書順で選ぶ。「最も近い1件」にすると、同一店舗が座標違いで重複登録
            // されている場合(例: リヴィンオズ大泉のドトール、施設実位置と住所ジオコード点が約44m差)に
            // 検索起点しだいで残る座標が入れ替わり、近接グルーピングの結果が検索のたびに揺れるため
            .groupBy { p ->
                val m = p.merchant
                if (m == null) "?:${p.name}" else "${m.id}:${engine.normalizedBranch(m, p.name)}"
            }
            .map { (_, dups) -> dups.minWith(compareBy({ it.lat }, { it.lon }, { it.name })) }
            .sortedBy { it.distanceFromCenter }
        val effectiveZoom = if (adaptZoom) {
            val nearCount = places.count { it.distanceFromCenter <= NEARBY_DENSE_RADIUS_M }
            if (nearCount < NEARBY_DENSE_THRESHOLD) NEARBY_WIDE_ZOOM else zoom
        } else {
            zoom
        }
        applyNearbyIfCurrent(
            gen,
            NearbyUi(
                places = places,
                ineligibleHiddenCount = ineligibleHidden.size,
                rawPoiCount = pois.size,
                centerLat = centerLat,
                centerLon = centerLon,
                userLat = userLat,
                userLon = userLon,
                zoom = effectiveZoom,
                searchStamp = gen,
            ),
            debugPois,
        )
        resolveMunicipalNotice(gen, centerLat, centerLon)
    }

    /**
     * 開いている地図の判定由来フィールド(還元ラベル・ピン色・表示可否)を、新しいエンジン・設定で
     * 再計算する(rebuild から呼ぶ)。対象外ペア(#63)の登録・解除や設定変更を、YOLP 再検索なし
     * (取得済みリストのメモリ内再計算)で開いている地図へ即反映するため。判定が 0 件になった店は
     * ピンごと消える(チェーン絞り込み中の merchant は場所確認用に残す。loadNearbyAround と同基準)。
     * 一度消えた店は登録を解除しても次の検索まで戻らない(タブを離れて戻れば再検索される)。
     */
    private fun recomputeNearbyPlaces(
        nearby: NearbyUi,
        engine: JudgmentEngine,
        settings: AppSettings,
        filters: Set<NearbyLens>,
    ): NearbyUi {
        if (nearby.places.isEmpty()) return nearby
        val today = LocalDate.now()
        val qrIds = settings.enabledQrPaymentIds
        val filterIds = filters.map { it.merchant.id }.toSet()
        // 検索時(loadNearbyAround)に間引いた分に、この再計算で新たに間引いた分を積む
        // (places はメモリ内の残存分だけなので検索時の分は数え直せない。次の検索でリセットされる)
        var ineligibleHidden = nearby.ineligibleHiddenCount
        val places = nearby.places.mapNotNull { place ->
            val merchant = place.merchant ?: return@mapNotNull place
            val excludedIds =
                engine.excludedCampaignIdsFor(merchant, place.name, settings.activeExcludedStorePairs)
            val ineligibleIds = engine.exhaustiveListIneligibleCampaignIds(merchant, place.name)
            val result = engine.judgeAll(
                merchant,
                today,
                qrIds,
                place.bannerId,
                excludedIds,
                ineligibleIds,
                memberships = settings.pointProgramMemberships,
            )
            val visible = result.judgments + result.presentationJudgments
            // 網羅リストの対象外店はブリッジ中でも残さない(loadNearbyAround と同基準。#70)
            if (visible.isEmpty() && (merchant.id !in filterIds || ineligibleIds.isNotEmpty())) {
                if (merchant.id in filterIds && ineligibleIds.isNotEmpty()) ineligibleHidden++
                return@mapNotNull null
            }
            place.copy(
                bestBenefit = result.bestBenefitLabel(),
                brandColors = visible.mapNotNull { it.brandColor }.distinct(),
                hasTimeLimited = visible.any { it.campaign.isTimeLimited },
            )
        }
        // プレビュー中の店は再計算後のインスタンスへ差し替え、消えた店ならプレビューを閉じる
        val selected = nearby.selectedPlace?.let { sp ->
            places.firstOrNull { it.name == sp.name && it.lat == sp.lat && it.lon == sp.lon }
        }
        return nearby.copy(places = places, selectedPlace = selected, ineligibleHiddenCount = ineligibleHidden)
    }

    /**
     * 検索中心の所在自治体を解決し、開催中の自治体施策があれば地図のお知らせピルに反映する。
     * 検索完了ごとに1回だけリバースジオコーディングする(カメラ追従では呼ばない。境界付近の
     * チラつきと Geocoder 呼び出しの嵩みを避けるため、更新は「このエリアを検索」等の再検索単位)。
     * 参考情報なので、解決失敗・該当なしは黙って何もしない(エラーもスピナーも出さない)。
     */
    private fun resolveMunicipalNotice(gen: Int, lat: Double, lon: Double) {
        val engine = engine ?: return
        val municipal = engine.activeCampaigns(LocalDate.now())
            .filter { it.campaignType == CampaignType.MUNICIPAL }
        if (municipal.isEmpty()) return // 施策が1件も無ければ Geocoder 自体を呼ばない
        viewModelScope.launch(Dispatchers.IO) {
            if (!Geocoder.isPresent()) return@launch
            val addr = try {
                reverseGeocode(Geocoder(getApplication(), Locale.JAPAN), lat, lon)
            } catch (e: Exception) {
                Timber.w(e, "検索中心のリバースジオコーディングに失敗")
                null
            } ?: return@launch
            val prefecture = addr.adminArea ?: return@launch
            // 市区町村候補は locality(東京23区・一般市)と subLocality(政令市の行政区)の両方を
            // 渡し、一致した施策をすべて載せる(市の施策と県全域施策の併催もひとつの詳細で見せる)
            val matched = municipalCampaignsForLocation(
                municipal, prefecture, listOfNotNull(addr.locality, addr.subLocality),
            )
            if (matched.isEmpty()) return@launch
            // ピルの文言: 県全域+市区町村の併催は「千葉県・千葉市」と併記(municipalRegionsLabel。
            // 施策詳細のタイトルと共用し、ピル「千葉市」/タイトル「千葉県」の食い違いを防ぐ)。
            // 単独ならより狭い単位を優先(市区町村があればそれ、県全域施策だけなら県名)
            val label = municipalRegionsLabel(matched)
                ?: matched.mapNotNull { it.region }.firstOrNull { !it.isPrefectureWide }?.name
                ?: prefecture
            _state.update { st ->
                if (gen != nearbyGeneration) return@update st
                val nearby = st.nearby ?: return@update st
                st.copy(nearby = nearby.copy(municipalNotice = MunicipalNotice(label, matched)))
            }
        }
    }

    private suspend fun reverseGeocode(geocoder: Geocoder, lat: Double, lon: Double): Address? {
        return if (Build.VERSION.SDK_INT >= 33) {
            suspendCancellableCoroutine { cont ->
                geocoder.getFromLocation(lat, lon, 1) { addresses ->
                    if (cont.isActive) cont.resume(addresses.firstOrNull())
                }
            }
        } else {
            @Suppress("DEPRECATION")
            geocoder.getFromLocation(lat, lon, 1)?.firstOrNull()
        }
    }

    /** 進行中の近隣取得が最新世代(タブ移動・再取得で破棄されていない)のときだけ結果を反映する */
    /** debugPois は非 null のときだけ更新する(null = 開発者モード OFF で未記録 → 既存を維持) */
    private fun applyNearbyIfCurrent(gen: Int, nearby: NearbyUi, debugPois: List<DebugPoi>? = null) {
        _state.update {
            if (gen != nearbyGeneration) return@update it
            it.copy(nearby = nearby, nearbyDebugPois = debugPois ?: it.nearbyDebugPois)
        }
    }

    /**
     * 近隣取得の失敗。最新世代のときだけ反映する。既に地図(=結果の中心)が出ているなら内容は残して
     * loading だけ畳み、一時失敗は Snackbar で知らせる(まっさらにしない)。表示すべき内容が無い
     * 初回などは全画面エラー(再試行)にする。
     */
    private fun failNearby(gen: Int, message: String) {
        _state.update {
            if (gen != nearbyGeneration) return@update it
            val prev = it.nearby
            if (prev?.centerLat != null && prev.centerLon != null) {
                it.copy(nearby = prev.copy(loading = false), nearbySearchFailed = message)
            } else {
                it.copy(nearby = NearbyUi(error = message))
            }
        }
    }

    /** 近隣再検索失敗の Snackbar を表示し終えたら文言を消費する(同じ失敗を再表示しない) */
    fun onNearbySearchFailedShown() = _state.update { it.copy(nearbySearchFailed = null) }

    fun onLocationDenied() {
        if (engine == null) return
        val isInitial = _state.value.nearby?.centerLat == null
        val message = "位置情報の許可が必要です。端末の設定からこのアプリに位置情報を許可してください"
        if (!isInitial) {
            failNearby(nearbyGeneration, message)
            return
        }
        val gen = ++nearbyGeneration
        _state.update {
            val base = it.nearby ?: NearbyUi()
            it.copy(
                nearby = base.copy(
                    loading = true,
                    loadingPhase = NearbyLoadPhase.SEARCHING,
                    error = null,
                    selectedPlace = null,
                ),
                selection = null,
                storeCheck = null,
                nearbyOrigin = null,
                geocodeCandidates = emptyList(),
                isGeocoding = false,
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            fallbackToDefaultPlace(gen, message)
        }
    }

    /**
     * 位置情報が取れないとき、デフォルト地点(新宿駅)で地図を表示しつつ Snackbar で通知する。
     * 起点は nearbyOrigin にセットし、距離表示は「新宿駅から○○m」になる。
     */
    private fun fallbackToDefaultPlace(gen: Int, message: String) {
        val place = FALLBACK_PLACE
        _state.update {
            if (gen != nearbyGeneration) return@update it
            it.copy(nearbyOrigin = place, nearbySearchFailed = message)
        }
        showMapAt(gen, place.lat, place.lon, userLat = null, userLon = null)
        loadNearbyAround(
            gen, place.lat, place.lon,
            // 現在地は取れていないので捏造しない(青ドット非表示)。距離の起点はフォールバック地点
            userLat = null, userLon = null,
            originLat = place.lat, originLon = place.lon,
            radiusM = NEARBY_DEFAULT_RADIUS_M, zoom = NEARBY_DEFAULT_ZOOM,
            adaptZoom = true,
        )
    }

    fun onSelectTab(tab: AppTab) {
        val prev = _state.value.selectedTab
        if (prev == tab) return
        if (prev == AppTab.NEARBY) nearbyGeneration++
        _state.update { st ->
            var s = st.copy(
                selectedTab = tab,
                selection = null,
                storeCheck = null,
                selectedCampaignGroup = null,
                campaignBridgeReturn = null,
                selectionBridgeReturn = null,
                // 二ペイン(#56)ではサブページを開いたまま Rail で他タブへ移れるため、離脱時に閉じる。
                // 残すと他タブの上に設定サブページが全画面オーバーレイで出てしまう
                // (一ペインではサブページ表示中はナビが隠れて移動できないので挙動は変わらない)
                settingsSubpage = null,
            )
            if (prev == AppTab.NEARBY) {
                s = s.copy(
                    nearby = null,
                    nearbySearchFailed = null,
                    nearbyOrigin = null,
                    geocodeCandidates = emptyList(),
                    isGeocoding = false,
                )
            }
            s
        }
    }

    fun onCloseNearby() {
        // ブリッジ経由なら、戻る操作でブリッジ元(施策詳細/判定詳細)へ復帰する
        val st = _state.value
        if (st.campaignBridgeReturn != null || st.selectionBridgeReturn != null) {
            nearbyGeneration++
            _state.update {
                it.copy(
                    selectedTab = if (it.campaignBridgeReturn != null) AppTab.CAMPAIGNS else AppTab.SEARCH,
                    selectedCampaignGroup = it.campaignBridgeReturn,
                    selection = it.selectionBridgeReturn,
                    campaignBridgeReturn = null,
                    selectionBridgeReturn = null,
                    nearby = null,
                    nearbySearchFailed = null,
                    nearbyOrigin = null,
                    geocodeCandidates = emptyList(),
                    isGeocoding = false,
                    nearbyMerchantFilters = emptySet(),
                )
            }
            return
        }
        onSelectTab(AppTab.SEARCH)
    }

    /**
     * 一覧の行/地図のピンをタップ → 全画面遷移せず「選択中」にする。
     * 地図はこの店にセンタリングしピンを強調、ボトムシートは店舗プレビューに切り替わる。
     * 判定詳細へはプレビューの導線(onSelectNearby)から進む。
     */
    fun onPreviewNearby(place: NearbyPlace) {
        _state.update { st ->
            val nearby = st.nearby ?: return@update st
            var s = st.copy(nearby = nearby.copy(selectedPlace = place))
            // 詳細サイドシート(#57)表示中のピンタップは、プレビューだけ差し替えるとシートの詳細と
            // 地図の選択が食い違うため、シートの詳細ごとその店に差し替える。縦画面では詳細表示中に
            // ピンは押せない(全画面オーバーレイが地図を覆う)ので、この分岐は横画面のシートでしか効かない
            val detailOpen = st.selection != null || st.storeCheck != null ||
                st.selectedCampaignGroup != null
            val merchant = place.merchant
            if (detailOpen && merchant != null) {
                engine?.let { e ->
                    s = s.withSelection(
                        e.selectionFor(merchant, place.name, displayName = place.name, bannerId = place.bannerId),
                    )
                }
            }
            s
        }
    }

    /**
     * 地図タブの詳細サイドシート(#57)を閉じる。クラスタ/複合ピンのタップでグループリストを
     * 右ペインに出すとき、シートが上に残ると隠れて見えないため UI 側から呼ばれる。
     */
    fun onCloseNearbyDetail() {
        _state.update {
            it.copy(selection = null, storeCheck = null, selectedCampaignGroup = null)
        }
    }

    /** プレビューを閉じて一覧表示に戻す(× / 戻る) */
    fun onClearNearbyPreview() {
        _state.update { st ->
            val nearby = st.nearby ?: return@update st
            if (nearby.selectedPlace == null) return@update st
            st.copy(nearby = nearby.copy(selectedPlace = null))
        }
    }

    /** プレビューから判定詳細へ → POI名を店舗対象判定のプリフィルと画面タイトルに引き継ぐ */
    fun onSelectNearby(place: NearbyPlace) {
        val engine = engine ?: return
        val merchant = place.merchant ?: return
        _state.update {
            it.withSelection(
                engine.selectionFor(merchant, place.name, displayName = place.name, bannerId = place.bannerId),
            )
        }
    }

    fun onToggleCategory(category: String) {
        _state.update {
            val selected = if (category in it.selectedCategories) {
                it.selectedCategories - category
            } else {
                it.selectedCategories + category
            }
            val outcome = engine?.searchRewarded(it.query, selected) ?: SearchOutcome()
            it.copy(
                selectedCategories = selected,
                results = outcome.results,
                unrewardedNames = outcome.unrewardedNames,
                selection = null,
                storeCheck = null,
            )
        }
    }

    /**
     * 「地図」のジャンル絞り込みトグル。表示集合の絞り込みは UI 側で nearby.places に対して行う
     * クライアントフィルタなので、ここでは選択集合を更新するだけ(YOLP 再取得しない)。
     * チップは一覧表示時のみ出る(プレビュー中は出ない)ため selectedPlace は触らない。
     */
    fun onToggleNearbyCategory(category: String) {
        _state.update {
            val selected = if (category in it.nearbySelectedCategories) {
                it.nearbySelectedCategories - category
            } else {
                it.nearbySelectedCategories + category
            }
            it.copy(nearbySelectedCategories = selected)
        }
    }

    /**
     * 「地図」のお店絞り込みのトグル(在チェーンのピッカーのチェック/ピルの×=レンズ2段目)。
     * ジャンル選択は残したまま(全ピルを解除すると元のジャンル絞り込みに戻る=ドリルダウンの体験)。
     * 同一系列のグループレンズと業態レンズは重ねない(グループを選んだら業態を外し、
     * 業態を選んだらグループを外す=広げ直し/絞り直しの意図に追従する)。
     */
    fun onToggleNearbyLens(lens: NearbyLens) {
        _state.update {
            val current = it.nearbyMerchantFilters
            val next = if (current.any { l -> l.sameTarget(lens) }) {
                current.filterNot { l -> l.sameTarget(lens) }.toSet()
            } else {
                val cleaned = if (lens.bannerId == null) {
                    current.filterNot { l -> l.merchant.id == lens.merchant.id }
                } else {
                    current.filterNot { l -> l.merchant.id == lens.merchant.id && l.bannerId == null }
                }
                cleaned.toSet() + lens
            }
            it.copy(nearbyMerchantFilters = next)
        }
    }

    /**
     * ブリッジ: 判定詳細(探す由来)の「近くのこのお店を探す」は単一チェーン、おトクタブの施策詳細
     * (「近くの対象店舗を探す」)は 1〜N チェーン。そのチェーン群に絞った状態を作り、
     * タブを NEARBY に切り替えて元の画面を閉じる。実際の「地図」突入(位置情報パーミッション→fetchNearby)は
     * UI 側が続けて行う。ジャンル絞り込みはクリアしてチェーンに集中する(ピル解除で全件に戻る)。
     * 閉じた判定詳細は selectionBridgeReturn に保存し、地図タブの戻る操作で判定詳細へ復帰できるようにする。
     */
    fun onFindNearby(merchant: Merchant) {
        val returnSelection = _state.value.selection
        // 業態としての判定詳細(POI・看板ヒット由来)から探すときは、その業態レンズを引き継ぐ
        val bannerId = returnSelection?.takeIf { it.merchant.id == merchant.id }?.bannerId
        onFindNearby(setOf(NearbyLens(merchant, bannerId)))
        _state.update { it.copy(selectionBridgeReturn = returnSelection) }
    }

    /**
     * 施策詳細から: merchant_rules の merchant_id 群を解決してブリッジする(解決できた分だけ)。
     * location_hint 持ち(自販機等)は地図で探せないので除く。看板スコープ(banner_ids /
     * ineligible_banner_ids)のあるルールは対象業態のレンズへ展開し、対象外業態のピンに飛ばさない。
     * 閉じる施策詳細を campaignBridgeReturn に保存し、地図タブの戻る操作で施策詳細へ復帰できるようにする。
     */
    fun onFindNearbyByIds(merchantIds: Collection<String>) {
        val idSet = merchantIds.toSet()
        // 合成 Merchant(カスタムキャンペーンの自由入力店名)からもブリッジできるよう統合データで引く
        val merchants = displayData?.merchants.orEmpty()
            .filter { it.id in idSet && it.locationHint == null }
        if (merchants.isEmpty()) return
        // 表示中の施策詳細(グループ)のルールから看板スコープを引く(ブリッジはこの画面からしか呼ばれない)
        val rulesById = _state.value.selectedCampaignGroup.orEmpty()
            .flatMap { it.campaign.merchantRules }
            .groupBy { it.merchantId }
        val lenses = merchants.flatMap { m -> lensesFor(m, rulesById[m.id].orEmpty()) }.toSet()
        val returnGroup = _state.value.selectedCampaignGroup
        onFindNearby(lenses)
        _state.update { it.copy(campaignBridgeReturn = returnGroup) }
    }

    /**
     * merchant 1 件ぶんのブリッジ用レンズ。看板スコープの無いルールが 1 つでもあれば
     * グループ全体(bannerId = null)。スコープ付きのみなら対象業態のレンズへ展開し、
     * 結果的に全業態が対象ならグループレンズ 1 個に畳む(ピルを増やさない)。
     */
    private fun lensesFor(merchant: Merchant, rules: List<MerchantRule>): List<NearbyLens> {
        if (rules.isEmpty() || rules.any { it.bannerIds.isEmpty() && it.ineligibleBannerIds.isEmpty() }) {
            return listOf(NearbyLens(merchant))
        }
        val allowed = rules.flatMap { rule ->
            if (rule.bannerIds.isNotEmpty()) rule.bannerIds
            else merchant.allBannerIds - rule.ineligibleBannerIds.toSet()
        }.distinct().filter { merchant.bannerName(it) != null }
        return when {
            allowed.isEmpty() -> listOf(NearbyLens(merchant))
            allowed.toSet() == merchant.allBannerIds.toSet() -> listOf(NearbyLens(merchant))
            else -> allowed.map { NearbyLens(merchant, it) }
        }
    }

    private fun onFindNearby(lenses: Set<NearbyLens>) {
        val prev = _state.value.selectedTab
        if (prev == AppTab.NEARBY) nearbyGeneration++
        _state.update { st ->
            var s = st.copy(
                selectedTab = AppTab.NEARBY,
                nearbyMerchantFilters = lenses,
                nearbySelectedCategories = emptySet(),
                selection = null,
                storeCheck = null,
                selectedCampaignGroup = null,
                // 新しいブリッジは古い復元先を無効化する(呼び出し元の public 関数が自分の復元先を上書き保存する)
                campaignBridgeReturn = null,
                selectionBridgeReturn = null,
            )
            if (prev == AppTab.NEARBY) {
                s = s.copy(
                    nearby = null,
                    nearbySearchFailed = null,
                    nearbyOrigin = null,
                    geocodeCandidates = emptyList(),
                    isGeocoding = false,
                )
            }
            s
        }
    }

    /**
     * selection を開く・差し替える遷移。開いていた店舗判定は古い selection のものなので必ず閉じる
     * (二ペイン(横画面)では店舗判定を開いたまま一覧の別のお店を選べるため。開いていなければ無害)。
     */
    private fun UiState.withSelection(selection: Selection): UiState =
        copy(selection = selection, storeCheck = null)

    fun onSelect(result: SearchResult) {
        val engine = engine ?: return
        val merchant = result.merchant
        _state.update {
            // 「マクドナルド渋谷店」のような入力で選んだ場合は、店舗名部分を対象判定のプリフィルに引き継ぐ
            val hint = it.query.trim()
                .takeUnless { q -> q.isBlank() || engine.isExactNameMatch(merchant, q) }
                .orEmpty()
            // 業態ヒットの行は業態としての判定+タイトルも業態名(杏林堂薬局)で開く
            it.withSelection(
                engine.selectionFor(merchant, hint, displayName = result.bannerName, bannerId = result.bannerId),
            )
        }
    }

    /** 判定詳細から店舗対象判定画面を開く(canCheckStore のチェーンのみ) */
    fun onOpenStoreCheck() {
        val engine = engine ?: return
        _state.update {
            val sel = it.selection ?: return@update it
            if (!engine.canCheckStore(sel.merchant)) return@update it
            it.copy(
                storeCheck = StoreCheckState(
                    merchant = sel.merchant,
                    input = sel.storeNameHint,
                    verdicts = engine.checkStore(sel.merchant, sel.storeNameHint),
                )
            )
        }
    }

    fun onStoreNameChange(storeName: String) {
        val engine = engine ?: return
        _state.update {
            val sc = it.storeCheck ?: return@update it
            it.copy(storeCheck = sc.copy(input = storeName, verdicts = engine.checkStore(sc.merchant, storeName)))
        }
    }

    /** 店舗対象判定画面を閉じて判定詳細に戻る */
    fun onCloseStoreCheck() {
        _state.update { it.copy(storeCheck = null) }
    }

    fun onBack() {
        _state.update { it.copy(selection = null) }
    }

    // --- 地名検索(起点コントロール) ---

    private suspend fun geocodeQuery(geocoder: Geocoder, query: String): List<Address> {
        return if (Build.VERSION.SDK_INT >= 33) {
            suspendCancellableCoroutine { cont ->
                geocoder.getFromLocationName(query, 5) { addresses ->
                    if (cont.isActive) cont.resume(addresses)
                }
            }
        } else {
            @Suppress("DEPRECATION")
            geocoder.getFromLocationName(query, 5) ?: emptyList()
        }
    }

    fun onGeocode(query: String) {
        if (query.isBlank()) {
            _state.update { it.copy(geocodeCandidates = emptyList(), isGeocoding = false) }
            return
        }
        _state.update { it.copy(isGeocoding = true, geocodeCandidates = emptyList()) }
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            if (!Geocoder.isPresent()) {
                _state.update { it.copy(isGeocoding = false) }
                return@launch
            }
            val geocoder = Geocoder(app, Locale.JAPAN)
            val candidates = try {
                val primary = geocodeQuery(geocoder, query)
                val stationSuffix = "駅"
                val extra = if (!query.endsWith(stationSuffix)) {
                    geocodeQuery(geocoder, query + stationSuffix)
                } else {
                    emptyList()
                }
                val seen = mutableSetOf<Pair<Double, Double>>()
                (primary + extra).mapNotNull { addr ->
                    val key = Pair(
                        (addr.latitude * 1e5).toLong() / 1e5,
                        (addr.longitude * 1e5).toLong() / 1e5,
                    )
                    if (!seen.add(key)) return@mapNotNull null
                    val rawName = addr.featureName
                        ?: addr.getAddressLine(0)?.split(",")?.firstOrNull()?.trim()
                        ?: return@mapNotNull null
                    // featureName が番地(数字+ハイフン)や国名なら施設名ではない
                    val useFullAddress = addr.featureName?.all { c ->
                        c in '0'..'9' || c in '０'..'９' || c == '-' || c == '−' || c == 'ー'
                    } == true || addr.featureName == addr.countryName
                    val componentAddress = buildString {
                        addr.adminArea?.let { append(it) }
                        addr.locality?.let { if (it != addr.adminArea) append(it) }
                        addr.subLocality?.let { append(it) }
                        addr.thoroughfare?.let { append(it) }
                        addr.subThoroughfare?.let { append(it) }
                    }
                    // 番地等の場合のみ getAddressLine(0) を優先(施設名付き住所の混入を避ける)
                    val fullAddress = if (useFullAddress) {
                        val addressLine = addr.getAddressLine(0)
                            ?.replace(Regex("^日本[、,]\\s*"), "")
                            ?.replace(Regex("〒[\\S]+\\s*"), "")
                            ?.trim()
                        if (addressLine != null && addressLine.length > componentAddress.length)
                            addressLine
                        else componentAddress
                    } else {
                        componentAddress
                    }
                    val name = if (useFullAddress && fullAddress.isNotBlank()) {
                        fullAddress
                    } else {
                        rawName
                    }
                    GeocodedPlace(name, fullAddress, addr.latitude, addr.longitude)
                }
            } catch (_: Exception) {
                emptyList()
            }
            _state.update { it.copy(geocodeCandidates = candidates, isGeocoding = false) }
        }
    }

    fun onSelectGeocodedPlace(place: GeocodedPlace) {
        if (engine == null) return
        val prev = _state.value.nearby
        val userLat = prev?.userLat
        val userLon = prev?.userLon
        val gen = ++nearbyGeneration
        _state.update {
            val base = it.nearby ?: NearbyUi()
            it.copy(
                nearbyOrigin = place,
                geocodeCandidates = emptyList(),
                isGeocoding = false,
                nearby = base.copy(
                    loading = true,
                    loadingPhase = NearbyLoadPhase.SEARCHING,
                    error = null,
                    selectedPlace = null,
                ),
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            loadNearbyAround(
                gen, place.lat, place.lon,
                userLat ?: place.lat, userLon ?: place.lon,
                place.lat, place.lon,
                radiusM = NEARBY_DEFAULT_RADIUS_M, zoom = NEARBY_DEFAULT_ZOOM,
                adaptZoom = true,
            )
        }
    }

    /** 起点を GPS に戻す(検索バーの✕)。カメラは動かさず距離だけ現在地基準で再計算する */
    fun onClearOrigin() {
        _state.update { st ->
            val n = st.nearby
                ?: return@update st.copy(nearbyOrigin = null, geocodeCandidates = emptyList())
            val gpsLat = n.userLat
            val gpsLon = n.userLon
            if (gpsLat == null || gpsLon == null) {
                return@update st.copy(nearbyOrigin = null, geocodeCandidates = emptyList())
            }
            val recalculated = n.places.map { p ->
                p.copy(distanceMeters = GeoMath.distanceMeters(gpsLat, gpsLon, p.lat, p.lon))
            }.sortedBy { it.distanceFromCenter }
            st.copy(
                nearbyOrigin = null,
                geocodeCandidates = emptyList(),
                nearby = n.copy(places = recalculated),
            )
        }
    }

    fun onDismissGeocoding() {
        _state.update { it.copy(geocodeCandidates = emptyList(), isGeocoding = false) }
    }

    fun onSetCampaignFilter(filter: CampaignFilter) {
        _state.update { it.copy(campaignFilter = filter) }
    }

    /**
     * お店タブのお知らせバナーから: おトクタブへ移動し、自治体フィルタを効かせて着地させる
     * (「登録地域のみ」は既定 ON なので、そのまま登録地域の自治体施策一覧になる)
     */
    fun onOpenMunicipalCampaigns() {
        onSelectTab(AppTab.CAMPAIGNS)
        _state.update { it.copy(campaignFilter = CampaignFilter.MUNICIPAL) }
    }

    fun onSelectCampaignGroup(group: List<Campaign>) {
        val e = engine ?: return
        // 店舗文脈なしの判定組み立ては JudgmentEngine に一本化(#85)。
        // カスタムカード・未所有カードの識別色も引けるよう統合データ(displayData)を渡す
        val catalog = displayData ?: return
        val judgments = e.judgeCampaignOverview(group, LocalDate.now(), catalog)
        // 地図タブ横画面のお知らせピルは、判定詳細のサイドシート表示中も押せる(非モーダル。#57)。
        // 開いていた判定詳細・店舗判定は施策詳細に置き換える(残すと全画面オーバーレイ・サイドシート
        // とも when の優先順で判定詳細側が最前面になり、開いたはずの施策詳細が見えないため)
        _state.update { it.copy(selectedCampaignGroup = judgments, selection = null, storeCheck = null) }
    }

    fun onCloseCampaignDetail() {
        _state.update { it.copy(selectedCampaignGroup = null) }
    }

    /**
     * 通知タップ(#82。MainActivity の onCreate/onNewIntent から)。おトクタブへ移動し、
     * 通知に積まれたグループキーの詳細カードを開く。キー無し(サマリ通知)はタブ移動のみ。
     */
    fun onNotificationTapped(groupKey: String?) {
        onSelectTab(AppTab.CAMPAIGNS)
        if (groupKey.isNullOrBlank()) return
        pendingNotificationGroupKey = groupKey
        // 起動中のタップならデータは揃っている=即開く。コールドスタートは rebuild 後に消費される
        consumePendingNotificationLink()
    }

    /**
     * 保留中の通知ディープリンクを消費して詳細カードを開く。エンジン未生成(データロード前)なら
     * 何もせず保留のまま(rebuild の末尾で再度呼ばれる)。通知後にデータが改定・終了して
     * 引き当てられないときはおトクタブのまま Snackbar で知らせる。
     */
    private fun consumePendingNotificationLink() {
        val key = pendingNotificationGroupKey ?: return
        val e = engine ?: return
        pendingNotificationGroupKey = null
        val today = LocalDate.now()
        // おトクタブの一覧と同じ範囲(開催中+開催予定)から引き当てる。エリアフィルタは通さない
        // (通知対象=登録エリア一致の自治体施策・所有決済の promotion なので絞る必要がなく、
        // 「すべて表示」トグルの状態に引き当てが左右されない方が確実)
        val group = campaignsInGroup(e.activeCampaigns(today) + e.upcomingCampaigns(today), key)
        if (group.isEmpty()) {
            _state.update { it.copy(notificationLinkMessage = "このキャンペーンは終了したか、見つかりませんでした") }
            return
        }
        onSelectCampaignGroup(group)
    }

    /** 通知ディープリンクの失敗 Snackbar を表示し終えたら消費する */
    fun onNotificationLinkMessageShown() = _state.update { it.copy(notificationLinkMessage = null) }

    // --- 設定値の更新(DataStore へ書き込み → settings Flow 経由で rebuild される) ---
    fun onSetThemeMode(mode: ThemeMode) = viewModelScope.launch { settingsRepo.setThemeMode(mode) }

    fun onSetDynamicColor(enabled: Boolean) = viewModelScope.launch { settingsRepo.setDynamicColor(enabled) }

    fun onSetAutoRefresh(enabled: Boolean) = viewModelScope.launch { settingsRepo.setAutoRefresh(enabled) }

    /**
     * キャンペーン通知(#6)の ON/OFF。設定の保存に加えて日次ジョブの登録/解除も行う。
     * 登録済みジョブは WorkManager が再起動をまたいで維持するため、起動時の再登録は不要。
     * パーミッション(Android 13+)は UI 側(NotificationSettingsPage)が確認してから呼ぶ。
     */
    fun onSetNotificationsEnabled(enabled: Boolean) = viewModelScope.launch {
        settingsRepo.setNotificationsEnabled(enabled)
        val app = getApplication<Application>()
        if (enabled) {
            CampaignNotifications.schedule(app, lastSettings.notificationTimeMinutes)
        } else {
            CampaignNotifications.cancel(app)
        }
    }

    /** 通知時刻の変更。通知 ON のときはその場で次回ジョブを新しい時刻に予約し直す */
    fun onSetNotificationTime(minutesOfDay: Int) = viewModelScope.launch {
        settingsRepo.setNotificationTime(minutesOfDay)
        if (lastSettings.notificationsEnabled) {
            CampaignNotifications.schedule(getApplication(), minutesOfDay)
        }
    }

    /**
     * 通知ジョブのテスト実行(開発者向け)。日次ジョブの発火を待たずに本番と同じ判定・通知を試す。
     * 遅延ありは画面を消してから鳴らす検証用。実際に通知が出るかは対象施策の有無・通知済み履歴・
     * 端末の通知許可しだいなので、ここでは「実行した」ことだけを知らせる。
     */
    fun onTestNotification(delaySeconds: Long) {
        CampaignNotifications.runTest(getApplication(), delaySeconds)
        val message = if (delaySeconds > 0) {
            "${delaySeconds}秒後にテスト通知します。画面を消してお待ちください"
        } else {
            "テスト通知を実行しました(対象がなければ通知は出ません)"
        }
        _state.update { it.copy(developerMessage = message) }
    }

    /** 通知済み履歴の消去(開発者向け)。同じキャンペーンをもう一度通知させたいときに使う */
    fun onClearNotifiedCampaigns() = viewModelScope.launch {
        settingsRepo.clearNotifiedCampaignKeys()
        _state.update { it.copy(developerMessage = "通知済み履歴を消しました") }
    }

    fun onDeveloperMessageShown() = _state.update { it.copy(developerMessage = null) }

    fun onSetDataCommitRef(ref: String) = viewModelScope.launch { settingsRepo.setDataCommitRef(ref) }

    fun onSetUseTestData(enabled: Boolean) = viewModelScope.launch { settingsRepo.setUseTestData(enabled) }

    fun onSetUseBundledData(enabled: Boolean) = viewModelScope.launch { settingsRepo.setUseBundledData(enabled) }

    /**
     * 開発者モードの ON/OFF。OFF は単なるトグル書き込みでなく開発者向け設定の一括リセット
     * (ref/testData/bundled を既定値へ)。リセットの emission を settings Flow の既存の変更検知が
     * 拾い、必要なら refresh(force=true) で本番データへ自動復帰する。
     */
    fun onSetDeveloperMode(enabled: Boolean) = viewModelScope.launch {
        if (enabled) settingsRepo.setDeveloperMode(true) else settingsRepo.resetDeveloperSettings()
    }

    // --- 設定のエクスポート/インポート(#50) ---

    /**
     * 設定を SAF で選ばれた URI へ書き出す。上書き保存(既存ファイルを選び直した場合)で
     * 前の内容が末尾に残らないよう、切り詰めモード("wt")で開く。
     */
    fun onExportSettings(uri: Uri) = viewModelScope.launch {
        val backup = settingsRepo.current().toBackup(
            exportedAt = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
            appVersion = BuildConfig.VERSION_NAME,
        )
        val message = withContext(Dispatchers.IO) {
            runCatching {
                val resolver = getApplication<Application>().contentResolver
                val stream = resolver.openOutputStream(uri, "wt")
                    ?: error("書き出し先を開けませんでした: $uri")
                stream.use { it.write(encodeSettingsBackup(backup).toByteArray()) }
            }.fold(
                onSuccess = { "設定を書き出しました" },
                onFailure = {
                    Timber.w(it, "設定の書き出しに失敗")
                    "書き出しに失敗しました。保存先を変えて再度お試しください。"
                },
            )
        }
        _state.update { it.copy(settingsBackupMessage = message) }
    }

    /**
     * SAF で選ばれたファイルを読み、確認ダイアログ待ちの状態にする。ここでは DataStore を
     * 書き換えない(適用は [onConfirmSettingsImport])。
     */
    fun onPickSettingsImport(uri: Uri) = viewModelScope.launch {
        val text = withContext(Dispatchers.IO) { readTextLimited(uri) }
        val backup = text?.let { decodeSettingsBackup(it) }
        val message = when {
            backup == null ->
                "ファイルを読み込めませんでした。このアプリで書き出した JSON か確認してください。"
            // 新しいアプリのファイルは知らないキーを黙って捨ててしまうので、読まずに断る
            backup.schemaVersion > SETTINGS_BACKUP_SCHEMA_VERSION ->
                "新しいバージョンのアプリで書き出したファイルです。アプリを更新してからお試しください。"
            else -> null
        }
        _state.update {
            it.copy(pendingSettingsImport = if (message == null) backup else null, settingsBackupMessage = message)
        }
    }

    /**
     * 確認済みのバックアップを適用する。設定の書き戻し自体は settings Flow 経由で rebuild
     * されるが、通知ジョブ(WorkManager)は DataStore と別管理なので復元値に合わせ直す。
     *
     * @param notificationsAllowed 通知を出せる状態か(UI が復元前にパーミッションを確認・要求した結果)。
     *   実行時パーミッションはバックアップに入れられないため、通知 ON のファイルを未許可の端末へ
     *   復元すると「設定は ON なのに通知が来ない」状態になる。許可されなかったときは通知だけ OFF に
     *   落として復元し、その旨を Snackbar で伝える(通知サブページの ON 操作と同じ「許可を取ってから
     *   ON にする」方針。ユーザーはあとから通知サブページで ON にし直せる)。
     */
    fun onConfirmSettingsImport(notificationsAllowed: Boolean) = viewModelScope.launch {
        val backup = _state.value.pendingSettingsImport ?: return@launch
        val restored = backup.toSettings()
        val notificationsDropped = restored.notificationsEnabled && !notificationsAllowed
        val imported =
            if (notificationsDropped) restored.copy(notificationsEnabled = false) else restored
        settingsRepo.importSettings(imported)
        // v2 バックアップは CardOverride.pointValue を復元し得るが、旧カード単位→通貨単位の移行
        // (migrateCardPointValues)は applyData(次回データロード)でしか発火しない。復元直後にも
        // 発火させ、値が次回起動まで宙に浮くのを防ぐ。lastLoaded が null(カタログ未ロード)なら
        // 何もしない(次回ロードで自然に移行される)
        lastLoaded?.let { loaded ->
            settingsRepo.migrateCardPointValues(cardToCurrencyMap(loaded))
        }
        val app = getApplication<Application>()
        if (imported.notificationsEnabled) {
            CampaignNotifications.schedule(app, imported.notificationTimeMinutes)
        } else {
            CampaignNotifications.cancel(app)
        }
        val message = if (notificationsDropped) {
            "設定を復元しました。通知が許可されなかったため、キャンペーン通知はオフです"
        } else {
            "設定を復元しました"
        }
        _state.update { it.copy(pendingSettingsImport = null, settingsBackupMessage = message) }
    }

    fun onCancelSettingsImport() = _state.update { it.copy(pendingSettingsImport = null) }

    fun onSettingsBackupMessageShown() = _state.update { it.copy(settingsBackupMessage = null) }

    /**
     * SAF で選ばれたファイルをテキストで読む。誤って巨大なファイル(動画等)を選ばれても
     * 落ちないよう [BACKUP_MAX_BYTES] で打ち切り、超えたら失敗(null)にする。
     */
    private fun readTextLimited(uri: Uri): String? = runCatching {
        getApplication<Application>().contentResolver.openInputStream(uri)?.use { stream ->
            val buffer = ByteArray(BACKUP_MAX_BYTES + 1)
            var size = 0
            while (size < buffer.size) {
                val read = stream.read(buffer, size, buffer.size - size)
                if (read < 0) break
                size += read
            }
            if (size > BACKUP_MAX_BYTES) null else String(buffer, 0, size, Charsets.UTF_8)
        }
    }.onFailure { Timber.w(it, "バックアップファイルの読み込みに失敗") }.getOrNull()

    fun onOpenSettingsSubpage(page: SettingsSubpage) {
        _state.update { it.copy(settingsSubpage = page) }
    }

    // 2 階層目のサブページ(ライセンス一覧・取得した地図データ)は、戻る操作で親サブページへ戻す
    fun onCloseSettingsSubpage() {
        _state.update { it.copy(settingsSubpage = it.settingsSubpage?.parent) }
    }

    fun onSetCardOwned(cardId: String, owned: Boolean) =
        viewModelScope.launch { settingsRepo.setOwned(cardId, owned) }

    fun onSetCardRate(cardId: String, rate: Double?) =
        viewModelScope.launch { settingsRepo.setRate(cardId, rate) }

    fun onSetCardBrand(cardId: String, brand: String) =
        viewModelScope.launch { settingsRepo.setBrand(cardId, brand) }

    // 倍率グループ(#84)を持つ通貨(ウエル活の Vポイント・WAON POINT 等)はグループ全員の id を
    // まとめて書き、どの通貨のチェックから切り替えても ON/OFF が連動する
    fun onSetPointMultiplierEnabled(currencyId: String, enabled: Boolean) {
        val ids = multiplierToggleIds(displayData?.pointCurrencies.orEmpty(), currencyId)
        viewModelScope.launch { settingsRepo.setPointMultipliersEnabled(ids, enabled) }
    }

    // 倍率の選択(#83)。選択肢(factor_options)を持つ通貨だけで意味を持つ
    fun onSetPointMultiplierFactor(currencyId: String, factor: Double) =
        viewModelScope.launch { settingsRepo.setPointMultiplierFactor(currencyId, factor) }

    fun onSetPointProgramMembership(currencyId: String, member: Boolean) =
        viewModelScope.launch { settingsRepo.setPointProgramMembership(currencyId, member) }

    fun onSetCardClass(cardId: String, cardClass: String) =
        viewModelScope.launch { settingsRepo.setCardClass(cardId, cardClass) }

    // 1pt 価値は #13 で通貨単位(pointCurrencyValues)へ移設済み。null で上書き解除(既定に戻す)
    fun onSetPointCurrencyValue(currencyId: String, value: Double?) =
        viewModelScope.launch { settingsRepo.setPointCurrencyValue(currencyId, value) }

    /** 期間限定ポイントの残高・失効日(通貨ごとに1件。#13)。null で削除 */
    fun onSetPointBalance(currencyId: String, balance: PointBalance?) =
        viewModelScope.launch { settingsRepo.setPointBalance(currencyId, balance) }

    fun onSetQrEnabled(id: String, enabled: Boolean) =
        viewModelScope.launch { settingsRepo.setQrEnabled(id, enabled) }

    fun onSetBrandOwned(brand: String, owned: Boolean) =
        viewModelScope.launch { settingsRepo.setBrandOwned(brand, owned) }

    fun onAddCustomCard(name: String, color: String?, brand: String) = viewModelScope.launch {
        settingsRepo.addCustomCard(
            CustomCard(
                id = CustomCard.ID_PREFIX + UUID.randomUUID(),
                name = name.trim(),
                color = color,
                brand = brand,
            )
        )
    }

    fun onUpdateCustomCard(card: CustomCard) =
        viewModelScope.launch { settingsRepo.updateCustomCard(card.copy(name = card.name.trim())) }

    fun onRemoveCustomCard(id: String) =
        viewModelScope.launch { settingsRepo.removeCustomCard(id) }

    /** カスタムキャンペーンの追加。id はここで採番する(UI からは id 空で渡す) */
    fun onAddCustomCampaign(campaign: CustomCampaign) = viewModelScope.launch {
        settingsRepo.addCustomCampaign(campaign.copy(id = CustomCampaign.ID_PREFIX + UUID.randomUUID()))
        enableLinkedQr(campaign)
    }

    fun onUpdateCustomCampaign(campaign: CustomCampaign) = viewModelScope.launch {
        settingsRepo.updateCustomCampaign(campaign)
        enableLinkedQr(campaign)
    }

    fun onRemoveCustomCampaign(id: String) =
        viewModelScope.launch { settingsRepo.removeCustomCampaign(id) }

    /**
     * 「対象外のお店として登録」(#63)。判定詳細(具体的なお店として開いた場合)から呼ぶ。
     * storeName はプリフィルをユーザーが確認・編集した値(YOLP 由来データをそのまま永続化しない)。
     * 反映は設定 Flow → rebuild 経由(判定詳細は登録済みの畳み表示へ、開いている地図は再計算)。
     */
    fun onExcludeStore(campaignId: String, storeName: String) {
        val merchantId = _state.value.selection?.merchant?.id ?: return
        val name = storeName.trim()
        if (name.isEmpty()) return
        viewModelScope.launch {
            settingsRepo.addExcludedStorePair(
                ExcludedStorePair(campaignId, merchantId, name, LocalDate.now().toString()),
            )
        }
    }

    /** 判定詳細の「登録済み」からの解除。開いているお店に一致する登録を表記ゆれ分も含めて消す */
    fun onRestoreExcludedStore(campaignId: String) {
        val engine = engine ?: return
        val sel = _state.value.selection ?: return
        val storeName = sel.displayName ?: return
        val pairs = engine.excludedPairsFor(sel.merchant, storeName, lastSettings.activeExcludedStorePairs)
            .filter { it.campaignId == campaignId }
        if (pairs.isEmpty()) return
        viewModelScope.launch { settingsRepo.removeExcludedStorePairs(pairs) }
    }

    /** 設定画面「対象外に登録したお店」からの個別削除 */
    fun onRemoveExcludedStorePair(pair: ExcludedStorePair) =
        viewModelScope.launch { settingsRepo.removeExcludedStorePairs(listOf(pair)) }

    /**
     * 終了した施策(データから消えた or 期間終了で判定に出ない)の登録をまとめて削除する。
     * サイレント自動削除はしない方針(データの一時的な取得失敗で消えると困る・期間終了は
     * 同じ id で更新され得る)のため、設定画面からの明示操作でのみ呼ばれる。
     */
    fun onRemoveStaleExcludedStorePairs() {
        val st = _state.value
        val stale = lastSettings.activeExcludedStorePairs.filter {
            it.campaignId !in st.allCampaignNames || it.campaignId in st.expiredCampaignIds
        }
        if (stale.isEmpty()) return
        viewModelScope.launch { settingsRepo.removeExcludedStorePairs(stale) }
    }

    /**
     * 紐付け先 QR を「利用中」に自動登録する。QR 施策の判定は利用中の QR に限られる
     * (judgeQr の enabledQrIds フィルタ)ため、未登録のままだと登録したキャンペーンが
     * お店・地図タブに出ない。QR に紐付けた事実を「その QR を使っている」とみなす。
     */
    private suspend fun enableLinkedQr(campaign: CustomCampaign) {
        campaign.payments.mapNotNull { it.qrPaymentId }.forEach { settingsRepo.setQrEnabled(it, true) }
    }

    fun onAddRegisteredArea(area: RegisteredArea) =
        viewModelScope.launch { settingsRepo.addRegisteredArea(area) }

    fun onRemoveRegisteredArea(area: RegisteredArea) =
        viewModelScope.launch { settingsRepo.removeRegisteredArea(area) }

    /** おトクタブの「登録地域のみ / すべて」切替。設定でなく閲覧モードなので永続化しない */
    fun onToggleShowAllCampaigns() {
        _state.update { it.copy(showAllCampaigns = !it.showAllCampaigns) }
        rebuild()
    }
}