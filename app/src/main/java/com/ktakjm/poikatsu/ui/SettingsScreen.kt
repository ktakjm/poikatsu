package com.ktakjm.poikatsu.ui

import android.Manifest
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.mikepenz.aboutlibraries.ui.compose.android.produceLibraries
import com.mikepenz.aboutlibraries.ui.compose.m3.LibrariesContainer
import com.ktakjm.poikatsu.BuildConfig
import com.ktakjm.poikatsu.R
import com.ktakjm.poikatsu.data.ExcludedStorePair
import com.ktakjm.poikatsu.data.ThemeMode
import com.ktakjm.poikatsu.domain.ENDS_SOON_DAYS
import com.ktakjm.poikatsu.ui.theme.onWarningContainerColor
import com.ktakjm.poikatsu.ui.theme.warningColor
import com.ktakjm.poikatsu.ui.theme.warningContainerColor

/**
 * 通知時刻の設定刻み(分)。保存形式は分単位で、刻みは UI 側の制約として持つ。
 * 実機検証で発火を待つのに細かい刻みは要らない(開発者向けの「テスト通知」で即時に確認する)。
 */
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
    excludedStoresSummary: String,
    developerSummary: String,
    onOpenSubpage: (SettingsSubpage) -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SettingsCategoryRow(SettingsSubpage.DISPLAY, displaySummary, onOpenSubpage)
        SettingsCategoryRow(SettingsSubpage.PAYMENT_METHODS, paymentSummary, onOpenSubpage)
        SettingsCategoryRow(SettingsSubpage.MUNICIPALITIES, municipalitySummary, onOpenSubpage)
        SettingsCategoryRow(SettingsSubpage.NOTIFICATIONS, notificationSummary, onOpenSubpage)
        SettingsCategoryRow(SettingsSubpage.DATA, dataSummary, onOpenSubpage)
        SettingsCategoryRow(SettingsSubpage.EXCLUDED_STORES, excludedStoresSummary, onOpenSubpage)
        // バックアップは状態を持たない操作の入口なので、サマリは現在値でなく用途を出す
        SettingsCategoryRow(
            SettingsSubpage.BACKUP,
            "機種変更・再インストールに備えて設定をファイルに保存",
            onOpenSubpage,
        )
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
 * すでに ON で権限だけ失われている状態(端末設定での取り消し・Auto Backup での復元)も検出して
 * 警告を出す。権限は画面外で変わるため ON_RESUME のたびに読み直す。警告面には「許可する」ボタンを
 * 置いてその場で権限要求まで完結させ(要求ダイアログを出せない状態なら端末の通知設定へ送る)、
 * 文言だけの案内で終わらせない。
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
    // 権限はこの画面の外でも変わる(端末設定での取り消し、Auto Backup での復元後に未許可のまま等)ので、
    // 復帰のたびに読み直して「設定は ON なのに通知が来ない」状態を検出できるようにする
    var permissionGranted by remember { mutableStateOf(notificationPermissionGranted(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permissionGranted = notificationPermissionGranted(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        permissionGranted = granted
        permissionDenied = !granted
        when {
            // ON にしようとして拒否 → 許可された時点で ON にする。すでに ON(復元・権限取消)の
            // ときは設定を書き直さない(通知ジョブが翌日へ再アンカーされるのを避ける)
            granted && !enabled -> onEnabledChange(true)
            // 拒否。2回拒否済みだとシステムがダイアログを出さず即 false が返る(rationale も false)
            // ので、その場合だけ端末の通知設定へ送る。1回目の拒否は本人の意思なので何もしない
            !granted && context.findActivity()?.let {
                ActivityCompat.shouldShowRequestPermissionRationale(
                    it,
                    Manifest.permission.POST_NOTIFICATIONS,
                )
            } != true -> openAppNotificationSettings(context)
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ListItem(
            headlineContent = { Text("キャンペーン通知") },
            supportingContent = {
                Text("毎日${notifyTimeLabel(notifyTimeMinutes)}、その日のお知らせがあるときだけまとめて通知します")
            },
            trailingContent = {
                Switch(
                    checked = enabled,
                    onCheckedChange = { on ->
                        when {
                            !on -> onEnabledChange(false)
                            notificationPermissionGranted(context) -> onEnabledChange(true)
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
        // 通知が届かない状態の 2 パターン。どちらも「許可する」ボタンでその場から権限要求まで
        // 完結させる(要求ダイアログを出せない状態なら端末の通知設定へ送る)。
        // (1) ON にしようとして拒否した直後(設定は OFF のまま)
        // (2) 設定は ON なのに権限が無い: 端末設定での取り消しや、Auto Backup での復元(権限付与は
        //     アプリデータと別枠で OS が扱うため揃うとは限らない)で起こる。放置すると「オンなのに来ない」
        //     ことに気づけない。JSON 復元(#50)は復元時に権限を要求するのでこの状態を作らない
        val permissionNotice = when {
            enabled && !permissionGranted ->
                "通知はオンですが、通知が許可されていません。許可するまで通知は届きません"
            permissionDenied && !enabled ->
                "通知が許可されていません。許可すると通知を受け取れます"
            else -> null
        }
        if (permissionNotice != null) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                NoticeRow(
                    permissionNotice,
                    containerColor = warningContainerColor(),
                    contentColor = onWarningContainerColor(),
                    actionLabel = "許可する",
                    onAction = { permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
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
                "予算到達しだい早く終わるキャンペーンは、終了日が確定していないため終了間近をお知らせできないことがあります。",
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
    // 既存値が刻みに乗っていない場合(刻みの変更・バックアップ復元等)も選択肢に丸めて表示する
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

// ---- サブページ: 対象外に登録したお店 ----

/**
 * 「対象外のお店として登録」(#63)の管理一覧。判定詳細から登録した (施策, 店舗) ペアを
 * 一覧し、個別削除と「終了したキャンペーン」(データから消えた or 期間終了=どちらも今の判定では
 * 参照されない登録)の一括削除を提供する。終了した登録も自動では消さない(データの一時的な
 * 取得失敗で消えると困る・期間終了は同じ id で更新され得るため、削除は明示操作に限る)。
 * この画面から登録はできない(登録は店舗を特定できる判定詳細のみ)。
 */
@Composable
internal fun ExcludedStoresSettingsPage(
    pairs: List<ExcludedStorePair>,
    campaignNames: Map<String, String>,
    expiredCampaignIds: Set<String>,
    merchantNames: Map<String, String>,
    onBack: () -> Unit,
    onRemove: (ExcludedStorePair) -> Unit,
    onRemoveStale: () -> Unit,
) {
    BackHandler(onBack = onBack)
    // 削除の確認ダイアログを出している対象(登録を戻す手段が判定詳細の再登録しかないため確認を挟む)
    var deletingPair by remember { mutableStateOf<ExcludedStorePair?>(null) }
    var confirmingStaleRemoval by remember { mutableStateOf(false) }
    // 終了扱い = データから消えた or 期間終了。ViewModel の一括削除(onRemoveStale)と同じ基準
    fun isEnded(pair: ExcludedStorePair): Boolean =
        pair.campaignId !in campaignNames || pair.campaignId in expiredCampaignIds
    val staleCount = pairs.count(::isEnded)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text(
            if (pairs.isEmpty()) {
                "登録はありません。お店・地図タブの判定詳細にある「対象外のお店として登録」から登録すると、" +
                    "そのお店をそのキャンペーンの判定・地図表示から除外できます。"
            } else {
                "名前が一致するお店は、そのキャンペーンの判定・地図表示から除外されます。" +
                    "削除すると、そのお店は再びキャンペーンの対象として扱われます。"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        if (staleCount > 0) {
            TextButton(
                onClick = { confirmingStaleRemoval = true },
                modifier = Modifier.padding(horizontal = 8.dp),
            ) {
                Text("終了したキャンペーンの登録をまとめて削除(${staleCount}件)")
            }
        }
        pairs.forEach { pair ->
            val campaignName = campaignNames[pair.campaignId]
            ListItem(
                headlineContent = { Text(pair.storeName) },
                supportingContent = {
                    Column {
                        if (campaignName != null) {
                            Text(campaignName)
                        }
                        if (isEnded(pair)) {
                            // 施策がデータから消えた(期限切れ削除・改定)か期間終了した登録。
                            // 実害はないが使われていないことを知らせ、上の一括削除へ誘導する
                            Text("キャンペーン終了(この登録は使われていません)", color = warningColor())
                        }
                        val merchant = merchantNames[pair.merchantId] ?: pair.merchantId
                        Text(
                            if (pair.registeredDate.isBlank()) merchant
                            else "$merchant・登録日 ${pair.registeredDate}",
                        )
                    }
                },
                trailingContent = {
                    IconButton(onClick = { deletingPair = pair }) {
                        Icon(Icons.Default.Close, contentDescription = "この登録を削除")
                    }
                },
            )
        }
        Spacer(Modifier.height(24.dp))
    }
    deletingPair?.let { pair ->
        AlertDialog(
            onDismissRequest = { deletingPair = null },
            title = { Text("登録を削除") },
            text = {
                val campaignName = campaignNames[pair.campaignId]
                Text(
                    if (!isEnded(pair) && campaignName != null) {
                        "「${pair.storeName}」の登録を削除しますか？ 削除すると、このお店は「$campaignName」の対象として扱われます。"
                    } else {
                        "「${pair.storeName}」の登録を削除しますか？ キャンペーンは終了しているため、削除しても判定は変わりません。"
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRemove(pair)
                        deletingPair = null
                    },
                ) { Text("削除") }
            },
            dismissButton = {
                TextButton(onClick = { deletingPair = null }) { Text("キャンセル") }
            },
        )
    }
    if (confirmingStaleRemoval) {
        AlertDialog(
            onDismissRequest = { confirmingStaleRemoval = false },
            title = { Text("まとめて削除") },
            text = {
                Text(
                    "終了したキャンペーンの登録${staleCount}件を削除しますか？ " +
                        "キャンペーンは終了しているため、削除しても判定は変わりません。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRemoveStale()
                        confirmingStaleRemoval = false
                    },
                ) { Text("削除") }
            },
            dismissButton = {
                TextButton(onClick = { confirmingStaleRemoval = false }) { Text("キャンセル") }
            },
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
