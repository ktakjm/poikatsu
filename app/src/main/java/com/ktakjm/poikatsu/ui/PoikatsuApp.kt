package com.ktakjm.poikatsu.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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
import com.ktakjm.poikatsu.domain.customCampaignBaseId
import com.ktakjm.poikatsu.domain.isCustom
import com.ktakjm.poikatsu.ui.theme.AppIcons
import com.ktakjm.poikatsu.util.GeoMath

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

    val selectedTab = state.selectedTab

    // カスタムキャンペーンの追加/編集ダイアログ。NEW_CUSTOM_CAMPAIGN(id 空)なら新規、null なら非表示。
    // 期間限定タブの一覧(追加行)と施策詳細(編集・削除)のどちらからも開くためここに置く
    var editingCustomCampaign by remember { mutableStateOf<CustomCampaign?>(null) }
    var deletingCustomCampaign by remember { mutableStateOf<CustomCampaign?>(null) }

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

    // 下位画面(詳細/店舗判定/キャンペーン詳細/カスタムキャンペーン編集/設定サブページ)や
    // ロード・エラーに重なっていないベースのタブ表示状態。下部ナビ・FAB の表示条件。
    val baseTabsVisible = !state.loading && state.error == null &&
        state.selection == null && state.storeCheck == null &&
        state.selectedCampaignGroup == null && state.settingsSubpage == null &&
        editingCustomCampaign == null

    Scaffold(
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
                        IconButton(onClick = { editingCustomCampaign = null }) {
                            Icon(Icons.Default.Close, contentDescription = "閉じる")
                        }
                    },
                )
                state.storeCheck != null -> TopAppBar(
                    title = { Text("${state.storeCheck!!.merchant.name} 対象判定") },
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
                // キャンペーン詳細はタブ非依存のオーバーレイ(探すバナー・地図ピルからも開くため)
                state.selectedCampaignGroup != null -> {
                    val group = state.selectedCampaignGroup!!
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
                selectedTab == AppTab.SEARCH -> TopAppBar(title = { Text("ポイ活ナビ") })
                selectedTab == AppTab.CAMPAIGNS -> TopAppBar(title = { Text("期間限定キャンペーン") })
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
            if (baseTabsVisible) {
                val barInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                NavigationBar(modifier = Modifier.height(56.dp + barInset)) {
                    NavigationBarItem(
                        selected = selectedTab == AppTab.SEARCH,
                        onClick = { if (selectedTab != AppTab.SEARCH) viewModel.onSelectTab(AppTab.SEARCH) },
                        icon = { Icon(AppIcons.Storefront, contentDescription = null) },
                        label = { Text("お店") },
                    )
                    NavigationBarItem(
                        selected = selectedTab == AppTab.NEARBY,
                        onClick = {
                            if (selectedTab != AppTab.NEARBY) {
                                viewModel.onSelectTab(AppTab.NEARBY)
                                onNearbyClick()
                            }
                        },
                        icon = { Icon(AppIcons.Map, contentDescription = null) },
                        label = { Text("地図") },
                    )
                    NavigationBarItem(
                        selected = selectedTab == AppTab.CAMPAIGNS,
                        onClick = { if (selectedTab != AppTab.CAMPAIGNS) viewModel.onSelectTab(AppTab.CAMPAIGNS) },
                        icon = { Icon(AppIcons.LocalOffer, contentDescription = null) },
                        label = { Text("期間限定") },
                    )
                    NavigationBarItem(
                        selected = selectedTab == AppTab.SETTINGS,
                        onClick = { if (selectedTab != AppTab.SETTINGS) viewModel.onSelectTab(AppTab.SETTINGS) },
                        icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                        label = { Text("設定") },
                    )
                }
            }
        },
    ) { innerPadding ->
        val isMap = baseTabsVisible && selectedTab == AppTab.NEARBY && state.nearby != null
        val contentPadding = if (isMap) {
            PaddingValues(bottom = innerPadding.calculateBottomPadding())
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
                        onClose = { editingCustomCampaign = null },
                    )
                }
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
                        onFindNearby = {
                            state.selection?.merchant?.let {
                                viewModel.onFindNearby(it)
                                onNearbyClick()
                            }
                        },
                    )
                }
                // キャンペーン詳細(タブ非依存のオーバーレイ)。topBar の分岐順と一致させること
                state.selectedCampaignGroup != null -> PaddedColumn {
                    // カスタムキャンペーンなら詳細に編集・削除の入口を出す(登録内容は customCampaigns から引く。
                    // 複数決済の展開 id は決済サフィックスを剥がした登録単位の id で逆引きする)
                    val customSource = state.selectedCampaignGroup!!.firstOrNull()?.campaign
                        ?.takeIf { it.isCustom }
                        ?.let { c -> state.customCampaigns.firstOrNull { it.id == customCampaignBaseId(c.id) } }
                    CampaignDetail(
                        judgments = state.selectedCampaignGroup!!,
                        merchantNames = state.merchantNames,
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
                        onBack = viewModel::onCloseSettingsSubpage,
                        onAdd = viewModel::onAddRegisteredArea,
                        onRemove = viewModel::onRemoveRegisteredArea,
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
                            onToggleChain = viewModel::onToggleNearbyChain,
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
                selectedTab == AppTab.CAMPAIGNS -> PaddedColumn {
                    CampaignPane(
                        activeCampaigns = state.timeLimitedActive,
                        upcomingCampaigns = state.timeLimitedUpcoming,
                        expiredCustomCampaigns = state.expiredCustomCampaigns,
                        merchantNames = state.merchantNames,
                        campaignColors = state.campaignBrandColors,
                        filter = state.campaignFilter,
                        onFilterChange = viewModel::onSetCampaignFilter,
                        showRegionChip = state.registeredAreas.isNotEmpty() &&
                            !state.municipalityMaster.isEmpty(),
                        regionFilterOn = !state.showAllCampaigns,
                        onToggleRegionFilter = viewModel::onToggleShowAllCampaigns,
                        onSelectGroup = viewModel::onSelectCampaignGroup,
                    )
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
                    dataSummary = dataRowSummary(
                        state.dataUpdatedAt,
                        state.dataSource,
                        state.useTestData,
                        state.useBundledData,
                    ),
                    developerSummary = developerRowSummary(
                        state.developerMode,
                        state.dataCommitRef,
                        state.useTestData,
                        state.useBundledData,
                    ),
                    onOpenSubpage = viewModel::onOpenSettingsSubpage,
                )
                else -> PaddedColumn {
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
                        onQueryChange = viewModel::onQueryChange,
                        onToggleCategory = viewModel::onToggleCategory,
                        onSelect = viewModel::onSelect,
                        onRefresh = viewModel::onManualRefresh,
                        onOpenMunicipalCampaigns = viewModel::onOpenMunicipalCampaigns,
                    )
                }
            }
        }
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
    onQueryChange: (String) -> Unit,
    onToggleCategory: (String) -> Unit,
    onSelect: (Merchant) -> Unit,
    onRefresh: () -> Unit,
    onOpenMunicipalCampaigns: () -> Unit,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth(),
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        placeholder = { Text("お店の名前(例: マック、サイゼ)") },
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
        // 初期画面(検索前)。自治体施策のお知らせは検索・判定と混ざらないようここだけに出す
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
            else "「$query」に一致するお店が見つかりませんでした。登録済みの高還元施策の対象外の可能性があります。",
            style = MaterialTheme.typography.bodyMedium,
        )
        else -> LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(results, key = { it.merchant.id }) { result ->
                SearchResultCard(result) { onSelect(result.merchant) }
            }
        }
    }
}

