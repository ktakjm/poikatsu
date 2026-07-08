package com.ktakjm.poikatsu.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.Build
import android.view.Surface as ViewSurface
import android.view.WindowManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMapOptions
import com.google.android.gms.maps.LocationSource
import com.google.android.gms.maps.model.MapColorScheme
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.maps.android.compose.ComposeMapColorScheme
import com.google.maps.android.clustering.Cluster
import com.google.maps.android.clustering.ClusterItem
import com.google.maps.android.clustering.view.DefaultClusterRenderer
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.MapsComposeExperimentalApi
import com.google.maps.android.compose.clustering.Clustering
import com.google.maps.android.compose.clustering.rememberClusterManager
import com.google.maps.android.compose.clustering.rememberClusterRenderer
import com.google.maps.android.compose.rememberCameraPositionState
import com.ktakjm.poikatsu.util.GeoMath
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow

/**
 * 地図表示の「継ぎ目」。地図ライブラリ(Google Maps)固有の型はこのファイルの中だけに閉じ込め、
 * アプリ側は自前の [MapPoint] / [MapMarker] だけを扱う。これにより将来 MapLibre 等へ
 * 表示層だけを差し替える場合も、変更はこのファイルと依存・キー設定に閉じる。
 * 旧 osmdroid からの移行経緯・規約は docs/map-data-stack.md を参照。
 */
data class MapPoint(val lat: Double, val lon: Double)

data class MapMarker(
    val point: MapPoint,
    val label: String,
    /**
     * 対応する発行体のブランドカラー("#RRGGBB")。ロゴ不使用方針のため発行体はピンの色で識別する。
     * 複数施策に対応する店舗は色を複数渡し、ピンを色ごとに分割して描く(2 色なら左右半分ずつ)。
     */
    val colorHexes: List<String>,
    /** 一覧/ピンで選択中の店舗。true のピンは大きく・白縁を太くして強調し、最前面に描く */
    val selected: Boolean = false,
    val onClick: () -> Unit,
    /** 同一地点(同一ビル等)の店舗数。2 以上ならクラスタバッジと同じ見た目で描く */
    val groupSize: Int = 1,
)

/**
 * 対象店舗をピン表示する地図。浮きコントロールは上部(検索バー)、
 * 条件付き「このエリアを検索」、右下の現在地ボタンで構成する。
 */
