package com.ktakjm.poikatsu.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ktakjm.poikatsu.data.DataRepository
import com.ktakjm.poikatsu.data.DataSource
import com.ktakjm.poikatsu.data.GithubRawClient
import com.ktakjm.poikatsu.data.LoadedData
import com.ktakjm.poikatsu.data.Merchant
import com.ktakjm.poikatsu.domain.Judgment
import com.ktakjm.poikatsu.domain.JudgmentEngine
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
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private var engine: JudgmentEngine? = null

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
