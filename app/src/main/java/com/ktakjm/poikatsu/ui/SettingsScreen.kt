package com.ktakjm.poikatsu.ui

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.ktakjm.poikatsu.BuildConfig
import com.ktakjm.poikatsu.data.RegisteredMunicipality
import com.ktakjm.poikatsu.data.ThemeMode
import com.ktakjm.poikatsu.domain.trimRate
import com.ktakjm.poikatsu.ui.theme.warningColor

/**
 * 設定画面(4 番目のタブ)。ListItem は端まで使うため PaddedColumn を介さず直接置く。
 * 表示/マイカード/QR決済/自治体/データ/このアプリのセクション。
 * 値は DataStore 由来(MainViewModel 経由)で、変更は即 ViewModel の setter へ流す。
 */
@Composable
internal fun SettingsScreen(
    themeMode: ThemeMode,
    dynamicColor: Boolean,
    autoRefresh: Boolean,
    cards: List<MainViewModel.CardSetting>,
    qrPayments: List<MainViewModel.QrPaymentSetting>,
    registeredMunicipalities: List<RegisteredMunicipality>,
    municipalityMaster: Map<String, List<String>>,
    dataStatus: String,
    refreshing: Boolean,
    dataCommitRef: String,
    onThemeModeChange: (ThemeMode) -> Unit,
    onDynamicColorChange: (Boolean) -> Unit,
    onAutoRefreshChange: (Boolean) -> Unit,
    onCardOwnedChange: (String, Boolean) -> Unit,
    onCardRateChange: (String, Double?) -> Unit,
    onCardBrandChange: (String, String) -> Unit,
    onCardWelcatsuChange: (String, Boolean) -> Unit,
    onQrEnabledChange: (String, Boolean) -> Unit,
    onAddMunicipality: (RegisteredMunicipality) -> Unit,
    onRemoveMunicipality: (RegisteredMunicipality) -> Unit,
    onLoadMunicipalityMaster: () -> Unit,
    onRefresh: () -> Unit,
    onDataCommitRefChange: (String) -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    var showMunicipalityPicker by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        // --- 表示 ---
        SettingsSectionHeader("表示")
        ThemeModeRow(themeMode = themeMode, onChange = onThemeModeChange)
        val dynamicSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        val dynamicNote: (@Composable () -> Unit)? =
            if (dynamicSupported) null else ({ Text("Android 12 以降で利用できます") })
        ListItem(
            headlineContent = { Text("壁紙の色を使う") },
            supportingContent = dynamicNote,
            trailingContent = {
                Switch(
                    checked = dynamicColor && dynamicSupported,
                    onCheckedChange = onDynamicColorChange,
                    enabled = dynamicSupported,
                )
            },
        )

        // --- マイカード ---
        SettingsSectionHeader("マイカード")
        cards.forEach { card ->
            CardSettingItem(
                card = card,
                onOwnedChange = { onCardOwnedChange(card.campaignId, it) },
                onRateChange = { onCardRateChange(card.campaignId, it) },
                onBrandChange = { onCardBrandChange(card.campaignId, it) },
                onWelcatsuChange = { onCardWelcatsuChange(card.campaignId, it) },
            )
        }

        // --- QR 決済 ---
        SettingsSectionHeader("QR 決済")
        if (qrPayments.isEmpty()) {
            Text(
                "利用可能な QR 決済がありません",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        } else {
            Text(
                "利用中の QR 決済にチェックを入れると、キャンペーン情報が判定に表示されます。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            qrPayments.forEach { qr ->
                ListItem(
                    headlineContent = { Text(qr.name) },
                    leadingContent = {
                        Checkbox(
                            checked = qr.enabled,
                            onCheckedChange = { onQrEnabledChange(qr.id, it) },
                        )
                    },
                    modifier = Modifier.clickable { onQrEnabledChange(qr.id, !qr.enabled) },
                )
            }
        }

        // --- 自治体 ---
        SettingsSectionHeader("自治体")
        Text(
            "居住地・行動圏の自治体を登録すると、該当するキャンペーンがキャンペーン一覧に表示されます。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        registeredMunicipalities.forEach { m ->
            ListItem(
                headlineContent = { Text(m.name) },
                supportingContent = { Text(m.prefecture) },
                trailingContent = {
                    IconButton(onClick = { onRemoveMunicipality(m) }) {
                        Icon(Icons.Default.Close, contentDescription = "削除")
                    }
                },
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
            modifier = Modifier.clickable {
                onLoadMunicipalityMaster()
                showMunicipalityPicker = true
            },
        )

        // --- データ ---
        SettingsSectionHeader("データ")
        ListItem(
            headlineContent = { Text("データの状態") },
            supportingContent = { Text(dataStatus) },
        )
        ListItem(
            headlineContent = { Text("自動更新") },
            supportingContent = { Text("起動・復帰時に最新データを取得(1時間に1回まで)") },
            trailingContent = { Switch(checked = autoRefresh, onCheckedChange = onAutoRefreshChange) },
        )
        ListItem(
            headlineContent = { Text("今すぐ更新") },
            trailingContent = {
                if (refreshing) {
                    CircularProgressIndicator(Modifier.size(24.dp))
                } else {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                }
            },
            modifier = Modifier.clickable(enabled = !refreshing, onClick = onRefresh),
        )

        // --- 開発者向け ---
        SettingsSectionHeader("開発者向け")
        CommitRefRow(value = dataCommitRef, onChange = onDataCommitRefChange)

        // --- このアプリ ---
        SettingsSectionHeader("このアプリ")
        ListItem(
            headlineContent = { Text("バージョン") },
            trailingContent = { Text(BuildConfig.VERSION_NAME) },
        )
        ListItem(
            headlineContent = { Text("ソースコード(GitHub)") },
            trailingContent = {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
            },
            modifier = Modifier.clickable { uriHandler.openUri("https://github.com/ktakjm/poikatsu") },
        )
        Spacer(Modifier.height(24.dp))
    }

    if (showMunicipalityPicker) {
        MunicipalityPickerDialog(
            master = municipalityMaster,
            registered = registeredMunicipalities,
            onAdd = onAddMunicipality,
            onDismiss = { showMunicipalityPicker = false },
        )
    }
}

/** カード1枚分の設定行: 所有チェック + (所有時) ブランド選択 / 還元率 / ウエル活。 */
@Composable
private fun CardSettingItem(
    card: MainViewModel.CardSetting,
    onOwnedChange: (Boolean) -> Unit,
    onRateChange: (Double?) -> Unit,
    onBrandChange: (String) -> Unit,
    onWelcatsuChange: (Boolean) -> Unit,
) {
    var showRateDialog by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text("${card.cardName}（${card.brand}）") },
        leadingContent = { Checkbox(checked = card.owned, onCheckedChange = onOwnedChange) },
        supportingContent = { Text(if (card.owned) "持っている" else "持っていない") },
        modifier = Modifier.clickable { onOwnedChange(!card.owned) },
    )
    if (card.owned) {
        if (card.showBrandPicker) {
            ListItem(
                headlineContent = { Text("ブランド") },
                trailingContent = { BrandDropdown(brand = card.brand, onChange = onBrandChange) },
                modifier = Modifier.padding(start = 24.dp),
            )
            if (card.brand.equals("Amex", ignoreCase = true)) {
                Text(
                    "Amex は一部店舗が優遇対象外になります",
                    style = MaterialTheme.typography.bodySmall,
                    color = warningColor(),
                    modifier = Modifier.padding(start = 24.dp, end = 16.dp, bottom = 8.dp),
                )
            }
        }
        ListItem(
            headlineContent = { Text("還元率（公式アプリの表示値）") },
            trailingContent = {
                Text("${trimRate(card.rate)}%", style = MaterialTheme.typography.titleMedium)
            },
            modifier = Modifier.padding(start = 24.dp).clickable { showRateDialog = true },
        )
        card.pointMultiplier?.let { pm ->
            val welcatsuNote: (@Composable () -> Unit)? = if (card.welcatsu) {
                ({ Text("${trimRate(card.rate * pm.factor)}% で表示中") })
            } else {
                null
            }
            ListItem(
                headlineContent = { Text(pm.label) },
                leadingContent = { Checkbox(checked = card.welcatsu, onCheckedChange = onWelcatsuChange) },
                supportingContent = welcatsuNote,
                modifier = Modifier.padding(start = 24.dp).clickable { onWelcatsuChange(!card.welcatsu) },
            )
        }
    }
    if (showRateDialog) {
        RateEditDialog(
            initial = card.rate,
            onDismiss = { showRateDialog = false },
            onConfirm = {
                onRateChange(it)
                showRateDialog = false
            },
        )
    }
}

