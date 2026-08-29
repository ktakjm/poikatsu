package com.ktakjm.poikatsu.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ktakjm.poikatsu.data.MunicipalityMaster
import com.ktakjm.poikatsu.data.Prefecture
import com.ktakjm.poikatsu.data.Region
import com.ktakjm.poikatsu.domain.isPrefectureWide

/**
 * 地域(Region)の表示名。「秋田県 湯沢市」、県全域は「神奈川県 全域」
 * (「神奈川県 神奈川県」にしない。おトクタブのタイトルは県名のみだが、選択 UI では
 * 「全域」と明示した方が市区町村を選び忘れたのと区別できる)。
 */
internal fun regionDisplayName(region: Region): String =
    if (region.isPrefectureWide) "${region.prefecture} 全域" else "${region.prefecture} ${region.name}"

/**
 * 自治体名・都道府県名の部分一致検索(都道府県横断)。市区町村は Region(name, prefecture)、
 * 都道府県名に一致した県は県全域(name == prefecture)として返す。並びはマスタのまま
 * (都道府県順・各県内は県全域→市区町村)。前後の空白は無視し、空クエリは空リスト。
 * 設定側の [searchAreas] と違いグループは対象外(施策の region に「東京23区」は書けない)。
 */
internal fun searchRegions(master: MunicipalityMaster, query: String): List<Region> {
    val q = query.trim()
    if (q.isEmpty()) return emptyList()
    return master.prefectures.flatMap { pref ->
        (if (q in pref.name) listOf(prefectureWideRegion(pref)) else emptyList()) +
            pref.municipalities.filter { q in it.name }.map { Region(name = it.name, prefecture = pref.name) }
    }
}

private fun prefectureWideRegion(pref: Prefecture) = Region(name = pref.name, prefecture = pref.name)

/**
 * 自治体キャンペーン(#91)の地域を 1 つ選ぶダイアログ。設定の自治体ピッカー
 * (MunicipalityPickerDialog)と同じ 2 段構成(都道府県→市区町村。都道府県の階に横断検索)だが、
 * **単一選択で行タップ即確定**し、グループ(まとめて登録)行は出さない(施策の region は
 * 自治体 1 つか県全域のため)。県全域は各都道府県の先頭に「{県名} 全域」行として置き、
 * 同梱データと同じ name == prefecture の規約([isPrefectureWide])に写す。
 */
@Composable
internal fun RegionPickerDialog(
    master: MunicipalityMaster,
    selected: Region?,
    onSelect: (Region) -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedPrefecture by remember { mutableStateOf<Prefecture?>(null) }
    var searchQuery by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (selectedPrefecture != null) {
                    IconButton(onClick = { selectedPrefecture = null }) {
                        Icon(Icons.Default.Close, contentDescription = "戻る")
                    }
                }
                Text(selectedPrefecture?.name ?: "地域を選択")
            }
        },
        text = {
            val prefecture = selectedPrefecture
            if (master.isEmpty()) {
                Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
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
                            items(master.prefectures, key = { it.code }) { pref ->
                                ListItem(
                                    headlineContent = { Text(pref.name) },
                                    colors = transparentListItemColors(),
                                    modifier = Modifier.clickable { selectedPrefecture = pref },
                                )
                            }
                        }
                    } else {
                        val results = remember(master, searchQuery) { searchRegions(master, searchQuery) }
                        if (results.isEmpty()) {
                            Box(Modifier.fillMaxWidth().height(400.dp), contentAlignment = Alignment.Center) {
                                Text(
                                    "「${searchQuery.trim()}」に一致する自治体はありません",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else {
                            LazyColumn(Modifier.fillMaxWidth().height(400.dp)) {
                                items(results, key = { "${it.prefecture}:${it.name}" }) { region ->
                                    // どの県の候補か分かるよう supporting に都道府県を出す
                                    RegionPickerRow(
                                        label = if (region.isPrefectureWide) "${region.name} 全域" else region.name,
                                        supporting = region.prefecture,
                                        selected = region == selected,
                                        onClick = { onSelect(region) },
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                LazyColumn(Modifier.fillMaxWidth().height(400.dp)) {
                    val wide = prefectureWideRegion(prefecture)
                    item(key = "wide") {
                        RegionPickerRow(
                            label = "${prefecture.name} 全域",
                            supporting = "県内の全ての市区町村が対象のキャンペーン",
                            selected = wide == selected,
                            onClick = { onSelect(wide) },
                        )
                    }
                    items(prefecture.municipalities, key = { it.code }) { m ->
                        val region = Region(name = m.name, prefecture = prefecture.name)
                        RegionPickerRow(
                            label = m.name,
                            supporting = null,
                            selected = region == selected,
                            onClick = { onSelect(region) },
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

@Composable
private fun RegionPickerRow(
    label: String,
    supporting: String?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(label) },
        supportingContent = supporting?.let { { Text(it) } },
        // クリック処理は行全体に一本化する(ラジオ側にもハンドラを張ると二重発火する)
        leadingContent = { RadioButton(selected = selected, onClick = null) },
        colors = transparentListItemColors(),
        modifier = Modifier.clickable(onClick = onClick),
    )
}
