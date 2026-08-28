package com.ktakjm.poikatsu.ui

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.calculateEndPadding
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffold
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldDefaults
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.layout.PaneScaffoldDirective
import androidx.compose.material3.adaptive.layout.ThreePaneScaffoldDestinationItem
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirective
import androidx.compose.material3.adaptive.layout.calculateThreePaneScaffoldValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import com.ktakjm.poikatsu.data.CustomCampaign
import com.ktakjm.poikatsu.data.CustomCard
import com.ktakjm.poikatsu.data.Merchant
import com.ktakjm.poikatsu.domain.CampaignJudgment
import com.ktakjm.poikatsu.domain.ExpiringPointNotice
import com.ktakjm.poikatsu.domain.campaignGroupDisplayTitle
import com.ktakjm.poikatsu.domain.customCampaignBaseId
import com.ktakjm.poikatsu.domain.groupLabelOf
import com.ktakjm.poikatsu.domain.isCustom
import com.ktakjm.poikatsu.ui.theme.AppIcons
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3AdaptiveApi::class)
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
        if (grants.values.any { it }) viewModel.nearby.fetchNearby() else viewModel.nearby.onLocationDenied()
    }
    val onNearbyClick = {
        val granted = listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ).any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
        if (granted) {
            viewModel.nearby.fetchNearby()
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
            // 同梱モード中は refresh が走らないため、失敗 = 同梱 JSON のパース失敗(編集ミス等)
            val message = if (state.useBundledData) {
                "同梱データを読み込めませんでした。JSON の内容を確認してください。"
            } else {
                "再取得できませんでした。通信状態を確認して再度お試しください。"
            }
            snackbarHostState.showSnackbar(message)
            viewModel.onRefreshFailedShown()
        }
    }

    // 設定のエクスポート/インポート(#50)の結果通知。成功・失敗とも一時的な通知なので Snackbar
    LaunchedEffect(state.settingsBackupMessage) {
        state.settingsBackupMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.onSettingsBackupMessageShown()
        }
    }

    // 開発者向け操作(テスト通知・通知済み履歴の消去)の結果通知
    LaunchedEffect(state.developerMessage) {
        state.developerMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.onDeveloperMessageShown()
        }
    }

    // 通知ディープリンク(#82)の引き当て失敗(終了済み・データ改定)の通知
    LaunchedEffect(state.notificationLinkMessage) {
        state.notificationLinkMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.onNotificationLinkMessageShown()
        }
    }

    val selectedTab = state.selectedTab

    // カスタムキャンペーンの追加/編集ダイアログ。NEW_CUSTOM_CAMPAIGN(id 空)なら新規、null なら非表示。
    // おトクタブの一覧(追加行)と施策詳細(編集・削除)のどちらからも開くためここに置く
    var editingCustomCampaign by remember { mutableStateOf<CustomCampaign?>(null) }
    var deletingCustomCampaign by remember { mutableStateOf<CustomCampaign?>(null) }
    // 編集エディタを閉じようとしたとき(戻る・✕)は、入力内容の破棄確認を挟む。閉じる導線
    // (topBar の✕・BackHandler)は直接 null にせずこの関数を通し、確認ダイアログを出す。
    // 保存(onSave)は別経路なので確認を挟まない
    var confirmCloseEditor by remember { mutableStateOf(false) }
    val requestCloseEditor = { confirmCloseEditor = true }

    // 「地図」タブ表示中だけ現在地を継続購読して青ドットを追従させる(カメラ移動・YOLP 再検索はしない)。
    // タブ離脱で composition から外れ、バックグラウンドでは repeatOnLifecycle(STARTED) が止めるので
    // 購読は自動解除される。key の searchStamp は検索完了のたびに購読をやり直すためのもので、
    // パーミッションを後から許可したケース(初回は購読ガードで即 return)を次の検索完了時に拾い直す。
    if (selectedTab == AppTab.NEARBY && state.nearby.search != null) {
        val searchStamp = state.nearby.search?.searchStamp
        LaunchedEffect(searchStamp) {
            lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.nearby.observeLocationUpdates()
            }
        }
    }

    // お店タブの一覧+詳細(list-detail)を左右二ペインで並べられる窓か。判断は M3 canonical layout の
    // 標準 directive に委ねる(#54。幅 Expanded 級=840dp 以上で 2 ペイン、それ未満は 1)。二ペインなら
    // 判定詳細(selection)・店舗判定(storeCheck)を全画面オーバーレイでなく右の詳細ペインに出す。
    // 一ペインの窓では従来どおり全画面オーバーレイ(縦画面と同じ見え方)になる。
    val paneDirective = calculatePaneScaffoldDirective(currentWindowAdaptiveInfo())
    val searchTwoPane = selectedTab == AppTab.SEARCH && paneDirective.maxHorizontalPartitions > 1
    // おトクタブも同じ基準で一覧+施策詳細の二ペインにする(#55)。施策詳細はタブ非依存の
    // オーバーレイ(お店タブのバナー・地図タブのお知らせピルからも開く)のため、
    // 「おトクタブ表示中に開いたときだけ」右ペインに出す(selectedTab で出し分け。
    // 地図タブから開いたときは従来どおり全画面オーバーレイが自然)
    val campaignsTwoPane = selectedTab == AppTab.CAMPAIGNS && paneDirective.maxHorizontalPartitions > 1
    // 設定タブも同じ基準でカテゴリ一覧(左)+サブページ内容(右)の二ペインにする(#56)。
    // サブページは設定タブ専用のオーバーレイなので、施策詳細(#55)のような出し分けは不要
    val settingsTwoPane = selectedTab == AppTab.SETTINGS && paneDirective.maxHorizontalPartitions > 1

    // 横画面では下部タブを左端の NavigationRail に置き換え、縦方向の占有をなくす(#4)。
    // M3 の定石(横長・中幅以上は Rail)。判定は WindowSizeClass でなく window の向きで足りる
    // (回転で Activity が再生成されるため、その場の Configuration を見ればよい)
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    // 地図タブの横画面では判定詳細・店舗判定・施策詳細(お知らせピル発)を全画面オーバーレイでなく、
    // 地図の上に浮くサイドシート(右端・全高・400dp)に出す(#57)。タップしたピンと周辺の位置関係を
    // 保ったまま詳細を読めるようにするため。nearby が無い間(初回ロード/エラー)は地図が無く
    // シートの置き場もないため、従来どおり全画面オーバーレイに倒す。縦画面は無変更
    // (ボトムシートに詳細を収める高さはない)
    val nearbySideSheet = selectedTab == AppTab.NEARBY && isLandscape && state.nearby.search != null

    // 全画面オーバーレイとして扱う店舗判定・判定詳細・施策詳細。二ペイン時は詳細ペイン内、
    // 地図タブ横画面はサイドシートの表示なので null になり、topBar・本文のオーバーレイ分岐と
    // baseTabsVisible を素通りして SearchListDetail / CampaignsListDetail / NearbyDetailSideSheet が受ける
    val overlayStoreCheck = state.storeCheck?.takeUnless { searchTwoPane || nearbySideSheet }
    val overlaySelection = state.selection?.takeUnless { searchTwoPane || nearbySideSheet }
    val overlayCampaignGroup = state.selectedCampaignGroup?.takeUnless { campaignsTwoPane || nearbySideSheet }
    // 設定サブページも同様。二ペイン時は SettingsListDetail の詳細ペイン内表示になる(#56)
    val overlaySettingsSubpage = state.settingsSubpage?.takeUnless { settingsTwoPane }

    // 下位画面(詳細/店舗判定/キャンペーン詳細/カスタムキャンペーン編集/設定サブページ)や
    // ロード・エラーに重なっていないベースのタブ表示状態。下部ナビ・FAB の表示条件。
    val baseTabsVisible = !state.loading && state.error == null &&
        overlaySelection == null && overlayStoreCheck == null &&
        overlayCampaignGroup == null && overlaySettingsSubpage == null &&
        editingCustomCampaign == null

    // 下部ナビ/NavigationRail 共通のタブ定義とタブ切替。地図タブは選択時に位置権限の確認・取得も走らせる
    val navTabs = listOf(
        NavTabSpec(AppTab.SEARCH, AppIcons.Storefront, "お店"),
        NavTabSpec(AppTab.NEARBY, AppIcons.Map, "地図"),
        NavTabSpec(AppTab.CAMPAIGNS, AppIcons.LocalOffer, "おトク"),
        NavTabSpec(AppTab.SETTINGS, Icons.Default.Settings, "設定"),
    )
    val onTabClick: (AppTab) -> Unit = { tab ->
        if (selectedTab != tab) {
            viewModel.onSelectTab(tab)
            if (tab == AppTab.NEARBY) onNearbyClick()
        }
    }

    // 検索窓を TopAppBar 側(SearchBarRow)に出すモード(横画面の1ペインのみ)。
    // topBar の分岐と SearchPane(本文の検索窓を隠す)で同じ判断を共有する
    val searchInHeader = selectedTab == AppTab.SEARCH && isLandscape && !searchTwoPane

    Row(Modifier.fillMaxSize()) {
        if (isLandscape && baseTabsVisible) {
            // 既定色は NavigationBar=surfaceContainer / NavigationRail=surface で食い違い、
            // Rail だと本文と同色になってタブ面が見えなくなる。縦の下部タブと同じ色に揃える
            NavigationRail(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                Spacer(Modifier.weight(1f))
                navTabs.forEach { spec ->
                    NavigationRailItem(
                        selected = selectedTab == spec.tab,
                        onClick = { onTabClick(spec.tab) },
                        icon = { Icon(spec.icon, contentDescription = null) },
                        label = { Text(spec.label) },
                    )
                }
                Spacer(Modifier.weight(1f))
            }
        }
        Scaffold(
            modifier = Modifier.weight(1f),
            topBar = {
                when {
                    state.loading -> Unit
                    state.error != null -> Unit
                    // カスタムキャンペーン編集は最前面のオーバーレイ(施策詳細の上からも開くため先頭で分岐)
                    editingCustomCampaign != null -> TopAppBar(
                        title = {
                            Text(
                                if (editingCustomCampaign!!.id.isEmpty()) "キャンペーンを追加"
                                else "キャンペーンを編集"
                            )
                        },
                        navigationIcon = {
                            IconButton(onClick = requestCloseEditor) {
                                Icon(Icons.Default.Close, contentDescription = "閉じる")
                            }
                        },
                    )
                    overlayStoreCheck != null -> TopAppBar(
                        title = { Text(storeCheckTitle(overlayStoreCheck)) },
                        navigationIcon = {
                            IconButton(onClick = viewModel::onCloseStoreCheck) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                            }
                        },
                    )
                    overlaySelection != null -> TopAppBar(
                        title = { Text(selectionTitle(overlaySelection)) },
                        navigationIcon = {
                            IconButton(onClick = viewModel::onBack) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                            }
                        },
                    )
                    // キャンペーン詳細はタブ非依存のオーバーレイ(探すバナー・地図ピルからも開くため)。
                    // おトクタブの二ペイン時は overlayCampaignGroup が null になりここを素通りする(#55)
                    overlayCampaignGroup != null -> {
                        val group = overlayCampaignGroup
                        val title = campaignGroupDisplayTitle(group.map { it.campaign }, state.merchantsById)
                        TopAppBar(
                            title = { Text(title) },
                            navigationIcon = {
                                IconButton(onClick = viewModel::onCloseCampaignDetail) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                                }
                            },
                        )
                    }
                    overlaySettingsSubpage != null -> TopAppBar(
                        title = { Text(overlaySettingsSubpage.title) },
                        navigationIcon = {
                            IconButton(onClick = viewModel::onCloseSettingsSubpage) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                            }
                        },
                    )
                    selectedTab == AppTab.NEARBY -> Unit
                    // 二ペイン時はグローバルバーを出さない(アプリバーは各ペインに属する M3 の multi-pane
                    // 定石)。タイトル行+全幅検索窓の2行ヘッダは一覧ペイン先頭に置き、
                    // 詳細ペインは上端まで全高にして詳細カードの表示域を確保する(#54)
                    searchTwoPane -> Unit
                    // おトクタブの二ペインも同様(タイトル行は一覧ペイン先頭の PaneHeader が担う。#55)
                    campaignsTwoPane -> Unit
                    // 設定タブの二ペインも同様(#56)
                    settingsTwoPane -> Unit
                    // 横画面(1ペイン)は検索窓+再取得をタイトル直後に左詰めで同居させ、本文側の検索窓の
                    // 行(約64dp)を節約する(#54)。actions(右寄せ)に置くと直下のカテゴリチップ行と分断
                    // されるため title スロットに Row で置く
                    searchInHeader -> TopAppBar(
                        title = {
                            SearchBarRow(
                                query = state.query,
                                onQueryChange = viewModel::onQueryChange,
                                refreshing = state.refreshing,
                                onRefresh = viewModel::onManualRefresh,
                            )
                        },
                    )
                    selectedTab == AppTab.SEARCH -> TopAppBar(title = { Text("ポイ活ナビ") })
                    selectedTab == AppTab.CAMPAIGNS -> TopAppBar(title = { Text("おトク") })
                    selectedTab == AppTab.SETTINGS -> TopAppBar(title = { Text("設定") })
                }
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
            // カスタムキャンペーンの登録はスクロール位置に依らず届くよう FAB に置く
            // (一覧+新規作成の M3 定石。ベースのタブ表示時のみ=詳細・ダイアログ表示中は出さない)
            floatingActionButton = {
                if (baseTabsVisible && selectedTab == AppTab.CAMPAIGNS) {
                    FloatingActionButton(onClick = { editingCustomCampaign = NEW_CUSTOM_CAMPAIGN }) {
                        Icon(Icons.Default.Add, contentDescription = "キャンペーンを自分で登録")
                    }
                }
            },
            bottomBar = {
                // 横画面は NavigationRail(Row の先頭)が担うため下部タブは出さない
                if (!isLandscape && baseTabsVisible) {
                    val barInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                    NavigationBar(modifier = Modifier.height(56.dp + barInset)) {
                        navTabs.forEach { spec ->
                            NavigationBarItem(
                                selected = selectedTab == spec.tab,
                                onClick = { onTabClick(spec.tab) },
                                icon = { Icon(spec.icon, contentDescription = null) },
                                label = { Text(spec.label) },
                            )
                        }
                    }
                }
            },
        ) { innerPadding ->
            val isMap = baseTabsVisible && selectedTab == AppTab.NEARBY && state.nearby.search != null
            val contentPadding = if (isMap) {
                // full-bleed にするのは上端のみ。下端(ナビバー)と、横画面で 3 ボタンナビが
                // 側面に来る端末の end inset は避ける(縦画面では end=0 なので影響なし)
                PaddingValues(
                    bottom = innerPadding.calculateBottomPadding(),
                    end = innerPadding.calculateEndPadding(LocalLayoutDirection.current),
                )
            } else {
                innerPadding
            }
            val stableOriginName = remember { mutableStateOf(state.nearby.origin?.name) }
            if (state.nearby.search?.loading != true) {
                stableOriginName.value = state.nearby.origin?.name
            }
            // 設定サブページの中身(#47)。全画面オーバーレイ(一ペイン)と二ペインの詳細ペイン(#56)の
            // どちらからも同じものを描くため、state/viewModel を掴んだローカルの Composable ラムダに
            // まとめる(お店・おトクタブの searchPane/campaignPane と同じ作法。引数で state と
            // コールバックを全部渡し直す関数抽出はシグネチャが 40 個近くになるため採らない)
            val settingsSubpageContent: @Composable (SettingsSubpage) -> Unit = { page ->
                when (page) {
                    SettingsSubpage.DISPLAY -> DisplaySettingsPage(
                        themeMode = state.themeMode,
                        dynamicColor = state.dynamicColor,
                        onBack = viewModel::onCloseSettingsSubpage,
                        onThemeModeChange = viewModel::onSetThemeMode,
                        onDynamicColorChange = viewModel::onSetDynamicColor,
                    )
                    SettingsSubpage.PAYMENT_METHODS -> PaymentMethodsSettingsPage(
                        cards = state.cardSettings,
                        customCards = state.customCards,
                        brands = state.brandSettings,
                        qrPayments = state.qrPaymentSettings,
                        pointCurrencies = state.pointCurrencySettings,
                        onBack = viewModel::onCloseSettingsSubpage,
                        onCardOwnedChange = viewModel::onSetCardOwned,
                        onCardRateChange = viewModel::onSetCardRate,
                        onCardBrandChange = viewModel::onSetCardBrand,
                        onCardClassChange = viewModel::onSetCardClass,
                        onAddCustomCard = viewModel::onAddCustomCard,
                        onUpdateCustomCard = viewModel::onUpdateCustomCard,
                        onRemoveCustomCard = viewModel::onRemoveCustomCard,
                        onBrandOwnedChange = viewModel::onSetBrandOwned,
                        onQrEnabledChange = viewModel::onSetQrEnabled,
                        onPointProgramMemberChange = viewModel::onSetPointProgramMembership,
                        onPointMultiplierChange = viewModel::onSetPointMultiplierEnabled,
                        onPointMultiplierFactorChange = viewModel::onSetPointMultiplierFactor,
                        onPointValueChange = viewModel::onSetPointCurrencyValue,
                        onPointBalanceChange = viewModel::onSetPointBalance,
                    )
                    SettingsSubpage.MUNICIPALITIES -> MunicipalitySettingsPage(
                        registeredAreas = state.registeredAreas,
                        municipalityMaster = state.municipalityMaster,
                        snackbarHostState = snackbarHostState,
                        onBack = viewModel::onCloseSettingsSubpage,
                        onAdd = viewModel::onAddRegisteredArea,
                        onRemove = viewModel::onRemoveRegisteredArea,
                    )
                    SettingsSubpage.NOTIFICATIONS -> NotificationSettingsPage(
                        enabled = state.notificationsEnabled,
                        notifyTimeMinutes = state.notificationTimeMinutes,
                        onBack = viewModel::onCloseSettingsSubpage,
                        onEnabledChange = viewModel::onSetNotificationsEnabled,
                        onTimeChange = viewModel::onSetNotificationTime,
                    )
                    SettingsSubpage.DATA -> DataSettingsPage(
                        dataStatus = dataStatusLabel(
                            state.dataUpdatedAt,
                            state.dataSource,
                            state.useTestData,
                            state.useBundledData,
                        ),
                        autoRefresh = state.autoRefresh,
                        refreshing = state.refreshing,
                        useBundledData = state.useBundledData,
                        onBack = viewModel::onCloseSettingsSubpage,
                        onAutoRefreshChange = viewModel::onSetAutoRefresh,
                        onRefresh = viewModel::onManualRefresh,
                    )
                    SettingsSubpage.EXCLUDED_STORES -> ExcludedStoresSettingsPage(
                        pairs = state.excludedStorePairs,
                        campaignNames = state.allCampaignNames,
                        expiredCampaignIds = state.expiredCampaignIds,
                        merchantNames = state.merchantNames,
                        onBack = viewModel::onCloseSettingsSubpage,
                        onRemove = viewModel::onRemoveExcludedStorePair,
                        onRemoveStale = viewModel::onRemoveStaleExcludedStorePairs,
                    )
                    SettingsSubpage.BACKUP -> BackupSettingsPage(
                        pendingImport = state.pendingSettingsImport,
                        onBack = viewModel::onCloseSettingsSubpage,
                        onExport = viewModel::onExportSettings,
                        onPickImport = viewModel::onPickSettingsImport,
                        onConfirmImport = viewModel::onConfirmSettingsImport,
                        onCancelImport = viewModel::onCancelSettingsImport,
                    )
                    SettingsSubpage.DEVELOPER -> DeveloperSettingsPage(
                        developerMode = state.developerMode,
                        dataCommitRef = state.dataCommitRef,
                        dataCommitSha = state.dataCommitSha,
                        useTestData = state.useTestData,
                        useBundledData = state.useBundledData,
                        nearbyPoiCount = state.nearbyDebugPois.size,
                        onBack = viewModel::onCloseSettingsSubpage,
                        onDeveloperModeChange = viewModel::onSetDeveloperMode,
                        onDataCommitRefChange = viewModel::onSetDataCommitRef,
                        onUseTestDataChange = viewModel::onSetUseTestData,
                        onUseBundledDataChange = viewModel::onSetUseBundledData,
                        onTestNotification = viewModel::onTestNotification,
                        onClearNotifiedCampaigns = viewModel::onClearNotifiedCampaigns,
                        onOpenNearbyPois = {
                            viewModel.onOpenSettingsSubpage(SettingsSubpage.DEVELOPER_POIS)
                        },
                    )
                    // onCloseSettingsSubpage が DEVELOPER へ戻す(2 階層目。#70)
                    SettingsSubpage.DEVELOPER_POIS -> DeveloperPoisPage(
                        pois = state.nearbyDebugPois,
                        onBack = viewModel::onCloseSettingsSubpage,
                    )
                    SettingsSubpage.ABOUT -> AboutSettingsPage(
                        onBack = viewModel::onCloseSettingsSubpage,
                        onOpenLicenses = { viewModel.onOpenSettingsSubpage(SettingsSubpage.LICENSES) },
                    )
                    // onCloseSettingsSubpage が ABOUT へ戻す(2 階層目)
                    SettingsSubpage.LICENSES -> LicensesPage(
                        onBack = viewModel::onCloseSettingsSubpage,
                    )
                }
            }
            Box(Modifier.fillMaxSize().padding(contentPadding)) {
                when {
                    state.loading -> Centered { CircularProgressIndicator() }
                    state.error != null -> Centered { Text(state.error!!, color = MaterialTheme.colorScheme.error) }
                    // カスタムキャンペーン編集(最前面のオーバーレイ)。topBar の分岐順と一致させること
                    editingCustomCampaign != null -> PaddedColumn {
                        val editing = editingCustomCampaign!!
                        // 紐付け先候補: 所有カタログカード + カスタムカード + コード決済 + ブランド指定。
                        // 未利用のコード決済も出す(保存時に VM が「利用中」へ自動登録するため迷子にならない)
                        val paymentOptions = state.cardSettings.filter { it.owned }.map {
                            PaymentOptionUi(cardId = it.cardId, label = it.cardName, color = it.brandColor)
                        } + state.customCards.map {
                            PaymentOptionUi(cardId = it.id, label = it.name, color = it.color ?: CustomCard.DEFAULT_COLOR)
                        } + state.qrPaymentSettings.map {
                            PaymentOptionUi(qrPaymentId = it.id, label = it.name, color = it.brandColor)
                        } + state.brandSettings.map {
                            PaymentOptionUi(cardBrand = it.brand, label = "${it.brand}(国際ブランド指定)", color = it.color)
                        }
                        CustomCampaignEditorScreen(
                            initial = editing.takeUnless { it.id.isEmpty() },
                            paymentOptions = paymentOptions,
                            chains = state.catalogMerchants,
                            onSave = { campaign ->
                                if (editing.id.isEmpty()) {
                                    viewModel.onAddCustomCampaign(campaign)
                                } else {
                                    viewModel.onUpdateCustomCampaign(campaign)
                                    // 開いている施策詳細は編集前の内容のままなので閉じる(一覧は rebuild で更新される)
                                    viewModel.onCloseCampaignDetail()
                                }
                                editingCustomCampaign = null
                            },
                            onClose = requestCloseEditor,
                        )
                    }
                    // 店舗判定・判定詳細の全画面オーバーレイ(overlay* は二ペイン時 null=詳細ペイン内表示)。
                    // topBar の分岐順と一致させること
                    overlayStoreCheck != null -> PaddedColumn {
                        StoreCheckScreen(
                            storeCheck = overlayStoreCheck,
                            onBack = viewModel::onCloseStoreCheck,
                            onStoreNameChange = viewModel::onStoreNameChange,
                        )
                    }
                    overlaySelection != null -> PaddedColumn {
                        JudgmentDetail(
                            selection = overlaySelection,
                            onBack = viewModel::onBack,
                            onOpenStoreCheck = viewModel::onOpenStoreCheck,
                            onFindNearby = {
                                state.selection?.merchant?.let {
                                    viewModel.nearby.onFindNearby(it)
                                    onNearbyClick()
                                }
                            },
                            onExcludeStore = viewModel::onExcludeStore,
                            onRestoreExcludedStore = viewModel::onRestoreExcludedStore,
                            expiringNotices = state.expiringPointNotices,
                        )
                    }
                    // キャンペーン詳細(タブ非依存のオーバーレイ)。topBar の分岐順と一致させること
                    overlayCampaignGroup != null -> PaddedColumn {
                        val customSource = customCampaignSource(overlayCampaignGroup, state.customCampaigns)
                        CampaignDetail(
                            judgments = overlayCampaignGroup,
                            merchants = state.merchantsById,
                            storeRates = state.campaignStoreRates,
                            onBack = viewModel::onCloseCampaignDetail,
                            onFindChains = { ids ->
                                viewModel.nearby.onFindNearbyByIds(ids)
                                onNearbyClick()
                            },
                            onEditCustom = customSource?.let { { editingCustomCampaign = it } },
                            onDeleteCustom = customSource?.let { { deletingCustomCampaign = it } },
                        )
                    }
                    overlaySettingsSubpage != null -> settingsSubpageContent(overlaySettingsSubpage)
                    selectedTab == AppTab.NEARBY -> {
                        val nearbySearch = state.nearby.search
                        if (nearbySearch != null) {
                            NearbyPane(
                                nearby = nearbySearch,
                                categories = state.categories,
                                selectedCategories = state.nearby.selectedCategories,
                                merchantFilters = state.nearby.merchantFilters,
                                searchFailed = state.nearby.searchFailed,
                                placeSearch = PlaceSearchState(
                                    originName = stableOriginName.value,
                                    candidates = state.nearby.geocodeCandidates,
                                    isGeocoding = state.nearby.isGeocoding,
                                ),
                                placeSearchActions = PlaceSearchActions(
                                    onGeocode = viewModel.nearby::onGeocode,
                                    onSelectCandidate = viewModel.nearby::onSelectGeocodedPlace,
                                    onClearOrigin = viewModel.nearby::onClearOrigin,
                                    onDismiss = viewModel.nearby::onDismissGeocoding,
                                ),
                                onClose = viewModel.nearby::onCloseNearby,
                                onToggleCategory = viewModel.nearby::onToggleNearbyCategory,
                                onToggleChain = viewModel.nearby::onToggleNearbyLens,
                                onReload = viewModel.nearby::fetchNearby,
                                onSearchFailedShown = viewModel.nearby::onNearbySearchFailedShown,
                                onPreviewPlace = viewModel.nearby::onPreviewNearby,
                                onClearPreview = viewModel.nearby::onClearNearbyPreview,
                                onOpenDetail = viewModel.nearby::onSelectNearby,
                                onCloseDetail = viewModel.nearby::onCloseNearbyDetail,
                                onSearchHere = viewModel.nearby::searchHere,
                                onSelectionZoomChanged = viewModel.nearby::onSelectionZoomChanged,
                                onOpenMunicipalGroup = viewModel::onSelectCampaignGroup,
                                topInset = innerPadding.calculateTopPadding(),
                            )
                            if (nearbySideSheet) {
                                NearbyDetailSideSheet(
                                    storeCheck = state.storeCheck,
                                    selection = state.selection,
                                    campaignGroup = state.selectedCampaignGroup,
                                    merchants = state.merchantsById,
                                    storeRates = state.campaignStoreRates,
                                    expiringNotices = state.expiringPointNotices,
                                    topInset = innerPadding.calculateTopPadding(),
                                    onBack = viewModel::onBack,
                                    onOpenStoreCheck = viewModel::onOpenStoreCheck,
                                    onCloseStoreCheck = viewModel::onCloseStoreCheck,
                                    onStoreNameChange = viewModel::onStoreNameChange,
                                    onExcludeStore = viewModel::onExcludeStore,
                                    onRestoreExcludedStore = viewModel::onRestoreExcludedStore,
                                    onCloseCampaignDetail = viewModel::onCloseCampaignDetail,
                                    onFindChains = { ids ->
                                        viewModel.nearby.onFindNearbyByIds(ids)
                                        onNearbyClick()
                                    },
                                    modifier = Modifier.align(Alignment.CenterEnd),
                                )
                            }
                        } else {
                            Centered { CircularProgressIndicator() }
                        }
                    }
                    selectedTab == AppTab.CAMPAIGNS -> {
                        // おトクタブ。二ペイン相当の窓なら一覧(左)+施策詳細(右)の list-detail(#55)、
                        // 一ペインなら従来どおり一覧のみ(詳細は上の全画面オーバーレイ分岐が受ける)
                        val campaignPane: @Composable () -> Unit = {
                            CampaignPane(
                                activeCampaigns = state.campaignsActive,
                                upcomingCampaigns = state.campaignsUpcoming,
                                expiredCustomCampaigns = state.expiredCustomCampaigns,
                                merchants = state.merchantsById,
                                campaignColors = state.campaignBrandColors,
                                personalRates = state.campaignPersonalRates,
                                filter = state.campaignFilter,
                                onFilterChange = viewModel::onSetCampaignFilter,
                                showRegionChip = state.registeredAreas.isNotEmpty() &&
                                    !state.municipalityMaster.isEmpty(),
                                regionFilterOn = !state.showAllCampaigns,
                                onToggleRegionFilter = viewModel::onToggleShowAllCampaigns,
                                selectedGroupId = state.selectedCampaignGroup
                                    ?.firstOrNull()?.campaign?.id
                                    ?.takeIf { campaignsTwoPane },
                                onSelectGroup = viewModel::onSelectCampaignGroup,
                            )
                        }
                        if (campaignsTwoPane) {
                            val customSource = state.selectedCampaignGroup
                                ?.let { customCampaignSource(it, state.customCampaigns) }
                            CampaignsListDetail(
                                selectedGroup = state.selectedCampaignGroup,
                                merchants = state.merchantsById,
                                storeRates = state.campaignStoreRates,
                                directive = paneDirective,
                                listPane = {
                                    // 二ペインはグローバル TopAppBar を持たない(詳細ペイン全高のため)ので、
                                    // タイトル行を一覧ペイン先頭に置く(お店タブと同じ様式。#55)
                                    PaneHeader(title = "おトク")
                                    campaignPane()
                                },
                                onBack = viewModel::onCloseCampaignDetail,
                                onFindChains = { ids ->
                                    viewModel.nearby.onFindNearbyByIds(ids)
                                    onNearbyClick()
                                },
                                onEditCustom = customSource?.let { { editingCustomCampaign = it } },
                                onDeleteCustom = customSource?.let { { deletingCustomCampaign = it } },
                            )
                        } else {
                            PaddedColumn { campaignPane() }
                        }
                    }
                    selectedTab == AppTab.SETTINGS -> {
                        // 設定タブ。二ペイン相当の窓ならカテゴリ一覧(左)+サブページ内容(右)の
                        // list-detail(#56)、一ペインなら従来どおりカテゴリ一覧のみ
                        // (サブページは上の全画面オーバーレイ分岐が受ける)
                        val settingsPane: @Composable () -> Unit = {
                            SettingsScreen(
                                displaySummary = displaySettingsSummary(
                                    state.themeMode,
                                    state.dynamicColor,
                                    dynamicSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
                                ),
                                paymentSummary = paymentMethodsSummary(
                                    cardCount = state.cardSettings.count { it.owned } + state.customCards.size,
                                    brandCount = state.brandSettings.count { it.owned },
                                    qrCount = state.qrPaymentSettings.count { it.enabled },
                                    pointCount = state.pointCurrencySettings
                                        .count { it.member || it.multiplierEnabled },
                                ),
                                municipalitySummary = municipalitySummary(state.registeredAreas),
                                notificationSummary = notificationSummary(
                                    state.notificationsEnabled,
                                    state.notificationTimeMinutes,
                                ),
                                dataSummary = dataRowSummary(
                                    state.dataUpdatedAt,
                                    state.dataSource,
                                    state.useTestData,
                                    state.useBundledData,
                                ),
                                excludedStoresSummary = excludedStoresSummary(state.excludedStorePairs.size),
                                developerSummary = developerRowSummary(
                                    state.developerMode,
                                    state.dataCommitRef,
                                    state.useTestData,
                                    state.useBundledData,
                                ),
                                // 2 階層目(ライセンス・取得した地図データ)を開いている間は親カテゴリの
                                // 行をハイライトしたままにする(一覧に無い行を選択中にはできない)
                                selectedPage = state.settingsSubpage
                                    ?.let { it.parent ?: it }
                                    ?.takeIf { settingsTwoPane },
                                onOpenSubpage = viewModel::onOpenSettingsSubpage,
                            )
                        }
                        if (settingsTwoPane) {
                            SettingsListDetail(
                                subpage = state.settingsSubpage,
                                directive = paneDirective,
                                listPane = settingsPane,
                                onClose = viewModel::onCloseSettingsSubpage,
                                subpageContent = settingsSubpageContent,
                            )
                        } else {
                            settingsPane()
                        }
                    }
                    else -> {
                        // お店タブ。二ペイン相当の窓なら一覧(左)+判定詳細(右)の list-detail、
                        // 一ペインなら従来どおり一覧のみ(詳細は上の全画面オーバーレイ分岐が受ける)
                        val searchPane: @Composable () -> Unit = {
                            SearchPane(
                                query = state.query,
                                categories = state.categories,
                                selectedCategories = state.selectedCategories,
                                results = state.results,
                                unrewardedNames = state.unrewardedNames,
                                dataStatus = dataStatusLabel(
                                    state.dataUpdatedAt,
                                    state.dataSource,
                                    state.useTestData,
                                    state.useBundledData,
                                ),
                                refreshing = state.refreshing,
                                municipalAreaNames = state.searchMunicipalAreaNames,
                                searchInHeader = searchInHeader,
                                compact = isLandscape,
                                selectedMerchantId = state.selection?.merchant?.id,
                                onQueryChange = viewModel::onQueryChange,
                                onToggleCategory = viewModel::onToggleCategory,
                                onSelect = viewModel::onSelect,
                                onRefresh = viewModel::onManualRefresh,
                                onOpenMunicipalCampaigns = viewModel::onOpenMunicipalCampaigns,
                                onRegisterCampaign = { editingCustomCampaign = NEW_CUSTOM_CAMPAIGN },
                            )
                        }
                        if (searchTwoPane) {
                            SearchListDetail(
                                selection = state.selection,
                                storeCheck = state.storeCheck,
                                directive = paneDirective,
                                listPane = {
                                    // 二ペインはグローバル TopAppBar を持たない(詳細ペイン全高のため)ので、
                                    // タイトル+再取得の行を一覧ペイン先頭に置く。検索窓は SearchPane 側の
                                    // 全幅フィールド=タイトルと検索窓の2行構成(1行に同居させるとペイン幅
                                    // では検索窓が短くなりすぎる)。再取得は横ではデータ状態行が無いため
                                    // ここ、縦(タブレット級)では従来どおり状態行側に出す
                                    PaneHeader(
                                        title = "ポイ活ナビ",
                                        trailing = {
                                            if (isLandscape) {
                                                RefreshAction(state.refreshing, viewModel::onManualRefresh)
                                            }
                                        },
                                    )
                                    searchPane()
                                },
                                onBack = viewModel::onBack,
                                onOpenStoreCheck = viewModel::onOpenStoreCheck,
                                onCloseStoreCheck = viewModel::onCloseStoreCheck,
                                onStoreNameChange = viewModel::onStoreNameChange,
                                onFindNearby = {
                                    state.selection?.merchant?.let {
                                        viewModel.nearby.onFindNearby(it)
                                        onNearbyClick()
                                    }
                                },
                                onExcludeStore = viewModel::onExcludeStore,
                                onRestoreExcludedStore = viewModel::onRestoreExcludedStore,
                                expiringNotices = state.expiringPointNotices,
                            )
                        } else {
                            PaddedColumn { searchPane() }
                        }
                    }
                }
            }
        }
    }

    // エディタを閉じる前の破棄確認(戻る・✕ から要求される)。入力途中の内容が消えるため確認する
    if (confirmCloseEditor && editingCustomCampaign != null) {
        val isNew = editingCustomCampaign!!.id.isEmpty()
        AlertDialog(
            onDismissRequest = { confirmCloseEditor = false },
            title = { Text(if (isNew) "入力を破棄しますか？" else "編集を破棄しますか？") },
            text = {
                Text("入力した内容は保存されません。", style = MaterialTheme.typography.bodyMedium)
            },
            confirmButton = {
                TextButton(onClick = {
                    editingCustomCampaign = null
                    confirmCloseEditor = false
                }) { Text("閉じる") }
            },
            dismissButton = {
                TextButton(onClick = { confirmCloseEditor = false }) {
                    Text(if (isNew) "入力を続ける" else "編集を続ける")
                }
            },
        )
    }

    deletingCustomCampaign?.let { campaign ->
        AlertDialog(
            onDismissRequest = { deletingCustomCampaign = null },
            title = { Text("キャンペーンを削除しますか？") },
            text = {
                Text("「${campaign.name}」を削除します。", style = MaterialTheme.typography.bodyMedium)
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.onRemoveCustomCampaign(campaign.id)
                    viewModel.onCloseCampaignDetail()
                    deletingCustomCampaign = null
                }) { Text("削除") }
            },
            dismissButton = {
                TextButton(onClick = { deletingCustomCampaign = null }) { Text("キャンセル") }
            },
        )
    }
}

