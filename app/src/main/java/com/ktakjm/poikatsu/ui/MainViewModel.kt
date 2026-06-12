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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {

    data class Selection(val merchant: Merchant, val judgments: List<Judgment>)

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

    init {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                applyData(repository.loadLocal())
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = "データの読み込みに失敗しました: ${e.message}") }
                return@launch
            }
            // ローカル表示後にリモートの最新データへ差し替え(失敗時はローカルのまま)
            repository.refresh()?.let { applyData(it) }
        }
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
                        ?.let { m -> Selection(m, newEngine.judge(m)) }
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
        _state.update { it.copy(selection = Selection(merchant, engine.judge(merchant))) }
    }

    fun onBack() {
        _state.update { it.copy(selection = null) }
    }
}