/** ブランド選択(Amex/Mastercard/Visa/JCB)。Amex のときだけ判定挙動が変わる。 */
@Composable
private fun BrandDropdown(brand: String, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) {
            Text(brand.ifBlank { "選択" })
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            listOf("Amex", "Mastercard", "Visa", "JCB").forEach { b ->
                DropdownMenuItem(
                    text = { Text(b) },
                    onClick = {
                        onChange(b)
                        expanded = false
                    },
                )
            }
        }
    }
}

/** 還元率の数値入力ダイアログ。空/「既定に戻す」で上書きを解除する(null を返す)。 */
@Composable
private fun RateEditDialog(initial: Double, onDismiss: () -> Unit, onConfirm: (Double?) -> Unit) {
    var text by remember { mutableStateOf(trimRate(initial)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("還元率を入力") },
        text = {
            Column {
                Text(
                    "公式アプリに表示される実効還元率(%)を入力してください。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    suffix = { Text("%") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(text.toDoubleOrNull()) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = { onConfirm(null) }) { Text("既定に戻す") } },
    )
}

/**
 * 自治体追加ダイアログ。都道府県選択→市区町村選択の2段ピッカー。
 * 東京都は「23区」「市部」のグループ表示。
 */
@Composable
private fun MunicipalityPickerDialog(
    master: Map<String, List<String>>,
    registered: List<RegisteredMunicipality>,
    onAdd: (RegisteredMunicipality) -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedPrefecture by remember { mutableStateOf<String?>(null) }
    val registeredKeys = remember(registered) {
        registered.map { "${it.prefecture}:${it.name}" }.toSet()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (selectedPrefecture != null) {
                    IconButton(onClick = { selectedPrefecture = null }) {
                        Icon(Icons.Default.Close, contentDescription = "戻る")
                    }
                }
                Text(selectedPrefecture ?: "都道府県を選択")
            }
        },
        text = {
            if (master.isEmpty()) {
                Box(
                    Modifier.fillMaxWidth().height(200.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            } else if (selectedPrefecture == null) {
                LazyColumn(Modifier.fillMaxWidth().height(400.dp)) {
                    val prefectures = master.keys.toList()
                    items(prefectures) { pref ->
                        ListItem(
                            headlineContent = { Text(pref) },
                            modifier = Modifier.clickable { selectedPrefecture = pref },
                        )
                    }
                }
            } else {
                val municipalities = master[selectedPrefecture].orEmpty()
                val isTokyoTo = selectedPrefecture == "東京都"
                LazyColumn(Modifier.fillMaxWidth().height(400.dp)) {
                    if (isTokyoTo) {
                        val wards = municipalities.filter { it.endsWith("区") }
                        val cities = municipalities.filter { !it.endsWith("区") }
                        if (wards.isNotEmpty()) {
                            item {
                                Text(
                                    "23区",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                )
                            }
                            items(wards) { name ->
                                MunicipalityRow(
                                    prefecture = selectedPrefecture!!,
                                    name = name,
                                    alreadyRegistered = "${selectedPrefecture}:$name" in registeredKeys,
                                    onAdd = onAdd,
                                )
                            }
                        }
                        if (cities.isNotEmpty()) {
                            item {
                                Text(
                                    "市部",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                )
                            }
                            items(cities) { name ->
                                MunicipalityRow(
                                    prefecture = selectedPrefecture!!,
                                    name = name,
                                    alreadyRegistered = "${selectedPrefecture}:$name" in registeredKeys,
                                    onAdd = onAdd,
                                )
                            }
                        }
                    } else {
                        items(municipalities) { name ->
                            MunicipalityRow(
                                prefecture = selectedPrefecture!!,
                                name = name,
                                alreadyRegistered = "${selectedPrefecture}:$name" in registeredKeys,
                                onAdd = onAdd,
                            )
                        }
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
private fun MunicipalityRow(
    prefecture: String,
    name: String,
    alreadyRegistered: Boolean,
    onAdd: (RegisteredMunicipality) -> Unit,
) {
    ListItem(
        headlineContent = {
            Text(
                name,
                color = if (alreadyRegistered) MaterialTheme.colorScheme.outline
                else MaterialTheme.colorScheme.onSurface,
            )
        },
        trailingContent = {
            if (alreadyRegistered) {
                Text("登録済み", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
        },
        modifier = Modifier.clickable(enabled = !alreadyRegistered) {
            onAdd(RegisteredMunicipality(prefecture, name))
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThemeModeRow(themeMode: ThemeMode, onChange: (ThemeMode) -> Unit) {
    val options = listOf(
        ThemeMode.SYSTEM to "システム",
        ThemeMode.LIGHT to "ライト",
        ThemeMode.DARK to "ダーク",
    )
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text("テーマ", style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(8.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, (mode, label) ->
                SegmentedButton(
                    selected = themeMode == mode,
                    onClick = { onChange(mode) },
                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                ) { Text(label) }
            }
        }
    }
}

@Composable
private fun SettingsSectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun CommitRefRow(value: String, onChange: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    ListItem(
        headlineContent = { Text("データ取得先 commit") },
        supportingContent = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(40) },
                placeholder = { Text("空欄 = main") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
        },
        trailingContent = {
            TextButton(
                onClick = { onChange(text) },
                enabled = text.trim() != value,
            ) { Text("適用") }
        },
    )
}
