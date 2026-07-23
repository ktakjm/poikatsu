package com.ktakjm.poikatsu.ui

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.ktakjm.poikatsu.BuildConfig
import com.ktakjm.poikatsu.data.ThemeMode

/**
 * 設定画面(4 番目のタブ)のトップページ。カテゴリ行(表示/お支払い方法/マイエリア/
 * キャンペーンデータ/開発者向け/このアプリ)のみを置き、項目本体は各サブページ
 * ([SettingsSubpage]。設定タブ上のオーバーレイ+戻る)へ移す(#47)。各行には畳んだ現在値のサマリ(UiHelpers の純関数で生成)を出し、
 * 遷移せずに状態を一望できるようにする。
 */
@Composable
internal fun SettingsScreen(
    displaySummary: String,
    paymentSummary: String,
    municipalitySummary: String,
    dataSummary: String,
    developerSummary: String,
    onOpenSubpage: (SettingsSubpage) -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SettingsCategoryRow(SettingsSubpage.DISPLAY, displaySummary, onOpenSubpage)
        SettingsCategoryRow(SettingsSubpage.PAYMENT_METHODS, paymentSummary, onOpenSubpage)
        SettingsCategoryRow(SettingsSubpage.MUNICIPALITIES, municipalitySummary, onOpenSubpage)
        SettingsCategoryRow(SettingsSubpage.DATA, dataSummary, onOpenSubpage)
        SettingsCategoryRow(SettingsSubpage.DEVELOPER, developerSummary, onOpenSubpage)
        SettingsCategoryRow(
            SettingsSubpage.ABOUT,
            "バージョン ${BuildConfig.VERSION_NAME}",
            onOpenSubpage,
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SettingsCategoryRow(
    page: SettingsSubpage,
    summary: String,
    onOpen: (SettingsSubpage) -> Unit,
) {
    ListItem(
        headlineContent = { Text(page.title) },
        supportingContent = { Text(summary) },
        trailingContent = {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
        },
        modifier = Modifier.clickable { onOpen(page) },
    )
}

// ---- サブページ: 表示 ----

/** 表示サブページ。テーマ(セグメントボタン)と dynamic color の切替 */
@Composable
internal fun DisplaySettingsPage(
    themeMode: ThemeMode,
    dynamicColor: Boolean,
    onBack: () -> Unit,
    onThemeModeChange: (ThemeMode) -> Unit,
    onDynamicColorChange: (Boolean) -> Unit,
) {
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
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
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThemeModeRow(themeMode: ThemeMode, onChange: (ThemeMode) -> Unit) {
    val options = listOf(ThemeMode.SYSTEM, ThemeMode.LIGHT, ThemeMode.DARK)
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text("テーマ", style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(8.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, mode ->
                SegmentedButton(
                    selected = themeMode == mode,
                    onClick = { onChange(mode) },
                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                ) { Text(themeModeLabel(mode)) }
            }
        }
    }
}

// ---- サブページ: データ ----

/** データサブページ。データの状態・自動更新・手動更新 */
@Composable
internal fun DataSettingsPage(
    dataStatus: String,
    autoRefresh: Boolean,
    refreshing: Boolean,
    useBundledData: Boolean,
    onBack: () -> Unit,
    onAutoRefreshChange: (Boolean) -> Unit,
    onRefresh: () -> Unit,
) {
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
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
    }
}

// ---- サブページ: このアプリ ----

/** このアプリサブページ。バージョン・ソースコードリンク(OSS ライセンス表示 #48 もここに載せる予定) */
@Composable
internal fun AboutSettingsPage(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val uriHandler = LocalUriHandler.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
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
    }
}

/** サブページ内のセクション見出し(お支払い方法サブページの マイカード/国際ブランド/コード決済 等) */
@Composable
internal fun SettingsSectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}
