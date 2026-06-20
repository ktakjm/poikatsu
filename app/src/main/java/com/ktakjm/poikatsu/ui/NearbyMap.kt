package com.ktakjm.poikatsu.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMapOptions
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.MapColorScheme
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.ComposeMapColorScheme
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberMarkerState

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
)

/**
 * 対象店舗をピン表示する地図。スクロール/ズームは Google Maps に任せ、Compose 側からは触らない
 * (操作中の再描画でズレないように)。中心を起点に再検索したいときは「このエリアを検索」ボタンを使う。
 *
 * @param center カメラ中心(=検索の起点)。値が変わると再センタリングする
 * @param userLocation 実際の現在地(青ドット)。null なら描画しない
 * @param markers 対象店舗ピン
 * @param initialZoom 検索半径に応じた初期ズーム
 * @param selectedPoint 選択中の店舗。非 null かつ変化したらズームは保ったままその点へカメラを寄せる
 * @param onSearchHere 「このエリアを検索」タップ時、その時点の地図中心を渡す
 * @param onSearchMyLocation 現在地ピン(📍)タップ時。現在地を取り直してその周辺で再検索する
 * @param loadingMessage 再検索中の表示文言。非 null の間は地図・ピンを残したまま進捗を小さく重ね、
 *        検索系ボタンを進捗ピル/無効化に切り替える(全画面ローディングにしない)
 * @param bottomPadding 地図下端の余白。Google ロゴ/著作権表示がボトムシートに隠れないよう、
 *        シートの peek 高さ分だけ持ち上げる(Maps 利用規約: 帰属表示は視認できる必要がある)
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
    loadingMessage: String?,
    modifier: Modifier = Modifier,
    bottomPadding: Dp = 0.dp,
) {
    val context = LocalContext.current
    // 設定のテーマ上書き(システム/ライト/ダーク)も含めた「実際の表示が暗いか」を配色から判定する
    // (OS 設定だけ見ると設定のテーマ上書きに追従しない)。暗いときは Google 純正のダーク配色を使う。
    val darkMode = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val uiSettings = remember {
        // 操作系は自前のボタン(このエリアを検索/現在地で検索)に集約しているので Google の UI は最小化する
        MapUiSettings(
            zoomControlsEnabled = false,
            mapToolbarEnabled = false,
            myLocationButtonEnabled = false,
            compassEnabled = false,
            rotationGesturesEnabled = false,
            tiltGesturesEnabled = false,
        )
    }

    // 初期カメラは「選択中の店舗があればその店、無ければ検索の中心」。詳細画面から戻ったときも
    // ここで最初から正しい場所に置く。move/animate は初回には走らせない(走らせると 0,0 付近からの
    // 高速移動が見えてしまう)。2 回目以降の center / selectedPoint の変化にだけ反応する。
    // この初期カメラは cameraPositionState の初期値と、地図 View 生成時の GoogleMapOptions の両方で使う。
    val initialCamera = CameraPosition.fromLatLngZoom((selectedPoint ?: center).toLatLng(), initialZoom.toFloat())
    val cameraPositionState = rememberCameraPositionState { position = initialCamera }
    var cameraMounted by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { cameraMounted = true }
    // 検索の起点(center)・半径(zoom)が変わったら再センタリング(初回は初期位置で済むので skip)。
    LaunchedEffect(center, initialZoom) {
        if (!cameraMounted) return@LaunchedEffect
        cameraPositionState.move(
            CameraUpdateFactory.newLatLngZoom(center.toLatLng(), initialZoom.toFloat()),
        )
    }
    // 店舗を選択したらズームは保ったままその点へ寄せる(初回は skip。検索の起点 center とは別物)。
    LaunchedEffect(selectedPoint) {
        if (!cameraMounted) return@LaunchedEffect
        if (selectedPoint != null) {
            cameraPositionState.animate(CameraUpdateFactory.newLatLng(selectedPoint.toLatLng()))
        }
    }

    Box(modifier) {
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = cameraPositionState,
            // 地図 View 生成時の初期カメラ・配色を固定する。これをしないと詳細画面から戻って地図が
            // 再生成された際、Google Maps が一瞬デフォルト(0,0=世界地図 / ライト配色)を描いてから
            // 切り替わって見える。
            googleMapOptionsFactory = {
                GoogleMapOptions()
                    .camera(initialCamera)
                    .mapColorScheme(if (darkMode) MapColorScheme.DARK else MapColorScheme.LIGHT)
            },
            uiSettings = uiSettings,
            // 暗いときは Google 純正のダーク配色(建物・駅も視認できる)。自前スタイル JSON は使わない。
            mapColorScheme = if (darkMode) ComposeMapColorScheme.DARK else ComposeMapColorScheme.LIGHT,
            contentPadding = PaddingValues(bottom = bottomPadding),
        ) {
            // 実際の現在地(青系の小さなドット・中心アンカー)
            if (userLocation != null) {
                Marker(
                    state = rememberMarkerState(
                        key = "me:${userLocation.lat},${userLocation.lon}",
                        position = userLocation.toLatLng(),
                    ),
                    icon = remember(userLocation) {
                        dotDescriptor(context, AndroidColor.rgb(0x1A, 0x73, 0xE8), sizeDp = 16f)
                    },
                    anchor = Offset(0.5f, 0.5f),
                    title = "現在地",
                    zIndex = 0f,
                )
            }
            // 対象店舗ピン(ブランドカラー)。選択中は最前面(zIndex)・拡大・白縁強調で描く。
            // リストの増減/並び替えで remember のスロットがズレないよう位置で key する。
            markers.forEach { m ->
                key(m.point.lat, m.point.lon) {
                    Marker(
                        state = rememberMarkerState(position = m.point.toLatLng()),
                        icon = remember(m.colorHexes, m.selected) {
                            storePinDescriptor(
                                context,
                                m.colorHexes,
                                sizeDp = if (m.selected) 34f else 24f,
                                selected = m.selected,
                            )
                        },
                        // 既定の下端中央アンカー(0.5, 1.0)を使う
                        title = m.label,
                        zIndex = if (m.selected) 1f else 0f,
                        onClick = {
                            m.onClick()
                            true
                        },
                    )
                }
            }
        }
        // 再検索中(loadingMessage != null)は「このエリアを検索」を進捗ピルに差し替える。
        // 地図・ピンは残したまま、ここだけで「検索中」を示し再タップも防ぐ(全画面ローディングにしない)。
        if (loadingMessage != null) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.secondaryContainer,
                tonalElevation = 3.dp,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp),
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
        } else {
            FilledTonalButton(
                onClick = {
                    val c = cameraPositionState.position.target
                    onSearchHere(MapPoint(c.latitude, c.longitude))
                },
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp),
            ) {
                Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("このエリアを検索")
            }
        }
        // 現在地で検索: 現在地を取り直し、その周辺で再検索する(完了後カメラも結果=現在地へ戻る)。
        // 地図中心が起点の「このエリアを検索」と対になり、検索の起点を「自分」か「見ている場所」かで選ぶ。
        // 再検索中(loadingMessage != null)は二重起動を避けるため無効化する。
        if (userLocation != null) {
            FilledTonalIconButton(
                onClick = onSearchMyLocation,
                enabled = loadingMessage == null,
                // タッチ領域は M3 最小の 48dp を確保。ボトムシート(peek)に隠れない上部右に置く
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 8.dp).size(48.dp),
            ) {
                Icon(Icons.Default.LocationOn, contentDescription = "現在地で検索")
            }
        }
    }
}

/**
 * 店舗ピンの丸ビットマップ(白縁付き)。対応する発行体が複数あるときは色を扇状に等分して描く。
 * 例: 三井住友(緑)と MUFG(赤)の両対応なら、斜めの境界線で右下=緑・左上=赤のように分かれる。
 * 色が 1 つ(または未指定)のときは単色丸ピン。
 *
 * @param selected 選択中なら白縁を太くして強調する(サイズの拡大は呼び出し側の sizeDp で行う)
 */