/** カスタムキャンペーン追加ダイアログを新規モードで開くためのセンチネル(id 空)。 */
private val NEW_CUSTOM_CAMPAIGN = CustomCampaign(id = "", name = "")

/** トップレベルタブの表示定義。縦の下部 NavigationBar と横の NavigationRail で共用する。 */
private data class NavTabSpec(val tab: AppTab, val icon: ImageVector, val label: String)

// ---- 探す(検索)タブ ----

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SearchPane(
    query: String,
    categories: List<String>,
    selectedCategories: Set<String>,
    results: List<MainViewModel.SearchResult>,
    unrewardedNames: List<String>,
    dataStatus: String,
    refreshing: Boolean,
    municipalAreaNames: List<String>,
    searchInHeader: Boolean,
    compact: Boolean,
    selectedMerchantId: String?,
    onQueryChange: (String) -> Unit,
    onToggleCategory: (String) -> Unit,
    onSelect: (MainViewModel.SearchResult) -> Unit,
    onRefresh: () -> Unit,
    onOpenMunicipalCampaigns: () -> Unit,
    onRegisterCampaign: () -> Unit,
) {
    // 検索窓は横画面(1ペイン)では TopAppBar の SearchBarRow 側にあるため本文には置かない
    // (searchInHeader。二ペインはペイン幅の全幅フィールドをここに置く)。横画面(compact)は
    // 上部を圧縮して検索結果の高さを確保する(#54): カテゴリチップは折り返しをやめて 1 行の
    // 横スクロール(地図タブの NearbyFilterBar と同型)にし、データ状態行を省く
    if (!searchInHeader) {
        SearchQueryField(
            query = query,
            onQueryChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = "お店の名前(例: マック、サイゼ)",
        )
    }
    if (compact) {
        val chipScroll = rememberScrollState()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalFadingEdges(chipScroll)
                .horizontalScroll(chipScroll),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CategoryFilterChips(categories, selectedCategories, onToggleCategory)
        }
    } else {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            CategoryFilterChips(categories, selectedCategories, onToggleCategory)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                dataStatus,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.weight(1f),
            )
            RefreshAction(refreshing, onRefresh)
        }
    }
    Spacer(Modifier.height(4.dp))
    when {
        // 初期画面(検索前)。自治体施策のお知らせは検索・判定と混ざらないようここだけに出す。
        // 初期説明は横画面でも出す(検索結果が出れば消えるので、常設の圧迫にはならない)
        query.isBlank() && selectedCategories.isEmpty() -> {
            Text(
                "お店の名前を入力するか、カテゴリを選択すると、おトクな支払い方法を表示します。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline,
            )
            if (municipalAreaNames.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                MunicipalCampaignBanner(
                    areaNames = municipalAreaNames,
                    onClick = onOpenMunicipalCampaigns,
                )
            }
        }
        // 0 件の案内は原因で出し分ける(#70): カテゴリのみ / 収録済みだが今出せるキャンペーンが無い /
        // アプリ未収録。未収録のときはカスタムキャンペーン登録の導線を出す(未収録≠対象外なので、
        // 旧文言「対象外の可能性があります」のような判定していない断定はしない)
        results.isEmpty() && query.isBlank() -> Text(
            "選択中のカテゴリにお店がありません。",
            style = MaterialTheme.typography.bodyMedium,
        )
        results.isEmpty() && unrewardedNames.isNotEmpty() -> Text(
            "「${unrewardedNames.joinToString("・")}」は登録されていますが、いま利用できるキャンペーンがありません。",
            style = MaterialTheme.typography.bodyMedium,
        )
        results.isEmpty() -> Column {
            Text(
                "「$query」はまだこのアプリに登録されていないお店のようです。",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "キャンペーンをご存じなら、自分で登録して判定に使えます。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline,
            )
            Spacer(Modifier.height(12.dp))
            FilledTonalButton(onClick = onRegisterCampaign) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("キャンペーンを自分で登録")
            }
        }
        else -> LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(results, key = { it.merchant.id }) { result ->
                SearchResultCard(
                    result,
                    selected = result.merchant.id == selectedMerchantId,
                ) { onSelect(result) }
            }
        }
    }
}

