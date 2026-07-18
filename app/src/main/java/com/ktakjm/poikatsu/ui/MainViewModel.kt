package com.ktakjm.poikatsu.ui

import android.app.Application
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ktakjm.poikatsu.data.Campaign
import com.ktakjm.poikatsu.data.DataRepository
import com.ktakjm.poikatsu.data.DataSource
import com.ktakjm.poikatsu.data.GithubRawClient
import com.ktakjm.poikatsu.data.LoadedData
import com.ktakjm.poikatsu.data.LocationProvider
import com.ktakjm.poikatsu.data.Merchant
import com.ktakjm.poikatsu.data.YolpClient
import com.ktakjm.poikatsu.data.YolpSearchConfig
import com.ktakjm.poikatsu.data.AppSettings
import com.ktakjm.poikatsu.data.CustomCard
import com.ktakjm.poikatsu.data.MunicipalityMaster
import com.ktakjm.poikatsu.data.PaymentCard
import com.ktakjm.poikatsu.data.PointMultiplier
import com.ktakjm.poikatsu.data.PoikatsuJson
import com.ktakjm.poikatsu.data.QrPayment
import com.ktakjm.poikatsu.data.RegisteredArea
import com.ktakjm.poikatsu.data.SettingsRepository
import com.ktakjm.poikatsu.data.ThemeMode
import com.ktakjm.poikatsu.domain.BenefitLabel
import com.ktakjm.poikatsu.domain.BestPaymentOption
import com.ktakjm.poikatsu.domain.CampaignJudgment
import com.ktakjm.poikatsu.domain.CampaignType
import com.ktakjm.poikatsu.domain.JudgmentEngine
import com.ktakjm.poikatsu.domain.StoreVerdict
import com.ktakjm.poikatsu.domain.appLinks
import com.ktakjm.poikatsu.domain.bestBenefitLabel
import com.ktakjm.poikatsu.domain.campaignType
import com.ktakjm.poikatsu.domain.filterCampaignsByArea
import com.ktakjm.poikatsu.domain.googlePayIneligibleWarning
import com.ktakjm.poikatsu.domain.municipalCampaignsForAreas
import com.ktakjm.poikatsu.domain.municipalCampaignsForLocation
import com.ktakjm.poikatsu.domain.isPrefectureWide
import com.ktakjm.poikatsu.domain.isTargetDay
import com.ktakjm.poikatsu.domain.isTimeLimited
import com.ktakjm.poikatsu.domain.nextTargetDay
import com.ktakjm.poikatsu.domain.walletAppLink
import com.ktakjm.poikatsu.util.GeoMath
import java.io.File
import java.time.LocalDate
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

/** 位置情報を取得できないときのフォールバック地点(新宿駅) */
private val FALLBACK_PLACE = MainViewModel.GeocodedPlace(
    name = "新宿駅",
    fullAddress = "東京都新宿区新宿三丁目",
    lat = 35.6896,
    lon = 139.7006,
)

enum class AppTab { SEARCH, NEARBY, CAMPAIGNS, SETTINGS }
enum class CampaignFilter { ALL, MUNICIPAL, NON_MUNICIPAL }

class MainViewModel(app: Application) : AndroidViewModel(app) {

