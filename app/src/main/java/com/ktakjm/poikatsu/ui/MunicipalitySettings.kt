package com.ktakjm.poikatsu.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.ktakjm.poikatsu.data.MunicipalityMaster
import com.ktakjm.poikatsu.data.Prefecture
import com.ktakjm.poikatsu.data.RegisteredArea
import com.ktakjm.poikatsu.data.RegisteredAreaType
import kotlinx.coroutines.launch

/**
 * 自治体サブページ(#47)。登録済みリストと追加ピッカーへの導線を置く。
 * 登録済みの表示名は「都道府県 名前」([areaDisplayName])——「南部」だけではどこの南部か
 * 分からないため。フィルタロジック(RegionFilter)は name/code 照合なので表示だけの変更。
 *
 * 登録済みリストの ✕ は確認ダイアログを挟まず即削除し、Snackbar「元に戻す」で取り消せる(#49)。
 * undo 経路はアプリ共通の snackbarHost を使う。ピッカー内のトグル解除には出さない
 * (チェックし直せばその場で戻せるし、ダイアログの背面に Snackbar が出ても押せないため)。
 */
@Composable
internal fun MunicipalitySettingsPage(
    registeredAreas: List<RegisteredArea>,
    municipalityMaster: MunicipalityMaster,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onAdd: (RegisteredArea) -> Unit,
    onRemove: (RegisteredArea) -> Unit,
) {
    BackHandler(onBack = onBack)
    var showMunicipalityPicker by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // 「元に戻す」は再追加で実現する(リスト末尾に付き直るが、並びは登録順で意味を持たないため許容)
    fun removeWithUndo(area: RegisteredArea) {
        onRemove(area)
        scope.launch {
            val result = snackbarHostState.showSnackbar(
                message = "「${areaDisplayName(area)}」を削除しました",
                actionLabel = "元に戻す",
                duration = SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) onAdd(area)
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text(
            "自治体を登録すると、おトクタブをその地域のキャンペーンに絞れます。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        registeredAreas.forEach { area ->
            // グループは構成自治体数を supporting に出す(マスタ未ロード・不一致時は種別のみ)
            val supporting = if (area.type == RegisteredAreaType.GROUP) {
                val memberCount = groupMemberCount(municipalityMaster, area.code)
                if (memberCount != null) "グループ・${memberCount}市区町村" else "グループ"
            } else {
                null
            }
            ListItem(
                headlineContent = { Text(areaDisplayName(area)) },
                supportingContent = supporting?.let { { Text(it) } },
                trailingContent = {
                    IconButton(onClick = { removeWithUndo(area) }) {
                        Icon(Icons.Default.Close, contentDescription = "削除")
                    }
                },
                colors = transparentListItemColors(),
            )
        }
        ListItem(
            headlineContent = { Text("自治体を追加") },
            leadingContent = {
                Icon(
                    Icons.Default.Add,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            },
            modifier = Modifier.clickable { showMunicipalityPicker = true },
            colors = transparentListItemColors(),
        )
    }

    if (showMunicipalityPicker) {
        MunicipalityPickerDialog(
            master = municipalityMaster,
            registered = registeredAreas,
            onAdd = onAdd,
            onRemove = onRemove,
            onDismiss = { showMunicipalityPicker = false },
        )
    }
}

/** グループ id からマスタの構成自治体数を引く。マスタ未ロード・id 不一致は null */
private fun groupMemberCount(master: MunicipalityMaster, groupId: String): Int? =
    master.prefectures.asSequence()
        .flatMap { it.groups }
        .firstOrNull { it.id == groupId }
        ?.municipalities?.size

/**
 * 自治体追加ダイアログ。都道府県選択→「グループ」+「市区町村」の2段ピッカー。
 * グループ(東京23区・埼玉県南部 等)はマスタ(municipalities.json)由来で、
 * 並び順もマスタのまま出す(補完グループ→一次細分→その配下の細分)。
 * 行はチェックボックスのトグルで登録/解除が即時反映される(他の設定項目と同じ即時適用。
 * 「登録済み=操作不能」にしないのは、押し間違いをその場で取り消せるようにするため)。
 * グループ行は ▼ で構成自治体名を展開できる(「23区西部」がどこまでか行タップなしで確認できる)。
 *
 * 都道府県選択の階には検索フィールドを置き、自治体名・グループ名の部分一致で
 * 都道府県横断の候補を出す(#49。名前が分かっているとき 2 段を辿らずに済む近道。
 * 既存の都道府県から辿る導線はそのまま残す)。
 */
@Composable
private fun MunicipalityPickerDialog(
    master: MunicipalityMaster,
    registered: List<RegisteredArea>,
    onAdd: (RegisteredArea) -> Unit,
    onRemove: (RegisteredArea) -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedPrefecture by remember { mutableStateOf<Prefecture?>(null) }
    var expandedGroupIds by remember { mutableStateOf(emptySet<String>()) }
    var searchQuery by remember { mutableStateOf("") }
    val registeredKeys = remember(registered) {
        registered.map { "${it.type}:${it.code}" }.toSet()
    }
    fun isRegistered(area: RegisteredArea) = "${area.type}:${area.code}" in registeredKeys
    fun toggle(area: RegisteredArea) = if (isRegistered(area)) onRemove(area) else onAdd(area)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (selectedPrefecture != null) {
                    IconButton(onClick = {
                        selectedPrefecture = null
                        expandedGroupIds = emptySet()
                    }) {
                        Icon(Icons.Default.Close, contentDescription = "戻る")
                    }
                }
                Text(selectedPrefecture?.name ?: "自治体を追加")
            }
        },
        text = {
            val prefecture = selectedPrefecture
            if (master.isEmpty()) {
                Box(
                    Modifier.fillMaxWidth().height(200.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            } else if (prefecture == null) {
                Column {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("自治体名で検索") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        trailingIcon = if (searchQuery.isNotEmpty()) {
                            {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(Icons.Default.Close, contentDescription = "検索をクリア")
                                }
                            }
                        } else {
                            null
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (searchQuery.isBlank()) {
                        LazyColumn(Modifier.fillMaxWidth().height(400.dp)) {
                            items(master.prefectures) { pref ->
                                ListItem(
                                    headlineContent = { Text(pref.name) },
                                    colors = transparentListItemColors(),
                                    modifier = Modifier.clickable { selectedPrefecture = pref },
                                )
                            }
                        }
                    } else {
                        val results = remember(master, searchQuery) { searchAreas(master, searchQuery) }
                        if (results.isEmpty()) {
                            Box(
                                Modifier.fillMaxWidth().height(400.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    "「${searchQuery.trim()}」に一致する自治体はありません",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else {
                            LazyColumn(Modifier.fillMaxWidth().height(400.dp)) {
                                items(results, key = { "${it.type}:${it.code}" }) { area ->
                                    // どの県の候補か分かるよう supporting に都道府県を出す(グループは構成数も)
                                    val supporting = if (area.type == RegisteredAreaType.GROUP) {
                                        val memberCount = groupMemberCount(master, area.code)
                                        if (memberCount != null) {
                                            "${area.prefecture}・グループ(${memberCount}市区町村)"
                                        } else {
                                            "${area.prefecture}・グループ"
                                        }
                                    } else {
                                        area.prefecture
                                    }
                                    AreaPickerRow(
                                        area = area,
                                        supporting = supporting,
                                        checked = isRegistered(area),
                                        onToggle = ::toggle,
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                val municipalityNames = remember(prefecture) {
                    prefecture.municipalities.associate { it.code to it.name }
                }
                LazyColumn(Modifier.fillMaxWidth().height(400.dp)) {
                    if (prefecture.groups.isNotEmpty()) {
                        item { PickerSectionHeader("グループ(まとめて登録)") }
                        prefecture.groups.forEach { group ->
                            val area = RegisteredArea(
                                type = RegisteredAreaType.GROUP,
                                code = group.id,
                                name = group.name,
                                prefecture = prefecture.name,
                            )
                            val expanded = group.id in expandedGroupIds
                            item(key = group.id) {
                                AreaPickerRow(
                                    area = area,
                                    supporting = "${group.municipalities.size}市区町村",
                                    checked = isRegistered(area),
                                    onToggle = ::toggle,
                                    expanded = expanded,
                                    onToggleExpand = {
                                        expandedGroupIds =
                                            if (expanded) expandedGroupIds - group.id
                                            else expandedGroupIds + group.id
                                    },
                                )
                            }
                            if (expanded) {
                                item(key = "${group.id}:members") {
                                    // 行の直下にぶら下がる角丸パネル(プルダウン風)。start はチェック
                                    // ボックス分を空けて行の文字位置に揃える
                                    Surface(
                                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(start = 56.dp, end = 16.dp, bottom = 8.dp),
                                    ) {
                                        Text(
                                            group.municipalities
                                                .mapNotNull { municipalityNames[it] }
                                                .joinToString("・"),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                    item { PickerSectionHeader("市区町村") }
                    items(prefecture.municipalities, key = { it.code }) { m ->
                        val area = RegisteredArea(
                            type = RegisteredAreaType.MUNICIPALITY,
                            code = m.code,
                            name = m.name,
                            prefecture = prefecture.name,
                        )
                        AreaPickerRow(
                            area = area,
                            supporting = null,
                            checked = isRegistered(area),
                            onToggle = ::toggle,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("閉じる") }
        },
    )
}

/**
 * 自治体名・グループ名の部分一致検索(#49)。都道府県横断で、並びはマスタのまま
 * (都道府県順・各県内はグループ→市区町村)返す。前後の空白は無視し、空クエリは空リスト。
 * マスタに読みがなが無いため表記(漢字)の部分一致のみ。
 */
internal fun searchAreas(master: MunicipalityMaster, query: String): List<RegisteredArea> {
    val q = query.trim()
    if (q.isEmpty()) return emptyList()
    return master.prefectures.flatMap { pref ->
        pref.groups.filter { q in it.name }.map { group ->
            RegisteredArea(RegisteredAreaType.GROUP, group.id, group.name, pref.name)
        } + pref.municipalities.filter { q in it.name }.map { m ->
            RegisteredArea(RegisteredAreaType.MUNICIPALITY, m.code, m.name, pref.name)
        }
    }
}

@Composable
private fun PickerSectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun AreaPickerRow(
    area: RegisteredArea,
    supporting: String?,
    checked: Boolean,
    onToggle: (RegisteredArea) -> Unit,
    /** グループ行の構成自治体の展開状態。null なら展開 UI を出さない(市区町村行) */
    expanded: Boolean? = null,
    onToggleExpand: (() -> Unit)? = null,
) {
    ListItem(
        headlineContent = { Text(area.name) },
        supportingContent = supporting?.let { { Text(it) } },
        leadingContent = {
            // クリック処理は行全体の toggleable に一本化する(チェックボックス側にもハンドラを
            // 張ると同一タップで二重発火し、解除→即再登録になることがある)
            Checkbox(checked = checked, onCheckedChange = null)
        },
        trailingContent = expanded?.let { isExpanded ->
            {
                IconButton(onClick = { onToggleExpand?.invoke() }) {
                    Icon(
                        if (isExpanded) Icons.Default.KeyboardArrowUp
                        else Icons.Default.KeyboardArrowDown,
                        contentDescription = if (isExpanded) "構成を閉じる" else "構成を表示",
                    )
                }
            }
        },
        colors = transparentListItemColors(),
        modifier = Modifier.toggleable(
            value = checked,
            role = Role.Checkbox,
            onValueChange = { onToggle(area) },
        ),
    )
}