/**
 * タイトル+検索窓+再取得の1行ヘッダ(#54)。横画面(1ペイン)の TopAppBar の title スロットに置く。
 * 検索窓はタイトル直後の左詰め——右寄せ(actions)にすると直下のカテゴリチップ行と分断されて
 * 「店名で検索 or ジャンルで絞る」の操作群に見えないため。二ペインでは使わない
 * (ペイン幅では検索窓が短くなりすぎるため、一覧ペイン先頭のタイトル行+全幅検索窓の2行構成)。
 */
@Composable
private fun SearchBarRow(
    query: String,
    onQueryChange: (String) -> Unit,
    refreshing: Boolean,
    onRefresh: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Text("ポイ活ナビ", style = MaterialTheme.typography.titleLarge)
        SearchQueryField(
            query = query,
            onQueryChange = onQueryChange,
            modifier = Modifier.width(320.dp),
            placeholder = "お店の名前(例: マック)",
        )
        RefreshAction(refreshing, onRefresh)
    }
}

/**
 * お店検索の入力フィールド。縦・二ペインの本文(全幅)と横1ペインの TopAppBar(320dp)で共用する。
 * placeholder は置き場所の幅に合わせて呼び出し側が選ぶ(狭い側は例を1つに省略)。
 */
@Composable
private fun SearchQueryField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier,
    placeholder: String,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier,
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        placeholder = { Text(placeholder, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        singleLine = true,
    )
}

