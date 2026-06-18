package com.ktakjm.poikatsu.ui

import android.Manifest
import android.os.Build
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberStandardBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.KeyboardType
import androidx.core.content.ContextCompat
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ktakjm.poikatsu.BuildConfig
import com.ktakjm.poikatsu.data.DataSource
import com.ktakjm.poikatsu.data.Merchant
import com.ktakjm.poikatsu.data.ThemeMode
import com.ktakjm.poikatsu.domain.Judgment
import com.ktakjm.poikatsu.domain.StoreEligibility
import com.ktakjm.poikatsu.domain.StoreVerdict
import com.ktakjm.poikatsu.ui.theme.warningColor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PoikatsuApp(viewModel: MainViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()

    // フォアグラウンド復帰のたびにリモートデータの再取得を試みる(初回起動時のON_START含む)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) viewModel.onAppForeground()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.any { it }) viewModel.fetchNearby() else viewModel.onLocationDenied()
    }
    val onNearbyClick = {
        val granted = listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ).any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
        if (granted) {
            viewModel.fetchNearby()
        } else {
            permissionLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
        }
    }

    // 一時的な失敗(再取得失敗)は画面に残さず Snackbar で通知し、表示後にフラグを消費する
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(state.refreshFailed) {
        if (state.refreshFailed) {
            snackbarHostState.showSnackbar("再取得できませんでした。通信状態を確認して再度お試しください。")
            viewModel.onRefreshFailedShown()
        }
    }

    Scaffold(
        // TopAppBar は表示中の画面に追従させる。分岐順は下の本文(when)と必ず一致させること
        topBar = {
            when {
                state.loading -> Unit
                state.error != null -> Unit
                state.showSettings -> TopAppBar(
                    title = { Text("設定") },
                    navigationIcon = {
                        IconButton(onClick = viewModel::onCloseSettings) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                        }
                    },
                )
                state.storeCheck != null -> TopAppBar(
                    title = { Text("${state.storeCheck!!.merchant.name} 店舗判定") },
                    navigationIcon = {
                        IconButton(onClick = viewModel::onCloseStoreCheck) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                        }
                    },
                )
                state.selection != null -> TopAppBar(
                    title = { Text(state.selection!!.displayName ?: state.selection!!.merchant.name) },
                    navigationIcon = {
                        IconButton(onClick = viewModel::onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                        }
                    },
                )
                // 地図画面は NearbyPane 内の BottomSheetScaffold が独自バーを持つので外側は出さない
                state.nearby != null -> Unit
                else -> TopAppBar(
                    title = { Text("対象チェーン店") },
                    actions = {
                        IconButton(onClick = viewModel::onOpenSettings) {
                            Icon(Icons.Default.Settings, contentDescription = "設定")
                        }
                    },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        // モード切替は下部ナビ。詳細・店舗判定などの下位画面に重なっている間は出さない
        // (= ベースの2タブ表示時のみ)。nearby != null が「近くタブ選択中」を兼ねる
        bottomBar = {
            if (!state.loading && state.error == null && state.selection == null && state.storeCheck == null && !state.showSettings) {
                // 標準 NavigationBar の内容高は 80dp。下部が厚いので 64dp に詰める。
                // システムバー inset は内部で消費されるぶんを足し戻し、安全領域を確保する
                val barInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                NavigationBar(modifier = Modifier.height(64.dp + barInset)) {
                    NavigationBarItem(
                        selected = state.nearby == null,
                        onClick = { if (state.nearby != null) viewModel.onCloseNearby() },
                        icon = { Icon(Icons.Default.Search, contentDescription = null) },
                        label = { Text("探す") },
                    )
                    NavigationBarItem(
                        selected = state.nearby != null,
                        onClick = { if (state.nearby == null) onNearbyClick() },
                        icon = { Icon(Icons.Default.Place, contentDescription = null) },
                        label = { Text("近く") },
                    )
                }
            }
        },
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding)) {
            when {
                state.loading -> Centered { CircularProgressIndicator() }
                state.error != null -> Centered { Text(state.error!!, color = MaterialTheme.colorScheme.error) }
                state.showSettings -> SettingsScreen(
                    themeMode = state.themeMode,
                    dynamicColor = state.dynamicColor,
                    autoRefresh = state.autoRefresh,
                    cards = state.cardSettings,
                    dataStatus = dataStatusLabel(state.dataUpdatedAt, state.dataSource),
                    refreshing = state.refreshing,
                    onThemeModeChange = viewModel::onSetThemeMode,
                    onDynamicColorChange = viewModel::onSetDynamicColor,
                    onAutoRefreshChange = viewModel::onSetAutoRefresh,
                    onCardOwnedChange = viewModel::onSetCardOwned,
                    onCardRateChange = viewModel::onSetCardRate,
                    onCardBrandChange = viewModel::onSetCardBrand,
                    onCardWelcatsuChange = viewModel::onSetCardWelcatsu,
                    onRefresh = viewModel::onManualRefresh,
                    onBack = viewModel::onCloseSettings,
                )
                state.storeCheck != null -> PaddedColumn {
                    StoreCheckScreen(
                        storeCheck = state.storeCheck!!,
                        onBack = viewModel::onCloseStoreCheck,
                        onStoreNameChange = viewModel::onStoreNameChange,
                    )
                }
                state.selection != null -> PaddedColumn {
                    JudgmentDetail(
                        selection = state.selection!!,
                        onBack = viewModel::onBack,
                        onOpenStoreCheck = viewModel::onOpenStoreCheck,
                    )
                }
                // 地図画面はボトムシート・地図を端まで使うため全幅。横paddingは内部で個別に当てる
                state.nearby != null -> NearbyPane(
                    nearby = state.nearby!!,
                    radiusM = state.nearbyRadiusM,
                    onClose = viewModel::onCloseNearby,
                    onReload = viewModel::fetchNearby,
                    onOpenSettings = viewModel::onOpenSettings,
                    onRadiusChange = viewModel::onNearbyRadiusChange,
                    onPreviewPlace = viewModel::onPreviewNearby,
                    onClearPreview = viewModel::onClearNearbyPreview,
                    onOpenDetail = viewModel::onSelectNearby,
                    onSearchHere = viewModel::searchHere,
                )
                else -> PaddedColumn {
                    SearchPane(
                        query = state.query,
                        categories = state.categories,
                        selectedCategories = state.selectedCategories,
                        results = state.results,
                        dataStatus = dataStatusLabel(state.dataUpdatedAt, state.dataSource),
                        refreshing = state.refreshing,
                        onQueryChange = viewModel::onQueryChange,
                        onToggleCategory = viewModel::onToggleCategory,
                        onSelect = viewModel::onSelect,
                        onRefresh = viewModel::onManualRefresh,
                    )
                }
            }
        }
    }
}