    data class SearchResult(
        val merchant: Merchant,
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
        /** 公式が対象/対象外を言い切っているチェーンか。true のときだけ対象判定画面へ遷移できる */
        val canCheckStore: Boolean = false,
        /** 店舗対象判定画面を開くときのプリフィル(検索クエリやNearbyのPOI名の店舗名部分) */
        val storeNameHint: String = "",
        /**
         * 画面タイトルに出す表示名(近隣リストの POI 名=支店付き)。
         * null のときはチェーン名(merchant.name)を使う。
         */
        val displayName: String? = null,
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
         * 「地図」のチェーン絞り込み(レンズ2段目)。非空なら ジャンル絞り込みより優先し、地図/一覧を
         * これらのチェーンだけに絞る。在チェーン選択((2))とブリッジ(探す→近く・期間限定の施策詳細,(3))の
         * 着地状態を共有する(単一チェーンのブリッジは要素1個の Set)。空セットで未絞り込み。
         * 表示名にのみ使うので Merchant をそのまま保持(フィルタは id 比較)。
         */
        val nearbyMerchantFilters: Set<Merchant> = emptySet(),
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
         * 施策の詳細は出さず「あること」だけ知らせ、タップで期間限定タブへ送る。
         */
        val searchMunicipalAreaNames: List<String> = emptyList(),
        val campaignFilter: CampaignFilter = CampaignFilter.ALL,
        val timeLimitedActive: List<Campaign> = emptyList(),
        val timeLimitedUpcoming: List<Campaign> = emptyList(),
        val merchantNames: Map<String, String> = emptyMap(),
        /** 施策 id → 発行体の識別色(#RRGGBB)。色は施策でなく発行体カタログ側に持つため、ここで解決して配る */
        val campaignBrandColors: Map<String, String> = emptyMap(),
        val selectedCampaignGroup: List<CampaignJudgment>? = null,
        /**
         * 施策詳細→地図ブリッジの復元先。ブリッジ時に閉じた施策詳細(selectedCampaignGroup)を保持し、
         * 地図タブで戻る操作をしたときに期間限定タブ+施策詳細へ復帰する。下部ナビでの手動タブ切替は
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
        /** 設定画面の「マイカード」用カタログ(未所有カードも含む全候補) */
        val cardSettings: List<CardSetting> = emptyList(),
        /** 設定画面の「カードブランド」用(カタログの card_brands 由来。常時表示) */
        val brandSettings: List<BrandSetting> = emptyList(),
        /** 設定画面の「QR 決済」用カタログ(payment_methods.json のカタログ + ユーザー差分) */
        val qrPaymentSettings: List<QrPaymentSetting> = emptyList(),
        /** 設定画面「マイカード」に出すカスタムカード(カタログ外。DataStore 由来) */
        val customCards: List<CustomCard> = emptyList(),
        /** 登録済みエリア(自治体単体・グループ) */
        val registeredAreas: List<RegisteredArea> = emptyList(),
        /** 自治体マスタ。設定画面のピッカーと期間限定タブの地域フィルタに使う(起動時に assets から読む) */
        val municipalityMaster: MunicipalityMaster = MunicipalityMaster(),
        /** 期間限定タブで「登録地域のみ」を解除して全件表示中か(セッション内のみ。永続化しない) */
        val showAllCampaigns: Boolean = false,
        val dataCommitRef: String = "",
        val useTestData: Boolean = false,
        val useBundledData: Boolean = false,
        val developerMode: Boolean = false,
        /** 開発者向け設定画面(設定タブ上のオーバーレイ)を表示中か */
        val developerSettingsOpen: Boolean = false,
    )

