package com.ktakjm.poikatsu.ui

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberStandardBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ktakjm.poikatsu.data.Campaign
import com.ktakjm.poikatsu.data.Merchant
import com.ktakjm.poikatsu.domain.CampaignJudgment
import com.ktakjm.poikatsu.domain.ExpiringPointNotice
import com.ktakjm.poikatsu.domain.campaignGroupDisplayTitle
import com.ktakjm.poikatsu.domain.groupLabelOf
import com.ktakjm.poikatsu.util.GeoMath

// ---- 近く(地図)タブ(#88 で PoikatsuApp.kt から移設) ----

/**
 * 地名検索(起点コントロール)の表示状態。PoikatsuApp → NearbyPane → NearbyMap と
 * 4 段素通しされていた 7 引数を [PlaceSearchActions] と対で束ねる(#88)。
 */
internal data class PlaceSearchState(
    /** 起点の表示名(「{起点名}から○○m」)。null は GPS 起点 */
    val originName: String?,
    /** ジオコーディング候補リスト。検索バーで地名を入力→送信後に結果が入る */
    val candidates: List<MainViewModel.GeocodedPlace>,
    /** ジオコーディング中フラグ */
    val isGeocoding: Boolean,
)

/** 地名検索の操作一式。[PlaceSearchState] と対で素通しする */
internal data class PlaceSearchActions(
    val onGeocode: (String) -> Unit,
    val onSelectCandidate: (MainViewModel.GeocodedPlace) -> Unit,
    val onClearOrigin: () -> Unit,
    val onDismiss: () -> Unit,
)

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
internal fun NearbyPane(
    nearby: MainViewModel.NearbyUi,
    categories: List<String>,
    selectedCategories: Set<String>,
    merchantFilters: Set<MainViewModel.NearbyLens>,
    searchFailed: String?,
    placeSearch: PlaceSearchState,
    placeSearchActions: PlaceSearchActions,
    onClose: () -> Unit,
    onReload: () -> Unit,
    onSearchFailedShown: () -> Unit,
    onToggleCategory: (String) -> Unit,
    onToggleChain: (MainViewModel.NearbyLens) -> Unit,
    onPreviewPlace: (MainViewModel.NearbyPlace) -> Unit,
    onClearPreview: () -> Unit,
    onOpenDetail: (MainViewModel.NearbyPlace) -> Unit,
    /**
     * 詳細サイドシート(#57)を閉じる。クラスタ/複合ピンのタップでグループリストを出すとき、
     * シートが上に残るとグループリストが隠れて見えないため、開くのと同時に閉じる。
     * 縦画面では詳細表示中に地図は触れない(全画面オーバーレイ)ので実質no-op。
     */
    onCloseDetail: () -> Unit,
    onSearchHere: (Double, Double, Int, Double) -> Unit,
    /** 選択中にカメラが停止したときのズーム報告(NearbyUi.selectionZoom へ退避→復元に使う) */
    onSelectionZoomChanged: (Double) -> Unit,
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
                        onCloseDetail()
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
            selectionZoom = nearby.selectionZoom,
            onSelectionZoomChanged = onSelectionZoomChanged,
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
                    onCloseDetail()
                    placeGroup = PlaceGroupSheet(places, sameSpot)
                }
            },
            loadingMessage = if (nearby.loading) nearbyLoadingText(nearby.loadingPhase) else null,
            placeSearch = placeSearch,
            placeSearchActions = placeSearchActions,
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
            originName = placeSearch.originName,
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

/** 地図タブ横画面の詳細サイドシートの幅(#57)。M3 side sheet の上限幅に合わせる。 */
private val NEARBY_SIDE_SHEET_WIDTH = 400.dp

/**
 * 地図タブ横画面の詳細サイドシート(#57)。判定詳細(selection)・店舗判定(storeCheck)・
 * 施策詳細(お知らせピル発の campaignGroup)を、地図の上に浮く右端・全高のパネルで表示する。
 * M3 の side sheet 相当(非モーダル・スクリムなし)で、下の地図・右ペイン(320dp)はサイズ不変のまま
 * 上に重ねる(ペイン幅を可変にすると GoogleMap の再レイアウトが開閉のたびに走るため採らない)。
 * 全画面時に TopAppBar が担うタイトルと閉じる/戻るは PaneHeader がシート内で肩代わりし、
 * 店舗判定はシート内で判定詳細と置き換わる(←で判定詳細へ。二ペインの詳細ペインと同じ)。
 * 分岐順(storeCheck → selection → campaignGroup)は全画面オーバーレイの when と揃える
 * (回転で全画面オーバーレイに切り替わっても同じ画面が最前面になるように)。
 * 表示するものが無ければ何も出さない。
 */
