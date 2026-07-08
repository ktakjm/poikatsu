package com.ktakjm.poikatsu.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.tasks.Task
import kotlin.coroutines.resume
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 現在地の取得。Play Services の Fused Location Provider(FLP)を使う。
 * FLP は端末全体で共有される位置キャッシュとセンサー融合を持ち、フレームワーク標準の
 * LocationManager の単発測位(GPS コールドスタートで数秒〜数十秒)より速く新鮮な位置が
 * 取れる(Google Maps と同じ仕組み)。地図描画で既に play-services-maps に依存しているため
 * GMS 依存は増えず、位置情報取得は課金対象 API でもない(docs/licenses.md)。
 */
class LocationProvider(private val context: Context) {

    private val client by lazy { LocationServices.getFusedLocationProviderClient(context) }

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /**
     * FLP のキャッシュ位置(OS・他アプリの測位も含む端末全体の最新値)。即座に返るので
     * 2段階表示の1段目(まず地図を出す)に使う。maxAgeMillis より古い位置は「少し前の場所」が
     * 出る事故のもとなので捨てて null を返す(その場合は currentLocation の結果を待つ)。
     */
    @SuppressLint("MissingPermission")
    suspend fun lastLocation(maxAgeMillis: Long = LAST_LOCATION_MAX_AGE_MS): Location? {
        if (!hasPermission()) return null
        val location = client.lastLocation.awaitOrNull() ?: return null
        // 鮮度は壁時計(getTime)でなく起動からの単調時刻で判定する(時刻合わせの影響を受けない)
        val ageMillis = (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000
        return location.takeIf { ageMillis in 0..maxAgeMillis }
    }

    /** 新鮮な単発測位(2段階表示の2段目)。屋内等で測位できない・タイムアウト時は null */
    @SuppressLint("MissingPermission")
    suspend fun currentLocation(): Location? {
        if (!hasPermission()) return null
        val cts = CancellationTokenSource()
        try {
            return withTimeoutOrNull(CURRENT_LOCATION_TIMEOUT_MS) {
                val request = CurrentLocationRequest.Builder()
                    .setPriority(Priority.PRIORITY_BALANCED_POWER_ACCURACY)
                    .build()
                client.getCurrentLocation(request, cts.token).awaitOrNull()
            }
        } finally {
            // タイムアウト・コルーチンキャンセル時に FLP 側の測位も止める
            cts.cancel()
        }
    }

    /**
     * 現在地の継続購読(「近く」タブ表示中の青ドット追従用)。collect のキャンセルで購読解除。
     * BALANCED 優先度(Wi-Fi/基地局主体、GPS は必要時のみ)+数秒間隔+最小移動距離つきなので
     * 電池消費は地図アプリとして常識的な範囲に収まる。
     */
    @SuppressLint("MissingPermission")
    fun locationUpdates(): Flow<Location> = callbackFlow {
        if (!hasPermission()) {
            close()
            return@callbackFlow
        }
        val request = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, UPDATE_INTERVAL_MS)
            .setMinUpdateDistanceMeters(UPDATE_MIN_DISTANCE_M)
            .build()
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { trySend(it) }
            }
        }
        client.requestLocationUpdates(request, callback, Looper.getMainLooper())
        awaitClose { client.removeLocationUpdates(callback) }
    }

    /**
     * Play Services の Task を suspend で待ち、失敗・キャンセルは null に落とす
     * (kotlinx-coroutines-play-services の依存追加を避ける最小実装)
     */
    private suspend fun <T> Task<T>.awaitOrNull(): T? = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { if (cont.isActive) cont.resume(it) }
        addOnFailureListener { if (cont.isActive) cont.resume(null) }
        addOnCanceledListener { if (cont.isActive) cont.resume(null) }
    }

    companion object {
        /** キャッシュ位置をそのまま1段目表示に使う鮮度の上限。これより古いと場所ズレを体感しうる */
        private const val LAST_LOCATION_MAX_AGE_MS = 2 * 60_000L
        private const val CURRENT_LOCATION_TIMEOUT_MS = 10_000L

        /** 継続購読の更新間隔と最小移動距離(青ドットの追従用。これ未満の移動は通知されない) */
        private const val UPDATE_INTERVAL_MS = 3_000L
        private const val UPDATE_MIN_DISTANCE_M = 5f
    }
}