/**
 * カテゴリ絞り込みチップの並び。お店タブの縦(FlowRow)・横(1行スクロール)と
 * 地図タブ(NearbyFilterBar)で同じ見た目・挙動を共有する。
 */
@Composable
internal fun CategoryFilterChips(
    categories: List<String>,
    selectedCategories: Set<String>,
    onToggleCategory: (String) -> Unit,
) {
    categories.forEach { category ->
        FilterChip(
            selected = category in selectedCategories,
            onClick = { onToggleCategory(category) },
            label = { Text(category) },
        )
    }
}

/**
 * データ再取得の入口。取得中はスピナーに差し替える。スピナーは IconButton(48dp)と枠を揃えて
 * 高さブレを防ぎ、アイコンはタッチ領域 48dp を保ったまま見た目だけ 20dp に抑える。
 * 縦画面はデータ状態行の右端、横1ペインは SearchBarRow、二ペイン横は一覧ペインのタイトル行で共用する。
 */
@Composable
private fun RefreshAction(refreshing: Boolean, onRefresh: () -> Unit) {
    if (refreshing) {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        }
    } else {
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

/**
 * お店タブの一覧+詳細二ペイン(M3 canonical layout の list-detail。#54)。
 * 窓が二ペイン相当(directive.maxHorizontalPartitions > 1)のときだけ呼ばれ、判定詳細(selection)と
 * 店舗判定(storeCheck)を全画面オーバーレイでなく右の詳細ペインに出す。店舗判定は詳細ペイン内で
 * 判定詳細と置き換わる(戻る/←で判定詳細へ)。全画面時に TopAppBar が担っていたタイトルと
 * 閉じる/戻るは PaneHeader がペイン内で肩代わりする(ui/DetailPanes.kt)。
 */
@Composable
private fun SearchListDetail(
    selection: MainViewModel.Selection?,
    storeCheck: MainViewModel.StoreCheckState?,
    directive: PaneScaffoldDirective,
    listPane: @Composable () -> Unit,
    onBack: () -> Unit,
    onOpenStoreCheck: () -> Unit,
    onCloseStoreCheck: () -> Unit,
    onStoreNameChange: (String) -> Unit,
    onFindNearby: () -> Unit,
    onExcludeStore: (campaignId: String, storeName: String) -> Unit,
    onRestoreExcludedStore: (campaignId: String) -> Unit,
    expiringNotices: List<ExpiringPointNotice>,
) {
    TabListDetailScaffold(
        directive = directive,
        listPane = { PaddedColumn(PaddingValues(start = 16.dp)) { listPane() } },
    ) {
        when {
            storeCheck != null -> StoreCheckPane(
                storeCheck = storeCheck,
                onClose = onCloseStoreCheck,
                onStoreNameChange = onStoreNameChange,
            )
            selection != null -> JudgmentDetailPane(
                selection = selection,
                onClose = onBack,
                onOpenStoreCheck = onOpenStoreCheck,
                onFindNearby = onFindNearby,
                onExcludeStore = onExcludeStore,
                onRestoreExcludedStore = onRestoreExcludedStore,
                expiringNotices = expiringNotices,
            )
            else -> PanePlaceholder("お店を選ぶと、おトクな支払い方法をここに表示します。")
        }
    }
}

/**
 * おトクタブの一覧+施策詳細二ペイン(M3 canonical layout の list-detail。#55)。
 * 骨格・分割判断は お店タブの [SearchListDetail] と同じで、右の詳細ペインに施策詳細
 * (CampaignDetail)を出す。施策詳細はタブ非依存のオーバーレイでもあるため、この Composable が
 * 受けるのは「おトクタブ表示中に開いた」ものだけ(他タブ発は従来どおり全画面オーバーレイ)。
 * カスタムキャンペーンの編集(CustomCampaignEditorScreen)は verticalScroll 付き全画面フォームで
 * 横画面でも成立しているため、二ペイン化せず従来どおり全画面オーバーレイのまま(#55 の追記)。
 */
@Composable
private fun CampaignsListDetail(
    selectedGroup: List<CampaignJudgment>?,
    merchants: Map<String, Merchant>,
    storeRates: Map<String, Map<String, Double>>,
    directive: PaneScaffoldDirective,
    listPane: @Composable () -> Unit,
    onBack: () -> Unit,
    onFindChains: (List<String>) -> Unit,
    onEditCustom: (() -> Unit)?,
    onDeleteCustom: (() -> Unit)?,
) {
    TabListDetailScaffold(
        directive = directive,
        listPane = { PaddedColumn(PaddingValues(start = 16.dp)) { listPane() } },
    ) {
        if (selectedGroup != null) {
            CampaignDetailPane(
                group = selectedGroup,
                merchants = merchants,
                storeRates = storeRates,
                onClose = onBack,
                onFindChains = onFindChains,
                onEditCustom = onEditCustom,
                onDeleteCustom = onDeleteCustom,
                // FAB(キャンペーンを自分で登録)は二ペインでも Scaffold 側に出したままの
                // ため、末尾まで送っても FAB に隠れない高さを空ける(一覧側と同じ 88dp)
                contentPadding = PaddingValues(bottom = 88.dp),
            )
        } else {
            PanePlaceholder("キャンペーンを選ぶと、詳細をここに表示します。")
        }
    }
}

/**
 * 施策詳細グループがカスタムキャンペーン由来なら、その登録内容(編集・削除の対象)を返す。
 * 登録内容は customCampaigns から引く。複数決済の展開 id は決済サフィックスを剥がした
 * 登録単位の id で逆引きする。全画面オーバーレイと二ペインの詳細ペインで共用する。
 */
private fun customCampaignSource(
    group: List<CampaignJudgment>,
    customCampaigns: List<CustomCampaign>,
): CustomCampaign? = group.firstOrNull()?.campaign
    ?.takeIf { it.isCustom }
    ?.let { c -> customCampaigns.firstOrNull { it.id == customCampaignBaseId(c.id) } }

/**
 * 設定タブのカテゴリ一覧+サブページ内容の二ペイン(M3 canonical layout の list-detail。#56)。
 * 骨格・分割判断は お店タブの [SearchListDetail] と同じで、右の詳細ペインに設定サブページ
 * ([subpageContent])を出す。ペイン内容は ListItem が自前で 16dp の余白を持つため
 * [PaddedColumn] は使わず、見出し行だけ画面端・ListItem のテキスト位置に合わせて寄せる。
 * 1 階層目のサブページは右端の✕で選択解除(未選択のプレースホルダに戻る)、2 階層目
 * (ライセンス・取得した地図データ)は左端の←で親カテゴリへ戻る(ペイン内置換)。
 */
@Composable
private fun SettingsListDetail(
    subpage: SettingsSubpage?,
    directive: PaneScaffoldDirective,
    listPane: @Composable () -> Unit,
    onClose: () -> Unit,
    subpageContent: @Composable (SettingsSubpage) -> Unit,
) {
    TabListDetailScaffold(
        directive = directive,
        listPane = {
            Column(Modifier.fillMaxSize()) {
                PaneHeader(title = "設定", modifier = Modifier.padding(start = 16.dp))
                listPane()
            }
        },
        // ListItem が自前で 16dp の余白を持つため、面の内側パディングは 0 にして
        // 見出し行だけ ListItem のテキスト位置に合わせて寄せる(縦画面と同じ扱い)
        detailContentPadding = PaddingValues(),
    ) {
        if (subpage != null) {
            // 2 階層目は親カテゴリへ戻る←(TopAppBar 様式)、1 階層目は選択解除の✕
            // (カード様式)。全画面時に TopAppBar が担っていた操作の置き換え
            if (subpage.parent != null) {
                PaneHeader(
                    title = subpage.title,
                    modifier = Modifier.padding(start = 4.dp),
                    leading = {
                        IconButton(onClick = onClose) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "${subpage.parent!!.title}に戻る",
                            )
                        }
                    },
                )
            } else {
                PaneHeader(
                    title = subpage.title,
                    modifier = Modifier.padding(start = 16.dp, end = 4.dp),
                    trailing = {
                        IconButton(onClick = onClose) {
                            Icon(Icons.Default.Close, contentDescription = "閉じる")
                        }
                    },
                )
            }
            subpageContent(subpage)
        } else {
            PanePlaceholder("設定したい項目を選ぶと、ここに表示します。")
        }
    }
}

