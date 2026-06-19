package com.ktakjm.poikatsu.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.luminance
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
import org.osmdroid.views.overlay.TilesOverlay

/**
 * 地図表示の「継ぎ目」。地図ライブラリ(osmdroid)固有の型はこのファイルの中だけに閉じ込め、
 * アプリ側は自前の [MapPoint] / [MapMarker] だけを扱う。これにより将来 Google Maps 等へ
 * 表示層だけを差し替える場合も、変更はこのファイルと依存・キー設定に閉じる。
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
)

/**
 * 対象店舗をピン表示する地図。スクロール/ズームは osmdroid に任せ、Compose 側からは触らない
 * (操作中の再描画でズレないように)。中心を起点に再検索したいときは「このエリアを検索」ボタンを使う。
 *
 * @param center カメラ中心(=検索の起点)。値が変わると再センタリングする
 * @param userLocation 実際の現在地(青ドット)。null なら描画しない
 * @param markers 対象店舗ピン
 * @param initialZoom 検索半径に応じた初期ズーム
 * @param selectedPoint 選択中の店舗。非 null かつ変化したらズームは保ったままその点へカメラを寄せる
 * @param onSearchHere 「このエリアを検索」タップ時、その時点の地図中心を渡す
 * @param onSearchMyLocation 現在地ピン(📍)タップ時。現在地を取り直してその周辺で再検索する
 */
@Composable
fun NearbyMap(
    center: MapPoint,
    userLocation: MapPoint?,
    markers: List<MapMarker>,
    initialZoom: Double,
    selectedPoint: MapPoint?,
    onSearchHere: (MapPoint) -> Unit,
    onSearchMyLocation: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val mapView = rememberMapViewWithLifecycle()
    // center / zoom / markers が変わったときだけ更新する(無関係な再コンポーズで描画し直さない)
    val renderState = remember { RenderState() }
    // OSM 標準タイルは描画済み画像なのでアプリのテーマに追従しない。
    // 設定のテーマ上書き(システム/ライト/ダーク)も含めた「実際の表示が暗いか」を
    // MaterialTheme の配色から判定する(OS 設定だけ見ると設定のテーマ上書きに追従しない)。
    val darkMode = MaterialTheme.colorScheme.surface.luminance() < 0.5f

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
                // 店舗を選択したらズームは保ったままその点へ寄せる(検索の起点 center とは別物)
                if (renderState.lastSelected != selectedPoint) {
                    if (selectedPoint != null) map.controller.animateTo(selectedPoint.toGeoPoint())
                    renderState.lastSelected = selectedPoint
                }
                // ピンはパン/ズームでは作り直さず、検索結果・選択状態(markers)が変わったときだけ再描画する
                if (renderState.lastMarkers !== markers) {
                    renderOverlays(map, context, userLocation, markers)
                    renderState.lastMarkers = markers
                }
                // ダークモード切替時だけフィルタを差し替える(毎フレーム invalidate しない)
                if (renderState.lastDarkMode != darkMode) {
                    map.overlayManager.tilesOverlay.setColorFilter(
                        if (darkMode) TilesOverlay.INVERT_COLORS else null,
                    )
                    renderState.lastDarkMode = darkMode
                    map.invalidate()
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
        // 現在地で検索: 現在地を取り直し、その周辺で再検索する(完了後カメラも結果=現在地へ戻る)。
        // 地図中心が起点の「このエリアを検索」と対になり、検索の起点を「自分」か「見ている場所」かで選ぶ。
        if (userLocation != null) {
            FilledTonalIconButton(
                onClick = onSearchMyLocation,
                // タッチ領域は M3 最小の 48dp を確保。ボトムシート(peek)に隠れない上部右に置く
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 8.dp).size(48.dp),
            ) {
                Icon(Icons.Default.LocationOn, contentDescription = "現在地で検索")
            }
        }
    }
}

private class RenderState {
    var lastCenter: MapPoint? = null
    var lastZoom: Double = 0.0
    var lastMarkers: List<MapMarker>? = null
    var lastDarkMode: Boolean? = null
    var lastSelected: MapPoint? = null
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

    // 対象店舗ピン(ブランドカラー)。選択中ピンは最前面に来るよう最後に描く(sortedBy で false→true 順)
    markers.sortedBy { it.selected }.forEach { m ->
        val marker = Marker(map).apply {
            position = m.point.toGeoPoint()
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            icon = storePinDrawable(context, m.colorHexes, sizeDp = if (m.selected) 34f else 24f, selected = m.selected)
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

/** 単色の丸ピン Drawable を生成(白縁付き)。現在地ドットなど 1 色で足りる用途に使う */
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

/**
 * 店舗ピンの丸 Drawable(白縁付き)。対応する発行体が複数あるときは色を扇状に等分して描く。
 * 例: 三井住友(緑)と MUFG(赤)の両対応なら、斜めの境界線で右下=緑・左上=赤のように分かれる。
 * 色が 1 つ(または未指定)のときは従来どおりの単色丸ピン。
 *
 * osmdroid の Marker は icon の intrinsic サイズで描画範囲とタップ判定を決めるため、
 * getIntrinsicWidth/Height を必ず返す(返さないと 0px 扱いになり、点になってクリックもできない)。
 *
 * @param selected 選択中なら白縁を太くして強調する(サイズの拡大は呼び出し側の sizeDp で行う)
 */
private fun storePinDrawable(context: Context, colorHexes: List<String>, sizeDp: Float, selected: Boolean = false): Drawable {
    val density = context.resources.displayMetrics.density
    val px = (sizeDp * density).toInt()
    val strokePx = (if (selected) 3f else 2f) * density
    // 未指定なら parseColor の既定色(緑)1 色で描く
    val colors = colorHexes.map { parseColor(it) }.ifEmpty { listOf(parseColor(null)) }
    return object : Drawable() {
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = AndroidColor.WHITE
            strokeWidth = strokePx
        }

        override fun getIntrinsicWidth() = px
        override fun getIntrinsicHeight() = px

        override fun draw(canvas: Canvas) {
            // 縁が切れないよう線幅の半分だけ内側に描画領域を取る
            val inset = strokePx / 2f
            val rect = RectF(
                bounds.left + inset,
                bounds.top + inset,
                bounds.right - inset,
                bounds.bottom - inset,
            )
            if (colors.size == 1) {
                fillPaint.color = colors[0]
                canvas.drawOval(rect, fillPaint)
            } else {
                val sweep = 360f / colors.size
                var start = -45f // 斜めの境界にするため右上(-45°)を起点に時計回りで等分
                colors.forEach { c ->
                    fillPaint.color = c
                    canvas.drawArc(rect, start, sweep, true, fillPaint)
                    start += sweep
                }
            }
            canvas.drawOval(rect, strokePaint)
        }

        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit

        @Suppress("DEPRECATION")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }.apply { setBounds(0, 0, px, px) }
}

/** "#RRGGBB" を Int 色に。不正なら既定色(プライマリ相当の緑) */
private fun parseColor(hex: String?): Int =
    hex?.let { runCatching { AndroidColor.parseColor(it) }.getOrNull() } ?: AndroidColor.rgb(0x2E, 0x7D, 0x32)

private fun MapPoint.toGeoPoint() = GeoPoint(lat, lon)
