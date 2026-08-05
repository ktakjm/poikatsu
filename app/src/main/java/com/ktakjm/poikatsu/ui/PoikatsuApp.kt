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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import com.ktakjm.poikatsu.data.Campaign
import com.ktakjm.poikatsu.data.CustomCampaign
import com.ktakjm.poikatsu.data.CustomCard
import com.ktakjm.poikatsu.data.Merchant
import com.ktakjm.poikatsu.domain.CampaignJudgment
import com.ktakjm.poikatsu.domain.customCampaignBaseId
import com.ktakjm.poikatsu.domain.isCustom
import com.ktakjm.poikatsu.ui.theme.AppIcons
import com.ktakjm.poikatsu.util.GeoMath

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
    if (selectedTab == AppTab.NEARBY && state.nearby != null) {
        val searchStamp = state.nearby?.searchStamp
        LaunchedEffect(searchStamp) {
            lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.observeLocationUpdates()
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

    // 全画面オーバーレイとして扱う店舗判定・判定詳細・施策詳細。二ペイン時は詳細ペイン内の表示なので
    // null になり、topBar・本文のオーバーレイ分岐と baseTabsVisible を素通りして
    // SearchListDetail / CampaignsListDetail が受ける
    val overlayStoreCheck = state.storeCheck?.takeUnless { searchTwoPane }
    val overlaySelection = state.selection?.takeUnless { searchTwoPane }
    val overlayCampaignGroup = state.selectedCampaignGroup?.takeUnless { campaignsTwoPane }

    // 下位画面(詳細/店舗判定/キャンペーン詳細/カスタムキャンペーン編集/設定サブページ)や
    // ロード・エラーに重なっていないベースのタブ表示状態。下部ナビ・FAB の表示条件。
    val baseTabsVisible = !state.loading && state.error == null &&
        overlaySelection == null && overlayStoreCheck == null &&
        overlayCampaignGroup == null && state.settingsSubpage == null &&
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

    // 横画面では下部タブを左端の NavigationRail に置き換え、縦方向の占有をなくす(#4)。
    // M3 の定石(横長・中幅以上は Rail)。判定は WindowSizeClass でなく window の向きで足りる
    // (回転で Activity が再生成されるため、その場の Configuration を見ればよい)
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

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
                        val title = campaignGroupDisplayTitle(group.first().campaign, state.merchantNames)
                        TopAppBar(
                            title = { Text(title) },
                            navigationIcon = {
                                IconButton(onClick = viewModel::onCloseCampaignDetail) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                                }
                            },
                        )
                    }
                    state.settingsSubpage != null -> TopAppBar(
                        title = { Text(state.settingsSubpage!!.title) },
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
            val isMap = baseTabsVisible && selectedTab == AppTab.NEARBY && state.nearby != null
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
            val stableOriginName = remember { mutableStateOf(state.nearbyOrigin?.name) }
            if (state.nearby?.loading != true) {
                stableOriginName.value = state.nearbyOrigin?.name
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
                                    viewModel.onFindNearby(it)
                                    onNearbyClick()
                                }
                            },
                            onExcludeStore = viewModel::onExcludeStore,
                            onRestoreExcludedStore = viewModel::onRestoreExcludedStore,
                        )
                    }
                    // キャンペーン詳細(タブ非依存のオーバーレイ)。topBar の分岐順と一致させること
                    overlayCampaignGroup != null -> PaddedColumn {
                        val customSource = customCampaignSource(overlayCampaignGroup, state.customCampaigns)
                        CampaignDetail(
                            judgments = overlayCampaignGroup,
                            merchants = state.merchantsById,
                            onBack = viewModel::onCloseCampaignDetail,
                            onFindChains = { ids ->
                                viewModel.onFindNearbyByIds(ids)
                                onNearbyClick()
                            },
                            onEditCustom = customSource?.let { { editingCustomCampaign = it } },
                            onDeleteCustom = customSource?.let { { deletingCustomCampaign = it } },
                        )
                    }
                    state.settingsSubpage != null -> when (state.settingsSubpage!!) {
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
                            onBack = viewModel::onCloseSettingsSubpage,
                            onCardOwnedChange = viewModel::onSetCardOwned,
                            onCardRateChange = viewModel::onSetCardRate,
                            onCardBrandChange = viewModel::onSetCardBrand,
                            onCardWelcatsuChange = viewModel::onSetCardWelcatsu,
                            onAddCustomCard = viewModel::onAddCustomCard,
                            onUpdateCustomCard = viewModel::onUpdateCustomCard,
                            onRemoveCustomCard = viewModel::onRemoveCustomCard,
                            onBrandOwnedChange = viewModel::onSetBrandOwned,
                            onQrEnabledChange = viewModel::onSetQrEnabled,
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
                            onBack = viewModel::onCloseSettingsSubpage,
                            onDeveloperModeChange = viewModel::onSetDeveloperMode,
                            onDataCommitRefChange = viewModel::onSetDataCommitRef,
                            onUseTestDataChange = viewModel::onSetUseTestData,
                            onUseBundledDataChange = viewModel::onSetUseBundledData,
                            onTestNotification = viewModel::onTestNotification,
                            onClearNotifiedCampaigns = viewModel::onClearNotifiedCampaigns,
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
                    selectedTab == AppTab.NEARBY -> {
                        val nearby = state.nearby
                        if (nearby != null) {
                            NearbyPane(
                                nearby = nearby,
                                categories = state.categories,
                                selectedCategories = state.nearbySelectedCategories,
                                merchantFilters = state.nearbyMerchantFilters,
                                searchFailed = state.nearbySearchFailed,
                                originName = stableOriginName.value,
                                geocodeCandidates = state.geocodeCandidates,
                                isGeocoding = state.isGeocoding,
                                onClose = viewModel::onCloseNearby,
                                onToggleCategory = viewModel::onToggleNearbyCategory,
                                onToggleChain = viewModel::onToggleNearbyLens,
                                onReload = viewModel::fetchNearby,
                                onSearchFailedShown = viewModel::onNearbySearchFailedShown,
                                onPreviewPlace = viewModel::onPreviewNearby,
                                onClearPreview = viewModel::onClearNearbyPreview,
                                onOpenDetail = viewModel::onSelectNearby,
                                onSearchHere = viewModel::searchHere,
                                onGeocode = viewModel::onGeocode,
                                onSelectCandidate = viewModel::onSelectGeocodedPlace,
                                onClearOrigin = viewModel::onClearOrigin,
                                onDismissSearch = viewModel::onDismissGeocoding,
                                onOpenMunicipalGroup = viewModel::onSelectCampaignGroup,
                                topInset = innerPadding.calculateTopPadding(),
                            )
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
                                merchantNames = state.merchantNames,
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
                                merchantNames = state.merchantNames,
                                merchants = state.merchantsById,
                                directive = paneDirective,
                                listPane = {
                                    // 二ペインはグローバル TopAppBar を持たない(詳細ペイン全高のため)ので、
                                    // タイトル行を一覧ペイン先頭に置く(お店タブと同じ様式。#55)
                                    PaneHeader(title = "おトク")
                                    campaignPane()
                                },
                                onBack = viewModel::onCloseCampaignDetail,
                                onFindChains = { ids ->
                                    viewModel.onFindNearbyByIds(ids)
                                    onNearbyClick()
                                },
                                onEditCustom = customSource?.let { { editingCustomCampaign = it } },
                                onDeleteCustom = customSource?.let { { deletingCustomCampaign = it } },
                            )
                        } else {
                            PaddedColumn { campaignPane() }
                        }
                    }
                    selectedTab == AppTab.SETTINGS -> SettingsScreen(
                        displaySummary = displaySettingsSummary(
                            state.themeMode,
                            state.dynamicColor,
                            dynamicSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
                        ),
                        paymentSummary = paymentMethodsSummary(
                            cardCount = state.cardSettings.count { it.owned } + state.customCards.size,
                            brandCount = state.brandSettings.count { it.owned },
                            qrCount = state.qrPaymentSettings.count { it.enabled },
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
                        onOpenSubpage = viewModel::onOpenSettingsSubpage,
                    )
                    else -> {
                        // お店タブ。二ペイン相当の窓なら一覧(左)+判定詳細(右)の list-detail、
                        // 一ペインなら従来どおり一覧のみ(詳細は上の全画面オーバーレイ分岐が受ける)
                        val searchPane: @Composable () -> Unit = {
                            SearchPane(
                                query = state.query,
                                categories = state.categories,
                                selectedCategories = state.selectedCategories,
                                results = state.results,
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
                                        viewModel.onFindNearby(it)
                                        onNearbyClick()
                                    }
                                },
                                onExcludeStore = viewModel::onExcludeStore,
                                onRestoreExcludedStore = viewModel::onRestoreExcludedStore,
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
        results.isEmpty() -> Text(
            if (query.isBlank()) "選択中のカテゴリにお店がありません。"
            else "「$query」に一致するお店が見つかりませんでした。登録済みの高還元キャンペーンの対象外の可能性があります。",
            style = MaterialTheme.typography.bodyMedium,
        )
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
private fun CategoryFilterChips(
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

/** 判定詳細のタイトル。全画面時の TopAppBar と二ペインの詳細ペインヘッダで共用する。 */
private fun selectionTitle(selection: MainViewModel.Selection): String =
    selection.displayName ?: selection.merchant.name

/** 店舗判定のタイトル。全画面時の TopAppBar と二ペインの詳細ペインヘッダで共用する。 */
private fun storeCheckTitle(storeCheck: MainViewModel.StoreCheckState): String =
    "${storeCheck.merchant.name} 対象判定"

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
 * 閉じる/戻るは DetailPaneHeader がペイン内で肩代わりする。
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
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
) {
    ListDetailPaneScaffold(
        directive = directive,
        value = calculateThreePaneScaffoldValue(
            maxHorizontalPartitions = directive.maxHorizontalPartitions,
            adaptStrategies = ListDetailPaneScaffoldDefaults.adaptStrategies(),
            // この Composable は二ペイン時にしか呼ばれず List/Detail とも常時 Expanded になるため
            // destination は固定でよい(一ペインへ縮んだ瞬間は外側の全画面オーバーレイ分岐が受ける)
            currentDestination = ThreePaneScaffoldDestinationItem<Nothing>(ListDetailPaneScaffoldRole.Detail),
        ),
        listPane = {
            AnimatedPane {
                // 画面端の側だけ 16dp の余白(縦画面の PaddedColumn と同じ)。ペイン間は
                // ライブラリの gutter が空ける
                PaddedColumn(PaddingValues(start = 16.dp)) { listPane() }
            }
        },
        detailPane = {
            AnimatedPane {
                when {
                    storeCheck != null -> PaddedColumn(PaddingValues(end = 16.dp)) {
                        PaneHeader(
                            title = storeCheckTitle(storeCheck),
                            leading = {
                                IconButton(onClick = onCloseStoreCheck) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = "判定詳細に戻る",
                                    )
                                }
                            },
                        )
                        StoreCheckScreen(
                            storeCheck = storeCheck,
                            onBack = onCloseStoreCheck,
                            onStoreNameChange = onStoreNameChange,
                        )
                    }
                    selection != null -> PaddedColumn(PaddingValues(end = 16.dp)) {
                        PaneHeader(
                            title = selectionTitle(selection),
                            trailing = {
                                IconButton(onClick = onBack) {
                                    Icon(Icons.Default.Close, contentDescription = "詳細を閉じる")
                                }
                            },
                        )
                        JudgmentDetail(
                            selection = selection,
                            onBack = onBack,
                            onOpenStoreCheck = onOpenStoreCheck,
                            onFindNearby = onFindNearby,
                            onExcludeStore = onExcludeStore,
                            onRestoreExcludedStore = onRestoreExcludedStore,
                        )
                    }
                    else -> Centered {
                        Text(
                            "お店を選ぶと、おトクな支払い方法をここに表示します。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            }
        },
    )
}

/**
 * おトクタブの一覧+施策詳細二ペイン(M3 canonical layout の list-detail。#55)。
 * 骨格・分割判断は お店タブの [SearchListDetail] と同じで、右の詳細ペインに施策詳細
 * (CampaignDetail)を出す。施策詳細はタブ非依存のオーバーレイでもあるため、この Composable が
 * 受けるのは「おトクタブ表示中に開いた」ものだけ(他タブ発は従来どおり全画面オーバーレイ)。
 * カスタムキャンペーンの編集(CustomCampaignEditorScreen)は verticalScroll 付き全画面フォームで
 * 横画面でも成立しているため、二ペイン化せず従来どおり全画面オーバーレイのまま(#55 の追記)。
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
private fun CampaignsListDetail(
    selectedGroup: List<CampaignJudgment>?,
    merchantNames: Map<String, String>,
    merchants: Map<String, Merchant>,
    directive: PaneScaffoldDirective,
    listPane: @Composable () -> Unit,
    onBack: () -> Unit,
    onFindChains: (List<String>) -> Unit,
    onEditCustom: (() -> Unit)?,
    onDeleteCustom: (() -> Unit)?,
) {
    ListDetailPaneScaffold(
        directive = directive,
        value = calculateThreePaneScaffoldValue(
            maxHorizontalPartitions = directive.maxHorizontalPartitions,
            adaptStrategies = ListDetailPaneScaffoldDefaults.adaptStrategies(),
            // 二ペイン時にしか呼ばれず List/Detail とも常時 Expanded になるため destination は固定
            // でよい(SearchListDetail と同じ理由)
            currentDestination = ThreePaneScaffoldDestinationItem<Nothing>(ListDetailPaneScaffoldRole.Detail),
        ),
        listPane = {
            AnimatedPane {
                PaddedColumn(PaddingValues(start = 16.dp)) { listPane() }
            }
        },
        detailPane = {
            AnimatedPane {
                if (selectedGroup != null) {
                    PaddedColumn(PaddingValues(end = 16.dp)) {
                        PaneHeader(
                            title = campaignGroupDisplayTitle(selectedGroup.first().campaign, merchantNames),
                            trailing = {
                                IconButton(onClick = onBack) {
                                    Icon(Icons.Default.Close, contentDescription = "詳細を閉じる")
                                }
                            },
                        )
                        CampaignDetail(
                            judgments = selectedGroup,
                            merchants = merchants,
                            onBack = onBack,
                            onFindChains = onFindChains,
                            onEditCustom = onEditCustom,
                            onDeleteCustom = onDeleteCustom,
                            // FAB(キャンペーンを自分で登録)は二ペインでも Scaffold 側に出したままの
                            // ため、末尾まで送っても FAB に隠れない高さを空ける(一覧側と同じ 88dp)
                            contentPadding = PaddingValues(bottom = 88.dp),
                        )
                    }
                } else {
                    Centered {
                        Text(
                            "キャンペーンを選ぶと、詳細をここに表示します。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            }
        },
    )
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
 * ペインの見出し行(#54)。全画面時に TopAppBar が担うタイトルと操作をペイン内で置き換える。
 * 一覧ペインはタイトル+再取得(trailing)、判定詳細は右端の✕(ペインを閉じる=カード様式)、
 * 店舗判定は左端の←(1 段深い画面から判定詳細へ戻る=TopAppBar 様式)。
 */
@Composable
private fun PaneHeader(
    title: String,
    leading: @Composable () -> Unit = {},
    trailing: @Composable () -> Unit = {},
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        leading()
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f),
        )
        trailing()
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

// ---- 近く(地図)タブ ----

/**
 * 「お店で絞る」ピッカーの1系列ぶん。banners は周辺に在る業態ごとの(レンズ, 件数)。
 * 業態が2つ以上在る系列だけグループ見出し+業態行で束ねて出す(初期状態から展開済み)。
 */
private data class PresentGroup(
    val merchant: Merchant,
    val total: Int,
    val banners: List<Pair<MainViewModel.NearbyLens, Int>>,
)

/**
 * 複合ピン/クラスタをタップしたときにボトムシートへ出す店舗グループ。
 * sameSpot=true は同一地点(同一ビル等の複合ピン、ズームで分解できないクラスタ)で
 * 「同じ場所に N 件」、false は付近に散らばるクラスタで「この付近に N 件」と見出しを変える。
 */
private data class PlaceGroupSheet(
    val places: List<MainViewModel.NearbyPlace>,
    val sameSpot: Boolean,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NearbyPane(
    nearby: MainViewModel.NearbyUi,
    categories: List<String>,
    selectedCategories: Set<String>,
    merchantFilters: Set<MainViewModel.NearbyLens>,
    searchFailed: String?,
    originName: String?,
    geocodeCandidates: List<MainViewModel.GeocodedPlace>,
    isGeocoding: Boolean,
    onClose: () -> Unit,
    onReload: () -> Unit,
    onSearchFailedShown: () -> Unit,
    onToggleCategory: (String) -> Unit,
    onToggleChain: (MainViewModel.NearbyLens) -> Unit,
    onPreviewPlace: (MainViewModel.NearbyPlace) -> Unit,
    onClearPreview: () -> Unit,
    onOpenDetail: (MainViewModel.NearbyPlace) -> Unit,
    onSearchHere: (Double, Double, Int, Double) -> Unit,
    onGeocode: (String) -> Unit,
    onSelectCandidate: (MainViewModel.GeocodedPlace) -> Unit,
    onClearOrigin: () -> Unit,
    onDismissSearch: () -> Unit,
    onOpenMunicipalGroup: (List<Campaign>) -> Unit,
    topInset: Dp,
) {
    val selectedPlace = nearby.selectedPlace
    // 複合ピン/クラスタをタップしたときの選択状態(BottomSheet で内包店舗をリスト表示)
    var placeGroup by remember { mutableStateOf<PlaceGroupSheet?>(null) }
    LaunchedEffect(selectedPlace) { if (selectedPlace != null) placeGroup = null }
    // 再検索(現在地ボタン/このエリアを検索)が始まったらグループシートも閉じる。ViewModel は
    // selectedPlace をクリアするが placeGroup はこの Composable のローカル状態なので、ここで
    // 閉じないと新しい検索結果に無関係な古いグループリストがシートに残り続ける
    LaunchedEffect(nearby.loading) { if (nearby.loading) placeGroup = null }
    // 戻る: プレビュー → グループリスト → 一覧 → モード閉じ の順に遡る
    BackHandler(onBack = {
        when {
            selectedPlace != null -> onClearPreview()
            placeGroup != null -> placeGroup = null
            else -> onClose()
        }
    })

    val center = if (nearby.centerLat != null && nearby.centerLon != null) {
        MapPoint(nearby.centerLat, nearby.centerLon)
    } else null

    // 地図(中心)がまだ無い初回ロード/エラー時だけ、地図なしの全画面表示にする。
    // 中心が既にあれば再検索中でも地図・一覧は残し、進捗は地図上に小さく重ねる(NearbyMap の loadingMessage)。
    if (center == null || nearby.error != null) {
        // 地図なしの全画面状態(地図が出る前のロード/エラー)。地図モードはタイトルバーを持たない
        // (full-bleed)ので、ここでも見出しは出さず内容だけを中央に出す。
        // 地図表示への切替で見出しが消える中途半端な見えを防ぐ。下部ナビは残るのでモード/設定への
        // 導線は保たれる(設定は「設定」タブから)。
        Centered {
            when {
                nearby.loading -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    // リングは地図ではなく「現在地の測位」→「YOLP で周辺店舗取得」を待っている。
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
    // お店絞り込み(merchantFilters)はジャンルより優先。未指定なら参照同一で再計算を避ける。
    val visiblePlaces = remember(nearby.places, selectedCategories, merchantFilters) {
        when {
            merchantFilters.isNotEmpty() ->
                nearby.places.filter { p -> merchantFilters.any { it.matches(p) } }
            selectedCategories.isEmpty() -> nearby.places
            else -> nearby.places.filter { it.merchant?.category in selectedCategories }
        }
    }
    // 「お店で絞る」ピッカー用: いま(ジャンル絞り込み後の)周辺に在る系列と、その配下の業態別件数。
    // 多い順→読み順。全体ではなく周辺に在るものだけ出す(「地図」の約束)。絞り込み中もピッカーは残し、
    // 追加・解除を続けられる。
    val presentChains = remember(nearby.places, selectedCategories) {
        nearby.places
            .filter { selectedCategories.isEmpty() || it.merchant?.category in selectedCategories }
            .filter { it.merchant != null }
            .groupBy { it.merchant!!.id }
            .values
            .map { places ->
                val merchant = places.first().merchant!!
                val banners = places
                    .groupingBy { it.bannerId ?: merchant.id }.eachCount()
                    .entries
                    .sortedByDescending { it.value }
                    .map { (bannerId, count) -> MainViewModel.NearbyLens(merchant, bannerId) to count }
                PresentGroup(merchant, places.size, banners)
            }
            .sortedWith(compareByDescending<PresentGroup> { it.total }.thenBy { it.merchant.reading })
    }
    // 同一地点(10m 以内・連結成分)の店舗をグルーピングし、複合マーカーで表示する。
    // タップでズーム分解できない重なりを、クラスタバッジと同じ見た目で一つにまとめる。
    // markerGroups はマーカー座標→グループ内店舗の逆引きで、ライブラリクラスタの
    // タップ時に内包店舗のリスト(onClusterOpen)へ展開するのに使う。
    val (markers, markerGroups) = remember(visiblePlaces, selectedPlace) {
        val groups = groupByProximity(visiblePlaces)
        val byPoint = HashMap<MapPoint, List<MainViewModel.NearbyPlace>>()
        val built = groups.map { group ->
            val rep = group[0]
            val point = MapPoint(rep.lat, rep.lon)
            byPoint[point] = group
            if (group.size == 1) {
                MapMarker(
                    point = point,
                    label = rep.name,
                    colorHexes = rep.brandColors,
                    selected = rep == selectedPlace,
                    onClick = { onPreviewPlace(rep) },
                )
            } else {
                MapMarker(
                    point = point,
                    label = "${group.size}件",
                    colorHexes = group.flatMap { it.brandColors }.distinct(),
                    selected = group.any { it == selectedPlace },
                    onClick = {
                        onClearPreview()
                        // 並び順はクラスタタップ時と同じ「起点からの距離」(各行の距離ラベルと一致)
                        placeGroup = PlaceGroupSheet(group.sortedBy { it.distanceMeters }, sameSpot = true)
                    },
                    groupSize = group.size,
                )
            }
        }
        built to byPoint
    }

    // 再検索の一時失敗は地図を残したまま Snackbar で通知する。外側 Scaffold の host は下部シート/
    // 右ペインの裏に隠れるため、地図領域に出す専用の host を使う
    // (縦は BottomSheetScaffold の host、横は地図に重ねた SnackbarHost)。
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(searchFailed) {
        if (searchFailed != null) {
            snackbarHostState.showSnackbar(searchFailed)
            onSearchFailedShown()
        }
    }

    // 地図本体。縦(シート背面)・横(中央の地図領域)で同じ呼び出しを共有する。
    // bottomPadding は縦=シート peek(Google ロゴ・現在地ボタンを peek 上へ逃がす)、横=0。
    val mapContent: @Composable (Dp) -> Unit = { mapBottomPadding ->
        NearbyMap(
            center = center,
            userLocation = userLocation,
            markers = markers,
            initialZoom = nearby.zoom,
            searchStamp = nearby.searchStamp,
            selectedPoint = selectedPlace?.let { MapPoint(it.lat, it.lon) },
            onSearchHere = { p, r, z -> onSearchHere(p.lat, p.lon, r, z) },
            onSearchMyLocation = onReload,
            onClusterOpen = { clusterMarkers, sameSpot ->
                // クラスタ内の店舗に展開する。並び順は各行の距離ラベルと同じ「起点からの距離」
                // (distanceFromCenter は旧検索中心基準のため、クラスタ内の並びとしては不自然)
                val places = clusterMarkers
                    .flatMap { markerGroups[it.point].orEmpty() }
                    .sortedBy { it.distanceMeters }
                if (places.isNotEmpty()) {
                    onClearPreview()
                    placeGroup = PlaceGroupSheet(places, sameSpot)
                }
            },
            loadingMessage = if (nearby.loading) nearbyLoadingText(nearby.loadingPhase) else null,
            originName = originName,
            geocodeCandidates = geocodeCandidates,
            isGeocoding = isGeocoding,
            onGeocode = onGeocode,
            onSelectCandidate = onSelectCandidate,
            onClearOrigin = onClearOrigin,
            onDismissSearch = onDismissSearch,
            municipalNoticeText = nearby.municipalNotice?.let { "${it.label}のキャンペーン開催中" },
            onMunicipalNoticeClick = {
                nearby.municipalNotice?.let { onOpenMunicipalGroup(it.campaigns) }
            },
            modifier = Modifier.fillMaxSize(),
            topInset = topInset,
            bottomPadding = mapBottomPadding,
        )
    }
    // シート/ペインの中身(一覧・プレビュー・グループの 3 状態)。縦横で共有する。
    val paneContent: @Composable (Dp?, ((Dp) -> Unit)?) -> Unit = { paneMaxHeight, onMeasured ->
        NearbySheetContent(
            nearby = nearby,
            selectedPlace = selectedPlace,
            placeGroup = placeGroup,
            visiblePlaces = visiblePlaces,
            presentChains = presentChains,
            categories = categories,
            selectedCategories = selectedCategories,
            merchantFilters = merchantFilters,
            originName = originName,
            onToggleCategory = onToggleCategory,
            onToggleChain = onToggleChain,
            onPreviewPlace = onPreviewPlace,
            onClearPreview = onClearPreview,
            onOpenDetail = onOpenDetail,
            onCloseGroup = { placeGroup = null },
            maxHeight = paneMaxHeight,
            onMeasured = onMeasured,
        )
    }

    // 横画面: 地図(中央・全高)+お店リスト(右 320dp 固定ペイン)の二ペイン構成(#4)。
    // peek 220dp のボトムシートでは横持ちの高さの半分以上を塞ぐため使わず、
    // ペイン固定になるぶんシート特有の状態遷移(peek/partialExpand)も無くなる。
    // 判定は外側の NavigationRail 分岐と同じ window の向き(食い違うとレイアウトが噛み合わない)。
    if (LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE) {
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxHeight()) {
                mapContent(0.dp)
                SnackbarHost(snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
            }
            // シートと同じ面色でペインを立てる。full-bleed でステータスバー裏まで届くため
            // 内容だけ topInset で避ける
            Surface(
                modifier = Modifier.width(NEARBY_PANE_WIDTH).fillMaxHeight(),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Column(Modifier.padding(top = topInset)) {
                    paneContent(null, null)
                }
            }
        }
        return
    }

    // 縦画面: 地図を全面に出し、店舗リストは引き上げ式のボトムシートに収める。
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
    LaunchedEffect(placeGroup) {
        if (placeGroup != null &&
            scaffoldState.bottomSheetState.currentValue != SheetValue.PartiallyExpanded
        ) {
            scaffoldState.bottomSheetState.partialExpand()
        }
    }
    // ボトムシートの覗き高さ。地図(上)とシート(下)の取り分。地図下端の余白(Google ロゴを peek 上に
    // 逃がす bottomPadding)もこの値に合わせる。
    // プレビュー(店舗選択中)は内容を実測し、220 で収まらない端末(フォント倍率・長い店名)では
    // 「詳細を確認」下端が欠けないよう覗き高さを内容まで伸ばす。収まるなら従来どおり 220 のまま。
    val listPeek = 220.dp
    var previewSheetPeek by remember { mutableStateOf<Dp?>(null) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // シート展開時の最大高さ: 検索バー上端まで(検索バーは覆う)。
        // 検索バーは topInset + 8.dp から始まるので、そこをシートの上限にする。
        val sheetMaxHeight = maxHeight - topInset - 16.dp
        // グループリストは件数分だけ内容が伸びるため、覗き高さは画面の約4割で頭打ちにして
        // 地図(タップしたクラスタ)が隠れないようにする。続きはシートを引き上げて見る。
        val groupPeekMax = maxHeight * 0.4f
        val sheetPeek = when {
            selectedPlace != null -> previewSheetPeek?.let { maxOf(listPeek, it) } ?: listPeek
            placeGroup != null ->
                previewSheetPeek?.let { maxOf(listPeek, minOf(it, groupPeekMax)) } ?: listPeek
            else -> listPeek
        }
        BottomSheetScaffold(
            scaffoldState = scaffoldState,
            sheetPeekHeight = sheetPeek,
            // 既定ハンドルは上下余白が厚く直下のクレジットが間延びするため、縦を詰めた小ぶりのものにする
            sheetDragHandle = { CompactDragHandle() },
            snackbarHost = { SnackbarHost(snackbarHostState) },
            // 地図を画面上端(ステータスバー裏)まで全面表示する full-bleed。タイトルバーは持たない。
            sheetContent = {
                // 覗き高さは帰属表示込みの内容実測にドラッグハンドル分を足す(プレビュー/グループで使用)
                paneContent(sheetMaxHeight) { measured ->
                    previewSheetPeek = measured + COMPACT_HANDLE_HEIGHT
                }
            },
        ) { _ ->
            // 地図は全面(full-bleed)。上端はステータスバー裏まで、下端はシート(peek)背面まで描き、
            // 角丸や端から背景が覗くのを防ぐ。ステータスバーと重なる浮きコントロールは topInset で避ける。
            mapContent(sheetPeek)
        }
    }
}

/** 横画面の右ペイン(お店リスト)の幅。残りが地図(中央・全高)の取り分になる。 */
private val NEARBY_PANE_WIDTH = 320.dp

/**
 * 地図タブの店舗パネルの中身(一覧 / プレビュー / グループの 3 状態)。
 * 縦はボトムシートの sheetContent、横は右ペインの中身として共用する。
 *
 * @param maxHeight 縦: シート展開時の上限(検索バー上端まで)。横: null(全高のペインに任せる)。
 * @param onMeasured 縦: プレビュー/グループの内容実測を peek 算出へ返す。横: 不要なので null。
 */
@Composable
private fun NearbySheetContent(
    nearby: MainViewModel.NearbyUi,
    selectedPlace: MainViewModel.NearbyPlace?,
    placeGroup: PlaceGroupSheet?,
    visiblePlaces: List<MainViewModel.NearbyPlace>,
    presentChains: List<PresentGroup>,
    categories: List<String>,
    selectedCategories: Set<String>,
    merchantFilters: Set<MainViewModel.NearbyLens>,
    originName: String?,
    onToggleCategory: (String) -> Unit,
    onToggleChain: (MainViewModel.NearbyLens) -> Unit,
    onPreviewPlace: (MainViewModel.NearbyPlace) -> Unit,
    onClearPreview: () -> Unit,
    onOpenDetail: (MainViewModel.NearbyPlace) -> Unit,
    onCloseGroup: () -> Unit,
    maxHeight: Dp?,
    onMeasured: ((Dp) -> Unit)?,
) {
    val density = LocalDensity.current
    val measured = if (onMeasured != null) {
        Modifier.onSizeChanged { onMeasured(with(density) { it.height.toDp() }) }
    } else {
        Modifier
    }
    val frame = if (maxHeight != null) Modifier.heightIn(max = maxHeight) else Modifier
    if (selectedPlace != null) {
        // 選択中: 地図を残したまま店舗情報をプレビュー。判定詳細へはここから明示遷移する。
        Column(modifier = measured) {
            SheetAttribution()
            NearbyPreview(
                place = selectedPlace,
                originName = originName,
                onOpenDetail = { onOpenDetail(selectedPlace) },
                onClose = onClearPreview,
            )
        }
    } else if (placeGroup != null) {
        // 複合ピン/クラスタをタップ: 内包する店舗をリストで見せる。
        // 縦の覗き高さは内容の実測(少数件は全件見せる)。多数件は sheetPeek 側で頭打ちにし、
        // シートを引き上げるとリスト内スクロールで続きを見られる。
        Column(modifier = frame.then(measured)) {
            SheetAttribution()
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (placeGroup.sameSpot) {
                            "同じ場所に ${placeGroup.places.size} 件"
                        } else {
                            "この付近に ${placeGroup.places.size} 件"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onCloseGroup) {
                        Icon(Icons.Default.Close, contentDescription = "閉じる")
                    }
                }
                LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false)) {
                    items(placeGroup.places, key = { "${it.lat},${it.lon},${it.name}" }) { place ->
                        ListItem(
                            headlineContent = { Text(place.name) },
                            supportingContent = {
                                Text("${distanceLabel(place.distanceMeters, originName)}・${place.merchant?.category.orEmpty()}")
                            },
                            trailingContent = {
                                place.bestBenefit?.let {
                                    Text(
                                        it.toString(),
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPreviewPlace(place) },
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    } else {
        Column(frame) {
            SheetAttribution()
            if (nearby.places.isNotEmpty() || merchantFilters.isNotEmpty()) {
                NearbyFilterBar(
                    categories = categories,
                    selectedCategories = selectedCategories,
                    presentChains = presentChains,
                    merchantFilters = merchantFilters,
                    onToggleCategory = onToggleCategory,
                    onToggleChain = onToggleChain,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                Spacer(Modifier.height(8.dp))
            }
            if (visiblePlaces.isEmpty()) {
                if (nearby.loading) {
                    // 現在地確定→地図先出しの直後(結果待ち)。「見つからない」と誤読させない
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            nearbyLoadingText(nearby.loadingPhase),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                } else {
                    Text(
                        when {
                            merchantFilters.isNotEmpty() ->
                                merchantFilters.joinToString("") { "「${it.label}」" } +
                                    "はこの範囲にありません。地図を動かすか、絞り込みを解除してください。"
                            nearby.places.isEmpty() ->
                                "この範囲に対象キャンペーンのあるお店が見つかりませんでした。地図を動かして探してください。"
                            else -> "選択中のジャンルに該当する周辺のお店がありません。"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            } else {
                LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                    items(visiblePlaces, key = { "${it.lat},${it.lon},${it.name}" }) { place ->
                        ListItem(
                            headlineContent = { Text(place.name) },
                            supportingContent = {
                                Text("${distanceLabel(place.distanceMeters, originName)}・${place.merchant?.category.orEmpty()}")
                            },
                            trailingContent = {
                                place.bestBenefit?.let {
                                    Text(
                                        it.toString(),
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPreviewPlace(place) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * 選択中の店舗プレビュー(ボトムシート内)。地図を残したまま店舗情報を見せ、
 * 「判定の詳細を見る」で初めて全画面の判定詳細へ遷移する。× / 戻るで一覧に復帰。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NearbyPreview(
    place: MainViewModel.NearbyPlace,
    originName: String?,
    onOpenDetail: () -> Unit,
    onClose: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                // 店名が長くバッジが幅に入らないときは潰さず折り返して次の行に出す
                FlowRow(
                    itemVerticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(place.name, style = MaterialTheme.typography.titleMedium)
                    if (place.hasTimeLimited) {
                        TimeLimitedBadge()
                    }
                }
                Text(
                    "${distanceLabel(place.distanceMeters, originName)}・${place.merchant?.category.orEmpty()}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = "プレビューを閉じる")
            }
        }
        place.bestBenefit?.let {
            Text(
                "最大 $it",
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

/** YOLP 帰属表示([YolpAttribution])のシート配置。常に視認できるようドラッグハンドル直下に置く。 */
@Composable
private fun SheetAttribution() {
    YolpAttribution(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp))
}

/**
 * 地図タブの絞り込みバー(横スクロール1行)。ボトムシートの peek 高さを圧迫しないよう1行に収める。
 * - お店絞り込み中(merchantFilters 非空): 「お店で絞る」ピッカー + 選択レンズ(系列/業態)のピル
 *   (各×で個別解除)。お店はジャンルより優先で、ジャンルチップは隠す(選択は保持)。
 * - 未絞り込み: 在チェーンが2つ以上あれば「お店で絞る」ピッカー + ジャンルチップ。
 * ジャンル選択集合はお店モードと独立(MainViewModel.nearbySelectedCategories)。
 */
@Composable
private fun NearbyFilterBar(
    categories: List<String>,
    selectedCategories: Set<String>,
    presentChains: List<PresentGroup>,
    merchantFilters: Set<MainViewModel.NearbyLens>,
    onToggleCategory: (String) -> Unit,
    onToggleChain: (MainViewModel.NearbyLens) -> Unit,
    modifier: Modifier = Modifier,
) {
    val filtering = merchantFilters.isNotEmpty()
    val scrollState = rememberScrollState()
    Row(
        // 右にスクロールできることが見た目で分かるよう、余地のある側をフェードさせる(お店タブと共通)
        modifier = modifier.fillMaxWidth().horizontalFadingEdges(scrollState).horizontalScroll(scrollState),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 未絞り込みでは在チェーン(系列)が2つ以上、または1系列でも業態が複数あるときに出す
        // (1択なら絞る意味がない)。絞り込み中は常に出し、解除せずに追加・入れ替えできるようにする。
        if (filtering || presentChains.size >= 2 || presentChains.any { it.banners.size >= 2 }) {
            ChainFilterDropdown(
                chains = presentChains,
                selected = merchantFilters,
                onToggle = onToggleChain,
            )
        }
        if (filtering) {
            merchantFilters.forEach { lens ->
                InputChip(
                    selected = true,
                    onClick = { onToggleChain(lens) },
                    label = { Text(lens.label) },
                    trailingIcon = {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "${lens.label}の絞り込みを解除",
                            modifier = Modifier.size(18.dp),
                        )
                    },
                )
            }
        } else {
            CategoryFilterChips(categories, selectedCategories, onToggleCategory)
        }
    }
}

/**
 * 「お店で絞る」ピッカー。いま周辺に在るお店を件数つきのチェックボックスで挙げ、
 * 複数選べる(トグルしてもメニューは閉じず続けて選べる)。
 * 同一系列の業態が複数在るときはグループ見出し(=系列一括選択)+業態行のインデントで束ね、
 * **初期状態から展開済み**にする(業態がどのグループか知らなくても業態行に直接届く。#60)。
 * テキスト検索ではなく在チェーンからの選択にとどめる(レンズ層・検索の入口は「お店」に一本化)。
 */
@Composable
private fun ChainFilterDropdown(
    chains: List<PresentGroup>,
    selected: Set<MainViewModel.NearbyLens>,
    onToggle: (MainViewModel.NearbyLens) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    fun isSelected(lens: MainViewModel.NearbyLens) = selected.any { it.sameTarget(lens) }

    @Composable
    fun lensRow(lens: MainViewModel.NearbyLens, label: String, indent: Boolean = false) {
        DropdownMenuItem(
            text = { Text(label) },
            leadingIcon = {
                Checkbox(
                    checked = isSelected(lens),
                    onCheckedChange = null, // 行タップに委ねる(タッチ領域を行全体にする)
                )
            },
            onClick = { onToggle(lens) },
            modifier = if (indent) Modifier.padding(start = 16.dp) else Modifier,
        )
    }
    Box {
        AssistChip(
            onClick = { expanded = true },
            label = { Text("お店で絞る") },
            trailingIcon = { Icon(Icons.Default.ArrowDropDown, contentDescription = null) },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            chains.forEach { group ->
                if (group.banners.size >= 2) {
                    // グループ見出し行(系列まるごと)+業態行。見出しの選択は同系列の業態選択を
                    // 置き換える(重ね掛けしない。MainViewModel.onToggleNearbyLens 側で解決)
                    lensRow(
                        MainViewModel.NearbyLens(group.merchant),
                        "${groupLabelOf(group.merchant)} 全て（${group.total}）",
                    )
                    group.banners.forEach { (lens, count) ->
                        lensRow(lens, "${lens.label}（$count）", indent = true)
                    }
                } else {
                    // 業態が1つだけの系列は行1つ。業態を持たない merchant は従来どおり系列レンズ
                    // (ブリッジ由来のレンズと一致させる)、業態持ちはその業態レンズで正確に絞る
                    val lens = if (group.merchant.banners.isEmpty()) {
                        MainViewModel.NearbyLens(group.merchant)
                    } else {
                        group.banners.firstOrNull()?.first ?: MainViewModel.NearbyLens(group.merchant)
                    }
                    lensRow(lens, "${lens.label}（${group.total}）")
                }
            }
        }
    }
}

/**
 * 近隣取得の待ち文言。全画面ローディング(初回)と地図上の進捗ピル(再検索)で同じ文言を使う。
 * リングは地図タイルではなく「現在地の測位」/「YOLP で周辺店舗取得」を待っている。
 */
private fun nearbyLoadingText(phase: MainViewModel.NearbyLoadPhase): String = when (phase) {
    MainViewModel.NearbyLoadPhase.LOCATING -> "現在地を確認しています…"
    MainViewModel.NearbyLoadPhase.SEARCHING -> "周辺のお店を探しています…"
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

private fun distanceLabel(meters: Int, originName: String?): String {
    val prefix = if (originName != null) {
        val trimmed = originName.replace(Regex("^.{2,3}[都道府県]"), "").ifEmpty { originName }
        val short = if (trimmed.length > 10) trimmed.take(10) + "…" else trimmed
        "${short}から"
    } else {
        "現在地から"
    }
    val dist = if (meters >= 1000) {
        val km = meters / 1000.0
        if (km == km.toLong().toDouble()) "${km.toLong()}km" else "%.1fkm".format(km)
    } else {
        "${meters}m"
    }
    return "$prefix$dist"
}

/**
 * 同一地点(閾値メートル以内)の店舗をグルーピングする。同一ビル 1F/2F 等の重なり対策。
 * 判定は「グループ内のいずれかのメンバーと閾値以内か」(連結成分)。シード1店舗との距離だけで
 * 判定すると A-B 4m / B-C 4m / A-C 8m のようなチェーンが入力順(=検索起点からの距離順)次第で
 * {A,B}+{C} にも {A,B,C} にも分かれてしまい、同じ施設でも検索のたびに結果が揺れるため。
 * 連結成分なら分割は入力順によらず一意に決まる。
 */
private fun groupByProximity(
    places: List<MainViewModel.NearbyPlace>,
    thresholdMeters: Int = 10,
): List<List<MainViewModel.NearbyPlace>> {
    val used = BooleanArray(places.size)
    val groups = mutableListOf<List<MainViewModel.NearbyPlace>>()
    for (i in places.indices) {
        if (used[i]) continue
        used[i] = true
        val memberIdx = mutableListOf(i)
        // メンバーが増えるたびに再走査し、閾値以内の店舗を推移的に取り込む
        var expanded = true
        while (expanded) {
            expanded = false
            for (j in places.indices) {
                if (used[j]) continue
                val near = memberIdx.any {
                    GeoMath.distanceMeters(places[it].lat, places[it].lon, places[j].lat, places[j].lon) <= thresholdMeters
                }
                if (near) {
                    memberIdx.add(j)
                    used[j] = true
                    expanded = true
                }
            }
        }
        // グループ内は元リストの並び(検索起点からの距離順)を保つ
        groups.add(memberIdx.sorted().map { places[it] })
    }
    return groups
}