/**
 * お店タブ初期画面の自治体施策お知らせバナー。施策の中身は出さず「あること」だけ知らせ、
 * タップで期間限定タブ(自治体フィルタ)へ送る。判定詳細(店舗カードタップ後)には出さない
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
                    "期間限定タブで詳細を確認できます",
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

@Composable
private fun SearchResultCard(result: MainViewModel.SearchResult, onClick: () -> Unit) {
    val fallback = MaterialTheme.colorScheme.primary
    val stripeColors = result.brandColors
        .mapNotNull { parseBrandColor(it) }
        .ifEmpty { listOf(fallback) }
    val separatorColor = MaterialTheme.colorScheme.surfaceContainerHigh
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            StripeBar(stripeColors, separatorColor)
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(result.merchant.name, style = MaterialTheme.typography.bodyLarge)
                        if (result.hasTimeLimited) {
                            TimeLimitedBadge()
                        }
                    }
                    Text(
                        result.merchant.category,
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
                            "${result.campaignCount}件の施策",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

// ---- 近く(地図)タブ ----

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
    merchantFilters: Set<Merchant>,
    searchFailed: String?,
    originName: String?,
    geocodeCandidates: List<MainViewModel.GeocodedPlace>,
    isGeocoding: Boolean,
    onClose: () -> Unit,
    onReload: () -> Unit,
    onSearchFailedShown: () -> Unit,
    onToggleCategory: (String) -> Unit,
    onToggleChain: (Merchant) -> Unit,
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
    // チェーン絞り込み(merchantFilters)はジャンルより優先。未指定なら参照同一で再計算を避ける。
    val visiblePlaces = remember(nearby.places, selectedCategories, merchantFilters) {
        val filterIds = merchantFilters.map { it.id }.toSet()
        when {
            filterIds.isNotEmpty() -> nearby.places.filter { it.merchant?.id in filterIds }
            selectedCategories.isEmpty() -> nearby.places
            else -> nearby.places.filter { it.merchant?.category in selectedCategories }
        }
    }
    // 「チェーンで絞る」ピッカー用: いま(ジャンル絞り込み後の)周辺に在るチェーンと件数。多い順→読み順。
    // 全体ではなく周辺に在るものだけ出す(「地図」の約束)。絞り込み中もピッカーは残し、追加・解除を続けられる。
    val presentChains = remember(nearby.places, selectedCategories) {
        nearby.places
            .filter { selectedCategories.isEmpty() || it.merchant?.category in selectedCategories }
            .mapNotNull { it.merchant }
            .groupingBy { it }.eachCount()
            .entries
            .sortedWith(compareByDescending<Map.Entry<Merchant, Int>> { it.value }.thenBy { it.key.reading })
            .map { it.key to it.value }
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
    LaunchedEffect(placeGroup) {
        if (placeGroup != null &&
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
                        originName = originName,
                        onOpenDetail = { onOpenDetail(selectedPlace) },
                        onClose = onClearPreview,
                    )
                }
            } else if (placeGroup != null) {
                // 複合ピン/クラスタをタップ: 内包する店舗をリストで見せる。
                // 覗き高さは内容の実測(少数件は全件見せる)。多数件は sheetPeek 側で頭打ちにし、
                // シートを引き上げるとリスト内スクロールで続きを見られる。
                val group = placeGroup!!
                Column(
                    modifier = Modifier
                        .heightIn(max = sheetMaxHeight)
                        .onSizeChanged {
                            previewSheetPeek = with(density) { it.height.toDp() } + COMPACT_HANDLE_HEIGHT
                        },
                ) {
                    SheetAttribution()
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (group.sameSpot) {
                                    "同じ場所に ${group.places.size} 件"
                                } else {
                                    "この付近に ${group.places.size} 件"
                                },
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = { placeGroup = null }) {
                                Icon(Icons.Default.Close, contentDescription = "閉じる")
                            }
                        }
                        LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false)) {
                            items(group.places, key = { "${it.lat},${it.lon},${it.name}" }) { place ->
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
                Column(Modifier.heightIn(max = sheetMaxHeight)) {
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
                                        merchantFilters.joinToString("") { "「${it.name}」" } +
                                            "はこの範囲にありません。地図を動かすか、絞り込みを解除してください。"
                                    nearby.places.isEmpty() ->
                                        "この範囲に対象施策のあるお店が見つかりませんでした。地図を動かして探してください。"
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
        },
    ) { _ ->
        // 地図は全面(full-bleed)。上端はステータスバー裏まで、下端はシート(peek)背面まで描き、
        // 角丸や端から背景が覗くのを防ぐ。ステータスバーと重なる浮きコントロールは topInset で避ける。
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
            bottomPadding = sheetPeek,
        )
    }
    }
}

/**
 * 選択中の店舗プレビュー(ボトムシート内)。地図を残したまま店舗情報を見せ、
 * 「判定の詳細を見る」で初めて全画面の判定詳細へ遷移する。× / 戻るで一覧に復帰。
 */
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
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
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
 * 地図タブの絞り込みバー(横スクロール1行)。ボトムシートの peek 高さを圧迫しないよう1行に収める。
 * - チェーン絞り込み中(merchantFilters 非空): 「チェーンで絞る」ピッカー + 選択チェーンのピル
 *   (各×で個別解除)。チェーンはジャンルより優先で、ジャンルチップは隠す(選択は保持)。
 * - 未絞り込み: 在チェーンが2つ以上あれば「チェーンで絞る」ピッカー + ジャンルチップ。
 * ジャンル選択集合はお店モードと独立(MainViewModel.nearbySelectedCategories)。
 */