/**
 * お店/おトク/設定タブ共通の list-detail 骨格(#54/#55/#56。重複していた 3 実装を #90 で集約)。
 * 二ペイン相当の窓でしか呼ばれず List/Detail とも常時 Expanded になるため destination は Detail 固定でよい
 * (一ペインへ縮んだ瞬間は外側の全画面オーバーレイ分岐が受ける)。一覧ペインの余白・見出しは
 * タブごとに違うため [listPane] 側が持ち、詳細ペインは [DetailPaneSurface] の面に [detailPane] を載せる。
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
private fun TabListDetailScaffold(
    directive: PaneScaffoldDirective,
    listPane: @Composable () -> Unit,
    detailContentPadding: PaddingValues = PaddingValues(horizontal = 16.dp),
    detailPane: @Composable ColumnScope.() -> Unit,
) {
    ListDetailPaneScaffold(
        directive = directive,
        value = calculateThreePaneScaffoldValue(
            maxHorizontalPartitions = directive.maxHorizontalPartitions,
            adaptStrategies = ListDetailPaneScaffoldDefaults.adaptStrategies(),
            currentDestination = ThreePaneScaffoldDestinationItem<Nothing>(ListDetailPaneScaffoldRole.Detail),
        ),
        listPane = { AnimatedPane { listPane() } },
        detailPane = { AnimatedPane { DetailPaneSurface(detailContentPadding, detailPane) } },
    )
}

/** 詳細ペインの未選択プレースホルダ(面の中に入れ、空の面で二ペイン構造を常時見せる) */
@Composable
private fun PanePlaceholder(text: String) {
    Centered {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
    }
}