    /**
     * 設定画面の「カードブランド」1件分。カタログのカードとは別に、イシュアー不問のブランド施策
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

    /** 設定画面のカード1枚分の表示・編集状態(payment_methods カタログ + ユーザー差分のマージ結果) */
    data class CardSetting(
        val cardId: String,
        val cardName: String,
        /** 発行体の識別色(#RRGGBB)。カード名の左のドット表示に使う */
        val brandColor: String?,
        val owned: Boolean,
        /** 表示・編集する還元率(上書きがあれば上書き値、無ければ既定) */
        val rate: Double,
        /** 実ブランド(ユーザー設定。単一ブランド製品は自動確定)。空文字は未選択 */
        val brand: String,
        /** この製品で選べるブランドの選択肢(カタログ) */
        val brands: List<String>,
        /** ブランド選択 UI を出すか。ブランドが判定に効き(Amex除外/ブランド施策)かつ選択肢が複数のカードのみ true */
        val showBrandPicker: Boolean,
        /** ウエル活チェックの定義(null ならチェックを出さない) */
        val pointMultiplier: PointMultiplier?,
        val welcatsu: Boolean,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    // applyData(IO) と設定購読(Main)の両方から書き換わるので可視性を確保する
    @Volatile
    private var engine: JudgmentEngine? = null

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

    private var lastFetchSucceededAt = 0L

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

    // 自治体マスタ(assets 同梱)は起動時に読む。設定画面のピッカーに加え、期間限定タブの
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

    /** チェーンと店舗名ヒントから判定詳細用の Selection を組む(判定・遷移可否をまとめて引く) */
    private fun JudgmentEngine.selectionFor(
        merchant: Merchant,
        storeNameHint: String,
        displayName: String? = null,
    ): Selection {
        val result = judgeAll(merchant, LocalDate.now(), enabledQrIds())
        return Selection(
            merchant = merchant,
            judgments = result.judgments,
            bestOption = result.bestOption,
            canCheckStore = canCheckStore(merchant),
            storeNameHint = storeNameHint,
            displayName = displayName,
        )
    }

    /** 検索結果のうち、所有カードで対象になる施策が1つ以上あるチェーンだけ残す(reward 無しは一覧に出さない) */
    private fun JudgmentEngine.searchRewarded(query: String, categories: Set<String>): List<SearchResult> =
        search(query, categories).mapNotNull { merchant ->
            val today = LocalDate.now()
            val result = judgeAll(merchant, today, enabledQrIds())
            if (result.judgments.isEmpty()) return@mapNotNull null
            val allCampaigns = result.judgments.map { it.campaign }
            SearchResult(
                merchant = merchant,
                bestBenefit = result.bestBenefitLabel(),
                campaignCount = allCampaigns.distinctBy { it.id }.size,
                brandColors = result.judgments.mapNotNull { it.brandColor }.distinct().take(3),
                hasTimeLimited = allCampaigns.any { it.isTimeLimited },
            )
        }

    private fun applyData(loaded: LoadedData) {
        lastLoaded = loaded
        rebuild()
    }

    /**
     * 直近のデータ(lastLoaded)とユーザー設定(lastSettings)からエンジンを作り直し状態へ反映する。
     * エンジンへは「所有カードのみ + 還元率/ブランド/ウエル活上書き」をマージしたカード一覧を渡す
     * (JudgmentEngine 自体は純 Kotlin のまま=実データテストを維持)。設定画面用カタログ(未所有も含む)は別に組む。
     */
    private fun rebuild() {
        val loaded = lastLoaded ?: return
        val settings = lastSettings
        val baseCards = loaded.data.cards

        // エンジン用: 所有カードのみ、上書きを反映したカード一覧。
        // 実ブランドはユーザー設定(CardOverride.brand)が唯一の情報源で、カタログが単一ブランド製品の
        // ときだけ自動確定する。複数ブランド製品で未選択なら空文字=どのブランドとも断定しない
        // (Amex 除外は発動せず、card_brand 施策にも一致しない)。
        val mergedCards = baseCards.mapNotNull { card ->
            val ov = settings.cardOverrides[card.id]
            if (ov?.owned == false) return@mapNotNull null
            val rawRate = ov?.rate ?: card.effectiveRateDefault
            val welcatsuOn = ov?.welcatsu == true && card.pointMultiplier != null
            val factor = if (welcatsuOn) card.pointMultiplier?.factor ?: 1.0 else 1.0
            card.copy(
                brand = ov?.brand ?: card.brands.singleOrNull().orEmpty(),
                effectiveRateDefault = rawRate?.let { it * factor },
                welcatsuApplied = welcatsuOn,
            )
        }
        // カスタムカード(カタログ外)は登録内容をそのまま PaymentCard に写してエンジンへ渡す。
        // 判定はキャンペーン駆動なので、施策が参照しない限り判定結果には現れない。現状効くのは
        // ブランド付き登録が card_brand 施策に一致する経路のみで、card_id 施策はカスタムキャンペーン
        // (#7)が custom: id を参照し始めた時点で自然に効く。
        val customCards = settings.customCards.map { c ->
            PaymentCard(
                id = c.id,
                cardName = c.name,
                brandColor = c.color ?: CustomCard.DEFAULT_COLOR,
                brands = listOfNotNull(c.brand.takeIf { it.isNotBlank() }),
                brand = c.brand,
            )
        }
        // ブランド単位の登録(カタログ外のカード)は仮想カードとしてエンジンに渡す。card_brand 施策の
        // resolveCard がブランド一致でマッチするだけで、card_id 施策には一致しない(id が衝突しないため)。
        // カタログのカードの後ろに置き、複数一致時の代表はカタログのカード(具体名)を優先する。
        val brandCards = settings.ownedBrands.sorted().map { brand ->
            PaymentCard(
                id = "owned_brand_${brand.lowercase()}",
                cardName = "${brand}カード",
                brands = listOf(brand),
                brand = brand,
            )
        }
        val engineData = loaded.data.copy(cards = mergedCards + customCards + brandCards)
        val newEngine = JudgmentEngine(engineData)
        engine = newEngine

        val today = LocalDate.now()
        // 期間限定タブの一覧。登録エリアがあれば既定で絞り込む(「すべて表示」トグルで解除可)
        val applyAreaFilter: (List<Campaign>) -> List<Campaign> = { campaigns ->
            if (_state.value.showAllCampaigns) campaigns
            else filterCampaignsByArea(campaigns, settings.registeredAreas, _state.value.municipalityMaster)
        }
        val timeLimitedActive = applyAreaFilter(
            newEngine.activeCampaigns(today).filter { it.campaignType != CampaignType.CARD_PROGRAM }
        )
        val timeLimitedUpcoming = applyAreaFilter(
            newEngine.upcomingCampaigns(today).filter { it.campaignType != CampaignType.CARD_PROGRAM }
        )

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
            // 1カード:N施策なので、紐づくどれかの施策に amex_excluded ルールがあればブランド選択を出す
            val cardCampaigns = loaded.data.campaigns.filter { it.cardId == card.id }
            val brandAffectsJudgment =
                cardCampaigns.any { c -> c.merchantRules.any { it.amexExcluded } } || hasBrandCampaign
            CardSetting(
                cardId = card.id,
                cardName = card.cardName,
                brandColor = card.brandColor,
                owned = ov?.owned ?: true,
                rate = ov?.rate ?: card.effectiveRateDefault ?: 0.0,
                brand = ov?.brand ?: card.brands.singleOrNull().orEmpty(),
                brands = card.brands,
                // ブランドが判定に効き(Amex除外 or ブランド施策あり)、かつ製品として選択肢が複数
                // あるカードだけ選択 UI を出す(単一ブランド製品は固定なので出さない)
                showBrandPicker = brandAffectsJudgment && card.brands.size > 1,
                pointMultiplier = card.pointMultiplier,
                welcatsu = ov?.welcatsu ?: false,
            )
        }

        // 設定画面「カードブランド」: カタログ(card_brands)の選択肢を常時出す。事前に登録しておけば
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

        _state.update {
            it.copy(
                loading = false,
                error = null,
                dataUpdatedAt = loaded.data.updatedAt,
                dataSource = loaded.source,
                dataCommitSha = loaded.commitSha,
                categories = newEngine.categories,
                results = newEngine.searchRewarded(it.query, it.selectedCategories),
                selection = it.selection?.let { sel ->
                    loaded.data.merchants.firstOrNull { m -> m.id == sel.merchant.id }
                        ?.let { m -> newEngine.selectionFor(m, sel.storeNameHint, sel.displayName) }
                },
                storeCheck = it.storeCheck?.let { sc ->
                    loaded.data.merchants.firstOrNull { m -> m.id == sc.merchant.id }
                        ?.takeIf { m -> newEngine.canCheckStore(m) }
                        ?.let { m -> StoreCheckState(m, sc.input, newEngine.checkStore(m, sc.input)) }
                },
                themeMode = settings.themeMode,
                dynamicColor = settings.dynamicColor,
                autoRefresh = settings.autoRefresh,
                cardSettings = cardSettings,
                brandSettings = brandSettings,
                qrPaymentSettings = qrPaymentSettings,
                customCards = settings.customCards,
                registeredAreas = settings.registeredAreas,
                dataCommitRef = settings.dataCommitRef,
                useTestData = settings.useTestData,
                useBundledData = settings.useBundledData,
                developerMode = settings.developerMode,
                timeLimitedActive = timeLimitedActive,
                timeLimitedUpcoming = timeLimitedUpcoming,
                searchMunicipalAreaNames = searchMunicipalAreaNames,
                merchantNames = loaded.data.merchants.associate { it.id to it.name },
                campaignBrandColors = loaded.data.campaigns
                    .mapNotNull { c -> loaded.data.brandColorOf(c)?.let { c.id to it } }
                    .toMap(),
            )
        }
    }

