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
        val branchName: String = "",
    )

    data class NearbyPlace(
        val name: String,
        val distanceMeters: Int,
        val merchant: Merchant?,
        val bestRate: Double?,
    )

    data class NearbyUi(
        val loading: Boolean = false,
        val error: String? = null,
        val places: List<NearbyPlace> = emptyList(),
    )

    data class UiState(
        val loading: Boolean = true,
        val error: String? = null,
        val query: String = "",
        val categories: List<String> = emptyList(),
        val selectedCategories: Set<String> = emptySet(),
        val results: List<Merchant> = emptyList(),
        val selection: Selection? = null,
        val dataUpdatedAt: String = "",
        val dataSource: DataSource? = null,
        val refreshing: Boolean = false,
        val refreshFailed: Boolean = false,
        val nearby: NearbyUi? = null,
        val nearbyRadiusM: Int = 3000,
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
                        ?.let { m -> Selection(m, newEngine.judge(m, sel.branchName), sel.branchName) }
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
                nearby = null,
            )
        }
    }

    /** 位置情報パーミッション取得済みの前提で呼ぶ(UI側でリクエスト) */
    fun fetchNearby() {
        val engine = engine ?: return
        _state.update { it.copy(nearby = NearbyUi(loading = true), selection = null) }
        viewModelScope.launch(Dispatchers.IO) {
            val location = locationProvider.currentLocation()
            if (location == null) {
                _state.update { it.copy(nearby = NearbyUi(error = "現在地を取得できませんでした。位置情報設定を確認してください")) }
                return@launch
            }
            val pois = OverpassClient.fetchNearby(
                location.latitude,
                location.longitude,
                radiusM = _state.value.nearbyRadiusM,
            )
            if (pois == null) {
                _state.update { it.copy(nearby = NearbyUi(error = "周辺店舗の取得に失敗しました。通信状態を確認してください")) }
                return@launch
            }
            // 対象施策のあるチェーンのみ、距離の近い順に表示
            val places = pois
                .mapNotNull { poi ->
                    val merchant = engine.matchStore(poi.name, poi.brand) ?: return@mapNotNull null
                    NearbyPlace(
                        name = poi.displayName,
                        distanceMeters = GeoMath.distanceMeters(location.latitude, location.longitude, poi.lat, poi.lon),
                        merchant = merchant,
                        bestRate = engine.judge(merchant).firstOrNull()?.effectiveRate,
                    )
                }
                .sortedBy { it.distanceMeters }
            _state.update { it.copy(nearby = NearbyUi(places = places)) }
        }
    }

    fun onNearbyRadiusChange(radiusM: Int) {
        if (_state.value.nearbyRadiusM == radiusM) return
        _state.update { it.copy(nearbyRadiusM = radiusM) }
        fetchNearby()
    }

    fun onLocationDenied() {
        _state.update { it.copy(nearby = NearbyUi(error = "位置情報の許可が必要です。端末の設定からこのアプリに位置情報を許可してください")) }
    }

    fun onCloseNearby() {
        _state.update { it.copy(nearby = null) }
    }

    /** 周辺リストの店をタップ → POI名を店舗名チェックに引き継いで判定 */
    fun onSelectNearby(place: NearbyPlace) {
        val engine = engine ?: return
        val merchant = place.merchant ?: return
        _state.update {
            it.copy(selection = Selection(merchant, engine.judge(merchant, place.name), place.name))
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
            )
        }
    }

    fun onSelect(merchant: Merchant) {
        val engine = engine ?: return
        _state.update {
            // 「マクドナルド渋谷店」のような入力で選んだ場合は、入力全体を店舗名チェックに引き継ぐ
            val branch = it.query.trim()
                .takeUnless { q -> q.isBlank() || engine.isExactNameMatch(merchant, q) }
                .orEmpty()
            it.copy(selection = Selection(merchant, engine.judge(merchant, branch), branch))
        }
    }

    fun onBranchNameChange(branchName: String) {
        val engine = engine ?: return
        _state.update {
            val sel = it.selection ?: return@update it
            it.copy(selection = sel.copy(judgments = engine.judge(sel.merchant, branchName), branchName = branchName))
        }
    }

    fun onBack() {
        _state.update { it.copy(selection = null) }
    }
}