/**
 * 二ペインの詳細ペインの面(#78)。両ペインが同じ surface 上に乗ると左右境界が見えないため、
 * 詳細ペインだけを surfaceContainerLow+全周 16dp 角丸の常設面として立てる(詳細だけが
 * container を持つ M3 canonical list-detail の形。[NearbyDetailSideSheet] と同系の塗り)。
 * 影は付けない: サイドシートは「地図の上に浮く」ため影 2dp を持つが、ペインは背景上の
 * 常設面なのでトーン差だけで示す(地図タブの右ペイン 320dp と同じ扱い)。ダークテーマで
 * surface とのトーン差が弱ければ、ここに outlineVariant の 1dp 枠を足せば 3 タブ一括で効く。
 * 従来ペイン内側にあった画面端側 16dp は面の外のマージンに移す(端に接すると角丸が切れて
 * 境界表現にならない)。ペイン間はライブラリの gutter(24dp)がそのまま空ける。
 * [contentPadding] は面の内側の余白で、既定は従来の [PaddedColumn] 相当の横 16dp。
 * 設定タブは ListItem が自前で 16dp を持つため 0 を渡す。未選択プレースホルダも面の中に
 * 入れる(空の面が常時見えて二ペイン構造が読める)。
 */
@Composable
private fun DetailPaneSurface(
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxSize().padding(end = 16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.fillMaxSize().padding(contentPadding), content = content)
    }
}

