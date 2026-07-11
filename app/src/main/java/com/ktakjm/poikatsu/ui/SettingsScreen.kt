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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
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
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.ktakjm.poikatsu.BuildConfig
import com.ktakjm.poikatsu.data.MunicipalityMaster
import com.ktakjm.poikatsu.data.Prefecture
import com.ktakjm.poikatsu.data.RegisteredArea
import com.ktakjm.poikatsu.data.RegisteredAreaType
import com.ktakjm.poikatsu.data.ThemeMode
import com.ktakjm.poikatsu.domain.trimRate
import com.ktakjm.poikatsu.ui.theme.warningColor

/**
 * 設定画面(4 番目のタブ)。ListItem は端まで使うため PaddedColumn を介さず直接置く。
 * 表示/マイカード/QR決済/自治体/データ/開発者向け/このアプリのセクション。
 * 値は DataStore 由来(MainViewModel 経由)で、変更は即 ViewModel の setter へ流す。
 * 開発者向け設定の項目本体は別画面([DeveloperSettingsScreen])に置き、ここは開発者モードの
 * トグルと導線のみ(OFF 操作は確認ダイアログを経て一括リセット)。
 */
@Composable
internal fun SettingsScreen(
    themeMode: ThemeMode,
    dynamicColor: Boolean,
    autoRefresh: Boolean,
    cards: List<MainViewModel.CardSetting>,
    brands: List<MainViewModel.BrandSetting>,
    qrPayments: List<MainViewModel.QrPaymentSetting>,
    registeredAreas: List<RegisteredArea>,
    municipalityMaster: MunicipalityMaster,
    dataStatus: String,
    refreshing: Boolean,
    useBundledData: Boolean,
    developerMode: Boolean,
    /** 「開発者向け設定」行に出す非既定値のサマリ([developerSettingsSummary]) */
    developerSummary: String,
    onThemeModeChange: (ThemeMode) -> Unit,
    onDynamicColorChange: (Boolean) -> Unit,
    onAutoRefreshChange: (Boolean) -> Unit,
    onCardOwnedChange: (String, Boolean) -> Unit,
    onCardRateChange: (String, Double?) -> Unit,
    onCardBrandChange: (String, String) -> Unit,
    onCardWelcatsuChange: (String, Boolean) -> Unit,
    onBrandOwnedChange: (String, Boolean) -> Unit,
    onQrEnabledChange: (String, Boolean) -> Unit,
    onAddRegisteredArea: (RegisteredArea) -> Unit,
    onRemoveRegisteredArea: (RegisteredArea) -> Unit,
    onRefresh: () -> Unit,
    onDeveloperModeChange: (Boolean) -> Unit,
    onOpenDeveloperSettings: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    var showMunicipalityPicker by remember { mutableStateOf(false) }

    // 開発者モードの切替確認ダイアログ。非 null なら表示中で、値が切替先(true=オン/false=オフ)。
    // ON は想定外挙動の注意、OFF は一括リセットの注意と、どちらの方向も確認を挟む
    var developerModeDialogTarget by remember { mutableStateOf<Boolean?>(null) }
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
                onOwnedChange = { onCardOwnedChange(card.cardId, it) },
                onRateChange = { onCardRateChange(card.cardId, it) },
                onBrandChange = { onCardBrandChange(card.cardId, it) },
                onWelcatsuChange = { onCardWelcatsuChange(card.cardId, it) },
            )
        }

        // --- カードブランド(イシュアー不問のブランド施策向け。事前登録できるよう常時出す) ---
        if (brands.isNotEmpty()) {
            SettingsSectionHeader("カードブランド")
            Text(
                "上のカード以外で持っているブランドにチェックを入れてください。カード会社を問わないブランド対象キャンペーン(Visa割など)が始まると、開始と同時に判定へ表示されます。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            brands.forEach { b ->
                ListItem(
                    headlineContent = { Text(b.brand) },
                    leadingContent = {
                        Checkbox(
                            checked = b.owned,
                            onCheckedChange = { onBrandOwnedChange(b.brand, it) },
                        )
                    },
                    modifier = Modifier.clickable { onBrandOwnedChange(b.brand, !b.owned) },
                )
            }
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
            "居住地・行動圏の自治体を登録すると、期間限定タブが登録地域の施策に絞られます。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        registeredAreas.forEach { area ->
            ListItem(
                headlineContent = { Text(area.name) },
                supportingContent = {
                    Text(
                        if (area.type == RegisteredAreaType.GROUP) "${area.prefecture}・グループ"
                        else area.prefecture
                    )
                },
                trailingContent = {
                    IconButton(onClick = { onRemoveRegisteredArea(area) }) {
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
            modifier = Modifier.clickable { showMunicipalityPicker = true },
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
        // 同梱モード中はリモート取得を止めるため手動更新もグレーアウトする(無言 no-op にしない)
        val disabledColors = ListItemDefaults.colors(
            headlineColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            supportingColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            trailingIconColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
        )
        ListItem(
            headlineContent = { Text("今すぐ更新") },
            supportingContent = if (useBundledData) {
                { Text("同梱データ使用中は更新できません") }
            } else {
                null
            },
            trailingContent = {
                if (refreshing) {
                    CircularProgressIndicator(Modifier.size(24.dp))
                } else {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                }
            },
            colors = if (useBundledData) disabledColors else ListItemDefaults.colors(),
            modifier = Modifier.clickable(enabled = !refreshing && !useBundledData, onClick = onRefresh),
        )

        // --- 開発者向け ---
        SettingsSectionHeader("開発者向け")
        ListItem(
            headlineContent = { Text("開発者モード") },
            supportingContent = { Text("開発・検証用の設定を表示します") },
            trailingContent = {
                Switch(
                    checked = developerMode,
                    onCheckedChange = { enabled -> developerModeDialogTarget = enabled },
                )
            },
        )
        if (developerMode) {
            ListItem(
                headlineContent = { Text("開発者向け設定") },
                supportingContent = { Text(developerSummary) },
                trailingContent = {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                },
                modifier = Modifier.clickable(onClick = onOpenDeveloperSettings),
            )
        }

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
            registered = registeredAreas,
            onAdd = onAddRegisteredArea,
            onRemove = onRemoveRegisteredArea,
            onDismiss = { showMunicipalityPicker = false },
        )
    }

    developerModeDialogTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { developerModeDialogTarget = null },
            title = { Text(if (target) "開発者モードをオンにしますか？" else "開発者モードをオフにしますか？") },
            text = {
                Text(
                    if (target) {
                        "開発者モードは開発者専用です。テストデータの表示など、アプリが通常と異なる想定外の動作になることがあります。それでもオンにしますか？"
                    } else {
                        "開発者向け設定(テストデータ・同梱データ・データ取得先 commit)はすべて既定値に戻ります。"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onDeveloperModeChange(target)
                    developerModeDialogTarget = null
                }) { Text(if (target) "オンにする" else "オフにする") }
            },
            dismissButton = {
                TextButton(onClick = { developerModeDialogTarget = null }) { Text("キャンセル") }
            },
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
    var showBrandRequiredDialog by remember { mutableStateOf(false) }
    // ブランドが判定に効くカード(showBrandPicker)は、未選択のまま有効化すると Amex 除外等が
    // 発動せず過剰表示になり得るため、有効化時にブランド選択を必須にする(選択せず閉じたら有効化しない)
    val requestOwnedChange: (Boolean) -> Unit = { owned ->
        if (owned && card.showBrandPicker && card.brand.isBlank()) {
            showBrandRequiredDialog = true
        } else {
            onOwnedChange(owned)
        }
    }
    // タイトルにブランドは出さない。ブランドが判定に効くカード(showBrandPicker)だけ下のブランド行で表示・変更する
    // (施策がブランド不問のカードに「（Visa）」等を出すと、そのブランド限定と誤読させるため)
    ListItem(
        headlineContent = { Text(card.cardName) },
        leadingContent = { Checkbox(checked = card.owned, onCheckedChange = requestOwnedChange) },
        supportingContent = { Text(if (card.owned) "持っている" else "持っていない") },
        modifier = Modifier.clickable { requestOwnedChange(!card.owned) },
    )
    if (card.owned) {
        if (card.showBrandPicker) {
            ListItem(
                headlineContent = { Text("ブランド") },
                trailingContent = {
                    BrandDropdown(brand = card.brand, options = card.brands, onChange = onBrandChange)
                },
                modifier = Modifier.padding(start = 24.dp),
            )
            if (card.brand.isBlank()) {
                Text(
                    "ブランド未選択のため、Amex で優遇対象外になり得る店舗は対象外として扱われます。お持ちのブランドを選ぶと正確に判定されます",
                    style = MaterialTheme.typography.bodySmall,
                    color = warningColor(),
                    modifier = Modifier.padding(start = 24.dp, end = 16.dp, bottom = 8.dp),
                )
            }
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
    if (showBrandRequiredDialog) {
        BrandRequiredDialog(
            cardName = card.cardName,
            options = card.brands,
            onSelect = { brand ->
                onBrandChange(brand)
                onOwnedChange(true)
                showBrandRequiredDialog = false
            },
            onDismiss = { showBrandRequiredDialog = false },
        )
    }
}

/** ブランド選択。選択肢はカタログ(payment_methods.json の brands)から出す。 */
@Composable
private fun BrandDropdown(brand: String, options: List<String>, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) {
            Text(brand.ifBlank { "選択" })
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { b ->
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

/** ブランドが判定に効くカードを有効化するとき、実ブランドの選択を求めるダイアログ。 */
@Composable
private fun BrandRequiredDialog(
    cardName: String,
    options: List<String>,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("ブランドを選択") },
        text = {
            Column {
                Text(
                    "${cardName}はブランドによって判定が変わります。お持ちのカードのブランドを選んでください。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                options.forEach { b ->
                    ListItem(
                        headlineContent = { Text(b) },
                        modifier = Modifier.clickable { onSelect(b) },
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
    )
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
 * 自治体追加ダイアログ。都道府県選択→「グループ」+「市区町村」の2段ピッカー。
 * グループ(東京23区・埼玉県南部 等)はマスタ(municipalities.json)由来で、
 * 並び順もマスタのまま出す(補完グループ→一次細分→その配下の細分)。
 * 行はチェックボックスのトグルで登録/解除が即時反映される(他の設定項目と同じ即時適用。
 * 「登録済み=操作不能」にしないのは、押し間違いをその場で取り消せるようにするため)。
 * グループ行は ▼ で構成自治体名を展開できる(「23区西部」がどこまでか行タップなしで確認できる)。
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
                Text(selectedPrefecture?.name ?: "都道府県を選択")
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
                LazyColumn(Modifier.fillMaxWidth().height(400.dp)) {
                    items(master.prefectures) { pref ->
                        ListItem(
                            headlineContent = { Text(pref.name) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.clickable { selectedPrefecture = pref },
                        )
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
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.toggleable(
            value = checked,
            role = Role.Checkbox,
            onValueChange = { onToggle(area) },
        ),
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