private fun storePinDescriptor(
    context: Context,
    colorHexes: List<String>,
    sizeDp: Float,
    selected: Boolean,
): BitmapDescriptor {
    val density = context.resources.displayMetrics.density
    val px = (sizeDp * density).toInt().coerceAtLeast(1)
    val strokePx = (if (selected) 3f else 2f) * density
    // 未指定なら parseColor の既定色(緑)1 色で描く
    val colors = colorHexes.map { parseColor(it) }.ifEmpty { listOf(parseColor(null)) }

    val bitmap = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = AndroidColor.WHITE
        strokeWidth = strokePx
    }
    // 縁が切れないよう線幅の半分だけ内側に描画領域を取る
    val inset = strokePx / 2f
    val rect = RectF(inset, inset, px - inset, px - inset)
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
    return BitmapDescriptorFactory.fromBitmap(bitmap)
}

/** 単色の丸ドット(白縁付き)。現在地ドットなど 1 色で足りる用途に使う */
private fun dotDescriptor(context: Context, color: Int, sizeDp: Float): BitmapDescriptor {
    val density = context.resources.displayMetrics.density
    val px = (sizeDp * density).toInt().coerceAtLeast(1)
    val strokePx = 2f * density
    val bitmap = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val inset = strokePx / 2f
    val rect = RectF(inset, inset, px - inset, px - inset)
    canvas.drawOval(rect, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; this.color = color })
    canvas.drawOval(
        rect,
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            this.color = AndroidColor.WHITE
            strokeWidth = strokePx
        },
    )
    return BitmapDescriptorFactory.fromBitmap(bitmap)
}

/** "#RRGGBB" を Int 色に。不正なら既定色(プライマリ相当の緑) */
private fun parseColor(hex: String?): Int =
    hex?.let { runCatching { AndroidColor.parseColor(it) }.getOrNull() } ?: AndroidColor.rgb(0x2E, 0x7D, 0x32)

private fun MapPoint.toLatLng() = LatLng(lat, lon)