    fun onQueryChange(query: String) {
        _state.update {
            it.copy(
                query = query,
                results = engine?.searchRewarded(query, it.selectedCategories).orEmpty(),
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
        val data = lastLoaded?.data
        // チェーン絞り込み中の merchant は、非対象日・開始前でも YOLP 検索対象に加える
        // (施策詳細からのブリッジで「場所の下見」ができるように。判定が無い店は還元率ラベルなしで出す)
        val filterIds = _state.value.nearbyMerchantFilters.map { it.id }.toSet()
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
                "周辺店舗を取得できませんでした。地図サーバが混雑しているか、通信が不安定な可能性があります。少し時間をおいて再度お試しください。",
            )
            return
        }
        val today = LocalDate.now()
        val qrIds = enabledQrIds()
        val places = pois
            .mapNotNull { poi ->
                val merchant = engine.matchStore(poi.name) ?: return@mapNotNull null
                if (engine.isFacilityTenant(merchant.name, poi.name)) return@mapNotNull null
                if (engine.isExcludedStore(merchant, poi.name)) return@mapNotNull null
                val result = engine.judgeAll(merchant, today, qrIds)
                // 判定なしは通常出さないが、チェーン絞り込み中(ブリッジ由来)の merchant は
                // 非対象日の場所確認用に残す(bestBenefit なし=還元率ラベルなしで表示)
                if (result.judgments.isEmpty() && merchant.id !in filterIds) return@mapNotNull null
                val allCampaigns = result.judgments.map { it.campaign }
                NearbyPlace(
                    name = poi.name,
                    distanceMeters = GeoMath.distanceMeters(originLat, originLon, poi.lat, poi.lon),
                    distanceFromCenter = GeoMath.distanceMeters(centerLat, centerLon, poi.lat, poi.lon),
                    merchant = merchant,
                    bestBenefit = result.bestBenefitLabel(),
                    lat = poi.lat,
                    lon = poi.lon,
                    brandColors = result.judgments.mapNotNull { it.brandColor }.distinct(),
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
                centerLat = centerLat,
                centerLon = centerLon,
                userLat = userLat,
                userLon = userLon,
                zoom = effectiveZoom,
                searchStamp = gen,
            ),
        )
        resolveMunicipalNotice(gen, centerLat, centerLon)
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
            // ピルの文言はより狭い単位を優先(市区町村があればそれ、県全域施策だけなら県名)
            val label = matched.mapNotNull { it.region }
                .firstOrNull { !it.isPrefectureWide }?.name ?: prefecture
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
    private fun applyNearbyIfCurrent(gen: Int, nearby: NearbyUi) {
        _state.update { if (gen == nearbyGeneration) it.copy(nearby = nearby) else it }
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
            st.copy(nearby = nearby.copy(selectedPlace = place))
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
            it.copy(selection = engine.selectionFor(merchant, place.name, displayName = place.name))
        }
    }

