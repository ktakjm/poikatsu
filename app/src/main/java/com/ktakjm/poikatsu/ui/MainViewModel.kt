package com.ktakjm.poikatsu.ui

import android.app.Application
import android.location.Geocoder
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ktakjm.poikatsu.data.DataRepository
import com.ktakjm.poikatsu.data.DataSource
import com.ktakjm.poikatsu.data.GithubRawClient
import com.ktakjm.poikatsu.data.LoadedData
import com.ktakjm.poikatsu.data.LocationProvider
import com.ktakjm.poikatsu.data.Merchant
import com.ktakjm.poikatsu.data.YolpClient
import com.ktakjm.poikatsu.data.AppSettings
import com.ktakjm.poikatsu.data.PointMultiplier
import com.ktakjm.poikatsu.data.Profile
import com.ktakjm.poikatsu.data.SettingsRepository
import com.ktakjm.poikatsu.data.ThemeMode
import com.ktakjm.poikatsu.domain.Judgment
import com.ktakjm.poikatsu.domain.JudgmentEngine
import com.ktakjm.poikatsu.domain.StoreVerdict
import com.ktakjm.poikatsu.util.GeoMath
import java.io.File
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

/** 「近く」初回・「現在地で検索」時の既定半径(m)。以降の「このエリアを検索」は地図の可視範囲から算出する */
private const val NEARBY_DEFAULT_RADIUS_M = 2000

/** 「近く」初回・「現在地で検索」時の既定ズーム。可視範囲検索では各回の地図ズームを引き継ぐ */
private const val NEARBY_DEFAULT_ZOOM = 16.0

/** 500m 以内の店舗がこの件数未満なら引きズーム(NEARBY_WIDE_ZOOM)にする */
private const val NEARBY_DENSE_THRESHOLD = 10
private const val NEARBY_DENSE_RADIUS_M = 500
private const val NEARBY_WIDE_ZOOM = 15.0

class MainViewModel(app: Application) : AndroidViewModel(app) {

    data class SearchResult(
        val merchant: Merchant,
        val bestRate: Double,
        val campaignCount: Int,
        /** ブランドカラー("#RRGGBB")を還元率の高い順に最大3色 */
        val brandColors: List<String>,
    )

    data class Selection(
        val merchant: Merchant,
        val judgments: List<Judgment>,
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
        val bestRate: Double?,
        val lat: Double,
        val lon: Double,
        /**
         * 対応する全施策のブランドカラー("#RRGGBB")を還元率の高い順・重複排除で並べたもの。
         * 地図ピンの着色に使い、複数あれば発行体ごとに色を分けて描く(両対応なら 2 色)。
         */
        val brandColors: List<String> = emptyList(),
    )

    /**
     * 近隣取得のローディング段階。リングの待ち時間が何待ちかを表示で出し分けるために持つ。
     * 地図(Google Maps)はこの間まだ描画されていない=「地図の読み込み」ではない点に注意。
     */
    enum class NearbyLoadPhase {
        /** 現在地の測位中(LocationProvider、最大15秒)。searchHere 経由では通らない */
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
        val refreshing: Boolean = false,
        val refreshFailed: Boolean = false,
        val nearby: NearbyUi? = null,
        /** 近隣の再検索が失敗したときの Snackbar 文言(地図は残す一時失敗)。表示後に null へ戻す */
        val nearbySearchFailed: String? = null,
        /**
         * 「近く」のジャンル絞り込み。探す側の selectedCategories とは独立に持つ
         * (一方の絞り込みが他方に波及しないように)。空セットは全ジャンル。
         * クライアント側フィルタなので YOLP 再取得は不要で、再検索(searchHere/半径変更/
         * fetchNearby)をまたいで保持したいため NearbyUi(毎回作り直す)でなくここに置く。
         */
        val nearbySelectedCategories: Set<String> = emptySet(),
        /**
         * 「近く」のチェーン絞り込み(レンズ2段目)。設定中はジャンル絞り込みより優先し、地図/一覧を
         * このチェーンだけに絞る。在チェーン選択((2))とブリッジ(探す→近く,(3))の着地状態を共有する。
         * null で未絞り込み。表示名にのみ使うので Merchant をそのまま保持(フィルタは id 比較)。
         */
        val nearbyMerchantFilter: Merchant? = null,
        /**
         * 「近く」の起点(地名検索)。null は GPS 起点(既定)。設定中は距離・並び順をこの地点から測る。
         * 再検索(searchHere/fetchNearby)をまたいで保持し、「現在地で検索」/検索バーの✕で null に戻す。
         */
        val nearbyOrigin: GeocodedPlace? = null,
        /** ジオコーディング候補リスト。検索バーで地名を入力→送信後に結果が入る */
        val geocodeCandidates: List<GeocodedPlace> = emptyList(),
        /** ジオコーディング中フラグ */
        val isGeocoding: Boolean = false,
        /** 設定オーバーレイの表示中フラグ。探す/近くのどちらの上にも重ねて開ける */
        val showSettings: Boolean = false,
        // --- 設定値(DataStore 由来) ---
        val themeMode: ThemeMode = ThemeMode.SYSTEM,
        val dynamicColor: Boolean = true,
        val autoRefresh: Boolean = true,
        /** 設定画面の「マイカード」用カタログ(未所有カードも含む全候補) */
        val cardSettings: List<CardSetting> = emptyList(),
        val dataCommitRef: String = "",
    )