@OptIn(MapsComposeExperimentalApi::class) // rememberClusterManager/Renderer(minClusterSize 変更のため)
@Composable
fun NearbyMap(
    center: MapPoint,
    /** 現在地(my-location レイヤーへ流し込む位置。ViewModel の継続購読に追従)。null なら青ドット非表示 */
    userLocation: MapPoint?,
    markers: List<MapMarker>,
    initialZoom: Double,
    /** 検索完了ごとに変わる世代スタンプ。center が同値の再検索でもカメラを寄せ直すためのキー */
    searchStamp: Int,
    selectedPoint: MapPoint?,
    onSearchHere: (MapPoint, Int, Double) -> Unit,
    onSearchMyLocation: () -> Unit,
    /**
     * クラスタをタップしたとき、内包するマーカー一覧を渡す(→店舗リスト表示)。
     * sameSpot=true はズームしても分解できないクラスタ(実質同一地点)で、ズームは行わない。
     * false は分解できるクラスタで、リスト表示と同時にズームインする。
     */
    onClusterOpen: (markers: List<MapMarker>, sameSpot: Boolean) -> Unit,
    loadingMessage: String?,
    // 地名検索(起点コントロール)
    originName: String?,
    geocodeCandidates: List<MainViewModel.GeocodedPlace>,
    isGeocoding: Boolean,
    onGeocode: (String) -> Unit,
    onSelectCandidate: (MainViewModel.GeocodedPlace) -> Unit,
    onClearOrigin: () -> Unit,
    onDismissSearch: () -> Unit,
    modifier: Modifier = Modifier,
    topInset: Dp = 0.dp,
    bottomPadding: Dp = 0.dp,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val darkMode = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val uiSettings = remember {
        MapUiSettings(
            zoomControlsEnabled = false,
            mapToolbarEnabled = false,
            myLocationButtonEnabled = false,
            compassEnabled = false,
            rotationGesturesEnabled = false,
            tiltGesturesEnabled = false,
        )
    }

    val initialCamera = CameraPosition.fromLatLngZoom((selectedPoint ?: center).toLatLng(), initialZoom.toFloat())
    val cameraPositionState = rememberCameraPositionState { position = initialCamera }

    // ズームが寄り(POI_SUPPRESS_ZOOM 以上)のときだけ Google 標準 POI ラベルを消す(定義側コメント参照)。
    // derivedStateOf により閾値をまたいだ瞬間だけ recompose され、スタイルが差し替わる。
    val suppressPoi by remember {
        derivedStateOf { cameraPositionState.position.zoom >= POI_SUPPRESS_ZOOM }
    }
    // 現在地は SDK 純正の my-location レイヤー(青ドット+向きのシェブロン)で表示するが、
    // 位置・向きは SDK 内蔵の購読でなく ManualLocationSource 経由でアプリが流し込む:
    // 位置=ViewModel の現在地継続購読(userLocation)、向き=コンパス(rememberCompassHeading)。
    // isMyLocationEnabled は権限未許可だと SecurityException を投げるため必ず権限でゲートする。
    // searchStamp キーで検索完了のたびに再評価し、拒否→OS設定で許可→📍再検索の流れでも
    // 次の検索から有効になる。
    val myLocationEnabled = remember(searchStamp) {
        listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            .any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    }
    val locationSource = remember { ManualLocationSource() }
    val heading = if (userLocation != null) rememberCompassHeading(userLocation) else null
    LaunchedEffect(userLocation, heading) {
        if (userLocation != null) {
            locationSource.update(
                Location("poikatsu").apply {
                    latitude = userLocation.lat
                    longitude = userLocation.lon
                    // bearing を立てるとドットのシェブロンがその方位を向く(センサー無し端末は向きなし)
                    if (heading != null) bearing = heading
                },
            )
        }
    }
    val mapProperties = remember(suppressPoi, myLocationEnabled) {
        MapProperties(
            isMyLocationEnabled = myLocationEnabled,
            mapStyleOptions = if (suppressPoi) POI_SUPPRESS_STYLE else null,
        )
    }

    // 検索完了のたびにカメラを検索中心へ寄せる。キーに searchStamp を含めるのは、現在地ボタンの
    // 再検索で GPS が前回と同じ座標を返すと center/initialZoom が同値のままで、値の変化だけでは
    // 発火せず「現在地に戻らない」ため(パンして地図だけ動かした後の現在地ボタンで起きる)。
    var centerInitialized by remember { mutableStateOf(false) }
    LaunchedEffect(center, initialZoom, searchStamp) {
        if (!centerInitialized) {
            centerInitialized = true
            return@LaunchedEffect
        }
        cameraPositionState.move(
            CameraUpdateFactory.newLatLngZoom(center.toLatLng(), initialZoom.toFloat()),
        )
    }

    var selectionInitialized by remember { mutableStateOf(false) }
    LaunchedEffect(selectedPoint) {
        if (!selectionInitialized) {
            selectionInitialized = true
            return@LaunchedEffect
        }
        if (selectedPoint != null) {
            val targetZoom = cameraPositionState.position.zoom.coerceAtLeast(SELECTION_MIN_ZOOM)
            cameraPositionState.animate(
                CameraUpdateFactory.newLatLngZoom(selectedPoint.toLatLng(), targetZoom),
            )
        }
    }

    // 「このエリアを検索」の表示条件:
    // (1) 地図カメラが最終検索中心から画面の約2割以上移動した(下限 50m)、または
    // (2) ズームアウトにより表示範囲が検索時の倍以上に広がった(ズームが1段階以上下がった)
    val showSearchHere by remember(center, initialZoom) {
        derivedStateOf {
            if (loadingMessage != null) return@derivedStateOf false
            val currentZoom = cameraPositionState.position.zoom.toDouble()
            val target = cameraPositionState.position.target
            val dist = GeoMath.distanceMeters(
                center.lat, center.lon, target.latitude, target.longitude,
            )
            val metersPerPx = 156543.03 * cos(Math.toRadians(center.lat)) / 2.0.pow(currentZoom)
            val halfScreenM = (540 * metersPerPx).toInt()
            val movedEnough = dist > maxOf(halfScreenM * 2 / 10, 50)
            val zoomedOutEnough = currentZoom <= initialZoom - 1.0
            movedEnough || zoomedOutEnough
        }
    }

    // 検索バーのローカル状態
    var searchActive by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var searchSubmitted by remember { mutableStateOf(false) }

    Box(modifier) {
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = cameraPositionState,
            googleMapOptionsFactory = {
                GoogleMapOptions()
                    .camera(initialCamera)
                    .mapColorScheme(if (darkMode) MapColorScheme.DARK else MapColorScheme.LIGHT)
            },
            properties = mapProperties,
            uiSettings = uiSettings,
            locationSource = locationSource,
            mapColorScheme = if (darkMode) ComposeMapColorScheme.DARK else ComposeMapColorScheme.LIGHT,
            contentPadding = PaddingValues(bottom = bottomPadding),
        ) {
            val clusterItems = remember(markers) { markers.map(::StoreClusterItem) }
            val clusterManager = rememberClusterManager<StoreClusterItem>()
            val clusterRenderer = rememberClusterRenderer(
                clusterContent = { cluster ->
                    // 複合ピン(groupSize>1)を含み得るため、アイテム数でなく店舗数の合計を表示する
                    ClusterBadge(count = cluster.items.sumOf { it.marker.groupSize })
                },
                clusterItemContent = { item ->
                    if (item.marker.groupSize > 1) {
                        // 同一地点の複合ピン: ライブラリクラスタと同じバッジで描く
                        ClusterBadge(count = item.marker.groupSize)
                    } else {
                        StorePin(item.marker)
                    }
                },
                clusterManager = clusterManager,
            )
            SideEffect {
                // 既定の最小クラスタサイズは 4 で、2〜3 個の近接ピンは束ねられず重なったまま
                // 描かれてしまう。「画面上で重なるなら束ねる」ため 2 に下げる
                (clusterRenderer as? DefaultClusterRenderer<*>)?.minClusterSize = 2
                // クラスタタップは「内包する店舗リストの表示」＋(分解できるなら)ズームイン。
                // YOLP 再検索はしない(minClusterSize=2 では高ズーム中のタップも多く、
                // その時点の表示範囲=極小半径で再検索すると
                // それまでの検索結果リストを狭い範囲の結果で上書きしてしまうため)。
                // リストは常に開く: ズームだけだと下部シートが旧検索中心基準の全体リストのままで、
                // タップしたクラスタと無関係な店舗が上位に並んでしまうため。
                // ズーム上限(MAX_CLUSTER_ZOOM)でもライブラリが束ね続ける広がりのクラスタは
                // ズームしても永遠に分解できない(デッドエンド)ため、ズームせず sameSpot=true で
                // 複合ピンと同じ「同じ場所に N 件」扱いにする。距離予測が外れて分解できなかった
                // 場合も、上限到達後の再タップで必ず sameSpot=true に落ちる
                // cluster/item は null で渡り得る: マーカーのタップはキャッシュ
                // (DefaultClusterRenderer.mMarkerCache)を引いて解決されるが、レンダラー
                // 差し替え時に旧レンダラーが非同期描画で残した孤児マーカーはキャッシュに
                // 無く、ライブラリに null ガードが無いため null のままリスナーへ届く
                clusterManager?.setOnClusterClickListener { cluster: Cluster<StoreClusterItem>? ->
                    if (cluster == null) return@setOnClusterClickListener true
                    val currentZoom = cameraPositionState.position.zoom
                    val maxSpreadM = maxPairwiseDistanceMeters(cluster.items.map { it.position })
                    val unsplittable = currentZoom >= MAX_CLUSTER_ZOOM - 0.01f ||
                        maxSpreadM <= clusterMergeDistanceMeters(MAX_CLUSTER_ZOOM, cluster.position.latitude)
                    if (!unsplittable) {
                        scope.launch {
                            val newZoom = (currentZoom + 2f).coerceAtMost(MAX_CLUSTER_ZOOM)
                            cameraPositionState.animate(
                                CameraUpdateFactory.newLatLngZoom(cluster.position, newZoom),
                            )
                        }
                    }
                    onClusterOpen(cluster.items.map { it.marker }, unsplittable)
                    true
                }
                clusterManager?.setOnClusterItemClickListener { item: StoreClusterItem? ->
                    item?.marker?.onClick()
                    true
                }
            }
            // ClusterManager は生成直後は標準レンダラー(赤いデフォルトピン)を持つため、
            // カスタムレンダラーの適用を待ってから Clustering にアイテムを流す
            // (Clustering.kt の deprecation メッセージが示すライブラリ推奨パターン)。
            // 適用前に流すと一瞬赤ピンで描かれ、描画が非同期なので差し替え時の掃除と
            // すれ違うと赤ピンが孤児として残る(タップするとキャッシュ未登録→null→クラッシュ)。
            // clusterManager.renderer は snapshot 状態ではなく composition で読んでも
            // 変化時に再コンポーズされないため、SideEffect から状態へ書いて反映する。
            var rendererApplied by remember { mutableStateOf(false) }
            SideEffect {
                if (clusterManager != null && clusterRenderer != null &&
                    clusterManager.renderer !== clusterRenderer
                ) {
                    clusterManager.renderer = clusterRenderer
                }
                rendererApplied = clusterManager != null && clusterManager.renderer === clusterRenderer
            }
            if (clusterManager != null && rendererApplied) {
                Clustering(items = clusterItems, clusterManager = clusterManager)
            }
        }

        // --- 上部コントロール: 検索バー + 候補リスト + 「このエリアを検索」/進捗ピル ---
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(top = topInset + 8.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PlaceSearchBar(
                originName = originName,
                active = searchActive,
                query = searchQuery,
                isGeocoding = isGeocoding,
                onActivate = {
                    searchActive = true
                    searchQuery = ""
                    searchSubmitted = false
                    onDismissSearch()
                },
                onQueryChange = { searchQuery = it; searchSubmitted = false },
                onSearch = {
                    if (searchQuery.isNotBlank()) {
                        searchSubmitted = true
                        onGeocode(searchQuery)
                        focusManager.clearFocus()
                    }
                },
                onClearOrigin = {
                    searchActive = false
                    searchQuery = ""
                    searchSubmitted = false
                    onClearOrigin()
                },
                onDismiss = {
                    searchActive = false
                    searchQuery = ""
                    searchSubmitted = false
                    onDismissSearch()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
            )

            // 候補リスト(検索バーが活性でジオコーディング完了後に表示)
            if (searchActive && (geocodeCandidates.isNotEmpty() || (searchSubmitted && !isGeocoding))) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(12.dp),
                    tonalElevation = 3.dp,
                    shadowElevation = 3.dp,
                ) {
                    Column {
                        if (geocodeCandidates.isEmpty() && searchSubmitted && !isGeocoding) {
                            Text(
                                "場所が見つかりませんでした",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                        geocodeCandidates.forEach { place ->
                            ListItem(
                                headlineContent = {
                                    val display = if (place.fullAddress.isNotBlank() &&
                                        place.fullAddress != place.name
                                    ) {
                                        "${place.name}（${place.fullAddress}）"
                                    } else {
                                        place.name
                                    }
                                    Text(display, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                },
                                leadingContent = {
                                    Icon(
                                        Icons.Default.Place,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp),
                                    )
                                },
                                modifier = Modifier.clickable {
                                    searchActive = false
                                    searchQuery = ""
                                    searchSubmitted = false
                                    focusManager.clearFocus()
                                    onSelectCandidate(place)
                                },
                            )
                        }
                    }
                }
            }

            // 「このエリアを検索」(条件付き) / 再検索中の進捗ピル
            if (loadingMessage != null) {
                Spacer(Modifier.height(8.dp))
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    tonalElevation = 3.dp,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(loadingMessage, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            } else if (showSearchHere) {
                Spacer(Modifier.height(8.dp))
                FilledTonalButton(
                    onClick = {
                        val pos = cameraPositionState.position
                        val c = pos.target
                        val bounds = cameraPositionState.projection?.visibleRegion?.latLngBounds
                        val radiusM = bounds?.let {
                            GeoMath.distanceMeters(
                                c.latitude, c.longitude, it.northeast.latitude, it.northeast.longitude,
                            )
                        } ?: 1000
                        onSearchHere(MapPoint(c.latitude, c.longitude), radiusM, pos.zoom.toDouble())
                    },
                ) {
                    Text("このエリアを検索")
                }
            }
        }

        // 📍 現在地で検索: 右下(ボトムシート peek の上)。位置が取れていない(フォールバック表示)
        // ときも出し、GPS 再試行の導線にする
        FilledTonalIconButton(
            onClick = onSearchMyLocation,
            enabled = loadingMessage == null,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = bottomPadding + 16.dp, end = 8.dp)
                .size(48.dp),
        ) {
            Icon(Icons.Default.LocationOn, contentDescription = "現在地で検索")
        }
    }
}

