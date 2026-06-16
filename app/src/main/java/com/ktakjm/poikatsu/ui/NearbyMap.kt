package com.ktakjm.poikatsu.ui

import android.content.Context
import android.graphics.Color as AndroidColor
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

/**
 * 地図表示の「継ぎ目」。地図ライブラリ(osmdroid)固有の型はこのファイルの中だけに閉じ込め、
 * アプリ側は自前の [MapPoint] / [MapMarker] だけを扱う。これにより将来 Google Maps 等へ
 * 表示層だけを差し替える場合も、変更はこのファイルと依存・キー設定に閉じる。
 */
data class MapPoint(val lat: Double, val lon: Double)

data class MapMarker(
    val point: MapPoint,
    val label: String,
    /** ブランドカラー("#RRGGBB")。ロゴ不使用方針のため発行体はピンの色で識別する */
    val colorHex: String?,
    val onClick: () -> Unit,
)

/**
 * 対象店舗をピン表示する地図。スクロール/ズームは osmdroid に任せ、Compose 側からは触らない
 * (操作中の再描画でズレないように)。中心を起点に再検索したいときは「このエリアを検索」ボタンを使う。
 *
 * @param center カメラ中心(=検索の起点)。値が変わると再センタリングする
 * @param userLocation 実際の現在地(青ドット)。null なら描画しない
 * @param markers 対象店舗ピン
 * @param initialZoom 検索半径に応じた初期ズーム
 * @param onSearchHere 「このエリアを検索」タップ時、その時点の地図中心を渡す
 */
@Composable
fun NearbyMap(
    center: MapPoint,
    userLocation: MapPoint?,
    markers: List<MapMarker>,
    initialZoom: Double,
    onSearchHere: (MapPoint) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val mapView = rememberMapViewWithLifecycle()
    // center / zoom / markers が変わったときだけ更新する(無関係な再コンポーズで描画し直さない)
    val renderState = remember { RenderState() }

    Box(modifier) {
        AndroidView(
            // clipToBounds: 地図がスクロール/ズームで自分の領域(300dp)の外に描画してチップ・リストに被るのを防ぐ
            modifier = Modifier.fillMaxSize().clipToBounds(),
            factory = { mapView },
            update = { map ->
                if (renderState.lastCenter != center || renderState.lastZoom != initialZoom) {
                    map.controller.setZoom(initialZoom)
                    map.controller.setCenter(center.toGeoPoint())
                    renderState.lastCenter = center
                    renderState.lastZoom = initialZoom
                }
                // ピンはパン/ズームでは作り直さず、検索結果(markers)が変わったときだけ再描画する
                if (renderState.lastMarkers !== markers) {
                    renderOverlays(map, context, userLocation, markers)
                    renderState.lastMarkers = markers
                }
            },
        )
        FilledTonalButton(
            onClick = {
                val c = mapView.mapCenter
                onSearchHere(MapPoint(c.latitude, c.longitude))
            },
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp),
        ) {
            Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text("このエリアを検索")
        }
    }
}

private class RenderState {
    var lastCenter: MapPoint? = null
    var lastZoom: Double = 0.0
    var lastMarkers: List<MapMarker>? = null
}

/** osmdroid の MapView を Compose の lifecycle に連動させて生成・破棄する */
@Composable
private fun rememberMapViewWithLifecycle(): MapView {
    val context = LocalContext.current
    val mapView = remember {
        Configuration.getInstance().apply {
            // タイルキャッシュを app-private 領域に置く(外部ストレージ権限不要)
            load(context, context.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
            // OSM タイル利用規約: User-Agent の設定は必須
            userAgentValue = context.packageName
        }
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            setHorizontalMapRepetitionEnabled(false)
            setVerticalMapRepetitionEnabled(false)
        }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onDetach()
        }
    }
    return mapView
}

/** 現在地マーカー + 店舗ピンを描画し直す(検索結果が変わるたびに呼ばれる) */
private fun renderOverlays(
    map: MapView,
    context: Context,
    userLocation: MapPoint?,
    markers: List<MapMarker>,
) {
    map.overlays.clear()

    // 実際の現在地(青系の小さなドット)
    if (userLocation != null) {
        val here = Marker(map).apply {
            position = userLocation.toGeoPoint()
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            icon = pinDrawable(context, AndroidColor.rgb(0x1A, 0x73, 0xE8), sizeDp = 16f)
            title = "現在地"
            setInfoWindow(null)
        }
        map.overlays.add(here)
    }

    // 対象店舗ピン(ブランドカラー)
    markers.forEach { m ->
        val marker = Marker(map).apply {
            position = m.point.toGeoPoint()
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            icon = pinDrawable(context, parseColor(m.colorHex), sizeDp = 24f)
            title = m.label
            setOnMarkerClickListener { _, _ ->
                m.onClick()
                true
            }
        }
        map.overlays.add(marker)
    }

    map.invalidate()
}

/** ブランドカラーの丸ピン Drawable を生成(白縁付き) */
private fun pinDrawable(context: Context, color: Int, sizeDp: Float): Drawable {
    val density = context.resources.displayMetrics.density
    val px = (sizeDp * density).toInt()
    return GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        setStroke((2 * density).toInt(), AndroidColor.WHITE)
        setSize(px, px)
        setBounds(0, 0, px, px)
    }
}

/** "#RRGGBB" を Int 色に。不正なら既定色(プライマリ相当の緑) */
private fun parseColor(hex: String?): Int =
    hex?.let { runCatching { AndroidColor.parseColor(it) }.getOrNull() } ?: AndroidColor.rgb(0x2E, 0x7D, 0x32)

private fun MapPoint.toGeoPoint() = GeoPoint(lat, lon)