@Composable
internal fun NearbyDetailSideSheet(
    storeCheck: MainViewModel.StoreCheckState?,
    selection: MainViewModel.Selection?,
    campaignGroup: List<CampaignJudgment>?,
    merchants: Map<String, Merchant>,
    storeRates: Map<String, Map<String, Double>>,
    expiringNotices: List<ExpiringPointNotice>,
    topInset: Dp,
    onBack: () -> Unit,
    onOpenStoreCheck: () -> Unit,
    onCloseStoreCheck: () -> Unit,
    onStoreNameChange: (String) -> Unit,
    onExcludeStore: (campaignId: String, storeName: String) -> Unit,
    onRestoreExcludedStore: (campaignId: String) -> Unit,
    onCloseCampaignDetail: () -> Unit,
    onFindChains: (List<String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (storeCheck == null && selection == null && campaignGroup == null) return
    Surface(
        modifier = modifier.fillMaxHeight().width(NEARBY_SIDE_SHEET_WIDTH),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        // 右ペインと同色のためシートの浮きは影で出す。角丸は画面端に接しない左側だけ(M3 side sheet)
        shape = RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp),
        shadowElevation = 2.dp,
    ) {
        // full-bleed でステータスバー裏まで届くため、内容だけ topInset で避ける(右ペインと同じ)
        Column(Modifier.padding(top = topInset).padding(horizontal = 16.dp)) {
            when {
                storeCheck != null -> {
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
                selection != null -> {
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
                        // 地図発の判定詳細は店舗特定済み(displayName あり)のため「近くのこのお店を
                        // 探す」は描画されず、この導線は呼ばれない
                        onFindNearby = {},
                        onExcludeStore = onExcludeStore,
                        onRestoreExcludedStore = onRestoreExcludedStore,
                        expiringNotices = expiringNotices,
                    )
                }
                campaignGroup != null -> {
                    PaneHeader(
                        title = campaignGroupDisplayTitle(campaignGroup.map { it.campaign }, merchants),
                        trailing = {
                            IconButton(onClick = onCloseCampaignDetail) {
                                Icon(Icons.Default.Close, contentDescription = "詳細を閉じる")
                            }
                        },
                    )
                    // お知らせピル発は自治体施策のみでカスタムキャンペーンは来ないため、
                    // 編集・削除(onEditCustom/onDeleteCustom)は出さない
                    CampaignDetail(
                        judgments = campaignGroup,
                        merchants = merchants,
                        storeRates = storeRates,
                        onBack = onCloseCampaignDetail,
                        onFindChains = onFindChains,
                    )
                }
            }
        }
    }
}

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
                        NearbyPlaceRow(
                            place = place,
                            originName = originName,
                            onClick = { onPreviewPlace(place) },
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
                            // 網羅リストの対象外店を間引いた結果の 0 件は「無い」でなく理由を言う(#70。
                            // 目の前に店があるのに「この範囲にありません」では嘘になるため)
                            merchantFilters.isNotEmpty() && nearby.ineligibleHiddenCount > 0 ->
                                "この範囲の" + merchantFilters.joinToString("") { "「${it.label}」" } +
                                    "はこのキャンペーンの対象のお店ではないため表示していません。" +
                                    "対象のお店があるエリアへ地図を動かしてください。"
                            merchantFilters.isNotEmpty() ->
                                merchantFilters.joinToString("") { "「${it.label}」" } +
                                    "はこの範囲にありません。地図を動かすか、絞り込みを解除してください。"
                            // 取得サマリで 0 件の意味を切り分ける(#70): 取得自体が 0 件(提供データが
                            // 薄いエリア等)と、取得はできたが対象チェーンが無かったを区別する
                            nearby.places.isEmpty() && nearby.rawPoiCount == 0 ->
                                "この付近のお店情報を取得できませんでした。別のエリアで試してください。"
                            nearby.places.isEmpty() ->
                                "周辺のお店情報${nearby.rawPoiCount}件のうち、対象キャンペーンのある" +
                                    "お店は見つかりませんでした。地図を動かして探してください。"
                            else -> "選択中のジャンルに該当する周辺のお店がありません。"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            } else {
                LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                    items(visiblePlaces, key = { "${it.lat},${it.lon},${it.name}" }) { place ->
                        NearbyPlaceRow(
                            place = place,
                            originName = originName,
                            onClick = { onPreviewPlace(place) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * 店舗リストの 1 行(店名・距離とジャンル・最良特典)。一覧とグループリスト
 * (複合ピン/クラスタの内包店舗)で同じ見た目を共用する(#88 で重複を統合)。
 */
@Composable
private fun NearbyPlaceRow(
    place: MainViewModel.NearbyPlace,
    originName: String?,
    onClick: () -> Unit,
) {
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
            .clickable(onClick = onClick),
    )
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
 * ジャンル選択集合はお店モードと独立(NearbyState.selectedCategories)。
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
                    // 置き換える(重ね掛けしない。NearbyController.onToggleNearbyLens 側で解決)
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