/**
 * お店タブ初期画面の自治体施策お知らせバナー。施策の中身は出さず「あること」だけ知らせ、
 * タップでおトクタブ(自治体フィルタ)へ送る。判定詳細(店舗カードタップ後)には出さない
 * (チェーン店は自治体施策の対象外が多く、店舗単位の断定はできないため)。
 */
@Composable
private fun MunicipalCampaignBanner(areaNames: List<String>, onClick: () -> Unit) {
    val areaLabel = if (areaNames.size <= 2) {
        areaNames.joinToString("・")
    } else {
        "${areaNames.take(2).joinToString("・")} 他${areaNames.size - 2}地域"
    }
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 48.dp)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                AppIcons.LocalOffer,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "${areaLabel}で自治体キャンペーン開催中",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "おトクタブで詳細を確認できます",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ---- 検索結果カード ----

/**
 * 検索結果カードの従属行。業態ヒットは所属グループ(「ドラッグストア・ツルハグループ」)、
 * グループ行(業態を持つ merchant)は内包業態を併記して、探している業態がどのカードに
 * 居るか画面内で分かるようにする(#60)。
 */
private fun searchResultSubtitle(result: MainViewModel.SearchResult): String {
    val merchant = result.merchant
    // カテゴリと業態情報は種類の違う情報なので「・」で並べず「 | 」で区切る
    // (全部「・」だとカテゴリ名が業態列挙に溶け込む。#62 実機フィードバック)
    if (result.bannerName != null) return "${merchant.category} | ${groupLabelOf(merchant)}"
    if (merchant.banners.isEmpty()) return merchant.category
    // 全列挙はカードが縦に伸びるため、先頭2業態+総数(代表看板を含む)にとどめる
    val names = merchant.banners.take(2).joinToString("・") { it.name }
    return "${merchant.category} | $names など${merchant.banners.size + 1}業態"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SearchResultCard(
    result: MainViewModel.SearchResult,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val fallback = MaterialTheme.colorScheme.primary
    val stripeColors = result.brandColors
        .mapNotNull { parseBrandColor(it) }
        .ifEmpty { listOf(fallback) }
    val separatorColor = MaterialTheme.colorScheme.surfaceContainerHigh
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        // 二ペイン時に詳細ペインへ出している行のハイライト(M3 list-detail の定石)。一覧のみの表示では常に false
        colors = if (selected) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        } else {
            CardDefaults.cardColors()
        },
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        // 本文が高さを決め、左端ストライプは matchParentSize で全高に追従する。
        // Row(IntrinsicSize.Min) だと店名+バッジの FlowRow が折り返したときに
        // 2行目がカード高さからクリップされるため使わない
        Box {
            Row(
                modifier = Modifier.padding(start = 20.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    // 店名が長くバッジが幅に入らないときは潰さず折り返して次の行に出す
                    FlowRow(
                        itemVerticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        // 業態ヒットは主ラベルを業態名で出す(「杏林堂」で検索して「ツルハドラッグ」と
                        // 出る食い違いを避ける)。グループ名は従属表示で学べる
                        Text(result.bannerName ?: result.merchant.name, style = MaterialTheme.typography.bodyLarge)
                        if (result.hasTimeLimited) {
                            TimeLimitedBadge()
                        }
                    }
                    Text(
                        searchResultSubtitle(result),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    result.bestBenefit?.let {
                        Text(
                            it.toString(),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    if (result.campaignCount > 1) {
                        Text(
                            "${result.campaignCount}件のキャンペーン",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Box(Modifier.matchParentSize()) {
                StripeBar(stripeColors, separatorColor)
            }
        }
    }
}
