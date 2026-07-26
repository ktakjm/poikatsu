package com.ktakjm.poikatsu.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.mikepenz.aboutlibraries.ui.compose.android.produceLibraries
import com.mikepenz.aboutlibraries.ui.compose.m3.LibrariesContainer
import com.ktakjm.poikatsu.BuildConfig
import com.ktakjm.poikatsu.R
import com.ktakjm.poikatsu.data.ThemeMode
import com.ktakjm.poikatsu.domain.ENDS_SOON_DAYS
import com.ktakjm.poikatsu.ui.theme.onWarningContainerColor
import com.ktakjm.poikatsu.ui.theme.warningContainerColor

/** 通知時刻の設定刻み(分)。保存形式は分単位で、刻みは UI 側の制約として持つ */
private const val NOTIFY_TIME_STEP_MINUTES = 15

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
    notificationSummary: String,
    dataSummary: String,
    developerSummary: String,
    onOpenSubpage: (SettingsSubpage) -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SettingsCategoryRow(SettingsSubpage.DISPLAY, displaySummary, onOpenSubpage)
        SettingsCategoryRow(SettingsSubpage.PAYMENT_METHODS, paymentSummary, onOpenSubpage)
        SettingsCategoryRow(SettingsSubpage.MUNICIPALITIES, municipalitySummary, onOpenSubpage)
        SettingsCategoryRow(SettingsSubpage.NOTIFICATIONS, notificationSummary, onOpenSubpage)
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

// ---- サブページ: 通知 ----

/**
 * 通知サブページ(#6)。キャンペーン通知のトグル・通知時刻(15分刻み)と、何がいつ通知されるかの説明。
 * ON 操作時、Android 13+ は通知パーミッションを要求し、許可されたときだけ有効化する
 * (拒否のまま ON にすると「有効なのに何も来ない」状態になるため)。12 以下は要求不要でそのまま ON。
 */
@Composable
internal fun NotificationSettingsPage(
    enabled: Boolean,
    notifyTimeMinutes: Int,
    onBack: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onTimeChange: (Int) -> Unit,
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    var permissionDenied by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            permissionDenied = false
            onEnabledChange(true)
        } else {
            permissionDenied = true
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ListItem(
            headlineContent = { Text("キャンペーン通知") },
            supportingContent = {
                Text("毎朝${notifyTimeLabel(notifyTimeMinutes)}、その日のお知らせがあるときだけまとめて通知します")
            },
            trailingContent = {
                Switch(
                    checked = enabled,
                    onCheckedChange = { on ->
                        when {
                            !on -> onEnabledChange(false)
                            Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
                                context, Manifest.permission.POST_NOTIFICATIONS,
                            ) == PackageManager.PERMISSION_GRANTED -> onEnabledChange(true)
                            else -> permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    },
                )
            },
        )
        ListItem(
            headlineContent = { Text("通知時刻") },
            // 「頃」の理由をここで説明する(WorkManager の省電力制約で厳密な時刻にならない)
            supportingContent = { Text("省電力の状況により数分ずれることがあります") },
            trailingContent = { Text(notifyTimeLabel(notifyTimeMinutes), style = MaterialTheme.typography.bodyLarge) },
            modifier = Modifier.clickable { showTimePicker = true },
        )
        if (permissionDenied && !enabled) {
            // 拒否直後の案内。2回拒否済みだと要求ダイアログ自体が出なくなるため、端末設定への導線を文言で示す
            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                NoticeRow(
                    "通知が許可されていません。端末の設定でこのアプリの通知を許可してください",
                    containerColor = warningContainerColor(),
                    contentColor = onWarningContainerColor(),
                )
            }
        }
        // 通知内容の説明。選べる項目ではないので ListItem(=操作できる行の見た目)にせず、
        // トグル下の説明段落として出す(Android のシステム設定の footer 説明と同じ扱い)
        SettingsSectionHeader("通知の内容")
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "次のキャンペーンについて、開始日と終了間近(終了${ENDS_SOON_DAYS}日前から)をお知らせします。\n" +
                    "・マイエリアの自治体キャンペーン\n" +
                    "・お支払い方法に登録したカード・国際ブランド・コード決済の期間限定キャンペーン\n" +
                    "・自分で登録したキャンペーン",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "予算到達しだい早く終わる施策は、終了日が確定していないため終了間近をお知らせできないことがあります。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
    if (showTimePicker) {
        NotifyTimePickerDialog(
            initialMinutes = notifyTimeMinutes,
            onConfirm = { minutes ->
                onTimeChange(minutes)
                showTimePicker = false
            },
            onDismiss = { showTimePicker = false },
        )
    }
}

/**
 * 通知時刻のピッカー(時 0〜23・分 15刻み)。M3 の TimePicker は分の刻みを指定できないため、
 * 時・分それぞれのドロップダウンで択一にする(「頃」の精度に分単位の入力は過剰)。
 */
@Composable
private fun NotifyTimePickerDialog(
    initialMinutes: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var hour by remember { mutableStateOf(initialMinutes / 60) }
    // 既存値が15分刻みでない場合(将来の保存形式変更等)も選択肢に丸めて表示する
    var minute by remember { mutableStateOf(initialMinutes % 60 / NOTIFY_TIME_STEP_MINUTES * NOTIFY_TIME_STEP_MINUTES) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("通知時刻") },
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TimePartPicker(
                    value = hour,
                    options = (0..23).toList(),
                    format = { it.toString() },
                    onSelect = { hour = it },
                )
                Text(":", style = MaterialTheme.typography.titleLarge)
                TimePartPicker(
                    value = minute,
                    options = (0 until 60 step NOTIFY_TIME_STEP_MINUTES).toList(),
                    format = { "%02d".format(it) },
                    onSelect = { minute = it },
                )
                Text("頃", style = MaterialTheme.typography.bodyLarge)
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(hour * 60 + minute) }) { Text("OK") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("キャンセル") }
        },
    )
}

/** 時刻の一部(時または分)のドロップダウン択一 */
@Composable
private fun TimePartPicker(
    value: Int,
    options: List<Int>,
    format: (Int) -> String,
    onSelect: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) {
            Text(format(value), style = MaterialTheme.typography.titleLarge)
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(format(option)) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                )
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

/** このアプリサブページ。バージョン・ソースコードリンク・OSS ライセンス表示(#48)への導線 */
@Composable
internal fun AboutSettingsPage(onBack: () -> Unit, onOpenLicenses: () -> Unit) {
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
        ListItem(
            headlineContent = { Text("オープンソースライセンス") },
            trailingContent = {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
            },
            modifier = Modifier.clickable(onClick = onOpenLicenses),
        )
    }
}

/**
 * オープンソースライセンス一覧(#48)。「このアプリ」配下の 2 階層目サブページ。
 * 一覧はビルド時に AboutLibraries プラグインが Gradle 依存から自動生成した
 * R.raw.aboutlibraries を表示する(依存の増減に自動追従)。Gradle 依存でない同梱コード
 * (AppIcons.kt の material-design-icons)は app/config/libraries/ のカスタム定義で載せる。
 */
@Composable
internal fun LicensesPage(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val libraries by produceLibraries(R.raw.aboutlibraries)
    LibrariesContainer(libraries, Modifier.fillMaxSize())
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