    fun onToggleCategory(category: String) {
        _state.update {
            val selected = if (category in it.selectedCategories) {
                it.selectedCategories - category
            } else {
                it.selectedCategories + category
            }
            it.copy(
                selectedCategories = selected,
                results = engine?.searchRewarded(it.query, selected).orEmpty(),
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
     * 「地図」のチェーン絞り込みのトグル(在チェーンのピッカーのチェック/ピルの×=レンズ2段目)。
     * ジャンル選択は残したまま(全ピルを解除すると元のジャンル絞り込みに戻る=ドリルダウンの体験)。
     */
    fun onToggleNearbyChain(merchant: Merchant) {
        _state.update {
            val current = it.nearbyMerchantFilters
            val next = if (current.any { m -> m.id == merchant.id }) {
                current.filterNot { m -> m.id == merchant.id }.toSet()
            } else {
                current + merchant
            }
            it.copy(nearbyMerchantFilters = next)
        }
    }

    /**
     * ブリッジ: 判定詳細(探す由来)の「近くのこのお店を探す」は単一チェーン、期間限定タブの施策詳細
     * (「近くの対象店舗を探す」)は 1〜N チェーン。そのチェーン群に絞った状態を作り、
     * タブを NEARBY に切り替えて元の画面を閉じる。実際の「地図」突入(位置情報パーミッション→fetchNearby)は
     * UI 側が続けて行う。ジャンル絞り込みはクリアしてチェーンに集中する(ピル解除で全件に戻る)。
     * 閉じた判定詳細は selectionBridgeReturn に保存し、地図タブの戻る操作で判定詳細へ復帰できるようにする。
     */
    fun onFindNearby(merchant: Merchant) {
        val returnSelection = _state.value.selection
        onFindNearby(setOf(merchant))
        _state.update { it.copy(selectionBridgeReturn = returnSelection) }
    }

    /**
     * 施策詳細から: merchant_rules の merchant_id 群を解決してブリッジする(解決できた分だけ)。
     * location_hint 持ち(自販機等)は地図で探せないので除く。閉じる施策詳細を campaignBridgeReturn に
     * 保存し、地図タブの戻る操作で施策詳細へ復帰できるようにする。
     */
    fun onFindNearbyByIds(merchantIds: Collection<String>) {
        val idSet = merchantIds.toSet()
        val merchants = lastLoaded?.data?.merchants.orEmpty()
            .filter { it.id in idSet && it.locationHint == null }
            .toSet()
        if (merchants.isEmpty()) return
        val returnGroup = _state.value.selectedCampaignGroup
        onFindNearby(merchants)
        _state.update { it.copy(campaignBridgeReturn = returnGroup) }
    }

    private fun onFindNearby(merchants: Set<Merchant>) {
        val prev = _state.value.selectedTab
        if (prev == AppTab.NEARBY) nearbyGeneration++
        _state.update { st ->
            var s = st.copy(
                selectedTab = AppTab.NEARBY,
                nearbyMerchantFilters = merchants,
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

    fun onSelect(merchant: Merchant) {
        val engine = engine ?: return
        _state.update {
            // 「マクドナルド渋谷店」のような入力で選んだ場合は、店舗名部分を対象判定のプリフィルに引き継ぐ
            val hint = it.query.trim()
                .takeUnless { q -> q.isBlank() || engine.isExactNameMatch(merchant, q) }
                .orEmpty()
            it.copy(selection = engine.selectionFor(merchant, hint))
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
     * お店タブのお知らせバナーから: 期間限定タブへ移動し、自治体フィルタを効かせて着地させる
     * (「登録地域のみ」は既定 ON なので、そのまま登録地域の自治体施策一覧になる)
     */
    fun onOpenMunicipalCampaigns() {
        onSelectTab(AppTab.CAMPAIGNS)
        _state.update { it.copy(campaignFilter = CampaignFilter.MUNICIPAL) }
    }

    fun onSelectCampaignGroup(group: List<Campaign>) {
        val e = engine ?: return
        val today = LocalDate.now()
        val catalog = lastLoaded?.data
        val qrMap = catalog?.qrPayments?.associateBy { it.id }.orEmpty()
        val judgments = group.map { campaign ->
            val qr = campaign.paymentMethodId?.let { qrMap[it] }
            val benefitType = com.ktakjm.poikatsu.domain.BenefitType.fromString(campaign.benefitType)
            val isLottery = benefitType == com.ktakjm.poikatsu.domain.BenefitType.LOTTERY
            val todayIsTarget = isTargetDay(campaign, today)
            CampaignJudgment(
                campaign = campaign,
                // ブランド施策はイシュアー不問なので、バッジは運営者でなくブランド名を出す
                badgeLabel = qr?.name ?: campaign.cardBrand ?: campaign.operator,
                // 未所有カードの施策も期間限定タブには出るため、色は全カタログから引く
                brandColor = catalog?.brandColorOf(campaign),
                benefitType = benefitType,
                effectiveRate = campaign.rateBase.takeUnless { isLottery },
                discountAmount = campaign.discountAmount.takeUnless { isLottery },
                daysRemaining = e.daysRemaining(campaign, today),
                // merchant 未特定のため店舗固有分は乗らず、campaign 直下(施策全体に一様に効く事実)だけが出る
                eligibleNotes = campaign.eligibleNotes,
                ineligibleNotes = campaign.ineligibleNotes,
                storeListUrl = null,
                warnings = buildList {
                    val days = e.daysRemaining(campaign, today)
                    if (days != null && days <= 3) add("残り${days}日")
                    campaign.googlePayIneligibleWarning?.let { add(it) }
                },
                minPurchase = campaign.minPurchase,
                usageLimitText = campaign.usageLimitNote
                    ?: campaign.usageLimit?.let { "お一人様${it}回まで" },
                perTransactionCap = campaign.perTransactionCap,
                periodTotalCap = campaign.periodTotalCap,
                capNote = campaign.capNote,
                storeSearchUrl = if (campaign.storeScope == "external") campaign.storeSearchUrl else null,
                detailUrl = campaign.detailUrl,
                appLinks = qr?.appLinks.orEmpty().ifEmpty { listOfNotNull(campaign.walletAppLink) },
                pointMultiplier = null,
                welcatsuApplied = false,
                mayEndEarly = campaign.mayEndEarly,
                todayIsTarget = todayIsTarget,
                nextTargetDate = if (todayIsTarget) null else nextTargetDay(campaign, today),
            )
        }
        _state.update { it.copy(selectedCampaignGroup = judgments) }
    }

    fun onCloseCampaignDetail() {
        _state.update { it.copy(selectedCampaignGroup = null) }
    }

    // --- 設定値の更新(DataStore へ書き込み → settings Flow 経由で rebuild される) ---
    fun onSetThemeMode(mode: ThemeMode) = viewModelScope.launch { settingsRepo.setThemeMode(mode) }

    fun onSetDynamicColor(enabled: Boolean) = viewModelScope.launch { settingsRepo.setDynamicColor(enabled) }

    fun onSetAutoRefresh(enabled: Boolean) = viewModelScope.launch { settingsRepo.setAutoRefresh(enabled) }

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

    fun onOpenDeveloperSettings() {
        _state.update { it.copy(developerSettingsOpen = true) }
    }

    fun onCloseDeveloperSettings() {
        _state.update { it.copy(developerSettingsOpen = false) }
    }

    fun onSetCardOwned(cardId: String, owned: Boolean) =
        viewModelScope.launch { settingsRepo.setOwned(cardId, owned) }

    fun onSetCardRate(cardId: String, rate: Double?) =
        viewModelScope.launch { settingsRepo.setRate(cardId, rate) }

    fun onSetCardBrand(cardId: String, brand: String) =
        viewModelScope.launch { settingsRepo.setBrand(cardId, brand) }

    fun onSetCardWelcatsu(cardId: String, enabled: Boolean) =
        viewModelScope.launch { settingsRepo.setWelcatsu(cardId, enabled) }

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

    fun onAddRegisteredArea(area: RegisteredArea) =
        viewModelScope.launch { settingsRepo.addRegisteredArea(area) }

    fun onRemoveRegisteredArea(area: RegisteredArea) =
        viewModelScope.launch { settingsRepo.removeRegisteredArea(area) }

    /** 期間限定タブの「登録地域のみ / すべて」切替。設定でなく閲覧モードなので永続化しない */
    fun onToggleShowAllCampaigns() {
        _state.update { it.copy(showAllCampaigns = !it.showAllCampaigns) }
        rebuild()
    }
}