    /** 設定画面のカード1枚分の表示・編集状態(profile カタログ + ユーザー差分のマージ結果) */
    data class CardSetting(
        val campaignId: String,
        val cardName: String,
        val owned: Boolean,
        /** 表示・編集する還元率(上書きがあれば上書き値、無ければ既定) */
        val rate: Double,
        val brand: String,
        /** ブランド選択(Amex 等)を出すか。amex_excluded ルールを持つ施策のみ true */
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
        readAsset = { name ->
            app.assets.open(name).bufferedReader().use { it.readText() }
        },
        cacheDir = File(app.filesDir, "remote_data"),
        fetchRemote = { fileName, ref -> GithubRawClient.fetch(fileName, ref) },
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
     * 完了しても古い世代なら結果を捨てる。読込中に「近く」タブを離れたのに、取得完了の
     * タイミングで勝手に近くタブへ戻されるのを防ぐ。書込はUIスレッドのみ・IOスレッドは
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

    // DataStore の設定変更を購読し、変わるたびにエンジン・状態を作り直す
    private val settingsJob: Job = settingsRepo.settings
        .onEach { new ->
            val refChanged = lastSettings.dataCommitRef != new.dataCommitRef
            lastSettings = new
            rebuild()
            if (refChanged) refresh(force = true)
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
        if (_state.value.refreshing) return
        if (!force && System.currentTimeMillis() - lastFetchSucceededAt < AUTO_REFRESH_MIN_INTERVAL_MS) return
        viewModelScope.launch(Dispatchers.IO) {
            initialLoad.join() // ローカルロード完了前にリモート結果で上書きされない順序を保証
            _state.update { it.copy(refreshing = true) }
            val ref = lastSettings.dataCommitRef.ifBlank { "main" }
            val loaded = repository.refresh(ref)
            if (loaded != null) {
                lastFetchSucceededAt = System.currentTimeMillis()
                applyData(loaded)
            }
            _state.update { it.copy(refreshing = false, refreshFailed = loaded == null) }
        }
    }

    companion object {
        // 施策データの更新は月数回程度なので、自動再取得は1時間に1回で十分
        private const val AUTO_REFRESH_MIN_INTERVAL_MS = 60 * 60_000L
    }

    /** チェーンと店舗名ヒントから判定詳細用の Selection を組む(判定・遷移可否をまとめて引く) */
    private fun JudgmentEngine.selectionFor(
        merchant: Merchant,
        storeNameHint: String,
        displayName: String? = null,
    ) = Selection(merchant, judge(merchant), canCheckStore(merchant), storeNameHint, displayName)

    /** 検索結果のうち、所有カードで対象になる施策が1つ以上あるチェーンだけ残す(reward 無しは一覧に出さない) */
    private fun JudgmentEngine.searchRewarded(query: String, categories: Set<String>): List<SearchResult> =
        search(query, categories).mapNotNull { merchant ->
            val judgments = judge(merchant)
            if (judgments.isEmpty()) return@mapNotNull null
            SearchResult(
                merchant = merchant,
                bestRate = judgments.first().effectiveRate,
                campaignCount = judgments.size,
                brandColors = judgments.mapNotNull { it.campaign.brandColor }.distinct().take(3),
            )
        }

    private fun applyData(loaded: LoadedData) {
        lastLoaded = loaded
        rebuild()
    }

    /**
     * 直近のデータ(lastLoaded)とユーザー設定(lastSettings)からエンジンを作り直し状態へ反映する。
     * エンジンへは「所有カードのみ + 還元率/ブランド/ウエル活上書き」をマージした profile を渡す
     * (JudgmentEngine 自体は純 Kotlin のまま=実データテストを維持)。設定画面用カタログ(未所有も含む)は別に組む。
     */
    private fun rebuild() {
        val loaded = lastLoaded ?: return
        val settings = lastSettings
        val baseCards = loaded.data.profile.cards

        // エンジン用: 所有カードのみ、上書きを反映した profile
        val mergedCards = baseCards.mapNotNull { card ->
            val ov = settings.cardOverrides[card.campaignId]
            if (ov?.owned == false) return@mapNotNull null
            val rawRate = ov?.rate ?: card.effectiveRateDefault
            val welcatsuOn = ov?.welcatsu == true && card.pointMultiplier != null
            val factor = if (welcatsuOn) card.pointMultiplier?.factor ?: 1.0 else 1.0
            card.copy(
                brand = ov?.brand ?: card.brand,
                effectiveRateDefault = rawRate?.let { it * factor },
                welcatsuApplied = welcatsuOn,
            )
        }
        val engineData = loaded.data.copy(profile = Profile(mergedCards))
        val newEngine = JudgmentEngine(engineData)
        engine = newEngine

        // 設定画面「マイカード」カタログ: 未所有カードも含む全候補 + 現在の上書き値
        val cardSettings = baseCards.map { card ->
            val ov = settings.cardOverrides[card.campaignId]
            val campaign = loaded.data.campaigns.firstOrNull { it.id == card.campaignId }
            CardSetting(
                campaignId = card.campaignId,
                cardName = card.cardName,
                owned = ov?.owned ?: true,
                rate = ov?.rate ?: card.effectiveRateDefault ?: 0.0,
                brand = ov?.brand ?: card.brand,
                showBrandPicker = campaign?.merchantRules?.any { it.amexExcluded } == true,
                pointMultiplier = card.pointMultiplier,
                welcatsu = ov?.welcatsu ?: false,
            )
        }

        _state.update {
            it.copy(
                loading = false,
                error = null,
                dataUpdatedAt = loaded.data.updatedAt,
                dataSource = loaded.source,
                categories = newEngine.categories,
                results = newEngine.searchRewarded(it.query, it.selectedCategories),
                // 表示中の判定があればマージ後の内容で引き直す
                selection = it.selection?.let { sel ->
                    loaded.data.merchants.firstOrNull { m -> m.id == sel.merchant.id }
                        ?.let { m -> newEngine.selectionFor(m, sel.storeNameHint, sel.displayName) }
                },
                // 店舗対象判定画面を開いていれば、新データで判定を引き直す
                storeCheck = it.storeCheck?.let { sc ->
                    loaded.data.merchants.firstOrNull { m -> m.id == sc.merchant.id }
                        ?.takeIf { m -> newEngine.canCheckStore(m) }
                        ?.let { m -> StoreCheckState(m, sc.input, newEngine.checkStore(m, sc.input)) }
                },
                themeMode = settings.themeMode,
                dynamicColor = settings.dynamicColor,
                autoRefresh = settings.autoRefresh,
                cardSettings = cardSettings,
                dataCommitRef = settings.dataCommitRef,
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
                nearby = null,
            )
        }
    }

    /** 位置情報パーミッション取得済みの前提で呼ぶ(UI側でリクエスト) */
    fun fetchNearby() {
        if (engine == null) return
        val gen = ++nearbyGeneration
        // 再取得(📍)中も直前の地図・一覧は残し loading だけ立てる(画面をまっさらにしない)。
        // 初回は prev が無い=NearbyUi() なので center も null となり全画面ローディングになる。
        // 📍 は現在地起点に戻すため nearbyOrigin もクリアする。
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
                nearbyOrigin = null,
                geocodeCandidates = emptyList(),
                isGeocoding = false,
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            val location = locationProvider.currentLocation()
            if (location == null) {
                failNearby(gen, "現在地を取得できませんでした。位置情報設定を確認してください")
                return@launch
            }
            setNearbyPhase(gen, NearbyLoadPhase.SEARCHING)
            // 起点も青ドットも現在地。初回/現在地検索は既定半径・既定ズームで取り直す
            loadNearbyAround(
                gen, location.latitude, location.longitude, location.latitude, location.longitude,
                location.latitude, location.longitude,
                radiusM = NEARBY_DEFAULT_RADIUS_M, zoom = NEARBY_DEFAULT_ZOOM,
                adaptZoom = true,
            )
        }
    }

