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
import com.ktakjm.poikatsu.data.OverpassClient
import com.ktakjm.poikatsu.domain.Judgment
import com.ktakjm.poikatsu.domain.JudgmentEngine
import com.ktakjm.poikatsu.domain.StoreVerdict
import com.ktakjm.poikatsu.util.GeoMath
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
    )

    data class StoreCheckState(
        val merchant: Merchant,
        val input: String,
        val verdicts: List<StoreVerdict>,
    )

    data class NearbyPlace(
        val name: String,
        val distanceMeters: Int,
        val merchant: Merchant?,
        val bestRate: Double?,
        val lat: Double,
        val lon: Double,
        /** 最上位施策のブランドカラー("#RRGGBB")。地図ピンの着色に使う */
        val brandColor: String? = null,
    )

    data class NearbyUi(
        val loading: Boolean = false,
        val error: String? = null,
        val places: List<NearbyPlace> = emptyList(),
        /** 検索の中心(=地図カメラ中心)。距離計算の起点。取得前は null */
        val centerLat: Double? = null,
        val centerLon: Double? = null,
        /** 実際の現在地(地図上の青ドット)。「このエリアを検索」しても保持する */
        val userLat: Double? = null,
        val userLon: Double? = null,
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
        val nearbyRadiusM: Int = 1000,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private var engine: JudgmentEngine? = null

    private val locationProvider = LocationProvider(app)

    private val repository = DataRepository(
        readAsset = { name ->
            app.assets.open(name).bufferedReader().use { it.readText() }
        },
        cacheDir = File(app.filesDir, "remote_data"),
        fetchRemote = GithubRawClient::fetch,
    )

    private var lastFetchSucceededAt = 0L

    // リモート取得は init ではなく ON_START(onAppForeground)起点。
    // 初回起動時も Activity の ON_START で必ず一度走る。
    private val initialLoad: Job = viewModelScope.launch(Dispatchers.IO) {
        try {
            applyData(repository.loadLocal())
        } catch (e: Exception) {
            _state.update { it.copy(loading = false, error = "データの読み込みに失敗しました: ${e.message}") }
        }
    }

    /** アプリがフォアグラウンドに来るたびに呼ぶ。直近で成功していればスキップ */
    fun onAppForeground() = refresh(force = false)

    /** 更新ボタン。スキップせず必ず取得を試みる */
    fun onManualRefresh() = refresh(force = true)

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
    private fun JudgmentEngine.selectionFor(merchant: Merchant, storeNameHint: String) =
        Selection(merchant, judge(merchant), canCheckStore(merchant), storeNameHint)

    private fun applyData(loaded: LoadedData) {
        val newEngine = JudgmentEngine(loaded.data)
        engine = newEngine
        _state.update {
            it.copy(
                loading = false,
                error = null,
                dataUpdatedAt = loaded.data.updatedAt,
                dataSource = loaded.source,
                categories = newEngine.categories,
                results = newEngine.search(it.query, it.selectedCategories),
                // 表示中の判定があればデータ差し替え後の内容で引き直す
                selection = it.selection?.let { sel ->
                    loaded.data.merchants.firstOrNull { m -> m.id == sel.merchant.id }
                        ?.let { m -> newEngine.selectionFor(m, sel.storeNameHint) }
                },
                // 店舗対象判定画面を開いていれば、新データで判定を引き直す
                storeCheck = it.storeCheck?.let { sc ->
                    loaded.data.merchants.firstOrNull { m -> m.id == sc.merchant.id }
                        ?.takeIf { m -> newEngine.canCheckStore(m) }
                        ?.let { m -> StoreCheckState(m, sc.input, newEngine.checkStore(m, sc.input)) }
                },
            )
        }
    }

    fun onQueryChange(query: String) {
        _state.update {
            it.copy(
                query = query,
                results = engine?.search(query, it.selectedCategories).orEmpty(),
                selection = null,
                storeCheck = null,
                nearby = null,
            )
        }
    }

    /** 位置情報パーミッション取得済みの前提で呼ぶ(UI側でリクエスト) */
    fun fetchNearby() {
        if (engine == null) return
        _state.update { it.copy(nearby = NearbyUi(loading = true), selection = null, storeCheck = null) }
        viewModelScope.launch(Dispatchers.IO) {
            val location = locationProvider.currentLocation()
            if (location == null) {
                _state.update { it.copy(nearby = NearbyUi(error = "現在地を取得できませんでした。位置情報設定を確認してください")) }
                return@launch
            }
            // 起点も青ドットも現在地
            loadNearbyAround(location.latitude, location.longitude, location.latitude, location.longitude)
        }
    }

    /** 地図の中心を起点に再検索(「このエリアを検索」)。現在地の青ドットは維持する */
    fun searchHere(lat: Double, lon: Double) {
        if (engine == null) return
        val prev = _state.value.nearby
        val userLat = prev?.userLat ?: lat
        val userLon = prev?.userLon ?: lon
        _state.update { it.copy(nearby = NearbyUi(loading = true)) }
        viewModelScope.launch(Dispatchers.IO) {
            loadNearbyAround(lat, lon, userLat, userLon)
        }
    }

    /** centerLat/centerLon を起点に Overpass 検索し、その点からの距離順でリスト化する */
    private fun loadNearbyAround(centerLat: Double, centerLon: Double, userLat: Double, userLon: Double) {
        val engine = engine ?: return
        val pois = OverpassClient.fetchNearby(centerLat, centerLon, radiusM = _state.value.nearbyRadiusM)
        if (pois == null) {
            _state.update {
                it.copy(
                    nearby = NearbyUi(
                        error = "周辺店舗を取得できませんでした。地図サーバが混雑しているか、通信が不安定な可能性があります。少し時間をおいて再度お試しください。",
                    ),
                )
            }
            return
        }
        // 対象施策のあるチェーンのみ、検索中心からの距離が近い順に表示
        val places = pois
            .mapNotNull { poi ->
                val merchant = engine.matchStore(poi.name, poi.brand) ?: return@mapNotNull null
                // 公式に「対象外」と明示された店舗(例: アカチャンホンポのららぽーと内店舗)は近隣リストに出さない
                if (engine.isExcludedStore(merchant, poi.displayName)) return@mapNotNull null
                val top = engine.judge(merchant).firstOrNull()
                NearbyPlace(
                    name = poi.displayName,
                    distanceMeters = GeoMath.distanceMeters(centerLat, centerLon, poi.lat, poi.lon),
                    merchant = merchant,
                    bestRate = top?.effectiveRate,
                    lat = poi.lat,
                    lon = poi.lon,
                    brandColor = top?.campaign?.brandColor,
                )
            }
            .sortedBy { it.distanceMeters }
        _state.update {
            it.copy(
                nearby = NearbyUi(
                    places = places,
                    centerLat = centerLat,
                    centerLon = centerLon,
                    userLat = userLat,
                    userLon = userLon,
                ),
            )
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
        _state.update { it.copy(nearby = null) }
    }

    /** 周辺リストの店をタップ → POI名を店舗対象判定のプリフィルに引き継ぐ */
    fun onSelectNearby(place: NearbyPlace) {
        val engine = engine ?: return
        val merchant = place.merchant ?: return
        _state.update {
            it.copy(selection = engine.selectionFor(merchant, place.name))
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
                results = engine?.search(it.query, selected).orEmpty(),
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
}
