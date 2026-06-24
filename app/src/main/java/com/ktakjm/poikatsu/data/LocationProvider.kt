package com.ktakjm.poikatsu.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 現在地を1回だけ取得する。Google Play Services(プロプライエタリ)は使わず、
 * フレームワーク標準の LocationManager のみで実装する(依存追加なし)。
 */
class LocationProvider(private val context: Context) {

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    suspend fun currentLocation(): Location? {
        if (!hasPermission()) return null
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        // GPS を優先し、なければ NETWORK(Wi-Fi/基地局)にフォールバック。
        // エミュレータの位置注入は GPS プロバイダにのみ効くため GPS が先。
        val provider = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .firstOrNull { manager.isProviderEnabled(it) } ?: return null

        val fresh = withTimeoutOrNull(15_000) {
            suspendCancellableCoroutine { cont ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    manager.getCurrentLocation(provider, null, context.mainExecutor) { location ->
                        if (cont.isActive) cont.resume(location)
                    }
                } else {
                    @Suppress("DEPRECATION")
                    manager.requestSingleUpdate(
                        provider,
                        { location -> if (cont.isActive) cont.resume(location) },
                        Looper.getMainLooper(),
                    )
                }
            }
        }
        return fresh ?: runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
    }
}