@Composable
private fun NearbyFilterBar(
    categories: List<String>,
    selectedCategories: Set<String>,
    presentChains: List<Pair<Merchant, Int>>,
    merchantFilters: Set<Merchant>,
    onToggleCategory: (String) -> Unit,
    onToggleChain: (Merchant) -> Unit,
    modifier: Modifier = Modifier,
) {
    val filtering = merchantFilters.isNotEmpty()
    Row(
        modifier = modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 未絞り込みでは在チェーンが2つ以上のときだけ出す(1つなら絞る意味がない)。
        // 絞り込み中は常に出し、解除せずにチェーンを追加・入れ替えできるようにする。
        if (filtering || presentChains.size >= 2) {
            ChainFilterDropdown(
                chains = presentChains,
                selected = merchantFilters,
                onToggle = onToggleChain,
            )
        }
        if (filtering) {
            merchantFilters.forEach { merchant ->
                InputChip(
                    selected = true,
                    onClick = { onToggleChain(merchant) },
                    label = { Text(merchant.name) },
                    trailingIcon = {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "${merchant.name}の絞り込みを解除",
                            modifier = Modifier.size(18.dp),
                        )
                    },
                )
            }
        } else {
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
 * 「チェーンで絞る」ピッカー。いま周辺に在るチェーンを件数つきのチェックボックスで挙げ、
 * 複数チェーンを選べる(トグルしてもメニューは閉じず続けて選べる)。
 * テキスト検索ではなく在チェーンからの選択にとどめる(レンズ層・検索の入口は「お店」に一本化)。
 */
@Composable
private fun ChainFilterDropdown(
    chains: List<Pair<Merchant, Int>>,
    selected: Set<Merchant>,
    onToggle: (Merchant) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedIds = selected.map { it.id }.toSet()
    Box {
        AssistChip(
            onClick = { expanded = true },
            label = { Text("お店で絞る") },
            trailingIcon = { Icon(Icons.Default.ArrowDropDown, contentDescription = null) },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            chains.forEach { (merchant, count) ->
                DropdownMenuItem(
                    text = { Text("${merchant.name}（$count）") },
                    leadingIcon = {
                        Checkbox(
                            checked = merchant.id in selectedIds,
                            onCheckedChange = null, // 行タップに委ねる(タッチ領域を行全体にする)
                        )
                    },
                    onClick = { onToggle(merchant) },
                )
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