/** 地図上部の場所検索バー。GPS 起点/地名起点/テキスト入力の3状態を切り替える */
@Composable
private fun PlaceSearchBar(
    originName: String?,
    active: Boolean,
    query: String,
    isGeocoding: Boolean,
    onActivate: () -> Unit,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onClearOrigin: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(active) {
        if (active) focusRequester.requestFocus()
    }

    Surface(
        modifier = modifier.height(48.dp),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp),
        ) {
            when {
                active -> {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(8.dp))
                    BasicTextField(
                        value = query,
                        onValueChange = onQueryChange,
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(focusRequester),
                        singleLine = true,
                        textStyle = TextStyle(
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = MaterialTheme.typography.bodyLarge.fontSize,
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                        decorationBox = { inner ->
                            Box(contentAlignment = Alignment.CenterStart) {
                                if (query.isEmpty()) {
                                    Text(
                                        "場所を検索…",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.bodyLarge,
                                    )
                                }
                                inner()
                            }
                        },
                    )
                    if (isGeocoding) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                    } else if (query.isNotEmpty()) {
                        IconButton(
                            onClick = { onQueryChange("") },
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "クリア",
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    } else {
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "閉じる",
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
                originName != null -> {
                    Icon(
                        Icons.Default.Place,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "${originName}周辺",
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onActivate() },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    IconButton(
                        onClick = onClearOrigin,
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "起点をクリア",
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                else -> {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "場所を検索…",
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onActivate() },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }
    }
}

/** ライブラリクラスタ・同一地点複合ピン共通の件数バッジ */
@Composable
private fun ClusterBadge(count: Int) {
    Surface(
        modifier = Modifier.size(40.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        border = BorderStroke(2.dp, MaterialTheme.colorScheme.outline),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = "$count",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/** 単独店舗のピン。ブランドカラーで塗り、複数施策対応の店舗は色ごとに扇形に分割する */
@Composable
private fun StorePin(marker: MapMarker) {
    val selected = marker.selected
    val sizeDp = if (selected) 34.dp else 24.dp
    val colors = marker.colorHexes.map { Color(parseColor(it)) }
        .ifEmpty { listOf(Color(parseColor(null))) }
    val strokeWidth = if (selected) 3.dp else 2.dp
    Box(
        modifier = Modifier
            .size(sizeDp)
            .drawBehind {
                val sw = strokeWidth.toPx()
                val inset = sw / 2f
                val ovalSize = Size(size.width - sw, size.height - sw)
                val ovalOffset = Offset(inset, inset)
                if (colors.size == 1) {
                    drawOval(colors[0], topLeft = ovalOffset, size = ovalSize)
                } else {
                    val sweep = 360f / colors.size
                    var start = -45f
                    colors.forEach { c ->
                        drawArc(
                            c, start, sweep,
                            useCenter = true,
                            topLeft = ovalOffset,
                            size = ovalSize,
                        )
                        start += sweep
                    }
                }
                drawOval(
                    Color.White,
                    topLeft = ovalOffset,
                    size = ovalSize,
                    style = Stroke(width = sw),
                )
            },
    )
}

/**
 * my-location レイヤー(SDK 純正の青ドット)へ位置と向きを流し込む差し込み口。
 * SDK 内蔵の位置購読を使わず、アプリ側の現在地継続購読(userLocation)とコンパスの方位を
 * `Location`(bearing 込み)にして push する。アイコン描画は SDK 純正のまま、
 * 更新タイミングだけをアプリが制御できる。
 */
private class ManualLocationSource : LocationSource {
    private var listener: LocationSource.OnLocationChangedListener? = null

    /** レイヤー有効化前に届いた最新値。activate 時に流して初期表示を待たせない */
    private var last: Location? = null

    override fun activate(l: LocationSource.OnLocationChangedListener) {
        listener = l
        last?.let { l.onLocationChanged(it) }
    }

    override fun deactivate() {
        listener = null
    }

    fun update(location: Location) {
        last = location
        listener?.onLocationChanged(location)
    }
}

/**
 * コンパス(回転ベクトルセンサー)による端末の方位角(真北基準・度)。センサーが無い端末は null。
 * 磁北→真北の偏角(日本では西へ約7〜9度)を現在地から補正する。
 * センサーの細かな揺れで再コンポーズが暴れないよう、約2度以上変わったときだけ値を更新する。
 */
@Composable
private fun rememberCompassHeading(location: MapPoint): Float? {
    val context = LocalContext.current
    var heading by remember { mutableStateOf<Float?>(null) }
    // 偏角は現在地依存だが、移動による変化は無視できるほど小さい(度単位の格子で足りる)
    val declination = remember(location.lat.toInt(), location.lon.toInt()) {
        GeomagneticField(
            location.lat.toFloat(), location.lon.toFloat(), 0f, System.currentTimeMillis(),
        ).declination
    }
    DisposableEffect(declination) {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (sensor == null) {
            heading = null
            return@DisposableEffect onDispose {}
        }
        val listener = object : SensorEventListener {
            private val rotationMatrix = FloatArray(9)
            private val remapped = FloatArray(9)
            private val orientation = FloatArray(3)
            override fun onSensorChanged(event: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                // 画面の向き(縦横回転)分を補正してから方位角を取り出す
                val (axisX, axisY) = when (displayRotation(context)) {
                    ViewSurface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
                    ViewSurface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
                    ViewSurface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
                    else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
                }
                SensorManager.remapCoordinateSystem(rotationMatrix, axisX, axisY, remapped)
                SensorManager.getOrientation(remapped, orientation)
                val azimuth =
                    (Math.toDegrees(orientation[0].toDouble()).toFloat() + declination + 360f) % 360f
                val prev = heading
                // 359度→1度のような 0 跨ぎを正しく差分にする(±180 に折り返す)
                val delta = prev?.let { abs(((azimuth - it + 540f) % 360f) - 180f) }
                if (delta == null || delta >= HEADING_UPDATE_DEG) heading = azimuth
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        onDispose { sensorManager.unregisterListener(listener) }
    }
    return heading
}

private fun displayRotation(context: Context): Int =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        context.display?.rotation ?: ViewSurface.ROTATION_0
    } else {
        @Suppress("DEPRECATION")
        (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
    }

/** 方位更新のしきい値(度)。これ未満のセンサーの揺れは無視して更新の暴れを抑える */
private const val HEADING_UPDATE_DEG = 2f

/** "#RRGGBB" を Int 色に。不正なら既定色(プライマリ相当の緑) */
private fun parseColor(hex: String?): Int =
    hex?.let { runCatching { AndroidColor.parseColor(it) }.getOrNull() } ?: AndroidColor.rgb(0x2E, 0x7D, 0x32)

private fun MapPoint.toLatLng() = LatLng(lat, lon)

private class StoreClusterItem(val marker: MapMarker) : ClusterItem {
    private val position = LatLng(marker.point.lat, marker.point.lon)
    override fun getPosition() = position
    override fun getTitle() = marker.label
    override fun getSnippet(): String? = null
    override fun getZIndex() = if (marker.selected) 1f else 0f
}

/**
 * ライブラリクラスタリング(NonHierarchicalDistanceBasedAlgorithm)が指定ズームで 2 点を
 * 同一クラスタに束ねる目安距離(メートル)。アルゴリズムは整数ズーム z ごとに正規化世界座標
 * (世界幅=1)で span = 100/2^z/256 の探索ボックス(±span/2)を張って点を取り込むため、
 * 実効的なマージ半径は span の半分。メートル換算は赤道周長×cos(緯度)(メルカトル)。
 * 例: ズーム19・緯度36°で約 12m。この距離以内のピン同士はズーム上限でも分解できない。
 */
private fun clusterMergeDistanceMeters(zoom: Float, lat: Double): Double {
    val discreteZoom = zoom.toInt()
    return EARTH_CIRCUMFERENCE_M * cos(Math.toRadians(lat)) * 50.0 / (256.0 * 2.0.pow(discreteZoom))
}

/** 点集合の最大ペア間距離(m)。クラスタ内アイテムの広がりの見積もりに使う(要素数は高々数十) */
private fun maxPairwiseDistanceMeters(points: List<LatLng>): Int {
    var max = 0
    for (i in points.indices) {
        for (j in i + 1 until points.size) {
            val d = GeoMath.distanceMeters(
                points[i].latitude, points[i].longitude,
                points[j].latitude, points[j].longitude,
            )
            if (d > max) max = d
        }
    }
    return max
}

private const val EARTH_CIRCUMFERENCE_M = 40075016.686
private const val MAX_CLUSTER_ZOOM = 19f
private const val SELECTION_MIN_ZOOM = 17f

// Google Maps 標準 POI ラベルの表示はこのズームを境に切り替える。
// 引き(閾値未満)はデフォルト表示のままにし、Google 内部の重要度ランキングに任せる
// (大型公園・百貨店・大病院などのランドマーク級しか出ない)。
// 寄り(閾値以上)ではコンビニ・個人クリニック等の細かい POI が湧いてアプリの店舗ピンと
// 紛らわしくなるため、POI ラベルを全て消す。JSON スタイルには規模・重要度による
// 絞り込みが無い(カテゴリ×on/off のみ)ため、ズーム連動のスタイル切替で代替している。
// visibility:"on" はダークモードの配色を上書きしてラベルが浮くため off 系ルールのみ使う。
private const val POI_SUPPRESS_ZOOM = 18f
private val POI_SUPPRESS_STYLE = MapStyleOptions(
    """[{"featureType":"poi","elementType":"labels","stylers":[{"visibility":"off"}]}]""",
)