    /**
     * 地図の中心を起点に再検索(「このエリアを検索」)。半径は地図の可視範囲から、ズームは現在の
     * 地図ズームを受け取り、結果反映時にカメラを動かさず可視範囲そのままで取り直す。青ドットは維持。
     */
    fun searchHere(lat: Double, lon: Double, radiusM: Int, zoom: Double) {
        if (engine == null) return
        val prev = _state.value.nearby
        val userLat = prev?.userLat ?: lat
        val userLon = prev?.userLon ?: lon
        val origin = _state.value.nearbyOrigin
        val originLat = origin?.lat ?: userLat
        val originLon = origin?.lon ?: userLon
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
        userLat: Double,
        userLon: Double,
        originLat: Double,
        originLon: Double,
        radiusM: Int,
        zoom: Double,
        adaptZoom: Boolean = false,
    ) {
        val engine = engine ?: return
        val pois = YolpClient.fetchNearby(centerLat, centerLon, radiusM = radiusM)
        if (pois == null) {
            failNearby(
                gen,
                "周辺店舗を取得できませんでした。地図サーバが混雑しているか、通信が不安定な可能性があります。少し時間をおいて再度お試しください。",
            )
            return
        }
        val places = pois
            .mapNotNull { poi ->
                val merchant = engine.matchStore(poi.name, poi.brand) ?: return@mapNotNull null
                if (engine.isFacilityTenant(merchant.name, poi.displayName)) return@mapNotNull null
                if (engine.isExcludedStore(merchant, poi.displayName)) return@mapNotNull null
                val judgments = engine.judge(merchant)
                if (judgments.isEmpty()) return@mapNotNull null
                NearbyPlace(
                    name = poi.displayName,
                    distanceMeters = GeoMath.distanceMeters(originLat, originLon, poi.lat, poi.lon),
                    distanceFromCenter = GeoMath.distanceMeters(centerLat, centerLon, poi.lat, poi.lon),
                    merchant = merchant,
                    bestRate = judgments.firstOrNull()?.effectiveRate,
                    lat = poi.lat,
                    lon = poi.lon,
                    brandColors = judgments.mapNotNull { it.campaign.brandColor }.distinct(),
                )
            }
            .sortedBy { it.distanceFromCenter }
            // 同一店舗の重複を排除(YOLP は同じ店を別名・空白違いで複数返すことがある。
            // 例: 「KFC…店」と「ケンタッキーフライドチキン…店」、空白有無違いの同名)。
            // 「チェーン + 支店名」がともに一致するものを同一店舗とみなし、最も近い1件を残す。
            // 座標基準にしないのは、同一モール内に同チェーンの別店舗(例: レイクタウンの複数スタバ)が
            // 入る場合に誤って1件へ潰さないため(支店名が異なれば別物として残る)。
            .distinctBy { p ->
                val m = p.merchant
                if (m == null) "?:${p.name}" else "${m.id}:${engine.normalizedBranch(m, p.name)}"
            }
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
            ),
        )
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

    /** 読込中のローディング段階だけを進める(最新世代かつ読込中のときのみ。完了済みは触らない) */
    private fun setNearbyPhase(gen: Int, phase: NearbyLoadPhase) {
        _state.update {
            val n = it.nearby
            if (gen == nearbyGeneration && n != null && n.loading) {
                it.copy(nearby = n.copy(loadingPhase = phase))
            } else {
                it
            }
        }
    }

    fun onLocationDenied() {
        _state.update { it.copy(nearby = NearbyUi(error = "位置情報の許可が必要です。端末の設定からこのアプリに位置情報を許可してください")) }
    }

    fun onCloseNearby() {
        // 進行中の近隣取得を無効化(世代を進める)。完了しても「近く」タブへ戻されない。
        // 未表示の再検索失敗も破棄し、次に「近く」を開いたとき古い Snackbar が出ないようにする
        nearbyGeneration++
        _state.update {
            it.copy(
                nearby = null,
                nearbySearchFailed = null,
                nearbyOrigin = null,
                geocodeCandidates = emptyList(),
                isGeocoding = false,
            )
        }
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
     * 「近く」のジャンル絞り込みトグル。表示集合の絞り込みは UI 側で nearby.places に対して行う
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
     * 「近く」のチェーン絞り込み(在チェーンのピッカーから選択=レンズ2段目)。ジャンル選択は
     * 残したまま(ピルを解除すると元のジャンル絞り込みに戻る=ドリルダウンの体験)。
     */
    fun onSelectNearbyChain(merchant: Merchant) {
        _state.update { it.copy(nearbyMerchantFilter = merchant) }
    }

    /** チェーン絞り込みを解除(ピルの×)。ジャンル絞り込みは元の選択に戻る。 */
    fun onClearNearbyChain() {
        _state.update { it.copy(nearbyMerchantFilter = null) }
    }

    /**
     * ブリッジ: 判定詳細(探す由来)から「近くのこの店を探す」。そのチェーンに絞った状態を作り、
     * 判定詳細を閉じる。実際の「近く」突入(位置情報パーミッション→fetchNearby)は UI 側が続けて行う。
     * ジャンル絞り込みはクリアしてチェーン1点に集中する(ピル解除で全件に戻る)。
     */
    fun onFindNearby(merchant: Merchant) {
        _state.update {
            it.copy(
                nearbyMerchantFilter = merchant,
                nearbySelectedCategories = emptySet(),
                selection = null,
            )
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
                val addresses = if (Build.VERSION.SDK_INT >= 33) {
                    suspendCancellableCoroutine { cont ->
                        geocoder.getFromLocationName(query, 5) { addresses ->
                            if (cont.isActive) cont.resume(addresses)
                        }
                    }
                } else {
                    @Suppress("DEPRECATION")
                    geocoder.getFromLocationName(query, 5) ?: emptyList()
                }
                addresses.mapNotNull { addr ->
                    val name = addr.featureName
                        ?: addr.getAddressLine(0)?.split(",")?.firstOrNull()?.trim()
                        ?: return@mapNotNull null
                    val fullAddress = buildString {
                        addr.adminArea?.let { append(it) }
                        addr.locality?.let { if (it != addr.adminArea) append(it) }
                        addr.subLocality?.let { append(it) }
                        addr.thoroughfare?.let { append(it) }
                        addr.subThoroughfare?.let { append(it) }
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

    /** 設定オーバーレイを開く(探す/近くのどちらからでも。下の画面状態は保持する) */
    fun onOpenSettings() = _state.update { it.copy(showSettings = true) }

    /** 設定オーバーレイを閉じ、開く前の画面(探す/近く)へ戻る */
    fun onCloseSettings() = _state.update { it.copy(showSettings = false) }

    // --- 設定値の更新(DataStore へ書き込み → settings Flow 経由で rebuild される) ---
    fun onSetThemeMode(mode: ThemeMode) = viewModelScope.launch { settingsRepo.setThemeMode(mode) }

    fun onSetDynamicColor(enabled: Boolean) = viewModelScope.launch { settingsRepo.setDynamicColor(enabled) }

    fun onSetAutoRefresh(enabled: Boolean) = viewModelScope.launch { settingsRepo.setAutoRefresh(enabled) }

    fun onSetDataCommitRef(ref: String) = viewModelScope.launch { settingsRepo.setDataCommitRef(ref) }

    fun onSetCardOwned(campaignId: String, owned: Boolean) =
        viewModelScope.launch { settingsRepo.setOwned(campaignId, owned) }

    fun onSetCardRate(campaignId: String, rate: Double?) =
        viewModelScope.launch { settingsRepo.setRate(campaignId, rate) }

    fun onSetCardBrand(campaignId: String, brand: String) =
        viewModelScope.launch { settingsRepo.setBrand(campaignId, brand) }

    fun onSetCardWelcatsu(campaignId: String, enabled: Boolean) =
        viewModelScope.launch { settingsRepo.setWelcatsu(campaignId, enabled) }
}
