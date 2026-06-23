package com.ktakjm.poikatsu.ui

import android.Manifest
import android.os.Build
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.AssistChip
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
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.KeyboardType
import androidx.core.content.ContextCompat
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ktakjm.poikatsu.BuildConfig
import com.ktakjm.poikatsu.data.DataSource
import com.ktakjm.poikatsu.data.LocationHint
import com.ktakjm.poikatsu.data.Merchant
import com.ktakjm.poikatsu.data.ThemeMode
import com.ktakjm.poikatsu.domain.Judgment
import com.ktakjm.poikatsu.domain.StoreEligibility
import com.ktakjm.poikatsu.domain.StoreVerdict
import com.ktakjm.poikatsu.ui.theme.onWarningContainerColor
import com.ktakjm.poikatsu.ui.theme.warningColor
import com.ktakjm.poikatsu.ui.theme.warningContainerColor

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

    // 下位画面(詳細/店舗判定/設定)やロード・エラーに重なっていない「ベースの2タブ表示」状態。
    // 下部ナビの表示条件であり、地図モード(= この状態 かつ 近くタブ選択)の判定にもそのまま使う。
    val baseTabsVisible = !state.loading && state.error == null &&
        state.selection == null && state.storeCheck == null && !state.showSettings

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
            if (baseTabsVisible) {
                // 標準 NavigationBar の内容高は 80dp。下部が厚いので 56dp まで詰める。
                // システムバー inset は内部で消費されるぶんを足し戻し、安全領域を確保する
                val barInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                NavigationBar(modifier = Modifier.height(56.dp + barInset)) {
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
        // 地図モードはステータスバー裏まで地図を全面表示(full-bleed)するため上端 inset を当てない。
        // 上端 inset は NearbyPane に渡し、地図上の浮きコントロール(設定/このエリアを検索/現在地)だけが
        // その分を避ける。地図以外(探す/設定/詳細/店舗判定/ロード/エラー)は従来どおり inset 分内側に寄せる。
        val isMap = baseTabsVisible && state.nearby != null
        val contentPadding = if (isMap) {
            PaddingValues(bottom = innerPadding.calculateBottomPadding())
        } else {
            innerPadding
        }
        Box(Modifier.fillMaxSize().padding(contentPadding)) {
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
                        // ブリッジ: そのチェーンに絞ってから「近く」へ突入(パーミッションは onNearbyClick が担う)
                        onFindNearby = {
                            state.selection?.merchant?.let {
                                viewModel.onFindNearby(it)
                                onNearbyClick()
                            }
                        },
                    )
                }
                // 地図画面はボトムシート・地図を端まで使うため全幅。横paddingは内部で個別に当てる
                state.nearby != null -> NearbyPane(
                    nearby = state.nearby!!,
                    categories = state.categories,
                    selectedCategories = state.nearbySelectedCategories,
                    merchantFilter = state.nearbyMerchantFilter,
                    searchFailed = state.nearbySearchFailed,
                    onClose = viewModel::onCloseNearby,
                    onToggleCategory = viewModel::onToggleNearbyCategory,
                    onSelectChain = viewModel::onSelectNearbyChain,
                    onClearChain = viewModel::onClearNearbyChain,
                    onReload = viewModel::fetchNearby,
                    onSearchFailedShown = viewModel::onNearbySearchFailedShown,
                    onOpenSettings = viewModel::onOpenSettings,
                    onPreviewPlace = viewModel::onPreviewNearby,
                    onClearPreview = viewModel::onClearNearbyPreview,
                    onClusterTap = viewModel::onClearNearbyPreview,
                    onOpenDetail = viewModel::onSelectNearby,
                    onSearchHere = viewModel::searchHere,
                    // full-bleed 地図の浮きコントロール/ロード・エラー画面が避けるステータスバー高さ
                    topInset = innerPadding.calculateTopPadding(),
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

/**
 * 警告・注意のトーナル面表示(アイコン + 文)。container/content の対で error(致命) / warning(注意) を出し分ける。
 * グレーのカード地に色文字を直接乗せるとコントラストが不足するため、専用の淡い面の上に濃い文字で出す。
 * アイコン/文字の色は Surface の contentColor から自動で引き継ぐ。
 */
@Composable
private fun NoticeRow(text: String, containerColor: Color, contentColor: Color) {
    Surface(
        color = containerColor,
        contentColor = contentColor,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                modifier = Modifier.size(18.dp).padding(top = 2.dp),
            )
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
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
    categories: List<String>,
    selectedCategories: Set<String>,
    merchantFilter: Merchant?,
    searchFailed: String?,
    onClose: () -> Unit,
    onReload: () -> Unit,
    onSearchFailedShown: () -> Unit,
    onOpenSettings: () -> Unit,
    onToggleCategory: (String) -> Unit,
    onSelectChain: (Merchant) -> Unit,
    onClearChain: () -> Unit,
    onPreviewPlace: (MainViewModel.NearbyPlace) -> Unit,
    onClearPreview: () -> Unit,
    onClusterTap: () -> Unit,
    onOpenDetail: (MainViewModel.NearbyPlace) -> Unit,
    onSearchHere: (Double, Double, Int, Double) -> Unit,
    topInset: Dp,
) {
    val selectedPlace = nearby.selectedPlace
    // 戻る: プレビュー中なら一覧へ戻し、そうでなければ近くのお店モードを閉じる
    BackHandler(onBack = { if (selectedPlace != null) onClearPreview() else onClose() })

    val center = if (nearby.centerLat != null && nearby.centerLon != null) {
        MapPoint(nearby.centerLat, nearby.centerLon)
    } else null

    // 地図(中心)がまだ無い初回ロード/エラー時だけ、地図なしの全画面表示にする。
    // 中心が既にあれば再検索中でも地図・一覧は残し、進捗は地図上に小さく重ねる(NearbyMap の loadingMessage)。
    if (center == null || nearby.error != null) {
        // 地図なしの全画面状態(地図が出る前のロード/エラー)。地図モードはタイトルバーを持たない
        // (full-bleed)ので、ここでも「近くのお店」見出し・歯車は出さず内容だけを中央に出す。
        // 地図表示への切替で見出しが消える中途半端な見えを防ぐ。下部ナビは残るのでモード/設定への
        // 導線は保たれる(設定は「探す」タブから)。
        Centered {
            when {
                nearby.loading -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    // リングは地図ではなく「現在地の測位」→「Overpass で周辺店舗取得」を待っている。
                    // どちらの待ちかを出して長い待ち時間の理由を示す。
                    Text(
                        nearbyLoadingText(nearby.loadingPhase),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                nearby.error != null -> NearbyRetryState(
                    message = nearby.error,
                    isError = true,
                    onRetry = onReload,
                )
                else -> NearbyRetryState(
                    message = "現在地を取得できませんでした。",
                    isError = false,
                    onRetry = onReload,
                )
            }
        }
        return
    }

    val userLocation = if (nearby.userLat != null && nearby.userLon != null) {
        MapPoint(nearby.userLat, nearby.userLon)
    } else null
    // 絞り込み(レンズ)を適用した表示集合。地図ピン・一覧の両方でこれを使う。
    // チェーン絞り込み(merchantFilter)はジャンルより優先。未指定なら参照同一で再計算を避ける。
    val visiblePlaces = remember(nearby.places, selectedCategories, merchantFilter) {
        when {
            merchantFilter != null -> nearby.places.filter { it.merchant?.id == merchantFilter.id }
            selectedCategories.isEmpty() -> nearby.places
            else -> nearby.places.filter { it.merchant?.category in selectedCategories }
        }
    }
    // 「チェーンで絞る」ピッカー用: いま(ジャンル絞り込み後の)周辺に在るチェーンと件数。多い順→読み順。
    // 全体ではなく周辺に在るものだけ出す(「近く」の約束)。merchantFilter 指定中はピル表示なので未使用。
    val presentChains = remember(nearby.places, selectedCategories) {
        nearby.places
            .filter { selectedCategories.isEmpty() || it.merchant?.category in selectedCategories }
            .mapNotNull { it.merchant }
            .groupingBy { it }.eachCount()
            .entries
            .sortedWith(compareByDescending<Map.Entry<Merchant, Int>> { it.value }.thenBy { it.key.reading })
            .map { it.key to it.value }
    }
    // ピンは位置が固定なので places に対して安定に作る(パン/ズームでは作り直さない)。
    // 選択状態が変わったら強調ピンを描き直すため selectedPlace も remember キーに含める。
    val markers = remember(visiblePlaces, selectedPlace) {
        visiblePlaces.map { place ->
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
    // 一覧を展開中(Expanded)に店舗を選んだら peek まで畳んで地図を見せる。ただし既に
    // PartiallyExpanded のとき(詳細画面から戻った直後の再生成を含む)は partialExpand を呼ばない。
    // レイアウト確定前に状態変更すると競合し、シートが peek より沈んで「詳細を確認」下端が欠ける。
    LaunchedEffect(selectedPlace) {
        if (selectedPlace != null &&
            scaffoldState.bottomSheetState.currentValue != SheetValue.PartiallyExpanded
        ) {
            scaffoldState.bottomSheetState.partialExpand()
        }
    }
    // 再検索の一時失敗は地図を残したまま Snackbar で通知する。外側 Scaffold の host は下部シートの
    // 裏に隠れるため、地図領域に出るこの BottomSheetScaffold 自身の host を使う。
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(searchFailed) {
        if (searchFailed != null) {
            snackbarHostState.showSnackbar(searchFailed)
            onSearchFailedShown()
        }
    }
    // ボトムシートの覗き高さ。地図(上)とシート(下)の取り分。地図下端の余白(Google ロゴを peek 上に
    // 逃がす bottomPadding)もこの値に合わせる。
    // プレビュー(店舗選択中)は内容を実測し、220 で収まらない端末(フォント倍率・長い店名)では
    // 「詳細を確認」下端が欠けないよう覗き高さを内容まで伸ばす。収まるなら従来どおり 220 のまま。
    val listPeek = 220.dp
    val density = LocalDensity.current
    var previewSheetPeek by remember { mutableStateOf<Dp?>(null) }
    val sheetPeek = if (selectedPlace != null) {
        previewSheetPeek?.let { maxOf(listPeek, it) } ?: listPeek
    } else {
        listPeek
    }
    BottomSheetScaffold(
        scaffoldState = scaffoldState,
        sheetPeekHeight = sheetPeek,
        // 既定ハンドルは上下余白が厚く直下のクレジットが間延びするため、縦を詰めた小ぶりのものにする
        sheetDragHandle = { CompactDragHandle() },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        // 地図を画面上端(ステータスバー裏)まで全面表示する full-bleed。タイトルバーは持たず、
        // 設定への入口(歯車)は地図上の浮きコントロールへ移した(NearbyMap)。
        sheetContent = {
            if (selectedPlace != null) {
                // 選択中: 地図を残したまま店舗情報をプレビュー。判定詳細へはここから明示遷移する。
                // 覗き高さを内容に合わせるため帰属表示込みで実測し、ドラッグハンドル分を足す。
                Column(
                    modifier = Modifier.onSizeChanged {
                        previewSheetPeek = with(density) { it.height.toDp() } + COMPACT_HANDLE_HEIGHT
                    },
                ) {
                    SheetAttribution()
                    NearbyPreview(
                        place = selectedPlace,
                        onOpenDetail = { onOpenDetail(selectedPlace) },
                        onClose = onClearPreview,
                    )
                }
            } else {
                SheetAttribution()
                // 絞り込み(レンズ)。絞るものが在るか、チェーン絞り込み中のとき出す。
                // peek を圧迫しないよう横スクロールの1行。半径は地図ズームで決まるのでチップは持たない。
                if (nearby.places.isNotEmpty() || merchantFilter != null) {
                    NearbyFilterBar(
                        categories = categories,
                        selectedCategories = selectedCategories,
                        presentChains = presentChains,
                        merchantFilter = merchantFilter,
                        onToggleCategory = onToggleCategory,
                        onSelectChain = onSelectChain,
                        onClearChain = onClearChain,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                }
                // 検索中心からの距離順リスト(還元率・距離)。並びは ViewModel で確定済み。
                // 表示は絞り込み後の visiblePlaces。空の理由を3通りで出し分ける。
                if (visiblePlaces.isEmpty()) {
                    Text(
                        when {
                            merchantFilter != null ->
                                "「${merchantFilter.name}」はこの範囲にありません。地図を動かすか、絞り込みを解除してください。"
                            nearby.places.isEmpty() ->
                                "この範囲に対象施策のある店舗が見つかりませんでした。地図を動かして探してください。"
                            else -> "選択中のジャンルに該当する周辺店舗がありません。"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                } else {
                    LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                        items(visiblePlaces, key = { "${it.lat},${it.lon},${it.name}" }) { place ->
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
    ) { _ ->
        // 地図は全面(full-bleed)。上端はステータスバー裏まで、下端はシート(peek)背面まで描き、
        // 角丸や端から背景が覗くのを防ぐ。ステータスバーと重なる浮きコントロールは topInset で避ける。
        NearbyMap(
            center = center,
            userLocation = userLocation,
            markers = markers,
            initialZoom = nearby.zoom,
            selectedPoint = selectedPlace?.let { MapPoint(it.lat, it.lon) },
            onSearchHere = { p, r, z -> onSearchHere(p.lat, p.lon, r, z) },
            onSearchMyLocation = onReload,
            onOpenSettings = onOpenSettings,
            onClusterTap = onClusterTap,
            loadingMessage = if (nearby.loading) nearbyLoadingText(nearby.loadingPhase) else null,
            modifier = Modifier.fillMaxSize(),
            // 浮きコントロールを押し下げてステータスバーと干渉させない高さ
            topInset = topInset,
            // Google ロゴ/著作権表示が peek 状態のボトムシートに隠れないよう、peek 高さ分だけ持ち上げる
            bottomPadding = sheetPeek,
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
        // peek の下端にボタンが密着して欠けて見えないよう、最後に余白を確保する
        Spacer(Modifier.height(12.dp))
    }
}

/**
 * ボトムシートの掴み手。既定(BottomSheetDefaults.DragHandle)は上下余白が厚く、直下の
 * Yahoo! クレジット周りが間延びするため、縦を詰めた小ぶりのハンドルにする。
 */
@Composable
private fun CompactDragHandle() {
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            shape = RoundedCornerShape(50),
        ) {
            Box(Modifier.width(32.dp).height(4.dp))
        }
    }
}

/** [CompactDragHandle] の総高(縦 padding 6dp×2 + ハンドル 4dp)。プレビュー用 peek 算出に使う。 */
private val COMPACT_HANDLE_HEIGHT = 16.dp

/**
 * YOLP 利用規約: 店舗データの帰属表示。常に視認できるようシート上部(ドラッグハンドル直下)に置く。
 * 色・サイズを潰さないこと(docs/map-data-stack.md §3.2/§7)。
 */
@Composable
private fun SheetAttribution() {
    Text(
        "店舗情報: Web Services by Yahoo! JAPAN",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
    )
}

/**
 * 「近く」モードの絞り込みバー(横スクロール1行)。ボトムシートの peek 高さを圧迫しないよう1行に収める。
 * - チェーン絞り込み中(merchantFilter != null): そのチェーン名のピル(×で解除)だけを出す(チェーンはジャンルより優先)。
 * - 未絞り込み: 在チェーンが2つ以上あれば「チェーンで絞る」ピッカー + ジャンルチップ。
 * ジャンル選択集合は探すモードと独立(MainViewModel.nearbySelectedCategories)。
 */
@Composable
private fun NearbyFilterBar(
    categories: List<String>,
    selectedCategories: Set<String>,
    presentChains: List<Pair<Merchant, Int>>,
    merchantFilter: Merchant?,
    onToggleCategory: (String) -> Unit,
    onSelectChain: (Merchant) -> Unit,
    onClearChain: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (merchantFilter != null) {
        Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            InputChip(
                selected = true,
                onClick = onClearChain,
                label = { Text(merchantFilter.name) },
                trailingIcon = {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "チェーン絞り込みを解除",
                        modifier = Modifier.size(18.dp),
                    )
                },
            )
        }
    } else {
        Row(
            modifier = modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 在チェーンが2つ以上のときだけ「チェーンで絞る」を出す(1つなら絞る意味がない)
            if (presentChains.size >= 2) {
                ChainFilterDropdown(chains = presentChains, onSelect = onSelectChain)
            }
            categories.forEach { category ->
                FilterChip(
                    selected = category in selectedCategories,
                    onClick = { onToggleCategory(category) },
                    label = { Text(category) },
                )
            }
        }
    }
}

/**
 * 「チェーンで絞る」ピッカー。いま周辺に在るチェーンを件数つきで挙げ、選ぶと merchantFilter を設定する。
 * テキスト検索ではなく在チェーンからの選択にとどめる(レンズ層・検索の入口は「探す」に一本化)。
 */
@Composable
private fun ChainFilterDropdown(
    chains: List<Pair<Merchant, Int>>,
    onSelect: (Merchant) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        AssistChip(
            onClick = { expanded = true },
            label = { Text("チェーンで絞る") },
            trailingIcon = { Icon(Icons.Default.ArrowDropDown, contentDescription = null) },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            chains.forEach { (merchant, count) ->
                DropdownMenuItem(
                    text = { Text("${merchant.name}（$count）") },
                    onClick = {
                        onSelect(merchant)
                        expanded = false
                    },
                )
            }
        }
    }
}

/**
 * 近隣取得の待ち文言。全画面ローディング(初回)と地図上の進捗ピル(再検索)で同じ文言を使う。
 * リングは地図タイルではなく「現在地の測位」/「Overpass で周辺店舗取得」を待っている。
 */
private fun nearbyLoadingText(phase: MainViewModel.NearbyLoadPhase): String = when (phase) {
    MainViewModel.NearbyLoadPhase.LOCATING -> "現在地を確認しています…"
    MainViewModel.NearbyLoadPhase.SEARCHING -> "周辺の店舗を探しています…"
}

/**
 * 近くのお店の取得失敗・現在地不明時の表示。メッセージと「再試行」(現在地で再取得=onReload)を出す。
 * 地図が出せずピンも置けない状態なので、再取得の導線をここに持つ(通常時は地図の📍が担う)。
 */
@Composable
private fun NearbyRetryState(
    message: String,
    isError: Boolean,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
        Button(onClick = onRetry) { Text("再試行") }
    }
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
    onFindNearby: () -> Unit,
) {
    BackHandler(onBack = onBack)
    // 店名は TopAppBar に表示済み。ここではカテゴリを静的タグで補足する
    CategoryTag(selection.merchant.category)
    Spacer(Modifier.height(12.dp))
    // 近隣(YOLP)由来で開いた場合のみ、店舗名は YOLP データなので帰属表示を出す。
    // 名前検索由来(displayName == null)は merchants.json のデータなのでクレジットは出さない。
    if (selection.displayName != null) {
        Text(
            "店舗情報: Web Services by Yahoo! JAPAN",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )
    }
    // ブリッジ(探す→近く): 名前検索から来たとき(displayName == null)だけ「近くで探す」を出す。
    // 近隣由来(displayName != null)は既に地図上にいるので出さない。
    if (selection.displayName == null) {
        val locationHint = selection.merchant.locationHint
        if (locationHint == null) {
            FilledTonalButton(onClick = onFindNearby, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Place, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("近くのこの店を探す")
            }
        } else {
            // 位置情報を持たない発行体(自販機など)は「近く」が行き止まりになるので、
            // 代わりに位置を確認できる外部アプリ/サイトへ案内する
            LocationHintNote(locationHint)
        }
        Spacer(Modifier.height(8.dp))
    }
    // 公式が対象/対象外を言い切っているチェーンだけ、店舗単位の判定画面へ遷移できる。
    // 遷移元で文言を出し分ける: 地図ピン由来(displayName != null)は特定の1店舗を見ているので
    // 「この店舗が対象か」、名前検索由来(displayName == null)はチェーン全体なので「対象店舗を調べる」。
    if (selection.canCheckStore) {
        Button(onClick = onOpenStoreCheck, modifier = Modifier.fillMaxWidth()) {
            val storeCheckLabel =
                if (selection.displayName != null) "この店舗が対象か調べる →" else "対象店舗を調べる →"
            Text(storeCheckLabel)
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

/**
 * 位置情報を持たない発行体(自販機など)向けの案内行。「近くのこの店を探す」の代わりに、
 * 位置を確認できる外部アプリ/サイト(例: Coke ON 公式アプリ)への導線を出す。
 * 失敗・警告ではなく案内なので Info アイコン+onSurfaceVariant 系の控えめな見せ方にする。
 */
@Composable
private fun LocationHintNote(hint: LocationHint) {
    val uriHandler = LocalUriHandler.current
    Row(verticalAlignment = Alignment.Top) {
        Icon(
            Icons.Default.Info,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp).padding(top = 2.dp),
        )
        Spacer(Modifier.width(8.dp))
        Column {
            Text(
                "自販機の位置はこのアプリでは取得できません",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                hint.text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "${hint.label} →",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { uriHandler.openUri(hint.url) },
            )
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
            Text("支払い方法: ${campaign.paymentInstruction}", style = MaterialTheme.typography.bodyMedium)
            rule.note?.let {
                Text("この店の条件: $it", style = MaterialTheme.typography.bodyMedium)
            }
            // 致命的な注意(警告)は errorContainer、対象外があり得る等の注意は warningContainer で出し分ける
            judgment.warnings.forEach {
                NoticeRow(it, MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
            }
            rule.exclusionNote?.let {
                NoticeRow(it, warningContainerColor(), onWarningContainerColor())
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
    // 状態は絵文字ではなく Material アイコン + セマンティックカラーのトーナル面(ピル)で表す。
    // 色文字をカード地に直接乗せず、container/content の対で出すことでコントラストを担保する(テーマにも追従)。
    val icon: ImageVector
    val label: String
    val containerColor: Color
    val contentColor: Color
    when (verdict.eligibility) {
        StoreEligibility.ELIGIBLE -> {
            icon = Icons.Default.CheckCircle; label = "対象"
            containerColor = MaterialTheme.colorScheme.primaryContainer
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
        }
        StoreEligibility.INELIGIBLE -> {
            icon = Icons.Default.Close; label = "対象外"
            containerColor = MaterialTheme.colorScheme.errorContainer
            contentColor = MaterialTheme.colorScheme.onErrorContainer
        }
        StoreEligibility.UNKNOWN -> {
            icon = Icons.Default.Info; label = "要確認"
            containerColor = warningContainerColor()
            contentColor = onWarningContainerColor()
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
            Surface(
                color = containerColor,
                contentColor = contentColor,
                shape = RoundedCornerShape(10.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(icon, contentDescription = null)
                    Text(label, style = MaterialTheme.typography.titleLarge)
                }
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
