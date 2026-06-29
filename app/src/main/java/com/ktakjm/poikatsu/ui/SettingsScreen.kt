package com.ktakjm.poikatsu.ui

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.ktakjm.poikatsu.BuildConfig
import com.ktakjm.poikatsu.data.ThemeMode
import com.ktakjm.poikatsu.ui.theme.warningColor

/**
 * 設定画面(4 番目のタブ)。ListItem は端まで使うため PaddedColumn を介さず直接置く。
 * 表示/マイカード/データ/このアプリの 4 セクション。
 * 値は DataStore 由来(MainViewModel 経由)で、変更は即 ViewModel の setter へ流す。
 */
@Composable
internal fun SettingsScreen(
    themeMode: ThemeMode,
    dynamicColor: Boolean,
    autoRefresh: Boolean,
    cards: List<MainViewModel.CardSetting>,
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
    onRefresh: () -> Unit,
    onDataCommitRefChange: (String) -> Unit,
) {
    val uriHandler = LocalUriHandler.current
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