/** 地図以外の画面共通の縦並びコンテナ。従来ルートにあった横16dpパディングをここに移譲 */
@Composable
private fun PaddedColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        content = content,
    )
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

/**
 * 設定画面(探す/近く 両モードの TopAppBar 右肩の歯車から開く全画面オーバーレイ)。
 * ListItem は端まで使うため PaddedColumn を介さず直接置く。表示/マイカード/データ/このアプリの
 * 4 セクション。値は DataStore 由来(MainViewModel 経由)で、変更は即 ViewModel の setter へ流す。
 */
@Composable
private fun SettingsScreen(
    themeMode: ThemeMode,
    dynamicColor: Boolean,
    autoRefresh: Boolean,
    cards: List<MainViewModel.CardSetting>,
    dataStatus: String,
    refreshing: Boolean,
    onThemeModeChange: (ThemeMode) -> Unit,
    onDynamicColorChange: (Boolean) -> Unit,
    onAutoRefreshChange: (Boolean) -> Unit,
    onCardOwnedChange: (String, Boolean) -> Unit,
    onCardRateChange: (String, Double?) -> Unit,
    onCardBrandChange: (String, String) -> Unit,
    onCardWelcatsuChange: (String, Boolean) -> Unit,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
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

/** 非インタラクティブなカテゴリ表示。押せる見た目(Chip)を持たせない静的タグ */
@Composable
private fun CategoryTag(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = RoundedCornerShape(8.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

/** 警告・注意の一行表示(アイコン + 文)。色で error(致命) / warning(注意) を出し分ける */
@Composable
private fun NoticeRow(text: String, color: Color) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            Icons.Default.Warning,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(18.dp).padding(top = 2.dp),
        )
        Text(text, style = MaterialTheme.typography.bodyMedium, color = color)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SearchPane(
    query: String,
    categories: List<String>,
    selectedCategories: Set<String>,
    results: List<Merchant>,
    dataStatus: String,
    refreshing: Boolean,
    onQueryChange: (String) -> Unit,
    onToggleCategory: (String) -> Unit,
    onSelect: (Merchant) -> Unit,
    onRefresh: () -> Unit,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth(),
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        placeholder = { Text("店名を入力(例: マック、サイゼ)") },
        singleLine = true,
    )
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        categories.forEach { category ->
            FilterChip(
                selected = category in selectedCategories,
                onClick = { onToggleCategory(category) },
                label = { Text(category) },
            )
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            dataStatus,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.weight(1f),
        )
        if (refreshing) {
            // スピナーは IconButton(48dp)と高さを揃え、再取得中の行の高さブレを防ぐ
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            }
        } else {
            // タッチ領域は M3 最小の 48dp を確保し、アイコンの見た目だけ 20dp に抑える
            IconButton(onClick = onRefresh) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = "データを再取得",
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
    Spacer(Modifier.height(4.dp))
    when {
        query.isBlank() && selectedCategories.isEmpty() -> Text(
            "チェーン名を入力するか、カテゴリを選択すると、どのカードで払うのが得かを表示します。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outline,
        )
        results.isEmpty() -> Text(
            if (query.isBlank()) "選択中のカテゴリに店舗がありません。"
            else "「$query」に一致する店舗が見つかりませんでした。登録済みの高還元施策の対象外の可能性があります。",
            style = MaterialTheme.typography.bodyMedium,
        )
        else -> LazyColumn {
            items(results, key = { it.id }) { merchant ->
                ListItem(
                    headlineContent = { Text(merchant.name) },
                    supportingContent = { Text(merchant.category) },
                    trailingContent = {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(merchant) },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NearbyPane(
    nearby: MainViewModel.NearbyUi,
    radiusM: Int,
    onClose: () -> Unit,
    onReload: () -> Unit,
    onOpenSettings: () -> Unit,
    onRadiusChange: (Int) -> Unit,
    onPreviewPlace: (MainViewModel.NearbyPlace) -> Unit,
    onClearPreview: () -> Unit,
    onOpenDetail: (MainViewModel.NearbyPlace) -> Unit,
    onSearchHere: (Double, Double) -> Unit,
) {
    val selectedPlace = nearby.selectedPlace
    // 戻る: プレビュー中なら一覧へ戻し、そうでなければ近くのお店モードを閉じる
    BackHandler(onBack = { if (selectedPlace != null) onClearPreview() else onClose() })

    val center = if (nearby.centerLat != null && nearby.centerLon != null) {
        MapPoint(nearby.centerLat, nearby.centerLon)
    } else null

    // 地図を出せない状態(読込中/エラー/現在地不明)は地図なしの縦並びで表示する
    if (nearby.loading || nearby.error != null || center == null) {
        Column(Modifier.fillMaxSize()) {
            NearbyTopBar(onOpenSettings = onOpenSettings)
            RadiusChips(
                radiusM = radiusM,
                onRadiusChange = onRadiusChange,
                onReload = onReload,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Spacer(Modifier.height(4.dp))
            when {
                nearby.loading -> Box(
                    Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }
                nearby.error != null -> Text(
                    nearby.error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                else -> Text(
                    "現在地を取得できませんでした。",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
        return
    }

    val userLocation = if (nearby.userLat != null && nearby.userLon != null) {
        MapPoint(nearby.userLat, nearby.userLon)
    } else null
    // ピンは位置が固定なので places に対して安定に作る(パン/ズームでは作り直さない)。
    // 選択状態が変わったら強調ピンを描き直すため selectedPlace も remember キーに含める。
    val markers = remember(nearby.places, selectedPlace) {
        nearby.places.map { place ->
            MapMarker(
                point = MapPoint(place.lat, place.lon),
                label = place.name,
                colorHexes = place.brandColors,
                selected = place == selectedPlace,
                onClick = { onPreviewPlace(place) },
            )
        }
    }

    // 地図を全面に出し、店舗リストは引き上げ式のボトムシートに収める。
    // 普段は地図を広く見せ、シートを引き上げると一覧を確認できる。
    val scaffoldState = rememberBottomSheetScaffoldState(
        bottomSheetState = rememberStandardBottomSheetState(
            initialValue = SheetValue.PartiallyExpanded,
            skipHiddenState = true, // 一覧/プレビューシートは常に下部に残す(消えない)
        ),
    )
    // 店舗を選択したらシートを peek まで畳んで地図を見せる(一覧を展開中でもプレビューが隠れない)
    LaunchedEffect(selectedPlace) {
        if (selectedPlace != null) scaffoldState.bottomSheetState.partialExpand()
    }
    BottomSheetScaffold(
        scaffoldState = scaffoldState,
        sheetPeekHeight = 200.dp,
        topBar = { NearbyTopBar(onOpenSettings = onOpenSettings) },
        sheetContent = {
            if (selectedPlace != null) {
                // 選択中: 地図を残したまま店舗情報をプレビュー。判定詳細へはここから明示遷移する
                NearbyPreview(
                    place = selectedPlace,
                    onOpenDetail = { onOpenDetail(selectedPlace) },
                    onClose = onClearPreview,
                )
            } else {
                RadiusChips(
                    radiusM = radiusM,
                    onRadiusChange = onRadiusChange,
                    onReload = onReload,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                Spacer(Modifier.height(8.dp))
                // 検索中心からの距離順リスト(還元率・距離)。並びは ViewModel で確定済み
                if (nearby.places.isEmpty()) {
                    Text(
                        "周辺${distanceLabel(radiusM)}に対象施策のある店舗が見つかりませんでした。",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                } else {
                    LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                        items(nearby.places, key = { "${it.lat},${it.lon},${it.name}" }) { place ->
                            ListItem(
                                headlineContent = { Text(place.name) },
                                supportingContent = {
                                    Text("${distanceLabel(place.distanceMeters)}・${place.merchant?.category.orEmpty()}")
                                },
                                trailingContent = {
                                    place.bestRate?.let {
                                        Text(
                                            "${trimRate(it)}%",
                                            style = MaterialTheme.typography.titleMedium,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                },
                                // タップは全画面遷移せず「選択」。地図でその店にセンタリングしプレビューを出す
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onPreviewPlace(place) },
                            )
                        }
                    }
                }
            }
        },
    ) { innerPadding ->
        // 地図は全面。下端はボトムシート(peek)が重なるが、操作系は地図上部にあるので隠れない
        NearbyMap(
            center = center,
            userLocation = userLocation,
            markers = markers,
            initialZoom = zoomForRadius(radiusM),
            selectedPoint = selectedPlace?.let { MapPoint(it.lat, it.lon) },
            onSearchHere = { c -> onSearchHere(c.lat, c.lon) },
            modifier = Modifier.fillMaxSize().padding(innerPadding),
        )
    }
}

/**
 * 選択中の店舗プレビュー(ボトムシート内)。地図を残したまま店舗情報を見せ、
 * 「判定の詳細を見る」で初めて全画面の判定詳細へ遷移する。× / 戻るで一覧に復帰。
 */
@Composable
private fun NearbyPreview(
    place: MainViewModel.NearbyPlace,
    onOpenDetail: () -> Unit,
    onClose: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(place.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${distanceLabel(place.distanceMeters)}・${place.merchant?.category.orEmpty()}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = "プレビューを閉じる")
            }
        }
        place.bestRate?.let {
            Text(
                "最大 ${trimRate(it)}% 還元",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Button(onClick = onOpenDetail, modifier = Modifier.fillMaxWidth()) {
            Text("詳細を確認")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NearbyTopBar(onOpenSettings: () -> Unit) {
    // モード切替は下部ナビが担うため戻る矢印は持たない(タブ間の対等な移動)。
    // 再読み込みは半径チップ右端へ移したので、ここは設定への入口(歯車)だけ持つ
    TopAppBar(
        title = { Text("近くのお店") },
        actions = {
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Default.Settings, contentDescription = "設定")
            }
        },
        // 外側 Scaffold が既にステータスバー inset を消費済みなので、ここで二重に空けない
        windowInsets = WindowInsets(0, 0, 0, 0),
    )
}

@Composable
private fun RadiusChips(
    radiusM: Int,
    onRadiusChange: (Int) -> Unit,
    onReload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        listOf(500, 1000, 3000).forEach { radius ->
            FilterChip(
                selected = radiusM == radius,
                onClick = { onRadiusChange(radius) },
                label = { Text(distanceLabel(radius)) },
            )
        }
        // 半径チップの右端に再読み込み。地図表示時はこの行ごとボトムシート内に入る
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onReload) {
            Icon(Icons.Default.Refresh, contentDescription = "再読み込み")
        }
    }
}

/** 検索半径に応じた地図の初期ズーム(半径が大きいほど広域=低ズーム)。+1 レベル≒2倍寄り */
private fun zoomForRadius(radiusM: Int): Double = when {
    radiusM <= 500 -> 17.0
    radiusM <= 1000 -> 16.0
    else -> 14.5
}

private fun distanceLabel(meters: Int): String =
    if (meters >= 1000) {
        val km = meters / 1000.0
        if (km == km.toLong().toDouble()) "${km.toLong()}km" else "%.1fkm".format(km)
    } else {
        "${meters}m"
    }

@Composable
private fun JudgmentDetail(
    selection: MainViewModel.Selection,
    onBack: () -> Unit,
    onOpenStoreCheck: () -> Unit,
) {
    BackHandler(onBack = onBack)
    // 店名は TopAppBar に表示済み。ここではカテゴリを静的タグで補足する
    CategoryTag(selection.merchant.category)
    Spacer(Modifier.height(12.dp))
    // 公式が対象/対象外を言い切っているチェーンだけ、店舗単位の判定画面へ遷移できる
    if (selection.canCheckStore) {
        Button(onClick = onOpenStoreCheck, modifier = Modifier.fillMaxWidth()) {
            Text("この店舗が対象か調べる →")
        }
        Spacer(Modifier.height(8.dp))
    }

    if (selection.judgments.isEmpty()) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Text(
                "登録済みの高還元施策の対象外です。通常還元率のカード・QR決済を利用してください。",
                modifier = Modifier.padding(16.dp),
            )
        }
        return
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(selection.judgments, key = { it.campaign.id }) { judgment ->
            JudgmentCard(judgment)
        }
    }
}

@Composable
private fun JudgmentCard(judgment: Judgment) {
    val brandColor = parseBrandColor(judgment.campaign.brandColor) ?: MaterialTheme.colorScheme.primary
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(
                Modifier
                    .width(8.dp)
                    .fillMaxHeight()
                    .background(brandColor)
            )
            JudgmentCardBody(judgment, brandColor)
        }
    }
}

@Composable
private fun JudgmentCardBody(judgment: Judgment, brandColor: Color) {
    val campaign = judgment.campaign
    val rule = judgment.rule
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${trimRate(judgment.effectiveRate)}%",
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Surface(
                            color = brandColor,
                            shape = RoundedCornerShape(4.dp),
                        ) {
                            Text(
                                judgment.card?.cardName ?: campaign.issuer,
                                style = MaterialTheme.typography.labelMedium,
                                // ブランドカラーは任意の色なので、白固定ではなく輝度から読める文字色を選ぶ
                                color = onColorFor(brandColor),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            )
                        }
                        // ウエル活フラグを持つ決済手段は、ON/OFF によらず常に識別バッジを付ける(色=ウエルシアのロゴ色)
                        val pm = judgment.card?.pointMultiplier
                        if (pm != null) {
                            val welciaColor = parseBrandColor(pm.color) ?: MaterialTheme.colorScheme.tertiary
                            Surface(color = welciaColor, shape = RoundedCornerShape(4.dp)) {
                                Text(
                                    "ウエル活利用可",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = onColorFor(welciaColor),
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                )
                            }
                        }
                    }
                    Text(
                        campaign.name,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
            if (campaign.rateMax > judgment.effectiveRate) {
                Text(
                    "条件達成で最大${trimRate(campaign.rateMax)}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            Text("支払い方法: ${campaign.paymentInstruction}", style = MaterialTheme.typography.bodyMedium)
            rule.note?.let {
                Text("この店の条件: $it", style = MaterialTheme.typography.bodyMedium)
            }
            // 致命的な注意(警告)は error、対象外があり得る等の注意は warning で出し分ける
            judgment.warnings.forEach {
                NoticeRow(it, MaterialTheme.colorScheme.error)
            }
            rule.exclusionNote?.let {
                NoticeRow(it, warningColor())
            }
            rule.storeListUrl?.let { url ->
                val uriHandler = LocalUriHandler.current
                Text(
                    "公式の対象店舗一覧を開く →",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { uriHandler.openUri(url) },
                )
            }
            campaign.monthlyCapNote?.let {
                Text("上限: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
            // ウエル活フラグのある決済手段は、ON/OFF で実質還元率の意味づけを補足する
            judgment.card?.takeIf { it.pointMultiplier != null }?.let { card ->
                Text(
                    if (card.welcatsuApplied) "※還元率はウエル活利用時の実質還元率"
                    else "※WAONポイントに交換する事でウエル活利用可能",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            Text(
                "情報確認日: ${campaign.verifiedDate} / 最新の条件は公式サイトで確認してください",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
    }
}

/** 公式が対象/対象外を言い切っているチェーン専用の、店舗単位の対象判定画面 */
@Composable
private fun StoreCheckScreen(
    storeCheck: MainViewModel.StoreCheckState,
    onBack: () -> Unit,
    onStoreNameChange: (String) -> Unit,
) {
    BackHandler(onBack = onBack)
    // 画面タイトル(○○ 店舗判定)は TopAppBar 側に表示済み
    OutlinedTextField(
        value = storeCheck.input,
        onValueChange = onStoreNameChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text("店舗名を入力") },
        placeholder = { Text("例: ○○駅前店") },
        singleLine = true,
    )
    Spacer(Modifier.height(8.dp))

    if (storeCheck.verdicts.isEmpty()) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Text(
                "対象か調べたい店舗名を入力してください。公式が対象/対象外を公表している店舗のみ判定します。",
                modifier = Modifier.padding(16.dp),
            )
        }
        return
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(storeCheck.verdicts, key = { it.campaign.id }) { verdict ->
            StoreVerdictCard(verdict)
        }
    }
}

@Composable
private fun StoreVerdictCard(verdict: StoreVerdict) {
    // 状態は絵文字ではなく Material アイコン + セマンティックカラーで表す(端末差なく、テーマに追従)
    val icon: ImageVector
    val label: String
    val color: Color
    when (verdict.eligibility) {
        StoreEligibility.ELIGIBLE -> {
            icon = Icons.Default.CheckCircle; label = "対象"; color = MaterialTheme.colorScheme.primary
        }
        StoreEligibility.INELIGIBLE -> {
            icon = Icons.Default.Close; label = "対象外"; color = MaterialTheme.colorScheme.error
        }
        StoreEligibility.UNKNOWN -> {
            icon = Icons.Default.Info; label = "要確認"; color = warningColor()
        }
    }
    val reason = when (verdict.eligibility) {
        StoreEligibility.ELIGIBLE -> "「${verdict.matched}」は公式の対象店舗です"
        StoreEligibility.INELIGIBLE -> "「${verdict.matched}」は公式の対象外店舗です"
        StoreEligibility.UNKNOWN ->
            "公式の対象/対象外リストに掲載がない店舗です。一部対象外店舗があるため、店頭・公式サイトでご確認ください"
    }
    Card(modifier = Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(verdict.campaign.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(icon, contentDescription = null, tint = color)
                Text(label, style = MaterialTheme.typography.headlineSmall, color = color)
            }
            Text(reason, style = MaterialTheme.typography.bodyMedium)
            if (verdict.updatedDate.isNotBlank()) {
                val dateLabel = if (verdict.dateIsOfficial) {
                    "公式情報の更新日: ${verdict.updatedDate}"
                } else {
                    "公式リスト確認日: ${verdict.updatedDate}(公式に更新日記載なし)"
                }
                Text(dateLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
            verdict.sourceUrl?.let { url ->
                val uriHandler = LocalUriHandler.current
                Text(
                    "公式の店舗情報を開く →",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { uriHandler.openUri(url) },
                )
            }
        }
    }
}

private fun trimRate(rate: Double): String =
    if (rate == rate.toLong().toDouble()) rate.toLong().toString() else rate.toString()

private fun dataStatusLabel(updatedAt: String, source: DataSource?): String {
    val sourceLabel = when (source) {
        DataSource.REMOTE -> "最新データ取得済み"
        DataSource.CACHE -> "前回取得データ(オフライン?)"
        DataSource.BUNDLED -> "同梱データ(オフライン?)"
        null -> ""
    }
    return "データ更新日: $updatedAt $sourceLabel"
}

/** 背景色に対して読めるコンテンツ色(黒/白)を輝度から選ぶ */
private fun onColorFor(background: Color): Color =
    if (background.luminance() > 0.5f) Color.Black else Color.White

/** "#RRGGBB" を Color に変換。形式が不正なら null */
private fun parseBrandColor(hex: String?): Color? {
    val digits = hex?.removePrefix("#") ?: return null
    if (digits.length != 6) return null
    return digits.toLongOrNull(16)?.let { Color(0xFF000000 or it) }
}
