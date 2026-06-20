package com.ktakjm.poikatsu.ui

import android.app.Application
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {

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
        /** 現在地からの距離(m)。検索の起点=地図中心ではなく、常に現在地基準 */
        val distanceMeters: Int,
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
    )

    data class UiState(
        val loading: Boolean = true,
        val error: String? = null,
        val query: String = "",
        val categories: List<String> = emptyList(),
        val selectedCategories: Set<String> = emptySet(),
        val results: List<Merchant> = emptyList(),
        val selection: Selection? = null,
        val storeCheck: StoreCheckState? = null,
        val dataUpdatedAt: String = "",
        val dataSource: DataSource? = null,
        val refreshing: Boolean = false,
        val refreshFailed: Boolean = false,
        val nearby: NearbyUi? = null,
        /** 近隣の再検索が失敗したときの Snackbar 文言(地図は残す一時失敗)。表示後に null へ戻す */
        val nearbySearchFailed: String? = null,
        val nearbyRadiusM: Int = 1000,
        /** 設定オーバーレイの表示中フラグ。探す/近くのどちらの上にも重ねて開ける */
        val showSettings: Boolean = false,
        // --- 設定値(DataStore 由来) ---
        val themeMode: ThemeMode = ThemeMode.SYSTEM,
        val dynamicColor: Boolean = true,
        val autoRefresh: Boolean = true,
        /** 設定画面の「マイカード」用カタログ(未所有カードも含む全候補) */
        val cardSettings: List<CardSetting> = emptyList(),
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
        fetchRemote = GithubRawClient::fetch,
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
        .onEach { lastSettings = it; rebuild() }
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
            val loaded = repository.refresh()
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
    private fun JudgmentEngine.searchRewarded(query: String, categories: Set<String>): List<Merchant> =
        search(query, categories).filter { judge(it).isNotEmpty() }

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
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            val location = locationProvider.currentLocation()
            if (location == null) {
                failNearby(gen, "現在地を取得できませんでした。位置情報設定を確認してください")
                return@launch
            }
            setNearbyPhase(gen, NearbyLoadPhase.SEARCHING)
            // 起点も青ドットも現在地
            loadNearbyAround(gen, location.latitude, location.longitude, location.latitude, location.longitude)
        }
    }

    /** 地図の中心を起点に再検索(「このエリアを検索」)。現在地の青ドットは維持する */
    fun searchHere(lat: Double, lon: Double) {
        if (engine == null) return
        val prev = _state.value.nearby
        val userLat = prev?.userLat ?: lat
        val userLon = prev?.userLon ?: lon
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
            loadNearbyAround(gen, lat, lon, userLat, userLon)
        }
    }

    /**
     * centerLat/centerLon を起点に YOLP で周辺店舗を取得し、現在地(userLat/userLon)からの
     * 距離が近い順でリスト化する。検索の起点(地図中心)と距離の基準(現在地)は別物で、
     * 「このエリアを検索」で遠方を見ても距離は常に現在地から測る。
     */
    private fun loadNearbyAround(gen: Int, centerLat: Double, centerLon: Double, userLat: Double, userLon: Double) {
        val engine = engine ?: return
        val pois = YolpClient.fetchNearby(centerLat, centerLon, radiusM = _state.value.nearbyRadiusM)
        if (pois == null) {
            failNearby(
                gen,
                "周辺店舗を取得できませんでした。地図サーバが混雑しているか、通信が不安定な可能性があります。少し時間をおいて再度お試しください。",
            )
            return
        }
        // 対象施策のあるチェーンのみ、現在地からの距離が近い順に表示(距離は地図中心ではなく現在地基準)
        val places = pois
            .mapNotNull { poi ->
                val merchant = engine.matchStore(poi.name, poi.brand) ?: return@mapNotNull null
                // 商業施設(=対象チェーン)内テナントの誤検知を除外
                // (例: 「ドミー安城横山店大嶽クリーニング」はドミーではなくクリーニング店)
                if (engine.isFacilityTenant(merchant.name, poi.displayName)) return@mapNotNull null
                // 公式に「対象外」と明示された店舗(例: アカチャンホンポのららぽーと内店舗)は近隣リストに出さない
                if (engine.isExcludedStore(merchant, poi.displayName)) return@mapNotNull null
                val judgments = engine.judge(merchant)
                // 所有カードで対象になる施策が無ければ近隣リストに出さない
                if (judgments.isEmpty()) return@mapNotNull null
                NearbyPlace(
                    name = poi.displayName,
                    distanceMeters = GeoMath.distanceMeters(userLat, userLon, poi.lat, poi.lon),
                    merchant = merchant,
                    bestRate = judgments.firstOrNull()?.effectiveRate,
                    lat = poi.lat,
                    lon = poi.lon,
                    brandColors = judgments.mapNotNull { it.campaign.brandColor }.distinct(),
                )
            }
            .sortedBy { it.distanceMeters }
            // 同一店舗の重複を排除(YOLP は同じ店を別名・空白違いで複数返すことがある。
            // 例: 「KFC…店」と「ケンタッキーフライドチキン…店」、空白有無違いの同名)。
            // 「チェーン + 支店名」がともに一致するものを同一店舗とみなし、最も近い1件を残す。
            // 座標基準にしないのは、同一モール内に同チェーンの別店舗(例: レイクタウンの複数スタバ)が
            // 入る場合に誤って1件へ潰さないため(支店名が異なれば別物として残る)。
            .distinctBy { p ->
                val m = p.merchant
                if (m == null) "?:${p.name}" else "${m.id}:${engine.normalizedBranch(m, p.name)}"
            }
        applyNearbyIfCurrent(
            gen,
            NearbyUi(
                places = places,
                centerLat = centerLat,
                centerLon = centerLon,
                userLat = userLat,
                userLon = userLon,
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

    fun onNearbyRadiusChange(radiusM: Int) {
        if (_state.value.nearbyRadiusM == radiusM) return
        _state.update { it.copy(nearbyRadiusM = radiusM) }
        // 直近の検索中心(地図中心)を保ったまま半径だけ変えて再検索。無ければ現在地から
        val prev = _state.value.nearby
        if (prev?.centerLat != null && prev.centerLon != null) {
            searchHere(prev.centerLat, prev.centerLon)
        } else {
            fetchNearby()
        }
    }

    fun onLocationDenied() {
        _state.update { it.copy(nearby = NearbyUi(error = "位置情報の許可が必要です。端末の設定からこのアプリに位置情報を許可してください")) }
    }

    fun onCloseNearby() {
        // 進行中の近隣取得を無効化(世代を進める)。完了しても「近く」タブへ戻されない。
        // 未表示の再検索失敗も破棄し、次に「近く」を開いたとき古い Snackbar が出ないようにする
        nearbyGeneration++
        _state.update { it.copy(nearby = null, nearbySearchFailed = null) }
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

    /** 設定オーバーレイを開く(探す/近くのどちらからでも。下の画面状態は保持する) */
    fun onOpenSettings() = _state.update { it.copy(showSettings = true) }

    /** 設定オーバーレイを閉じ、開く前の画面(探す/近く)へ戻る */
    fun onCloseSettings() = _state.update { it.copy(showSettings = false) }

    // --- 設定値の更新(DataStore へ書き込み → settings Flow 経由で rebuild される) ---
    fun onSetThemeMode(mode: ThemeMode) = viewModelScope.launch { settingsRepo.setThemeMode(mode) }

    fun onSetDynamicColor(enabled: Boolean) = viewModelScope.launch { settingsRepo.setDynamicColor(enabled) }

    fun onSetAutoRefresh(enabled: Boolean) = viewModelScope.launch { settingsRepo.setAutoRefresh(enabled) }

    fun onSetCardOwned(campaignId: String, owned: Boolean) =
        viewModelScope.launch { settingsRepo.setOwned(campaignId, owned) }

    fun onSetCardRate(campaignId: String, rate: Double?) =
        viewModelScope.launch { settingsRepo.setRate(campaignId, rate) }

    fun onSetCardBrand(campaignId: String, brand: String) =
        viewModelScope.launch { settingsRepo.setBrand(campaignId, brand) }

    fun onSetCardWelcatsu(campaignId: String, enabled: Boolean) =
        viewModelScope.launch { settingsRepo.setWelcatsu(campaignId, enabled) }
}
